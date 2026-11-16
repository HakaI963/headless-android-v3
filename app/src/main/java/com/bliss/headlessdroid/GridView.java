package com.bliss.headlessdroid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * The grid itself: up to three portrait cells per row, at most nine running
 * apps, one "add" cell whenever a slot is free.
 *
 * <p>Hand-laid out instead of RecyclerView because the grid has at most ten
 * children and every child is one app's live screen; the layout code is the
 * whole implementation and there is nothing to recycle.
 */
public final class GridView extends ViewGroup {
    private static final float ASPECT = 4f / 3f;

    private final float gap;
    private final float radius;
    private int columns = Droid.MAX_COLUMNS;
    private boolean showAdd = true;

    /** Grid events the activity reacts to. */
    public interface Listener {
        void onAdd();

        void onLongPress(Tile tile);

        void onSelect(int slot);
    }

    private Listener listener;

    public GridView(Context context) {
        super(context);
        gap = Ui.dp(context, 8);
        radius = Ui.dp(context, 12);
        setClipChildren(false);
    }

    public void setColumns(int value) {
        int clamped = Droid.clamp(value, 1, Droid.MAX_COLUMNS, Droid.MAX_COLUMNS);
        if (clamped != columns) {
            columns = clamped;
            requestLayout();
        }
    }

    public int columns() {
        return columns;
    }

    public void setShowAdd(boolean value) {
        if (value != showAdd) {
            showAdd = value;
            requestLayout();
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private int cellWidth() {
        int available = getWidth() - getPaddingLeft() - getPaddingRight();
        if (available <= 0) {
            return 0;
        }
        int total = (int) ((columns - 1) * gap);
        return (available - total) / columns;
    }

    private int cellHeight(int cellWidth) {
        return Math.round(cellWidth * ASPECT);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int inner = width - getPaddingLeft() - getPaddingRight();
        int cell = (int) ((inner - (columns - 1) * gap) / columns);
        int cellH = Math.round(cell * ASPECT);
        int rows = (int) Math.ceil(getChildCount() / (double) columns);
        int totalHeight = getPaddingTop() + getPaddingBottom()
                + rows * cellH + Math.max(0, rows - 1) * (int) gap;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(width, resolveSize(totalHeight, heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int cell = cellWidth();
        if (cell <= 0) {
            return;
        }
        int cellH = cellHeight(cell);
        int x = getPaddingLeft();
        int y = getPaddingTop();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.layout(x, y, x + cell, y + cellH);
            x += cell + gap;
            if ((i + 1) % columns == 0) {
                x = getPaddingLeft();
                y += cellH + gap;
            }
        }
    }

    /** Rebuilds the tile views from the engine's current list. */
    public void rebuild(List<Tile> tiles, int selectedSlot) {
        detachAll();
        int slot = 0;
        for (Tile tile : tiles) {
            TileView view = new TileView(getContext());
            view.setContentDescription(tile.label);
            view.bind(tile, tile.slot == selectedSlot);
            view.setOnClickListener(v -> select(tile.slot));
            view.setOnLongClickListener(v -> {
                if (listener != null) {
                    listener.onLongPress(tile);
                }
                return true;
            });
            addView(view);
            slot = tile.slot;
        }
        if (showAdd && tiles.size() < Droid.MAX_SLOTS) {
            addView(new AddCell(getContext(), v -> {
                if (listener != null) {
                    listener.onAdd();
                }
            }));
        }
        requestLayout();
    }

    private void select(int slot) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof TileView) {
                TileView tileView = (TileView) child;
                tileView.bind(tileView.tile(), tileView.slot() == slot);
            }
        }
        Listener local = listener;
        if (local != null) {
            local.onSelect(slot);
        }
    }

    public List<TileView> tileViews() {
        List<TileView> out = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof TileView) {
                out.add((TileView) child);
            }
        }
        return out;
    }

    private void detachAll() {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            removeViewAt(i);
        }
    }

    /** The "add" cell: same cell geometry, no frame, one plus sign. */
    private static final class AddCell extends View {
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint plus = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final float radius;
        private final float line;
        private final float arm;

        AddCell(Context context, OnClickListener listener) {
            super(context);
            float density = getResources().getDisplayMetrics().density;
            radius = 12f * density;
            line = density;
            arm = 10f * density;
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(line);
            border.setColor(Ui.color(context, R.color.hairline));
            plus.setStyle(Paint.Style.STROKE);
            plus.setStrokeWidth(1.5f * density);
            plus.setColor(Ui.color(context, R.color.text_muted));
            plus.setStrokeCap(Paint.Cap.ROUND);
            label.setTextSize(13f * getResources().getDisplayMetrics().scaledDensity);
            label.setColor(Ui.color(context, R.color.text_muted));
            label.setAntiAlias(true);
            setClickable(true);
            setOnClickListener(listener);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            box.set(line / 2f, line / 2f, width - line / 2f, height - line / 2f);
            canvas.drawRoundRect(box, radius, radius, border);

            float cx = width / 2f;
            float cy = height / 2f - 8f;
            canvas.drawLine(cx - arm, cy, cx + arm, cy, plus);
            canvas.drawLine(cx, cy - arm, cx, cy + arm, plus);

            String text = getContext().getString(R.string.action_add);
            float textWidth = label.measureText(text);
            canvas.drawText(text, cx - textWidth / 2f, height - 14f, label);
        }
    }
}