package mc.rooyal.mCMovie.listener;

import mc.rooyal.mCMovie.screen.Screen;
import mc.rooyal.mCMovie.screen.ScreenManager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import java.util.UUID;

public class ScreenProtectionListener implements Listener {

    private final ScreenManager screenManager;

    public ScreenProtectionListener(ScreenManager screenManager) {
        this.screenManager = screenManager;
    }

    private boolean isScreenFrame(Entity entity) {
        if (!(entity instanceof ItemFrame)) return false;
        UUID uuid = entity.getUniqueId();
        for (Screen screen : screenManager.getAllScreens().values()) {
            if (screen.getItemFrameUuids().contains(uuid)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (isScreenFrame(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityDamage(EntityDamageEvent event) {
        if (isScreenFrame(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (isScreenFrame(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }
}
