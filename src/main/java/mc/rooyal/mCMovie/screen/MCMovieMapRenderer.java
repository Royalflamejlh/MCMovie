package mc.rooyal.mCMovie.screen;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

public class MCMovieMapRenderer extends MapRenderer {

    // Pre-converted Minecraft map palette indices (128*128 bytes).
    // Written by the video thread; read by the main thread in render().
    private volatile byte[] currentBytes = null;

    // The last byte[] reference that was actually written into the canvas buffer.
    // Because the canvas is shared across all players viewing the same MapView,
    // once the first player's render() call writes the pixels for a given frame,
    // every subsequent call within the same tick already finds the buffer correct
    // and can skip the entire setPixel loop (O(1) reference check instead of
    // 16,384 individual pixel writes per extra player per tile per tick).
    private byte[] lastRenderedBytes = null;

    public MCMovieMapRenderer() {
        super(false);
    }

    public void updateFrame(byte[] bytes) {
        this.currentBytes = bytes;
    }

    @Override
    @SuppressWarnings({"deprecation", "removal"})
    public void render(MapView map, MapCanvas canvas, Player player) {
        byte[] bytes = currentBytes;
        if (bytes == null) return;
        if (bytes == lastRenderedBytes) return; // canvas already up-to-date for this frame
        lastRenderedBytes = bytes;
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                canvas.setPixel(x, y, bytes[y * 128 + x]);
            }
        }
    }

    public boolean hasFrame() {
        return currentBytes != null;
    }
}
