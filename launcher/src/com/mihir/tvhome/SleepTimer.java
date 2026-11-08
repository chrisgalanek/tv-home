package com.mihir.tvhome;

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
    private static final Pattern ORIG_WHEN = Pattern.compile("origWhen (\\d{12,})");

    private SleepTimer() { }

    /** Wall-clock time (ms) the sleep timer turns the TV off, or 0 if no timer is set. */
    static long endTime() {
        Process p = null;
        try {
            p = new ProcessBuilder("dumpsys", "alarm").redirectErrorStream(true).start();
            long lastWhen = 0;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                for (String line; (line = r.readLine()) != null; ) {
                    // Each alarm is an "Alarm{... origWhen <ms> ...}" line followed by its tag line.
                    Matcher m = ORIG_WHEN.matcher(line);
                    if (m.find()) lastWhen = Long.parseLong(m.group(1));
                    else if (line.contains(SLEEP_ALARM) && line.contains("tag=") && lastWhen > System.currentTimeMillis()) {
                        return lastWhen;
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (p != null) p.destroy();
        }
        return 0;
    }

    /** "28 min", "1 h 05 min", or "< 1 min" for the time left until {@code end}. */
    static String left(long end) {
        long ms = end - System.currentTimeMillis();
        if (ms <= 0) return "";
        long min = (ms + 59_999) / 60_000; // round up: "1 min" until it's really under a minute
        if (ms < 60_000) return "< 1 min";
        return min < 60 ? min + " min" : (min / 60) + " h " + String.format(java.util.Locale.ROOT, "%02d", min % 60) + " min";
    }
}
