package com.mihir.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * The launcher's sleep timer going off (SleepTimer.set): a notice one minute before, then the TV
 * goes to sleep. Putting the TV to sleep needs the TopBarService accessibility service ("Home: top
 * bar"), which is allowed to; without it there's nothing this app can do, so it just stops.
 */
public class SleepReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        long end = SleepTimer.ownEnd(c);
        TopBarService bar = TopBarService.instance;
        if (SleepTimer.ACTION_WARN.equals(i.getAction())) {
            if (end != 0 && bar != null) bar.showNotice("The TV turns off in 1 minute (sleep timer)");
            return;
        }
        if (SleepTimer.ACTION_SLEEP.equals(i.getAction())) {
            // Only if this is still the timer that was set (not cancelled or replaced in between).
            long stored = c.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE).getLong("end", 0);
            if (stored == 0 || stored - SystemClock.elapsedRealtime() > 5_000) return;
            SleepTimer.clear(c);
            if (bar != null) bar.sleepNow();
        }
    }
}
