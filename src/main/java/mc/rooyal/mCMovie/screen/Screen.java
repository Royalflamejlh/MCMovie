package mc.rooyal.mCMovie.screen;

import mc.rooyal.mCMovie.video.VideoPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.map.MapView;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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

    // Guards the async scheduler so only one tick's work runs at a time per screen.
    // Prevents overlapping executions if packet sending takes longer than one tick.
    private final AtomicBoolean asyncBusy = new AtomicBoolean(false);

    // volatile: read by the async scheduler thread, written by main-thread commands.
    private volatile VideoPlayer videoPlayer;
    private String currentUrl;
    private volatile boolean playing;

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
     * Returns nearby players by filtering all online players by world and squared distance.
     * Safe to call from async threads — no Bukkit world scan, just cached location reads.
     */
    public List<Player> getNearbyPlayersAsync(double range) {
        double rangeSq = range * range;
        double cx = center.getX(), cy = center.getY(), cz = center.getZ();
        List<Player> result = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location loc = p.getLocation();
            if (!world.equals(loc.getWorld())) continue;
            double dx = loc.getX() - cx, dy = loc.getY() - cy, dz = loc.getZ() - cz;
            if (dx * dx + dy * dy + dz * dz <= rangeSq) result.add(p);
        }
        return result;
    }

    /** Acquires the async-frame lock. Returns false if a frame is already in progress. */
    public boolean tryStartAsyncFrame() {
        return asyncBusy.compareAndSet(false, true);
    }

    /** Releases the async-frame lock. */
    public void finishAsyncFrame() {
        asyncBusy.set(false);
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
