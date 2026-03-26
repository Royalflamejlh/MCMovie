package mc.rooyal.mCMovie.screen;

import mc.rooyal.mCMovie.MCMovie;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.Rotation;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class ScreenManager {

    private final MCMovie plugin;
    private final Map<String, Screen> screens = new ConcurrentHashMap<>();

    public ScreenManager(MCMovie plugin) {
        this.plugin = plugin;
    }

    /**
     * Creates a screen from two clicked corner locations on a wall.
     * Positions list contains the two clicked block locations.
     */
    public Screen createScreen(String id, World world, BlockFace face, List<Location> positions) {
        if (positions.size() < 2) {
            throw new IllegalArgumentException("Need exactly 2 corner positions");
        }

        Location a = positions.get(0);
        Location b = positions.get(1);

        int minX = Math.min(a.getBlockX(), b.getBlockX());
        int maxX = Math.max(a.getBlockX(), b.getBlockX());
        int minY = Math.min(a.getBlockY(), b.getBlockY());
        int maxY = Math.max(a.getBlockY(), b.getBlockY());
        int minZ = Math.min(a.getBlockZ(), b.getBlockZ());
        int maxZ = Math.max(a.getBlockZ(), b.getBlockZ());

        int widthMaps;
        int heightMaps = maxY - minY + 1;

        // Build ordered list of block positions (col, row → block) based on face orientation
        // The wall itself: for NORTH/SOUTH faces the wall extends in X; for EAST/WEST it extends in Z
        // We also need the Z (for NS) or X (for EW) coordinate of the wall block itself
        int wallZ = 0, wallX = 0;
        List<Location> orderedPositions = new ArrayList<>();

        switch (face) {
            case NORTH:
            case SOUTH:
                widthMaps = maxX - minX + 1;
                wallZ = a.getBlockZ(); // same Z for both clicks on a NS wall
                // NORTH face: player looks South → left is East (+X), so col 0 = maxX
                // SOUTH face: player looks North → left is West (-X), so col 0 = minX
                for (int row = 0; row < heightMaps; row++) {
                    int y = maxY - row;
                    for (int col = 0; col < widthMaps; col++) {
                        int x = (face == BlockFace.NORTH) ? (maxX - col) : (minX + col);
                        orderedPositions.add(new Location(world, x, y, wallZ));
                    }
                }
                break;
            case EAST:
            case WEST:
                widthMaps = maxZ - minZ + 1;
                wallX = a.getBlockX(); // same X for both clicks on an EW wall
                // col 0 = max Z (EAST), col 0 = min Z (WEST), row 0 = max Y
                for (int row = 0; row < heightMaps; row++) {
                    int y = maxY - row;
                    for (int col = 0; col < widthMaps; col++) {
                        int z = (face == BlockFace.EAST) ? (maxZ - col) : (minZ + col);
                        orderedPositions.add(new Location(world, wallX, y, z));
                    }
                }
                break;
            default:
                throw new IllegalArgumentException("Unsupported face: " + face);
        }

        plugin.debug("createScreen '" + id + "': face=" + face
                + " widthMaps=" + widthMaps + " heightMaps=" + heightMaps
                + " tiles=" + (widthMaps * heightMaps));

        List<UUID> itemFrameUuids = new ArrayList<>();
        int[] mapIds = new int[widthMaps * heightMaps];
        List<MCMovieMapRenderer> renderers = new ArrayList<>();

        for (int i = 0; i < orderedPositions.size(); i++) {
            Location blockLoc = orderedPositions.get(i);
            // Spawn the item frame in the air block in front of the wall face,
            // NOT inside the solid wall block itself.
            Location spawnLoc = blockLoc.clone().add(
                    face.getModX() + 0.5,
                    face.getModY() + 0.5,
                    face.getModZ() + 0.5
            );

            plugin.debug("  tile " + i + ": wallBlock=" + blockLoc.toVector()
                    + " spawnLoc=" + spawnLoc.toVector() + " face=" + face);

            GlowItemFrame frame = world.spawn(spawnLoc, GlowItemFrame.class, f -> {
                f.setFacingDirection(face);
                f.setFixed(true);
                f.setVisible(false);
                f.setPersistent(true);
                f.setRotation(Rotation.NONE);
            });

            plugin.debug("  tile " + i + ": frame UUID=" + frame.getUniqueId()
                    + " actualLoc=" + frame.getLocation().toVector()
                    + " facing=" + frame.getFacing());

            MapView mapView = Bukkit.createMap(world);
            mapView.getRenderers().forEach(mapView::removeRenderer);
            MCMovieMapRenderer renderer = new MCMovieMapRenderer();
            mapView.addRenderer(renderer);
            mapView.setTrackingPosition(false);
            mapView.setUnlimitedTracking(false);

            ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
            MapMeta meta = (MapMeta) mapItem.getItemMeta();
            meta.setMapView(mapView);
            mapItem.setItemMeta(meta);
            frame.setItem(mapItem, false);

            itemFrameUuids.add(frame.getUniqueId());
            mapIds[i] = mapView.getId();
            renderers.add(renderer);
        }

        // Compute center
        double cx = (minX + maxX) / 2.0 + 0.5;
        double cy = (minY + maxY) / 2.0 + 0.5;
        double cz = (minZ + maxZ) / 2.0 + 0.5;
        Location center = new Location(world, cx, cy, cz);

        plugin.debug("createScreen '" + id + "': center=" + center.toVector()
                + " frames=" + itemFrameUuids.size());

        Screen screen = new Screen(id, world, face, widthMaps, heightMaps, center,
                itemFrameUuids, mapIds, renderers);
        screens.put(id, screen);
        return screen;
    }

    public void deleteScreen(String id) {
        Screen screen = screens.get(id);
        if (screen == null) return;

        // Stop any playing video
        if (screen.getVideoPlayer() != null) {
            screen.getVideoPlayer().stop();
        }

        // Remove item frames
        for (UUID uuid : screen.getItemFrameUuids()) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null) {
                entity.remove();
            }
        }

        // Remove renderers from map views
        for (int mapId : screen.getMapIds()) {
            MapView mapView = Bukkit.getMap(mapId);
            if (mapView != null) {
                mapView.getRenderers().forEach(mapView::removeRenderer);
            }
        }

        screens.remove(id);
    }

    public Screen getScreen(String id) {
        return screens.get(id);
    }

    public Map<String, Screen> getAllScreens() {
        return Collections.unmodifiableMap(screens);
    }

    public void saveScreens(File file) {
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<String, Screen> entry : screens.entrySet()) {
            Screen s = entry.getValue();
            String path = "screens." + s.getId();
            config.set(path + ".world", s.getWorld().getName());
            config.set(path + ".face", s.getFace().name());
            config.set(path + ".widthMaps", s.getWidthMaps());
            config.set(path + ".heightMaps", s.getHeightMaps());
            config.set(path + ".centerX", s.getCenter().getX());
            config.set(path + ".centerY", s.getCenter().getY());
            config.set(path + ".centerZ", s.getCenter().getZ());

            // Save map IDs as comma-separated string
            StringBuilder mapIdsStr = new StringBuilder();
            for (int i = 0; i < s.getMapIds().length; i++) {
                if (i > 0) mapIdsStr.append(",");
                mapIdsStr.append(s.getMapIds()[i]);
            }
            config.set(path + ".mapIds", mapIdsStr.toString());

            // Save item frame UUIDs
            StringBuilder uuidsStr = new StringBuilder();
            List<UUID> uuids = s.getItemFrameUuids();
            for (int i = 0; i < uuids.size(); i++) {
                if (i > 0) uuidsStr.append(",");
                uuidsStr.append(uuids.get(i).toString());
            }
            config.set(path + ".itemFrameUuids", uuidsStr.toString());
        }

        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save screens.yml", e);
        }
    }

    public void loadScreens(File file) {
        if (!file.exists()) return;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        if (!config.isConfigurationSection("screens")) return;

        for (String id : config.getConfigurationSection("screens").getKeys(false)) {
            String path = "screens." + id;
            String worldName = config.getString(path + ".world");
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                plugin.getLogger().warning("Could not find world '" + worldName + "' for screen '" + id + "', skipping.");
                continue;
            }

            BlockFace face;
            try {
                face = BlockFace.valueOf(config.getString(path + ".face"));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid face for screen '" + id + "', skipping.");
                continue;
            }

            int widthMaps = config.getInt(path + ".widthMaps");
            int heightMaps = config.getInt(path + ".heightMaps");
            double cx = config.getDouble(path + ".centerX");
            double cy = config.getDouble(path + ".centerY");
            double cz = config.getDouble(path + ".centerZ");
            Location center = new Location(world, cx, cy, cz);

            // Load map IDs
            String mapIdsStr = config.getString(path + ".mapIds", "");
            String[] mapIdParts = mapIdsStr.isEmpty() ? new String[0] : mapIdsStr.split(",");
            int[] mapIds = new int[mapIdParts.length];
            List<MCMovieMapRenderer> renderers = new ArrayList<>();
            for (int i = 0; i < mapIdParts.length; i++) {
                try {
                    mapIds[i] = Integer.parseInt(mapIdParts[i].trim());
                } catch (NumberFormatException e) {
                    mapIds[i] = -1;
                }
                // Re-attach renderer to existing map view
                if (mapIds[i] >= 0) {
                    MapView mapView = Bukkit.getMap(mapIds[i]);
                    if (mapView != null) {
                        mapView.getRenderers().forEach(mapView::removeRenderer);
                        MCMovieMapRenderer renderer = new MCMovieMapRenderer();
                        mapView.addRenderer(renderer);
                        // Force-resend the map to any online players whose client may have
                        // cached blank data from a previous unclean disable/reload.
                        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
                            p.sendMap(mapView);
                        }
                        renderers.add(renderer);
                    } else {
                        renderers.add(new MCMovieMapRenderer());
                    }
                } else {
                    renderers.add(new MCMovieMapRenderer());
                }
            }

            // Load item frame UUIDs
            String uuidsStr = config.getString(path + ".itemFrameUuids", "");
            String[] uuidParts = uuidsStr.isEmpty() ? new String[0] : uuidsStr.split(",");
            List<UUID> itemFrameUuids = new ArrayList<>();
            for (String uuidStr : uuidParts) {
                try {
                    itemFrameUuids.add(UUID.fromString(uuidStr.trim()));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid UUID '" + uuidStr + "' for screen '" + id + "'");
                }
            }

            Screen screen = new Screen(id, world, face, widthMaps, heightMaps, center,
                    itemFrameUuids, mapIds, renderers);
            screens.put(id, screen);
            plugin.getLogger().info("Loaded screen '" + id + "' (" + widthMaps + "x" + heightMaps + " maps)");
        }
    }
}
