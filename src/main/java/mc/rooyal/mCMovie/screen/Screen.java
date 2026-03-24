package mc.rooyal.mCMovie.screen;

import mc.rooyal.mCMovie.video.VideoPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.map.MapView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class Screen {

    private final String id;
    private final World world;
    private final BlockFace face;
    private final int widthMaps;
    private final int heightMaps;
    private final Location center;

    private final List<UUID> itemFrameUuids;
    private final int[] mapIds;
    private final List<MCMovieMapRenderer> renderers;

    // MapView objects for each tile, resolved once (they never change after creation).
    private final MapView[] mapViews;

    // Cached list of nearby players — refreshed every playerCacheInterval ticks
    // so we don't do a world entity scan on every single tick.
    private List<Player> cachedPlayers = Collections.emptyList();
    private int ticksSincePlayerRefresh = 0;
    private static final int PLAYER_CACHE_INTERVAL = 5; // refresh every 5 ticks (0.25 s)

    private VideoPlayer videoPlayer;
    private String currentUrl;
    private boolean playing;

    public Screen(String id, World world, BlockFace face, int widthMaps, int heightMaps,
                  Location center, List<UUID> itemFrameUuids, int[] mapIds,
                  List<MCMovieMapRenderer> renderers) {
        this.id = id;
        this.world = world;
        this.face = face;
        this.widthMaps = widthMaps;
        this.heightMaps = heightMaps;
        this.center = center;
        this.itemFrameUuids = new ArrayList<>(itemFrameUuids);
        this.mapIds = mapIds;
        this.renderers = new ArrayList<>(renderers);
        this.playing = false;

        // Resolve and cache MapViews immediately — Bukkit.getMap() never needs to be
        // called again inside the hot per-tick scheduler loop.
        this.mapViews = new MapView[mapIds.length];
        for (int i = 0; i < mapIds.length; i++) {
            this.mapViews[i] = Bukkit.getMap(mapIds[i]);
        }
    }

    /**
     * Returns the cached nearby-player list, refreshing it every
     * {@link #PLAYER_CACHE_INTERVAL} ticks. Must be called on the main thread.
     */
    public List<Player> getCachedNearbyPlayers(double range) {
        if (ticksSincePlayerRefresh++ >= PLAYER_CACHE_INTERVAL) {
            cachedPlayers = new ArrayList<>(
                    world.getNearbyEntitiesByType(Player.class, center, range));
            ticksSincePlayerRefresh = 0;
        }
        return cachedPlayers;
    }

    // Getters
    public String getId() { return id; }
    public World getWorld() { return world; }
    public BlockFace getFace() { return face; }
    public int getWidthMaps() { return widthMaps; }
    public int getHeightMaps() { return heightMaps; }
    public Location getCenter() { return center; }
    public List<UUID> getItemFrameUuids() { return itemFrameUuids; }
    public int[] getMapIds() { return mapIds; }
    public MapView[] getMapViews() { return mapViews; }
    public List<MCMovieMapRenderer> getRenderers() { return renderers; }

    public VideoPlayer getVideoPlayer() { return videoPlayer; }
    public void setVideoPlayer(VideoPlayer videoPlayer) { this.videoPlayer = videoPlayer; }

    public String getCurrentUrl() { return currentUrl; }
    public void setCurrentUrl(String currentUrl) { this.currentUrl = currentUrl; }

    public boolean isPlaying() { return playing; }
    public void setPlaying(boolean playing) { this.playing = playing; }
}
