package com.mihir.tvhome;

import android.app.Activity;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Search movies, shows and people (TMDB), then see which of the TV's apps stream a title and open
 * it there. Opened from the home screen's Search pill, or with a query from Recently watched.
 */
public class SearchActivity extends Activity {
    static final String EXTRA_QUERY = "query";
    // Pick mode ("Choose the show" on a Recently watched tile): picking a TV show returns it to the
    // home screen instead of opening where to watch. The extra is the episode title, for the prompt.
    static final String EXTRA_PICK_SHOW_FOR = "pick_show_for";
    static final String RESULT_SHOW_ID = "show_id", RESULT_SHOW_NAME = "show_name";
    private String pickFor;
    static final int COLS = 6;           // poster size in the result rows: 6 across the screen

    /** TMDB/JustWatch provider names (squashed) → Android TV package. */
    static final Map<String, String> PROVIDERS = new HashMap<>();
    static {
        String[][] p = {
                {"netflix", "com.netflix.ninja"}, {"netflixbasicwithads", "com.netflix.ninja"},
                {"amazonprimevideo", "com.amazon.amazonvideo.livingroom"},
                {"amazonprimevideowithads", "com.amazon.amazonvideo.livingroom"},
                {"amazonvideo", "com.amazon.amazonvideo.livingroom"},
                {"hulu", "com.hulu.livingroomplus"},
                {"peacock", "com.peacocktv.peacockandroid"}, {"peacockpremium", "com.peacocktv.peacockandroid"},
                {"peacockpremiumplus", "com.peacocktv.peacockandroid"},
                {"paramountplus", "com.cbs.ott"}, {"paramountpluspremium", "com.cbs.ott"},
                {"paramountplusessential", "com.cbs.ott"}, {"paramountpluswithshowtime", "com.cbs.ott"},
                {"youtube", "com.google.android.youtube.tv"}, {"youtubepremium", "com.google.android.youtube.tv"},
                {"youtubetv", "com.google.android.youtube.tvunplugged"},
                {"appletv", "com.apple.atve.sony.appletv"}, {"appletvplus", "com.apple.atve.sony.appletv"},
                {"appletvstore", "com.apple.atve.sony.appletv"},
                {"tubitv", "com.tubitv"}, {"tubi", "com.tubitv"},
                {"therokuchannel", "com.roku.web.trc"},
                {"slingtv", "com.sling"}, {"sling", "com.sling"},
                {"fubotv", "com.fubo.firetv.screen"},
                {"plex", "com.plexapp.android"}, {"plexchannel", "com.plexapp.android"},
                {"fandangoathome", "air.com.vudu.air.DownloaderTablet"},
                {"fandangoathomefree", "air.com.vudu.air.DownloaderTablet"},
                {"vudu", "air.com.vudu.air.DownloaderTablet"},
                {"britbox", "com.britbox.tv"},
                {"discoveryplus", "com.discovery.discoveryplus.mobile"},
                {"angelstudios", "com.angel.tv"},
                {"greatamericanpureflix", "com.pureflix"}, {"pureflix", "com.pureflix"},
                {"foxone", "com.fox.foxone"},
                {"dailywireplus", "com.dailywire.androidtv"},
                {"disneyplus", "com.disney.disneyplus"},
                {"max", "com.wbd.stream"}, {"hbomax", "com.wbd.stream"},
                {"plutotv", "tv.pluto.android"},
                {"crunchyroll", "com.crunchyroll.crunchyroid"},
        };
        for (String[] e : p) PROVIDERS.put(e[0], e[1]);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService api = Executors.newSingleThreadExecutor();
    private final ExecutorService imgs = Executors.newFixedThreadPool(4);
    private Tmdb tmdb;
    private int generation;

    private LinearLayout main;
    private List<Tmdb.Result> results = new ArrayList<>();
    private String head = "";
    private boolean credits;    // showing a person's movies and shows
    private boolean typingRows; // rendered for while the keyboard is up (compact top result)
    private boolean wantResultsFocus; // Search pressed: move to the results as soon as there are some
    private EditText field;
    private TextView status, heading;
    private ScrollView scroll;
    private LinearLayout grid;
    private FrameLayout detail;
    private final List<View> cards = new ArrayList<>();
    private View lastCard;
    private final Runnable runSearch = this::search;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        tmdb = new Tmdb(this);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(buildUi());
        pickFor = getIntent().getStringExtra(EXTRA_PICK_SHOW_FOR);
        if (pickFor != null) field.setHint("Type the show's name");
        String q = getIntent().getStringExtra(EXTRA_QUERY);
        if (!tmdb.hasKey()) {
            status.setText("Search needs a free TMDB API key. Put it in launcher/tmdb-api-key.txt on your "
                    + "computer, then run launcher/build-and-install.sh.");
        } else if (q != null && !q.isEmpty()) {
            field.setText(q);
            field.setSelection(q.length());
            search();
        } else {
            if (pickFor != null) status.setText(pickPrompt());
            field.requestFocus();
            ui.postDelayed(() -> ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT), 300);
        }
    }

    private String pickPrompt() {
        return "Which show is “" + pickFor + "” from? Search for it, then pick it.";
    }

    /** Pick mode: OK on a show sends it back to the home screen. Movies and people don't count. */
    private boolean pick(Tmdb.Result r) {
        if (pickFor == null) return false;
        if ("tv".equals(r.type)) {
            setResult(RESULT_OK, new Intent().putExtra(RESULT_SHOW_ID, r.id).putExtra(RESULT_SHOW_NAME, r.title));
            finish();
        } else if (!"person".equals(r.type)) {
            status.setText("That's a movie. " + pickPrompt());
        }
        return !"person".equals(r.type);
    }

    @Override
    protected void onDestroy() {
        api.shutdownNow();
        imgs.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (detail.getVisibility() == View.VISIBLE) { closeDetail(); return; }
        if (heading.getTag() != null) {
            heading.setTag(null); // back from a person's credits to the search results
            search();
            return;
        }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1B2333, 0xFF0D1018, 0xFF07080C}));

        LinearLayout col = main = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        field = new EditText(this);
        field.setSingleLine(true);
        field.setHint("Movies, shows, actors…");
        field.setHintTextColor(0x80FFFFFF);
        field.setTextColor(Color.WHITE);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        // No microphone key or "hold the mic to search" hint: voice search is disabled on this TV.
        // Google's keyboard reads both spellings of this opt-out.
        field.setPrivateImeOptions("nm,com.google.android.inputmethod.latin.noMicrophoneKey");
        field.setPadding(dp(20), 0, dp(20), 0);
        GradientDrawable fbg = new GradientDrawable();
        fbg.setCornerRadius(dp(22));
        fbg.setColor(0x26FFFFFF);
        fbg.setStroke(dp(1), 0x33FFFFFF);
        field.setBackground(fbg);
        field.setOnFocusChangeListener((v, has) -> {
            fbg.setStroke(dp(has ? 2 : 1), has ? 0xE6FFFFFF : 0x33FFFFFF);
            // Keyboard up (typing): a smaller top result above it. Focus moved down: full size.
            ui.post(() -> {
                if (field.hasFocus() == typingRows || results.isEmpty()) return;
                render();
                if (!field.hasFocus() && !cards.isEmpty() && detail.getVisibility() != View.VISIBLE) cards.get(0).requestFocus();
            });
        });
        field.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE
                    || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                ui.removeCallbacks(runSearch);
                heading.setTag(null);
                search();
                hideKeyboard();
                return true;
            }
            return false;
        });
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                ui.removeCallbacks(runSearch);
                heading.setTag(null);
                wantResultsFocus = false;
                ui.postDelayed(runSearch, 600); // search as you type, after a short pause
            }
        });
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(dp(520), dp(46));
        fp.setMargins(dp(56), dp(32), dp(56), 0);
        col.addView(field, fp);

        status = text(14, "sans-serif", 0xB3FFFFFF);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(dp(60), dp(10), dp(56), 0);
        col.addView(status, sp);

        scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(0, dp(8), 0, dp(40));
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setClipChildren(false);
        inner.setClipToPadding(false);
        heading = text(13, "sans-serif-medium", 0xB3FFFFFF);
        heading.setLetterSpacing(0.22f);
        inner.addView(heading, llp(-2, -2, dp(56), 0, dp(56), 0));
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        inner.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(inner, new FrameLayout.LayoutParams(-1, -2));
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView credit = text(11, "sans-serif", 0x80FFFFFF);
        credit.setText("Data from TMDB · Streaming availability from JustWatch");
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.setMargins(dp(60), 0, 0, dp(14));
        col.addView(credit, cp);

        detail = new FrameLayout(this);
        detail.setVisibility(View.GONE);
        detail.setClickable(true);
        detail.setFocusable(true); // holds focus while where-to-watch loads
        detail.setClipChildren(false); // let focused buttons zoom past the info column
        root.addView(detail, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    // ---------------------------------------------------------------- search

    private void search() {
        String q = field.getText().toString().trim();
        int gen = ++generation;
        if (!tmdb.hasKey()) return;
        if (q.isEmpty()) { show(new ArrayList<>(), "", ""); return; }
        status.setText("Searching…");
        api.execute(() -> {
            try {
                List<Tmdb.Result> r = tmdb.search(q);
                ui.post(() -> { if (gen == generation) show(r, "RESULTS FOR “" + q.toUpperCase(Locale.getDefault()) + "”",
                        r.isEmpty() ? "Nothing found for “" + q + "”." : ""); });
            } catch (Exception e) {
                ui.post(() -> { if (gen == generation) status.setText(message(e)); });
            }
        });
    }

    private void showCredits(Tmdb.Result person) {
        int gen = ++generation;
        status.setText("Loading " + person.title + "…");
        api.execute(() -> {
            try {
                List<Tmdb.Result> r = tmdb.credits(person.id);
                ui.post(() -> {
                    if (gen != generation) return;
                    show(r, person.title.toUpperCase(Locale.getDefault()), "");
                    credits = true;
                    render();
                    heading.setTag(person); // Back returns to the search results
                    if (!cards.isEmpty()) cards.get(0).requestFocus();
                });
            } catch (Exception e) {
                ui.post(() -> { if (gen == generation) status.setText(message(e)); });
            }
        });
    }

    private void show(List<Tmdb.Result> r, String head, String msg) {
        results = r;
        this.head = head;
        if (pickFor != null && msg.isEmpty()) msg = pickPrompt();
        credits = false;
        status.setText(msg);
        render();
    }

    /**
     * Lays the results out, most obvious match first: the top match large, with its backdrop and
     * description, then rows by kind: Shows, Movies, People (in the order they first appear, so the
     * likeliest kind is first). While typing, the top match is drawn smaller so it stays above the
     * on-screen keyboard; the rows are under the keyboard until it closes.
     */
    private void render() {
        typingRows = field.hasFocus();
        // Removing a focused card hands focus back to the search field; put it back on the results.
        boolean refocus = grid.hasFocus() || (wantResultsFocus && !typingRows);
        heading.setText(head);
        grid.removeAllViews();
        cards.clear();
        if (results.isEmpty()) return;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        List<Tmdb.Result> rest = new ArrayList<>(results);
        Tmdb.Result top = credits ? null : topResult(rest, field.getText().toString());
        if (top != null) rest.remove(top);
        int w = (screenW - 2 * dp(56) - (COLS - 1) * dp(16)) / COLS;
        if (top != null) addTop(top, screenW, typingRows);
        Map<String, List<Tmdb.Result>> byType = new java.util.LinkedHashMap<>();
        for (Tmdb.Result x : rest) {
            List<Tmdb.Result> l = byType.get(x.type);
            if (l == null) byType.put(x.type, l = new ArrayList<>());
            l.add(x);
        }
        for (Map.Entry<String, List<Tmdb.Result>> e : byType.entrySet()) {
            String label = "movie".equals(e.getKey()) ? "MOVIES" : "tv".equals(e.getKey()) ? "SHOWS" : "PEOPLE";
            if (top != null && byType.size() == 1) label = "MORE RESULTS";
            addRow(label, e.getValue(), w);
        }
        if (top != null && cards.size() > 1) {
            // Down from the top result goes to the start of the next row, not whatever is below it.
            for (View c : cards.subList(0, 2)) if (c.getId() == View.NO_ID) c.setId(View.generateViewId());
            cards.get(0).setNextFocusDownId(cards.get(1).getId());
            cards.get(1).setNextFocusUpId(cards.get(0).getId());
        }
        scroll.scrollTo(0, 0);
        if ((refocus || wantResultsFocus) && !cards.isEmpty()) {
            wantResultsFocus = false;
            cards.get(0).requestFocus();
        }
    }

    /**
     * The most obvious match: among the first few movies and shows, one whose title is exactly what
     * was typed (the most popular of those, so the original beats its spin-offs), else the first
     * movie or show. Null when a person comes first ("tom hanks") or there's only one result.
     */
    private static Tmdb.Result topResult(List<Tmdb.Result> rs, String query) {
        if (rs.size() < 2 || "person".equals(rs.get(0).type)) return null;
        String q = squash(query);
        Tmdb.Result best = null;
        for (int i = 0; i < Math.min(8, rs.size()); i++) {
            Tmdb.Result r = rs.get(i);
            if ("person".equals(r.type)) continue;
            String t = squash(r.title);
            boolean exact = t.equals(q) || t.equals("the" + q) || ("the" + t).equals(q);
            if (exact && (best == null || r.popularity > best.popularity)) best = r;
        }
        return best != null ? best : rs.get(0);
    }

    /** A labelled row of poster cards that scrolls sideways. */
    private void addRow(String label, List<Tmdb.Result> items, int w) {
        HorizontalRow row = new HorizontalRow(label);
        for (int i = 0; i < items.size(); i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, -2);
            p.setMargins(i == 0 ? 0 : dp(16), 0, 0, 0);
            row.items.addView(card(items.get(i)), p);
        }
    }

    /**
     * The best match, big: its backdrop on the left, title, year and description beside it.
     * Compact (keyboard up): a smaller card, no label and a shorter description, all above the keyboard.
     */
    private void addTop(Tmdb.Result r, int screenW, boolean compact) {
        HorizontalRow row = new HorizontalRow(compact ? null : "TOP RESULT");
        int cardW = Math.round(screenW * (compact ? 0.25f : 0.4f));
        LinearLayout box = new LinearLayout(this);
        box.setClipChildren(false);
        box.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout frame = new MainActivity.AspectFrame(this);
        frame.setFocusable(true);
        frame.setClickable(true);
        frame.setClipToOutline(true);
        frame.setOutlineProvider(rounded(dp(12)));
        frame.setBackgroundColor(accent(r.title));
        TextView placeholder = text(24, "sans-serif-light", Color.WHITE);
        placeholder.setText(r.title);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setPadding(dp(16), 0, dp(16), 0);
        frame.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(img, new FrameLayout.LayoutParams(-1, -1));
        loadInto(img, r.backdrop != null ? r.backdrop : r.image, "w1280");
        View ring = Focus.ring(this, dp(12));
        frame.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        box.addView(frame, new LinearLayout.LayoutParams(cardW, -2));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(compact ? 26 : 30, "sans-serif-light", Color.WHITE);
        t.setText(r.title);
        t.setMaxLines(compact ? 1 : 2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(t);
        TextView m = text(15, "sans-serif-medium", 0xB3FFFFFF);
        m.setText(kindAndYear(r));
        info.addView(m, llp(-2, -2, 0, dp(4), 0, 0));
        TextView ov = text(15, "sans-serif", 0xCCFFFFFF);
        ov.setText(r.overview);
        ov.setMaxLines(compact ? 2 : 4);
        ov.setEllipsize(TextUtils.TruncateAt.END);
        ov.setLineSpacing(0, 1.2f);
        info.addView(ov, llp(-1, -2, 0, dp(12), 0, 0));
        TextView hint = text(14, "sans-serif-medium", 0x99FFFFFF);
        hint.setText(compact ? "Press Search or ↓ for all results"
                : pickFor != null ? "OK  ·  Choose this show" : "OK  ·  Where to watch");
        info.addView(hint, llp(-2, -2, 0, dp(14), 0, 0));
        int infoW = screenW - 2 * dp(56) - cardW - dp(32);
        box.addView(info, llp(infoW, -2, dp(32), 0, 0, 0));

        frame.setOnFocusChangeListener((v, has) -> {
            Focus.animate(v, has, ring);
            hint.setTextColor(has ? Color.WHITE : 0x99FFFFFF);
            if (has) { lastCard = v; scrollTo(box); }
        });
        frame.setOnClickListener(v -> { if (!pick(r)) openDetail(r); });
        cards.add(frame);
        row.items.addView(box);
    }

    /** One section in the results: optional label over a sideways-scrolling row. */
    private final class HorizontalRow {
        final LinearLayout items = new LinearLayout(SearchActivity.this);

        HorizontalRow(String label) {
            LinearLayout section = new LinearLayout(SearchActivity.this);
            section.setOrientation(LinearLayout.VERTICAL);
            section.setClipChildren(false);
            section.setClipToPadding(false);
            if (label != null) {
                TextView l = text(13, "sans-serif-medium", 0xB3FFFFFF);
                l.setLetterSpacing(0.22f);
                l.setText(label);
                section.addView(l, llp(-2, -2, dp(56), dp(18), 0, 0));
            }
            android.widget.HorizontalScrollView h = new android.widget.HorizontalScrollView(SearchActivity.this);
            h.setHorizontalScrollBarEnabled(false);
            h.setClipChildren(false);
            h.setClipToPadding(false);
            h.setPadding(dp(56), dp(16), dp(56), dp(10)); // room for the focus zoom
            items.setClipChildren(false);
            items.setClipToPadding(false);
            h.addView(items, new FrameLayout.LayoutParams(-2, -2));
            section.addView(h, new LinearLayout.LayoutParams(-1, -2));
            grid.addView(section, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    /**
     * A 2:3 poster card with a two-line title. People get their photo and what they're known for.
     * With no poster: the backdrop, or the title on a colour picked from it.
     */
    private View card(Tmdb.Result r) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout frame = new PosterFrame(this);
        frame.setFocusable(true);
        frame.setClickable(true);
        frame.setClipToOutline(true);
        boolean person = "person".equals(r.type);
        frame.setOutlineProvider(rounded(dp(8)));
        frame.setBackgroundColor(accent(r.title));
        TextView placeholder = text(15, "sans-serif-medium", Color.WHITE);
        placeholder.setText(r.title);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setPadding(dp(10), 0, dp(10), 0);
        frame.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(img, new FrameLayout.LayoutParams(-1, -1));
        if (r.image == null && r.backdrop != null && !person) loadInto(img, r.backdrop, "w780");
        else loadInto(img, r.image, "w342");
        View ring = Focus.ring(this, dp(8));
        frame.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        box.addView(frame, new LinearLayout.LayoutParams(-1, -2));

        TextView name = text(15, "sans-serif-medium", Color.WHITE);
        name.setText(r.title);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setLineSpacing(0, 1.05f);
        box.addView(name, llp(-1, -2, 0, dp(10), 0, 0));
        TextView sub = text(13, "sans-serif", 0xB3FFFFFF);
        sub.setText(person ? r.knownFor : kindAndYear(r));
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(sub, llp(-1, -2, 0, dp(3), 0, 0));

        frame.setOnFocusChangeListener((v, has) -> {
            Focus.animate(v, has, ring);
            if (has) { lastCard = v; scrollTo(box); }
        });
        frame.setOnClickListener(v -> { if (pick(r)) return; if (person) showCredits(r); else openDetail(r); });
        cards.add(frame);
        return box;
    }

    private static String kindAndYear(Tmdb.Result r) {
        return ("movie".equals(r.type) ? "Movie" : "TV show") + (r.year.isEmpty() ? "" : " · " + r.year);
    }

    /** A muted colour from the title, so placeholder cards differ from each other. */
    private static int accent(String title) {
        float hue = (title.hashCode() & 0x7fffffff) % 360;
        return Color.HSVToColor(new float[]{hue, 0.45f, 0.36f});
    }

    private ViewOutlineProvider rounded(int radius) {
        return new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        };
    }

    // ---------------------------------------------------------------- detail

    private void openDetail(Tmdb.Result r) {
        detail.removeAllViews();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        ImageView back = new ImageView(this);
        back.setScaleType(ImageView.ScaleType.CENTER_CROP);
        back.setBackgroundColor(0xFF0D1018);
        detail.addView(back, new FrameLayout.LayoutParams(-1, -1));
        loadInto(back, r.backdrop, "w1280");
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF2080A10, 0xCC080A10, 0x00080A10}));
        detail.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        // Text and buttons keep to the left ~60%, so the backdrop shows on the right.
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setClipChildren(false);
        info.setClipToPadding(false);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams((int) (screenW * 0.6f), -2,
                Gravity.START | Gravity.CENTER_VERTICAL);
        ip.setMargins(dp(64), 0, 0, 0);
        detail.addView(info, ip);

        TextView t = text(32, "sans-serif-light", Color.WHITE);
        t.setText(r.title);
        info.addView(t);
        TextView meta = text(14, "sans-serif-medium", 0xB3FFFFFF);
        meta.setText(kindAndYear(r));
        info.addView(meta, llp(-2, -2, 0, dp(6), 0, 0));
        TextView ov = text(15, "sans-serif", 0xCCFFFFFF);
        ov.setText(r.overview);
        ov.setMaxLines(5);
        ov.setEllipsize(TextUtils.TruncateAt.END);
        ov.setLineSpacing(0, 1.2f);
        info.addView(ov, llp(-1, -2, 0, dp(16), 0, 0));

        TextView loading = text(14, "sans-serif", 0xB3FFFFFF);
        loading.setText("Checking your apps…");
        info.addView(loading, llp(-2, -2, 0, dp(24), 0, 0));
        // The main button: the best way to watch on an app this TV already has.
        FrameLayout primarySlot = new FrameLayout(this);
        primarySlot.setClipChildren(false);
        info.addView(primarySlot, llp(-2, -2, 0, dp(22), 0, 0));
        TextView otherLabel = text(13, "sans-serif-medium", 0xB3FFFFFF);
        otherLabel.setLetterSpacing(0.22f);
        otherLabel.setVisibility(View.GONE);
        info.addView(otherLabel, llp(-2, -2, 0, dp(26), 0, 0));
        // Everything else, smaller, with provider logos. Wraps onto more rows if needed.
        FlowLayout chips = new FlowLayout(this, dp(14), dp(14));
        info.addView(chips, llp(-1, -2, 0, dp(12), 0, 0));

        // Back on the remote closes this page. Until the buttons arrive the page itself holds
        // focus, and the results behind it can't take it.
        main.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        detail.setVisibility(View.VISIBLE);
        detail.setAlpha(0f);
        detail.animate().alpha(1f).setDuration(200).start();
        detail.requestFocus();

        int gen = ++generation;
        api.execute(() -> {
            List<Tmdb.Offer> offers;
            try { offers = tmdb.offers(r); } catch (Exception e) { offers = null; }
            List<Tmdb.Offer> got = offers;
            // Direct links into apps that can open this exact title (Wikidata). Best effort.
            Map<String, String> found;
            try { found = Wikidata.links(r); } catch (Exception e) { found = new HashMap<>(); }
            Map<String, String> links = found;
            ui.post(() -> {
                if (gen != generation || detail.getVisibility() != View.VISIBLE) return;
                if (got == null) { loading.setText("Couldn't load where to watch."); return; }
                List<Tmdb.Offer> mine = new ArrayList<>(), other = new ArrayList<>();
                List<String> seen = new ArrayList<>(); // one button per app (Paramount+ has several plans)
                for (Tmdb.Offer o : got) {
                    // Skip add-on channels sold through another store ("Paramount+ Amazon Channel").
                    if (o.provider.toLowerCase(Locale.ROOT).contains("channel")) continue;
                    String pkg = PROVIDERS.get(squash(o.provider));
                    String key = pkg != null ? pkg : appName(o.provider).toLowerCase(Locale.ROOT);
                    if (seen.contains(key)) continue;
                    seen.add(key);
                    if (pkg != null && isInstalled(pkg)) mine.add(o);
                    else if (other.size() < 8) other.add(o);
                }
                // Main button: an installed app that includes it (subscription, free) and can open the
                // title itself, else any installed app that includes it, else the first installed one.
                Tmdb.Offer best = null;
                for (Tmdb.Offer o : mine) if (included(o) && links.containsKey(PROVIDERS.get(squash(o.provider)))) { best = o; break; }
                if (best == null) for (Tmdb.Offer o : mine) if (included(o)) { best = o; break; }
                if (best == null && !mine.isEmpty()) best = mine.get(0);

                if (best != null) {
                    String pkg = PROVIDERS.get(squash(best.provider));
                    String link = links.get(pkg);
                    primarySlot.addView(primaryButton((link != null ? "▶  Watch on " : "▶  Open in ") + appName(best.provider),
                            best.kind, v -> { if (link == null || !openLink(pkg, link)) watch(pkg, r.title); }),
                            new FrameLayout.LayoutParams(-2, dp(52)));
                }
                for (Tmdb.Offer o : mine) {
                    if (o == best) continue;
                    String pkg = PROVIDERS.get(squash(o.provider));
                    String link = links.get(pkg);
                    chips.addView(chip(o, appName(o.provider), o.kind, true,
                            v -> { if (link == null || !openLink(pkg, link)) watch(pkg, r.title); }),
                            new ViewGroup.LayoutParams(-2, dp(44)));
                }
                for (Tmdb.Offer o : other) {
                    String name = appName(o.provider);
                    // Not installed: opens the Play Store searching for the app.
                    chips.addView(chip(o, name, "Get", false, v -> getApp(name)), new ViewGroup.LayoutParams(-2, dp(44)));
                }
                if (got.isEmpty()) loading.setText("Not streaming anywhere in your country right now.");
                else if (mine.isEmpty() && other.isEmpty()) loading.setText("Not on any app for this TV.");
                else if (mine.isEmpty()) loading.setText("Not on your apps yet. Get one of these:");
                else loading.setVisibility(View.GONE);
                if (chips.getChildCount() > 0 && best != null) {
                    otherLabel.setText("OTHER WAYS TO WATCH");
                    otherLabel.setVisibility(View.VISIBLE);
                }
                View first = primarySlot.getChildCount() > 0 ? primarySlot.getChildAt(0)
                        : chips.getChildCount() > 0 ? chips.getChildAt(0) : null;
                if (first != null) first.requestFocus();
            });
        });
    }

    /** Watching is included (subscription, free, with ads), not a rental or purchase. */
    private static boolean included(Tmdb.Offer o) {
        return !"Rent".equals(o.kind) && !"Buy".equals(o.kind);
    }

    private void closeDetail() {
        generation++;
        detail.setVisibility(View.GONE);
        detail.removeAllViews();
        main.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (lastCard != null) lastCard.requestFocus();
    }

    /** Opens a title link in that app only (never a browser or chooser). False if the app refused it. */
    private boolean openLink(String pkg, String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
                    .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Opens the app on its search for this title when it supports that, otherwise just the app. */
    private void watch(String pkg, String title) {
        Intent s = new Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!getPackageManager().queryIntentActivities(s, 0).isEmpty()) {
            try { startActivity(s); return; } catch (RuntimeException ignored) { }
        }
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Opens the Play Store with a search for the app, so it's at the top of the results. */
    private void getApp(String name) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?c=apps&q=" + Uri.encode(name)))
                    .setPackage("com.android.vending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { }
    }

    /** Service name without plan names: "Paramount Plus Premium" → "Paramount Plus". */
    static String appName(String provider) {
        String n = provider.trim(), prev;
        do {
            prev = n;
            n = n.replaceAll("(?i)\\s+(premium plus|premium|essential|basic with ads|standard with ads|with ads|"
                    + "with showtime|free|store|kids)$", "");
        } while (!n.equals(prev));
        return n;
    }

    // ---------------------------------------------------------------- helpers

    private void loadInto(ImageView v, String path, String size) {
        if (path == null) return;
        imgs.execute(() -> {
            Bitmap b = tmdb.image(path, size);
            if (b != null) ui.post(() -> { v.setImageBitmap(b); v.setAlpha(0f); v.animate().alpha(1f).setDuration(200).start(); });
        });
    }

    /** Brings the focused card's row into view (the row itself scrolls sideways on its own). */
    private void scrollTo(View box) {
        View section = box;
        while (section.getParent() instanceof View && section.getParent() != grid) section = (View) section.getParent();
        if (section.getParent() != grid) return;
        // Later rows: scroll the row above fully out of view (its zoomed card included).
        int y = grid.indexOfChild(section) == 0 ? 0 : grid.getTop() + section.getTop() + dp(10);
        scroll.smoothScrollTo(0, Math.max(0, y));
    }

    // Installed apps: a see-through light green that fades out left to right, with white text. Focus
    // fills it in (pale green, dark green text) and adds a white ring.
    static final int[] GREEN = {0x998FDCAB, 0x1A9BE0B4};
    static final int[] GREEN_FOCUS = {0xF2BDF0CF, 0xCCE2F7EA};
    static final int GREEN_TEXT = 0xFF0E2E1B, GREEN_KIND = 0xA60E2E1B;
    static final int TEXT = Color.WHITE, KIND = 0xC2FFFFFF;

    /** The main watch button (always an installed app): big, with the offer kind in smaller text. */
    private TextView primaryButton(String label, String kind, View.OnClickListener l) {
        TextView b = text(17, "sans-serif-medium", TEXT);
        android.text.SpannableString plain = withKind(label, kind, KIND);
        android.text.SpannableString focused = withKind(label, kind, GREEN_KIND);
        b.setText(plain);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(26), 0, dp(26), 0);
        b.setFocusable(true);
        b.setClickable(true);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, GREEN);
        bg.setCornerRadius(dp(26));
        bg.setStroke(dp(3), 0x00FFFFFF);
        b.setBackground(bg);
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColors(has ? GREEN_FOCUS : GREEN);
            bg.setStroke(dp(3), has ? Color.WHITE : 0x00FFFFFF);
            b.setTextColor(has ? GREEN_TEXT : TEXT);
            b.setText(has ? focused : plain);
            Focus.animate(v, has, null);
        });
        b.setOnClickListener(l);
        return b;
    }

    private static android.text.SpannableString withKind(String label, String kind, int kindColor) {
        android.text.SpannableString t = new android.text.SpannableString(label + "     " + kind);
        t.setSpan(new android.text.style.RelativeSizeSpan(0.8f), label.length(), t.length(), 0);
        t.setSpan(new android.text.style.ForegroundColorSpan(kindColor), label.length(), t.length(), 0);
        return t;
    }

    /**
     * A smaller choice: provider logo, app name and the offer kind ("Subscription", "Buy"), or "Get"
     * for an app that isn't installed (outlined instead of filled green).
     */
    private View chip(Tmdb.Offer o, String name, String kind, boolean installed, View.OnClickListener l) {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(6), 0, dp(18), 0);
        c.setFocusable(true);
        c.setClickable(true);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                installed ? GREEN : new int[]{Color.TRANSPARENT, Color.TRANSPARENT});
        bg.setCornerRadius(dp(22));
        bg.setStroke(installed ? dp(3) : dp(1), installed ? 0x00FFFFFF : 0x66FFFFFF);
        c.setBackground(bg);
        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setClipToOutline(true);
        logo.setOutlineProvider(rounded(dp(8)));
        logo.setBackgroundColor(0x33FFFFFF);
        loadInto(logo, o.logo, "w92");
        c.addView(logo, llp(dp(32), dp(32), 0, 0, dp(10), 0));
        int nameColor = installed ? TEXT : 0xCCFFFFFF;
        int kindColor = installed ? KIND : 0x99FFFFFF;
        TextView n = text(14, "sans-serif-medium", nameColor);
        n.setText(name);
        n.setSingleLine(true);
        c.addView(n);
        TextView k = text(12, "sans-serif", kindColor);
        k.setText(kind);
        k.setSingleLine(true);
        c.addView(k, llp(-2, -2, dp(8), 0, 0, 0));
        c.setOnFocusChangeListener((v, has) -> {
            if (installed) {
                bg.setColors(has ? GREEN_FOCUS : GREEN);
                bg.setStroke(dp(3), has ? Color.WHITE : 0x00FFFFFF);
                n.setTextColor(has ? GREEN_TEXT : nameColor);
                k.setTextColor(has ? GREEN_KIND : kindColor);
            } else {
                int fill = has ? Color.WHITE : Color.TRANSPARENT;
                bg.setColors(new int[]{fill, fill});
                n.setTextColor(has ? 0xFF111111 : nameColor);
                k.setTextColor(has ? 0x99000000 : kindColor);
            }
            Focus.animate(v, has, null);
        });
        c.setOnClickListener(l);
        return c;
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void hideKeyboard() {
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(field.getWindowToken(), 0);
        wantResultsFocus = true;
        ui.postDelayed(() -> {
            if (wantResultsFocus && !cards.isEmpty()) { wantResultsFocus = false; cards.get(0).requestFocus(); }
        }, 900);
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        if (m != null && m.contains("401")) return "TMDB rejected the API key. Check launcher/tmdb-api-key.txt and reinstall.";
        return "Couldn't reach TMDB. Check the TV's internet connection.";
    }

    static String squash(String s) {
        return s.replace("+", "plus").replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
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

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    /** Lays children out left to right, wrapping to a new row when one doesn't fit. */
    static final class FlowLayout extends android.view.ViewGroup {
        private final int hGap, vGap;
        FlowLayout(Context c, int hGap, int vGap) { super(c); this.hGap = hGap; this.vGap = vGap; setClipChildren(false); }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int max = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                measureChild(c, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), hSpec);
                if (x > 0 && x + c.getMeasuredWidth() > max) { x = 0; y += rowH + vGap; rowH = 0; }
                x += c.getMeasuredWidth() + hGap;
                rowH = Math.max(rowH, c.getMeasuredHeight());
            }
            setMeasuredDimension(MeasureSpec.getSize(wSpec), y + rowH + getPaddingTop() + getPaddingBottom());
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int max = r - l - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                int w = c.getMeasuredWidth(), h = c.getMeasuredHeight();
                if (x > 0 && x + w > max) { x = 0; y += rowH + vGap; rowH = 0; }
                c.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + w, getPaddingTop() + y + h);
                x += w + hGap;
                rowH = Math.max(rowH, h);
            }
        }
    }

    /** 2:3 poster container. */
    static final class PosterFrame extends FrameLayout {
        PosterFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 3 / 2, MeasureSpec.EXACTLY));
        }
    }
}
