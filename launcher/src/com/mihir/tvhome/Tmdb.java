package com.mihir.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal client for The Movie Database (themoviedb.org): search movies, shows and people, a
 * person's credits, and where a title streams (TMDB's watch-provider data comes from JustWatch).
 * The key comes from launcher/tmdb-api-key.txt at build time: a v3 API key or a v4 read token.
 * Call from a background thread.
 */
final class Tmdb {
    static final String API = "https://api.themoviedb.org/3";
    static final String IMG = "https://image.tmdb.org/t/p/";

    static final class Result {
        String type;          // "movie", "tv" or "person"
        int id;
        String title, year = "", overview = "", image; // image: poster or profile path
        String backdrop;
        double popularity;
        String knownFor = ""; // people: department ("Acting") and a couple of titles
    }

    static final class Offer {
        String provider, kind; // kind: "Subscription", "Free", "With ads", "Rent", "Buy"
        String logo;
    }

    private final String key;
    private final File cacheDir;
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    Tmdb(Context c) {
        key = c.getString(R.string.tmdb_key).trim();
        cacheDir = new File(c.getCacheDir(), "tmdb");
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
    }

    boolean hasKey() { return !key.isEmpty(); }

    static String region() {
        String r = Locale.getDefault().getCountry();
        return r == null || r.isEmpty() ? "US" : r;
    }

    List<Result> search(String query) throws Exception {
        JSONObject o = get("/search/multi", "query", query, "include_adult", "false");
        List<Result> out = new ArrayList<>();
        JSONArray a = o.optJSONArray("results");
        for (int i = 0; a != null && i < a.length(); i++) {
            Result r = parse(a.getJSONObject(i), null);
            if (r != null) out.add(r);
        }
        return out;
    }

    /**
     * A person's movies and shows, best known first (most rated on TMDB). Talk shows, news and
     * "Self" appearances are left out, or a famous actor's list would be all late-night guest spots.
     */
    List<Result> credits(int personId) throws Exception {
        JSONObject o = get("/person/" + personId + "/combined_credits");
        List<Result> out = new ArrayList<>();
        Map<String, Double> pop = new LinkedHashMap<>();
        for (String part : new String[]{"cast", "crew"}) {
            JSONArray a = o.optJSONArray(part);
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                Result r = parse(j, j.optString("media_type"));
                if (r == null || r.image == null || pop.containsKey(r.type + r.id)) continue;
                String role = j.optString("character", "").toLowerCase(Locale.ROOT);
                if (role.contains("self") || hasGenre(j, 10767) || hasGenre(j, 10763)) continue; // talk, news
                pop.put(r.type + r.id, (double) j.optInt("vote_count"));
                out.add(r);
            }
        }
        out.sort((x, y) -> Double.compare(pop.get(y.type + y.id), pop.get(x.type + x.id)));
        return out.size() > 40 ? out.subList(0, 40) : out;
    }

    /** Where a movie or show is available in this country, subscriptions and free first. */
    List<Offer> offers(Result r) throws Exception {
        JSONObject o = get("/" + r.type + "/" + r.id + "/watch/providers");
        JSONObject here = o.optJSONObject("results") == null ? null : o.getJSONObject("results").optJSONObject(region());
        List<Offer> out = new ArrayList<>();
        if (here == null) return out;
        String[][] kinds = {{"flatrate", "Subscription"}, {"free", "Free"}, {"ads", "With ads"},
                {"rent", "Rent"}, {"buy", "Buy"}};
        List<String> seen = new ArrayList<>();
        for (String[] k : kinds) {
            JSONArray a = here.optJSONArray(k[0]);
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject p = a.getJSONObject(i);
                String name = p.optString("provider_name");
                if (name.isEmpty() || seen.contains(name)) continue;
                seen.add(name);
                Offer f = new Offer();
                f.provider = name;
                f.kind = k[1];
                f.logo = p.optString("logo_path", null);
                out.add(f);
            }
        }
        return out;
    }

    /**
     * The still (a frame from the episode) for a show's episode, found by the episode's name,
     * newest season first. Null if no episode has that name or it has no still.
     */
    String episodeStill(int tvId, String episodeName) throws Exception {
        String want = SearchActivity.squash(episodeName);
        if (want.isEmpty()) return null;
        int seasons = get("/tv/" + tvId).optInt("number_of_seasons");
        for (int s = Math.min(seasons, 60); s >= 0; s--) {
            JSONArray eps;
            try {
                eps = get("/tv/" + tvId + "/season/" + s).optJSONArray("episodes");
            } catch (Exception e) {
                continue; // no such season (specials are often missing)
            }
            for (int i = 0; eps != null && i < eps.length(); i++) {
                JSONObject e = eps.getJSONObject(i);
                if (!SearchActivity.squash(e.optString("name")).equals(want)) continue;
                String still = e.optString("still_path", "");
                return still.isEmpty() || "null".equals(still) ? null : still;
            }
        }
        return null;
    }

    /** Poster/profile/logo image, cached in memory and on disk. size: e.g. "w342", "w780". */
    Bitmap image(String path, String size) {
        if (path == null || path.isEmpty()) return null;
        String k = size + path;
        Bitmap b = images.get(k);
        if (b != null) return b;
        File f = new File(cacheDir, k.replaceAll("[^A-Za-z0-9.]", "_"));
        try {
            if (!f.exists()) {
                byte[] data = fetch(IMG + size + path, false);
                try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); }
            }
            b = BitmapFactory.decodeFile(f.getPath());
        } catch (Exception e) {
            return null;
        }
        if (b != null) images.put(k, b);
        return b;
    }

    private static boolean hasGenre(JSONObject j, int genre) {
        JSONArray g = j.optJSONArray("genre_ids");
        for (int i = 0; g != null && i < g.length(); i++) if (g.optInt(i) == genre) return true;
        return false;
    }

    private Result parse(JSONObject j, String typeHint) {
        String type = j.optString("media_type", typeHint);
        if (type == null || type.isEmpty()) type = typeHint;
        if (!"movie".equals(type) && !"tv".equals(type) && !"person".equals(type)) return null;
        Result r = new Result();
        r.type = type;
        r.id = j.optInt("id");
        r.title = j.optString("movie".equals(type) ? "title" : "name");
        if (r.title.isEmpty()) return null;
        String date = j.optString("movie".equals(type) ? "release_date" : "first_air_date", "");
        if (date.length() >= 4) r.year = date.substring(0, 4);
        r.overview = j.optString("overview", "");
        r.popularity = j.optDouble("popularity", 0);
        String img = j.optString("person".equals(type) ? "profile_path" : "poster_path", "");
        r.image = img.isEmpty() || "null".equals(img) ? null : img;
        String bd = j.optString("backdrop_path", "");
        r.backdrop = bd.isEmpty() || "null".equals(bd) ? null : bd;
        if ("person".equals(type)) {
            StringBuilder kf = new StringBuilder(j.optString("known_for_department", ""));
            JSONArray a = j.optJSONArray("known_for");
            for (int i = 0; a != null && i < Math.min(2, a.length()); i++) {
                JSONObject t = a.optJSONObject(i);
                if (t == null) continue;
                String n = t.optString("title", t.optString("name", ""));
                if (!n.isEmpty()) kf.append(kf.length() > 0 ? " · " : "").append(n);
            }
            r.knownFor = kf.toString();
        }
        return r;
    }

    private JSONObject get(String path, String... params) throws Exception {
        Uri.Builder u = Uri.parse(API + path).buildUpon();
        for (int i = 0; i + 1 < params.length; i += 2) u.appendQueryParameter(params[i], params[i + 1]);
        u.appendQueryParameter("language", Locale.getDefault().toLanguageTag());
        boolean bearer = key.contains(".");          // v4 read access tokens are JWTs
        if (!bearer) u.appendQueryParameter("api_key", key);
        return new JSONObject(new String(fetch(u.build().toString(), bearer), "UTF-8"));
    }

    private byte[] fetch(String url, boolean bearer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Accept", "application/json");
        int code = c.getResponseCode();
        if (code == 401) throw new Exception("TMDB rejected the API key (401)");
        if (code >= 400) throw new Exception("TMDB error " + code);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
