package com.dante.tv;

import android.content.Context;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The built-in WireGuard tunnel, one standard config file per place in the app's files folder (vpn-Japan.conf and so
 * on, kept on the TV only). The engine loads on first use, so it costs nothing while off. Stays off after a restart of
 * the TV or the app.
 */
final class Vpn {

    interface Done {
        void on(String current, boolean failed);
    }

    private static final long CONNECT_MS = 8000;
    private static final String PREFIX = "vpn-", SUFFIX = ".conf";
    private static Vpn instance;

    private final Context ctx;
    private final Tunnel tunnel = new Tunnel() {
        @Override
        public String getName() {
            return "dante";
        }

        @Override
        public void onStateChange(State state) {
            if (state != State.UP) current = null;
        }
    };
    private GoBackend backend;
    private volatile String current;
    private volatile boolean busy;

    private Vpn(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    static synchronized Vpn get(Context ctx) {
        if (instance == null) instance = new Vpn(ctx);
        return instance;
    }

    /** The places there are config files for, A to Z. */
    List<String> places() {
        List<String> names = new ArrayList<>();
        String[] files = ctx.getExternalFilesDir(null).list();
        if (files != null) {
            for (String f : files) {
                if (f.startsWith(PREFIX) && f.endsWith(SUFFIX)) names.add(f.substring(PREFIX.length(), f.length() - SUFFIX.length()));
            }
        }
        Collections.sort(names);
        return names;
    }

    /** The place connected to, or null when off. */
    String current() {
        return current;
    }

    boolean busy() {
        return busy;
    }

    /** Connects to a place, or turns off for null. Off the main thread; done runs on it. Going up only counts once the server answers, or the TV loses its internet. */
    void set(String place, Done done) {
        if (busy) return;
        busy = true;
        new Thread(() -> {
            boolean failed = false;
            try {
                if (backend == null) backend = new GoBackend(ctx);
                if (place != null) {
                    // This app stays out of the tunnel: the Wi-Fi strip is on the home network, not behind the server.
                    String text = new String(Files.readAllBytes(new File(ctx.getExternalFilesDir(null), PREFIX + place + SUFFIX).toPath()),
                            StandardCharsets.UTF_8).replace("[Interface]", "[Interface]\nExcludedApplications = " + ctx.getPackageName());
                    com.wireguard.config.Config config = com.wireguard.config.Config.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
                    backend.setState(tunnel, Tunnel.State.UP, config);
                    current = place;
                    failed = !answered();
                    if (failed) backend.setState(tunnel, Tunnel.State.DOWN, null);
                } else {
                    backend.setState(tunnel, Tunnel.State.DOWN, null);
                }
            } catch (Exception e) {
                android.util.Log.w("Vpn", "connect failed", e);
                failed = true;
            }
            busy = false;
            boolean nowFailed = failed;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> done.on(current, nowFailed));
        }, "vpn").start();
    }

    private boolean answered() throws Exception {
        long end = System.currentTimeMillis() + CONNECT_MS;
        while (System.currentTimeMillis() < end) {
            if (backend.getStatistics(tunnel).totalRx() > 0) return true;
            Thread.sleep(250);
        }
        return false;
    }
}
