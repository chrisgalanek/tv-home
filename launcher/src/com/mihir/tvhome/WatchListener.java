package com.mihir.tvhome;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Watches what's playing so the home screen can show "Recently watched". Android only lets a
 * notification listener see other apps' media sessions, so that's what this is; notifications
 * themselves are ignored. Access is granted over ADB by launcher/build-and-install.sh:
 *   cmd notification allow_listener com.mihir.tvhome/com.mihir.tvhome.WatchListener
 * Records title, subtitle and artwork while something is PLAYING. Nothing leaves the TV.
 */
public class WatchListener extends NotificationListenerService {

    /** Each show's session activity while its app is still running: opens the player directly. */
    static final Map<String, PendingIntent> liveActivities = new HashMap<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<MediaController.Callback, MediaController> watched = new HashMap<>();
    private MediaSessionManager msm;

    private final MediaSessionManager.OnActiveSessionsChangedListener onSessions = this::watch;

    @Override
    public void onListenerConnected() {
        msm = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, WatchListener.class);
        try {
            msm.addOnActiveSessionsChangedListener(onSessions, me, handler);
            watch(msm.getActiveSessions(me));
        } catch (SecurityException ignored) { } // access not granted (yet)
    }

    @Override
    public void onListenerDisconnected() {
        if (msm != null) msm.removeOnActiveSessionsChangedListener(onSessions);
        unwatchAll();
    }

    private void watch(List<MediaController> controllers) {
        unwatchAll();
        if (controllers == null) return;
        for (MediaController mc : controllers) {
            if (mc.getPackageName().equals(getPackageName())) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override public void onPlaybackStateChanged(PlaybackState s) { record(mc); }
                @Override public void onMetadataChanged(MediaMetadata m) { record(mc); }
            };
            mc.registerCallback(cb, handler);
            watched.put(cb, mc);
            record(mc);
        }
    }

    private void unwatchAll() {
        for (Map.Entry<MediaController.Callback, MediaController> e : watched.entrySet()) {
            e.getValue().unregisterCallback(e.getKey());
        }
        watched.clear();
    }

    private void record(MediaController mc) {
        PlaybackState st = mc.getPlaybackState();
        MediaMetadata md = mc.getMetadata();
        if (st == null || md == null) return;
        String pkg = mc.getPackageName();
        String title = first(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        long duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long position = st.getPosition();
        if (st.getState() != PlaybackState.STATE_PLAYING) {
            // Paused or stopped: remember where, for the progress bar on the Recently watched tile.
            if (!title.isEmpty() && position >= 0) {
                WatchHistory.Item probe = new WatchHistory.Item();
                probe.pkg = pkg;
                probe.title = title;
                WatchHistory.updatePosition(this, probe.key(), position, duration);
            }
            return;
        }
        if (title.isEmpty() || !isApp(pkg) || sameAsAppName(pkg, title)) return;

        WatchHistory.Item it = new WatchHistory.Item();
        it.pkg = pkg;
        it.title = title;
        it.subtitle = first(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM);
        if (it.subtitle.equalsIgnoreCase(title)) it.subtitle = "";
        it.time = System.currentTimeMillis();
        if (duration > 0 && position >= 0) { it.position = position; it.duration = duration; }
        if (mc.getSessionActivity() != null) liveActivities.put(it.key(), mc.getSessionActivity());

        boolean isNew = WatchHistory.put(this, it);
        if (isNew || !WatchHistory.hasArt(this, it)) {
            Bitmap art = art(md);
            if (art != null) new Thread(() -> WatchHistory.saveArt(this, it, art)).start();
        }
    }

    private Bitmap art(MediaMetadata md) {
        for (String k : new String[]{MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            Bitmap b = md.getBitmap(k);
            if (b != null) return b;
        }
        // Local URIs only. Without artwork here, the home screen looks the show up on TMDB instead.
        for (String k : new String[]{MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
            String u = md.getString(k);
            if (u == null || !(u.startsWith("content:") || u.startsWith("android.resource:"))) continue;
            try (InputStream in = getContentResolver().openInputStream(Uri.parse(u))) {
                Bitmap b = BitmapFactory.decodeStream(in);
                if (b != null) return b;
            } catch (Exception ignored) { }
        }
        return null;
    }

    /** Only apps with a TV launcher icon: skips Bluetooth, system audio and the like. */
    private boolean isApp(String pkg) {
        return getPackageManager().getLeanbackLaunchIntentForPackage(pkg) != null;
    }

    /** Some apps put their own name in the title while browsing (Prime Video: "PrimeVideo"). */
    private boolean sameAsAppName(String pkg, String title) {
        try {
            PackageManager pm = getPackageManager();
            String label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            return squash(label).equals(squash(title));
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    private static String squash(String s) {
        return s.replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT);
    }

    private static String first(MediaMetadata md, String... keys) {
        for (String k : keys) {
            CharSequence v = md.getText(k);
            if (v != null && v.toString().trim().length() > 0) return v.toString().trim();
        }
        return "";
    }
}
