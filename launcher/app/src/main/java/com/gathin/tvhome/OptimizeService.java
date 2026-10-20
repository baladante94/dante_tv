package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import java.util.List;

/**
 * Frees memory the only way Android 14 still allows from the TV itself: for each app, open its App info page in
 * TV Settings and press Force stop, then OK, under a full-screen cover so the pages are never seen. Idle and
 * blind to everything else until the launcher's Optimize button hands it a list.
 */
public class OptimizeService extends AccessibilityService {

    interface Done {
        void onDone(int stopped);
    }

    private static final String SETTINGS = "com.android.tv.settings";
    private static final String ACTION_ITEM = SETTINGS + ":id/guidedactions_item_title";
    /** Give up on one app after this long (a page that never loads, a missing button) and go on to the next. */
    private static final long STEP_TIMEOUT_MS = 5000;

    private static OptimizeService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::next;
    private List<String> pkgs, labels;
    private Done done;
    private int index, stopped;
    /** 0: waiting for the App info page, 1: waiting for the Force stop question, 2: answered, moving on. */
    private int step;
    private String forceStopText, okText;
    private View cover;
    private TextView progress;
    private ObjectAnimator spin;

    static boolean available() {
        return instance != null;
    }

    /** Puts the TV to sleep, as the power button would; false when the service is off or the TV refuses. */
    static boolean sleepNow() {
        return instance != null && instance.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    static void run(List<String> pkgs, List<String> labels, Done done) {
        if (instance != null && instance.pkgs == null) instance.start(pkgs, labels, done);
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        removeCover();
        return super.onUnbind(intent);
    }

    private void start(List<String> pkgs, List<String> labels, Done done) {
        this.pkgs = pkgs;
        this.labels = labels;
        this.done = done;
        stopped = 0;
        index = -1;
        // The button texts in the TV's own language, read from the Settings app; English if it has none.
        forceStopText = settingsString("force_stop", "Force stop");
        okText = getString(android.R.string.ok);
        showCover();
        next();
    }

    private String settingsString(String name, String fallback) {
        try {
            Resources r = getPackageManager().getResourcesForApplication(SETTINGS);
            int id = r.getIdentifier(name, "string", SETTINGS);
            if (id != 0) return r.getString(id);
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private void next() {
        handler.removeCallbacks(timeout);
        if (pkgs == null) return;
        index++;
        if (index >= pkgs.size()) {
            finish();
            return;
        }
        step = 0;
        progress.setText(getString(R.string.optimize_progress, labels.get(index), index + 1, pkgs.size()));
        Intent info = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkgs.get(index)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(info);
        } catch (Exception e) {
            handler.post(this::next);
            return;
        }
        handler.postDelayed(timeout, STEP_TIMEOUT_MS);
        handler.postDelayed(this::poke, 500);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (pkgs != null && SETTINGS.contentEquals(event.getPackageName() == null ? "" : event.getPackageName())) poke();
    }

    /** Looks at the Settings window and presses whatever this step needs, if it is on screen yet. */
    private void poke() {
        if (pkgs == null || step == 2) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        if (step == 0) {
            // The right app's page: its package name is the summary under the version line.
            if (root.findAccessibilityNodeInfosByText(pkgs.get(index)).isEmpty()) return;
            AccessibilityNodeInfo row = clickableWithText(root.findAccessibilityNodeInfosByText(forceStopText), forceStopText);
            if (row == null) return;
            if (!row.isEnabled()) {                // already stopped: Settings greys the row out
                step = 2;
                handler.post(this::next);
                return;
            }
            step = 1;
            row.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } else if (step == 1) {
            List<AccessibilityNodeInfo> items = root.findAccessibilityNodeInfosByViewId(ACTION_ITEM);
            if (items.isEmpty()) return;
            AccessibilityNodeInfo ok = clickableWithText(items, okText);
            if (ok == null) ok = clickableAncestor(items.get(0));   // OK is always listed first
            if (ok == null) return;
            step = 2;
            ok.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            stopped++;
            handler.removeCallbacks(timeout);
            handler.postDelayed(this::next, 300);
        }
    }

    private static AccessibilityNodeInfo clickableWithText(List<AccessibilityNodeInfo> nodes, String text) {
        for (AccessibilityNodeInfo n : nodes) {
            if (n.getText() != null && text.equalsIgnoreCase(n.getText().toString().trim())) {
                AccessibilityNodeInfo c = clickableAncestor(n);
                if (c != null) return c;
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        for (int i = 0; n != null && i < 6; i++, n = n.getParent()) {
            if (n.isClickable()) return n;
        }
        return null;
    }

    private void finish() {
        handler.removeCallbacks(timeout);
        pkgs = null;
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        handler.postDelayed(this::removeCover, 450);
        Done d = done;
        done = null;
        if (d != null) d.onDone(stopped);
    }

    private void showCover() {
        WindowManager wm = getSystemService(WindowManager.class);
        cover = LayoutInflater.from(this).inflate(R.layout.overlay_optimize, null);
        progress = cover.findViewById(R.id.progress);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        wm.addView(cover, lp);
        spin = ObjectAnimator.ofFloat(cover.findViewById(R.id.spinner), View.ROTATION, 0f, 360f);
        spin.setDuration(900);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.start();
    }

    private void removeCover() {
        if (spin != null) spin.cancel();
        spin = null;
        if (cover != null) {
            try {
                getSystemService(WindowManager.class).removeView(cover);
            } catch (Exception ignored) {
            }
        }
        cover = null;
    }

    @Override
    public void onInterrupt() {
    }
}
