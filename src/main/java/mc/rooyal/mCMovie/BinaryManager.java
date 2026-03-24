package mc.rooyal.mCMovie;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Manages the ffmpeg binary.
 * On first startup it is downloaded from johnvansickle.com and cached at
 * {@code <dataFolder>/bin/ffmpeg}. Subsequent startups reuse the cached binary.
 *
 * Setup runs on a daemon thread so {@code onEnable()} is not blocked.
 * {@link #getFfmpegPath()} blocks the calling thread (up to 120 s) until setup completes.
 */
public class BinaryManager {

    private static final String DOWNLOAD_URL =
            "https://johnvansickle.com/ffmpeg/builds/ffmpeg-git-amd64-static.tar.xz";

    private static final String MANUAL_INSTALL_PATH = "<plugins>/MCMovie/bin/ffmpeg";

    private final MCMovie plugin;

    /** Absolute path to the ffmpeg executable, or {@code null} if unavailable. */
    private volatile String ffmpegPath = null;

    private final CountDownLatch readyLatch = new CountDownLatch(1);

    public BinaryManager(MCMovie plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts the async setup. Call once from {@code onEnable()}.
     */
    public void setupAsync() {
        Thread t = new Thread(this::setup, "MCMovie-BinarySetup");
        t.setDaemon(true);
        t.start();
    }

    private void setup() {
        try {
            File ffmpegFile = new File(plugin.getDataFolder(), "bin/ffmpeg");

            if (ffmpegFile.exists() && ffmpegFile.canExecute()) {
                ffmpegPath = ffmpegFile.getAbsolutePath();
                plugin.getLogger().info("[MCMovie] Using cached ffmpeg: " + ffmpegPath);
                return;
            }

            plugin.getLogger().info("[MCMovie] ffmpeg not found — downloading from " + DOWNLOAD_URL);
            plugin.getLogger().info("[MCMovie] This is a one-time download (~70 MB), please wait...");

            ffmpegFile.getParentFile().mkdirs();
            downloadAndExtract(ffmpegFile);

        } catch (Exception e) {
            plugin.getLogger().severe("[MCMovie] ===================================================");
            plugin.getLogger().severe("[MCMovie] FAILED to download ffmpeg: " + e.getMessage());
            plugin.getLogger().severe("[MCMovie] Video/audio playback will not work.");
            plugin.getLogger().severe("[MCMovie] To fix: manually download the static ffmpeg binary");
            plugin.getLogger().severe("[MCMovie]   from: " + DOWNLOAD_URL);
            plugin.getLogger().severe("[MCMovie]   extract the 'ffmpeg' file from the archive");
            plugin.getLogger().severe("[MCMovie]   and place it at: " + MANUAL_INSTALL_PATH);
            plugin.getLogger().severe("[MCMovie]   (make it executable: chmod +x <path>)");
            plugin.getLogger().severe("[MCMovie] ===================================================");
        } finally {
            readyLatch.countDown();
        }
    }

    private void downloadAndExtract(File targetFile) throws Exception {
        File tempTar  = new File(plugin.getDataFolder(), "bin/.ffmpeg-download.tar.xz");
        File tempDir  = new File(plugin.getDataFolder(), "bin/.ffmpeg-extract");

        try {
            // ── Download ──────────────────────────────────────────────────────
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DOWNLOAD_URL))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();

            plugin.getLogger().info("[MCMovie] Downloading ffmpeg...");
            HttpResponse<Path> response = client.send(
                    request, HttpResponse.BodyHandlers.ofFile(tempTar.toPath()));

            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " from " + DOWNLOAD_URL);
            }
            long sizeKb = tempTar.length() / 1024;
            plugin.getLogger().info("[MCMovie] Download complete (" + sizeKb / 1024 + " MB), extracting...");

            // ── Extract via tar ───────────────────────────────────────────────
            tempDir.mkdirs();

            ProcessBuilder pb = new ProcessBuilder(
                    "tar", "-xJf", tempTar.getAbsolutePath(), "-C", tempDir.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            // Drain output so the process doesn't block on a full pipe buffer
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                br.lines().forEach(line -> plugin.debug("[tar] " + line));
            }

            int exit = proc.waitFor();
            if (exit != 0) {
                throw new IOException("tar exited with code " + exit);
            }

            // ── Find the ffmpeg binary inside the extracted tree ──────────────
            File ffmpegBinary = findFile(tempDir, "ffmpeg");
            if (ffmpegBinary == null) {
                throw new IOException("'ffmpeg' not found inside the downloaded archive");
            }

            Files.copy(ffmpegBinary.toPath(), targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            if (!targetFile.setExecutable(true)) {
                throw new IOException("Could not set ffmpeg as executable: " + targetFile);
            }

            ffmpegPath = targetFile.getAbsolutePath();
            plugin.getLogger().info("[MCMovie] ffmpeg installed at: " + ffmpegPath);

        } finally {
            // Always clean up temp files even on failure
            tempTar.delete();
            deleteRecursive(tempDir);
        }
    }

    /** Recursively searches {@code dir} for a file named {@code name}. */
    private File findFile(File dir, String name) {
        File[] children = dir.listFiles();
        if (children == null) return null;
        for (File f : children) {
            if (f.isDirectory()) {
                File found = findFile(f, name);
                if (found != null) return found;
            } else if (f.getName().equals(name)) {
                return f;
            }
        }
        return null;
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    /**
     * Returns the absolute path to ffmpeg, blocking until setup finishes (max 120 s).
     * Returns {@code null} if ffmpeg is unavailable (download failed, no manual install).
     */
    public String getFfmpegPath() {
        try {
            if (!readyLatch.await(120, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("[MCMovie] Timed out waiting for ffmpeg setup.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ffmpegPath;
    }

    /** True once setup has completed and ffmpeg is usable. */
    public boolean isReady() {
        return readyLatch.getCount() == 0 && ffmpegPath != null;
    }
}
