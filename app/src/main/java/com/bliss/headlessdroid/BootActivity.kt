package com.bliss.headlessdroid

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Boot screen, and the app's own diagnostic.
 *
 * <p>It opens before anything else is initialised, so it is the answer to "does
 * this app even run on this phone" — and it answers it with specific checks
 * instead of a crash dialog. Each check runs on its own and can never take the
 * activity down, because a blank or silent failure gives nobody anything to
 * work with.
 *
 * <p>Layout is built in code on purpose: there is no XML to inflate, so there is
 * nothing here that a broken resource or a missing constructor could break.
 */
class BootActivity : Activity() {

    private lateinit var checks: LinearLayout
    private lateinit var summary: TextView
    private lateinit var open: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = Ui.dp(this, 20f)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.color(this, R.color.bg))
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "Headless Droid"
        title.textSize = 24f
        title.setTextColor(Ui.color(this, R.color.text_primary))
        root.addView(title)

        val subtitle = TextView(this)
        subtitle.text = "Boot check. The grid is one tap away."
        subtitle.textSize = 14f
        subtitle.setTextColor(Ui.color(this, R.color.text_secondary))
        root.addView(subtitle)

        summary = TextView(this)
        summary.textSize = 14f
        summary.setTextColor(Ui.color(this, R.color.text_secondary))
        root.addView(summary)

        checks = LinearLayout(this)
        checks.orientation = LinearLayout.VERTICAL
        val checksParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        checksParams.topMargin = Ui.dp(this, 16f)
        checks.layoutParams = checksParams
        root.addView(checks)

        open = Button(this)
        open.text = "Open the grid"
        open.setTextColor(Ui.color(this, R.color.on_accent))
        open.setBackgroundColor(Ui.color(this, R.color.accent))
        open.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        root.addView(open)

        val again = Button(this)
        again.text = "Run the checks again"
        again.setTextColor(Ui.color(this, R.color.text_secondary))
        again.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        again.setOnClickListener { runChecks() }
        val againParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        againParams.topMargin = Ui.dp(this, 8f)
        again.layoutParams = againParams
        root.addView(again)

        setContentView(root)
        runChecks()
    }

    private fun runChecks() {
        checks.removeAllViews()
        summary.text = "Checking…"
        val results = ArrayList<Pair<String, Int>>()

        check("App packaged and running", true).let { results.add(it) }
        results.add(check("Shizuku installed", shizukuInstalled()))
        results.add(check("Grid engine started", engineStarted()))
        results.add(check("MCP endpoint listening", mcpListening()))
        results.add(check("Can create a virtual display", canCreateDisplay()))

        results.forEach { addRow(it.first, it.second) }
        val passed = results.count { it.second == GOOD }
        summary.text = "$passed of ${results.size} checks passed"
        summary.setTextColor(Ui.color(this,
                if (passed == results.size) R.color.good else R.color.danger))
        open.isEnabled = true
    }

    private fun addRow(label: String, status: Int) {
        val row = TextView(this)
        row.text = "  " + dot(status) + "  " + label
        row.textSize = 14f
        row.setTextColor(Ui.color(this,
                if (status == GOOD) R.color.text_secondary else if (status == BAD) R.color.danger
                else R.color.text_muted))
        row.typeface = Typeface.MONOSPACE
        row.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 6f))
        checks.addView(row)
    }

    private fun dot(status: Int): String = when (status) {
        GOOD -> "OK"
        BAD -> "NO"
        else -> "--"
    }

    private fun shizukuInstalled(): Boolean = try {
        packageManager.getPackageInfo(Droid.EXT_LAUNCHER, 0) != null
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun engineStarted(): Boolean = Engine.get() != null

    private fun mcpListening(): Boolean = McpServer.running()

    /**
     * The one check that needs no permission and proves the platform can do
     * what this app is for: a virtual display is created and released.
     */
    private fun canCreateDisplay(): Boolean = try {
        val stack = DisplayStack.create(this, 360, 480, 160)
        stack.release()
        true
    } catch (e: Throwable) {
        false
    }

    private fun check(label: String, value: Boolean): Pair<String, Int> =
            Pair(label, if (value) GOOD else BAD)

    private companion object {
        private const val GOOD = 1
        private const val BAD = 2
        private const val UNKNOWN = 3
    }
}