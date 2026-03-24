package mc.rooyal.mCMovie.screen;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.lang.reflect.Field;

public class MCMovieMapRenderer extends MapRenderer {

    // Pre-converted Minecraft map palette indices (128*128 bytes).
    // Written by the video thread; read by the main thread in render().
    private volatile byte[] currentBytes = null;

    // Tracks what is currently written into the shared canvas buffer.
    // Updated only inside render() — keeps Bukkit's initial map send correct
    // for players who load the map after playback has already started.
    private byte[] lastRenderedBytes = null;

    // Tracks what was last delivered via PacketEvents dirty-region packets.
    // Separate from lastRenderedBytes so that render() still works correctly
    // for newly joining players even after many patch packets have been sent.
    private byte[] lastSentBytes = null;

    // Lazily resolved reflection handle to CraftMapCanvas.buffer.
    // Lets render() do a single System.arraycopy instead of 16,384 setPixel calls.
    private static volatile Field canvasBufferField = null;
    private static volatile boolean canvasBufferFieldInit = false;

    // The actual byte[] backing the canvas for this renderer's MapView.
    // Cached on first render() so Field.get() is never called again.
    private byte[] cachedCanvasBuffer = null;

    /** A minimal dirty bounding-box snapshot of one tile's changed pixels. */
    public static final class Patch {
        /** Top-left corner and dimensions of the changed region (0–127). */
        public final int x, y, width, height;
        /** Row-major pixel data for the region, length == width * height. */
        public final byte[] data;

        Patch(int x, int y, int width, int height, byte[] data) {
            this.x = x; this.y = y; this.width = width; this.height = height;
            this.data = data;
        }
    }

    public MCMovieMapRenderer() {
        super(false);
    }

    public void updateFrame(byte[] bytes) {
        this.currentBytes = bytes;
    }

    /**
     * Computes the minimal dirty bounding box between the current frame and the
     * last PacketEvents-sent frame, extracts the changed pixel data, and marks
     * lastSentBytes so subsequent calls return null until the next updateFrame().
     *
     * Returns null if the tile has not changed since the last packet was sent.
     * Must be called on the main thread.
     */
    public Patch computeAndMarkPatch() {
        byte[] current = currentBytes;
        byte[] previous = lastSentBytes;
        if (current == null || current == previous) return null;
        lastSentBytes = current;

        int minX, maxX, minY, maxY;

        if (previous == null) {
            // First frame ever: send the full tile.
            minX = 0; minY = 0; maxX = 127; maxY = 127;
        } else {
            minX = 128; maxX = -1; minY = 128; maxY = -1;
            for (int i = 0; i < 128 * 128; i++) {
                if (current[i] != previous[i]) {
                    int x = i & 127;   // i % 128
                    int y = i >>> 7;   // i / 128
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
            if (maxX < 0) return null; // frames are bytewise equal
        }

        int w = maxX - minX + 1;
        int h = maxY - minY + 1;

        // Full tile: hand the array directly to avoid a copy.
        if (w == 128 && h == 128) return new Patch(0, 0, 128, 128, current);

        byte[] data = new byte[w * h];
        for (int row = 0; row < h; row++) {
            System.arraycopy(current, (minY + row) * 128 + minX, data, row * w, w);
        }
        return new Patch(minX, minY, w, h, data);
    }

    /**
     * Called by Bukkit when it needs to (re-)send a map to a player, e.g. when a
     * player first loads the area or re-enters range.  Keeps the canvas up to date
     * independently of the PacketEvents update path so new players always see the
     * correct frame.
     */
    @Override
    @SuppressWarnings({"deprecation", "removal"})
    public void render(MapView map, MapCanvas canvas, Player player) {
        byte[] bytes = currentBytes;
        if (bytes == null) return;
        if (bytes == lastRenderedBytes) return; // canvas already current
        lastRenderedBytes = bytes;

        // Fast path: write directly into CraftMapCanvas.buffer via reflection —
        // one System.arraycopy instead of 16,384 setPixel() calls.
        if (!canvasBufferFieldInit) {
            initCanvasBufferField(canvas);
        }
        if (canvasBufferField != null) {
            try {
                if (cachedCanvasBuffer == null) {
                    // Resolve once and cache — the canvas buffer never changes for a given MapView.
                    cachedCanvasBuffer = (byte[]) canvasBufferField.get(canvas);
                }
                System.arraycopy(bytes, 0, cachedCanvasBuffer, 0, 128 * 128);
                return;
            } catch (IllegalAccessException e) {
                canvasBufferField = null;
                cachedCanvasBuffer = null; // fall through to setPixel
            }
        }

        // Fallback: iterate setPixel if reflection is unavailable.
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                canvas.setPixel(x, y, bytes[y * 128 + x]);
            }
        }
    }

    private static synchronized void initCanvasBufferField(MapCanvas canvas) {
        if (canvasBufferFieldInit) return;
        canvasBufferFieldInit = true;
        try {
            Field f = canvas.getClass().getDeclaredField("buffer");
            f.setAccessible(true);
            canvasBufferField = f;
        } catch (Exception ignored) {
            canvasBufferField = null;
        }
    }

    public boolean hasFrame() {
        return currentBytes != null;
    }
}
