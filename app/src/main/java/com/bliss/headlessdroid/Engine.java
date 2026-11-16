package com.bliss.headlessdroid;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The grid: nine slots, each one real app running on its own virtual display.
 *
 * <p>This is the single object the UI, the MCP endpoint and the recorder all
 * talk to. It owns slots, launches, input dispatch, frame capture and the
 * lifecycle of the display stacks. Everything expensive happens on
 * {@link #worker} so the UI thread only reads volatile state.
 */
public final class Engine {
    private static final String TAG = "HeadlessDroid";
    private static final int TICK_MS = 45;

    private static volatile Engine instance;

    private final Context context;
    private final Handler worker;
    private final HandlerThread thread;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Tile[] tiles = new Tile[Droid.MAX_SLOTS + 1];
    private final PowerManager.WakeLock wakeLock;

    private volatile List<AppEntry> appCache;
    private volatile long appCacheAt;
    private volatile boolean running;
    private volatile long lastUiPing;
    private volatile String lastError = "";
    private final long startedAt = SystemClock.uptimeMillis();

    // --------------------------------------------------------------- app entry

    /** One launchable app on the phone. */
    public static final class AppEntry {
        public final String label;
        public final String pkg;
        public final String component;

        AppEntry(String label, String pkg, String component) {
            this.label = label;
            this.pkg = pkg;
            this.component = component;
        }
    }

    // ---------------------------------------------------------------- lifecycle

    public static Engine start(Context context) {
        Engine local = instance;
        if (local == null) {
            synchronized (Engine.class) {
                if (instance == null) {
                    instance = new Engine(context.getApplicationContext());
                }
                local = instance;
            }
        }
        return local;
    }

    public static Engine get() {
        return instance;
    }

    private Engine(Context context) {
        this.context = context;
        this.thread = new HandlerThread("HeadlessDroid");
        this.thread.start();
        this.worker = new Handler(this.thread.getLooper());
        PowerManager.WakeLock lock = null;
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power != null) {
                lock = power.newWakeLock(PowerManager.FULL_WAKE_LOCK, "HeadlessDroid:grid");
                lock.setReferenceCounted(false);
            }
        } catch (Throwable ignored) {
        }
        this.wakeLock = lock;
        this.running = true;
        worker.post(this::loop);
        worker.post(this::keepAwake);
        worker.post(this::loadAppsTask);
        worker.post(this::startMcp);
    }

    /**
     * The MCP endpoint comes up with the engine, because an AI client should be
     * able to connect before anything is running and see an empty grid. The
     * token is the only gate; see {@link McpServer}.
     */
    private void startMcp() {
        try {
            McpServer.start(this, Prefs.port(context), Prefs.token(context));
        } catch (Throwable e) {
            lastError = message(e);
            Log.w(TAG, "mcp start failed: " + e);
        }
    }

    public Context context() {
        return context;
    }

    public void shutdown() {
        running = false;
        for (int slot = 1; slot <= Droid.MAX_SLOTS; slot++) {
            closeLocked(slot);
        }
        releaseWake();
        try {
            McpServer.stop();
        } catch (Throwable ignored) {
        }
        try {
            thread.quitSafely();
        } catch (Throwable ignored) {
        }
    }

    /** Applies a new port or token from Settings without reconnecting. */
    public void restartMcp() {
        worker.post(() -> {
            try {
                McpServer.restart(this, Prefs.port(context), Prefs.token(context));
            } catch (Throwable e) {
                lastError = message(e);
            }
        });
    }

    public String mcpEndpoint() {
        String base = McpServer.endpoint();
        return base == null ? "" : base;
    }

    private void keepAwake() {
        boolean want = Prefs.keepAwake(context) && count() > 0;
        if (wakeLock == null) {
            return;
        }
        try {
            if (want && !wakeLock.isHeld()) {
                wakeLock.acquire();
            } else if (!want && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
        }
    }

    private void releaseWake() {
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public void refreshWake() {
        worker.post(this::keepAwake);
    }

    // ------------------------------------------------------------------- ticks

    private void loop() {
        if (!running) {
            return;
        }
        try {
            boolean dirty = false;
            long now = SystemClock.uptimeMillis();
            for (int slot = 1; slot <= Droid.MAX_SLOTS; slot++) {
                Tile tile = tiles[slot];
                if (tile == null) {
                    continue;
                }
                DisplayStack stack = tile.stack;
                if (stack == null || !stack.alive()) {
                    continue;
                }
                Bitmap frame = stack.poll(0);
                if (tile.frames != stack.frameCount()) {
                    tile.frames = stack.frameCount();
                    tile.lastFrameAt = stack.lastFrameAt();
                    if (Tile.STARTING.equals(tile.state) && tile.frames > 0) {
                        tile.state = Tile.LIVE;
                        dirty = true;
                        Log.i(TAG, "slot " + slot + " live: " + tile.pkg);
                    }
                    if (!Tile.ERROR.equals(tile.state)) {
                        dirty = true;
                    }
                }
                if (Tile.LIVE.equals(tile.state) && tile.recording) {
                    Recorder.feed(slot, frame);
                }
            }
            if (dirty) {
                pingUi();
            }
        } catch (Throwable e) {
            lastError = message(e);
            Log.w(TAG, "loop error: " + e);
        }
        worker.postDelayed(this::loop, TICK_MS);
    }

    /** Throttled "something changed" for the UI: at most 20 pings per second. */
    private void pingUi() {
        long now = SystemClock.uptimeMillis();
        if (now - lastUiPing < 50) {
            main.postDelayed(() -> pingListeners(), 50 - (now - lastUiPing));
            lastUiPing = now + 50;
            return;
        }
        lastUiPing = now;
        main.post(this::pingListeners);
    }

    private void pingListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Throwable ignored) {
            }
        }
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    // -------------------------------------------------------------------- slots

    private void loadAppsTask() {
        List<AppEntry> found = new ArrayList<>();
        try {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> installed = pm.getInstalledApplications(0);
            for (ApplicationInfo info : installed) {
                Intent intent = pm.getLaunchIntentForPackage(info.packageName);
                if (intent == null) {
                    continue;
                }
                ComponentName component = intent.getComponent();
                if (component == null) {
                    continue;
                }
                String label;
                try {
                    label = String.valueOf(pm.getApplicationLabel(info));
                } catch (Throwable ignored) {
                    label = info.packageName;
                }
                found.add(new AppEntry(label, info.packageName, component.flattenToShortString()));
            }
            found.sort(Comparator.comparing(a -> a.label.toLowerCase(Locale.US)));
        } catch (Throwable e) {
            Log.w(TAG, "app listing failed: " + e);
        }
        appCache = found;
        appCacheAt = SystemClock.uptimeMillis();
        pingListeners();
    }

    public List<Tile> tiles() {
        List<Tile> out = new ArrayList<>();
        synchronized (lock) {
            for (int slot = 1; slot <= Droid.MAX_SLOTS; slot++) {
                Tile tile = tiles[slot];
                if (tile != null) {
                    out.add(tile);
                }
            }
        }
        out.sort(Tile.BY_SLOT);
        return out;
    }

    public int count() {
        synchronized (lock) {
            int total = 0;
            for (int slot = 1; slot <= Droid.MAX_SLOTS; slot++) {
                if (tiles[slot] != null) {
                    total++;
                }
            }
            return total;
        }
    }

    public int freeSlots() {
        return Droid.MAX_SLOTS - count();
    }

    public Tile tile(int slot) {
        synchronized (lock) {
            if (slot < 1 || slot > Droid.MAX_SLOTS) {
                return null;
            }
            return tiles[slot];
        }
    }

    public Tile tileForPkg(String pkg) {
        synchronized (lock) {
            for (int slot = 1; slot <= Droid.MAX_SLOTS; slot++) {
                Tile tile = tiles[slot];
                if (tile != null && tile.pkg.equals(pkg)) {
                    return tile;
                }
            }
        }
        return null;
    }

    private int firstFreeSlot() {
        synchronized (lock) {
            for (int slot = 1; slot <= Droid.MAX_SLOTS; slot++) {
                if (tiles[slot] == null) {
                    return slot;
                }
            }
        }
        return -1;
    }

    public int columns() {
        return Prefs.columns(context);
    }

    public void setColumns(int value) {
        Prefs.columns(context, value);
        pingListeners();
    }

    // ------------------------------------------------------------------- launch

    /**
     * Adds one app to the grid.
     *
     * <p>Order of operations is deliberate: the display is created first (that
     * needs no permission), then the real app is asked to start on it. Nothing
     * is copied, reinstalled or re-signed, so the app keeps its own data, its
     * own signing key and its own settings.
     */
    public Tile launch(String pkg) {
        return launch(pkg, -1, true);
    }

    public Tile launch(String pkg, int slot, boolean forceStop) {
        AppEntry entry = entryFor(pkg);
        if (entry == null) {
            Log.w(TAG, "not launchable: " + pkg);
            return null;
        }
        synchronized (lock) {
            int target = slot > 0 ? slot : firstFreeSlot();
            if (target < 1) {
                Log.i(TAG, "grid is full");
                return null;
            }
            if (tiles[target] != null) {
                closeLocked(target);
            }
            int[] screen = Droid.SCREENS[Prefs.screen(context)];
            Tile tile = new Tile(target, entry.pkg, entry.label, screen[0], screen[1], screen[2]);
            tile.component = entry.component;
            tile.state = Tile.STARTING;
            tiles[target] = tile;
            worker.post(() -> launchWorker(tile, entry, forceStop));
            refreshWake();
            rememberPackages();
            pingListeners();
            return tile;
        }
    }

    private void launchWorker(Tile tile, AppEntry entry, boolean forceStop) {
        try {
            if (forceStop) {
                stopShell(entry.pkg);
            }
            DisplayStack stack = DisplayStack.create(context, tile.width, tile.height, tile.dpi);
            tile.stack = stack;
            tile.displayId = stack.displayId();
            if (!stack.ensureOn()) {
                Log.w(TAG, "slot " + tile.slot + " display not ON yet: " + stack.stateName());
            }
            String error = startShell(entry.component, stack.displayId());
            if (error != null) {
                // Last resort without Shizuku: start the activity ourselves and
                // ask the framework to put it on the virtual display.
                Intent intent = context.getPackageManager()
                        .getLaunchIntentForPackage(entry.pkg);
                if (intent != null) {
                    String result = Shell.startWithoutShell(context, intent, stack.displayId());
                    if ("ok".equals(result)) {
                        error = null;
                        Log.i(TAG, "slot " + tile.slot + " launched without shell");
                    } else {
                        Log.w(TAG, "fallback launch failed: " + result);
                    }
                }
            }
            if (error != null) {
                tile.state = Tile.ERROR;
                tile.error = error;
                Log.w(TAG, "slot " + tile.slot + " launch failed: " + error);
            }
        } catch (Throwable e) {
            tile.state = Tile.ERROR;
            tile.error = message(e);
            Log.w(TAG, "slot " + tile.slot + " launch error: " + e);
        }
        pingListeners();
    }

    private String startShell(String component, int displayId) {
        Shell shell = Shell.get(context);
        if (!shell.ready()) {
            return "shell service not ready (" + shell.state() + ")";
        }
        try {
            JSONObject reply = shell.command("start")
                    .put("component", component)
                    .put("display", displayId);
            String out = reply == null ? "no reply" : reply.toString();
            if (reply == null || !reply.optBoolean("ok")) {
                return out;
            }
            Log.i(TAG, "start " + component + " on display " + displayId + ": " + out);
            return null;
        } catch (Throwable e) {
            return message(e);
        }
    }

    private void stopShell(String pkg) {
        try {
            Shell.get(context).command("stop").put("pkg", pkg);
        } catch (Throwable ignored) {
        }
    }

    // -------------------------------------------------------------------- close

    public boolean close(int slot) {
        synchronized (lock) {
            boolean had = tiles[slot] != null;
            closeLocked(slot);
            if (had) {
                refreshWake();
                rememberPackages();
                pingListeners();
            }
            return had;
        }
    }

    public void close(String pkg) {
        Tile tile = tileForPkg(pkg);
        if (tile != null) {
            close(tile.slot);
        }
    }

    public void closeAll() {
        worker.post(() -> {
            for (int slot = 1; slot <= Droid.MAX_SLOTS; slot++) {
                closeLocked(slot);
            }
            refreshWake();
            rememberPackages();
            pingListeners();
        });
    }

    private void closeLocked(int slot) {
        Tile tile = tiles[slot];
        if (tile == null) {
            return;
        }
        tiles[slot] = null;
        String pkg = tile.pkg;
        worker.post(() -> {
            if (tile.recording) {
                Recorder.stop(slot);
            }
            tile.release();
            stopShell(pkg);
        });
    }

    public boolean restart(int slot) {
        final Tile tile = tile(slot);
        if (tile == null) {
            return false;
        }
        worker.post(() -> {
            closeLocked(slot);
            launch(tile.pkg, slot, true);
        });
        return true;
    }

    public void relaunchAll() {
        List<String> packages = remembered();
        if (packages.isEmpty()) {
            return;
        }
        closeAll();
        for (String pkg : packages) {
            launch(pkg);
        }
    }

    // --------------------------------------------------------------------- apps

    public List<AppEntry> apps() {
        List<AppEntry> local = appCache;
        if (local != null && SystemClock.uptimeMillis() - appCacheAt < 5 * 60 * 1000L) {
            return local;
        }
        worker.post(this::loadAppsTask);
        return local == null ? new ArrayList<>() : local;
    }

    public void refreshApps() {
        appCacheAt = 0;
        worker.post(this::loadAppsTask);
    }

    public AppEntry entryFor(String pkg) {
        for (AppEntry entry : apps()) {
            if (entry.pkg.equals(pkg)) {
                return entry;
            }
        }
        return null;
    }

    public String appsJson() {
        JSONArray array = new JSONArray();
        for (AppEntry entry : apps()) {
            try {
                JSONObject object = new JSONObject();
                object.put("label", entry.label);
                object.put("package", entry.pkg);
                object.put("component", entry.component);
                array.put(object);
            } catch (Throwable ignored) {
            }
        }
        return array.toString();
    }

    public void rememberPackages() {
        StringBuilder out = new StringBuilder(128);
        for (Tile tile : tiles()) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(tile.pkg);
        }
        Prefs.lastPackages(context, out.toString());
    }

    public List<String> remembered() {
        String raw = Prefs.lastPackages(context);
        List<String> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String pkg = part.trim();
            if (!pkg.isEmpty() && !out.contains(pkg)) {
                out.add(pkg);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------- input

    public boolean tap(int slot, float nx, float ny) {
        Tile tile = tile(slot);
        if (tile == null || tile.displayId < 0) {
            return false;
        }
        return input(slot).tap(tile.displayId, nx * tile.width, ny * tile.height);
    }

    public boolean longPress(int slot, float nx, float ny, int durationMs) {
        Tile tile = tile(slot);
        if (tile == null || tile.displayId < 0) {
            return false;
        }
        return input(slot).longPress(tile.displayId, nx * tile.width, ny * tile.height, durationMs);
    }

    public boolean swipe(int slot, float nx1, float ny1, float nx2, float ny2, int durationMs) {
        Tile tile = tile(slot);
        if (tile == null || tile.displayId < 0) {
            return false;
        }
        return input(slot).swipe(tile.displayId,
                nx1 * tile.width, ny1 * tile.height,
                nx2 * tile.width, ny2 * tile.height, durationMs);
    }

    public boolean key(int slot, int keyCode) {
        Tile tile = tile(slot);
        if (tile == null || tile.displayId < 0) {
            return false;
        }
        return input(slot).key(tile.displayId, keyCode);
    }

    public boolean text(int slot, String value) {
        Tile tile = tile(slot);
        if (tile == null || tile.displayId < 0) {
            return false;
        }
        return input(slot).text(tile.displayId, value);
    }

    private CommandInput input(int slot) {
        return new CommandInput(slot);
    }

    /** Small adapter so callers get a boolean instead of JSON. */
    final class CommandInput {
        final int slot;

        CommandInput(int slot) {
            this.slot = slot;
        }

        boolean tap(int displayId, float x, float y) {
            return send("tap", o -> o.put("x", (int) Math.round(x)).put("y", (int) Math.round(y)));
        }

        boolean longPress(int displayId, float x, float y, int duration) {
            return send("long", o -> o.put("x", (int) Math.round(x))
                    .put("y", (int) Math.round(y)).put("dur", duration));
        }

        boolean swipe(int displayId, float x1, float y1, float x2, float y2, int duration) {
            return send("swipe", o -> o
                    .put("x1", (int) Math.round(x1)).put("y1", (int) Math.round(y1))
                    .put("x2", (int) Math.round(x2)).put("y2", (int) Math.round(y2))
                    .put("dur", duration));
        }

        boolean key(int displayId, int code) {
            return send("key", o -> o.put("code", code));
        }

        boolean text(int displayId, String value) {
            return send("text", o -> o.put("text", value));
        }

        private interface Writer {
            void write(JSONObject object) throws org.json.JSONException;
        }

        private boolean send(String cmd, Writer writer) {
            try {
                JSONObject object = new JSONObject().put("cmd", cmd);
                Tile tile = tile(slot);
                object.put("display", tile == null ? -1 : tile.displayId);
                writer.write(object);
                JSONObject reply = Shell.get(context).exec(object);
                return reply != null && reply.optBoolean("ok");
            } catch (Throwable e) {
                Log.w(TAG, cmd + " failed: " + e);
                return false;
            }
        }
    }

    // ------------------------------------------------------------------- frames

    public Bitmap frame(int slot) {
        DisplayStack stack = stackOf(slot);
        return stack == null ? null : stack.poll(0);
    }

    public Bitmap frameBlocking(int slot, long timeoutMs) {
        DisplayStack stack = stackOf(slot);
        if (stack == null) {
            return null;
        }
        Bitmap frame = stack.poll(timeoutMs);
        return frame;
    }

    public byte[] jpeg(int slot, int quality, long timeoutMs) {
        DisplayStack stack = stackOf(slot);
        if (stack == null) {
            return null;
        }
        Bitmap frame = stack.poll(timeoutMs);
        if (frame == null) {
            return null;
        }
        return encode(frame, Bitmap.CompressFormat.JPEG, quality);
    }

    public static byte[] encode(Bitmap bitmap, Bitmap.CompressFormat format, int quality) {
        if (bitmap == null) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 17);
        try {
            bitmap.compress(format, quality, out);
            byte[] bytes = out.toByteArray();
            return bytes.length == 0 ? null : bytes;
        } catch (Throwable e) {
            return null;
        } finally {
            try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public Bitmap gridComposite(int maxWidth) {
        List<Tile> live = tiles();
        if (live.isEmpty()) {
            return null;
        }
        int columns = Math.min(Droid.MAX_COLUMNS, Math.max(1, columns()));
        int rows = (int) Math.ceil(live.size() / (double) columns);
        int cellW = 0;
        int cellH = 0;
        List<Bitmap> frames = new ArrayList<>();
        for (Tile tile : live) {
            Bitmap frame = frame(tile.slot);
            if (frame == null) {
                frame = frameBlocking(tile.slot, 400);
            }
            if (frame != null) {
                cellW = Math.max(cellW, frame.getWidth());
                cellH = Math.max(cellH, frame.getHeight());
            }
            frames.add(frame);
        }
        if (cellW <= 0 || cellH <= 0) {
            for (Bitmap bitmap : frames) {
                // nothing to draw
            }
            return null;
        }
        int padding = 8;
        int width = columns * cellW + (columns + 1) * padding;
        int height = rows * cellH + (rows + 1) * padding;
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(0xFF121417);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        Rect dst = new Rect();
        int index = 0;
        for (Tile tile : live) {
            Bitmap frame = frames.get(index++);
            int column = (index - 1) % columns;
            int row = (index - 1) / columns;
            if (frame == null) {
                continue;
            }
            dst.set(padding + column * (cellW + padding),
                    padding + row * (cellH + padding),
                    padding + column * (cellW + padding) + cellW,
                    padding + row * (cellH + padding) + cellH);
            canvas.drawBitmap(frame, null, dst, paint);
        }
        if (maxWidth > 0 && width > maxWidth) {
            int scaledWidth = Math.max(1, (int) Math.round(maxWidth * height / (double) width));
            Bitmap scaled = Bitmap.createScaledBitmap(out, maxWidth, scaledWidth, true);
            out.recycle();
            return scaled;
        }
        return out;
    }

    private DisplayStack stackOf(int slot) {
        Tile tile = tile(slot);
        return tile == null ? null : tile.stack;
    }

    // ------------------------------------------------------------------- status

    public String lastError() {
        return lastError;
    }

    public boolean shellReady() {
        return Shell.get(context).ready();
    }

    public String shellState() {
        return Shell.get(context).state();
    }

    public String statusJson() {
        JSONObject out = new JSONObject();
        try {
            JSONArray array = new JSONArray();
            for (Tile tile : tiles()) {
                JSONObject object = new JSONObject();
                object.put("slot", tile.slot);
                object.put("package", tile.pkg);
                object.put("label", tile.label);
                object.put("state", tile.state);
                object.put("display_id", tile.displayId);
                object.put("width", tile.width);
                object.put("height", tile.height);
                object.put("frames", tile.frames);
                object.put("recording", tile.recording);
                array.put(object);
            }
            out.put("slots", array);
            out.put("used", count());
            out.put("max", Droid.MAX_SLOTS);
            out.put("columns", columns());
            out.put("shell", Shell.get(context).state());
            out.put("uptime_ms", SystemClock.uptimeMillis() - startedAt);
            out.put("last_error", lastError);
        } catch (Throwable ignored) {
        }
        return out.toString();
    }

    private static String message(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}