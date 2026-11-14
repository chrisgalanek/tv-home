package com.mihir.tvhome;

import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sleep timer. The Sleep dropdowns set the launcher's own timer (set); at the end
 * SleepReceiver puts the TV to sleep through TopBarService. A timer set in the TV's own menu is
 * read too: Sony's timer app keeps that private, but it sets an
 * alarm to put the TV to sleep, and the alarm list (dumpsys alarm) shows when. Reading the list
 * needs android.permission.DUMP and usage access (PACKAGE_USAGE_STATS), both granted over ADB by
 * build-and-install.sh; without them this just reports no timer. Read-only: only the sleep alarm's time is used. Call off the main thread.
 */
final class SleepTimer {
    private static final String SLEEP_ALARM = "SLEEPTIMER_PUT_DEVICE_TO_SLEEP";
    // The alarm's time since the TV started (immune to a wall clock that's wrong just after power-on).
    private static final Pattern WHEN_ELAPSED = Pattern.compile("whenElapsed (\\d+)");
    // Sony's longest sleep timer is 2 hours; anything further off isn't a running sleep timer.
    private static final long MAX_AHEAD_MS = 4 * 60 * 60 * 1000L;

    private SleepTimer() { }

    /**
     * When the sleep timer turns the TV off, as SystemClock.elapsedRealtime() (time since the TV
     * started, not the wall clock), or 0 if no timer is set. Only a sleep-alarm tag line directly
     * under its own "Alarm{... whenElapsed ...}" line counts: the list also names the alarm in its
     * history sections, next to other alarms' times.
     */
    static long endTime() {
        Process p = null;
        try {
            p = new ProcessBuilder("dumpsys", "alarm").redirectErrorStream(true).start();
            long prevWhen = -1; // whenElapsed of the line just above, or -1
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                for (String line; (line = r.readLine()) != null; ) {
                    Matcher m = WHEN_ELAPSED.matcher(line);
                    if (line.contains("Alarm{") && m.find()) {
                        prevWhen = Long.parseLong(m.group(1));
                        continue;
                    }
                    if (prevWhen >= 0 && line.contains(SLEEP_ALARM) && line.contains("tag=")) {
                        long ahead = prevWhen - SystemClock.elapsedRealtime();
                        if (ahead > 0 && ahead <= MAX_AHEAD_MS) return prevWhen;
                    }
                    prevWhen = -1;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (p != null) p.destroy();
        }
        return 0;
    }

    // ---------------------------------------------------------------- the launcher's own timer

    /** Sleep timer choices in minutes (0 = off), as in the Sleep dropdowns. */
    static final int[] CHOICES = {0, 15, 30, 45, 60, 90, 120};
    static final String ACTION_WARN = "com.mihir.tvhome.SLEEP_WARN", ACTION_SLEEP = "com.mihir.tvhome.SLEEP_NOW";
    static final long WARN_BEFORE_MS = 60_000;
    private static final String PREFS = "sleep_timer";

    /**
     * Sets (or with 0, cancels) the launcher's own sleep timer: alarms for a one-minute warning and
     * for the end, when SleepReceiver puts the TV to sleep through TopBarService. Times are kept
     * as time since the TV started, with the boot count, so a restart cancels the timer.
     */
    static void set(android.content.Context c, int minutes) {
        android.app.AlarmManager am = c.getSystemService(android.app.AlarmManager.class);
        android.app.PendingIntent warn = alarm(c, ACTION_WARN), sleep = alarm(c, ACTION_SLEEP);
        am.cancel(warn);
        am.cancel(sleep);
        android.content.SharedPreferences.Editor e = c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit();
        if (minutes <= 0) {
            e.clear().apply();
            return;
        }
        long end = SystemClock.elapsedRealtime() + minutes * 60_000L;
        e.putLong("end", end).putInt("minutes", minutes).putInt("boot", bootCount(c)).apply();
        if (minutes * 60_000L > WARN_BEFORE_MS) {
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, end - WARN_BEFORE_MS, warn);
        }
        am.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, end, sleep);
    }

    /** End of the launcher's own timer (time since the TV started), or 0 if none is running. */
    static long ownEnd(android.content.Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
        long end = p.getLong("end", 0);
        if (end == 0 || p.getInt("boot", -1) != bootCount(c) || end <= SystemClock.elapsedRealtime()) return 0;
        return end;
    }

    /** The choice (minutes) the running own timer was set to, or 0. */
    static int ownMinutes(android.content.Context c) {
        return ownEnd(c) == 0 ? 0 : c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getInt("minutes", 0);
    }

    /** Clears the own timer's record once it has fired. */
    static void clear(android.content.Context c) {
        c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().clear().apply();
    }

    /**
     * Whichever timer turns the TV off first: the launcher's own, or one set in the TV's menu
     * (read from the alarm list). Time since the TV started, or 0. Call off the main thread.
     */
    static long current(android.content.Context c) {
        long own = ownEnd(c), tv = endTime();
        if (own == 0) return tv;
        if (tv == 0) return own;
        return Math.min(own, tv);
    }

    private static android.app.PendingIntent alarm(android.content.Context c, String action) {
        android.content.Intent i = new android.content.Intent(action).setClass(c, SleepReceiver.class);
        return android.app.PendingIntent.getBroadcast(c, action.hashCode(), i,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static int bootCount(android.content.Context c) {
        return android.provider.Settings.Global.getInt(c.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, 0);
    }

    /** "15 minutes", "1 hour", "1 hour 30 minutes" for a choice. */
    static String choiceLabel(int minutes) {
        if (minutes <= 0) return "Off";
        if (minutes < 60) return minutes + " minutes";
        return minutes % 60 == 0 ? (minutes / 60) + (minutes == 60 ? " hour" : " hours")
                : (minutes / 60) + " hour " + (minutes % 60) + " minutes";
    }

    /** True while {@code end} (from endTime) is still ahead. */
    static boolean running(long end) {
        long ahead = end - SystemClock.elapsedRealtime();
        return end > 0 && ahead > 0 && ahead <= MAX_AHEAD_MS;
    }

    /** "28 min", "1 h 05 min", or "< 1 min" for the time left until {@code end}. */
    static String left(long end) {
        if (!running(end)) return "";
        long ms = end - SystemClock.elapsedRealtime();
        if (ms < 60_000) return "< 1 min";
        long min = (ms + 59_999) / 60_000; // round up: "1 min" until it's really under a minute
        return min < 60 ? min + " min" : (min / 60) + " h " + String.format(java.util.Locale.ROOT, "%02d", min % 60) + " min";
    }
}
