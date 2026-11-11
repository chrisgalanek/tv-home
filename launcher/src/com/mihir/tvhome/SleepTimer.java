package com.mihir.tvhome;

import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When the TV's sleep timer will turn it off. Sony's timer app keeps that private, but it sets an
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
