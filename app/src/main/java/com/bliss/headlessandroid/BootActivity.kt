package com.bliss.headlessandroid

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Diagnostics: a quiet, readable answer to "can this phone run the app".
 *
 * <p>Reached from Settings, not from the launcher. Each check is isolated so a
 * failure is shown as a row instead of a crash dialog.
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

        root.addView(text(R.string.diagnostics_title, 24f, R.color.text_primary, false))
        root.addView(text(R.string.diagnostics_subtitle, 14f, R.color.text_secondary, false))

        summary = text(R.string.diagnostics_summary, 14f, R.color.text_secondary, true)
        root.addView(summary)

        checks = LinearLayout(this)
        checks.orientation = LinearLayout.VERTICAL
        checks.setBackgroundResource(R.drawable.bg_card)
        checks.setPadding(pad, pad, pad, pad)
        val checkParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        checkParams.topMargin = Ui.dp(this, 24f)
        checks.layoutParams = checkParams
        root.addView(checks)

        open = Button(this)
        open.text = getString(R.string.diagnostics_open)
        open.setTextColor(Ui.color(this, R.color.on_accent))
        open.setBackgroundColor(Ui.color(this, R.color.accent))
        val openParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 48f))
        openParams.topMargin = Ui.dp(this, 24f)
        open.layoutParams = openParams
        open.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        root.addView(open)

        val again = Button(this)
        again.text = getString(R.string.diagnostics_again)
        again.setTextColor(Ui.color(this, R.color.text_secondary))
        again.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        again.setOnClickListener { runChecks() }
        root.addView(again)

        setContentView(root)
        runChecks()
    }

    private fun text(res: Int, size: Float, color: Int, mono: Boolean): TextView {
        val view = TextView(this)
        view.setText(res)
        view.textSize = size
        view.setTextColor(Ui.color(this, color))
        if (mono) view.typeface = Typeface.MONOSPACE
        view.setPadding(0, Ui.dp(this, 4f), 0, Ui.dp(this, 4f))
        return view
    }

    private fun runChecks() {
        checks.removeAllViews()
        summary.text = getString(R.string.diagnostics_summary)
        val results = ArrayList<Pair<String, Boolean>>()
        results.add("App packaged and running" to true)
        results.add("Shizuku installed" to shizukuInstalled())
        results.add("Grid engine started" to (Engine.get() != null))
        results.add("Agent endpoint listening" to McpServer.running())
        results.add("Virtual display available" to canCreateDisplay())

        for ((label, ok) in results) {
            val row = TextView(this)
            row.text = (if (ok) "  OK   " else "  NO   ") + label
            row.textSize = 14f
            row.typeface = Typeface.MONOSPACE
            row.setTextColor(Ui.color(this, if (ok) R.color.text_secondary else R.color.danger))
            row.setPadding(0, Ui.dp(this, 10f), 0, Ui.dp(this, 10f))
            checks.addView(row)
        }
        val passed = results.count { it.second }
        summary.text = "$passed of ${results.size} checks passed"
        summary.setTextColor(Ui.color(this,
                if (passed == results.size) R.color.good else R.color.danger))
        open.isEnabled = true
    }

    private fun shizukuInstalled(): Boolean = try {
        packageManager.getPackageInfo(Droid.EXT_LAUNCHER, 0) != null
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Creates and releases a display — no permission needed, proves the platform works. */
    private fun canCreateDisplay(): Boolean = try {
        val stack = DisplayStack.create(this, 360, 480, 160)
        stack.release()
        true
    } catch (e: Throwable) {
        false
    }
}
