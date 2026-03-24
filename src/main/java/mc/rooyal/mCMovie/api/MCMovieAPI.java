package mc.rooyal.mCMovie.api;

import mc.rooyal.mCMovie.MCMovie;
import mc.rooyal.mCMovie.screen.Screen;
import mc.rooyal.mCMovie.video.VideoPlayer;
import java.io.File;

import java.util.Collection;
import java.util.Collections;

/**
 * Public API for MCMovie plugin. Use these static methods to control screens programmatically.
 */
public class MCMovieAPI {

    private MCMovieAPI() {}

    /**
     * Start playing a URL or file on the given screen.
     *
     * @param screenId the screen ID
     * @param url      URL or filename (relative to plugin/videos/) to play
     * @return true if the screen was found and play was initiated
     */
    public static boolean play(String screenId, String url) {
        MCMovie plugin = MCMovie.getInstance();
        if (plugin == null) return false;

        Screen screen = plugin.getScreenManager().getScreen(screenId);
        if (screen == null) return false;

        // Stop current player if any
        if (screen.getVideoPlayer() != null) {
            screen.getVideoPlayer().stop();
        }

        VideoPlayer vp = new VideoPlayer(screen, plugin);
        screen.setVideoPlayer(vp);
        vp.play(url);
        return true;
    }

    /**
     * Stop playback on the given screen.
     *
     * @param screenId the screen ID
     * @return true if the screen was found and stopped
     */
    public static boolean stop(String screenId) {
        MCMovie plugin = MCMovie.getInstance();
        if (plugin == null) return false;

        Screen screen = plugin.getScreenManager().getScreen(screenId);
        if (screen == null) return false;

        if (screen.getVideoPlayer() != null) {
            screen.getVideoPlayer().stop();
            screen.setVideoPlayer(null);
        }
        screen.setPlaying(false);
        return true;
    }

    /**
     * Get a screen by ID.
     *
     * @param id screen ID
     * @return the Screen, or null if not found
     */
    public static Screen getScreen(String id) {
        MCMovie plugin = MCMovie.getInstance();
        if (plugin == null) return null;
        return plugin.getScreenManager().getScreen(id);
    }

    /**
     * Check whether a screen is currently playing.
     *
     * @param screenId the screen ID
     * @return true if the screen exists and is playing
     */
    public static boolean isPlaying(String screenId) {
        Screen screen = getScreen(screenId);
        return screen != null && screen.isPlaying();
    }

    /**
     * Get all registered screens (unmodifiable).
     *
     * @return collection of all screens
     */
    public static Collection<Screen> getAllScreens() {
        MCMovie plugin = MCMovie.getInstance();
        if (plugin == null) return Collections.emptyList();
        return Collections.unmodifiableCollection(plugin.getScreenManager().getAllScreens().values());
    }
}
