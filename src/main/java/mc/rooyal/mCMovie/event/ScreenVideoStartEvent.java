package mc.rooyal.mCMovie.event;

import mc.rooyal.mCMovie.screen.Screen;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class ScreenVideoStartEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Screen screen;
    private final String url;

    public ScreenVideoStartEvent(Screen screen, String url) {
        this.screen = screen;
        this.url = url;
    }

    public Screen getScreen() { return screen; }
    public String getUrl() { return url; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
