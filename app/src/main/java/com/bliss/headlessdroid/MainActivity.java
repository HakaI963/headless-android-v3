package com.bliss.headlessdroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The grid screen.
 *
 * <p>Chrome is deliberately plain — an app bar, a status strip, the tiles, and an
 * action bar that only appears for the selected slot — because the product is
 * what the tiles show: real apps, running portrait, on virtual displays.
 */
public final class MainActivity extends Activity implements GridView.Listener {
    private static final long TICK_MS = 60;

    private Engine engine;
    private GridView grid;
    private View empty;
    private View actionsScroll;
    private LinearLayout actions;
    private TextView subtitle;
    private TextView textShell;
    private TextView textMcp;
    private ImageView dotShell;
    private ImageView dotMcp;
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            syncTiles();
            ticker.postDelayed(this, TICK_MS);
        }
    };

    private final Runnable engineChanged = this::refreshAll;
    private int selectedSlot = -1;
    private String gridSignature = "";
    private int gridColumns = -1;
    private boolean gridShowAdd;
    private int actionBarSlot = Integer.MIN_VALUE;
    private String actionBarSignature = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        engine = Engine.get() == null ? Engine.start(this) : Engine.get();
        engine.addListener(engineChanged);

        subtitle = findViewById(R.id.subtitle);
        grid = findViewById(R.id.grid);
        empty = findViewById(R.id.empty);
        actionsScroll = findViewById(R.id.actions_scroll);
        actions = findViewById(R.id.actions);
        textShell = findViewById(R.id.text_shell);
        textMcp = findViewById(R.id.text_mcp);
        dotShell = findViewById(R.id.dot_shell);
        dotMcp = findViewById(R.id.dot_mcp);

        grid.setListener(this);
        findViewById(R.id.btn_apps).setOnClickListener(v -> openApps());
        findViewById(R.id.btn_settings).setOnClickListener(v -> openSettings());
        findViewById(R.id.btn_columns).setOnClickListener(v -> cycleColumns());
        findViewById(R.id.empty_add).setOnClickListener(v -> openApps());
        findViewById(R.id.status_shell).setOnClickListener(v -> grantShell());
        findViewById(R.id.status_mcp).setOnClickListener(v -> showMcp());

        rebuildGrid();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!engine.shellReady()) {
            Shell.get(this).refresh();
        }
        engine.refreshApps();
        refreshStatus();
        syncTiles();
        ticker.postDelayed(tick, TICK_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        engine.removeListener(engineChanged);
    }

    // ------------------------------------------------------------------ engine

    private void refreshAll() {
        refreshStatus();
        syncTiles();
        checkGrid();
    }

    /**
     * Rebuilds the grid only when the set of running apps, the column count or
     * the selection changes. Frame updates arrive many times a second, and
     * rebuilding nine live tiles that often would spend the whole frame budget
     * inflating views instead of drawing them.
     */
    private void checkGrid() {
        int used = engine.count();
        empty.setVisibility(used == 0 ? View.VISIBLE : View.GONE);
        grid.setVisibility(used == 0 ? View.GONE : View.VISIBLE);

        StringBuilder signature = new StringBuilder(48);
        for (Tile tile : engine.tiles()) {
            signature.append(tile.slot).append(':').append(tile.pkg).append('|');
        }
        int columns = engine.columns();
        boolean showAdd = used < Droid.MAX_SLOTS;
        if (!signature.toString().equals(gridSignature)
                || columns != gridColumns
                || showAdd != gridShowAdd) {
            grid.setColumns(columns);
            grid.setShowAdd(showAdd);
            grid.rebuild(engine.tiles(), selectedSlot);
            gridSignature = signature.toString();
            gridColumns = columns;
            gridShowAdd = showAdd;
        }
        refreshActions();
    }

    private void syncTiles() {
        int used = engine.count();
        for (TileView view : grid.tileViews()) {
            view.sync();
        }
        subtitle.setText(used + " of " + Droid.MAX_SLOTS + " slots");
    }

    private void rebuildGrid() {
        int used = engine.count();
        empty.setVisibility(used == 0 ? View.VISIBLE : View.GONE);
        grid.setVisibility(used == 0 ? View.GONE : View.VISIBLE);
        grid.setColumns(engine.columns());
        grid.setShowAdd(used < Droid.MAX_SLOTS);
        grid.rebuild(engine.tiles(), selectedSlot);
        refreshActions();
    }

    private void refreshStatus() {
        boolean shell = engine.shellReady();
        textShell.setText(shell ? R.string.shell_ready : R.string.shell_missing);
        textShell.setTextColor(Ui.color(this, shell ? R.color.text_secondary : R.color.danger));
        Ui.dot(dotShell, shell ? R.color.good : R.color.danger);

        boolean mcp = McpServer.running();
        int bound = McpServer.port();
        // The server may have fallen back to the next free port, so the port
        // shown is the one it actually bound, not the one that was configured.
        textMcp.setText(mcp
                ? "MCP :" + (bound > 0 ? bound : Prefs.port(this))
                : getString(R.string.mcp_offline));
        textMcp.setTextColor(Ui.color(this, mcp ? R.color.text_secondary : R.color.text_muted));
        Ui.dot(dotMcp, mcp ? R.color.accent : R.color.idle);
    }

    private void refreshActions() {
        Tile tile = selectedSlot > 0 ? engine.tile(selectedSlot) : null;
        if (tile == null) {
            actionBarSlot = Integer.MIN_VALUE;
            actionBarSignature = "";
            actionsScroll.setVisibility(View.GONE);
            return;
        }
        // Only rebuild the chips when the slot or its recording state changed.
        String signature = tile.slot + ":" + tile.recording + ":" + tile.state;
        if (tile.slot == actionBarSlot && signature.equals(actionBarSignature)) {
            return;
        }
        actionBarSlot = tile.slot;
        actionBarSignature = signature;

        actions.removeAllViews();
        actionsScroll.setVisibility(View.VISIBLE);
        addAction(R.drawable.ic_back, R.string.action_back,
                v -> key(android.view.KeyEvent.KEYCODE_BACK));
        addAction(R.drawable.ic_home, R.string.action_home,
                v -> key(android.view.KeyEvent.KEYCODE_HOME));
        addAction(R.drawable.ic_recents, R.string.action_recents,
                v -> key(android.view.KeyEvent.KEYCODE_APP_SWITCH));
        addAction(R.drawable.ic_text, R.string.action_type, v -> typeText());
        addAction(R.drawable.ic_camera, R.string.action_shot, v -> screenshot());
        addAction(R.drawable.ic_record, tile.recording ? R.string.action_stop : R.string.action_record,
                v -> record());
        addAction(R.drawable.ic_restart, R.string.action_restart, v -> {
            engine.restart(tile.slot);
            toast("Relaunching " + tile.label);
        });
        addAction(R.drawable.ic_close, R.string.action_close, v -> {
            engine.close(tile.slot);
            selectedSlot = -1;
            rebuildGrid();
        });
    }

    private void addAction(int icon, int label, View.OnClickListener listener) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackgroundResource(R.drawable.bg_button);
        chip.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(Ui.dp(this, 8));
        chip.setLayoutParams(params);

        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        image.setColorFilter(Ui.color(this, R.color.text_secondary));
        chip.addView(image, Ui.size(this, 18));

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(13f);
        text.setTextColor(Ui.color(this, R.color.text_secondary));
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.setMarginStart(Ui.dp(this, 8));
        text.setLayoutParams(textParams);
        chip.addView(text);

        chip.setOnClickListener(listener);
        actions.addView(chip);
    }

    // ---------------------------------------------------------------- commands

    private void key(int code) {
        Tile tile = engine.tile(selectedSlot);
        if (tile == null) {
            return;
        }
        if (!engine.key(tile.slot, code)) {
            toast("Input needs the shell service");
        }
    }

    private void typeText() {
        final Tile tile = engine.tile(selectedSlot);
        if (tile == null) {
            return;
        }
        EditText field = new EditText(this);
        field.setHint(R.string.action_type);
        field.setTextColor(Ui.color(this, R.color.text_primary));
        field.setHintTextColor(Ui.color(this, R.color.text_muted));
        field.setSingleLine();
        int pad = Ui.dp(this, 16);
        field.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_type)
                .setView(field)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String value = String.valueOf(field.getText());
                    if (!TextUtils.isEmpty(value) && !engine.text(tile.slot, value)) {
                        toast("Input needs the shell service");
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void screenshot() {
        Tile tile = engine.tile(selectedSlot);
        if (tile == null) {
            return;
        }
        Bitmap frame = engine.frameBlocking(tile.slot, 2500);
        byte[] jpeg = Engine.encode(frame, Bitmap.CompressFormat.JPEG, 92);
        String name = Captures.publish(this, Droid.DIR_SHOTS,
                Captures.fileName("slot" + tile.slot + "-" + safeName(tile.label), ".jpg"), jpeg);
        toast(name == null ? "Screenshot failed" : "Saved " + name);
    }

    private void record() {
        final Tile tile = engine.tile(selectedSlot);
        if (tile == null) {
            return;
        }
        if (tile.recording) {
            // Stopping drains the encoder, which can take seconds, so it never
            // runs on the UI thread.
            final int slot = tile.slot;
            final String label = tile.label;
            tile.recording = false;
            new Thread(() -> {
                final String path = Recorder.stop(slot);
                runOnUiThread(() -> {
                    toast(path == null ? "Recording failed" : "Saved " + path);
                    refreshAll();
                });
            }, "droid-stop-rec-" + slot).start();
        } else {
            tile.recording = Recorder.start(tile.slot, tile.width, tile.height, 12);
            toast(tile.recording ? "Recording " + tile.label : "Recording failed to start");
        }
        refreshActions();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------- shells

    private void grantShell() {
        if (engine.shellReady()) {
            Shell.get(this).refresh();
            return;
        }
        Shell.get(this).requestPermission();
        toast("Grant Headless Droid in Shizuku");
    }

    private void cycleColumns() {
        int next = engine.columns() >= Droid.MAX_COLUMNS ? 1 : engine.columns() + 1;
        engine.setColumns(next);
        rebuildGrid();
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void openApps() {
        AppsSheet.open(this, engine);
    }

    // ---------------------------------------------------------------------- mcp

    private void showMcp() {
        String endpoint = engine.mcpEndpoint();
        if (endpoint == null || endpoint.isEmpty()) {
            endpoint = "http://" + Network.host(this) + ":" + Prefs.port(this);
        }
        final String token = Prefs.token(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);

        TextView endpointView = new TextView(this);
        final String endpointUrl = endpoint + "/mcp";
        endpointView.setText(endpointUrl);
        endpointView.setTypeface(android.graphics.Typeface.MONOSPACE);
        endpointView.setTextColor(Ui.color(this, R.color.accent));
        endpointView.setTextSize(13f);
        root.addView(endpointView);

        TextView tokenView = new TextView(this);
        tokenView.setText(token);
        tokenView.setTypeface(android.graphics.Typeface.MONOSPACE);
        tokenView.setTextColor(Ui.color(this, R.color.text_secondary));
        tokenView.setTextSize(12f);
        tokenView.setTextIsSelectable(true);
        LinearLayout.LayoutParams tokenParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tokenParams.topMargin = Ui.dp(this, 12);
        tokenView.setLayoutParams(tokenParams);
        root.addView(tokenView);

        new AlertDialog.Builder(this)
                .setTitle(R.string.mcp_title)
                .setView(root)
                .setPositiveButton("Copy", (d, w) -> copy(endpointUrl + "\n" + token))
                .setNeutralButton(R.string.settings_mcp_regen,
                        (d, w) -> {
                            Prefs.newToken(this);
                            engine.restartMcp();
                            showMcp();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void copy(String value) {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText("Headless Droid", value));
        }
        toast("Copied");
    }

    // ------------------------------------------------------------------- events

    @Override
    public void onAdd() {
        openApps();
    }

    @Override
    public void onSelect(int slot) {
        selectedSlot = slot;
        refreshActions();
    }

    @Override
    public void onLongPress(Tile tile) {
        selectedSlot = tile.slot;
        grid.rebuild(engine.tiles(), selectedSlot);
        refreshActions();
    }

    private static String safeName(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "app";
        }
        return value.trim().toLowerCase(java.util.Locale.US).replaceAll("[^a-z0-9]+", "-");
    }
}