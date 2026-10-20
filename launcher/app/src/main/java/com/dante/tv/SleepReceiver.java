package com.dante.tv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * The Quick panel's sleep timer: an alarm a minute before (a warning) and one at the end, which puts the TV to
 * sleep through OptimizeService. Alarms, not a handler, so it still fires while another app is in front.
 */
public class SleepReceiver extends BroadcastReceiver {

    private static final String PREFS = "sleep_timer", AT = "at";
    private static final String WARN = "com.dante.tv.SLEEP_WARN", SLEEP = "com.dante.tv.SLEEP";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (WARN.equals(intent.getAction())) {
            Toast.makeText(ctx, R.string.sleep_soon, Toast.LENGTH_LONG).show();
        } else if (SLEEP.equals(intent.getAction())) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(AT).apply();
            if (!OptimizeService.sleepNow()) Toast.makeText(ctx, R.string.sleep_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    /** Minutes left on the timer, or 0 when it is off. */
    static int minutesLeft(Context ctx) {
        long at = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(AT, 0);
        long left = at - System.currentTimeMillis();
        return left > 0 ? (int) Math.ceil(left / 60_000.0) : 0;
    }

    /** Sets the timer to this many minutes from now; 0 turns it off. */
    static void set(Context ctx, int minutes) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        PendingIntent warn = pending(ctx, WARN), sleep = pending(ctx, SLEEP);
        am.cancel(warn);
        am.cancel(sleep);
        if (minutes <= 0) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(AT).apply();
            return;
        }
        long at = System.currentTimeMillis() + minutes * 60_000L;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(AT, at).apply();
        if (minutes > 1) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at - 60_000L, warn);
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, sleep);
    }

    private static PendingIntent pending(Context ctx, String action) {
        return PendingIntent.getBroadcast(ctx, action.hashCode(),
                new Intent(action).setClass(ctx, SleepReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
