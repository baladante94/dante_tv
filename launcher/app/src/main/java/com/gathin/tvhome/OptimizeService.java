package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
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

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private boolean returnHome;
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

    // ---- MEMC ------------------------------------------------------------------------------------

    /** The MEMC Effect values on MediaTek TVs, in the order the TV's picker steps through them. */
    static final String[] MEMC = {"Off", "Standard", "Sports", "Smooth"};
    private static final String PQ = "com.mediatek.tv.settings";
    private String memcTarget;
    private Done memcDone;
    private long memcUntil;
    private int memcBacks, memcLastRead;
    private long memcSettle;

    /**
     * Sets MEMC for the current picture mode the only way an app can: opens the TV's Picture panel (the side panel
     * stays over it, the video keeps playing), walks Advanced Settings → MEMC and steps the Effect picker to the
     * wanted value. Done gets 1 on success.
     */
    static boolean setMemc(String target, Done done) {
        if (instance == null || instance.pkgs != null || instance.memcTarget != null) return false;
        instance.startMemc(target, done);
        return true;
    }

    private void startMemc(String target, Done done) {
        memcTarget = target;
        memcDone = done;
        memcUntil = System.currentTimeMillis() + 20000;
        memcBacks = 0;
        memcLastRead = -1;
        memcSettle = 0;
        try {
            startActivity(new Intent().setClassName(PQ, PQ + ".displayandsound.picture.PictureActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Exception e) {
            memcFinish(false);
            return;
        }
        handler.postDelayed(this::memcStep, 300);
    }

    private void memcStep() {
        if (memcTarget == null) return;
        if (System.currentTimeMillis() > memcUntil) {
            memcFinish(false);
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && PQ.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
            List<AccessibilityNodeInfo> titles = root.findAccessibilityNodeInfosByViewId(PQ + ":id/panel_setting_title");
            CharSequence page = titles.isEmpty() ? null : titles.get(0).getText();
            if ("MEMC".contentEquals(page == null ? "" : page)) {
                AccessibilityNodeInfo effect = textNode(root, "Effect");
                if (effect != null && pageMemc(effect)) {
                    memcFinish(true);
                    return;
                }
                if (memcSettle > 0) {
                    handler.postDelayed(this::memcStep, memcSettle);
                    memcSettle = 0;
                    return;
                }
            } else if ("Advanced Settings".contentEquals(page == null ? "" : page) && openRow(root, "MEMC")
                    || "Picture".contentEquals(page == null ? "" : page) && openRow(root, "Advanced Settings")) {
                handler.postDelayed(this::memcStep, 500);
                return;
            }
        }
        handler.postDelayed(this::memcStep, 200);
    }

    /**
     * Moves the Effect picker to the target; true once it shows it. The row reports its position (0 Off .. 3 Smooth)
     * as a range, but reads 0 for a moment when the page opens and lags a press by up to a second, so a value only
     * counts once two reads agree, and the whole distance is pressed in one go before reading again.
     */
    private boolean pageMemc(AccessibilityNodeInfo effect) {
        AccessibilityNodeInfo row = clickableAncestor(effect);
        if (row == null || row.getRangeInfo() == null) return false;
        int at = Math.round(row.getRangeInfo().getCurrent()), want = Arrays.asList(MEMC).indexOf(memcTarget);
        if (want < 0) return false;
        if (at != memcLastRead) {
            memcLastRead = at;
            memcSettle = 400;
            return false;
        }
        if (at == want) return true;
        // The picker takes no accessibility scroll or progress actions, only Left and Right on the focused row.
        if (!row.isFocused()) row.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        int key = at < want ? GLOBAL_ACTION_DPAD_RIGHT : GLOBAL_ACTION_DPAD_LEFT;
        for (int i = 0; i < Math.abs(want - at); i++) handler.postDelayed(() -> performGlobalAction(key), i * 150L);
        memcLastRead = -1;
        memcSettle = Math.abs(want - at) * 150L + 1000;
        return false;
    }

    /** Clicks the row titled `title` (true), or scrolls the settings list down towards it (false). */
    private boolean openRow(AccessibilityNodeInfo root, String title) {
        AccessibilityNodeInfo n = textNode(root, title);
        AccessibilityNodeInfo row = n == null ? null : clickableAncestor(n);
        if (row != null) return row.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        List<AccessibilityNodeInfo> lists = root.findAccessibilityNodeInfosByViewId(PQ + ":id/list");
        if (!lists.isEmpty()) lists.get(0).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        return false;
    }

    private static AccessibilityNodeInfo textNode(AccessibilityNodeInfo root, String text) {
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(text)) {
            if (n.getText() != null && text.equalsIgnoreCase(n.getText().toString().trim())) return n;
        }
        return null;
    }

    /** Backs out of the Picture screens to whatever was on screen before, then lifts the cover. */
    private void memcFinish(boolean ok) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean inPq = root != null && PQ.contentEquals(root.getPackageName() == null ? "" : root.getPackageName());
        if (inPq && memcBacks++ < 4) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            handler.postDelayed(() -> memcFinish(ok), 200);
            return;
        }
        memcTarget = null;
        Done d = memcDone;
        memcDone = null;
        if (d != null) d.onDone(ok ? 1 : 0);
    }

    /** Of the given apps (package to label), the ones worth stopping: running, not core system, not in `keep`. */
    static Map<String, String> targets(Context ctx, Map<String, String> apps, String... keep) {
        Set<String> skip = new HashSet<>(Arrays.asList(keep));
        skip.addAll(Arrays.asList(ctx.getPackageName(), "com.android.vending", SETTINGS));
        String ime = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null) skip.add(ime.split("/")[0]);
        String a11y = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (a11y != null) for (String svc : a11y.split(":")) skip.add(svc.split("/")[0]);
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : apps.entrySet()) {
            if (skip.contains(e.getKey())) continue;
            try {
                ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(e.getKey(), 0);
                boolean core = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                // FLAG_STOPPED: never run since its last force stop, so nothing of it is in memory.
                if (core || (ai.flags & ApplicationInfo.FLAG_STOPPED) != 0) continue;
            } catch (android.content.pm.PackageManager.NameNotFoundException ex) {
                continue;
            }
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** returnHome false: back out of Settings when done, to whatever was on screen before (the side panel's run). */
    static void run(List<String> pkgs, List<String> labels, boolean returnHome, Done done) {
        if (instance != null && instance.pkgs == null) {
            instance.returnHome = returnHome;
            instance.start(pkgs, labels, done);
        }
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
        if (returnHome) {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
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
