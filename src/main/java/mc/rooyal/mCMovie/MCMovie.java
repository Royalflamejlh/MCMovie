package mc.rooyal.mCMovie;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import mc.rooyal.mCMovie.command.MCMovieCommand;
import mc.rooyal.mCMovie.listener.ScreenCreationListener;
import mc.rooyal.mCMovie.listener.ScreenProtectionListener;
import mc.rooyal.mCMovie.screen.Screen;
import mc.rooyal.mCMovie.screen.ScreenManager;
import mc.rooyal.mCMovie.video.MinecraftMapPalette;
import mc.rooyal.mCMovie.video.VideoPlayer;
import mc.rooyal.mCMovie.voicechat.MCMovieVoicechatPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;

public final class MCMovie extends JavaPlugin {

    private static MCMovie instance;

    private ScreenManager screenManager;
    private ScreenCreationListener screenCreationListener;
    private BinaryManager binaryManager;
    private boolean voiceChatEnabled = false;
    private VoicechatServerApi voicechatServerApi = null;

    private File screensFile;

    @Override
    public void onEnable() {
        instance = this;

        // Load config (creates config.yml from defaults if missing)
        saveDefaultConfig();

        // Create data directories
        getDataFolder().mkdirs();
        File videosDir = new File(getDataFolder(), "videos");
        videosDir.mkdirs();

        screensFile = new File(getDataFolder(), "screens.yml");

        // Initialize screen manager
        screenManager = new ScreenManager(this);

        // Register listeners
        screenCreationListener = new ScreenCreationListener(this, screenManager);
        getServer().getPluginManager().registerEvents(screenCreationListener, this);
        getServer().getPluginManager().registerEvents(new ScreenProtectionListener(screenManager), this);

        // Register command
        MCMovieCommand commandExecutor = new MCMovieCommand(this, screenManager, screenCreationListener);
        getCommand("mcmovie").setExecutor(commandExecutor);
        getCommand("mcmovie").setTabCompleter(commandExecutor);

        // Pre-warm the Minecraft map colour lookup cache off the main thread.
        // After this completes, MinecraftMapPalette.matchColor() is O(1) for every colour.
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            long t = System.currentTimeMillis();
            MinecraftMapPalette.prewarm();
            getLogger().info("[MCMovie] Map colour cache pre-warmed in "
                    + (System.currentTimeMillis() - t) + " ms");
        });

        // Download/verify ffmpeg async (cached after first run)
        binaryManager = new BinaryManager(this);
        binaryManager.setupAsync();

        // Register SimpleVoiceChat integration (soft depend)
        if (getServer().getPluginManager().getPlugin("voicechat") != null) {
            try {
                BukkitVoicechatService service = getServer().getServicesManager().load(BukkitVoicechatService.class);
                if (service != null) {
                    service.registerPlugin(new MCMovieVoicechatPlugin());
                    voiceChatEnabled = true;
                    if (voicechatServerApi != null) {
                        getLogger().info("[MCMovie] SimpleVoiceChat integration registered and server API acquired immediately.");
                    } else {
                        getLogger().warning("[MCMovie] SimpleVoiceChat integration registered but server API is still null — initialize() may not have been called. Audio will not work until VoicechatServerStartedEvent fires.");
                    }
                } else {
                    getLogger().warning("[MCMovie] SimpleVoiceChat plugin found but service not available.");
                }
            } catch (Exception e) {
                getLogger().warning("[MCMovie] Failed to register SimpleVoiceChat integration: " + e.getMessage());
            }
        } else {
            getLogger().info("[MCMovie] SimpleVoiceChat not found, audio playback will be disabled.");
        }

        // Load saved screens
        screenManager.loadScreens(screensFile);

        // Every tick: advance the frame queue by one frame, then send updated maps to players.
        //
        // Performance notes:
        //  - advanceFrame() is called once per screen (not per player).
        //  - MapViews are cached in Screen — no Bukkit.getMap() in the hot loop.
        //  - Nearby players are cached and refreshed every 5 ticks (see Screen).
        //  - render() skips the setPixel loop for the 2nd+ player per tile per tick
        //    (the canvas buffer is already correct after the first player renders it).
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Screen screen : screenManager.getAllScreens().values()) {
                if (!screen.isPlaying()) continue;

                VideoPlayer vp = screen.getVideoPlayer();

                // advanceFrame() returns true only when a new frame was actually consumed.
                // During pre-roll, HLS segment gaps, or queue stalls it returns false —
                // we skip sendMap() entirely so zero packets are sent with stale data.
                boolean newFrame = (vp != null) && vp.advanceFrame();
                if (!newFrame) continue;

                List<Player> players = screen.getCachedNearbyPlayers(80);
                if (players.isEmpty()) continue;

                MapView[] mapViews = screen.getMapViews();
                for (Player player : players) {
                    for (MapView mapView : mapViews) {
                        if (mapView != null) player.sendMap(mapView);
                    }
                }
            }
        }, 1L, 1L);

        getLogger().info("[MCMovie] Plugin enabled successfully.");
    }

    @Override
    public void onDisable() {
        // Stop all video players
        if (screenManager != null) {
            for (Screen screen : screenManager.getAllScreens().values()) {
                if (screen.getVideoPlayer() != null) {
                    screen.getVideoPlayer().stop();
                }
            }
            // Save screens
            screenManager.saveScreens(screensFile);
        }

        getLogger().info("[MCMovie] Plugin disabled.");
        instance = null;
    }

    /**
     * Called by MCMovieVoicechatPlugin.initialize() once the voicechat API is ready.
     */
    public void setVoicechatServerApi(VoicechatServerApi api) {
        this.voicechatServerApi = api;
        getLogger().info("[MCMovie] SimpleVoiceChat server API is now active.");
    }

    /**
     * Save screens to disk immediately (called after create/delete).
     */
    public void saveScreensNow() {
        if (screenManager != null) {
            screenManager.saveScreens(screensFile);
        }
    }

    public static MCMovie getInstance() { return instance; }
    public ScreenManager getScreenManager() { return screenManager; }
    public BinaryManager getBinaryManager() { return binaryManager; }

    public boolean isDebug() {
        return getConfig().getBoolean("debug", false);
    }

    public void debug(String msg) {
        if (isDebug()) getLogger().info("[DEBUG] " + msg);
    }

    public boolean isVoiceChatEnabled() {
        return voiceChatEnabled && voicechatServerApi != null;
    }

    public VoicechatServerApi getVoicechatServerApi() {
        return voicechatServerApi;
    }
}
