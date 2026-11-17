package com.bliss.headlessdroid

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * The grid screen.
 *
 * <p>Chrome is deliberately plain — an app bar, a status strip, the tiles, and an
 * action bar that only appears for the selected slot — because the product is
 * what the tiles show: real apps, running portrait, on virtual displays.
 */
class MainActivity : Activity(), GridView.Listener {

    private lateinit var engine: Engine
    private lateinit var grid: GridView
    private lateinit var empty: View
    private lateinit var actionsScroll: View
    private lateinit var actions: LinearLayout
    private lateinit var subtitle: TextView
    private lateinit var textShell: TextView
    private lateinit var textMcp: TextView
    private lateinit var dotShell: ImageView
    private lateinit var dotMcp: ImageView

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            syncTiles()
            ticker.postDelayed(this, TICK_MS)
        }
    }

    private val engineChanged = Runnable { refreshAll() }
    private var selectedSlot: Int = NO_SLOT
    private var gridSignature: String = ""
    private var gridColumns: Int = -1
    private var gridShowAdd: Boolean = false
    private var actionBarSlot: Int = NO_SLOT
    private var actionBarSignature: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        engine = Engine.get() ?: Engine.start(this)
        engine.addListener(engineChanged)

        subtitle = findViewById(R.id.subtitle)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        actionsScroll = findViewById(R.id.actions_scroll)
        actions = findViewById(R.id.actions)
        textShell = findViewById(R.id.text_shell)
        textMcp = findViewById(R.id.text_mcp)
        dotShell = findViewById(R.id.dot_shell)
        dotMcp = findViewById(R.id.dot_mcp)

        grid.listener = this
        findViewById<View>(R.id.btn_apps).setOnClickListener { openApps() }
        findViewById<View>(R.id.btn_settings).setOnClickListener { openSettings() }
        findViewById<View>(R.id.btn_columns).setOnClickListener { cycleColumns() }
        findViewById<View>(R.id.empty_add).setOnClickListener { openApps() }
        findViewById<View>(R.id.status_shell).setOnClickListener { grantShell() }
        findViewById<View>(R.id.status_mcp).setOnClickListener { showMcp() }

        rebuildGrid()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (!engine.shellReady()) {
            Shell.get(this).refresh()
        }
        engine.refreshApps()
        refreshStatus()
        syncTiles()
        ticker.postDelayed(tick, TICK_MS)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.removeListener(engineChanged)
    }

    // ------------------------------------------------------------------ engine

    private fun refreshAll() {
        refreshStatus()
        syncTiles()
        checkGrid()
    }

    /**
     * Rebuilds the grid only when the set of running apps, the column count or
     * the selection changes. Frame updates arrive many times a second, and
     * rebuilding nine live tiles that often would spend the whole frame budget
     * inflating views instead of drawing them.
     */
    private fun checkGrid() {
        val used = engine.count()
        empty.visibility = if (used == 0) View.VISIBLE else View.GONE
        grid.visibility = if (used == 0) View.GONE else View.VISIBLE

        val signature = StringBuilder(48)
        for (tile in engine.tiles()) {
            signature.append(tile.slot).append(':').append(tile.pkg).append('|')
        }
        val columns = engine.columns()
        val showAdd = used < Droid.MAX_SLOTS
        if (signature.toString() != gridSignature
                || columns != gridColumns
                || showAdd != gridShowAdd) {
            grid.setColumns(columns)
            grid.setShowAdd(showAdd)
            grid.rebuild(engine.tiles(), selectedSlot)
            gridSignature = signature.toString()
            gridColumns = columns
            gridShowAdd = showAdd
        }
        refreshActions()
    }

    private fun syncTiles() {
        val used = engine.count()
        for (view in grid.tileViews()) {
            view.sync()
        }
        subtitle.text = used.toString() + " of " + Droid.MAX_SLOTS + " slots"
    }

    private fun rebuildGrid() {
        val used = engine.count()
        empty.visibility = if (used == 0) View.VISIBLE else View.GONE
        grid.visibility = if (used == 0) View.GONE else View.VISIBLE
        grid.setColumns(engine.columns())
        grid.setShowAdd(used < Droid.MAX_SLOTS)
        grid.rebuild(engine.tiles(), selectedSlot)
        gridSignature = ""
        refreshActions()
    }

    private fun refreshStatus() {
        val shell = engine.shellReady()
        textShell.setText(if (shell) R.string.shell_ready else R.string.shell_missing)
        textShell.setTextColor(Ui.color(this,
                if (shell) R.color.text_secondary else R.color.danger))
        Ui.dot(dotShell, if (shell) R.color.good else R.color.danger)

        val mcp = McpServer.running()
        val bound = McpServer.port()
        // The server may have fallen back to the next free port, so the port
        // shown is the one it actually bound, not the one that was configured.
        textMcp.text = if (mcp)
            "MCP :" + (if (bound > 0) bound else Prefs.port(this))
        else getString(R.string.mcp_offline)
        textMcp.setTextColor(Ui.color(this,
                if (mcp) R.color.text_secondary else R.color.text_muted))
        Ui.dot(dotMcp, if (mcp) R.color.accent else R.color.idle)
    }

    private fun refreshActions() {
        val tile = if (selectedSlot > 0) engine.tile(selectedSlot) else null
        if (tile == null) {
            actionBarSlot = NO_SLOT
            actionBarSignature = ""
            actionsScroll.visibility = View.GONE
            return
        }
        // Only rebuild the chips when the slot or its recording state changed.
        val signature = tile.slot.toString() + ":" + tile.recording + ":" + tile.state
        if (tile.slot == actionBarSlot && signature == actionBarSignature) {
            return
        }
        actionBarSlot = tile.slot
        actionBarSignature = signature

        actions.removeAllViews()
        actionsScroll.visibility = View.VISIBLE
        addAction(R.drawable.ic_back, R.string.action_back,
                { key(android.view.KeyEvent.KEYCODE_BACK) })
        addAction(R.drawable.ic_home, R.string.action_home,
                { key(android.view.KeyEvent.KEYCODE_HOME) })
        addAction(R.drawable.ic_recents, R.string.action_recents,
                { key(android.view.KeyEvent.KEYCODE_APP_SWITCH) })
        addAction(R.drawable.ic_text, R.string.action_type, { typeText() })
        addAction(R.drawable.ic_camera, R.string.action_shot, { screenshot() })
        addAction(R.drawable.ic_record,
                if (tile.recording) R.string.action_stop else R.string.action_record,
                { record() })
        addAction(R.drawable.ic_restart, R.string.action_restart, {
            engine.restart(tile.slot)
            toast("Relaunching " + tile.label)
        })
        addAction(R.drawable.ic_close, R.string.action_close, {
            engine.close(tile.slot)
            selectedSlot = NO_SLOT
            rebuildGrid()
        })
    }

    private fun addAction(icon: Int, label: Int, listener: () -> Unit) {
        val chip = LinearLayout(this)
        chip.orientation = LinearLayout.HORIZONTAL
        chip.gravity = Gravity.CENTER_VERTICAL
        chip.setBackgroundResource(R.drawable.bg_button)
        chip.setPadding(Ui.dp(this, 12f), Ui.dp(this, 10f), Ui.dp(this, 14f), Ui.dp(this, 10f))
        val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.marginEnd = Ui.dp(this, 8f)
        chip.layoutParams = params

        val image = ImageView(this)
        image.setImageResource(icon)
        image.setColorFilter(Ui.color(this, R.color.text_secondary))
        chip.addView(image, Ui.size(this, 18))

        val text = TextView(this)
        text.setText(label)
        text.textSize = 13f
        text.setTextColor(Ui.color(this, R.color.text_secondary))
        val textParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        textParams.marginStart = Ui.dp(this, 8f)
        text.layoutParams = textParams
        chip.addView(text)

        chip.setOnClickListener { listener() }
        actions.addView(chip)
    }

    // ---------------------------------------------------------------- commands

    private fun key(code: Int) {
        val tile = engine.tile(selectedSlot) ?: return
        if (!engine.key(tile.slot, code)) {
            toast("Input needs the shell service")
        }
    }

    private fun typeText() {
        val tile = engine.tile(selectedSlot) ?: return
        val field = EditText(this)
        field.setHint(R.string.action_type)
        field.setTextColor(Ui.color(this, R.color.text_primary))
        field.setHintTextColor(Ui.color(this, R.color.text_muted))
        field.setSingleLine()
        val pad = Ui.dp(this, 16f)
        field.setPadding(pad, pad, pad, pad)
        AlertDialog.Builder(this)
                .setTitle(R.string.action_type)
                .setView(field)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val value = field.text.toString()
                    if (!TextUtils.isEmpty(value) && !engine.text(tile.slot, value)) {
                        toast("Input needs the shell service")
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun screenshot() {
        val tile = engine.tile(selectedSlot) ?: return
        val frame = engine.frameBlocking(tile.slot, 2500)
        val jpeg = Engine.encode(frame, Bitmap.CompressFormat.JPEG, 92)
        val name = Captures.publish(this, Droid.DIR_SHOTS,
                Captures.fileName("slot" + tile.slot + "-" + safeName(tile.label), ".jpg"), jpeg)
        toast(if (name == null) "Screenshot failed" else "Saved " + name)
    }

    private fun record() {
        val tile = engine.tile(selectedSlot) ?: return
        if (tile.recording) {
            // Stopping drains the encoder, which can take seconds, so it never
            // runs on the UI thread.
            val slot = tile.slot
            tile.recording = false
            Thread({
                val path = Recorder.stop(slot)
                runOnUiThread {
                    toast(if (path == null) "Recording failed" else "Saved $path")
                    refreshAll()
                }
            }, "droid-stop-rec-$slot").start()
        } else {
            tile.recording = Recorder.start(tile.slot, tile.width, tile.height, 12)
            toast(if (tile.recording) "Recording " + tile.label else "Recording failed to start")
        }
        refreshActions()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------- shells

    private fun grantShell() {
        if (engine.shellReady()) {
            Shell.get(this).refresh()
            return
        }
        Shell.get(this).requestPermission()
        toast("Grant Headless Droid in Shizuku")
    }

    private fun cycleColumns() {
        val next = if (engine.columns() >= Droid.MAX_COLUMNS) 1 else engine.columns() + 1
        engine.setColumns(next)
        rebuildGrid()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openApps() {
        AppsSheet.open(this, engine)
    }

    // ---------------------------------------------------------------------- mcp

    private fun showMcp() {
        var endpoint = engine.mcpEndpoint()
        if (endpoint.isNullOrEmpty()) {
            endpoint = "http://" + Network.host(this) + ":" + Prefs.port(this)
        }
        val endpointUrl = endpoint + "/mcp"
        val token = Prefs.token(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(this, 16f)
        root.setPadding(pad, pad, pad, pad)

        val endpointView = TextView(this)
        endpointView.text = endpointUrl
        endpointView.typeface = android.graphics.Typeface.MONOSPACE
        endpointView.setTextColor(Ui.color(this, R.color.accent))
        endpointView.textSize = 13f
        root.addView(endpointView)

        val tokenView = TextView(this)
        tokenView.text = token
        tokenView.typeface = android.graphics.Typeface.MONOSPACE
        tokenView.setTextColor(Ui.color(this, R.color.text_secondary))
        tokenView.textSize = 12f
        tokenView.setTextIsSelectable(true)
        val tokenParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tokenParams.topMargin = Ui.dp(this, 12f)
        tokenView.layoutParams = tokenParams
        root.addView(tokenView)

        AlertDialog.Builder(this)
                .setTitle(R.string.mcp_title)
                .setView(root)
                .setPositiveButton("Copy") { _, _ -> copy("$endpointUrl\n$token") }
                .setNeutralButton(R.string.settings_mcp_regen) { _, _ ->
                    Prefs.newToken(this)
                    engine.restartMcp()
                    showMcp()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun copy(value: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText("Headless Droid", value))
        toast("Copied")
    }

    // ------------------------------------------------------------------- events

    override fun onAdd() {
        openApps()
    }

    override fun onSelect(slot: Int) {
        selectedSlot = slot
        refreshActions()
    }

    override fun onLongPress(tile: Tile) {
        selectedSlot = tile.slot
        grid.rebuild(engine.tiles(), selectedSlot)
        refreshActions()
    }

    private fun safeName(value: String?): String {
        if (value == null || value.trim().isEmpty()) return "app"
        return value.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-")
    }

    private companion object {
        const val TICK_MS = 60L
        const val NO_SLOT = -1
    }
}