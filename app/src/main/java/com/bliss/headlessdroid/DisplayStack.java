package com.bliss.headlessdroid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import java.lang.reflect.Field;

/**
 * One real, physical phone screen: a {@link VirtualDisplay} and the
 * {@link ImageReader} surface that captures what it renders.
 *
 * <p>Creating a public virtual display needs no special permission, so the
 * display (and therefore every preview frame, screenshot and recorded video)
 * is produced inside the app's own process. Only launching an app onto the
 * display and injecting input into it need a privileged identity, which is what
 * {@link Shell} provides through Shizuku.
 */
final class DisplayStack {
    private static final String TAG = "HeadlessDroid";

    private final int width;
    private final int height;
    private final int dpi;
    private final ImageReader reader;
    private final VirtualDisplay display;
    private final int displayId;
    private final String flagsLabel;
    private final Bitmap staging;
    private final int paddedWidth;

    /** Newest published frame. Never recycled while this stack is alive. */
    private volatile Bitmap latest;
    private volatile long frames;
    private volatile long lastFrameAt;

    private DisplayStack(int width, int height, int dpi, ImageReader reader,
                         VirtualDisplay display, int displayId, int flags) {
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        this.reader = reader;
        this.display = display;
        this.displayId = displayId;
        this.flagsLabel = describeFlags(flags);

        Bitmap scratch = null;
        int padded = width;
        try {
            android.media.Image image = reader.acquireLatestImage();
            if (image != null) {
                // The producer pads rows; the staging bitmap must match the
                // padded geometry so copyPixelsFromBuffer lands correctly.
                android.media.Image.Plane plane = image.getPlanes()[0];
                padded = Math.max(width, plane.getRowStride() / Math.max(1, plane.getPixelStride()));
                scratch = Bitmap.createBitmap(padded, height, Bitmap.Config.ARGB_8888);
                image.close();
            }
        } catch (Throwable ignored) {
        }
        if (scratch == null) {
            padded = width;
            scratch = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        this.paddedWidth = padded;
        this.staging = scratch;
    }

    static DisplayStack create(Context context, int width, int height, int dpi) {
        DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("DisplayManager unavailable");
        }
        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);

        int base = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        int touch = hiddenFlag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH");
        int focus = hiddenFlag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS");
        int trusted = hiddenFlag("VIRTUAL_DISPLAY_FLAG_TRUSTED");
        int[] candidates = new int[]{
                base | touch | focus | trusted,
                base | touch | focus,
                base | touch,
                base,
        };

        Throwable lastError = null;
        for (int flags : candidates) {
            VirtualDisplay created = null;
            try {
                created = manager.createVirtualDisplay(
                        "HeadlessDroid", width, height, dpi, reader.getSurface(), flags);
                Display target = created == null ? null : created.getDisplay();
                if (target != null) {
                    return new DisplayStack(width, height, dpi, reader, created,
                            target.getDisplayId(), flags);
                }
            } catch (Throwable e) {
                lastError = e;
            }
            if (created != null) {
                try {
                    created.release();
                } catch (Throwable ignored) {
                }
            }
        }
        reader.close();
        throw new IllegalStateException("createVirtualDisplay failed for every flag set", lastError);
    }

    int displayId() {
        return displayId;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    int dpi() {
        return dpi;
    }

    String flagsLabel() {
        return flagsLabel;
    }

    long frameCount() {
        return frames;
    }

    long lastFrameAt() {
        return lastFrameAt;
    }

    Bitmap latest() {
        return latest;
    }

    boolean alive() {
        try {
            VirtualDisplay local = display;
            return local != null && local.getDisplay() != null;
        } catch (Throwable e) {
            return false;
        }
    }

    String stateName() {
        try {
            int state = display.getDisplay().getState();
            switch (state) {
                case Display.STATE_OFF:
                    return "OFF";
                case Display.STATE_ON:
                    return "ON";
                case Display.STATE_DOZE:
                    return "DOZE";
                case Display.STATE_DOZE_SUSPEND:
                    return "DOZE_SUSPEND";
                case Display.STATE_VR:
                    return "VR";
                case Display.STATE_ON_SUSPEND:
                    return "ON_SUSPEND";
                default:
                    return "STATE_" + state;
            }
        } catch (Throwable e) {
            return "unknown";
        }
    }

    /**
     * Some ROMs create the display in the OFF state, in which case nothing is
     * ever composed. Detaching and re-attaching the surface turns it back ON.
     */
    boolean ensureOn() {
        if ("ON".equals(stateName())) {
            return true;
        }
        try {
            display.setSurface(null);
            SystemClock.sleep(120);
            display.setSurface(reader.getSurface());
            SystemClock.sleep(300);
        } catch (Throwable ignored) {
        }
        return "ON".equals(stateName());
    }

    /** Pulls the newest rendered frame into a fresh bitmap, or null if none. */
    Bitmap poll(long timeoutMs) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            long deadline = SystemClock.uptimeMillis() + Math.max(0, timeoutMs);
            while (image == null && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(20);
                try {
                    image = reader.acquireLatestImage();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (image == null) {
                return latest;
            }
            android.media.Image.Plane plane = image.getPlanes()[0];
            staging.copyPixelsFromBuffer(plane.getBuffer());
            Bitmap frame = paddedWidth == width
                    ? staging.copy(Bitmap.Config.ARGB_8888, false)
                    : Bitmap.createBitmap(staging, 0, 0, width, height);
            latest = frame;
            frames++;
            lastFrameAt = SystemClock.uptimeMillis();
            return frame;
        } catch (Throwable e) {
            Log.w(TAG, "frame conversion failed: " + e);
            return latest;
        } finally {
            try {
                if (image != null) {
                    image.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    void release() {
        try {
            display.release();
        } catch (Throwable ignored) {
        }
        try {
            reader.close();
        } catch (Throwable ignored) {
        }
        try {
            if (!staging.isRecycled()) {
                staging.recycle();
            }
        } catch (Throwable ignored) {
        }
        latest = null;
    }

    private static int hiddenFlag(String name) {
        try {
            Field field = DisplayManager.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String describeFlags(int flags) {
        StringBuilder out = new StringBuilder();
        if ((flags & DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC) != 0) {
            out.append("PUBLIC|");
        }
        if ((flags & DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION) != 0) {
            out.append("PRESENTATION|");
        }
        if ((flags & DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY) != 0) {
            out.append("OWN_CONTENT_ONLY|");
        }
        int touch = hiddenFlag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH");
        int focus = hiddenFlag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS");
        int trusted = hiddenFlag("VIRTUAL_DISPLAY_FLAG_TRUSTED");
        if (touch != 0 && (flags & touch) != 0) {
            out.append("SUPPORTS_TOUCH|");
        }
        if (focus != 0 && (flags & focus) != 0) {
            out.append("OWN_FOCUS|");
        }
        if (trusted != 0 && (flags & trusted) != 0) {
            out.append("TRUSTED|");
        }
        if (out.length() > 0) {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }
}