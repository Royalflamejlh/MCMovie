package mc.rooyal.mCMovie.listener;

import mc.rooyal.mCMovie.MCMovie;
import mc.rooyal.mCMovie.screen.Screen;
import mc.rooyal.mCMovie.screen.ScreenManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.*;
import java.util.logging.Level;

public class ScreenCreationListener implements Listener {

    private enum Stage { FIRST, SECOND }

    private static class SelectionState {
        Stage stage = Stage.FIRST;
        String screenName;
        Block firstBlock;
        BlockFace firstFace;

        SelectionState(String screenName) {
            this.screenName = screenName;
        }
    }

    private final Map<UUID, SelectionState> pending = new HashMap<>();
    private final MCMovie plugin;
    private final ScreenManager screenManager;

    public ScreenCreationListener(MCMovie plugin, ScreenManager screenManager) {
        this.plugin = plugin;
        this.screenManager = screenManager;
    }

    /**
     * Called by MCMovieCommand to put a player into screen creation mode.
     */
    public void startSelection(Player player, String screenName) {
        pending.put(player.getUniqueId(), new SelectionState(screenName));
        player.sendMessage(Component.text("[MCMovie] Click the first corner of the screen wall.", NamedTextColor.AQUA));
    }

    public boolean isSelecting(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    public void cancelSelection(Player player) {
        pending.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        SelectionState state = pending.get(player.getUniqueId());
        if (state == null) return;

        // Cancel the event so we don't break the block
        event.setCancelled(true);

        Block clickedBlock = event.getClickedBlock();
        BlockFace clickedFace = event.getBlockFace();

        if (clickedBlock == null) return;

        // Only allow wall faces (horizontal)
        if (clickedFace != BlockFace.NORTH && clickedFace != BlockFace.SOUTH
                && clickedFace != BlockFace.EAST && clickedFace != BlockFace.WEST) {
            player.sendMessage(Component.text("[MCMovie] Please click on a wall face (north/south/east/west).", NamedTextColor.RED));
            return;
        }

        if (state.stage == Stage.FIRST) {
            state.firstBlock = clickedBlock;
            state.firstFace = clickedFace;
            state.stage = Stage.SECOND;
            plugin.debug("Screen creation '" + state.screenName + "': first corner="
                    + clickedBlock.getX() + "," + clickedBlock.getY() + "," + clickedBlock.getZ()
                    + " face=" + clickedFace.name()
                    + " block=" + clickedBlock.getType());
            player.sendMessage(Component.text("[MCMovie] First corner set at " +
                    clickedBlock.getX() + ", " + clickedBlock.getY() + ", " + clickedBlock.getZ() +
                    " (face: " + clickedFace.name() + "). Now click the second corner.", NamedTextColor.AQUA));
        } else {
            // Second click
            Block secondBlock = clickedBlock;
            BlockFace secondFace = clickedFace;

            // Validate same world
            if (!secondBlock.getWorld().equals(state.firstBlock.getWorld())) {
                player.sendMessage(Component.text("[MCMovie] Both corners must be in the same world!", NamedTextColor.RED));
                return;
            }

            // Validate same face direction
            if (secondFace != state.firstFace) {
                player.sendMessage(Component.text("[MCMovie] Both corners must be on the same face direction! " +
                        "(First: " + state.firstFace.name() + ", Second: " + secondFace.name() + ")", NamedTextColor.RED));
                return;
            }

            // For N/S walls, both Z should be the same (blocks must be on the same wall plane)
            // For E/W walls, both X should be the same
            boolean sameAxis;
            switch (state.firstFace) {
                case NORTH:
                case SOUTH:
                    sameAxis = state.firstBlock.getZ() == secondBlock.getZ();
                    break;
                case EAST:
                case WEST:
                    sameAxis = state.firstBlock.getX() == secondBlock.getX();
                    break;
                default:
                    sameAxis = false;
            }

            if (!sameAxis) {
                player.sendMessage(Component.text("[MCMovie] Both corners must be on the same wall plane!", NamedTextColor.RED));
                return;
            }

            pending.remove(player.getUniqueId());

            plugin.debug("Screen creation '" + state.screenName + "': second corner="
                    + secondBlock.getX() + "," + secondBlock.getY() + "," + secondBlock.getZ()
                    + " face=" + secondFace.name()
                    + " block=" + secondBlock.getType());

            // Check if screen name already exists
            if (screenManager.getScreen(state.screenName) != null) {
                player.sendMessage(Component.text("[MCMovie] A screen named '" + state.screenName + "' already exists. Use a different name.", NamedTextColor.RED));
                return;
            }

            List<Location> positions = new ArrayList<>();
            positions.add(state.firstBlock.getLocation());
            positions.add(secondBlock.getLocation());

            try {
                Screen screen = screenManager.createScreen(
                        state.screenName,
                        state.firstBlock.getWorld(),
                        state.firstFace,
                        positions
                );

                player.sendMessage(Component.text("[MCMovie] Screen '" + screen.getId() + "' created! (" +
                        screen.getWidthMaps() + "x" + screen.getHeightMaps() + " map tiles)", NamedTextColor.GREEN));

                // Save screens
                plugin.saveScreensNow();

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to create screen", e);
                player.sendMessage(Component.text("[MCMovie] Failed to create screen: " + e.getMessage(), NamedTextColor.RED));
            }
        }
    }
}
