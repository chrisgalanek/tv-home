package com.mihir.tvhome;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Direct links into streaming apps for a TMDB title, from Wikidata (wikidata.org, free and open).
 * Wikidata records many titles' IDs on Apple TV, Hulu, Tubi, Paramount+ and Prime Video; each of
 * those apps on Android TV opens a link built from that ID straight on the title. Coverage is good
 * for well-known titles and patchy otherwise, so callers fall back to opening the app.
 * Call from a background thread.
 */
final class Wikidata {
    private static final String SPARQL = "https://query.wikidata.org/sparql";

    /** One service: its app, the Wikidata properties holding its IDs, and the link to build. */
    private static final class Service {
        final String pkg, movieProp, tvProp, movieUrl, tvUrl;
        Service(String pkg, String movieProp, String movieUrl, String tvProp, String tvUrl) {
            this.pkg = pkg; this.movieProp = movieProp; this.movieUrl = movieUrl; this.tvProp = tvProp; this.tvUrl = tvUrl;
        }
    }

    private static final Service[] SERVICES = {
            new Service("com.apple.atve.sony.appletv", "P9586", "https://tv.apple.com/movie/%s",
                    "P9751", "https://tv.apple.com/show/%s"),
            new Service("com.hulu.livingroomplus", "P6466", "https://www.hulu.com/movie/%s",
                    "P6467", "https://www.hulu.com/series/%s"),
            new Service("com.tubitv", "P7760", "https://tubitv.com/movies/%s",
                    "P7761", "https://tubitv.com/series/%s"),
            new Service("com.cbs.ott", "P13147", "https://www.paramountplus.com/shows/video/%s/",
                    "P13147", "https://www.paramountplus.com/shows/video/%s/"),
            new Service("com.amazon.amazonvideo.livingroom", "P14462", "https://app.primevideo.com/detail?gti=%s",
                    "P14462", "https://app.primevideo.com/detail?gti=%s"),
    };

    private Wikidata() { }

    /** Package → direct link for this title, for every service Wikidata has an ID for. */
    static Map<String, String> links(Tmdb.Result r) throws Exception {
        boolean movie = "movie".equals(r.type);
        StringBuilder props = new StringBuilder();
        for (Service s : SERVICES) props.append(" wdt:").append(movie ? s.movieProp : s.tvProp);
        String q = "SELECT ?p ?v WHERE { ?item wdt:" + (movie ? "P4947" : "P4983") + " \"" + r.id + "\" . "
                + "VALUES ?p {" + props + " } ?item ?p ?v } LIMIT 20";
        JSONObject o = new JSONObject(fetch(Uri.parse(SPARQL).buildUpon()
                .appendQueryParameter("query", q).appendQueryParameter("format", "json").build().toString()));

        Map<String, String> out = new HashMap<>();
        JSONArray rows = o.getJSONObject("results").getJSONArray("bindings");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String prop = row.getJSONObject("p").getString("value");   // http://www.wikidata.org/prop/direct/P9586
            prop = prop.substring(prop.lastIndexOf('/') + 1);
            String id = row.getJSONObject("v").getString("value");
            for (Service s : SERVICES) {
                if (out.containsKey(s.pkg) || !prop.equals(movie ? s.movieProp : s.tvProp)) continue;
                out.put(s.pkg, String.format(movie ? s.movieUrl : s.tvUrl, Uri.encode(id)));
            }
        }
        return out;
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/sparql-results+json");
        // Wikidata asks automated clients to identify themselves.
        c.setRequestProperty("User-Agent", "tv-home-launcher/1.0 (Android TV home screen; title links)");
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
