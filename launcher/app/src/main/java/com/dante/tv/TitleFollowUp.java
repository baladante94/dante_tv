package com.dante.tv;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * A second send of a Netflix title link from the Top Shelf (InputKeyService feeds it windows and keys). Netflix
 * opened from closed shows "Who's watching?" and forgets the link. Its screen exposes nothing to read, so a cold
 * start is told apart by time: a running Netflix comes to the front in under 100 ms, a fresh start takes about half
 * a second. After a fresh start the first OK (a profile picked) sends the link again; it waits at most a minute.
 * (JioHotstar's picker is handled by OptimizeService, which can read it. Sending a Hotstar link again after Back
 * was tried and dropped: Hotstar's own title page plays an unwatched title by itself, so it looped.)
 */
final class TitleFollowUp {

    private static final String NETFLIX = "com.netflix.ninja";
    private static final long COLD_MS = 300, WAIT_MS = 60_000;

    private static final int NONE = 0, NETFLIX_SHOWING = 1, NETFLIX_PICKER = 2;
    private static int mode = NONE;
    private static Intent link;
    private static long sentAt;
    private static final Handler handler = new Handler(Looper.getMainLooper());

    /** Call just before starting the Netflix title link. */
    static void netflix(Intent title) {
        link = title;
        sentAt = SystemClock.uptimeMillis();
        mode = NETFLIX_SHOWING;
    }

    static void onWindow(String pkg) {
        if (mode == NETFLIX_SHOWING && NETFLIX.equals(pkg)) {
            mode = SystemClock.uptimeMillis() - sentAt > COLD_MS ? NETFLIX_PICKER : NONE;
        }
    }

    /** Never consumes the key: the app gets it as usual. */
    static void onKey(Context ctx, KeyEvent event, String foreground) {
        if (mode == NONE || event.getAction() != KeyEvent.ACTION_UP) return;
        int code = event.getKeyCode();
        if (mode == NETFLIX_PICKER) {
            if (SystemClock.uptimeMillis() - sentAt > WAIT_MS) {
                mode = NONE;
            } else if (NETFLIX.equals(foreground)
                    && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
                mode = NONE;
                resend(ctx, 2000);   // the picked profile's home loads first
            }
        }
    }

    private static void resend(Context ctx, long delay) {
        final Intent again = link;
        final Context app = ctx.getApplicationContext();
        handler.postDelayed(() -> {
            try {
                app.startActivity(again);
            } catch (Exception ignored) {
            }
        }, delay);
    }
}
