package com.dante.tv;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The remote's Settings button, the way Google TV's own panel worked: a frosted panel slides in from the right
 * over whatever is playing (an accessibility overlay, so the app underneath is not paused). Wi-Fi, Bluetooth and
 * Inputs open as lists inside the panel, like the menu rail at Home; Back returns to the main list.
 */
final class SidePanel {

    private static final int[] SLEEP_STEPS = {0, 15, 30, 60, 90};
    private static final int MAIN = 0, WIFI = 1, BLUETOOTH = 2, INPUTS = 3, MEMC = 4, LIGHTS = 5;

    private final Context ctx;
    private final WindowManager wm;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout window;
    private View panel, scrim;
    private LinearLayout rows;
    private TextView title;
    private int page = MAIN;
    private BroadcastReceiver wifiReceiver;
    private BluetoothA2dp a2dp;
    /** Package of the settings screen the panel last opened, so the next Settings press can back out of it. */
    String openedPkg;
    /** The app on screen when the panel opened (set by InputKeyService), which Optimize leaves running. */
    String foreground;
    private String[] optimizeResult;

    SidePanel(Context ctx) {
        this.ctx = ctx;
        this.wm = ctx.getSystemService(WindowManager.class);
        this.density = ctx.getResources().getDisplayMetrics().density;
    }

    boolean isOpen() {
        return window != null;
    }

    void toggle() {
        if (window != null) close();
        else open();
    }

    void open() {
        open(0);
    }

    private void open(int focus) {
        if (window != null) return;
        window = new FrameLayout(ctx) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                int code = e.getKeyCode();
                boolean left = code == KeyEvent.KEYCODE_DPAD_LEFT;
                if (code == KeyEvent.KEYCODE_BACK || left && (page != MAIN || !hasFocusOnRow())) {
                    if (e.getAction() == KeyEvent.ACTION_UP) {
                        if (page != MAIN) showMain(page == MEMC ? 4 : page == LIGHTS ? lightsRow() : page - 1);
                        else close();
                    }
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        LayoutInflater.from(ctx).inflate(R.layout.overlay_side_panel, window, true);
        panel = window.findViewById(R.id.sidePanel);
        scrim = window.findViewById(R.id.sideScrim);
        rows = window.findViewById(R.id.sideRows);
        title = window.findViewById(R.id.sideTitle);
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (bt != null && a2dp == null) {
            bt.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    a2dp = (BluetoothA2dp) proxy;
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    a2dp = null;
                }
            }, BluetoothProfile.A2DP);
        }
        showMain(focus);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        wm.addView(window, lp);
        panel.setTranslationX(400 * density);
        panel.animate().translationX(0f).setDuration(240).setInterpolator(new DecelerateInterpolator(1.8f)).start();
        scrim.animate().alpha(1f).setDuration(240).start();
    }

    void close() {
        if (window == null) return;
        stopWifiUpdates();
        handler.removeCallbacksAndMessages(null);
        final FrameLayout gone = window;
        window = null;
        page = MAIN;
        panel.animate().translationX(400 * density).setDuration(180).start();
        scrim.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            try {
                wm.removeView(gone);
            } catch (Exception ignored) {
            }
        }).start();
    }

    // ---- main page ---------------------------------------------------------------------------------

    private void showMain(int focus) {
        stopWifiUpdates();
        handler.removeCallbacksAndMessages(null);
        page = MAIN;
        title.setText(R.string.side_title);
        rows.removeAllViews();
        addRow(R.drawable.ic_wifi, ctx.getString(R.string.wifi), wifiName(), v -> showWifi());
        addRow(R.drawable.ic_bluetooth, ctx.getString(R.string.bluetooth), bluetoothSummary(), v -> showBluetooth());
        addRow(R.drawable.ic_input, ctx.getString(R.string.inputs), null, v -> showInputs());
        addRow(R.drawable.ic_picture, ctx.getString(R.string.side_picture), null, v -> go(new Intent(Settings.ACTION_DISPLAY_SETTINGS)));
        // The TV's live value would need its Picture screens opened; this is the last value set from here.
        if (hasMemc()) addRow(R.drawable.ic_picture, "MEMC", memcSet(), v -> showMemc(0));
        // Only where a Magic Home controller answers on this network (Lights looks at start-up and on Wi-Fi changes).
        if (Lights.get(ctx).reachable()) addRow(R.drawable.ic_bulb, ctx.getString(R.string.lights), lightsMode(), v -> showLights(0));
        // The TV's own Sound screen; the general sound link opens Display & Sound at picture and scaling.
        addRow(R.drawable.ic_sound, ctx.getString(R.string.side_sound), null, v -> {
            Intent sound = new Intent("com.android.tv.action.VIEW_SOUND_SETTINGS");
            go(sound.resolveActivity(ctx.getPackageManager()) != null ? sound : new Intent(Settings.ACTION_SOUND_SETTINGS));
        });
        View sleep = addRow(R.drawable.ic_moon, ctx.getString(R.string.sleep_timer), sleepText(), null);
        sleep.setOnClickListener(v -> {
            int left = SleepReceiver.minutesLeft(ctx), next = 0;
            for (int step : SLEEP_STEPS) {
                if (step > left) {
                    next = step;
                    break;
                }
            }
            SleepReceiver.set(ctx, next);
            ((TextView) sleep.findViewById(R.id.sideStatus)).setText(sleepText());
        });
        sleep.setOnLongClickListener(v -> {
            close();
            if (!OptimizeService.sleepNow()) Toast.makeText(ctx, R.string.sleep_unavailable, Toast.LENGTH_LONG).show();
            return true;
        });
        View opt = addRow(R.drawable.ic_optimize, ctx.getString(R.string.nav_optimize), null, v -> optimize());
        if (optimizeResult != null) {
            ((TextView) opt.findViewById(R.id.sideLabel)).setText(optimizeResult[0]);
            ((TextView) opt.findViewById(R.id.sideStatus)).setText(optimizeResult[1]);
            optimizeResult = null;
        }
        addRow(R.drawable.ic_settings, ctx.getString(R.string.side_all_settings), null, v -> go(new Intent(Settings.ACTION_SETTINGS)));
        focusRow(focus);
    }

    // ---- Optimize ----------------------------------------------------------------------------------

    

    /** Force-stops background apps right from the panel, then comes back with how much memory it freed. */
    private void optimize() {
        if (!OptimizeService.available()) {
            Toast.makeText(ctx, R.string.optimize_enable, Toast.LENGTH_LONG).show();
            go(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        Map<String, String> apps = new java.util.LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!apps.containsKey(pkg)) apps.put(pkg, String.valueOf(ri.loadLabel(ctx.getPackageManager())));
            }
        }
        Map<String, String> targets = OptimizeService.targets(ctx, apps,
                foreground == null ? "" : foreground, Config.load(ctx).liveTvPackage);
        android.app.ActivityManager am = ctx.getSystemService(android.app.ActivityManager.class);
        android.app.ActivityManager.MemoryInfo before = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(before);
        if (targets.isEmpty()) {
            showOptimized(0, before);
            return;
        }
        close();
        OptimizeService.run(new ArrayList<>(targets.keySet()), new ArrayList<>(targets.values()), false,
                stopped -> handler.postDelayed(() -> {
                    android.app.ActivityManager.MemoryInfo after = new android.app.ActivityManager.MemoryInfo();
                    am.getMemoryInfo(after);
                    showOptimized(after.availMem - before.availMem, after);
                }, 900));
    }

    private void showOptimized(long freed, android.app.ActivityManager.MemoryInfo now) {
        optimizeResult = new String[]{
                freed >= 20L << 20
                        ? ctx.getString(R.string.optimize_freed, android.text.format.Formatter.formatShortFileSize(ctx, freed))
                        : ctx.getString(R.string.optimize_nothing),
                ctx.getString(R.string.optimize_free_now, android.text.format.Formatter.formatShortFileSize(ctx, now.availMem),
                        android.text.format.Formatter.formatShortFileSize(ctx, now.totalMem))};
        int row = lightsRow() + (Lights.get(ctx).reachable() ? 3 : 2);
        if (window != null) showMain(row);
        else open(row);
    }

    // ---- Lights ------------------------------------------------------------------------------------

    private static final String[] COLOUR_NAMES = {"Blue", "Rainbow", "Purple", "Pink", "Red", "Amber", "Warm white", "Green", "Cyan"};

    private int lightsRow() {
        return hasMemc() ? 5 : 4;
    }

    private String lightsMode() {
        switch (Lights.get(ctx).mode()) {
            case Lights.OFF: return ctx.getString(R.string.lights_off);
            case Lights.STATIC: return ctx.getString(R.string.lights_static);
            case Lights.SOUND: return ctx.getString(R.string.lights_sound);
            default: return null;
        }
    }

    private void showLights(int focus) {
        Lights lights = Lights.get(ctx);
        openPage(LIGHTS, R.string.lights);
        String mode = lightsMode();
        addRow(R.drawable.ic_bulb, ctx.getString(R.string.lights_mode), mode == null ? ctx.getString(R.string.lights_off) : mode, v -> {
            int next = lights.mode() == Lights.STATIC ? Lights.SOUND : lights.mode() == Lights.SOUND ? Lights.OFF : Lights.STATIC;
            if (next == Lights.SOUND && !Lights.audioAllowed(ctx)) Toast.makeText(ctx, R.string.lights_mic, Toast.LENGTH_LONG).show();
            lights.setMode(next);
            showLights(0);
        });
        int at = 0;
        for (int i = 0; i < Lights.COLOURS.length; i++) if (Lights.COLOURS[i] == lights.colour()) at = i;
        final int colourAt = at;
        View colour = addRow(R.drawable.ic_bulb, ctx.getString(R.string.lights_colour), COLOUR_NAMES[at], v -> {
            lights.setColour(Lights.COLOURS[(colourAt + 1) % Lights.COLOURS.length]);
            showLights(1);
        });
        if (lights.colour() != Lights.RAINBOW) ((ImageView) colour.findViewById(R.id.sideIcon)).setColorFilter(lights.colour());
        int b = 0;
        for (int i = 0; i < Lights.BRIGHTNESS.length; i++) if (Lights.BRIGHTNESS[i] == lights.brightness()) b = i;
        final int brightAt = b;
        addRow(R.drawable.ic_bulb, ctx.getString(R.string.lights_brightness), lights.brightness() + "%", v -> {
            lights.setBrightness(Lights.BRIGHTNESS[(brightAt + 1) % Lights.BRIGHTNESS.length]);
            showLights(2);
        });
        View find = addRow(R.drawable.ic_wifi, ctx.getString(R.string.lights_find), lightsAddress(), null);
        find.setOnClickListener(v -> findLights(find));
        TextView note = new TextView(ctx);
        note.setText(R.string.lights_note);
        note.setTextColor(0x99FFFFFF);
        note.setTextSize(12);
        note.setPadding((int) (16 * density), (int) (10 * density), (int) (16 * density), 0);
        rows.addView(note);
        focusRow(focus);
        if (!lights.found()) findLights(find);
    }

    private String lightsAddress() {
        String ip = ctx.getSharedPreferences("lights", Context.MODE_PRIVATE).getString("ip", "");
        if (ip.isEmpty()) return ctx.getString(R.string.lights_not_found);
        return Lights.get(ctx).reachable() ? ip : ctx.getString(R.string.lights_other_wifi);
    }

    private void findLights(View row) {
        TextView st = row.findViewById(R.id.sideStatus);
        st.setText(R.string.lights_searching);
        Lights.get(ctx).find(ok -> handler.post(() -> {
            if (page == LIGHTS) st.setText(lightsAddress());
        }));
    }

    // ---- MEMC --------------------------------------------------------------------------------------

    /** MediaTek's Picture settings, the only place MEMC can be changed; other TVs don't get the row. */
    private boolean hasMemc() {
        try {
            ctx.getPackageManager().getPackageInfo("com.mediatek.tv.settings", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The value last set from here. The TV keeps MEMC per picture mode, so a new mode may differ. */
    private String memcSet() {
        return ctx.getSharedPreferences("side_panel", Context.MODE_PRIVATE).getString("memc", null);
    }

    private void showMemc(int focus) {
        page = MEMC;
        title.setText("MEMC");
        rows.removeAllViews();
        String set = memcSet();
        for (String v : OptimizeService.MEMC) {
            addRow(R.drawable.ic_picture, v, v.equals(set) ? "\u2713" : null,
                    x -> applyMemc(v));
        }
        TextView note = new TextView(ctx);
        note.setText(R.string.memc_note_last);
        note.setTextColor(0x99FFFFFF);
        note.setTextSize(12);
        note.setPadding((int) (16 * density), (int) (10 * density), (int) (16 * density), 0);
        rows.addView(note);
        focusRow(focus);
    }

    /**
     * The TV's Picture panel opens under this one while the change runs, so the video keeps playing beside it.
     * This panel gives up focus meanwhile: the service's D-pad presses have to reach the Picture panel.
     */
    private void applyMemc(String value) {
        if (!OptimizeService.available()) {
            Toast.makeText(ctx, R.string.optimize_enable, Toast.LENGTH_LONG).show();
            go(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        int at = java.util.Arrays.asList(OptimizeService.MEMC).indexOf(value);
        boolean started = OptimizeService.setMemc(value, ok -> handler.post(() -> {
            if (ok == 1) ctx.getSharedPreferences("side_panel", Context.MODE_PRIVATE).edit().putString("memc", value).apply();
            if (window == null) return;
            setFocusable(true);
            if (ok == 1) {
                showMemc(at);
            } else {
                Toast.makeText(ctx, R.string.memc_failed, Toast.LENGTH_LONG).show();
                go(new Intent(Settings.ACTION_DISPLAY_SETTINGS));
            }
        }));
        if (!started) return;
        setFocusable(false);
        title.setText(ctx.getString(R.string.memc_changing, value));
        View row = rows.getChildAt(at);
        ((TextView) row.findViewById(R.id.sideStatus)).setText("");
        android.widget.ProgressBar bar = new android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (4 * density));
        lp.setMargins((int) (16 * density), 0, (int) (16 * density), (int) (8 * density));
        rows.addView(bar, at + 1, lp);
    }

    private void setFocusable(boolean focusable) {
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) window.getLayoutParams();
        if (focusable) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        wm.updateViewLayout(window, lp);
    }

    private void openPage(int which, int titleRes) {
        page = which;
        title.setText(titleRes);
        rows.removeAllViews();
    }

    // ---- Wi-Fi -------------------------------------------------------------------------------------

    private WifiManager wifi() {
        return (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    private void showWifi() {
        WifiManager w = wifi();
        if (w == null) {
            go(new Intent(Settings.ACTION_WIFI_SETTINGS));
            return;
        }
        openPage(WIFI, R.string.wifi);
        fillWifi(w);
        focusRow(0);
        // A fresh scan lands a few seconds later, and a join changes the statuses: redraw, keeping focus.
        wifiReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (page != WIFI) return;
                int at = focusedRow();
                fillWifi(w);
                focusRow(at);
            }
        };
        IntentFilter f = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        ctx.registerReceiver(wifiReceiver, f);
        w.startScan();
    }

    private void stopWifiUpdates() {
        if (wifiReceiver == null) return;
        try {
            ctx.unregisterReceiver(wifiReceiver);
        } catch (Exception ignored) {
        }
        wifiReceiver = null;
    }

    private void fillWifi(WifiManager w) {
        rows.removeAllViews();
        WifiInfo info = w.getConnectionInfo();
        String current = info != null ? MainActivity.unquote(info.getSSID()) : "";
        Map<String, Integer> saved = new HashMap<>();
        try {
            List<WifiConfiguration> configs = w.getConfiguredNetworks();
            if (configs != null) for (WifiConfiguration c : configs) saved.put(MainActivity.unquote(c.SSID), c.networkId);
        } catch (SecurityException ignored) {
        }
        Map<String, ScanResult> best = new HashMap<>();
        for (ScanResult r : w.getScanResults()) {
            if (r.SSID == null || r.SSID.isEmpty()) continue;
            ScanResult b = best.get(r.SSID);
            if (b == null || r.level > b.level) best.put(r.SSID, r);
        }
        List<ScanResult> list = new ArrayList<>(best.values());
        Collections.sort(list, (a, b) -> {
            if (a.SSID.equals(current) != b.SSID.equals(current)) return a.SSID.equals(current) ? -1 : 1;
            return Integer.compare(b.level, a.level);
        });
        if (list.isEmpty()) addRow(R.drawable.ic_wifi, ctx.getString(R.string.wifi_searching), null, v -> { });
        for (ScanResult r : list) {
            boolean here = r.SSID.equals(current);
            Integer netId = saved.get(r.SSID);
            String status = here ? ctx.getString(R.string.connected)
                    : netId != null ? ctx.getString(R.string.wifi_saved)
                    : MainActivity.wifiSecurity(r) == 0 ? ctx.getString(R.string.wifi_open) : ctx.getString(R.string.wifi_secured);
            addRow(R.drawable.ic_wifi, r.SSID, status, v -> joinWifi(w, r, netId, here));
        }
        addSeparator();
        addRow(R.drawable.ic_settings, ctx.getString(R.string.wifi_settings), null, v -> go(new Intent(Settings.ACTION_WIFI_SETTINGS)));
    }

    private void joinWifi(WifiManager w, ScanResult r, Integer savedId, boolean here) {
        if (here) return;
        int security = MainActivity.wifiSecurity(r);
        if (security == 4) {
            Toast.makeText(ctx, ctx.getString(R.string.wifi_enterprise, r.SSID), Toast.LENGTH_SHORT).show();
            go(new Intent(Settings.ACTION_WIFI_SETTINGS));
            return;
        }
        if (savedId != null && w.enableNetwork(savedId, true)) {
            Toast.makeText(ctx, ctx.getString(R.string.wifi_connecting, r.SSID), Toast.LENGTH_SHORT).show();
            return;
        }
        if (security == 0) {
            WifiConfiguration c = new WifiConfiguration();
            c.SSID = "\"" + r.SSID + "\"";
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            int id = w.addNetwork(c);
            boolean ok = id != -1 && w.enableNetwork(id, true);
            Toast.makeText(ctx, ok ? ctx.getString(R.string.wifi_connecting, r.SSID) : ctx.getString(R.string.wifi_failed, r.SSID),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        // An overlay gets no on-screen keyboard, so Home asks for the password.
        close();
        ctx.startActivity(new Intent(ctx, MainActivity.class).putExtra(MainActivity.EXTRA_WIFI_JOIN, r.SSID)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private String wifiName() {
        try {
            WifiInfo info = wifi() != null ? wifi().getConnectionInfo() : null;
            String ssid = info != null ? info.getSSID() : null;
            if (ssid == null || ssid.contains("unknown")) return null;
            return MainActivity.unquote(ssid);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Bluetooth ---------------------------------------------------------------------------------

    /** Headphones and speakers only: the remote and game pads are paired too. */
    private static List<BluetoothDevice> audioDevices(BluetoothAdapter bt) {
        List<BluetoothDevice> out = new ArrayList<>();
        Set<BluetoothDevice> bonded = bt != null ? bt.getBondedDevices() : null;
        if (bonded == null) return out;
        for (BluetoothDevice d : bonded) {
            if (d.getBluetoothClass() != null && d.getBluetoothClass().getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO) {
                out.add(d);
            }
        }
        return out;
    }

    private String bluetoothSummary() {
        List<BluetoothDevice> devices = audioDevices(BluetoothAdapter.getDefaultAdapter());
        if (a2dp != null) {
            for (BluetoothDevice d : a2dp.getConnectedDevices()) {
                if (d.getName() != null) return d.getName();
            }
        }
        return devices.isEmpty() ? ctx.getString(R.string.bt_none) : ctx.getString(R.string.bt_paired, devices.size());
    }

    private void showBluetooth() {
        openPage(BLUETOOTH, R.string.bluetooth);
        fillBluetooth();
        focusRow(0);
    }

    private void fillBluetooth() {
        rows.removeAllViews();
        List<BluetoothDevice> connected = a2dp != null ? a2dp.getConnectedDevices() : Collections.emptyList();
        for (BluetoothDevice d : audioDevices(BluetoothAdapter.getDefaultAdapter())) {
            String name = d.getName() != null ? d.getName() : d.getAddress();
            boolean on = connected.contains(d);
            addRow(R.drawable.ic_bluetooth, name, on ? ctx.getString(R.string.connected) : null, v -> connectBluetooth(d, name, on));
        }
        if (rows.getChildCount() > 0) addSeparator();
        addRow(R.drawable.ic_settings, ctx.getString(R.string.bt_pair_new), null, v -> go(bluetoothPairing()));
    }

    /** BluetoothA2dp.connect is not public API; Android allows it for apps targeting 28. Pairing screen if refused. */
    private void connectBluetooth(BluetoothDevice d, String name, boolean on) {
        if (on) return;
        boolean asked = false;
        try {
            if (a2dp != null) {
                asked = Boolean.TRUE.equals(BluetoothA2dp.class.getMethod("connect", BluetoothDevice.class).invoke(a2dp, d));
            }
        } catch (Exception ignored) {
        }
        if (!asked) {
            go(bluetoothPairing());
            return;
        }
        Toast.makeText(ctx, ctx.getString(R.string.bt_connecting, name), Toast.LENGTH_SHORT).show();
        for (int s = 2; s <= 8; s += 3) {
            handler.postDelayed(() -> {
                if (page != BLUETOOTH) return;
                int at = focusedRow();
                fillBluetooth();
                focusRow(at);
            }, s * 1000L);
        }
    }

    /** Google TV pairs headphones from its Accessories screen (CONNECT_INPUT); others have Bluetooth settings. */
    private Intent bluetoothPairing() {
        Intent pair = new Intent("com.google.android.intent.action.CONNECT_INPUT");
        if (pair.resolveActivity(ctx.getPackageManager()) != null) return pair;
        return new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
    }

    // ---- Inputs ------------------------------------------------------------------------------------

    private void showInputs() {
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) {
            go(new Intent("com.android.tv.action.VIEW_INPUTS"));
            return;
        }
        openPage(INPUTS, R.string.inputs);
        List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
        Collections.sort(inputs, (a, b) -> {
            boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
            if (ha != hb) return ha ? -1 : 1;
            return String.valueOf(a.loadLabel(ctx)).compareToIgnoreCase(String.valueOf(b.loadLabel(ctx)));
        });
        // Some TVs report every HDMI port as connected whether or not anything is plugged in: drop the status then.
        int ports = 0, plugged = 0;
        for (TvInputInfo info : inputs) {
            if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
            ports++;
            if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) plugged++;
        }
        boolean stateBogus = ports > 1 && plugged == ports;
        String tunerId = null;
        for (TvInputInfo info : inputs) {
            if (info.getType() == TvInputInfo.TYPE_TUNER && tunerId == null
                    && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                tunerId = info.getId();
            }
            if (!info.isPassthroughInput() || info.isHidden(ctx)) continue;
            CharSequence l = info.loadLabel(ctx);
            String label = l == null ? info.getId() : l.toString();
            boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
            if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
            CharSequence custom = info.loadCustomLabel(ctx);
            if (custom != null && custom.length() > 0 && !custom.toString().equals(label)) label = custom + " · " + label;
            int state = hdmi && stateBogus ? -1 : tim.getInputState(info.getId());
            String status = state == TvInputManager.INPUT_STATE_CONNECTED ? ctx.getString(R.string.connected) : null;
            String id = info.getId();
            addRow(hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, status,
                    v -> go(channel(TvContract.buildChannelUriForPassthroughInput(id))));
        }
        if (tunerId != null) {
            Intent live = channel(TvContract.buildChannelUriForPassthroughInput(tunerId));
            if (live.resolveActivity(ctx.getPackageManager()) != null) {
                if (rows.getChildCount() > 0) addSeparator();
                addRow(R.drawable.ic_livetv, ctx.getString(R.string.live_tv), null, v -> go(live));
            }
        }
        if (rows.getChildCount() == 0) {
            go(new Intent("com.android.tv.action.VIEW_INPUTS"));
            return;
        }
        focusRow(0);
    }

    private static Intent channel(Uri uri) {
        return new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel");
    }

    // ---- rows --------------------------------------------------------------------------------------

    private boolean hasFocusOnRow() {
        return window != null && window.findFocus() != null;
    }

    private int focusedRow() {
        for (int i = 0; i < rows.getChildCount(); i++) if (rows.getChildAt(i).hasFocus()) return i;
        return 0;
    }

    private void focusRow(int at) {
        for (int i = Math.min(at, rows.getChildCount() - 1); i >= 0; i--) {
            View row = rows.getChildAt(i);
            if (row.isFocusable()) {
                row.requestFocus();
                return;
            }
        }
    }

    private View addRow(int icon, String label, String status, View.OnClickListener click) {
        View row = LayoutInflater.from(ctx).inflate(R.layout.item_side_row, rows, false);
        ((ImageView) row.findViewById(R.id.sideIcon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.sideLabel)).setText(label);
        TextView st = row.findViewById(R.id.sideStatus);
        st.setText(status == null ? "" : status);
        if (click != null) row.setOnClickListener(click);
        row.setOnFocusChangeListener((v, focused) -> v.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                .setDuration(140).start());
        rows.addView(row);
        return row;
    }

    private void addSeparator() {
        View line = new View(ctx);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void go(Intent i) {
        close();
        ResolveInfo ri = ctx.getPackageManager().resolveActivity(i, 0);
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            String pkg = ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : null;
            // Only settings screens get backed out of on the next Settings press, never an input or Live TV.
            openedPkg = pkg != null && !Intent.ACTION_VIEW.equals(i.getAction()) ? pkg : null;
        } catch (Exception e) {
            Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private String sleepText() {
        int left = SleepReceiver.minutesLeft(ctx);
        return left > 0 ? ctx.getString(R.string.sleep_in, left) : ctx.getString(R.string.sleep_off);
    }
}
