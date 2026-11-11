package com.mihir.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The home screen's top buttons, anywhere: hold Up on the remote inside any app and a transparent
 * bar slides down from the top with Home, Search, Get apps, Screensaver, Sleep, Wi-Fi, Inputs and
 * Settings. A short press of Up still goes to the app. Back or Down (or a few idle seconds) closes it.
 *
 * Android only lets an accessibility service see the remote's keys inside other apps and draw over
 * them, so that's what this is. It reads only the Up key and which app is in front; nothing leaves
 * the TV. It has to be switched on by the owner: Settings → System → Accessibility → Home: top bar.
 */
public class TopBarService extends AccessibilityService {
    static final long HOLD_MS = 650, IDLE_MS = 8000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private FrameLayout bar;
    private LinearLayout row;
    private String frontPackage = "";
    private boolean fired;
    // Coming back: the app that was in front when the bar opened, and (after a bar button opened
    // something like the sleep timer) the app to return to once that screen closes back to home.
    static final long RETURN_WINDOW_MS = 20 * 60 * 1000;
    private String barApp, returnTo;
    private final Set<String> passThrough = new HashSet<>(); // screens on the way back: not a new app
    private long returnUntil;
    private TextView sleepBtn; // shows the sleep timer's time left while one is running
    private long sleepEnd;
    private final Runnable idleClose = this::hideBar;

    @Override
    protected void onServiceConnected() {
        wm = getSystemService(WindowManager.class);
        android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        // Which app is in front: the bar isn't needed on the home screen, which has its own.
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getPackageName() != null) {
            String pkg = e.getPackageName().toString();
            String cls = e.getClassName() == null ? "" : e.getClassName().toString();
            checkReturn(pkg, cls);
            if (!pkg.equals(getPackageName()) || e.getClassName() == null
                    || e.getClassName().toString().endsWith("MainActivity")) {
                frontPackage = pkg;
            }
        }
    }

    @Override
    public void onInterrupt() { }

    /**
     * After a bar button opened a screen (sleep timer, Wi-Fi, Settings, Search...): when that screen
     * closes and Android falls back to the home screen, go back to the app that was in front
     * instead. Not after Home or Inputs, not once the remote's Home was pressed, and not if a
     * different app came up in between (a title picked in Search, say).
     */
    private void checkReturn(String pkg, String cls) {
        if (returnTo == null) return;
        if (System.currentTimeMillis() > returnUntil || pkg.equals(returnTo)) { returnTo = null; return; }
        if (pkg.equals(getPackageName()) && cls.endsWith(".MainActivity")) {
            String app = returnTo;
            returnTo = null;
            handler.postDelayed(() -> openPackage(app), 150);
            return;
        }
        if (!pkg.equals(getPackageName()) && !passThrough.contains(pkg)
                && !pkg.equals("com.android.systemui") && !pkg.equals("android")) {
            returnTo = null; // went on to another app on purpose
        }
    }

    /** Called by a bar button that opens {@code i}: come back to the app afterwards. */
    private void comeBackAfter(Intent i) {
        if (barApp == null || barApp.equals(getPackageName())) return;
        returnTo = barApp;
        returnUntil = System.currentTimeMillis() + RETURN_WINDOW_MS;
        passThrough.clear();
        android.content.pm.ResolveInfo r = getPackageManager().resolveActivity(i, 0);
        if (r != null && r.activityInfo != null) passThrough.add(r.activityInfo.packageName);
        passThrough.add("com.android.tv.settings"); // settings screens hand off to each other
    }

    /**
     * Watches Up only. The press goes to the app as usual; if Up is still held after HOLD_MS the
     * bar opens (timed here, so it works whether or not the remote sends repeats), and any further
     * repeats are kept from the app. The release always reaches the app, so none is left with a
     * key held down.
     */
    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_HOME) returnTo = null; // Home means home: don't bounce back
        if (e.getKeyCode() != KeyEvent.KEYCODE_DPAD_UP || bar != null) return false;
        if (frontPackage.equals(getPackageName())) return false; // home screen: it has the bar already
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                fired = false;
                handler.removeCallbacks(holdTimer);
                handler.postDelayed(holdTimer, HOLD_MS);
                return false;
            }
            if (!fired && e.isLongPress()) { // Android's own long-press mark on a held key
                handler.removeCallbacks(holdTimer);
                fired = true;
                handler.post(this::showBar);
                return true;
            }
            return fired; // repeats after the bar opened don't reach the app
        }
        handler.removeCallbacks(holdTimer); // released early: just an ordinary Up press
        fired = false;
        return false;
    }

    private final Runnable holdTimer = () -> { fired = true; showBar(); };

    // ---------------------------------------------------------------- the bar

    private void showBar() {
        if (bar != null || wm == null) return;
        barApp = frontPackage;
        bar = new FrameLayout(this) {
            @Override public boolean dispatchKeyEvent(KeyEvent e) {
                int k = e.getKeyCode();
                if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (e.getAction() == KeyEvent.ACTION_UP) hideBar();
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_UP) return true; // already at the top
                restartIdle();
                return super.dispatchKeyEvent(e);
            }
        };
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        // Transparent: a soft shade from the top edge fading to nothing, so the app shows through.
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC000000, 0x80000000, 0x00000000}));
        bar.setPadding(dp(48), dp(36), dp(48), dp(56));

        row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        List<TextView> buttons = new ArrayList<>();
        buttons.add(pill("Home", R.drawable.ic_home_outline, false, this::goHome));
        buttons.add(pill("Search", R.drawable.ic_search, true, () -> openAndReturn(new Intent(this, SearchActivity.class))));
        if (isInstalled(MainActivity.PLAY_STORE)) {
            buttons.add(pill("Get apps", R.drawable.ic_add, false, () -> {
                Intent store = getPackageManager().getLeanbackLaunchIntentForPackage(MainActivity.PLAY_STORE);
                if (store == null) store = getPackageManager().getLaunchIntentForPackage(MainActivity.PLAY_STORE);
                if (store != null) openAndReturn(store);
            }));
        }
        buttons.add(pill("Screensaver", R.drawable.ic_screensaver, false, () -> openAndReturn(new Intent(this, SlideshowActivity.class))));
        Intent sleep = new Intent(MainActivity.SONY_SLEEP_TIMER).setPackage(MainActivity.SONY_TIMERS);
        if (!getPackageManager().queryIntentActivities(sleep, 0).isEmpty()) {
            sleepBtn = pill("Sleep", R.drawable.ic_sleep, false, () -> openAndReturn(new Intent(sleep)));
            buttons.add(sleepBtn);
            new Thread(() -> {
                long end = SleepTimer.endTime();
                handler.post(() -> {
                    sleepEnd = end;
                    if (sleepBtn != null) setLabel(sleepBtn, "Sleep", sleepBtn.hasFocus());
                });
            }).start();
        }
        buttons.add(pill("Wi-Fi", R.drawable.ic_wifi, false, () -> openAndReturn(new Intent(Settings.ACTION_WIFI_SETTINGS))));
        buttons.add(pill("Inputs", R.drawable.ic_input, false,
                () -> { returnTo = null; open(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_INPUTS, true)); }));
        buttons.add(pill("Settings", R.drawable.ic_settings, false, () -> openAndReturn(new Intent(Settings.ACTION_SETTINGS))));
        for (int i = 0; i < buttons.size(); i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(34));
            p.setMargins(i == 0 ? 0 : dp(12), 0, 0, 0);
            row.addView(buttons.get(i), p);
            View b = buttons.get(i);
            b.setNextFocusUpId(b.getId());
            b.setNextFocusLeftId(i == 0 ? b.getId() : buttons.get(i - 1).getId());
            b.setNextFocusRightId(i == buttons.size() - 1 ? b.getId() : buttons.get(i + 1).getId());
        }
        bar.addView(row, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP;
        wm.addView(bar, lp);

        // Slide down from above the top edge.
        bar.setAlpha(0f);
        bar.setTranslationY(-dp(140));
        bar.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f)).setDuration(320).start();
        buttons.get(1).requestFocus(); // Search, as on the home screen
        restartIdle();
    }

    private void hideBar() {
        handler.removeCallbacks(idleClose);
        FrameLayout b = bar;
        if (b == null) return;
        bar = null;
        b.animate().alpha(0f).translationY(-dp(140)).setDuration(240).withEndAction(() -> {
            try { wm.removeView(b); } catch (RuntimeException ignored) { }
        }).start();
    }

    private void restartIdle() {
        handler.removeCallbacks(idleClose);
        handler.postDelayed(idleClose, IDLE_MS);
    }

    /** A top-bar button styled like the home screen's: a round icon whose label slides out on focus. */
    private TextView pill(String label, int icon, boolean showLabel, Runnable action) {
        TextView p = new TextView(this);
        p.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        p.setTextColor(0xE6FFFFFF);
        p.setSingleLine(true);
        p.setGravity(Gravity.CENTER);
        p.setFocusable(true);
        p.setId(View.generateViewId());
        Drawable d = getDrawable(icon).mutate();
        d.setBounds(0, 0, dp(16), dp(16));
        d.setTint(0xE6FFFFFF);
        p.setCompoundDrawables(d, null, null, null);
        setLabel(p, label, showLabel);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(17));
        bg.setColor(0x66000000);
        bg.setStroke(dp(1), 0x33FFFFFF);
        p.setBackground(bg);
        p.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x66000000);
            p.setTextColor(has ? 0xFF111111 : 0xE6FFFFFF);
            d.setTint(has ? 0xFF111111 : 0xE6FFFFFF);
            if (!showLabel) setLabel(p, label, has);
            Focus.animate(v, has, null);
        });
        p.setOnClickListener(v -> { hideBar(); action.run(); });
        return p;
    }

    private void setLabel(TextView p, String label, boolean on) {
        String text = on ? label : "";
        if (p == sleepBtn && SleepTimer.running(sleepEnd)) {
            String left = SleepTimer.left(sleepEnd); // a timer is running: its time left always shows
            text = on ? "Sleep  ·  " + left : left;
        }
        boolean shown = !text.isEmpty();
        p.setText(text);
        p.setCompoundDrawablePadding(shown ? dp(8) : 0);
        p.setPadding(shown ? dp(14) : dp(9), 0, shown ? dp(16) : dp(9), 0);
    }

    // ---------------------------------------------------------------- actions

    /** Opens a screen from the bar and comes back to the app once it closes. */
    private void openAndReturn(Intent i) {
        comeBackAfter(i);
        open(i);
    }

    private void goHome() {
        returnTo = null;
        open(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
    }

    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { }
    }

    private void openPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) open(i);
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
