package mc.rooyal.mCMovie.video;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData;
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
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class VideoPlayer {

    private static final int AUDIO_SAMPLE_RATE   = 48000;
    private static final int AUDIO_CHUNK_SAMPLES = 960;
    private static final int AUDIO_CHUNK_BYTES   = AUDIO_CHUNK_SAMPLES * 2;
    private static final long AUDIO_FRAME_NS     = 20_000_000L;

    private final Screen screen;
    private final MCMovie plugin;

    private volatile boolean running = false;

    /** Incremented each time a frame is delivered to players. Used for distance-based fps scaling. */
    private volatile long frameCount = 0;

    private Process videoProcess = null;
    private Process audioProcess = null;

    private Thread videoThread = null;
    private Thread audioThread = null;

    private LocationalAudioChannel audioChannel = null;

    /** Countdown from video delivery: audio sender awaits this before sending its first frame. */
    private CountDownLatch firstFrameLatch = new CountDownLatch(1);

    public VideoPlayer(Screen screen, MCMovie plugin) {
        this.screen = screen;
        this.plugin = plugin;
    }

    public void play(String urlOrFile) {
        if (running) stop();

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
        firstFrameLatch = new CountDownLatch(1);
        running = true;
        screen.setPlaying(true);

        final String finalInput = resolvedInput;
        final boolean isHttp = finalInput.startsWith("http://") || finalInput.startsWith("https://");

        startVideoThread(finalInput, isHttp);
        startAudioThread(finalInput, isHttp);

        Bukkit.getScheduler().runTask(plugin, () ->
                Bukkit.getPluginManager().callEvent(new ScreenVideoStartEvent(screen, finalInput)));
    }

    public void stop() {
        running = false;

        if (audioChannel != null) {
            try { audioChannel.flush(); } catch (Exception ignored) {}
            audioChannel = null;
        }

        destroyProcess(videoProcess);
        destroyProcess(audioProcess);
        videoProcess = null;
        audioProcess = null;

        interruptAndJoin(videoThread);
        interruptAndJoin(audioThread);
        videoThread = null;
        audioThread = null;

        frameCount = 0;
        screen.setPlaying(false);
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () ->
                    Bukkit.getPluginManager().callEvent(new ScreenVideoEndEvent(screen)));
        }
    }

    public boolean isRunning() { return running; }

    // ── Private helpers ────────────────────────────────────────────────────────

    private void destroyProcess(Process p) {
        if (p == null || !p.isAlive()) return;
        p.destroyForcibly();
        try { p.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void interruptAndJoin(Thread t) {
        if (t == null || !t.isAlive()) return;
        t.interrupt();
        try { t.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /**
     * Delivers a fully-built tile array to nearby players.
     * PacketEvents is async-safe; no scheduler involvement.
     */
    private void deliverFrame(byte[][] tiles) {
        frameCount++;
        int tileCount = tiles.length;
        List<MCMovieMapRenderer> renderers = screen.getRenderers();

        for (int i = 0; i < tileCount && i < renderers.size(); i++) {
            renderers.get(i).updateFrame(tiles[i]);
        }

        int viewDist = plugin.getConfig().getInt("view-distance", 40);
        List<Player> nearby = screen.getNearbyPlayersAsync(viewDist);
        if (nearby.isEmpty()) return;

        BlockFace face = screen.getFace();
        int faceX = face.getModX(), faceZ = face.getModZ();
        double cx = screen.getCenter().getX(),
               cy = screen.getCenter().getY(),
               cz = screen.getCenter().getZ();

        List<Player> players = new ArrayList<>(nearby.size());
        for (Player p : nearby) {
            Location loc = p.getLocation();

            if (faceX != 0 || faceZ != 0) {
                float yaw  = (float) Math.toRadians(loc.getYaw());
                float fwdX = -(float) Math.sin(yaw);
                float fwdZ =  (float) Math.cos(yaw);
                if (fwdX * faceX + fwdZ * faceZ > 0.1f) continue;
            }

            double dx = loc.getX() - cx, dy = loc.getY() - cy, dz = loc.getZ() - cz;
            double distSq = dx * dx + dy * dy + dz * dz;
            int sendEvery = distSq < 20 * 20 ? 1 : distSq < 40 * 40 ? 2 : 4;
            if ((frameCount % sendEvery) != 0) continue;

            // Line-of-sight: skip players with a solid block between them and the screen.
            if (plugin.getConfig().getBoolean("line-of-sight", true)) {
                try {
                    Location eye = p.getEyeLocation();
                    double toX = cx - eye.getX(), toY = cy - eye.getY(), toZ = cz - eye.getZ();
                    double eyeDist = Math.sqrt(toX * toX + toY * toY + toZ * toZ);
                    if (eyeDist > 1.0) {
                        double inv = 1.0 / eyeDist;
                        RayTraceResult hit = p.getWorld().rayTraceBlocks(
                                eye,
                                new Vector(toX * inv, toY * inv, toZ * inv),
                                eyeDist - 1.0,
                                FluidCollisionMode.NEVER);
                        if (hit != null) continue;
                    }
                } catch (Exception ignored) {
                    // Async raytrace failed — include this player rather than skipping
                }
            }

            players.add(p);
        }
        if (players.isEmpty()) return;

        int[] mapIds = screen.getMapIds();
        int count = Math.min(tileCount, Math.min(mapIds.length, renderers.size()));
        WrapperPlayServerMapData[] packets = new WrapperPlayServerMapData[count];
        boolean anyDirty = false;
        for (int i = 0; i < count; i++) {
            MCMovieMapRenderer.Patch patch = renderers.get(i).computeAndMarkPatch();
            if (patch == null) continue;
            packets[i] = new WrapperPlayServerMapData(
                    mapIds[i], (byte) 0, false, false,
                    Collections.emptyList(),
                    patch.width, patch.height, patch.x, patch.y, patch.data);
            anyDirty = true;
        }
        if (!anyDirty) return;

        var pm = PacketEvents.getAPI().getPlayerManager();
        for (Player player : players) {
            for (int i = 0; i < count; i++) {
                if (packets[i] != null) pm.sendPacket(player, packets[i]);
            }
        }
    }

    private void startVideoThread(String input, boolean isHttpInput) {
        int frameW     = screen.getWidthMaps()  * 128;
        int frameH     = screen.getHeightMaps() * 128;
        int frameBytes = frameW * frameH * 3;
        int wMaps      = screen.getWidthMaps();
        int hMaps      = screen.getHeightMaps();
        int tileCount  = wMaps * hMaps;

        int fps = plugin.getConfig().getInt("video-fps", 10);
        long frameIntervalNs = 1_000_000_000L / fps;
        // ~4 seconds of decoded frames — absorbs HLS segment bursts without stutter.
        final LinkedBlockingQueue<byte[][]> frameQueue = new LinkedBlockingQueue<>(fps * 4);

        // ── Decoder: ffmpeg stdout → palette-convert → frameQueue ────────────
        Thread decoderThread = new Thread(() -> {
            try {
                String ffmpegBin = plugin.getBinaryManager().getFfmpegPath();
                if (ffmpegBin == null) {
                    plugin.getLogger().severe("[MCMovie] ffmpeg unavailable — video will not play. Check startup logs.");
                    return;
                }

                List<String> cmd = new ArrayList<>();
                cmd.add(ffmpegBin);

                if (isHttpInput) {
                    cmd.add("-fflags");           cmd.add("+nobuffer+discardcorrupt");
                    cmd.add("-flags");            cmd.add("low_delay");
                    cmd.add("-live_start_index"); cmd.add("-1");
                } else {
                    cmd.add("-re");
                }

                cmd.add("-i");       cmd.add(input);
                cmd.add("-vf");      cmd.add("scale=" + frameW + ":" + frameH + ",fps=" + fps);
                cmd.add("-f");       cmd.add("rawvideo");
                cmd.add("-pix_fmt"); cmd.add("rgb24");
                cmd.add("-an");
                cmd.add("pipe:1");
                plugin.debug("Video ffmpeg cmd: " + String.join(" ", cmd));

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(false);
                videoProcess = pb.start();
                plugin.getLogger().info("[MCMovie] Video ffmpeg PID=" + videoProcess.pid()
                        + " screen=" + screen.getId()
                        + " frameBytes=" + frameBytes + " tiles=" + tileCount);

                // Log ffmpeg stderr so errors are visible in the Minecraft console.
                final Process vProc = videoProcess;
                Thread stderrThread = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(vProc.getErrorStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            plugin.debug("[ffmpeg-video] " + line);
                        }
                    } catch (IOException ignored) {}
                }, "MCMovie-VideoStderr-" + screen.getId());
                stderrThread.setDaemon(true);
                stderrThread.start();

                InputStream in = videoProcess.getInputStream();
                byte[] rawBuf = new byte[frameBytes];
                int localFrameCount = 0;

                while (running) {
                    if (!readInto(in, rawBuf)) {
                        plugin.debug("Video EOF after " + localFrameCount + " frames, screen=" + screen.getId());
                        break;
                    }
                    if (localFrameCount == 0)
                        plugin.getLogger().info("[MCMovie] First video frame, screen=" + screen.getId());
                    localFrameCount++;

                    byte[][] tiles = new byte[tileCount][];
                    for (int row = 0; row < hMaps; row++) {
                        for (int col = 0; col < wMaps; col++) {
                            byte[] tileBytes = new byte[128 * 128];
                            int tileBase = (row * 128 * frameW + col * 128) * 3;
                            for (int py = 0; py < 128; py++) {
                                int src = tileBase + py * frameW * 3;
                                int dst = py * 128;
                                for (int px = 0; px < 128; px++, src += 3) {
                                    tileBytes[dst + px] = MinecraftMapPalette.matchColor(
                                            rawBuf[src]     & 0xFF,
                                            rawBuf[src + 1] & 0xFF,
                                            rawBuf[src + 2] & 0xFF);
                                }
                            }
                            tiles[row * wMaps + col] = tileBytes;
                        }
                    }

                    try {
                        frameQueue.put(tiles); // block if delivery is falling behind
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (IOException e) {
                if (running)
                    plugin.getLogger().log(Level.WARNING,
                            "[MCMovie] Video decoder error, screen=" + screen.getId(), e);
            }
        }, "MCMovie-VideoDecoder-" + screen.getId());
        decoderThread.setDaemon(true);

        // ── Delivery: frameQueue → clients at steady fps clock ──────────────
        videoThread = new Thread(() -> {
            decoderThread.start();

            long nextFrameTime = System.nanoTime();
            byte[][] lastTiles = null;
            boolean firstDelivered = false;

            try {
                while (running) {
                    byte[][] tiles = frameQueue.poll();
                    if (tiles != null) lastTiles = tiles;

                    // Hold last frame between HLS segments so clients see a freeze-frame
                    // rather than a hard pause.
                    if (lastTiles != null) {
                        try {
                            deliverFrame(lastTiles);
                        } catch (Exception e) {
                            plugin.getLogger().log(Level.WARNING,
                                    "[MCMovie] Frame delivery error, screen=" + screen.getId(), e);
                        }
                        if (!firstDelivered) {
                            firstDelivered = true;
                            firstFrameLatch.countDown(); // release audio sender
                        }
                    }

                    nextFrameTime += frameIntervalNs;
                    long waitNs = nextFrameTime - System.nanoTime();
                    if (waitNs > 0) {
                        try {
                            Thread.sleep(waitNs / 1_000_000L, (int)(waitNs % 1_000_000L));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    } else if (waitNs < -frameIntervalNs) {
                        nextFrameTime = System.nanoTime();
                    }
                }
            } finally {
                firstFrameLatch.countDown(); // unblock audio even if video never delivered a frame
                decoderThread.interrupt();
                if (running) {
                    running = false;
                    screen.setPlaying(false);
                    if (plugin.isEnabled()) {
                        Bukkit.getScheduler().runTask(plugin, () ->
                                Bukkit.getPluginManager().callEvent(new ScreenVideoEndEvent(screen)));
                    }
                }
            }
        }, "MCMovie-VideoDelivery-" + screen.getId());
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
            plugin.getLogger().warning("[MCMovie] Audio skipped: voicechatServerApi is null, screen=" + screen.getId());
            return;
        }

        Location center = screen.getCenter();
        audioChannel = api.createLocationalAudioChannel(
                UUID.randomUUID(),
                api.fromServerLevel(center.getWorld()),
                api.createPosition(center.getX(), center.getY(), center.getZ())
        );

        if (audioChannel == null) {
            plugin.getLogger().warning("[MCMovie] createLocationalAudioChannel returned null — voicechat not ready? screen=" + screen.getId());
            return;
        }

        audioChannel.setDistance((float) plugin.getConfig().getDouble("audio-distance", 32.0));
        plugin.getLogger().info("[MCMovie] Audio channel created id=" + audioChannel.getId() + " screen=" + screen.getId());

        final LocationalAudioChannel channel = audioChannel;
        // 200 frames = ~4 seconds at 50fps — matches the video queue's ~4s depth so
        // both pipelines drain at the same rate and stay in sync.
        final LinkedBlockingQueue<short[]> pcmQueue = new LinkedBlockingQueue<>(200);
        final short[] SILENCE = new short[AUDIO_CHUNK_SAMPLES];
        final byte[] rawAudioBuf = new byte[AUDIO_CHUNK_BYTES];

        // ── Reader: ffmpeg → pcmQueue ──────────────────────────────────────────
        Thread readerThread = new Thread(() -> {
            String ffmpegBin = plugin.getBinaryManager().getFfmpegPath();
            if (ffmpegBin == null) {
                plugin.getLogger().severe("[MCMovie] ffmpeg unavailable — audio will not play.");
                return;
            }
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegBin);

            if (isHttpInput) {
                cmd.add("-fflags");           cmd.add("+nobuffer+discardcorrupt");
                cmd.add("-flags");            cmd.add("low_delay");
                cmd.add("-live_start_index"); cmd.add("-1");
            }

            cmd.add("-i");  cmd.add(input);
            cmd.add("-vn");
            cmd.add("-f");  cmd.add("s16le");
            cmd.add("-ar"); cmd.add(String.valueOf(AUDIO_SAMPLE_RATE));
            cmd.add("-ac"); cmd.add("1");
            cmd.add("pipe:1");
            plugin.debug("Audio ffmpeg cmd: " + String.join(" ", cmd));

            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(false);
                audioProcess = pb.start();
                plugin.getLogger().info("[MCMovie] Audio ffmpeg PID=" + audioProcess.pid() + " screen=" + screen.getId());

                final Process aProc = audioProcess;
                Thread aStderr = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(aProc.getErrorStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            plugin.debug("[ffmpeg-audio] " + line);
                        }
                    } catch (IOException ignored) {}
                }, "MCMovie-AudioStderr-" + screen.getId());
                aStderr.setDaemon(true);
                aStderr.start();

                InputStream in = audioProcess.getInputStream();
                long audioFrames = 0;
                while (running) {
                    if (!readInto(in, rawAudioBuf)) {
                        plugin.debug("[MCMovie] Audio EOF after " + audioFrames + " frames, screen=" + screen.getId());
                        break;
                    }
                    if (audioFrames == 0)
                        plugin.getLogger().info("[MCMovie] First audio frame, screen=" + screen.getId());
                    audioFrames++;

                    short[] samples = new short[AUDIO_CHUNK_SAMPLES];
                    ByteBuffer.wrap(rawAudioBuf).order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer().get(samples);

                    try {
                        pcmQueue.put(samples); // block if queue full — prevents audio racing ahead of video
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (IOException e) {
                if (running)
                    plugin.getLogger().log(Level.WARNING, "[MCMovie] Audio reader error, screen=" + screen.getId(), e);
            }
        }, "MCMovie-AudioReader-" + screen.getId());
        readerThread.setDaemon(true);

        // ── Sender: pcmQueue → Opus → channel ─────────────────────────────────
        audioThread = new Thread(() -> {
            OpusEncoder encoder = api.createEncoder(OpusEncoderMode.AUDIO);
            plugin.debug("[MCMovie] OpusEncoder created, screen=" + screen.getId());
            readerThread.start();

            // Wait until the video delivery thread has sent at least one frame to clients.
            // This ensures audio and video start together, preventing audio from playing
            // while the video queue is still filling up with the initial HLS segment.
            try {
                firstFrameLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running) return;
            plugin.debug("[MCMovie] Audio sender unlatched, screen=" + screen.getId());

            long startTime = System.nanoTime();
            long frameIndex = 0;
            try {
                while (running) {
                    short[] samples = pcmQueue.poll();
                    if (samples == null) samples = SILENCE;

                    byte[] opusFrame = encoder.encode(samples);
                    channel.send(opusFrame);
                    frameIndex++;

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
                plugin.debug("[MCMovie] Audio sender done after " + frameIndex + " frames, screen=" + screen.getId());
                try { channel.flush(); } catch (Exception ignored) {}
                try { encoder.close(); } catch (Exception ignored) {}
            }
        }, "MCMovie-AudioSender-" + screen.getId());
        audioThread.setDaemon(true);
        audioThread.start();
    }

    /** Reads exactly {@code buf.length} bytes into {@code buf}. Returns false on EOF. */
    private boolean readInto(InputStream in, byte[] buf) throws IOException {
        int off = 0, n = buf.length;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r == -1) return false;
            off += r;
        }
        return true;
    }
}
