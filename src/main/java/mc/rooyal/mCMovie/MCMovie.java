package mc.rooyal.mCMovie;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import mc.rooyal.mCMovie.command.MCMovieCommand;
import mc.rooyal.mCMovie.listener.ScreenCreationListener;
import mc.rooyal.mCMovie.listener.ScreenProtectionListener;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData;
import mc.rooyal.mCMovie.screen.MCMovieMapRenderer;
import mc.rooyal.mCMovie.screen.Screen;
import mc.rooyal.mCMovie.screen.ScreenManager;
import mc.rooyal.mCMovie.video.MinecraftMapPalette;
import mc.rooyal.mCMovie.video.VideoPlayer;
import mc.rooyal.mCMovie.voicechat.MCMovieVoicechatPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
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

        // Every tick (async): advance the frame queue, compute dirty regions, and push
        // map packets to nearby players entirely off the main thread.
        //
        // Why this is safe:
        //  - PacketEvents writes directly to Netty channels — async-safe by design.
        //  - frameQueue is a LinkedBlockingQueue — thread-safe.
        //  - renderer fields use volatile for cross-thread visibility.
        //  - Player list is built by filtering Bukkit.getOnlinePlayers() with cached
        //    location math — no Bukkit world scan required, safe from async threads.
        //  - ConcurrentHashMap in ScreenManager allows safe async iteration.
        //  - Per-screen AtomicBoolean guard ensures only one tick runs at a time,
        //    preventing overlap if a frame takes longer than 50 ms to send.
        //  - render() remains registered so Bukkit can still send the full canvas to
        //    players who load the map mid-playback (separate lastRenderedBytes path).
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            for (Screen screen : screenManager.getAllScreens().values()) {
                if (!screen.isPlaying()) continue;
                if (!screen.tryStartAsyncFrame()) continue; // skip if previous tick still running

                try {
                    VideoPlayer vp = screen.getVideoPlayer();

                    // advanceFrame() returns true only when a new frame was actually consumed.
                    // During pre-roll, HLS segment gaps, or queue stalls it returns false —
                    // no packets are sent with stale data.
                    if (vp == null || !vp.advanceFrame()) continue;

                    List<Player> players = screen.getNearbyPlayersAsync(80);
                    if (players.isEmpty()) continue;

                    int[] mapIds = screen.getMapIds();
                    List<MCMovieMapRenderer> renderers = screen.getRenderers();
                    int tileCount = Math.min(mapIds.length, renderers.size());

                    // Build one packet per dirty tile. computeAndMarkPatch() returns only the
                    // changed bounding box, so unchanged tiles (letters, borders) produce null.
                    // WrapperPlayServerMapData fields: mapId, scale, trackingPosition, locked,
                    //   decorations, columns (width), rows (height), x (startX), z (startY), data.
                    WrapperPlayServerMapData[] packets = new WrapperPlayServerMapData[tileCount];
                    boolean anyDirty = false;
                    for (int i = 0; i < tileCount; i++) {
                        MCMovieMapRenderer.Patch patch = renderers.get(i).computeAndMarkPatch();
                        if (patch == null) continue;
                        packets[i] = new WrapperPlayServerMapData(
                                mapIds[i], (byte) 0, false, false,
                                java.util.Collections.emptyList(),
                                patch.width, patch.height, patch.x, patch.y, patch.data);
                        anyDirty = true;
                    }
                    if (!anyDirty) continue;

                    var pm = PacketEvents.getAPI().getPlayerManager();
                    for (Player player : players) {
                        for (int i = 0; i < tileCount; i++) {
                            if (packets[i] != null) {
                                pm.sendPacket(player, packets[i]);
                            }
                        }
                    }
                } finally {
                    screen.finishAsyncFrame();
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
