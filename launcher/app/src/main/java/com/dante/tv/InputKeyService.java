package com.dante.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Maps the remote's Input button to the system input switcher, and its Settings button to the side panel
 * (SidePanel), from any app. Remotes such as the Chromecast voice
 * remote send KEYCODE_TV for it, others (KTC and Kalley Realtek Google TV remotes) KEYCODE_TV_INPUT; on Google TV
 * the stock launcher handles both, so with the stock launcher disabled the button does nothing.
 * Opt-in: INPUT_KEY_SERVICE=1 build-and-install.sh (or -InputKeyService on Windows) enables it.
 */
public class InputKeyService extends AccessibilityService {

    private static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";

    private SidePanel panel;
    private String foreground;
    /** Other launchers' home screens (HomeGuard.otherHomes), looked up on the first window change. */
    private java.util.Set<String> otherHomes;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * Home at every power-on (CleanYourTV's power_on_home_always): this Philips wakes on its last source, an HDMI
     * input or an app. Shortly after the screen comes on Dante TV is brought to the front, unless a key was pressed
     * meanwhile (someone already has the TV) or an HDMI device is awake: a Fire TV woken by its own remote turns
     * the TV on over HDMI-CEC and should keep the screen.
     */
    private static final long POWER_ON_DELAY_MS = 800, POWER_ON_WINDOW_MS = 4000;
    private long powerOnUntil;

    @Override
    protected void onServiceConnected() {
        Lights.get(this).start();
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context c, Intent i) {
                powerOnUntil = android.os.SystemClock.uptimeMillis() + POWER_ON_WINDOW_MS;
                handler.postDelayed(InputKeyService.this::homeAtPowerOn, POWER_ON_DELAY_MS);
            }
        }, new android.content.IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    private void homeAtPowerOn() {
        if (android.os.SystemClock.uptimeMillis() > powerOnUntil || getPackageName().equals(foreground)) return;
        if (hdmiDeviceAwake()) return;
        HomeGuard.openHome(this, false);
    }

    /** An HDMI-CEC device (it has an input of its own under its port) that reports itself on, not in standby. */
    private boolean hdmiDeviceAwake() {
        android.media.tv.TvInputManager tim = (android.media.tv.TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim == null) return false;
        for (android.media.tv.TvInputInfo info : tim.getTvInputList()) {
            if (info.getParentId() != null && info.getType() == android.media.tv.TvInputInfo.TYPE_HDMI
                    && tim.getInputState(info.getId()) == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // The remote's Settings button: Dante TV's side panel, over any app (the stock Google TV home that
        // drew the original one is disabled). This Philips/Skyworth remote sends it as KEYCODE_NOTIFICATION (83,
        // scancode 240), as TCL Google TV remotes do; standard remotes send KEYCODE_SETTINGS.
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_POWER && code != KeyEvent.KEYCODE_TV_POWER && code != KeyEvent.KEYCODE_WAKEUP) {
            powerOnUntil = 0;   // the remote is in use: leave the screen as it is
        }
        TitleFollowUp.onKey(this, event, foreground);
        if (code == KeyEvent.KEYCODE_HOME) {
            if (panel != null && panel.isOpen()) panel.close();
            // A TV update made the stock launcher the default again: Home still brings Dante TV.
            if (HomeGuard.isDefaultHome(this)) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) HomeGuard.openHome(this, false);
            return true;
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
        if (code != KeyEvent.KEYCODE_TV && code != KeyEvent.KEYCODE_TV_INPUT) return false;
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
        TitleFollowUp.onWindow(foreground);
        // Safety net for the Home guard: the stock home screen came up some other way than the Home key (an app sent
        // the TV home, the firmware turned the stock launcher back on). Only while Dante TV is not the default.
        CharSequence cls = event.getClassName();
        if (cls != null && !getPackageName().equals(foreground)) {
            if (otherHomes == null) otherHomes = HomeGuard.otherHomes(this);
            if (otherHomes.contains(cls.toString()) && !HomeGuard.isDefaultHome(this)) HomeGuard.openHome(this, false);
        }
    }

    @Override
    public void onInterrupt() {
    }
}
