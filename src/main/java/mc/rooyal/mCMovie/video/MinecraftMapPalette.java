package mc.rooyal.mCMovie.video;

/**
 * Self-contained Minecraft map colour palette with a 64³ lookup cache.
 *
 * Palette index = baseIndex * 4 + shade  (shade 0-3)
 * Shade multipliers: 0 → 180/255, 1 → 220/255, 2 → 255/255, 3 → 135/255
 * Indices 0-3 (base 0) are transparent / unused; matchColor never returns them.
 */
public final class MinecraftMapPalette {

    private static final int[][] BASE = {
        {  0,   0,   0}, {127, 178,  56}, {247, 233, 163}, {199, 199, 199},
        {255,   0,   0}, {160, 160, 255}, {167, 167, 167}, {  0, 124,   0},
        {255, 255, 255}, {164, 168, 184}, {151, 109,  77}, {112, 112, 112},
        { 64,  64, 255}, {143, 119,  72}, {255, 252, 245}, {216, 127,  51},
        {178,  76, 216}, {102, 153, 216}, {229, 229,  51}, {127, 204,  25},
        {242, 127, 165}, { 76,  76,  76}, {153, 153, 153}, { 76, 127, 153},
        {127,  63, 178}, { 51,  76, 178}, {102,  76,  51}, {102, 127,  51},
        {153,  51,  51}, { 25,  25,  25}, {250, 238,  77}, { 92, 219, 213},
        { 74, 128, 255}, {  0, 217,  58}, {129,  86,  49}, {112,   2,   0},
        {209, 177, 161}, {159,  82,  36}, {149,  87, 108}, {112, 108, 138},
        {186, 133,  36}, {103, 117,  53}, {160,  77,  78}, { 57,  41,  35},
        {135, 107,  98}, { 87,  92,  92}, {122,  73,  88}, { 76,  62,  92},
        { 76,  50,  35}, { 76,  82,  42}, {142,  60,  46}, { 37,  22,  16},
        {189,  48,  49}, {148,  63,  97}, { 92,  25,  29}, { 22, 126, 134},
        { 58, 142, 140}, { 86,  44,  62}, { 20, 180, 133}, {100, 100, 100},
        {216, 175, 147}, {127, 167, 150}
    };

    private static final int[] SHADE_MUL = {180, 220, 255, 135};

    /** Precomputed RGB int[3] for each palette index (indices 0-3 are null). */
    private static final int[][] RGB = new int[256][];

    static {
        for (int base = 1; base < BASE.length; base++) {
            for (int shade = 0; shade < 4; shade++) {
                int idx = base * 4 + shade;
                if (idx > 255) break;
                int mul = SHADE_MUL[shade];
                RGB[idx] = new int[]{
                    BASE[base][0] * mul / 255,
                    BASE[base][1] * mul / 255,
                    BASE[base][2] * mul / 255
                };
            }
        }
    }

    // 64³ cache keyed by (r>>2, g>>2, b>>2). Separate filled array because byte 0 is valid.
    private static final byte[]    COLOR_CACHE        = new byte[64 * 64 * 64];
    private static final boolean[] COLOR_CACHE_FILLED = new boolean[64 * 64 * 64];

    /** Returns the palette index whose RGB is nearest to (r, g, b). Thread-safe after warm-up. */
    public static byte matchColor(int r, int g, int b) {
        int key = ((r >> 2) << 12) | ((g >> 2) << 6) | (b >> 2);
        if (COLOR_CACHE_FILLED[key]) return COLOR_CACHE[key];

        byte best = 4;
        int  bestDist = Integer.MAX_VALUE;
        for (int i = 4; i < 256; i++) {
            if (RGB[i] == null) continue;
            int dr = r - RGB[i][0];
            int dg = g - RGB[i][1];
            int db = b - RGB[i][2];
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) { bestDist = dist; best = (byte) i; }
        }
        COLOR_CACHE[key]        = best;
        COLOR_CACHE_FILLED[key] = true;
        return best;
    }

    /**
     * Pre-warms the full 64³ lookup cache by visiting every quantised colour.
     * Call this once asynchronously at startup so the first video frame doesn't
     * pay the cold-cache cost (~65 M comparisons total, typically 50-150 ms).
     */
    public static void prewarm() {
        for (int r = 0; r < 256; r += 4) {
            for (int g = 0; g < 256; g += 4) {
                for (int b = 0; b < 256; b += 4) {
                    matchColor(r, g, b);
                }
            }
        }
    }

    private MinecraftMapPalette() {}
}
