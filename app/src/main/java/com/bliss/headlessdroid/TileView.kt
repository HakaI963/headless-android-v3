package com.bliss.headlessdroid

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
 * One slot in the grid: the real app's own screen, small and quiet.
 *
 * <p>This is the one place in the product that carries color — the app running
 * inside it. The chrome around the frame is graphite and one hairline, so the
 * frame reads instantly. The status dot is the product's signature: amber
 * while the app renders, grey when it does not.
 */
class TileView(context: Context) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG)
    private val meta = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val dst = Rect()

    private val radius: Float = Ui.dp(context, 12f).toFloat()
    private val padding: Float = Ui.dp(context, 8f).toFloat()
    private val dotRadius: Float = Ui.dp(context, 3f).toFloat()
    private val stripHeight: Float = Ui.dp(context, 30f).toFloat()
    private val density: Float = resources.displayMetrics.density

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

        label.textSize = 13f * resources.displayMetrics.scaledDensity
        label.color = Ui.color(context, R.color.text_secondary)
        label.isAntiAlias = true

        meta.textSize = 11f * resources.displayMetrics.scaledDensity
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
        canvas.drawRoundRect(box, radius, radius, fill)
        stroke.strokeWidth = density
        stroke.color = Ui.color(context, if (selected) R.color.accent else R.color.hairline)
        canvas.drawRoundRect(box, radius, radius, stroke)

        // The app's own screen, letterboxed inside the cell.
        val frameLeft = padding
        val frameTop = padding + dotRadius + 4f * density
        val frameRight = width - padding
        val frameBottom = height - stripHeight
        val bitmap: Bitmap? = tile?.latest()
        if (bitmap != null && !bitmap.isRecycled) {
            val bitmapWidth = bitmap.width.toFloat()
            val bitmapHeight = bitmap.height.toFloat()
            val cellWidth = frameRight - frameLeft
            val cellHeight = frameBottom - frameTop
            val drawScale = min(cellWidth / bitmapWidth, cellHeight / bitmapHeight)
            val drawWidth = bitmapWidth * drawScale
            val drawHeight = bitmapHeight * drawScale
            val left = frameLeft + (cellWidth - drawWidth) / 2f
            val top = frameTop + (cellHeight - drawHeight) / 2f
            dst.set(Math.round(left), Math.round(top),
                    Math.round(left + drawWidth), Math.round(top + drawHeight))
            canvas.drawBitmap(bitmap, null, dst, frame)
        } else {
            // No frame yet: a quiet placeholder reads as "slot", not "error".
            frame.color = Ui.color(context, R.color.bg)
            box.set(frameLeft, frameTop, frameRight, frameBottom)
            canvas.drawRoundRect(box, 8f * density, 8f * density, frame)
        }

        // Status dot, top left. The only icon on the tile.
        fill.color = tile?.statusColor() ?: Ui.color(context, R.color.idle)
        canvas.drawCircle(padding + dotRadius, padding + dotRadius, dotRadius, fill)
        fill.color = Ui.color(context, R.color.surface)

        // Slot number, top right, mono, muted.
        tile?.let {
            val slotText = it.slot.toString()
            canvas.drawText(slotText, width - padding - meta.measureText(slotText),
                    padding + meta.textSize, meta)
        }

        tile?.let {
            // Label on the left, frame count on the right, one line at the
            // bottom of the cell.
            val title = if (it.label.isNullOrBlank()) it.pkg else it.label!!.trim()
            val maxWidth = width - 2f * padding - 44f * density
            canvas.drawText(ellipsize(title, maxWidth, label), padding, height - 11f * density, label)

            val stateText = stateText(it)
            canvas.drawText(stateText, width - padding - meta.measureText(stateText),
                    height - 11f * density, meta)
        }
    }

    private fun stateText(tile: Tile): String {
        if (Tile.ERROR == tile.state) {
            // A slot that could not start says why: a silent tile reads as
            // "nothing happened", which is the worst possible feedback.
            return if (tile.error.isNullOrBlank()) "error" else ellipsize(tile.error!!, width * 0.6f, meta)
        }
        val idle = android.os.SystemClock.uptimeMillis() - tile.lastFrameAt
        if (tile.frames > 0 && idle < 3000) {
            return tile.frames.toString() + "f"
        }
        return tile.stateLabel()
    }

    private fun ellipsize(value: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(value) <= maxWidth) return value
        var end = value.length
        while (end > 1 && paint.measureText(value, 0, end) > maxWidth) {
            end--
        }
        return value.substring(0, end - 1) + "."
    }
}