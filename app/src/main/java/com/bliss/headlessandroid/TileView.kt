package com.bliss.headlessandroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.min

/**
 * One display: the app's own screen inside a labelled frame.
 *
 * <p>The frame is quiet graphite; the only colored mark is the signal dot.
 * The display id is the signature — it proves this is a real virtual display.
 */
class TileView(context: Context) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG)
    private val meta = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val dst = Rect()

    private val radius = Ui.dp(context, 14f).toFloat()
    private val padding = Ui.dp(context, 12f).toFloat()
    private val dotRadius = Ui.dp(context, 4f).toFloat()
    private val stripHeight = Ui.dp(context, 48f).toFloat()
    private val density = resources.displayMetrics.density

    private var tile: Tile? = null
    private var selected: Boolean = false
    private var drawnFrames: Long = -1L
    private var drawnState: String = ""
    private var drawnSelected: Boolean = false

    init {
        fill.style = Paint.Style.FILL
        fill.color = Ui.color(context, R.color.surface)
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = density
        stroke.color = Ui.color(context, R.color.hairline)
        frame.isFilterBitmap = true
        frame.isAntiAlias = true

        label.textSize = 15f * resources.displayMetrics.scaledDensity
        label.color = Ui.color(context, R.color.text_primary)
        label.isAntiAlias = true

        meta.textSize = 12f * resources.displayMetrics.scaledDensity
        meta.color = Ui.color(context, R.color.text_muted)
        meta.typeface = Typeface.MONOSPACE
        meta.isAntiAlias = true

        isClickable = true
        isFocusable = true
    }

    fun bind(tile: Tile?, selected: Boolean) {
        this.tile = tile
        this.selected = selected
        sync()
    }

    /** Invalidates only when the tile actually changed. */
    fun sync() {
        val tile = tile ?: return
        val frames = tile.frames
        val state = tile.state.toString()
        if (frames == drawnFrames && state == drawnState && selected == drawnSelected) {
            return
        }
        drawnFrames = frames
        drawnState = state
        drawnSelected = selected
        invalidate()
    }

    fun tile(): Tile? = tile

    fun slot(): Int = tile?.slot ?: -1

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        box.set(0f, 0f, width, height)
        fill.color = Ui.color(context, if (selected) R.color.surface_2 else R.color.surface)
        canvas.drawRoundRect(box, radius, radius, fill)
        stroke.strokeWidth = if (selected) 2f * density else density
        stroke.color = Ui.color(context, if (selected) R.color.accent else R.color.hairline)
        canvas.drawRoundRect(box, radius, radius, stroke)

        val frameTop = padding + 16f * density
        val frameBottom = height - stripHeight
        val bitmap: Bitmap? = tile?.latest()
        if (bitmap != null && !bitmap.isRecycled) {
            val cellWidth = width - padding * 2f
            val cellHeight = frameBottom - frameTop
            val drawScale = min(cellWidth / bitmap.width, cellHeight / bitmap.height)
            val drawWidth = bitmap.width * drawScale
            val drawHeight = bitmap.height * drawScale
            val left = (width - drawWidth) / 2f
            val top = frameTop + (cellHeight - drawHeight) / 2f
            dst.set(Math.round(left), Math.round(top),
                    Math.round(left + drawWidth), Math.round(top + drawHeight))
            canvas.drawBitmap(bitmap, null, dst, frame)
        } else {
            frame.color = Ui.color(context, R.color.bg)
            box.set(padding, frameTop, width - padding, frameBottom)
            canvas.drawRoundRect(box, 8f * density, 8f * density, frame)
        }

        // Header: signal dot, display id, frame count.
        fill.color = tile?.statusColor() ?: Ui.color(context, R.color.idle)
        canvas.drawCircle(padding + dotRadius, padding + dotRadius, dotRadius, fill)
        val id = tile?.displayId?.let { if (it >= 0) "D $it" else "D --" } ?: "D --"
        canvas.drawText(id, padding + dotRadius * 2f + 8f * density,
                padding + dotRadius + meta.textSize * 0.35f, meta)
        val frames = tile?.frames ?: 0
        if (frames > 0) {
            val framesText = frames.toString()
            canvas.drawText(framesText, width - padding - meta.measureText(framesText),
                    padding + dotRadius + meta.textSize * 0.35f, meta)
        }

        // Footer: app name and state on two lines, both left aligned.
        tile?.let {
            val title = if (it.label.isBlank()) it.pkg else it.label.trim()
            canvas.drawText(ellipsize(title, width - padding * 2f, label),
                    padding, height - stripHeight + 22f * density, label)
            canvas.drawText(ellipsize(stateText(it), width - padding * 2f, meta),
                    padding, height - 10f * density, meta)
        }
    }

    private fun stateText(tile: Tile): String {
        if (Tile.ERROR == tile.state) {
            return if (tile.error.isBlank()) "error" else ellipsize(tile.error, width * 0.8f, meta)
        }
        val idle = android.os.SystemClock.uptimeMillis() - tile.lastFrameAt
        if (tile.frames > 0 && idle < 3000) {
            return "live · " + tile.frames + " frames"
        }
        return tile.stateLabel()
    }

    private fun ellipsize(value: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(value) <= maxWidth) return value
        var end = value.length
        while (end > 1 && paint.measureText(value, 0, end) > maxWidth) {
            end--
        }
        return value.substring(0, end - 1) + "…"
    }
}
