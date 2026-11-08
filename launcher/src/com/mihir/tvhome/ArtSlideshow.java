package com.mihir.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * The art slideshow shown by the screensaver (ArtDream) and the home screen's Screensaver button
 * (SlideshowActivity): the home screen's pictures full screen, starting with the one the home
 * screen is showing, then a new one every 40 seconds, crossfading, each shown whole, with its
 * caption and the time in the bottom corners (swapping sides now and then).
 */
final class ArtSlideshow {
    static final long SHOW_MS = 40_000, FADE_MS = 3_000;

    private final Context c;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FrameLayout root;
    private final Wallpapers walls;
    private int index;
    private ImageView back, front;
    private boolean first = true; // the home screen's picture: shown at once, not faded in from black
    private final LinearLayout info;
    private final TextView title, sub, clock;
    private final Runnable next = this::showNext;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            clock.setText(new SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(c)
                    ? "H:mm" : "h:mm", Locale.getDefault()).format(new Date()));
            handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
        }
    };

    ArtSlideshow(Context c) {
        this.c = c;
        root = new FrameLayout(c);
        root.setBackgroundColor(Color.BLACK);
        back = layer();
        front = layer();
        root.addView(back, new FrameLayout.LayoutParams(-1, -1));
        root.addView(front, new FrameLayout.LayoutParams(-1, -1));
        // A soft shade along the bottom so the caption and clock read on any picture.
        View shade = new View(c);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xB3000000, 0x33000000, 0x00000000}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, dp(240), Gravity.BOTTOM));

        info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        title = text(20, "sans-serif-medium", 0xF2FFFFFF);
        sub = text(15, "sans-serif", 0xBFFFFFFF);
        info.addView(title);
        info.addView(sub);
        root.addView(info, lp(Gravity.BOTTOM | Gravity.START));
        clock = text(30, "sans-serif-thin", 0xE6FFFFFF);
        root.addView(clock, lp(Gravity.BOTTOM | Gravity.END));

        walls = Wallpapers.load(c);
        // Start on the picture the home screen is showing (MainActivity saves its index in its own
        // preferences), so the slideshow carries on from it rather than jumping somewhere else.
        int home = c.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getInt("wallpaper", -1);
        index = walls.paths.length == 0 ? 0
                : home >= 0 ? home % walls.paths.length : new Random().nextInt(walls.paths.length);
    }

    View view() { return root; }

    /** Full brightness for the window showing the slideshow, whatever the TV's brightness setting. */
    static void fullBrightness(android.view.Window w) {
        android.view.WindowManager.LayoutParams a = w.getAttributes();
        a.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL;
        w.setAttributes(a);
    }

    void start() {
        handler.removeCallbacksAndMessages(null);
        tick.run();
        showNext();
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
    }

    /** Decode the next picture off the main thread, then crossfade and slowly zoom it in. */
    private void showNext() {
        if (walls.paths.length == 0) return;
        String path = walls.paths[index];
        index = Wallpapers.next(c, walls.paths.length, index); // same shuffle as the home screen
        new Thread(() -> {
            Bitmap bm = null;
            try (java.io.InputStream in = Wallpapers.open(c, path)) {
                bm = BitmapFactory.decodeStream(in);
            } catch (java.io.IOException ignored) { }
            Bitmap result = bm;
            handler.post(() -> { if (result != null && front != null) reveal(result, path); });
        }).start();
        handler.postDelayed(next, SHOW_MS);
    }

    private void reveal(Bitmap bm, String path) {
        ImageView old = front;
        front = back;
        back = old;
        front.bringToFront();
        info.bringToFront();
        clock.bringToFront();
        front.setImageBitmap(bm);
        front.setAlpha(first ? 1f : 0f);
        first = false;
        // The whole picture, edge to edge (no zoom, which would crop it). The picture changing every
        // 40 seconds and the caption moving between corners keep the panel from burning in.
        front.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> back.setImageDrawable(null)).start();
        boolean left = new Random().nextBoolean();
        ((FrameLayout.LayoutParams) info.getLayoutParams()).gravity = Gravity.BOTTOM | (left ? Gravity.START : Gravity.END);
        ((FrameLayout.LayoutParams) clock.getLayoutParams()).gravity = Gravity.BOTTOM | (left ? Gravity.END : Gravity.START);
        info.setGravity(left ? Gravity.START : Gravity.END);
        info.requestLayout();
        clock.requestLayout();
        // Caption fades out, changes, and fades back in with the new picture.
        info.animate().alpha(0f).setDuration(600).withEndAction(() -> {
            title.setText(walls.title(path));
            sub.setText(walls.subtitle(path));
            info.animate().alpha(1f).setDuration(1200).start();
        }).start();
    }

    private ImageView layer() {
        ImageView v = new ImageView(c);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER); // whole picture; ours are all 16:9 so it fills the screen
        return v;
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        t.setShadowLayer(dp(10), 0, 0, 0xCC000000);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setMaxWidth(dp(800));
        return t;
    }

    private FrameLayout.LayoutParams lp(int gravity) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, gravity);
        p.setMargins(dp(56), 0, dp(56), dp(40));
        return p;
    }

    private int dp(float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
