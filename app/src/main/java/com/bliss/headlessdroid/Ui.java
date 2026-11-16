package com.bliss.headlessdroid;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small view helpers so every control uses the same type, color and rhythm. */
final class Ui {
    private Ui() {
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static int color(Context context, int colorRes) {
        return context.getResources().getColor(colorRes);
    }

    static LinearLayout.LayoutParams size(Context context, int dp) {
        return new LinearLayout.LayoutParams(dp(context, dp), dp(context, dp));
    }

    static Paint textPaint(Context context, float spSize, int colorRes, boolean mono) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(spSize * context.getResources().getDisplayMetrics().scaledDensity);
        paint.setColor(color(context, colorRes));
        paint.setTypeface(mono ? Typeface.MONOSPACE : Typeface.DEFAULT);
        return paint;
    }

    /** Applies an icon tint so icons take the color of their text role. */
    static void tint(android.view.View view, int colorRes) {
        if (view instanceof ImageView) {
            ((ImageView) view).setColorFilter(color(view.getContext(), colorRes));
        }
    }

    static void text(View view, int text) {
        if (view instanceof TextView) {
            ((TextView) view).setText(text);
        }
    }

    /** One accent circle behind a label: the live status dot. */
    static void dot(android.view.View view, int colorRes) {
        if (view instanceof ImageView) {
            ImageView image = (ImageView) view;
            int res;
            switch (colorRes) {
                case R.color.live:
                case R.color.accent:
                    res = R.drawable.dot_live;
                    break;
                case R.color.danger:
                    res = R.drawable.dot_danger;
                    break;
                case R.color.stopped:
                    res = R.drawable.dot_stopped;
                    break;
                case R.color.idle:
                default:
                    res = R.drawable.dot_idle;
                    break;
            }
            image.setImageResource(res);
        }
    }
}