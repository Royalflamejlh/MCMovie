package mc.rooyal.mCMovie.event;

import mc.rooyal.mCMovie.screen.Screen;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class ScreenVideoChangeEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Screen screen;
    private final String oldUrl;
    private final String newUrl;
    private boolean cancelled = false;

    public ScreenVideoChangeEvent(Screen screen, String oldUrl, String newUrl) {
        this.screen = screen;
        this.oldUrl = oldUrl;
        this.newUrl = newUrl;
    }

    public Screen getScreen() { return screen; }
    public String getOldUrl() { return oldUrl; }
    public String getNewUrl() { return newUrl; }

    @Override
    public boolean isCancelled() { return cancelled; }

    @Override
    public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
