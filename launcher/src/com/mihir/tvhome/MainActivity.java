package com.mihir.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Apple TV style home screen: clock top-left, pills top-right, the focused dock app's name over an
 * ambient glow, a dock of pinned apps, and every other app in a grid that scrolls up on Down.
 * Plain framework views only (no AndroidX), no network, no background work.
 */
public class MainActivity extends Activity {

    // Hidden apps, Live TV and grid width come from res/values/config.xml. The dock starts from
    // config.xml too, then follows whatever is set on the TV (long-press OK on a tile), saved in prefs.
    private final List<String> dockApps = new ArrayList<>();
    static final int MAX_DOCK = 7;
    static final String PREF_DOCK = "dock";
    static final String PLAY_STORE = "com.android.vending";
    static final String SONY_TIMERS = "com.sony.dtv.timers";
    static final String SONY_SLEEP_TIMER = "com.sony.dtv.timers.intent.action.SLEEP_TIMER";
    private String focusPkg; // tile to focus after the next rebuild (one just added or moved)
    private final Set<String> hidden = new HashSet<>();
    private String liveTvPkg, inputPickerPkg;
    private int cols;

    static final class App {
        String pkg, label;
        ComponentName component;
        Drawable art;
        boolean isBanner;
        int color;
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private final List<View> recentCards = new ArrayList<>();
    private View recentsSection;
    private LinearLayout recentsRow;
    private android.widget.HorizontalScrollView recentsScroll;
    private String recentsSig;
    private final List<View> pills = new ArrayList<>();
    private TextView inputsPill;
    private List<WatchHistory.Item> history = new ArrayList<>();

    // Show artwork for Recently watched tiles that came without any, looked up on TMDB (one at a time).
    private Tmdb tmdb;
    private final java.util.concurrent.ExecutorService bg = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Set<String> artTried = new HashSet<>();

    // Hero: what's above the dock. The focused dock app's last show (artwork behind, "Continue" text),
    // or just the app's name over the wallpaper when nothing has been watched in it.
    private ImageView heroArt;
    private LinearLayout heroText;
    private TextView kicker, heroSub;
    private String heroKey;
    private Runnable heroArtTask;
    static final int PEEK_DP = 64; // how much of the row under the dock shows on the first screen

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private FrameLayout hero;
    private LinearLayout grid;
    private View glow;
    private ImageView wallBack, wallFront;
    private View gridShade;
    private String[] wallpapers = new String[0]; // asset paths, e.g. "wallpapers/art/07.jpg"
    private final Map<String, String[]> captions = new HashMap<>(); // path → {title, artist and date, place}
    private View wallDim;
    private LinearLayout caption;
    private TextView captionTitle, captionSub;
    private int wallIndex = -1;
    private long wallChangedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    // A new picture after a random 2 to 10 minutes, so the changes don't feel mechanical.
    static final long WALLPAPER_MIN_MS = 2 * 60 * 1000, WALLPAPER_MAX_MS = 10 * 60 * 1000;
    private long wallDelay = WALLPAPER_MIN_MS;
    // Pictures shown before (for Previous) and after, when you've stepped back (for Next).
    private final java.util.ArrayDeque<Integer> wallHistory = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Integer> wallForward = new java.util.ArrayDeque<>();
    private TextView searchPill, picPrev, picNext;
    private TextView greeting, clock, ampm, date, title, wifiPill, appsLabel;
    private int glowColor = 0xFF3A3A3A;
    private ValueAnimator glowAnim;
    private int lastFocusedId = View.NO_ID;
    private boolean firstShow = true;
    private int packageSeq;
    private PopupWindow popup;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); updateWifi(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { buildApps(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String saved = getPreferences(MODE_PRIVATE).getString(PREF_DOCK, null);
        if (saved != null) {
            for (String p : saved.split(",")) if (!p.isEmpty()) dockApps.add(p);
        } else {
            dockApps.addAll(Arrays.asList(getResources().getStringArray(R.array.dock_apps)));
        }
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        liveTvPkg = getString(R.string.live_tv_package);
        inputPickerPkg = getString(R.string.input_picker_package);
        cols = getResources().getInteger(R.integer.columns);
        tmdb = new Tmdb(this);
        ArtDownloader.schedule(this); // a few new art pictures each day
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        buildApps();
        // The picture buttons show only while focus is in the top bar.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener(
                (oldFocus, newFocus) -> showPictureButtons(newFocus != null && pills.contains(newFocus)));

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(tick, tf);
        updateClock();
        updateWifi();
        buildRecents();
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last == null && !dockCards.isEmpty()) last = dockCards.get(0);
        if (last != null) last.requestFocus();
        refreshHero(); // something new may have been watched since
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        if (firstShow) { firstShow = false; playEntrance(); }
        // New photo on return if the current one has been up for the full interval.
        if (wallIndex < 0 || SystemClock.elapsedRealtime() - wallChangedAt >= wallDelay) nextWallpaper();
        else scheduleWallpaper(wallDelay - (SystemClock.elapsedRealtime() - wallChangedAt));
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotateWallpaper);
        unregisterReceiver(tick);
        if (popup != null) popup.dismiss();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        bg.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the first dock app.
        if (popup != null) popup.dismiss();
        if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back from the grid returns to the dock.
        if (scroll.getScrollY() > 0 && !dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Wallpaper: two stacked layers so a new photo can crossfade in over the old one.
        wallBack = wallpaperLayer();
        wallFront = wallpaperLayer();
        root.addView(wallBack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(wallFront, new FrameLayout.LayoutParams(-1, -1));
        // Extra darkening for bright pictures, set per picture from its brightness (see showWallpaper).
        wallDim = new View(this);
        wallDim.setBackgroundColor(Color.BLACK);
        wallDim.setAlpha(0f);
        root.addView(wallDim, new FrameLayout.LayoutParams(-1, -1));
        // Show artwork for the focused dock app, crossfaded over the wallpaper (showWallpaper keeps the
        // two picture layers at 0 and 1, under the dimmer and this).
        heroArt = wallpaperLayer();
        heroArt.setAlpha(0f);
        root.addView(heroArt, new FrameLayout.LayoutParams(-1, -1));
        // Scrim: darker at the top (clock, pills) and bottom (dock) so text stays readable.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x40000000, 0x26000000, 0x8C000000, 0xE6000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        gridShade = new View(this);
        gridShade.setBackgroundColor(0xB3000000);
        gridShade.setAlpha(0f);
        root.addView(gridShade, new FrameLayout.LayoutParams(-1, -1));
        loadWallpapers();

        glow = new View(this);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        setGlow(glowColor);

        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // Hero: one screen tall less a strip, so the top of the next row peeks in under the dock and
        // shows there's more below. Header on top, title + dock at the bottom.
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH - dp(PEEK_DP)));

        hero.addView(buildHeader(), lp(-1, -2, Gravity.TOP, dp(48), dp(36), dp(48), 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        hero.addView(bottom, lp(-1, -2, Gravity.BOTTOM, dp(48), 0, dp(48), dp(14)));

        heroText = new LinearLayout(this);
        heroText.setOrientation(LinearLayout.VERTICAL);
        kicker = text(13, "sans-serif-medium", 0xD9FFFFFF);
        kicker.setLetterSpacing(0.2f);
        kicker.setSingleLine(true);
        heroText.addView(kicker);
        title = text(34, "sans-serif-light", Color.WHITE);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heroText.addView(title, llp(-2, -2, 0, dp(2), 0, 0));
        heroSub = text(15, "sans-serif", 0xCCFFFFFF);
        heroSub.setSingleLine(true);
        heroSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heroText.addView(heroSub, llp(-2, -2, 0, dp(2), 0, 0));
        for (TextView t : new TextView[]{kicker, title, heroSub}) t.setShadowLayer(dp(14), 0, 0, 0x99000000);
        // Above the dock: the hero text on the left, the wallpaper's caption (artworks) on the right.
        LinearLayout aboveDock = new LinearLayout(this);
        aboveDock.setGravity(Gravity.BOTTOM);
        aboveDock.addView(heroText, new LinearLayout.LayoutParams(0, -2, 1f));
        caption = new LinearLayout(this);
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.setGravity(Gravity.END);
        captionTitle = text(14, "sans-serif-medium", 0xE6FFFFFF);
        captionSub = text(12, "sans-serif", 0xB3FFFFFF);
        for (TextView t : new TextView[]{captionTitle, captionSub}) {
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END); // long captions end in "…", never cut at the start
            t.setMaxWidth(dp(760));
            t.setGravity(Gravity.END);
            t.setShadowLayer(dp(10), 0, 0, 0xCC000000);
            caption.addView(t);
        }
        caption.setAlpha(0f);
        aboveDock.addView(caption, llp(-2, -2, dp(24), 0, dp(8), 0));
        bottom.addView(aboveDock, llp(-1, -2, dp(8), 0, 0, dp(14)));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(20), dp(22), dp(20), dp(22));
        GradientDrawable band = new GradientDrawable();
        band.setCornerRadius(dp(26));
        band.setColor(0x1FFFFFFF);
        band.setStroke(dp(1), 0x14FFFFFF);
        dockRow.setBackground(band);
        bottom.addView(dockRow, new LinearLayout.LayoutParams(-1, -2));

        // Recently watched: shows from apps' media sessions (WatchListener), one sideways-scrolling row.
        LinearLayout recents = new LinearLayout(this);
        recents.setOrientation(LinearLayout.VERTICAL);
        recents.setClipChildren(false);
        recents.setClipToPadding(false);
        TextView recentsLabel = text(13, "sans-serif-medium", 0xB3FFFFFF);
        recentsLabel.setText("RECENTLY WATCHED");
        recentsLabel.setLetterSpacing(0.22f);
        recents.addView(recentsLabel, llp(-2, -2, dp(68), 0, 0, 0));
        recentsScroll = new android.widget.HorizontalScrollView(this);
        recentsScroll.setHorizontalScrollBarEnabled(false);
        recentsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        recentsScroll.setClipChildren(false);
        recentsScroll.setClipToPadding(false);
        recentsScroll.setPadding(dp(68), dp(18), dp(68), dp(18));
        recentsRow = new LinearLayout(this);
        recentsRow.setClipChildren(false);
        recentsRow.setClipToPadding(false);
        recentsScroll.addView(recentsRow, new FrameLayout.LayoutParams(-2, -2));
        recents.addView(recentsScroll, new LinearLayout.LayoutParams(-1, -2));
        recents.setVisibility(View.GONE);
        recentsSection = recents;
        content.addView(recents, llp(-1, -2, 0, dp(8), 0, dp(12)));

        appsLabel = text(13, "sans-serif-medium", 0xB3FFFFFF);
        appsLabel.setText("ALL APPS");
        appsLabel.setLetterSpacing(0.22f);
        content.addView(appsLabel, llp(-2, -2, dp(68), dp(8), 0, dp(18)));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, llp(-1, -2, dp(68), 0, dp(68), dp(160)));
        return root;
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greeting = text(12, "sans-serif-medium", 0xB3FFFFFF);
        greeting.setLetterSpacing(0.25f);
        greeting.setSingleLine(true);
        greeting.setPadding(0, 0, dp(16), 0);
        left.addView(greeting);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text(54, "sans-serif-thin", Color.WHITE);
        clock.setIncludeFontPadding(false);
        clockRow.addView(clock);
        ampm = text(14, "sans-serif-light", 0xCCFFFFFF);
        clockRow.addView(ampm, llp(-2, -2, dp(6), 0, 0, dp(8)));
        left.addView(clockRow, llp(-2, -2, 0, dp(2), 0, dp(2)));

        date = text(12, "sans-serif-medium", 0xB3FFFFFF);
        date.setSingleLine(true);
        date.setPadding(0, 0, dp(16), 0); // letterSpacing is not counted in the measured width
        date.setLetterSpacing(0.22f);
        left.addView(date);
        for (TextView t : new TextView[]{greeting, clock, ampm, date}) t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        header.addView(left, new FrameLayout.LayoutParams(dp(420), -2, Gravity.START | Gravity.TOP));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        // Search keeps its label; the rest are round icon buttons whose label slides out on focus.
        TextView search = pill("Search", R.drawable.ic_search, true);
        // Search: movies, shows and people across the TV's apps (SearchActivity, TMDB data).
        search.setOnClickListener(v -> launch(new Intent(this, SearchActivity.class)));
        searchPill = search;
        // Previous / next picture: fade in at the start of the bar while focus is up here.
        picPrev = pill("Previous picture", R.drawable.ic_prev, false);
        picPrev.setOnClickListener(v -> prevWallpaper());
        picNext = pill("Next picture", R.drawable.ic_next, false);
        picNext.setOnClickListener(v -> nextWallpaper());
        for (TextView t : new TextView[]{picPrev, picNext}) t.setAlpha(0f);
        List<TextView> top = new ArrayList<>(Arrays.asList(picPrev, picNext, search));
        inputsPill = pill("Inputs", R.drawable.ic_input, false);
        inputsPill.setOnClickListener(v -> showInputs(v));
        // Get apps: the Play Store's TV storefront. New installs show up in the grid on their own.
        if (isInstalled(PLAY_STORE)) {
            TextView store = pill("Get apps", R.drawable.ic_add, false);
            store.setOnClickListener(v -> openPackage(PLAY_STORE));
            top.add(store);
        }
        // Screensaver: the art slideshow now (the same one the TV's screensaver shows).
        TextView saver = pill("Screensaver", R.drawable.ic_screensaver, false);
        saver.setOnClickListener(v -> {
            try {
                startActivity(new Intent(this, SlideshowActivity.class));
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            } catch (RuntimeException ignored) { }
        });
        top.add(saver);
        // Sleep: the TV's own sleep timer screen. Turning the TV off needs a system permission a
        // launcher can't have, so the manufacturer's timer does the work. Hidden if there isn't one.
        Intent sleep = new Intent(SONY_SLEEP_TIMER).setPackage(SONY_TIMERS);
        if (!getPackageManager().queryIntentActivities(sleep, 0).isEmpty()) {
            TextView sleepPill = pill("Sleep", R.drawable.ic_sleep, false);
            sleepPill.setOnClickListener(v -> launch(new Intent(sleep)));
            top.add(sleepPill);
        }
        wifiPill = pill("Wi-Fi", R.drawable.ic_wifi, false);
        wifiPill.setOnClickListener(v -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        TextView settings = pill("Settings", R.drawable.ic_settings, false);
        settings.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        top.add(wifiPill);
        top.add(inputsPill); // next to Settings
        top.add(settings);
        for (TextView p : top) {
            right.addView(p, llp(-2, dp(34), dp(12), 0, 0, 0));
            pills.add(p);
        }
        header.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        return header;
    }

    /** A top-bar button: icon plus label, or (showLabel false) a round icon whose label shows on focus. */
    private TextView pill(String label, int icon, boolean showLabel) {
        TextView p = text(14, "sans-serif-medium", 0xE6FFFFFF);
        p.setTag(label);
        p.setSingleLine(true);
        p.setGravity(Gravity.CENTER);
        p.setFocusable(true);
        p.setId(View.generateViewId());
        Drawable d = getDrawable(icon).mutate();
        d.setBounds(0, 0, dp(16), dp(16));
        d.setTint(0xE6FFFFFF);
        p.setCompoundDrawables(d, null, null, null);
        setPillLabel(p, showLabel);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(17));
        bg.setColor(0x66000000);
        bg.setStroke(dp(1), 0x33FFFFFF);
        p.setBackground(bg);
        p.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x66000000);
            p.setTextColor(has ? 0xFF111111 : 0xE6FFFFFF);
            d.setTint(has ? 0xFF111111 : 0xE6FFFFFF);
            if (!showLabel) setPillLabel(p, has);
            Focus.animate(v, has, null);
            if (has) { lastFocusedId = v.getId(); scroll.smoothScrollTo(0, 0); fadeGlow(1f); }
        });
        return p;
    }

    /** Label shown: icon + text in a pill. Hidden: a 34dp circle with just the icon. */
    private void setPillLabel(TextView p, boolean on) {
        p.setText(on ? (String) p.getTag() : "");
        p.setCompoundDrawablePadding(on ? dp(8) : 0);
        p.setPadding(on ? dp(14) : dp(9), 0, on ? dp(16) : dp(9), 0);
    }

    // ---------------------------------------------------------------- apps

    private void buildApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> byPkg = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.isBanner = banner != null;
            a.art = banner != null ? banner : ri.loadIcon(pm);
            a.color = sampleColor(a.pkg, a.art);
            byPkg.put(a.pkg, a);
        }

        List<App> dock = new ArrayList<>();
        for (String p : dockApps) if (byPkg.containsKey(p)) dock.add(byPkg.remove(p));
        List<App> rest = new ArrayList<>(byPkg.values());
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));

        dockRow.removeAllViews();
        grid.removeAllViews();
        dockCards.clear();
        gridCards.clear();

        for (int i = 0; i < dock.size(); i++) {
            View tile = tile(dock.get(i), true);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i == 0 ? 0 : dp(10), 0, i == dock.size() - 1 ? 0 : dp(10), 0);
            dockRow.addView(tile, p);
        }

        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, llp(-1, -2, 0, 0, 0, dp(26)));
            }
            View tile = tile(rest.get(i), false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i % cols == 0 ? 0 : dp(10), 0, i % cols == cols - 1 ? 0 : dp(10), 0);
            row.addView(tile, p);
        }
        // Pad the last row so tiles keep their width.
        if (row != null) for (int i = rest.size() % cols; i != 0 && i < cols; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
            p.setMargins(dp(10), 0, i == cols - 1 ? 0 : dp(10), 0);
            row.addView(new View(this), p);
        }
        appsLabel.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        recentsSig = null; // app list changed: rebuild recents too (an app may be gone)
        buildRecents();
        wireFocus();

        if (!dock.isEmpty()) { showHero(dock.get(0)); setGlow(dock.get(0).color); }
        if (focusPkg != null) {
            for (View c : dockCards) if (((App) c.getTag()).pkg.equals(focusPkg)) lastFocusedId = c.getId();
            for (View c : gridCards) if (((App) c.getTag()).pkg.equals(focusPkg)) lastFocusedId = c.getId();
            focusPkg = null;
        }
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last != null) last.requestFocus();
        else if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    /** A 16:9 card with the app's banner; the name shows under it while focused. */
    private View tile(App app, boolean inDock) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(app);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(app.isBanner ? 0xFF1C1C1E : darken(app.color, 0.45f));
        card.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setImageDrawable(app.art);
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            img.setPadding(dp(22), dp(14), dp(22), dp(14));
        }

        // Focus ring and sheen, drawn above the art.
        View shine = Focus.ring(this, dp(10));
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView label = text(14, "sans-serif-medium", Color.WHITE);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        if (!inDock) box.addView(label, llp(-1, -2, 0, dp(10), 0, 0));

        card.setOnClickListener(v -> launch(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)));
        card.setLongClickable(true);
        card.setOnLongClickListener(v -> { showTileMenu(v, app, inDock); return true; });
        card.setOnFocusChangeListener((v, has) -> {
            Focus.animate(v, has, shine);
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has) return;
            lastFocusedId = v.getId();
            if (inDock) {
                showHero(app);
                heroText.animate().alpha(1f).setDuration(200).start();
                animateGlow(app.color);
                fadeGlow(1f);
                scroll.smoothScrollTo(0, 0);
            } else {
                fadeGlow(0.35f);
                leaveHero();
                scrollToRow(box);
            }
        });

        (inDock ? dockCards : gridCards).add(card);
        return box;
    }

    // ---------------------------------------------------------------- recently watched

    /** Rebuilds the Recently watched row when the history has changed since the last build. */
    private void buildRecents() {
        List<WatchHistory.Item> items = new ArrayList<>();
        StringBuilder sig = new StringBuilder();
        for (WatchHistory.Item it : WatchHistory.load(this)) {
            if (hidden.contains(it.pkg) || !isInstalled(it.pkg)) continue;
            items.add(it);
            sig.append(it.key()).append(WatchHistory.hasArt(this, it) ? "+" : "-")
                    .append(it.artChecked ? "t" : "").append('\n');
        }
        history = items;
        fetchMissingArt(items);
        if (sig.toString().equals(recentsSig)) return;
        recentsSig = sig.toString();

        int focusAt = -1; // the tile that had focus, so it keeps it after the rebuild
        for (int i = 0; i < recentCards.size(); i++) if (recentCards.get(i).getId() == lastFocusedId) focusAt = i;
        boolean focused = focusAt >= 0 && recentCards.get(focusAt).hasFocus();
        recentsRow.removeAllViews();
        recentCards.clear();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        // 4 across: big enough for the picture and the show's title over it.
        int tileW = (screenW - 2 * dp(68) - 3 * dp(20)) / 4;
        for (int i = 0; i < items.size(); i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(tileW, -2);
            p.setMargins(i == 0 ? 0 : dp(10), 0, i == items.size() - 1 ? 0 : dp(10), 0);
            recentsRow.addView(recentTile(items.get(i)), p);
        }
        recentsSection.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        if (focusAt >= 0) {
            View keep = recentCards.isEmpty() ? null : recentCards.get(Math.min(focusAt, recentCards.size() - 1));
            lastFocusedId = keep == null ? View.NO_ID : keep.getId();
            if (focused && keep != null) keep.requestFocus();
        }
        if (!focused) recentsScroll.scrollTo(0, 0);
        wireFocus();
    }

    /** A 16:9 card with the show's artwork (or the app's banner), title and subtitle underneath. */
    private View recentTile(WatchHistory.Item it) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setLongClickable(true);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        card.setBackgroundColor(0xFF1C1C1E);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap art = WatchHistory.loadArt(this, it, 640);
        if (art != null) img.setImageBitmap(art);
        else {
            // No picture (the app sent none and TMDB couldn't tell which show): the app's banner,
            // smaller and raised, leaving room for the episode title below it.
            img.setImageDrawable(appBanner(it.pkg));
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setPadding(dp(40), dp(14), dp(40), dp(62));
        }
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));

        if (art != null) {
            // Which app it opens in: a small app icon in the corner of the show's artwork.
            ImageView badge = new ImageView(this);
            badge.setImageDrawable(appIcon(it.pkg));
            badge.setScaleType(ImageView.ScaleType.CENTER_CROP);
            badge.setClipToOutline(true);
            badge.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(6));
                }
            });
            badge.setElevation(dp(2));
            card.addView(badge, lp(dp(26), dp(26), Gravity.TOP | Gravity.START, dp(8), dp(8), 0, 0));
        }
        // Over the bottom of the picture, on a dark fade: the episode's title in white (a movie has
        // none, so its picture stays clear). Unknown show (the app gave only the episode): the same.
        boolean episode = !it.movie();
        if (episode) {
        View fade = new View(this);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x73000000, 0x00000000}));
        card.addView(fade, lp(-1, -1, Gravity.BOTTOM, 0, 0, 0, 0));
        TextView ep = text(14, "sans-serif-medium", Color.WHITE);
        ep.setText(it.title);
        ep.setMaxLines(2);
        ep.setEllipsize(android.text.TextUtils.TruncateAt.END);
        ep.setShadowLayer(dp(6), 0, 0, 0xCC000000);
        card.addView(ep, lp(-1, -2, Gravity.BOTTOM, dp(12), 0, dp(12), dp(12)));
        }

        if (it.duration > 0) {
            float f = Math.max(0f, Math.min(1f, it.position / (float) it.duration));
            if (f > 0.02f && f < 0.97f) {
                // How far in: a thin white bar along the bottom edge of the picture.
                LinearLayout bar = new LinearLayout(this);
                bar.setBackgroundColor(0x59FFFFFF);
                View fill = new View(this);
                fill.setBackgroundColor(Color.WHITE);
                bar.addView(fill, new LinearLayout.LayoutParams(0, -1, f));
                bar.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1f - f));
                card.addView(bar, lp(-1, dp(4), Gravity.BOTTOM, 0, 0, 0, 0));
            }
        }

        View shine = Focus.ring(this, dp(10));
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));
        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        // Under the picture: the show's title (a movie: its title), then the app (the "channel").
        // Unknown show: just the app, rather than guess a show name.
        if (!it.unknownShow()) {
            TextView name = text(16, "sans-serif-medium", Color.WHITE);
            name.setText(episode ? it.subtitle : it.title);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            box.addView(name, llp(-1, -2, dp(2), dp(10), dp(2), 0));
        }
        TextView channel = text(it.unknownShow() ? 16 : 13, it.unknownShow() ? "sans-serif-medium" : "sans-serif",
                it.unknownShow() ? Color.WHITE : 0xB3FFFFFF);
        channel.setText(appLabel(it.pkg));
        channel.setSingleLine(true);
        channel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(channel, llp(-1, -2, dp(2), it.unknownShow() ? dp(10) : dp(2), dp(2), 0));

        card.setOnClickListener(v -> openRecent(it));
        card.setOnLongClickListener(v -> { showRecentMenu(v, it); return true; });
        card.setOnFocusChangeListener((v, has) -> {
            Focus.animate(v, has, shine);
            if (!has) return;
            lastFocusedId = v.getId();
            fadeGlow(0.35f);
            leaveHero();
            scrollToRecents();
        });
        recentCards.add(card);
        return box;
    }

    /** Back into the show if its app is still running; otherwise open the app (most resume there). */
    private void openRecent(WatchHistory.Item it) {
        PendingIntent pi = WatchListener.liveActivities.get(it.key());
        if (pi != null) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                    o.setPendingIntentBackgroundActivityStartMode(
                            android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    pi.send(this, 0, null, null, null, null, o.toBundle());
                } else {
                    pi.send();
                }
                return;
            } catch (PendingIntent.CanceledException e) {
                WatchListener.liveActivities.remove(it.key());
            }
        }
        openPackage(it.pkg);
    }

    private void showRecentMenu(View card, WatchHistory.Item it) {
        String app = appLabel(it.pkg);
        List<TextView> rows = new ArrayList<>();
        rows.add(inputRow("Open " + app, () -> openPackage(it.pkg)));
        Intent inApp = new Intent(Intent.ACTION_SEARCH).setPackage(it.pkg)
                .putExtra(android.app.SearchManager.QUERY, it.title);
        if (!getPackageManager().queryIntentActivities(inApp, 0).isEmpty()) {
            rows.add(inputRow("Search in " + app, () -> launch(inApp)));
        }
        if (it.unknownShow() || it.showChosen) {
            // The app didn't say which show this is (Peacock): pick it in Search, once.
            rows.add(inputRow(it.showChosen ? "Change the show" : "Choose the show", () -> {
                pickingFor = it.key();
                try {
                    startActivityForResult(new Intent(this, SearchActivity.class)
                            .putExtra(SearchActivity.EXTRA_PICK_SHOW_FOR, it.title), PICK_SHOW);
                } catch (RuntimeException ignored) { }
            }));
        }
        rows.add(inputRow("Find where to watch", () -> launch(new Intent(this, SearchActivity.class)
                .putExtra(SearchActivity.EXTRA_QUERY, it.subtitle.isEmpty() ? it.title : it.subtitle))));
        rows.add(inputRow("Remove from list", () -> {
            WatchHistory.remove(this, it);
            buildRecents();
            if (!recentCards.isEmpty()) recentCards.get(0).requestFocus();
            else if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        }));
        showPopup(card, rows);
    }

    static final int PICK_SHOW = 7;
    private String pickingFor; // key of the Recently watched item whose show is being picked

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_SHOW || result != RESULT_OK || data == null || pickingFor == null) return;
        String name = data.getStringExtra(SearchActivity.RESULT_SHOW_NAME);
        int id = data.getIntExtra(SearchActivity.RESULT_SHOW_ID, 0);
        for (WatchHistory.Item it : WatchHistory.load(this)) {
            if (!it.key().equals(pickingFor)) continue;
            WatchHistory.addChosenShow(this, it.pkg, id, name);
            break;
        }
        WatchHistory.setShow(this, pickingFor, name, true);
        artTried.remove(pickingFor);
        pickingFor = null;
        recentsSig = null;
        buildRecents(); // onResume runs it too; either way the new show's episode still is fetched
    }

    private void scrollToRecents() {
        // Same as the first grid row: slide the dock band up to the top edge.
        View band = (View) dockRow.getParent();
        int target = hero.getTop() + band.getTop() + dockRow.getTop() - dp(22);
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
    }

    private Drawable appBanner(String pkg) {
        PackageManager pm = getPackageManager();
        try {
            Drawable d = pm.getApplicationBanner(pkg);
            return d != null ? d : pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private Drawable appIcon(String pkg) {
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * The tile's picture from TMDB: for an episode (show in the subtitle, episode in the title), a
     * still from that episode, else the show's backdrop; for a movie, the movie's backdrop. Used in
     * place of the app's own artwork, which is usually the show's poster or logo. Only exact title
     * matches count, so a YouTube video never picks up some film's picture. Asked once per item.
     */
    private void fetchMissingArt(List<WatchHistory.Item> items) {
        if (!tmdb.hasKey()) return;
        for (WatchHistory.Item it : items) {
            if (it.artChecked || !artTried.add(it.key())) continue;
            bg.execute(() -> {
                Bitmap bm = null;
                boolean asked = false;
                try {
                    String show = it.subtitle.trim(), name = it.title.trim();
                    if (show.isEmpty() && it.unknownShow()) {
                        // The app didn't say which show: try the shows picked for this app before.
                        for (String[] cs : WatchHistory.chosenShows(this)) {
                            if (!cs[0].equals(it.pkg)) continue;
                            String still = tmdb.episodeStill(Integer.parseInt(cs[1]), name);
                            if (still == null) continue;
                            WatchHistory.setShow(this, it.key(), cs[2], true);
                            bm = tmdb.image(still, "original");
                            break;
                        }
                    }
                    if (bm == null && !show.isEmpty()) {
                        Tmdb.Result tv = exactMatch(tmdb.search(show), show, "tv");
                        if (tv != null) {
                            String still = tmdb.episodeStill(tv.id, name);
                            if (still != null) bm = tmdb.image(still, "original");
                            else if (tv.backdrop != null) bm = tmdb.image(tv.backdrop, "w1280");
                        }
                    }
                    // A movie's own backdrop, only for movie-length items with no show name: a short
                    // item is an episode whose show the app didn't give (Peacock), and its episode
                    // title would match some unrelated film.
                    if (bm == null && it.movie()) {
                        Tmdb.Result r = exactMatch(tmdb.search(name), name, "movie");
                        if (r != null && r.backdrop != null) bm = tmdb.image(r.backdrop, "w1280");
                    }
                    // Unknown show: drop any picture an earlier version matched wrongly; the app's own
                    // artwork comes back the next time it plays, otherwise the tile shows the app.
                    if (bm == null && it.unknownShow()) WatchHistory.deleteArt(this, it);
                    asked = true;
                } catch (Exception ignored) { } // offline: try again next time
                if (asked) WatchHistory.markArtChecked(this, it.key());
                if (bm == null) {
                    if (asked) runOnUiThread(() -> { if (!isDestroyed()) buildRecents(); });
                    return;
                }
                WatchHistory.saveArt(this, it, bm);
                runOnUiThread(() -> { if (!isDestroyed()) { buildRecents(); refreshHero(); } });
            });
        }
    }

    /** The first movie or show (of {@code type}, if given) whose title is exactly {@code q}. */
    private static Tmdb.Result exactMatch(List<Tmdb.Result> rs, String q, String type) {
        String want = SearchActivity.squash(q);
        for (Tmdb.Result r : rs) {
            if ("person".equals(r.type) || (type != null && !type.equals(r.type))) continue;
            if (SearchActivity.squash(r.title).equals(want)) return r;
        }
        return null;
    }

    // ---------------------------------------------------------------- hero

    /** Text above the dock for a focused dock app; its show's artwork fades in after a short pause. */
    private void showHero(App app) {
        WatchHistory.Item last = null;
        for (WatchHistory.Item it : history) if (it.pkg.equals(app.pkg)) { last = it; break; }
        String name = app.label.toUpperCase(Locale.getDefault());
        if (last == null) {
            kicker.setText(name);
            title.setVisibility(View.GONE);
            heroSub.setVisibility(View.GONE);
        } else {
            kicker.setText("CONTINUE  ·  " + name);
            title.setText(last.title);
            title.setVisibility(View.VISIBLE);
            heroSub.setText(last.subtitle);
            heroSub.setVisibility(last.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        }
        WatchHistory.Item withArt = last != null && WatchHistory.hasArt(this, last) ? last : null;
        String key = withArt == null ? null : withArt.key();
        if (heroArtTask != null) handler.removeCallbacks(heroArtTask);
        if (java.util.Objects.equals(key, heroKey)) return;
        // Wait until focus settles, so moving across the dock doesn't flash every show's artwork.
        heroArtTask = () -> setHeroArt(withArt);
        handler.postDelayed(heroArtTask, withArt == null ? 0 : 400);
    }

    /** Re-reads the hero for the focused dock app, if a dock app has focus. */
    private void refreshHero() {
        View f = getCurrentFocus();
        if (f != null && dockCards.contains(f)) showHero((App) f.getTag());
    }

    /** Focus left the hero (down into the rows): hide the title and the show artwork. */
    private void leaveHero() {
        heroText.animate().alpha(0f).setDuration(200).start();
        if (heroArtTask != null) handler.removeCallbacks(heroArtTask);
        setHeroArt(null);
    }

    private void setHeroArt(WatchHistory.Item it) {
        String key = it == null ? null : it.key();
        heroKey = key;
        if (it == null) {
            heroArt.animate().alpha(0f).setDuration(300).start();
            showCaption(true); // the wallpaper is back in view
            return;
        }
        int w = getResources().getDisplayMetrics().widthPixels;
        bg.execute(() -> {
            Bitmap bm = WatchHistory.loadArt(this, it, w / 2);
            runOnUiThread(() -> {
                if (isDestroyed() || !java.util.Objects.equals(key, heroKey) || bm == null) return;
                heroArt.setImageBitmap(bm);
                heroArt.animate().alpha(1f).setDuration(400).start();
                showCaption(false); // the caption describes the wallpaper, now hidden behind the show
            });
        });
    }

    private String appLabel(String pkg) {
        PackageManager pm = getPackageManager();
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** Explicit D-pad targets: one row per press, ends of rows and of the top bar don't wrap. */
    private void wireFocus() {
        int d = dockCards.size(), g = gridCards.size(), rc = recentCards.size();
        for (int i = 0; i < pills.size(); i++) {
            View p = pills.get(i);
            p.setNextFocusLeftId(i == 0 ? p.getId() : pills.get(i - 1).getId());
            p.setNextFocusRightId(i == pills.size() - 1 ? p.getId() : pills.get(i + 1).getId());
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(d > 0 ? dockCards.get(Math.min(d - 1, d / 2)).getId() : p.getId());
        }
        int topId = searchPill.getId(); // Up from the dock lands on Search, not the picture buttons
        for (int i = 0; i < d; i++) {
            View c = dockCards.get(i);
            c.setNextFocusUpId(topId);
            c.setNextFocusLeftId(i == 0 ? c.getId() : dockCards.get(i - 1).getId());
            c.setNextFocusRightId(i == d - 1 ? c.getId() : dockCards.get(i + 1).getId());
            c.setNextFocusDownId(rc > 0 ? recentCards.get(Math.min(i, rc - 1)).getId()
                    : g > 0 ? gridCards.get(Math.min(i, g - 1)).getId() : c.getId());
        }
        for (int i = 0; i < rc; i++) {
            View c = recentCards.get(i);
            c.setNextFocusUpId(d > 0 ? dockCards.get(Math.min(i, d - 1)).getId() : topId);
            c.setNextFocusDownId(g > 0 ? gridCards.get(Math.min(Math.min(i, cols - 1), g - 1)).getId() : c.getId());
            c.setNextFocusLeftId(i == 0 ? c.getId() : recentCards.get(i - 1).getId());
            c.setNextFocusRightId(i == rc - 1 ? c.getId() : recentCards.get(i + 1).getId());
        }
        int lastRow = (g - 1) / cols;
        for (int j = 0; j < g; j++) {
            View c = gridCards.get(j);
            int r = j / cols, col = j % cols;
            c.setNextFocusUpId(r == 0
                    ? (rc > 0 ? recentCards.get(Math.min(col, rc - 1)).getId()
                    : d > 0 ? dockCards.get(Math.min(col, d - 1)).getId() : topId)
                    : gridCards.get(j - cols).getId());
            c.setNextFocusDownId(j + cols < g ? gridCards.get(j + cols).getId()
                    : r < lastRow ? gridCards.get(g - 1).getId() : c.getId());
            c.setNextFocusLeftId(col == 0 ? c.getId() : gridCards.get(j - 1).getId());
            c.setNextFocusRightId(col == cols - 1 || j == g - 1 ? c.getId() : gridCards.get(j + 1).getId());
        }
    }

    private void scrollToRow(View tileBox) {
        View row = (View) tileBox.getParent();
        int y = grid.getTop() + row.getTop();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // First grid row: slide the dock band up to the top edge; later rows centre on screen.
        View band = (View) dockRow.getParent();
        int target = grid.indexOfChild(row) == 0 && recentCards.isEmpty()
                ? hero.getTop() + band.getTop() + dockRow.getTop() - dp(22)
                : y - (int) (screenH * 0.42f);
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
    }

    // ---------------------------------------------------------------- inputs

    private void showInputs(View anchor) {
        List<TextView> rows = new ArrayList<>();

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (t.isPassthroughInput() && !t.isHidden(this)) inputs.add(t);
        }
        Collections.sort(inputs, (x, y) -> inputLabel(x).compareToIgnoreCase(inputLabel(y)));
        for (TvInputInfo t : inputs) {
            rows.add(inputRow(inputLabel(t), () -> {
                Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(t.getId()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!launch(i)) openPackage(inputPickerPkg);
            }));
        }
        if (isInstalled(liveTvPkg)) rows.add(inputRow("Live TV", () -> openPackage(liveTvPkg)));
        if (inputs.isEmpty() && isInstalled(inputPickerPkg)) rows.add(inputRow("All sources…", () -> openPackage(inputPickerPkg)));

        if (rows.isEmpty()) rows.add(inputRow("Settings", () -> launch(new Intent(Settings.ACTION_SETTINGS))));
        showPopup(anchor, rows);
    }

    /** Long-press OK on a tile: add an app to the dock, or move / remove a dock app. */
    private void showTileMenu(View card, App app, boolean inDock) {
        List<TextView> rows = new ArrayList<>();
        if (inDock) {
            int i = dockApps.indexOf(app.pkg);
            if (i > 0) rows.add(inputRow("Move left", () -> moveInDock(app.pkg, -1)));
            if (i >= 0 && i < dockApps.size() - 1) rows.add(inputRow("Move right", () -> moveInDock(app.pkg, 1)));
            rows.add(inputRow("Remove from dock", () -> {
                dockApps.remove(app.pkg);
                focusPkg = app.pkg; // it lands in the grid; keep focus on it
                saveDock();
            }));
        } else if (dockCards.size() < MAX_DOCK) {
            rows.add(inputRow("Add to dock", () -> {
                dockApps.remove(app.pkg);
                dockApps.add(app.pkg);
                focusPkg = app.pkg;
                saveDock();
            }));
        } else {
            TextView full = inputRow("Dock is full (" + MAX_DOCK + ")", () -> { });
            full.setAlpha(0.6f);
            rows.add(full);
        }
        // Uninstall: Android shows its own "Do you want to uninstall this app?" confirmation. Apps that
        // came with the TV can't be uninstalled, only disabled, so they get App info instead.
        if (isUserApp(app.pkg)) {
            rows.add(inputRow("Uninstall", () -> launch(new Intent(Intent.ACTION_DELETE,
                    android.net.Uri.fromParts("package", app.pkg, null)))));
        }
        rows.add(inputRow("App info", () -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", app.pkg, null)))));
        showPopup(card, rows);
    }

    /** Installed by the user (or the store), not part of the TV's system image. */
    private boolean isUserApp(String pkg) {
        try {
            int f = getPackageManager().getApplicationInfo(pkg, 0).flags;
            return (f & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void moveInDock(String pkg, int delta) {
        // Move past apps in the saved list that aren't installed, so each press visibly moves the tile.
        int i = dockApps.indexOf(pkg), j = i;
        do { j += delta; } while (j >= 0 && j < dockApps.size() && !isShownInDock(dockApps.get(j)));
        if (i < 0 || j < 0 || j >= dockApps.size()) return;
        dockApps.remove(i);
        dockApps.add(j, pkg);
        focusPkg = pkg;
        saveDock();
    }

    private boolean isShownInDock(String pkg) {
        for (View c : dockCards) if (((App) c.getTag()).pkg.equals(pkg)) return true;
        return false;
    }

    private void saveDock() {
        getPreferences(MODE_PRIVATE).edit().putString(PREF_DOCK, String.join(",", dockApps)).apply();
        buildApps();
    }

    /** A frosted dropdown of rows, each row's tag a Runnable run after the popup closes. */
    private void showPopup(View anchor, List<TextView> rows) {
        if (popup != null) popup.dismiss();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);

        PopupWindow pw = new PopupWindow(list, dp(240), -2, true);
        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(42)));
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { popup = null; anchor.requestFocus(); });
        popup = pw;
        // Below the anchor, or above it when there isn't room (the dock sits near the bottom edge).
        // Placed by hand: on TV, showAsDropDown clips at the screen edge instead of flipping.
        list.measure(View.MeasureSpec.makeMeasureSpec(dp(240), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = list.getMeasuredHeight();
        int[] at = new int[2];
        anchor.getLocationOnScreen(at); // includes the focus zoom
        int anchorH = Math.round(anchor.getHeight() * anchor.getScaleY());
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int y = at[1] + anchorH + dp(8);
        if (y + h > screenH - dp(12)) y = Math.max(dp(12), at[1] - h - dp(8));
        pw.showAtLocation(anchor, Gravity.TOP | Gravity.START, at[0], y);
        rows.get(0).requestFocus();
    }

    private String inputLabel(TvInputInfo t) {
        CharSequence c = t.loadCustomLabel(this);
        if (c == null || c.length() == 0) c = t.loadLabel(this);
        return String.valueOf(c);
    }

    private TextView inputRow(String label, Runnable action) {
        TextView r = text(15, "sans-serif", Color.WHITE);
        r.setText(label);
        r.setTag(action);
        r.setId(View.generateViewId());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), 0, dp(14), 0);
        r.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            r.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        return r;
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "GOOD NIGHT" : h < 12 ? "GOOD MORNING" : h < 17 ? "GOOD AFTERNOON" : "GOOD EVENING");
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        ampm.setText(is24 ? "" : (h < 12 ? "AM" : "PM"));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault()));
    }

    private void updateWifi() {
        // Online/offline comes from ConnectivityManager. WifiManager only gives the network name (and a
        // real network id) when Location is on, so a missing name must not be read as "Offline".
        String name = "Offline";
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkCapabilities nc = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc != null && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                boolean wifi = nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
                name = wifi ? "Wi-Fi" : "Ethernet";
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wifi && wm != null && wm.getConnectionInfo() != null) {
                    String s = wm.getConnectionInfo().getSSID();
                    if (s != null && !s.isEmpty() && !s.contains("unknown ssid")) name = s.replace("\"", "");
                }
            }
        } catch (SecurityException ignored) { }
        wifiPill.setTag(name);
        if (wifiPill.hasFocus()) setPillLabel(wifiPill, true);
    }

    // ---------------------------------------------------------------- glow

    private void setGlow(int color) {
        glowColor = color;
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0.5f, 0.78f);
        g.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.62f);
        g.setColors(new int[]{withAlpha(color, 0.5f), withAlpha(color, 0.14f), Color.TRANSPARENT});
        glow.setBackground(g);
    }

    private void animateGlow(int to) {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, to);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    private void fadeGlow(float alpha) {
        glow.animate().alpha(alpha).setDuration(300).start();
        // The grid sits over the photo's busiest middle part, so dim it while browsing apps.
        gridShade.animate().alpha(alpha < 1f ? 1f : 0f).setDuration(300).start();
        // From the dock, the rows below are just a hint: dimmed until focus moves down into them.
        float rows = alpha < 1f ? 1f : 0.45f;
        for (View v : new View[]{recentsSection, appsLabel, grid}) v.animate().alpha(rows).setDuration(300).start();
    }

    // ---------------------------------------------------------------- wallpaper

    private ImageView wallpaperLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    /** The pictures to rotate through and their captions (shared with the screensaver, ArtDream). */
    private void loadWallpapers() {
        Wallpapers w = Wallpapers.load(this);
        wallpapers = w.paths;
        captions.putAll(w.captions);
    }

    /** Decode the next picture off the main thread, then crossfade it in over the current one. */
    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        loadWallpapers(); // picks up pictures ArtDownloader added since
        if (wallpapers.length == 0) return;
        if (wallIndex < 0) wallIndex = getPreferences(MODE_PRIVATE).getInt("wallpaper", -1);
        else {
            wallHistory.push(wallIndex);
            while (wallHistory.size() > 50) wallHistory.removeLast();
        }
        // After stepping back, Next retraces those steps; otherwise the next picture in the shuffle.
        Integer fwd = wallForward.poll();
        int next = fwd != null && fwd < wallpapers.length ? fwd
                : Wallpapers.next(this, wallpapers.length, wallIndex); // shuffled, no repeats until all shown
        showWallpaperAt(next);
    }

    /** Back to the picture shown before this one (the Previous button). */
    private void prevWallpaper() {
        Integer prev = wallHistory.poll();
        if (prev == null || prev >= wallpapers.length) return;
        handler.removeCallbacks(rotateWallpaper);
        if (wallIndex >= 0) wallForward.push(wallIndex);
        showWallpaperAt(prev);
    }

    /** Fade in the picture buttons while focus is in the top bar, out when it leaves. */
    private void showPictureButtons(boolean show) {
        for (View v : new View[]{picPrev, picNext}) {
            if (v != null) v.animate().alpha(show ? 1f : 0f).setDuration(show ? 250 : 400).start();
        }
    }

    private void showWallpaperAt(int index) {
        wallIndex = index;
        getPreferences(MODE_PRIVATE).edit().putInt("wallpaper", wallIndex).apply();
        wallChangedAt = SystemClock.elapsedRealtime();
        String name = wallpapers[wallIndex];
        new Thread(() -> {
            Bitmap bm = null;
            try (java.io.InputStream in = Wallpapers.open(this, name)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                bm = BitmapFactory.decodeStream(in, null, o);
            } catch (java.io.IOException ignored) { }
            Bitmap result = bm;
            float dim = result == null ? 0f : dimFor(result);
            runOnUiThread(() -> showWallpaper(result, name, dim));
        }).start();
        wallDelay = WALLPAPER_MIN_MS + (long) (Math.random() * (WALLPAPER_MAX_MS - WALLPAPER_MIN_MS));
        scheduleWallpaper(wallDelay);
    }

    /**
     * How much to darken a picture so the clock, buttons and dock stay readable: none for most
     * pictures, up to 25% for very bright ones (frescoes, white marble in daylight). Measured where the text sits: the top band and the bottom band.
     */
    private static float dimFor(Bitmap bm) {
        Bitmap s = Bitmap.createScaledBitmap(bm, 32, 18, true);
        double sum = 0;
        int n = 0;
        for (int y = 0; y < 18; y++) {
            if (y > 4 && y < 10) continue; // the middle carries no text
            for (int x = 0; x < 32; x++) {
                int p = s.getPixel(x, y);
                sum += (0.2126 * Color.red(p) + 0.7152 * Color.green(p) + 0.0722 * Color.blue(p)) / 255.0;
                n++;
            }
        }
        if (s != bm) s.recycle();
        double lum = sum / Math.max(1, n);
        return (float) Math.max(0, Math.min(0.25, (lum - 0.45) * 0.9)); // only very bright pictures, and lightly
    }

    /**
     * Shows the wallpaper's caption, unless a show's artwork is covering the wallpaper (heroKey set).
     * Goes by heroKey rather than the artwork's alpha, which is mid-fade when this is called.
     */
    private void showCaption(boolean show) {
        boolean has = captionTitle.getText().length() > 0;
        caption.animate().alpha(show && has && heroKey == null ? 1f : 0f).setDuration(300).start();
    }

    private void showWallpaper(Bitmap bm, String name, float dim) {
        if (bm == null || isDestroyed()) return;
        String[] c = captions.get(name);
        caption.animate().alpha(0f).setDuration(400).withEndAction(() -> {
            captionTitle.setText(c == null ? "" : c[0]);
            captionSub.setText(c == null ? "" : c[1] + "  ·  " + c[2]);
            showCaption(heroKey == null);
        }).start();
        wallDim.animate().alpha(dim).setDuration(firstWallpaper ? 600 : 2000).start();
        // Swap roles: the old front becomes the back, the new photo fades in on top.
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        wallFront.animate().alpha(1f).setDuration(firstWallpaper ? 600 : 2000).withEndAction(() -> {
            wallBack.setImageDrawable(null); // release the previous photo
        }).start();
        firstWallpaper = false;
    }

    private boolean firstWallpaper = true;

    private void scheduleWallpaper(long delay) {
        handler.removeCallbacks(rotateWallpaper);
        handler.postDelayed(rotateWallpaper, Math.max(1000, delay));
    }

    /** Average the banner's vivid pixels, then push saturation so the glow reads as a colour. */
    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        colorCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        View header = hero.getChildAt(0);
        header.setAlpha(0f);
        header.setTranslationY(-dp(12));
        header.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
        heroText.setAlpha(0f);
        heroText.animate().alpha(1f).setDuration(500).setStartDelay(200).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(24));
            t.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(450).setStartDelay(160 + i * 60L).start();
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean launch(Intent i) {
        try {
            if (!i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) launch(i);
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int withAlpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Menu key on the remote opens the Inputs dropdown.
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP && inputsPill != null) {
            showInputs(inputsPill);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** 16:9 container. */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    /** ScrollView that never auto-scrolls to the focused child; the activity scrolls it by row. */
    static final class HomeScrollView extends android.widget.ScrollView {
        HomeScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
    }
}
