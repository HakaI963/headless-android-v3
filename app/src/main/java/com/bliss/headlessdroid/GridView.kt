package com.bliss.headlessdroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.ceil

/**
 * The grid itself: up to three portrait cells per row, at most nine running
 * apps, one "add" cell whenever a slot is free.
 *
 * <p>Hand-laid out instead of RecyclerView because the grid has at most ten
 * children and every child is one app's live screen; the layout code is the
 * whole implementation and there is nothing to recycle.
 */
class GridView : ViewGroup {

    /** Grid events the activity reacts to. */
    interface Listener {
        fun onAdd()
        fun onLongPress(tile: Tile)
        fun onSelect(slot: Int)
    }

    private val gap: Float = Ui.dp(context, 8f).toFloat()
    private val border = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plus = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    private var columns: Int = Droid.MAX_COLUMNS
    private var showAdd: Boolean = true
    var listener: Listener? = null

    constructor(context: Context) : super(context) {
        clipChildren = false
    }

    /**
     * The constructor the layout inflater actually calls.
     *
     * <p>Without it, inflating `<com.bliss.headlessdroid.GridView>` throws
     * [NoSuchMethodException] wrapped in an [android.view.InflateException],
     * which kills the activity before anything is drawn.
     */
    constructor(context: Context, attrs: AttributeSet?) : this(context)

    constructor(context: Context, attrs: AttributeSet?, style: Int) : this(context)

    fun setColumns(value: Int) {
        val clamped = Droid.clamp(value, 1, Droid.MAX_COLUMNS, Droid.MAX_COLUMNS)
        if (clamped != columns) {
            columns = clamped
            requestLayout()
        }
    }

    fun columns(): Int = columns

    fun setShowAdd(value: Boolean) {
        if (value != showAdd) {
            showAdd = value
            requestLayout()
        }
    }

    private fun cellWidth(): Int {
        val available = width - paddingLeft - paddingRight
        if (available <= 0) return 0
        val total = ((columns - 1) * gap).toInt()
        return (available - total) / columns
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val inner = width - paddingLeft - paddingRight
        val cell = (inner - (columns - 1) * gap).toInt() / columns
        val cellHeight = Math.round(cell * ASPECT)
        val rows = ceil(childCount / columns.toDouble()).toInt()
        val totalHeight = paddingTop + paddingBottom +
                rows * cellHeight + Math.max(0, rows - 1) * gap.toInt()
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(width, resolveSize(totalHeight, heightSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val cell = cellWidth()
        if (cell <= 0) return
        val cellHeight = Math.round(cell * ASPECT)
        var x = paddingLeft
        var y = paddingTop
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.layout(x, y, x + cell, y + cellHeight)
            x += (cell + gap).toInt()
            if ((index + 1) % columns == 0) {
                x = paddingLeft
                y += (cellHeight + gap).toInt()
            }
        }
    }

    /** Rebuilds the tile views from the engine's current list. */
    fun rebuild(tiles: List<Tile>, selectedSlot: Int) {
        detachAll()
        for (tile in tiles) {
            val view = TileView(context)
            view.contentDescription = tile.label
            view.bind(tile, tile.slot == selectedSlot)
            view.setOnClickListener { select(tile.slot) }
            view.setOnLongClickListener {
                listener?.onLongPress(tile)
                true
            }
            addView(view)
        }
        if (showAdd && tiles.size < Droid.MAX_SLOTS) {
            addView(AddCell(context) { listener?.onAdd() })
        }
        requestLayout()
    }

    private fun select(slot: Int) {
        for (index in 0 until childCount) {
            val child = getChildAt(index) as? TileView ?: continue
            child.bind(child.tile(), child.slot() == slot)
        }
        listener?.onSelect(slot)
    }

    fun tileViews(): List<TileView> {
        val out = ArrayList<TileView>()
        for (index in 0 until childCount) {
            val child = getChildAt(index) as? TileView ?: continue
            out.add(child)
        }
        return out
    }

    private fun detachAll() {
        for (index in childCount - 1 downTo 0) {
            removeViewAt(index)
        }
    }

    /** The "add" cell: same cell geometry, no frame, one plus sign. */
    private class AddCell(
        context: Context,
        private val onClick: () -> Unit
    ) : View(context) {

        private val arm = Ui.dp(context, 10f).toFloat()
        private val density = resources.displayMetrics.density
        private val border = Paint(Paint.ANTI_ALIAS_FLAG)
        private val plus = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()

        init {
            isClickable = true
            setOnClickListener { onClick() }
            border.style = Paint.Style.STROKE
            border.strokeWidth = density
            border.color = Ui.color(context, R.color.hairline)
            plus.style = Paint.Style.STROKE
            plus.strokeWidth = 1.5f * density
            plus.color = Ui.color(context, R.color.text_muted)
            plus.strokeCap = Paint.Cap.ROUND
            label.textSize = 13f * resources.displayMetrics.scaledDensity
            label.color = Ui.color(context, R.color.text_muted)
            label.isAntiAlias = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val width = width.toFloat()
            val height = height.toFloat()
            box.set(0.5f * density, 0.5f * density, width - 0.5f * density, height - 0.5f * density)
            canvas.drawRoundRect(box, 12f * density, 12f * density, border)

            val centerX = width / 2f
            val centerY = height / 2f - 8f * density
            canvas.drawLine(centerX - arm, centerY, centerX + arm, centerY, plus)
            canvas.drawLine(centerX, centerY - arm, centerX, centerY + arm, plus)

            val text = context.getString(R.string.action_add)
            canvas.drawText(text, centerX - label.measureText(text) / 2f, height - 14f, label)
        }
    }

    private companion object {
        /** Portrait cells: three across, and tall enough to read an app in. */
        const val ASPECT = 4f / 3f
    }
}