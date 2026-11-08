package com.mihir.tvhome;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The background pictures, shared by the home screen and the screensaver (ArtDream). Which set
 * comes from config.xml's wallpaper_set: "art" (historic Christian paintings, sculpture, glass,
 * mosaics and churches, with captions), "nature" (landscape photos) or "both" (taking turns).
 * Captions come from each folder's captions.tsv: file, title, artist and date, place.
 */
final class Wallpapers {
    final String[] paths;                  // asset paths, e.g. "wallpapers/art/07.jpg"
    final Map<String, String[]> captions;  // path → {title, artist and date, place}

    private Wallpapers(String[] paths, Map<String, String[]> captions) {
        this.paths = paths;
        this.captions = captions;
    }

    static Wallpapers load(Context c) {
        String set = c.getString(R.string.wallpaper_set).trim();
        Map<String, String[]> captions = new HashMap<>();
        List<String[]> lists = new ArrayList<>();
        for (String dir : "both".equals(set) ? new String[]{"art", "nature"} : new String[]{set}) {
            List<String> files = new ArrayList<>();
            try {
                String[] names = c.getAssets().list("wallpapers/" + dir);
                if (names != null) for (String n : names) if (n.endsWith(".jpg")) files.add("wallpapers/" + dir + "/" + n);
            } catch (IOException ignored) { }
            Collections.sort(files);
            if ("art".equals(dir)) addDownloads(c, files, captions); // new pictures from ArtDownloader
            lists.add(files.toArray(new String[0]));
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    c.getAssets().open("wallpapers/" + dir + "/captions.tsv"), "UTF-8"))) {
                for (String line; (line = r.readLine()) != null; ) {
                    String[] f = line.split("\t");
                    if (f.length >= 4) captions.put("wallpapers/" + dir + "/" + f[0], new String[]{f[1], f[2], f[3]});
                }
            } catch (IOException ignored) { } // nature photos have no captions
        }
        // Interleave the sets ("both"), so it alternates rather than running through one then the other.
        List<String> all = new ArrayList<>();
        for (int i = 0, more = 1; more > 0; i++) {
            more = 0;
            for (String[] l : lists) if (i < l.length) { all.add(l[i]); more++; }
        }
        return new Wallpapers(all.toArray(new String[0]), captions);
    }

    /** Pictures ArtDownloader has added (files/art_dl/), as "file:" paths, with their captions. */
    private static void addDownloads(Context c, List<String> files, Map<String, String[]> captions) {
        java.io.File dir = new java.io.File(c.getFilesDir(), ArtDownloader.DIR);
        String[] names = dir.list((d, n) -> n.endsWith(".jpg"));
        if (names == null) return;
        java.util.Arrays.sort(names);
        for (String n : names) files.add("file:" + new java.io.File(dir, n).getPath());
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(new java.io.File(dir, "captions.tsv")), "UTF-8"))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] f = line.split("\t");
                if (f.length >= 4) captions.put("file:" + new java.io.File(dir, f[0]).getPath(), new String[]{f[1], f[2], f[3]});
            }
        } catch (IOException ignored) { }
    }

    /** Opens a picture: a bundled asset, or a downloaded "file:" one. */
    static java.io.InputStream open(Context c, String path) throws IOException {
        return path.startsWith("file:") ? new java.io.FileInputStream(path.substring(5)) : c.getAssets().open(path);
    }

    /**
     * The next picture, in a shuffled order shared by the home screen and the screensaver: every
     * picture once before any repeats, then a new shuffle (never starting with the one just shown).
     */
    static synchronized int next(Context c, int count, int current) {
        if (count <= 1) return 0;
        android.content.SharedPreferences p = c.getSharedPreferences("wallpaper_deck", Context.MODE_PRIVATE);
        String[] deck = p.getString("deck", "").split(",");
        int pos = p.getInt("pos", 0);
        if (deck.length != count || pos >= count) {
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < count; i++) order.add(i);
            Collections.shuffle(order);
            if (order.get(0) == current) Collections.swap(order, 0, count - 1);
            StringBuilder b = new StringBuilder();
            for (int i : order) b.append(b.length() == 0 ? "" : ",").append(i);
            deck = b.toString().split(",");
            pos = 0;
        }
        int next;
        try {
            next = Integer.parseInt(deck[pos]) % count;
        } catch (NumberFormatException e) {
            next = (current + 1) % count;
        }
        p.edit().putString("deck", String.join(",", deck)).putInt("pos", pos + 1).apply();
        return next;
    }

    /** "Michelangelo, 1515  ·  San Pietro in Vincoli, Rome", or "" without a caption. */
    String subtitle(String path) {
        String[] c = captions.get(path);
        return c == null ? "" : c[1] + "  ·  " + c[2];
    }

    String title(String path) {
        String[] c = captions.get(path);
        return c == null ? "" : c[0];
    }
}
