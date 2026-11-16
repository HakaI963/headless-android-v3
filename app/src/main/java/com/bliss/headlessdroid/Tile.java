package com.bliss.headlessdroid;

import android.graphics.Bitmap;

import java.util.Comparator;

/**
 * One slot in the grid: one real app running on its own virtual display.
 *
 * <p>A tile never clones, repackages or re-signs anything. The phone's own
 * installed app is started with {@code am start --display <id>}, so the APK,
 * the data directory and the signature identity all stay the original one.
 */
public final class Tile {
    /** Lifecycle state of the slot, mirrored in the UI and in the MCP status. */
    public static final String IDLE = "idle";
    public static final String STARTING = "starting";
    public static final String LIVE = "live";
    public static final String STOPPED = "stopped";
    public static final String ERROR = "error";

    public final int slot;               // 1..9
    public final String pkg;             // package name of the real app
    public volatile String label;        // human label shown on the tile
    public volatile String component;    // pkg/.Activity that was launched
    public final int width;              // virtual display geometry (portrait)
    public final int height;
    public final int dpi;

    public volatile DisplayStack stack;  // null until the display is created
    public volatile int displayId = -1;
    public volatile String state = IDLE;
    public volatile String error = "";
    public volatile long frames;         // frames captured since launch
    public volatile long lastFrameAt;    // uptime millis of the last frame
    public volatile boolean recording;
    public volatile long createdUptime;

    public Tile(int slot, String pkg, String label, int width, int height, int dpi) {
        this.slot = slot;
        this.pkg = pkg;
        this.label = label;
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        this.createdUptime = android.os.SystemClock.uptimeMillis();
    }

    public boolean busy() {
        return STARTING.equals(state);
    }

    /** The newest captured frame, or null before the first one lands. */
    public android.graphics.Bitmap latest() {
        DisplayStack local = stack;
        return local == null ? null : local.latest();
    }

    public boolean running() {
        DisplayStack local = stack;
        return local != null && local.alive();
    }

    public String stateLabel() {
        if (ERROR.equals(state)) {
            return "error";
        }
        return state;
    }

    public int statusColor() {
        switch (state) {
            case LIVE:
                return com.bliss.headlessdroid.R.color.live;
            case STARTING:
                return com.bliss.headlessdroid.R.color.accent;
            case STOPPED:
                return com.bliss.headlessdroid.R.color.stopped;
            case ERROR:
                return com.bliss.headlessdroid.R.color.danger;
            default:
                return com.bliss.headlessdroid.R.color.idle;
        }
    }

    /** Sort key so the grid reads slot 1, 2, 3 ... */
    public static final Comparator<Tile> BY_SLOT = (a, b) -> Integer.compare(a.slot, b.slot);

    /** Frees every resource the slot holds. Safe to call twice. */
    public void release() {
        recording = false;
        DisplayStack local = stack;
        stack = null;
        if (local != null) {
            local.release();
        }
    }
}