package com.bliss.headlessandroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * The empty state's product visual: a small stack of private display frames.
 *
 * <p>Not decoration — it is the thing the app makes. Three hairline frames, one
 * filled amber signal on the front one, drawn in the same palette as the tiles.
 */
class EmptyStateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = Ui.color(context, R.color.hairline_strong)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Ui.color(context, R.color.surface_2)
    }
    private val lamp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Ui.color(context, R.color.accent)
    }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val inset = 20f * density
        val step = 12f * density
        val frameW = w - inset * 2 - step * 2
        val frameH = h - inset * 2 - step
        for (index in 2 downTo 0) {
            val left = inset + step * index
            val top = inset + step * (2 - index)
            box.set(left, top, left + frameW, top + frameH)
            canvas.drawRoundRect(box, 12f * density, 12f * density, fill)
            canvas.drawRoundRect(box, 12f * density, 12f * density, stroke)
        }
        val radius = 3f * density
        canvas.drawCircle(inset + step * 0 + 14f * density,
                inset + step * 2 + 14f * density, radius, lamp)
    }
}
