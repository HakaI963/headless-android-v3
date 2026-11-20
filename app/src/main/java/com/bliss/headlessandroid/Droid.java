package com.bliss.headlessandroid;

/**
 * Product-level constants and tiny shared helpers.
 *
 * <p>Headless Android runs real, installed apps directly on virtual displays. No
 * repackaging, no clones, no second copy of anything: the app's own APK and its
 * own data are used as-is, on the user's own phone, in user space.
 */
public final class Droid {
    private Droid() {
    }

    public static final String PKG = "com.bliss.headlessandroid";
    public static final String TAG = "HeadlessAndroid";

    /** A 3x3 grid is the hard cap: 9 slots, at most 3 columns per row. */
    public static final int MAX_SLOTS = 9;
    public static final int MAX_COLUMNS = 3;

    /** Screen presets per tile: {width, height, dpi}. Portrait always. */
    public static final int[][] SCREENS = {
            {360, 480, 160},   // compact: 9 tiles at once
            {540, 720, 240},   // balanced: default
            {720, 960, 320},   // large: best quality, ~3 tiles comfortable
    };
    public static final int DEFAULT_SCREEN_INDEX = 1;
    public static final int DEFAULT_PORT = 8765;

    /** MCP clients identify with this header. */
    public static final String TOKEN_HEADER = "X-Droid-Token";

    public static final String DIR_SHOTS = "Download/HeadlessAndroid/shots";
    public static final String DIR_VIDEOS = "Download/HeadlessAndroid/videos";

    public static final String EXT_LAUNCHER = "moe.shizuku.privileged.api";

    public static int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    public static String safe(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    public static String hex() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder(32);
        for (byte value : bytes) {
            out.append(String.format(java.util.Locale.US, "%02x", value));
        }
        return out.toString();
    }
}