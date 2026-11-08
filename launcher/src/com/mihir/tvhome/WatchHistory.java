package com.mihir.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The last {@link #MAX} shows played on the TV, newest first, as reported by apps' media sessions
 * (see {@link WatchListener}). Stored on the TV only: a small JSON list in prefs, artwork as JPEGs.
 */
final class WatchHistory {
    static final int MAX = 10;
    private static final String PREFS = "watch_history", KEY = "items";

    static final class Item {
        String pkg, title, subtitle;
        long time;
        long position, duration; // ms; 0 when the app doesn't say (progress bar on the tile)
        boolean artChecked;      // TMDB has been asked for this episode's or movie's own picture
        boolean showChosen;      // the show (subtitle) was picked on the TV, not reported by the app

        static final long MOVIE_MS = 70 * 60 * 1000L;

        /** An episode whose app doesn't say which show (Peacock): no subtitle, and shorter than a movie. */
        boolean unknownShow() { return subtitle.isEmpty() && duration > 0 && duration < MOVIE_MS; }

        /** Probably a movie: no show name, and either feature length or of unknown length. */
        boolean movie() { return subtitle.isEmpty() && !unknownShow(); }

        String key() { return pkg + "|" + title.toLowerCase(java.util.Locale.ROOT); }
    }

    private WatchHistory() { }

    static synchronized List<Item> load(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.pkg = o.getString("pkg");
                it.title = o.getString("title");
                it.subtitle = o.optString("subtitle", "");
                it.time = o.optLong("time");
                it.position = o.optLong("pos");
                it.duration = o.optLong("dur");
                it.artChecked = o.optBoolean("ac2");
                it.showChosen = o.optBoolean("cs"); // "ac2": everything is re-checked once under the 70-minute rule
                out.add(it);
            }
        } catch (JSONException ignored) { }
        return out;
    }

    /** Adds or bumps an item to the front. Returns true if it's new (so the caller saves artwork). */
    static synchronized boolean put(Context c, Item item) {
        List<Item> items = load(c);
        boolean isNew = true;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key().equals(item.key())) {
                Item old = items.remove(i);
                if (item.subtitle.isEmpty()) item.subtitle = old.subtitle;
                if (item.duration == 0) { item.position = old.position; item.duration = old.duration; }
                item.artChecked = old.artChecked;
                item.showChosen = old.showChosen;
                isNew = false;
                break;
            }
        }
        items.add(0, item);
        while (items.size() > MAX) deleteArt(c, items.remove(items.size() - 1));
        save(c, items);
        return isNew;
    }

    /** Updates where playback stopped (on pause/stop), only for an item already in the list. */
    static synchronized void updatePosition(Context c, String key, long position, long duration) {
        if (duration <= 0) return;
        List<Item> items = load(c);
        for (Item it : items) {
            if (!it.key().equals(key)) continue;
            if (it.position == position && it.duration == duration) return;
            it.position = position;
            it.duration = duration;
            save(c, items);
            return;
        }
    }

    /** Remembers that TMDB was asked for this item's picture, so it isn't asked again. */
    static synchronized void markArtChecked(Context c, String key) {
        List<Item> items = load(c);
        for (Item it : items) {
            if (!it.key().equals(key)) continue;
            if (it.artChecked) return;
            it.artChecked = true;
            save(c, items);
            return;
        }
    }

    /**
     * Sets the show for an episode whose app didn't say ("Choose the show"), so its tile and the
     * episode still can be looked up. Clears the old picture and asks TMDB again.
     */
    static synchronized void setShow(Context c, String key, String show, boolean chosen) {
        List<Item> items = load(c);
        for (Item it : items) {
            if (!it.key().equals(key)) continue;
            it.subtitle = show;
            it.showChosen = chosen;
            it.artChecked = false;
            deleteArt(c, it);
            save(c, items);
            return;
        }
    }

    /** Shows picked on the TV, per app, so that app's later episodes can be matched on their own. */
    static synchronized void addChosenShow(Context c, String pkg, int tmdbId, String name) {
        List<String[]> shows = chosenShows(c);
        JSONArray a = new JSONArray();
        try {
            a.put(new JSONObject().put("pkg", pkg).put("id", tmdbId).put("name", name));
            for (String[] s : shows) {
                if (s[0].equals(pkg) && s[1].equals(String.valueOf(tmdbId))) continue;
                if (a.length() >= 20) break;
                a.put(new JSONObject().put("pkg", s[0]).put("id", Integer.parseInt(s[1])).put("name", s[2]));
            }
        } catch (JSONException ignored) { }
        prefs(c).edit().putString("chosen_shows", a.toString()).apply();
    }

    /** {pkg, TMDB id, name}, most recently picked first. */
    static synchronized List<String[]> chosenShows(Context c) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("chosen_shows", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new String[]{o.getString("pkg"), String.valueOf(o.getInt("id")), o.getString("name")});
            }
        } catch (JSONException ignored) { }
        return out;
    }

    static synchronized void remove(Context c, Item item) {
        List<Item> items = load(c);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key().equals(item.key())) { deleteArt(c, items.remove(i)); break; }
        }
        save(c, items);
    }

    static void saveArt(Context c, Item item, Bitmap bm) {
        if (bm == null) return;
        int w = Math.min(1280, bm.getWidth()); // big enough to fill the home screen behind the dock
        int h = Math.max(1, bm.getHeight() * w / Math.max(1, bm.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(bm, w, h, true);
        try (FileOutputStream out = new FileOutputStream(artFile(c, item))) {
            small.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (IOException ignored) { }
    }

    static boolean hasArt(Context c, Item item) {
        return artFile(c, item).exists();
    }

    static Bitmap loadArt(Context c, Item item) {
        return loadArt(c, item, 0);
    }

    /** Artwork decoded at roughly {@code maxWidth} pixels (0 = full size), to keep tiles light. */
    static Bitmap loadArt(Context c, Item item, int maxWidth) {
        File f = artFile(c, item);
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        if (maxWidth > 0) {
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= maxWidth) o.inSampleSize *= 2;
        }
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    static void deleteArt(Context c, Item item) {
        //noinspection ResultOfMethodCallIgnored
        artFile(c, item).delete();
    }

    private static File artFile(Context c, Item item) {
        File dir = new File(c.getFilesDir(), "watch");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, Integer.toHexString(item.key().hashCode()) + ".jpg");
    }

    private static void save(Context c, List<Item> items) {
        JSONArray a = new JSONArray();
        try {
            for (Item it : items) {
                a.put(new JSONObject().put("pkg", it.pkg).put("title", it.title)
                        .put("subtitle", it.subtitle).put("time", it.time)
                        .put("pos", it.position).put("dur", it.duration).put("ac2", it.artChecked).put("cs", it.showChosen));
            }
        } catch (JSONException ignored) { }
        prefs(c).edit().putString(KEY, a.toString()).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
