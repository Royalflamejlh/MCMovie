package mc.rooyal.mCMovie.event;

import mc.rooyal.mCMovie.screen.Screen;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class ScreenVideoEndEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Screen screen;

    public ScreenVideoEndEvent(Screen screen) {
        this.screen = screen;
    }

    public Screen getScreen() { return screen; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
