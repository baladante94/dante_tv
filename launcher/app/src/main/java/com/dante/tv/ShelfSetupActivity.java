package com.dante.tv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Locale;

/**
 * Lets the owner add their own TMDB key without typing it on a remote: a QR code opens a page served by the TV
 * itself, only while this screen is open and only at a random address, where the key is pasted from a phone.
 * The TV checks it with TMDB before keeping it.
 */
public class ShelfSetupActivity extends Activity {

    private ServerSocket server;
    private String secret;
    private TextView status;
    private View remove;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shelf_setup);
        status = findViewById(R.id.setupStatus);
        remove = findViewById(R.id.setupRemove);
        ((TextView) findViewById(R.id.setupRegion)).setText(getString(R.string.shelf_region,
                ShelfKey.regionName(ShelfKey.region(this, Config.load(this).region))));
        findViewById(R.id.setupDone).setOnClickListener(v -> finish());
        remove.setOnClickListener(v -> {
            ShelfKey.clear(this);
            Trending.reset(this);
            LauncherSettingsActivity.version++;
            setResult(RESULT_OK);
            showState();
        });
        showState();
        findViewById(R.id.setupDone).requestFocus();
        startServer();
    }

    private void showState() {
        boolean set = !ShelfKey.token(this).isEmpty();
        status.setText(set ? R.string.shelf_key_set : R.string.shelf_key_none);
        status.setTextColor(set ? 0xFF8FD19E : 0xFFFFC46B);
        remove.setVisibility(set ? View.VISIBLE : View.GONE);
    }

    private void startServer() {
        byte[] b = new byte[4];
        new SecureRandom().nextBytes(b);
        secret = String.format(Locale.ROOT, "%02x%02x%02x%02x", b[0], b[1], b[2], b[3]);
        String ip = address();
        try {
            server = new ServerSocket(0);
        } catch (Exception e) {
            ((TextView) findViewById(R.id.setupUrl)).setText(R.string.not_available);
            return;
        }
        String url = "http://" + ip + ":" + server.getLocalPort() + "/" + secret;
        ((TextView) findViewById(R.id.setupUrl)).setText(url);
        ((ImageView) findViewById(R.id.setupQr)).setImageBitmap(qr(url, 512));
        // Each connection on its own thread: phone browsers open a spare connection first and send nothing on it,
        // which would hold up the real request behind it.
        new Thread(() -> {
            while (server != null && !server.isClosed()) {
                try {
                    Socket s = server.accept();
                    new Thread(() -> {
                        try (Socket c = s) {
                            handle(c);
                        } catch (Exception ignored) {
                        }
                    }, "shelf-setup-conn").start();
                } catch (Exception ignored) {
                }
            }
        }, "shelf-setup").start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        server = null;
    }

    /** One request: the form on GET, the key on POST. Anything not at the secret address is refused. */
    private void handle(Socket s) throws Exception {
        s.setSoTimeout(5_000);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        String request = in.readLine();
        if (request == null) return;
        int length = 0;
        for (String h; (h = in.readLine()) != null && !h.isEmpty(); ) {
            if (h.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(h.substring(15).trim());
        }
        String[] parts = request.split(" ");
        if (parts.length < 2 || !parts[1].startsWith("/" + secret)) {
            reply(s, "404 Not Found", page("Not found", "This setup page is only open while the TV shows it."));
            return;
        }
        if (!"POST".equals(parts[0])) {
            reply(s, "200 OK", form(""));
            return;
        }
        char[] body = new char[Math.min(length, 4096)];
        int read = 0;
        while (read < body.length) {
            int n = in.read(body, read, body.length - read);
            if (n < 0) break;
            read += n;
        }
        String token = "";
        for (String kv : new String(body, 0, read).split("&")) {
            if (kv.startsWith("token=")) token = URLDecoder.decode(kv.substring(6), "UTF-8").trim();
        }
        runOnUiThread(() -> {
            status.setText(R.string.shelf_key_checking);
            status.setTextColor(0xFFFFFFFF);
        });
        boolean ok = !token.isEmpty() && Trending.check(token);
        if (ok) {
            ShelfKey.save(this, token);
            Trending.reset(this);
            LauncherSettingsActivity.version++;
            setResult(RESULT_OK);
            reply(s, "200 OK", page("Saved", "Your TV has the key. Press Done on the TV and the Top Shelf will fill in a minute."));
        } else {
            reply(s, "200 OK", form("TMDB did not accept that key. Copy the API Read Access Token and try again."));
        }
        runOnUiThread(() -> {
            showState();
            if (!ok) {
                status.setText(R.string.shelf_key_bad);
                status.setTextColor(0xFFFF8A80);
            }
        });
    }

    private void reply(Socket s, String code, String html) throws Exception {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 " + code + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + bytes.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private String form(String error) {
        return page("Top Shelf key", (error.isEmpty() ? "" : "<p class=e>" + error + "</p>")
                + "<p>Paste your TMDB <b>API Read Access Token</b> (or API Key) from themoviedb.org → Settings → API.</p>"
                + "<form method=post><textarea name=token rows=5 placeholder='eyJhbGciOi…' autofocus></textarea>"
                + "<button>Save to TV</button></form>");
    }

    private static String page(String title, String body) {
        return "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>" + title
                + "</title><style>body{font:16px system-ui;background:#0b0d14;color:#eee;margin:0;padding:24px}"
                + "h1{font-weight:300}textarea{width:100%;box-sizing:border-box;font:14px monospace;padding:10px;"
                + "border-radius:12px;border:0}button{margin-top:14px;width:100%;padding:14px;border:0;border-radius:12px;"
                + "font:600 17px system-ui;background:#fff}.e{color:#ff8a80}small{color:#888}</style><h1>Dante TV · " + title
                + "</h1>" + body + "<p><small>This product uses the TMDB API but is not endorsed or certified by TMDB.</small></p>";
    }

    /** The TV's address on the home network: Wi-Fi, or else the first IPv4 address of any interface (Ethernet). */
    private String address() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int ip = wm != null && wm.getConnectionInfo() != null ? wm.getConnectionInfo().getIpAddress() : 0;
            if (ip != 0) {
                return String.format(Locale.ROOT, "%d.%d.%d.%d", ip & 0xff, ip >> 8 & 0xff, ip >> 16 & 0xff, ip >> 24 & 0xff);
            }
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "0.0.0.0";
    }

    private static Bitmap qr(String text, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                    Collections.singletonMap(EncodeHintType.MARGIN, 1));
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) px[y * size + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565);
        } catch (Exception e) {
            return null;
        }
    }
}
