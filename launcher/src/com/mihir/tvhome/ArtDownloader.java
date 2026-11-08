package com.mihir.tvhome;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Once a day, adds a few new pictures of historic Christian art to the background rotation: freely
 * licensed paintings, sculpture, stained glass, mosaics and churches inside and out from Wikimedia
 * Commons (photos from its "Quality images"). Each is framed like the built-in set (wide pictures
 * fill the screen; tall ones stand full height on the right, fading into a deep tone from the
 * picture) and captioned with its title, photographer or artist, and licence. The newest
 * {@link #KEEP} are kept in files/art_dl/, next to captions.tsv and credits.tsv.
 */
public class ArtDownloader extends JobService {
    static final int JOB_DAILY = 4201, JOB_NOW = 4202;
    static final int PER_RUN = 6, KEEP = 150;
    static final String DIR = "art_dl";
    private static final String UA = "tv-home-wallpapers/1.0 (personal TV launcher)";
    private static final Pattern OK_LICENSE =
            Pattern.compile("(?i)public domain|^pd|cc0|cc[- ]by(?!.*\\b(nc|nd)\\b)");

    /** Photo searches, limited to Commons "Quality images" so they're sharp and well composed. */
    private static final String[] PHOTO_QUERIES = {
            "cathedral interior nave", "church interior altar", "basilica interior", "church ceiling fresco",
            "baroque church interior", "gothic cathedral exterior", "church facade", "abbey church",
            "stained glass window church", "rose window cathedral", "church apse mosaic",
            "orthodox church interior icons", "statue of saint church", "Madonna statue", "crucifix church sculpture",
            "pieta sculpture", "baroque altar sculpture", "church portal sculpture", "monastery church",
            "wooden church", "chapel interior",
    };
    /** Painting searches: paintings on Commons are photographs of public-domain works. */
    private static final String[] PAINTING_QUERIES = {
            "Madonna and Child painting", "Annunciation painting", "Nativity painting", "Adoration of the Magi painting",
            "Crucifixion painting", "Resurrection of Christ painting", "Last Supper painting", "Baptism of Christ painting",
            "Saint painting Renaissance", "altarpiece", "icon of Christ", "icon Theotokos", "Holy Family painting",
    };

    /** Schedules the daily job (kept across reboots), plus one run soon if nothing was downloaded yet. */
    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        ComponentName me = new ComponentName(c, ArtDownloader.class);
        if (js.getPendingJob(JOB_DAILY) == null) {
            js.schedule(new JobInfo.Builder(JOB_DAILY, me)
                    .setPeriodic(24L * 60 * 60 * 1000)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build());
        }
        File[] have = new File(c.getFilesDir(), DIR).listFiles((d, n) -> n.endsWith(".jpg"));
        if ((have == null || have.length == 0) && js.getPendingJob(JOB_NOW) == null) {
            js.schedule(new JobInfo.Builder(JOB_NOW, me)
                    .setMinimumLatency(60_000)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build());
        }
    }

    private volatile Thread worker;

    @Override
    public boolean onStartJob(JobParameters params) {
        worker = new Thread(() -> {
            boolean retry = false;
            try {
                run(this);
            } catch (Exception e) {
                retry = params.getJobId() == JOB_NOW; // offline or rate-limited: the daily job tries again anyway
            }
            jobFinished(params, retry);
        });
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (worker != null) worker.interrupt();
        return false;
    }

    // ---------------------------------------------------------------- download

    static void run(Context c) throws Exception {
        File dir = new File(c.getFilesDir(), DIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        SharedPreferences prefs = c.getSharedPreferences("art_downloader", MODE_PRIVATE);
        Set<String> seen = new HashSet<>(prefs.getStringSet("seen", new HashSet<>()));
        Random rnd = new Random();
        List<String> queries = new ArrayList<>();
        for (String q : PHOTO_QUERIES) queries.add("P" + q);
        for (String q : PAINTING_QUERIES) queries.add("A" + q);
        Collections.shuffle(queries, rnd);

        int added = 0;
        for (String q : queries) {
            if (added >= PER_RUN || Thread.currentThread().isInterrupted()) break;
            boolean photo = q.charAt(0) == 'P';
            String search = q.substring(1) + (photo ? " incategory:Quality_images" : "") + " filetype:bitmap";
            JSONObject res = api("action", "query", "list", "search", "srsearch", search, "srnamespace", "6",
                    "srlimit", "20", "sroffset", String.valueOf(rnd.nextInt(10) * 20));
            JSONArray hits = res.getJSONObject("query").optJSONArray("search");
            if (hits == null || hits.length() == 0) continue;
            List<String> titles = new ArrayList<>();
            for (int i = 0; i < hits.length(); i++) {
                String t = hits.getJSONObject(i).getString("title");
                if (!seen.contains(t)) titles.add(t);
            }
            if (titles.isEmpty()) continue;
            Collections.shuffle(titles, rnd);
            titles = titles.subList(0, Math.min(8, titles.size()));
            JSONObject info = api("action", "query", "prop", "imageinfo", "titles", String.join("|", titles),
                    "iiprop", "url|size|mime|extmetadata", "iiurlwidth", "1920");
            JSONArray pages = info.getJSONObject("query").getJSONArray("pages");
            int fromThis = 0;
            for (int i = 0; i < pages.length() && fromThis < 2 && added < PER_RUN; i++) {
                JSONObject p = pages.getJSONObject(i);
                String title = p.optString("title");
                seen.add(title);
                JSONArray iis = p.optJSONArray("imageinfo");
                if (iis == null || iis.length() == 0) continue;
                JSONObject ii = iis.getJSONObject(0);
                String mime = ii.optString("mime");
                int w = ii.optInt("width"), h = ii.optInt("height");
                if (!mime.equals("image/jpeg") && !mime.equals("image/png")) continue;
                if (Math.max(w, h) < 1600 || w < h * 0.45 || w > h * 2.6) continue; // too small, or a strip
                JSONObject md = ii.optJSONObject("extmetadata");
                String license = meta(md, "LicenseShortName");
                if (!OK_LICENSE.matcher(license).find()) continue;
                if (!photo && !license.toLowerCase().contains("public domain") && !license.toLowerCase().startsWith("pd")) continue;
                Bitmap src = download(ii.optString("thumburl", ii.optString("url")));
                if (src == null) continue;
                Bitmap framed = frame(src);
                src.recycle();
                String name = Integer.toHexString(title.hashCode()) + ".jpg";
                try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
                    framed.compress(Bitmap.CompressFormat.JPEG, 82, out);
                }
                framed.recycle();
                String caption = cleanTitle(meta(md, "ObjectName"), title);
                String author = Wallpapers.tidyAuthor(meta(md, "Artist"));
                if (author.length() > 60) author = author.substring(0, 57) + "…";
                append(new File(dir, "captions.tsv"), name + "\t" + caption + "\t" + author + "\tWikimedia Commons, " + license);
                append(new File(dir, "credits.tsv"), name + "\t" + title + "\t" + author + "\t" + license + "\t" + ii.optString("descriptionurl"));
                android.util.Log.i("ArtDownloader", "added " + title + " (" + license + ")");
                added++;
                fromThis++;
            }
        }
        prefs.edit().putStringSet("seen", seen).apply();
        prune(dir);
        android.util.Log.i("ArtDownloader", "added " + added + " pictures");
    }

    /** Keeps the newest KEEP pictures; drops older ones and their caption lines. */
    private static void prune(File dir) throws Exception {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".jpg"));
        if (files == null || files.length <= KEEP) return;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        Set<String> drop = new HashSet<>();
        for (int i = KEEP; i < files.length; i++) {
            drop.add(files[i].getName());
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
        for (String list : new String[]{"captions.tsv", "credits.tsv"}) {
            File f = new File(dir, list);
            if (!f.exists()) continue;
            StringBuilder keep = new StringBuilder();
            for (String line : new String(readAll(new java.io.FileInputStream(f)), "UTF-8").split("\n")) {
                if (!line.isEmpty() && !drop.contains(line.split("\t")[0])) keep.append(line).append('\n');
            }
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(keep.toString().getBytes("UTF-8")); }
        }
    }

    // ---------------------------------------------------------------- framing (same as the built-in set)

    static Bitmap frame(Bitmap im) {
        final int W = 1920, H = 1080;
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint smooth = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        float ratio = im.getWidth() / (float) im.getHeight();
        int fitW = Math.round(im.getWidth() * (H / (float) im.getHeight()));
        if (ratio >= 1.25f || fitW > W * 0.8f) {
            // Wide: fill the screen, cropping a little top and bottom (kept a bit above centre).
            float s = Math.max(W / (float) im.getWidth(), H / (float) im.getHeight());
            float dw = im.getWidth() * s, dh = im.getHeight() * s;
            cv.drawBitmap(im, null, new RectF((W - dw) / 2, (H - dh) * 0.35f, (W + dw) / 2, (H - dh) * 0.35f + dh), smooth);
            return out;
        }
        // Tall: full height against the right edge, fading into a deep tone from the picture's left edge.
        Bitmap edge = Bitmap.createScaledBitmap(Bitmap.createBitmap(im, 0, 0, Math.max(1, im.getWidth() / 6), im.getHeight()), 1, 1, true);
        int e = edge.getPixel(0, 0);
        double lum = 0.2126 * Color.red(e) + 0.7152 * Color.green(e) + 0.0722 * Color.blue(e);
        double k = 22.0 / Math.max(lum, 1.0);
        int base = Color.rgb((int) Math.min(40, Color.red(e) * k), (int) Math.min(40, Color.green(e) * k), (int) Math.min(40, Color.blue(e) * k));
        cv.drawColor(base);
        int x0 = W - fitW;
        int layer = cv.saveLayer(x0, 0, W, H, null);
        cv.drawBitmap(im, null, new Rect(x0, 0, W, H), smooth);
        Paint mask = new Paint();
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        float fade = fitW * 0.45f;
        mask.setShader(new LinearGradient(x0, 0, x0 + fade, 0,
                new int[]{0x00000000, 0x40000000, 0xC0000000, 0xFF000000}, new float[]{0f, 0.3f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(x0, 0, W, H, mask);
        cv.restoreToCount(layer);
        Paint vignette = new Paint();
        vignette.setShader(new LinearGradient(0, 0, W * 0.55f, 0, (base & 0x00FFFFFF) | 0x78000000, base & 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, W, H, vignette);
        return out;
    }

    // ---------------------------------------------------------------- helpers

    /** "File:Interior_of_St._Vitus_Cathedral_-_02.jpg" → "Interior of St. Vitus Cathedral". */
    static String cleanTitle(String objectName, String fileTitle) {
        String t = objectName.isEmpty() ? fileTitle.replaceFirst("^File:", "") : objectName;
        t = t.replaceAll("\\.(jpe?g|png|tiff?)$", "").replace('_', ' ');
        t = t.replaceAll("\\s*[-–,]?\\s*\\(?\\d{1,4}\\)?$", "").replaceAll("\\s+", " ").trim();
        if (t.length() > 70) t = t.substring(0, 67) + "…";
        return t;
    }

    private static String meta(JSONObject md, String key) {
        if (md == null) return "";
        JSONObject o = md.optJSONObject(key);
        String v = o == null ? "" : o.optString("value", "");
        return v.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    }

    private static JSONObject api(String... kv) throws Exception {
        Uri.Builder u = Uri.parse("https://commons.wikimedia.org/w/api.php").buildUpon()
                .appendQueryParameter("format", "json").appendQueryParameter("formatversion", "2");
        for (int i = 0; i + 1 < kv.length; i += 2) u.appendQueryParameter(kv[i], kv[i + 1]);
        return new JSONObject(new String(get(u.build().toString()), "UTF-8"));
    }

    private static Bitmap download(String url) {
        try {
            byte[] data = get(url);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            while (o.outHeight / (o.inSampleSize * 2) >= 1080 && o.outWidth / (o.inSampleSize * 2) >= 1920 * 0.45) o.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        }
    }

    /** GET, politely: a pause before each request; gives up on errors (the next day tries again). */
    private static byte[] get(String url) throws Exception {
        Thread.sleep(1500);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", UA);
        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("HTTP " + code);
        try (InputStream in = c.getInputStream()) {
            return readAll(in);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            for (int n; (n = i.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static synchronized void append(File f, String line) throws Exception {
        try (FileWriter w = new FileWriter(f, true)) { w.write(line.replace("\n", " ") + "\n"); }
    }
}
