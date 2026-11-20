package com.bliss.headlessandroid

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Settings, in one column of panels.
 *
 * <p>Everything here is a decision the user makes once: where the AI connects,
 * how the grid is laid out, and how much screen the phone keeps holding for it.
 */
class SettingsActivity : Activity() {

    private lateinit var engine: Engine
    private lateinit var port: EditText
    private lateinit var endpoint: TextView
    private lateinit var token: TextView
    private lateinit var version: TextView
    private lateinit var columns: LinearLayout
    private lateinit var resolutions: LinearLayout
    private lateinit var wake: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings)
        engine = Engine.get() ?: Engine.start(this)
        port = findViewById(R.id.mcp_port)
        endpoint = findViewById(R.id.mcp_endpoint)
        token = findViewById(R.id.mcp_token)
        version = findViewById(R.id.version)
        columns = findViewById(R.id.columns)
        resolutions = findViewById(R.id.resolutions)
        wake = findViewById(R.id.wake_switch)

        var endpointText = engine.mcpEndpoint()
        if (endpointText.isNullOrEmpty()) {
            endpointText = "http://" + Network.host(this) + ":" + Prefs.port(this)
        }
        endpoint.text = endpointText + "/mcp"
        token.text = Prefs.token(this)
        version.text = "1.0.0  ·  " + android.os.Build.MODEL +
                "  ·  Android " + android.os.Build.VERSION.RELEASE

        port.setText(Prefs.port(this).toString())
        port.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
        })
        findViewById<View>(R.id.mcp_port_save).setOnClickListener { applyPort() }
        findViewById<View>(R.id.mcp_regen).setOnClickListener {
            Prefs.newToken(this)
            token.text = Prefs.token(this)
            engine.restartMcp()
            toast("New token generated")
        }

        wake.isChecked = Prefs.keepAwake(this)
        wake.setOnCheckedChangeListener { _, checked ->
            Prefs.keepAwake(this, checked)
            engine.refreshWake()
        }

        findViewById<View>(R.id.relaunch).setOnClickListener {
            engine.relaunchAll()
            finish()
        }
        findViewById<View>(R.id.close_all).setOnClickListener {
            engine.closeAll()
            finish()
        }
        findViewById<View>(R.id.open_diagnostics).setOnClickListener {
            startActivity(Intent(this, BootActivity::class.java))
        }

        select(columns, engine.columns())
        select(resolutions, Prefs.screen(this))
    }

    private fun applyPort() {
        val raw = port.text?.toString()?.trim() ?: ""
        val value = try {
            raw.toInt()
        } catch (e: NumberFormatException) {
            Droid.DEFAULT_PORT
        }
        Prefs.port(this, value)
        engine.restartMcp()
        val bound = McpServer.port()
        endpoint.text = "http://" + Network.host(this) + ":" +
                (if (bound > 0) bound else Prefs.port(this)) + "/mcp"
        port.setText(Prefs.port(this).toString())
        toast("MCP restarted on " + Prefs.port(this))
    }

    /** Marks the chosen chip with the accent hairline; clears the rest. */
    private fun select(group: LinearLayout, value: Int) {
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            val chosen = child.tag?.toString()?.toIntOrNull() == value
            child.setBackgroundResource(
                    if (chosen) R.drawable.bg_tile_focus else R.drawable.bg_button)
            child.setOnClickListener { choose(group, child) }
        }
    }

    private fun choose(group: LinearLayout, child: View) {
        val value = child.tag?.toString()?.toIntOrNull() ?: return
        if (group === columns) {
            engine.setColumns(value)
        } else {
            Prefs.screen(this, value)
            val screen = Droid.SCREENS[value]
            toast("New apps use " + screen[0] + "x" + screen[1])
        }
        select(group, value)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}