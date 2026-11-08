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
    private int start;        // the picture to open on (the home screen's)
    private int current = -1; // the picture on screen
    // Pictures shown before (for Previous) and after, once you've stepped back (for Next).
    private final java.util.ArrayDeque<Integer> history = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Integer> forward = new java.util.ArrayDeque<>();
    private long fadeMs = FADE_MS;
    private final TextView prevButton, nextButton;
    private final Runnable hideButtons = this::hideButtons;
    private ImageView back, front;
    private boolean first = true; // the home screen's picture: shown at once, not faded in from black
    private final LinearLayout info;
    private final TextView title, sub, clock;
    private final Runnable next = () -> nextPicture(false);
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

        // Previous / next: round buttons at the left and right edges. They fade in when an arrow
        // on the remote is pressed (left and right step through pictures) and fade out a few
        // seconds later, so the picture stays uncluttered.
        prevButton = arrowButton(R.drawable.ic_prev);
        nextButton = arrowButton(R.drawable.ic_next);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START | Gravity.CENTER_VERTICAL);
        pl.setMargins(dp(40), 0, 0, 0);
        root.addView(prevButton, pl);
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END | Gravity.CENTER_VERTICAL);
        nl.setMargins(0, 0, dp(40), 0);
        root.addView(nextButton, nl);

        walls = Wallpapers.load(c);
        // Start on the picture the home screen is showing (MainActivity saves its index in its own
        // preferences), so the slideshow carries on from it rather than jumping somewhere else.
        int home = c.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getInt("wallpaper", -1);
        start = walls.paths.length == 0 ? 0
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
        nextPicture(false);
    }

    /**
     * Remote keys: left / right go to the previous / next picture, up / down just show the
     * buttons. Returns false for any other key (the caller closes the slideshow).
     */
    boolean handleKey(android.view.KeyEvent e) {
        int k = e.getKeyCode();
        boolean arrow = k == android.view.KeyEvent.KEYCODE_DPAD_LEFT || k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                || k == android.view.KeyEvent.KEYCODE_DPAD_UP || k == android.view.KeyEvent.KEYCODE_DPAD_DOWN;
        if (!arrow) return false;
        if (e.getAction() != android.view.KeyEvent.ACTION_DOWN) return true;
        showButtons();
        if (k == android.view.KeyEvent.KEYCODE_DPAD_LEFT) { press(prevButton); previousPicture(); }
        if (k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) { press(nextButton); nextPicture(true); }
        return true;
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
    }

    /** The next picture: retracing steps after Previous, otherwise the next in the shared shuffle. */
    private void nextPicture(boolean manual) {
        if (walls.paths.length == 0) return;
        Integer fwd = forward.poll();
        int idx = current < 0 ? start
                : fwd != null ? fwd
                : Wallpapers.next(c, walls.paths.length, current); // same shuffle as the home screen
        if (current >= 0) {
            history.push(current);
            while (history.size() > 50) history.removeLast();
        }
        showAt(idx, manual);
    }

    private void previousPicture() {
        Integer prev = history.poll();
        if (prev == null) return;
        forward.push(current);
        showAt(prev, true);
    }

    /** Decode a picture off the main thread, then crossfade it in (quicker when stepped to by hand). */
    private void showAt(int idx, boolean manual) {
        current = idx;
        fadeMs = manual ? 900 : FADE_MS;
        handler.removeCallbacks(next);
        String path = walls.paths[idx];
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
        prevButton.bringToFront();
        nextButton.bringToFront();
        front.setImageBitmap(bm);
        front.setAlpha(first ? 1f : 0f);
        first = false;
        // The whole picture, edge to edge (no zoom, which would crop it). The picture changing every
        // 40 seconds and the caption moving between corners keep the panel from burning in.
        front.animate().alpha(1f).setDuration(fadeMs).withEndAction(() -> back.setImageDrawable(null)).start();
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

    private TextView arrowButton(int icon) {
        TextView b = new TextView(c);
        android.graphics.drawable.Drawable d = c.getDrawable(icon).mutate();
        d.setBounds(0, 0, dp(26), dp(26));
        d.setTint(0xF2FFFFFF);
        b.setCompoundDrawables(d, null, null, null);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(15), 0, 0, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x80000000);
        bg.setStroke(dp(1), 0x40FFFFFF);
        b.setBackground(bg);
        b.setAlpha(0f);
        return b;
    }

    private void showButtons() {
        for (View v : new View[]{prevButton, nextButton}) v.animate().alpha(1f).setDuration(250).start();
        handler.removeCallbacks(hideButtons);
        handler.postDelayed(hideButtons, 3500);
    }

    private void hideButtons() {
        for (View v : new View[]{prevButton, nextButton}) v.animate().alpha(0f).setDuration(600).start();
    }

    /** A quick white flash and pop on the button that was pressed. */
    private void press(View b) {
        GradientDrawable bg = (GradientDrawable) b.getBackground();
        bg.setColor(0xE6FFFFFF);
        ((TextView) b).getCompoundDrawables()[0].setTint(0xFF111111);
        b.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120).withEndAction(() -> {
            bg.setColor(0x80000000);
            ((TextView) b).getCompoundDrawables()[0].setTint(0xF2FFFFFF);
            b.animate().scaleX(1f).scaleY(1f).setDuration(200).start();
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
