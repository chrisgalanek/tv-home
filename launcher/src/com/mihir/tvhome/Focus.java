package com.mihir.tvhome;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * One focus look for every tile and pill on both screens: the same zoom, lift, timing, and a white
 * ring with a soft sheen drawn over the art.
 */
final class Focus {
    static final float SCALE = 1.1f;
    static final float LIFT_DP = 14;
    static final int IN_MS = 300, OUT_MS = 200;

    private Focus() { }

    /** Zooms and lifts {@code v} while focused; fades {@code ring} (may be null) in and out with it. */
    static void animate(View v, boolean has, View ring) {
        float lift = LIFT_DP * v.getResources().getDisplayMetrics().density;
        v.animate().scaleX(has ? SCALE : 1f).scaleY(has ? SCALE : 1f).translationZ(has ? lift : 0)
                .setInterpolator(has ? new OvershootInterpolator(1.6f) : new DecelerateInterpolator())
                .setDuration(has ? IN_MS : OUT_MS).start();
        if (ring != null) ring.animate().alpha(has ? 1f : 0f).setDuration(160).start();
    }

    /** The ring overlay: a 2dp white stroke with a faint top-left sheen. Starts hidden. */
    static View ring(Context c, float radiusPx) {
        float d = c.getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radiusPx);
        g.setStroke(Math.round(2 * d), 0xE6FFFFFF);
        g.setOrientation(GradientDrawable.Orientation.TL_BR);
        g.setColors(new int[]{0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        View v = new View(c);
        v.setBackground(g);
        v.setAlpha(0f);
        return v;
    }
}
