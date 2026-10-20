package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Maps the remote's Input button to the system input switcher, and its Settings button to the side panel
 * (SidePanel), from any app. Remotes such as the Chromecast voice
 * remote send KEYCODE_TV for it; on Google TV the stock launcher handles that key, so with the stock launcher
 * disabled the button does nothing. Only KEYCODE_TV is consumed; every other key passes through untouched.
 * Opt-in: INPUT_KEY_SERVICE=1 build-and-install.sh (or -InputKeyService on Windows) enables it.
 */
public class InputKeyService extends AccessibilityService {

    private static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";

    private SidePanel panel;
    private String foreground;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // The remote's Settings button: Dante TV's side panel, over any app (the stock Google TV home that
        // drew the original one is disabled). This Philips/Skyworth remote sends it as KEYCODE_NOTIFICATION (83,
        // scancode 240), as TCL Google TV remotes do; standard remotes send KEYCODE_SETTINGS.
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_HOME) {
            if (panel != null && panel.isOpen()) panel.close();
            return false;
        }
        if (code == KeyEvent.KEYCODE_SETTINGS || code == KeyEvent.KEYCODE_NOTIFICATION) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (panel == null) panel = new SidePanel(this);
                if (panel.isOpen()) panel.close();
                else if (panel.openedPkg != null && panel.openedPkg.equals(foreground)) backOut(panel.openedPkg, 6);
                else {
                    panel.foreground = foreground;
                    panel.open();
                }
            }
            return true;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_TV) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            try {
                startActivity(new Intent(ACTION_VIEW_INPUTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) {
            }
        }
        return true;
    }

    /** The settings screen the panel opened is still up: Back out of it (and any screens under it) first. */
    private void backOut(String pkg, int tries) {
        if (tries == 0 || !pkg.equals(foreground)) {
            panel.openedPkg = null;
            panel.foreground = foreground;
            panel.open();
            return;
        }
        performGlobalAction(GLOBAL_ACTION_BACK);
        handler.postDelayed(() -> backOut(pkg, tries - 1), 220);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // The panel's own overlay reports as this package too; only real app windows count.
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getPackageName() == null) return;
        if (panel != null && panel.isOpen() && getPackageName().contentEquals(event.getPackageName())) return;
        foreground = event.getPackageName().toString();
    }

    @Override
    public void onInterrupt() {
    }
}
