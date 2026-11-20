package com.bliss.headlessandroid

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small view helpers so every control uses the same type, color and rhythm.
 *
 * <p>Color comes from one neutral ramp plus one accent (see
 * [R.color]); nothing here is allowed to invent its own.
 */
internal object Ui {

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun color(context: Context, res: Int): Int = context.resources.getColor(res, null)

    fun size(context: Context, dp: Int): LinearLayout.LayoutParams {
        val edge = dp(context, dp.toFloat())
        return LinearLayout.LayoutParams(edge, edge)
    }

    fun textPaint(context: Context, spSize: Float, colorRes: Int, mono: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = spSize * context.resources.displayMetrics.scaledDensity
            color = color(context, colorRes)
            typeface = if (mono) Typeface.MONOSPACE else Typeface.DEFAULT
        }

    /** Icons take the color of the text role they sit next to. */
    fun tint(view: View, colorRes: Int) {
        (view as? ImageView)?.setColorFilter(color(view.context, colorRes))
    }

    fun text(view: View, res: Int) {
        (view as? TextView)?.setText(res)
    }

    /**
     * The status dot: amber while a slot renders, dim when it does not,
     * red on error. This is the product's one signature detail.
     */
    fun dot(view: View, colorRes: Int) {
        val image = view as? ImageView ?: return
        val drawable = when (colorRes) {
            R.color.live, R.color.accent -> R.drawable.dot_live
            R.color.danger -> R.drawable.dot_danger
            R.color.stopped -> R.drawable.dot_stopped
            else -> R.drawable.dot_idle
        }
        image.setImageResource(drawable)
    }
}