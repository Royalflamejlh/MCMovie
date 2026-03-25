package mc.rooyal.mCMovie.api;

import mc.rooyal.mCMovie.MCMovie;
import mc.rooyal.mCMovie.screen.Screen;
import mc.rooyal.mCMovie.video.VideoPlayer;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * Public API for MCMovie. Use these static methods to control screens from other plugins.
 *
 * <p>All methods are null-safe: if MCMovie is not loaded or a screen ID is unknown they return
 * a sensible default ({@code false}, {@code null}, empty collection) rather than throwing.
 *
 * <p>Events fired by MCMovie ({@link mc.rooyal.mCMovie.event.ScreenVideoStartEvent},
 * {@link mc.rooyal.mCMovie.event.ScreenVideoChangeEvent},
 * {@link mc.rooyal.mCMovie.event.ScreenVideoEndEvent}) are standard Bukkit events — register
 * listeners with Bukkit's plugin manager to react to playback changes.
 */
public class MCMovieAPI {

    private MCMovieAPI() {}

    // ── Plugin state ──────────────────────────────────────────────────────────

    /**
     * Returns true if MCMovie is loaded and its plugin instance is active.
     * Always check this before calling other API methods if MCMovie is a soft-depend.
     */
    public static boolean isAvailable() {
        return MCMovie.getInstance() != null;
    }

    /**
     * Returns true if SimpleVoiceChat is present and the audio pipeline is ready.
     */
    public static boolean isAudioEnabled() {
        MCMovie plugin = MCMovie.getInstance();
        return plugin != null && plugin.isVoiceChatEnabled();
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    /**
     * Start playing a URL or local filename on the given screen.
     * Stops any current playback first.
     *
     * @param screenId the screen ID
     * @param url      HTTP/HTTPS URL, HLS stream, or filename relative to {@code plugins/MCMovie/videos/}
     * @return true if the screen was found and playback was initiated
     */
    public static boolean play(String screenId, String url) {
        MCMovie plugin = MCMovie.getInstance();
        if (plugin == null) return false;

        Screen screen = plugin.getScreenManager().getScreen(screenId);
        if (screen == null) return false;

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
     * @return true if the screen was found (regardless of whether it was playing)
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
     * Stop playback on all screens.
     */
    public static void stopAll() {
        MCMovie plugin = MCMovie.getInstance();
        if (plugin == null) return;

        for (Screen screen : plugin.getScreenManager().getAllScreens().values()) {
            if (screen.getVideoPlayer() != null) {
                screen.getVideoPlayer().stop();
                screen.setVideoPlayer(null);
            }
            screen.setPlaying(false);
        }
    }

    /**
     * Start playing the same URL on every screen simultaneously.
     *
     * @param url URL or filename to play on all screens
     */
    public static void playOnAll(String url) {
        MCMovie plugin = MCMovie.getInstance();
        if (plugin == null) return;

        for (Screen screen : plugin.getScreenManager().getAllScreens().values()) {
            if (screen.getVideoPlayer() != null) {
                screen.getVideoPlayer().stop();
            }
            VideoPlayer vp = new VideoPlayer(screen, plugin);
            screen.setVideoPlayer(vp);
            vp.play(url);
        }
    }

    // ── Screen queries ────────────────────────────────────────────────────────

    /**
     * Returns the Screen object for the given ID, or null if not found.
     */
    public static Screen getScreen(String id) {
        MCMovie plugin = MCMovie.getInstance();
        if (plugin == null) return null;
        return plugin.getScreenManager().getScreen(id);
    }

    /**
     * Returns an unmodifiable collection of all registered screens.
     */
    public static Collection<Screen> getAllScreens() {
        MCMovie plugin = MCMovie.getInstance();
        if (plugin == null) return Collections.emptyList();
        return Collections.unmodifiableCollection(plugin.getScreenManager().getAllScreens().values());
    }

    /**
     * Returns an unmodifiable set of all registered screen IDs.
     */
    public static Set<String> getScreenIds() {
        MCMovie plugin = MCMovie.getInstance();
        if (plugin == null) return Collections.emptySet();
        return Collections.unmodifiableSet(plugin.getScreenManager().getAllScreens().keySet());
    }

    /**
     * Returns the number of registered screens.
     */
    public static int getScreenCount() {
        MCMovie plugin = MCMovie.getInstance();
        if (plugin == null) return 0;
        return plugin.getScreenManager().getAllScreens().size();
    }

    /**
     * Returns true if a screen with the given ID exists.
     */
    public static boolean screenExists(String id) {
        MCMovie plugin = MCMovie.getInstance();
        if (plugin == null) return false;
        return plugin.getScreenManager().getScreen(id) != null;
    }

    // ── Playback state ────────────────────────────────────────────────────────

    /**
     * Returns true if the given screen exists and is currently playing.
     */
    public static boolean isPlaying(String screenId) {
        Screen screen = getScreen(screenId);
        return screen != null && screen.isPlaying();
    }

    /**
     * Returns the URL/file currently playing on the given screen, or null if idle or not found.
     */
    public static String getCurrentUrl(String screenId) {
        Screen screen = getScreen(screenId);
        if (screen == null || !screen.isPlaying()) return null;
        return screen.getCurrentUrl();
    }
}
