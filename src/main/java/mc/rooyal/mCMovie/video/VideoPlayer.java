package mc.rooyal.mCMovie.video;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import mc.rooyal.mCMovie.MCMovie;
import mc.rooyal.mCMovie.event.ScreenVideoChangeEvent;
import mc.rooyal.mCMovie.event.ScreenVideoEndEvent;
import mc.rooyal.mCMovie.event.ScreenVideoStartEvent;
import mc.rooyal.mCMovie.screen.MCMovieMapRenderer;
import mc.rooyal.mCMovie.screen.Screen;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class VideoPlayer {

    private static final int AUDIO_SAMPLE_RATE  = 48000;
    private static final int AUDIO_CHUNK_SAMPLES = 960;   // 20ms @ 48kHz mono
    private static final int AUDIO_CHUNK_BYTES   = AUDIO_CHUNK_SAMPLES * 2;
    private static final int AUDIO_QUEUE_CAPACITY = 40;

    /**
     * How many pre-converted frames to buffer between the video thread and the
     * game-tick scheduler. Large enough to absorb a full HLS segment burst
     * (typically 2-4 s × 20 fps = 40-80 frames) so the game tick scheduler can
     * drain them smoothly at exactly one frame per tick.
     */
    private static final int FRAME_QUEUE_CAPACITY = 120;

    /**
     * Pre-roll for HTTP/HLS streams: buffer this many frames before starting.
     * 60 frames = 3 s at 20 fps — enough to absorb a 2-3 s HLS segment gap.
     */
    private static final int BUFFER_THRESHOLD_HTTP = 60;

    /**
     * Pre-roll for local files: minimal — just enough to let the palette cache
     * warm up. The pipe is rate-limited with {@code -re} so no burst buffering needed.
     */
    private static final int BUFFER_THRESHOLD_LOCAL = 4;

    /**
     * Maximum time (ms) to wait for the buffer to fill before starting anyway.
     */
    private static final long BUFFER_TIMEOUT_MS = 8_000;

    private final Screen screen;
    private final MCMovie plugin;

    private volatile boolean running = false;

    // Pre-roll gating: advanceFrame() does nothing until enough frames are queued
    // (or the timeout fires), preventing choppy startup on HLS streams.
    private volatile boolean playbackReady   = false;
    private volatile long    bufferStartMs   = 0;
    private volatile int     bufferThreshold = BUFFER_THRESHOLD_HTTP;

    private Process videoProcess = null;
    private Process audioProcess = null;

    private Thread videoThread = null;
    private Thread audioThread = null;

    // Video frames pre-converted to Minecraft map-palette bytes.
    // Each element is one complete frame: byte[tileCount][128*128].
    // The video thread produces; the game-tick scheduler consumes (advanceFrame).
    private final LinkedBlockingQueue<byte[][]> frameQueue =
            new LinkedBlockingQueue<>(FRAME_QUEUE_CAPACITY);

    private final LinkedBlockingQueue<short[]> audioQueue =
            new LinkedBlockingQueue<>(AUDIO_QUEUE_CAPACITY);

    private AudioPlayer audioPlayer = null;
    private LocationalAudioChannel audioChannel = null;

    public VideoPlayer(Screen screen, MCMovie plugin) {
        this.screen = screen;
        this.plugin = plugin;
    }

    public void play(String urlOrFile) {
        if (running) {
            stop();
        }

        String resolvedInput;
        if (StreamResolver.isLocalFile(urlOrFile)) {
            resolvedInput = StreamResolver.resolveLocalPath(
                    urlOrFile, new File(plugin.getDataFolder(), "videos"));
        } else {
            resolvedInput = urlOrFile;
        }

        ScreenVideoChangeEvent changeEvent =
                new ScreenVideoChangeEvent(screen, screen.getCurrentUrl(), resolvedInput);
        Bukkit.getPluginManager().callEvent(changeEvent);
        if (changeEvent.isCancelled()) return;

        screen.setCurrentUrl(resolvedInput);
        running = true;
        screen.setPlaying(true);

        final String finalInput = resolvedInput;
        final boolean isHttp = finalInput.startsWith("http://") || finalInput.startsWith("https://");
        bufferThreshold = isHttp ? BUFFER_THRESHOLD_HTTP : BUFFER_THRESHOLD_LOCAL;

        startVideoThread(finalInput, isHttp);
        startAudioThread(finalInput, isHttp);

        Bukkit.getScheduler().runTask(plugin, () ->
                Bukkit.getPluginManager().callEvent(new ScreenVideoStartEvent(screen, finalInput)));
    }

    public void stop() {
        running = false;

        if (audioPlayer != null) {
            try { audioPlayer.stopPlaying(); } catch (Exception ignored) {}
            audioPlayer = null;
        }
        audioChannel = null;

        destroyProcess(videoProcess);
        destroyProcess(audioProcess);
        videoProcess = null;
        audioProcess = null;

        interruptThread(videoThread);
        interruptThread(audioThread);
        videoThread = null;
        audioThread = null;

        frameQueue.clear();
        audioQueue.clear();
        playbackReady   = false;
        bufferStartMs   = 0;
        bufferThreshold = BUFFER_THRESHOLD_HTTP;

        screen.setPlaying(false);
        Bukkit.getScheduler().runTask(plugin, () ->
                Bukkit.getPluginManager().callEvent(new ScreenVideoEndEvent(screen)));
    }

    /**
     * Called by the main-thread scheduler once per game tick.
     *
     * <p>During the pre-roll phase this returns {@code false} and does nothing,
     * so the caller skips {@code sendMap()} entirely — zero packet overhead while
     * buffering.  Once the gate opens it pops one frame per tick, updates all tile
     * renderers atomically, and returns {@code true} so the caller knows to push
     * the updated canvas to players.  When the queue is temporarily empty (HLS
     * segment gap) it holds the last frame and returns {@code false}, which again
     * suppresses all {@code sendMap()} calls — no wasted packets during stalls.
     *
     * @return {@code true} if a new frame was consumed and maps should be sent
     */
    public boolean advanceFrame() {
        if (!playbackReady) {
            if (bufferStartMs == 0) bufferStartMs = System.currentTimeMillis();

            int queued = frameQueue.size();
            boolean timedOut = System.currentTimeMillis() - bufferStartMs > BUFFER_TIMEOUT_MS;
            if (queued < bufferThreshold && !timedOut) return false; // still pre-rolling

            playbackReady = true;
            plugin.getLogger().info("[MCMovie] Buffered " + queued + " frames"
                    + (timedOut ? " (timeout)" : "") + ", starting playback for screen=" + screen.getId());
        }

        byte[][] frame = frameQueue.poll();
        if (frame == null) return false; // queue empty (HLS gap) — hold last frame, skip sendMap

        List<MCMovieMapRenderer> renderers = screen.getRenderers();
        for (int i = 0; i < frame.length && i < renderers.size(); i++) {
            renderers.get(i).updateFrame(frame[i]);
        }
        return true;
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private void destroyProcess(Process p) {
        if (p != null && p.isAlive()) p.destroyForcibly();
    }

    private void interruptThread(Thread t) {
        if (t != null && t.isAlive()) t.interrupt();
    }

    private void startVideoThread(String input, boolean isHttpInput) {
        int frameW     = screen.getWidthMaps()  * 128;
        int frameH     = screen.getHeightMaps() * 128;
        int frameBytes = frameW * frameH * 3;
        int wMaps      = screen.getWidthMaps();
        int hMaps      = screen.getHeightMaps();
        int tileCount  = wMaps * hMaps;

        videoThread = new Thread(() -> {
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add(plugin.getBinaryManager().getFfmpegPath());

                if (isHttpInput) {
                    // Live HLS/HTTP stream: start at the live edge, minimise internal
                    // buffering so frames reach us with the lowest possible latency.
                    cmd.add("-fflags");       cmd.add("+nobuffer+discardcorrupt");
                    cmd.add("-flags");        cmd.add("low_delay");
                    cmd.add("-live_start_index"); cmd.add("-1"); // newest segment only
                } else {
                    // Local file: pace output to real-time so we don't flood the pipe
                    // and blow up the queue with thousands of pre-converted frames.
                    cmd.add("-re");
                }

                cmd.add("-i"); cmd.add(input);
                cmd.add("-vf"); cmd.add("scale=" + frameW + ":" + frameH + ",fps=20");
                cmd.add("-f");        cmd.add("rawvideo");
                cmd.add("-pix_fmt"); cmd.add("rgb24");
                cmd.add("-an");
                cmd.add("pipe:1");
                plugin.debug("Video ffmpeg cmd: " + String.join(" ", cmd));

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(false);
                videoProcess = pb.start();
                plugin.debug("Video ffmpeg PID=" + videoProcess.pid()
                        + " screen=" + screen.getId()
                        + " frameBytes=" + frameBytes + " tiles=" + tileCount);

                InputStream in = videoProcess.getInputStream();
                int frameCount = 0;

                while (running) {
                    byte[] raw = readExact(in, frameBytes);
                    if (raw == null) {
                        plugin.debug("Video EOF after " + frameCount
                                + " frames, screen=" + screen.getId());
                        break;
                    }
                    if (frameCount == 0)
                        plugin.debug("First frame received, screen=" + screen.getId());
                    frameCount++;

                    // Convert all tiles using the cached palette matcher (no BufferedImage,
                    // no per-frame color-matching overhead once the cache is warm).
                    byte[][] tiles = new byte[tileCount][];
                    for (int row = 0; row < hMaps; row++) {
                        for (int col = 0; col < wMaps; col++) {
                            byte[] tileBytes = new byte[128 * 128];
                            for (int py = 0; py < 128; py++) {
                                for (int px = 0; px < 128; px++) {
                                    int srcIdx = ((row * 128 + py) * frameW
                                            + (col * 128 + px)) * 3;
                                    tileBytes[py * 128 + px] = MinecraftMapPalette.matchColor(
                                            raw[srcIdx]     & 0xFF,
                                            raw[srcIdx + 1] & 0xFF,
                                            raw[srcIdx + 2] & 0xFF);
                                }
                            }
                            tiles[row * wMaps + col] = tileBytes;
                        }
                    }

                    // Offer to queue; drop the frame if the queue is full rather than blocking.
                    if (!frameQueue.offer(tiles)) {
                        plugin.debug("Frame queue full, dropping frame " + frameCount
                                + " for screen=" + screen.getId());
                    }
                }
            } catch (IOException e) {
                if (running)
                    plugin.getLogger().log(Level.WARNING,
                            "[MCMovie] Video thread error, screen=" + screen.getId(), e);
            } finally {
                if (running) {
                    running = false;
                    screen.setPlaying(false);
                    Bukkit.getScheduler().runTask(plugin, () ->
                            Bukkit.getPluginManager().callEvent(new ScreenVideoEndEvent(screen)));
                }
            }
        }, "MCMovie-Video-" + screen.getId());
        videoThread.setDaemon(true);
        videoThread.start();
    }

    private void startAudioThread(String input, boolean isHttpInput) {
        if (!plugin.isVoiceChatEnabled()) return;

        VoicechatServerApi api = plugin.getVoicechatServerApi();
        if (api == null) return;

        Location center = screen.getCenter();
        try {
            audioChannel = api.createLocationalAudioChannel(
                    UUID.randomUUID(),
                    api.fromServerLevel(center.getWorld()),
                    api.createPosition(center.getX(), center.getY(), center.getZ())
            );
            audioChannel.setDistance(32f);

            audioPlayer = api.createAudioPlayer(audioChannel, api.createEncoder(), () -> {
                if (!running) return null;
                try {
                    return audioQueue.poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            });
            audioPlayer.startPlaying();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "[MCMovie] Failed to create audio channel", e);
            return;
        }

        audioThread = new Thread(() -> {
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add(plugin.getBinaryManager().getFfmpegPath());

                if (isHttpInput) {
                    cmd.add("-fflags");       cmd.add("+nobuffer+discardcorrupt");
                    cmd.add("-flags");        cmd.add("low_delay");
                    cmd.add("-live_start_index"); cmd.add("-1");
                } else {
                    cmd.add("-re");
                }

                cmd.add("-i"); cmd.add(input);
                cmd.add("-vn");
                cmd.add("-f");   cmd.add("s16le");
                cmd.add("-ar");  cmd.add(String.valueOf(AUDIO_SAMPLE_RATE));
                cmd.add("-ac");  cmd.add("1");
                cmd.add("pipe:1");
                plugin.debug("Audio ffmpeg cmd: " + String.join(" ", cmd));

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(false);
                audioProcess = pb.start();
                plugin.debug("Audio ffmpeg PID=" + audioProcess.pid()
                        + " screen=" + screen.getId());

                InputStream in = audioProcess.getInputStream();
                while (running) {
                    byte[] raw = readExact(in, AUDIO_CHUNK_BYTES);
                    if (raw == null) break;
                    short[] samples = new short[AUDIO_CHUNK_SAMPLES];
                    ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer().get(samples);
                    audioQueue.offer(samples);
                }
            } catch (IOException e) {
                if (running)
                    plugin.getLogger().log(Level.WARNING,
                            "[MCMovie] Audio thread error, screen=" + screen.getId(), e);
            }
        }, "MCMovie-Audio-" + screen.getId());
        audioThread.setDaemon(true);
        audioThread.start();
    }

    /** Reads exactly {@code n} bytes. Returns {@code null} on EOF. */
    private byte[] readExact(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r == -1) return null;
            off += r;
        }
        return buf;
    }

    public boolean isRunning() { return running; }

    /** Exposed for debug/status only. */
    public int getFrameQueueSize() { return frameQueue.size(); }
}
