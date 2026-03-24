package mc.rooyal.mCMovie.video;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;

public class VideoPlayer {

    private static final int AUDIO_SAMPLE_RATE   = 48000;
    private static final int AUDIO_CHUNK_SAMPLES = 960;   // 20ms @ 48kHz mono
    private static final int AUDIO_CHUNK_BYTES   = AUDIO_CHUNK_SAMPLES * 2;
    private static final long AUDIO_FRAME_NS     = 20_000_000L; // 20ms in nanoseconds

    /**
     * How many pre-converted frames to buffer between the video thread and the
     * game-tick scheduler.
     */
    private static final int FRAME_QUEUE_CAPACITY = 120;

    /**
     * Pre-roll for HTTP/HLS streams: buffer this many frames before starting.
     */
    private static final int BUFFER_THRESHOLD_HTTP = 60;

    /**
     * Pre-roll for local files: minimal.
     */
    private static final int BUFFER_THRESHOLD_LOCAL = 4;

    /**
     * Maximum time (ms) to wait for the buffer to fill before starting anyway.
     */
    private static final long BUFFER_TIMEOUT_MS = 8_000;

    private final Screen screen;
    private final MCMovie plugin;

    private volatile boolean running = false;

    private volatile boolean playbackReady   = false;
    private volatile long    bufferStartMs   = 0;
    private volatile int     bufferThreshold = BUFFER_THRESHOLD_HTTP;

    private Process videoProcess = null;
    private Process audioProcess = null;

    private Thread videoThread = null;
    private Thread audioThread = null;

    private final LinkedBlockingQueue<byte[][]> frameQueue =
            new LinkedBlockingQueue<>(FRAME_QUEUE_CAPACITY);

    // Audio channel — set before audio thread starts, cleared in stop()
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

        // Flush and null the audio channel — the audio thread's finally block
        // will also try to flush via its local reference, which is harmless.
        if (audioChannel != null) {
            try { audioChannel.flush(); } catch (Exception ignored) {}
            audioChannel = null;
        }

        destroyProcess(videoProcess);
        destroyProcess(audioProcess);
        videoProcess = null;
        audioProcess = null;

        interruptThread(videoThread);
        interruptThread(audioThread);
        videoThread = null;
        audioThread = null;

        frameQueue.clear();
        playbackReady   = false;
        bufferStartMs   = 0;
        bufferThreshold = BUFFER_THRESHOLD_HTTP;

        screen.setPlaying(false);
        Bukkit.getScheduler().runTask(plugin, () ->
                Bukkit.getPluginManager().callEvent(new ScreenVideoEndEvent(screen)));
    }

    /**
     * Called by the main-thread scheduler once per game tick.
     * @return {@code true} if a new frame was consumed and maps should be sent
     */
    public boolean advanceFrame() {
        if (!playbackReady) {
            if (bufferStartMs == 0) bufferStartMs = System.currentTimeMillis();

            int queued = frameQueue.size();
            boolean timedOut = System.currentTimeMillis() - bufferStartMs > BUFFER_TIMEOUT_MS;
            if (queued < bufferThreshold && !timedOut) return false;

            playbackReady = true;
            plugin.getLogger().info("[MCMovie] Buffered " + queued + " frames"
                    + (timedOut ? " (timeout)" : "") + ", starting playback for screen=" + screen.getId());
        }

        byte[][] frame = frameQueue.poll();
        if (frame == null) return false;

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
                String ffmpegBin = plugin.getBinaryManager().getFfmpegPath();
                if (ffmpegBin == null) {
                    plugin.getLogger().severe("[MCMovie] ffmpeg is not available — video will not play. Check server logs for installation instructions.");
                    return;
                }
                List<String> cmd = new ArrayList<>();
                cmd.add(ffmpegBin);

                if (isHttpInput) {
                    cmd.add("-fflags");       cmd.add("+nobuffer+discardcorrupt");
                    cmd.add("-flags");        cmd.add("low_delay");
                    cmd.add("-live_start_index"); cmd.add("-1");
                } else {
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
                        plugin.debug("First video frame received, screen=" + screen.getId());
                    frameCount++;

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

                    if (isHttpInput) {
                        // HLS: drop frame if queue is full — never block on a live stream
                        if (!frameQueue.offer(tiles)) {
                            plugin.debug("Frame queue full, dropping frame " + frameCount
                                    + " for screen=" + screen.getId());
                        }
                    } else {
                        // Local file: block until space is available.
                        // This is the rate limiter — naturally caps the producer to the 20fps consumer.
                        try {
                            frameQueue.put(tiles);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
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
        if (!plugin.isVoiceChatEnabled()) {
            plugin.getLogger().info("[MCMovie] Audio skipped: SimpleVoiceChat not enabled, screen=" + screen.getId());
            return;
        }

        VoicechatServerApi api = plugin.getVoicechatServerApi();
        if (api == null) {
            plugin.getLogger().warning("[MCMovie] Audio skipped: voicechatServerApi is null (server started event not fired?), screen=" + screen.getId());
            return;
        }

        Location center = screen.getCenter();
        plugin.debug("[MCMovie] Creating audio channel at " + center.toVector() + " screen=" + screen.getId());

        audioChannel = api.createLocationalAudioChannel(
                UUID.randomUUID(),
                api.fromServerLevel(center.getWorld()),
                api.createPosition(center.getX(), center.getY(), center.getZ())
        );

        if (audioChannel == null) {
            plugin.getLogger().warning("[MCMovie] createLocationalAudioChannel returned null — voicechat server not ready? screen=" + screen.getId());
            return;
        }

        audioChannel.setDistance(32f);
        plugin.getLogger().info("[MCMovie] Audio channel created id=" + audioChannel.getId() + " screen=" + screen.getId());

        // Capture local references for the threads — stop() may null the fields
        final LocationalAudioChannel channel = audioChannel;

        // PCM queue between the ffmpeg reader and the timed sender.
        // ~100 frames = 2 s of buffer, enough to absorb HLS segment fetch gaps.
        final LinkedBlockingQueue<short[]> pcmQueue = new LinkedBlockingQueue<>(100);
        final short[] SILENCE = new short[AUDIO_CHUNK_SAMPLES]; // all zeros

        // Reader thread: pulls raw PCM from ffmpeg and fills pcmQueue.
        Thread readerThread = new Thread(() -> {
            String ffmpegBin = plugin.getBinaryManager().getFfmpegPath();
            if (ffmpegBin == null) {
                plugin.getLogger().severe("[MCMovie] ffmpeg is not available — audio will not play. Check server logs for installation instructions.");
                return;
            }
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegBin);

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

            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(false);
                audioProcess = pb.start();
                plugin.getLogger().info("[MCMovie] Audio ffmpeg PID=" + audioProcess.pid()
                        + " screen=" + screen.getId());

                InputStream in = audioProcess.getInputStream();
                long frameCount = 0;
                while (running) {
                    byte[] raw = readExact(in, AUDIO_CHUNK_BYTES);
                    if (raw == null) {
                        plugin.debug("[MCMovie] Audio EOF after " + frameCount + " frames, screen=" + screen.getId());
                        break;
                    }
                    if (frameCount == 0)
                        plugin.getLogger().info("[MCMovie] First audio frame received, screen=" + screen.getId());
                    frameCount++;

                    short[] samples = new short[AUDIO_CHUNK_SAMPLES];
                    ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer().get(samples);

                    if (!pcmQueue.offer(samples)) {
                        plugin.debug("[MCMovie] Audio PCM queue full, dropping frame " + frameCount
                                + " screen=" + screen.getId());
                    }
                }
            } catch (IOException e) {
                if (running)
                    plugin.getLogger().log(Level.WARNING,
                            "[MCMovie] Audio reader error, screen=" + screen.getId(), e);
            }
        }, "MCMovie-AudioReader-" + screen.getId());
        readerThread.setDaemon(true);

        // Sender thread: encodes PCM and sends one Opus frame every 20 ms.
        // Sends silence when the queue is empty (HLS segment gap) instead of stalling.
        audioThread = new Thread(() -> {
            OpusEncoder encoder = api.createEncoder(OpusEncoderMode.AUDIO);
            plugin.debug("[MCMovie] OpusEncoder created, screen=" + screen.getId());
            readerThread.start();

            long startTime = System.nanoTime();
            long frameIndex = 0;

            try {
                while (running) {
                    short[] samples = pcmQueue.poll(); // non-blocking — null on empty
                    if (samples == null) {
                        samples = SILENCE; // fill gap with silence instead of skipping
                    }

                    byte[] opusFrame = encoder.encode(samples);
                    channel.send(opusFrame);
                    frameIndex++;

                    // Drift-correcting sleep: maintains exactly 20ms cadence
                    long targetNs = startTime + frameIndex * AUDIO_FRAME_NS;
                    long waitNs = targetNs - System.nanoTime();
                    if (waitNs > 0) {
                        try {
                            Thread.sleep(waitNs / 1_000_000L, (int)(waitNs % 1_000_000L));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            } finally {
                readerThread.interrupt();
                plugin.debug("[MCMovie] Audio sender ending after " + frameIndex + " frames, screen=" + screen.getId());
                try { channel.flush(); } catch (Exception ignored) {}
                try { encoder.close(); } catch (Exception ignored) {}
                plugin.debug("[MCMovie] Audio sender done, screen=" + screen.getId());
            }
        }, "MCMovie-AudioSender-" + screen.getId());
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
