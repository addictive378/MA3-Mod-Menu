package com.ma3.overlay;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * Palet warna + helper tampilan supaya panel melayang dan layar utama punya
 * gaya yang sama (gelap, kartu membulat, aksen hijau toska).
 */
public final class Ui {

    /* latar */
    public static final int BG_TOP = 0xF2141F28;
    public static final int BG_BOTTOM = 0xF20A1015;
    public static final int CARD = 0xFF16222C;
    public static final int CARD_SOFT = 0xFF111A22;
    public static final int STROKE = 0xFF24333F;

    /* teks */
    public static final int TEXT = 0xFFEAF3F8;
    public static final int MUTED = 0xFF8AA3B2;
    public static final int FAINT = 0xFF5E7684;

    /* aksen */
    public static final int ACCENT = 0xFF7FE0A0;
    public static final int ACCENT_INK = 0xFF08170F;
    public static final int GOLD = 0xFFFFD166;
    public static final int DIAMOND = 0xFF6FE3FF;
    public static final int WARN = 0xFFFFC46B;
    public static final int ERR = 0xFFFF8A80;

    private Ui() { }

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static GradientDrawable solid(int color, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable outline(int color, float radiusDp,
                                           int strokeColor, float strokeDp, Context c) {
        GradientDrawable d = solid(color, radiusDp, c);
        if (strokeDp > 0) d.setStroke(dp(c, strokeDp), strokeColor);
        return d;
    }

    public static GradientDrawable vertical(int top, int bottom, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{top, bottom});
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static TextView label(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }
}
