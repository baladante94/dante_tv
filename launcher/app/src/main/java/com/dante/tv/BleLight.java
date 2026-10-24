package com.dante.tv;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;

import java.util.ArrayDeque;
import java.util.UUID;

/**
 * A Bluetooth LED controller of the ELK-BLEDOM family (the LotusLamp X app's), driven next to or instead of the Magic
 * Home one. It has its own microphone, so in Sound mode the controller listens to the soundbar itself and the TV
 * records nothing. Commands are 9-byte frames written without response to characteristic FFF3; the byte values follow
 * the elkbledom Home Assistant integration. Everything runs on the lights thread.
 */
final class BleLight {

    private static final UUID WRITE = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb");

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Handler net;
    private final Runnable onReady;
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic ch;
    private boolean busy, scanning;
    private volatile boolean connected;
    private Boolean soundOn;

    BleLight(Context ctx, SharedPreferences prefs, Handler net, Runnable onReady) {
        this.ctx = ctx;
        this.prefs = prefs;
        this.net = net;
        this.onReady = onReady;
    }

    boolean known() {
        return !prefs.getString("ble_mac", "").isEmpty();
    }

    boolean connected() {
        return connected;
    }

    String name() {
        return prefs.getString("ble_name", "");
    }

    private static boolean ours(String name) {
        return name != null && (name.startsWith("ELK-") || name.startsWith("MELK"));
    }

    // ---- commands ----------------------------------------------------------------------------------

    void power(boolean on) {
        byte p = (byte) (name().startsWith("MELK") ? 0x04 : 0x00);
        write(on ? frame(p, 0x04, 0xF0, 0x00, 0x01, 0xFF, 0x00) : frame(p, 0x04, 0x00, 0x00, 0x00, 0xFF, 0x00));
    }

    /** 0 to 100; it also dims the controller's own music effects. */
    void brightness(int percent) {
        boolean melk = name().startsWith("MELK");
        write(melk ? frame(0x04, 0x01, percent, 0x01, 0xFF, 0xFF, 0x00) : frame(0x00, 0x01, percent, 0xFF, 0x00, 0xFF, 0x00));
    }

    /** At full brightness: brightness() sets the level. */
    void colour(int c) {
        sound(false);
        write(frame(0x00, 0x05, 0x03, Color.red(c), Color.green(c), Color.blue(c), 0x00));
    }

    static final int EFFECTS = 8;

    /** The controller's own microphone and one of its 8 music effects (0 to 7), or back to plain colours. */
    void sound(boolean on) {
        if (soundOn != null && soundOn == on) return;
        soundOn = on;
        write(frame(0x04, 0x07, on ? 0x01 : 0x00, 0xFF, 0xFF, 0xFF, 0x00));
        if (on) {
            sensitivity();
            effect();
        }
    }

    static final int[] SENSITIVITY = {20, 40, 60, 80};

    /** 0 to 100; high values pick up the room too (a fan), so it starts at 40. */
    void sensitivity() {
        write(frame(0x04, 0x06, prefs.getInt("ble_sens", 40), 0xFF, 0xFF, 0xFF, 0x00));
    }

    void effect() {
        write(frame(0x05, 0x03, 0x80 + prefs.getInt("ble_effect", 0), 0x04, 0xFF, 0xFF, 0x00));
    }

    private static byte[] frame(int a, int b, int c, int d, int e, int f, int g) {
        return new byte[]{0x7E, (byte) a, (byte) b, (byte) c, (byte) d, (byte) e, (byte) f, (byte) g, (byte) 0xEF};
    }

    // ---- connection --------------------------------------------------------------------------------

    private BluetoothAdapter adapter() {
        BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
        return bm == null ? null : bm.getAdapter();
    }

    /** Keeps a connection to the remembered controller; Android reconnects by itself when it comes back in range. */
    void connect() {
        if (gatt != null || !known()) return;
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) return;
        try {
            BluetoothDevice d = a.getRemoteDevice(prefs.getString("ble_mac", ""));
            gatt = d.connectGatt(ctx, true, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            gatt = null;
        }
    }

    /** Looks for a controller for 6 s; done gets true when one was found and remembered. */
    void scan(java.util.function.Consumer<Boolean> done) {
        BluetoothAdapter a = adapter();
        BluetoothLeScanner s = a == null || !a.isEnabled() ? null : a.getBluetoothLeScanner();
        if (s == null || scanning) {
            if (done != null) done.accept(false);
            return;
        }
        scanning = true;
        ScanCallback found = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult r) {
                String name = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
                if (!ours(name)) return;
                net.post(() -> {
                    if (!scanning) return;
                    finish(s, this);
                    remember(r.getDevice().getAddress(), name);
                    if (done != null) done.accept(true);
                });
            }
        };
        try {
            s.startScan(found);
        } catch (Exception e) {
            scanning = false;
            if (done != null) done.accept(false);
            return;
        }
        net.postDelayed(() -> {
            if (!scanning) return;
            finish(s, found);
            if (done != null) done.accept(false);
        }, 6000);
    }

    private void finish(BluetoothLeScanner s, ScanCallback cb) {
        scanning = false;
        try {
            s.stopScan(cb);
        } catch (Exception ignored) {
        }
    }

    private void remember(String mac, String name) {
        if (!mac.equals(prefs.getString("ble_mac", ""))) {
            if (gatt != null) gatt.close();
            gatt = null;
            ch = null;
            connected = false;
        }
        prefs.edit().putString("ble_mac", mac).putString("ble_name", name).apply();
        connect();
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            net.post(() -> {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices();
                } else {
                    connected = false;
                    ch = null;
                    busy = false;
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            net.post(() -> {
                for (BluetoothGattService s : g.getServices()) {
                    BluetoothGattCharacteristic c = s.getCharacteristic(WRITE);
                    if (c != null) {
                        c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                        ch = c;
                    }
                }
                connected = ch != null;
                if (connected) {
                    soundOn = null;
                    queue.clear();
                    onReady.run();
                }
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            net.post(() -> {
                net.removeCallbacks(unstick);
                busy = false;
                pump();
            });
        }
    };

    /** One write in flight at a time; a newer colour replaces one still waiting, so Rainbow never lags behind. */
    private void write(byte[] b) {
        if (b[2] == 0x05 || b[2] == 0x01 || b[2] == 0x06) queue.removeIf(q -> q[2] == b[2]);
        queue.add(b);
        pump();
    }

    private void pump() {
        if (busy || ch == null || gatt == null || queue.isEmpty()) return;
        byte[] next = queue.poll();
        ch.setValue(next);
        try {
            busy = gatt.writeCharacteristic(ch);
        } catch (Exception e) {
            busy = false;
        }
        if (busy) {
            net.postDelayed(unstick, 500);   // a lost write callback must not stop the queue for good
        } else {
            queue.addFirst(next);
            net.postDelayed(this::pump, 50);
        }
    }

    private final Runnable unstick = () -> {
        busy = false;
        pump();
    };
}
