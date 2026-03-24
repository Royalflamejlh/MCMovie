package mc.rooyal.mCMovie.video;

import java.io.File;

public class StreamResolver {

    private StreamResolver() {}

    /**
     * Returns true if the input looks like a local file reference (no "://" scheme other than file://).
     */
    public static boolean isLocalFile(String input) {
        if (input == null) return false;
        if (input.startsWith("file://")) return true;
        return !input.contains("://");
    }

    /**
     * Resolves a local file input to an absolute path.
     * If input contains no path separator, looks inside videosDir.
     */
    public static String resolveLocalPath(String input, File videosDir) {
        if (input.startsWith("file://")) {
            input = input.substring(7);
        }

        File f = new File(input);
        if (f.isAbsolute()) {
            return f.getAbsolutePath();
        }

        if (!input.contains(File.separator) && !input.contains("/")) {
            File inVideos = new File(videosDir, input);
            if (inVideos.exists()) {
                return inVideos.getAbsolutePath();
            }
        }

        return f.getAbsolutePath();
    }
}
