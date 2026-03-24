package mc.rooyal.mCMovie;

import java.io.*;
import java.nio.file.*;

/**
 * Manages the ffmpeg binary bundled inside the plugin JAR.
 * On first startup it is extracted to &lt;dataFolder&gt;/bin/ffmpeg.
 */
public class BinaryManager {

    private final MCMovie plugin;
    private String ffmpegPath = "ffmpeg";

    public BinaryManager(MCMovie plugin) {
        this.plugin = plugin;
    }

    /**
     * Synchronously extracts the bundled ffmpeg binary. Call during onEnable.
     */
    public void extractBinaries() {
        File ffmpegFile = new File(plugin.getDataFolder(), "bin/ffmpeg");
        if (ffmpegFile.exists() && ffmpegFile.canExecute()) {
            ffmpegPath = ffmpegFile.getAbsolutePath();
            plugin.getLogger().info("[MCMovie] Using cached ffmpeg: " + ffmpegPath);
            return;
        }

        plugin.getLogger().info("[MCMovie] Extracting bundled ffmpeg...");
        try (InputStream is = getClass().getResourceAsStream("/bin/ffmpeg")) {
            if (is == null) {
                plugin.getLogger().warning("[MCMovie] Bundled ffmpeg not found in JAR. " +
                        "Install ffmpeg manually: sudo apt-get install ffmpeg");
                return;
            }
            ffmpegFile.getParentFile().mkdirs();
            Files.copy(is, ffmpegFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            ffmpegFile.setExecutable(true);
            ffmpegPath = ffmpegFile.getAbsolutePath();
            plugin.getLogger().info("[MCMovie] ffmpeg extracted to: " + ffmpegPath);
        } catch (IOException e) {
            plugin.getLogger().warning("[MCMovie] Failed to extract ffmpeg: " + e.getMessage());
        }
    }

    public String getFfmpegPath() { return ffmpegPath; }
}
