package com.bliss.headlessdroid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

/**
 * One slot in the grid: the real app's own screen, small and quiet.
 *
 * <p>This is the one place in the product that carries color — the app running
 * inside it. The chrome around the frame is graphite and one hairline, so the
 * frame reads instantly. The status dot is the product's signature: amber
 * while the app renders, grey when it does not.
 */
public final class TileView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint meta = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Rect dst = new Rect();

    private final float radius;
    private final float padding;
    private final float dotRadius;
    private final float stripHeight;
    private final float topStrip;

    private Tile tile;
    private boolean selected;
    private long drawnFrames = -1;
    private String drawnState = "";
    private boolean drawnSelected;

    public TileView(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        radius = 12f * density;
        padding = 8f * density;
        dotRadius = 3f * density;
        stripHeight = 30f * density;
        topStrip = 16f * density;

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Ui.color(context, R.color.surface));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(density);
        stroke.setColor(Ui.color(context, R.color.hairline));

        frame.setFilterBitmap(true);
        frame.setAntiAlias(true);

        label.setTextSize(13f * getResources().getDisplayMetrics().scaledDensity);
        label.setColor(Ui.color(context, R.color.text_secondary));
        label.setAntiAlias(true);

        meta.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
        meta.setColor(Ui.color(context, R.color.text_muted));
        meta.setTypeface(android.graphics.Typeface.MONOSPACE);
        meta.setAntiAlias(true);

        setClickable(true);
        setFocusable(true);
    }

    public void bind(Tile tile, boolean selected) {
        this.tile = tile;
        this.selected = selected;
        sync();
    }

    /** Invalidates only when the tile actually changed. */
    public void sync() {
        if (tile == null) {
            return;
        }
        long frames = tile.frames;
        String state = String.valueOf(tile.state);
        if (frames == drawnFrames && state.equals(drawnState) && selected == drawnSelected) {
            return;
        }
        drawnFrames = frames;
        drawnState = state;
        drawnSelected = selected;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        box.set(0f, 0f, width, height);
        canvas.drawRoundRect(box, radius, radius, fill);
        stroke.setStrokeWidth(getResources().getDisplayMetrics().density);
        stroke.setColor(Ui.color(getContext(), selected ? R.color.accent : R.color.hairline));
        canvas.drawRoundRect(box, radius, radius, stroke);

        float scale = getResources().getDisplayMetrics().density;

        // The app's own screen, letterboxed inside the cell.
        float frameLeft = padding;
        float frameTop = padding + dotRadius + 4f * scale;
        float frameRight = width - padding;
        float frameBottom = height - stripHeight;
        Bitmap bitmap = tile == null ? null : tile.latest();
        if (bitmap != null && !bitmap.isRecycled()) {
            int bw = bitmap.getWidth();
            int bh = bitmap.getHeight();
            float cellW = frameRight - frameLeft;
            float cellH = frameBottom - frameTop;
            float drawScale = Math.min(cellW / bw, cellH / bh);
            float dw = bw * drawScale;
            float dh = bh * drawScale;
            float dx = frameLeft + (cellW - dw) / 2f;
            float dy = frameTop + (cellH - dh) / 2f;
            dst.set(Math.round(dx), Math.round(dy),
                    Math.round(dx + dw), Math.round(dy + dh));
            canvas.drawBitmap(bitmap, null, dst, frame);
        } else {
            // No frame yet: a quiet placeholder reads as "slot", not "error".
            frame.setColor(Ui.color(getContext(), R.color.bg));
            box.set(frameLeft, frameTop, frameRight, frameBottom);
            canvas.drawRoundRect(box, 8f * scale, 8f * scale, frame);
        }

        // Status dot, top left. The only icon on the tile.
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(tile == null ? Ui.color(getContext(), R.color.idle) : tile.statusColor());
        canvas.drawCircle(padding + dotRadius, padding + dotRadius, dotRadius, fill);
        fill.setColor(Ui.color(getContext(), R.color.surface));

        // Slot number, top right, mono, muted.
        if (tile != null) {
            String slotText = String.valueOf(tile.slot);
            float slotWidth = meta.measureText(slotText);
            canvas.drawText(slotText, width - padding - slotWidth,
                    padding + meta.getTextSize(), meta);
        }

        if (tile != null) {
            // Label on the left, frame count on the right, one line at the
            // bottom of the cell.
            String title = tile.label == null || tile.label.trim().isEmpty()
                    ? tile.pkg : tile.label.trim();
            float maxWidth = width - 2 * padding - 44f * scale;
            String clipped = ellipsize(title, maxWidth, label);
            canvas.drawText(clipped, padding, height - 11f * scale, label);

            String stateText = stateText();
            float stateWidth = meta.measureText(stateText);
            canvas.drawText(stateText, width - padding - stateWidth,
                    height - 11f * scale, meta);
        }
    }

    private String stateText() {
        if (tile == null) {
            return "";
        }
        long idle = android.os.SystemClock.uptimeMillis() - tile.lastFrameAt;
        if (tile.frames > 0 && idle < 3000) {
            return tile.frames + "f";
        }
        return tile.stateLabel();
    }

    private String ellipsize(String value, float maxWidth, Paint paint) {
        if (paint.measureText(value) <= maxWidth) {
            return value;
        }
        int end = value.length();
        while (end > 1 && paint.measureText(value, 0, end) > maxWidth) {
            end--;
        }
        return value.substring(0, end - 1) + ".";
    }

    public Tile tile() {
        return tile;
    }

    public int slot() {
        return tile == null ? -1 : tile.slot;
    }
}