package com.dante.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * An LED strip behind the TV on a Magic Home (Zengge) Wi-Fi controller, driven over the home network: found with
 * the controller's own UDP discovery, then sent its 5577 commands directly, no cloud. It follows the TV: on with the
 * screen, off in standby. In Sound mode, while something plays, it follows the TV's own output mix (Visualizer):
 * lows, mids and highs, worked out on the TV every 40 ms; nothing is recorded or sent anywhere. When the mix is empty
 * while something plays (an HDMI input's Dolby bitstream goes straight to the soundbar), the TV's microphone, which
 * hears the soundbar, stands in until an app's sound is back in the mix. Audio is read only
 * while Sound mode is on, the screen is on and something plays.
 */
final class Lights {

    static final int UNSET = 0, OFF = 1, STATIC = 2, SOUND = 3;
    /** The colour choice that goes through every hue: a slow fade in Static, a drift with jumps on beats in Sound. */
    static final int RAINBOW = 0;
    /** Colour choices, cycled from the panel. */
    static final int[] COLOURS = {0xFF0092FF, RAINBOW, 0xFF7A3CFF, 0xFFFF2D6E, 0xFFFF3B1F, 0xFFFFB347, 0xFFFFE3C4, 0xFF20E070, 0xFF00E0E0};
    static final int[] BRIGHTNESS = {25, 50, 75, 100};

    private static final int PORT = 5577;
    private static Lights instance;

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Handler net;
    private Socket socket;
    private boolean screenOn = true, started, playing;
    private volatile boolean listening;
    private long lastRediscover, failedAt;
    /** False while the controller doesn't answer, as when the TV is on another router's Wi-Fi. */
    private volatile boolean reachable;

    static synchronized Lights get(Context ctx) {
        if (instance == null) instance = new Lights(ctx.getApplicationContext());
        return instance;
    }

    private Lights(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences("lights", Context.MODE_PRIVATE);
        HandlerThread t = new HandlerThread("lights");
        t.start();
        net = new Handler(t.getLooper());
    }

    // ---- settings ----------------------------------------------------------------------------------

    int mode() {
        return prefs.getInt("mode", UNSET);
    }

    int colour() {
        return prefs.getInt("colour", COLOURS[0]);
    }

    int brightness() {
        return prefs.getInt("brightness", 75);
    }

    boolean found() {
        return !prefs.getString("ip", "").isEmpty();
    }

    void setMode(int mode) {
        prefs.edit().putInt("mode", mode).apply();
        net.post(this::apply);
    }

    void setColour(int colour) {
        prefs.edit().putInt("colour", colour).apply();
        net.post(this::apply);
    }

    void setBrightness(int percent) {
        prefs.edit().putInt("brightness", percent).apply();
        net.post(this::apply);
    }

    static boolean audioAllowed(Context ctx) {
        return ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    // ---- following the TV --------------------------------------------------------------------------

    /** Called once the key service runs: from then on the strip follows the screen and playback. */
    void start() {
        if (started) return;
        started = true;
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        ctx.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                screenOn = Intent.ACTION_SCREEN_ON.equals(i.getAction());
                net.post(Lights.this::apply);
            }
        }, f);
        // A Wi-Fi change (the TV moving to a backup router and back): connect afresh and put the strip right at once.
        android.net.ConnectivityManager cm = ctx.getSystemService(android.net.ConnectivityManager.class);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(new android.net.ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(android.net.Network network) {
                    // A few seconds first: the new network is announced before it carries traffic.
                    net.postDelayed(() -> {
                        closeSocket();
                        lastRediscover = 0;
                        retries = 0;
                        lookQuietly();
                        retry.run();
                    }, 3000);
                }
            });
        }
        net.post(this::lookQuietly);
        net.post(this::apply);
    }

    /**
     * One discovery message at start-up and on each Wi-Fi change: the panel shows Lights only on a network where a
     * controller answers, and a new address from the router is picked up.
     */
    private void lookQuietly() {
        String[] f = discover();
        reachable = f != null;
        if (f != null) prefs.edit().putString("ip", f[0]).putString("mac", f[1]).apply();
    }

    private int retries;

    /**
     * While the controller doesn't answer, try again every 20 s, three times (Static sends nothing on its own). On
     * another router's Wi-Fi it then rests until the next Wi-Fi change.
     */
    private final Runnable retry = () -> {
        failedAt = 0;
        apply();
    };

    boolean reachable() {
        return reachable;
    }

    /** Sound mode only: is something playing? Checked once a second while the screen is on; apply() starts it. */
    private final Runnable watchPlayback = new Runnable() {
        @Override
        public void run() {
            if (mode() != SOUND || !screenOn) return;
            net.postDelayed(this, 1000);
            AudioManager audio = ctx.getSystemService(AudioManager.class);
            boolean now = audio != null && audio.isMusicActive();
            if (now != playing) {
                playing = now;
                apply();
            }
        }
    };

    /** Puts the strip in the state the mode, the screen and playback call for. Runs on the lights thread. */
    private void apply() {
        int mode = mode();
        net.removeCallbacks(rainbow);
        net.removeCallbacks(watchPlayback);
        if (mode == SOUND && screenOn && found()) net.postDelayed(watchPlayback, 1000);
        else playing = false;
        if (mode == UNSET || !found()) {
            stopListening();
            return;
        }
        if (mode == OFF || !screenOn) {
            stopListening();
            send(power(false));
            return;
        }
        send(power(true));
        if (mode == SOUND && playing && audioAllowed(ctx)) {
            startListening();
        } else {
            stopListening();
            if (colour() == RAINBOW) net.post(rainbow);
            else send(colour(scaled(colour(), brightness() / 100f), true));
        }
    }

    private float rainbowHue;

    /** About 18 s around the colour wheel, ten steps a second. */
    private final Runnable rainbow = new Runnable() {
        @Override
        public void run() {
            rainbowHue = (rainbowHue + 2) % 360;
            send(colour(Color.HSVToColor(new float[]{rainbowHue, 1f, brightness() / 100f}), false));
            net.postDelayed(this, 100);
        }
    };

    // ---- sound -------------------------------------------------------------------------------------

    private void startListening() {
        if (listening) return;
        listening = true;
        new Thread(this::listen, "lights-sound").start();
    }

    private void stopListening() {
        listening = false;
    }

    /**
     * The mix's spectrum every 40 ms, split into lows (to 250 Hz), mids (to 4 kHz) and highs. Each band has its own
     * floor and peak that adapt to the content, so quiet and loud scenes both swing the full range, and its own
     * envelope: quick to rise, quick enough to fall that every hit shows. A chosen colour pulses in brightness, lows
     * weighing most. Rainbow mixes three hues 120 degrees apart, one per band, slowly turning, and a kick (lows jumping
     * well over their recent average) turns it a step.
     */
    private void listen() {
        Visualizer vis;
        byte[] fft;
        try {
            vis = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange();
            vis.setCaptureSize(Math.max(range[0], Math.min(range[1], 1024)));
            vis.setEnabled(true);
            fft = new byte[vis.getCaptureSize()];
        } catch (Exception | Error e) {
            android.util.Log.w("TvHome.Lights", "no visualizer", e);
            listening = false;
            return;
        }
        int n = fft.length;
        double hzPerBin = vis.getSamplingRate() / 1000.0 / n;
        int lowEnd = Math.max(2, (int) (250 / hzPerBin)), midEnd = Math.max(lowEnd + 1, (int) (4000 / hzPerBin));
        AudioRecord mic = null;
        short[] pcm = new short[MIC_HOP];
        double[] ring = new double[MIC_N], re = new double[MIC_N], im = new double[MIC_N];
        double[] floor = new double[3], peak = {1, 1, 1}, env = new double[3];
        float[] hsv = new float[3];
        float turn = 0, hue = 0;
        double kickFast = 0, kickSlow = 0, flash = 0;
        long lastKick = 0, lastSend = 0, heard = SystemClock.uptimeMillis();
        int sent = 0;
        while (listening) {
            double[] band = new double[3];
            if (mic == null) SystemClock.sleep(40);
            if (vis.getFft(fft) == Visualizer.SUCCESS) {
                for (int k = 1; k < n / 2; k++) {
                    double mag = Math.hypot(fft[2 * k], fft[2 * k + 1]);
                    band[k < lowEnd ? 0 : k < midEnd ? 1 : 2] += mag * mag;
                }
            }
            long now = SystemClock.uptimeMillis();
            boolean mixSilent = band[0] + band[1] + band[2] == 0;
            if (!mixSilent) {
                heard = now;
                if (mic != null) {   // an app's PCM is back in the mix
                    closeMic(mic);
                    mic = null;
                    java.util.Arrays.fill(floor, 0);
                    java.util.Arrays.fill(peak, 1);
                }
            } else if (mic == null && now - heard > 1500) {
                // Playing but nothing in the mix: a bitstream (an HDMI input's Dolby, say) goes straight to the
                // soundbar. The microphone hears it instead.
                mic = openMic();
                if (mic == null) {
                    heard = now;
                    continue;
                }
                java.util.Arrays.fill(floor, 0);
                java.util.Arrays.fill(peak, 1);
            }
            if (mic != null && !micBands(mic, pcm, ring, re, im, band)) continue;
            double[] level = new double[3];
            for (int b = 0; b < 3; b++) {
                double e = Math.sqrt(band[b]);
                floor[b] = e < floor[b] ? e : floor[b] + (e - floor[b]) * 0.02;
                peak[b] = Math.max(e, Math.max(1, peak[b] * 0.995));   // settles to a new level in about 8 s
                double l = Math.max(0, Math.min(1, (e - floor[b]) / Math.max(1, peak[b] - floor[b])));
                env[b] += (l - env[b]) * (l > env[b] ? 0.75 : 0.3);
                level[b] = env[b];
            }
            double low = Math.sqrt(band[0]);
            kickFast += (low - kickFast) * 0.6;
            kickSlow += (low - kickSlow) * 0.06;
            boolean kick = kickFast > kickSlow * 1.25 && kickFast > floor[0] * 1.3 && now - lastKick > 200;
            if (kick) {
                lastKick = now;
                flash = 1;
            } else {
                flash *= 0.7;   // gone in about 200 ms
            }
            // Squared for contrast (the bass line keeps the lows up between kicks), and every kick flashes full.
            double mix = 0.5 * level[0] + 0.3 * level[1] + 0.2 * level[2];
            double pulse = 0.04 + 0.96 * Math.max(0.85 * mix * mix, flash);
            float user = brightness() / 100f;
            int c;
            if (colour() == RAINBOW) {
                turn = (turn + (kick ? 40 : 0.8f)) % 360;
                // The band hues averaged as directions on the colour wheel, the loudest band pulling hardest, at full
                // saturation: mixing their RGB instead turns pastel, and white when the bands are equally loud.
                double x = 0, y = 0;
                for (int b = 0; b < 3; b++) {
                    double w = 0.01 + Math.pow(level[b], 3), a = Math.toRadians(turn + b * 120);
                    x += w * Math.cos(a);
                    y += w * Math.sin(a);
                }
                if (Math.hypot(x, y) > 0.005) {
                    float target = (float) Math.toDegrees(Math.atan2(y, x));
                    float d = ((target - hue) % 360 + 540) % 360 - 180;
                    hue = (hue + d * 0.5f + 360) % 360;
                }
                c = Color.HSVToColor(new float[]{hue, 1f, (float) pulse * user});
            } else {
                Color.colorToHSV(colour(), hsv);
                c = Color.HSVToColor(new float[]{hsv[0], hsv[1], (float) pulse * user});
            }
            if (c != sent && now - lastSend >= 50) {
                lastSend = now;
                sent = c;
                final int out = c;
                net.post(() -> send(colour(out, false)));
            }
        }
        vis.release();
        if (mic != null) closeMic(mic);
        net.post(this::apply);   // back to the steady colour
    }

    private static final int MIC_RATE = 16000, MIC_N = 1024, MIC_HOP = 640;   // 40 ms a step

    private static AudioRecord openMic() {
        try {
            AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, MIC_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(AudioRecord.getMinBufferSize(MIC_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), MIC_HOP * 8));
            rec.startRecording();
            return rec;
        } catch (Exception | Error e) {
            return null;
        }
    }

    private static void closeMic(AudioRecord rec) {
        try {
            rec.stop();
        } catch (Exception ignored) {
        }
        rec.release();
    }

    /** The next 40 ms from the microphone, into the same three bands as the mix (Hann window over the last 1024). */
    private static boolean micBands(AudioRecord rec, short[] pcm, double[] ring, double[] re, double[] im, double[] band) {
        int got = rec.read(pcm, 0, pcm.length);
        if (got <= 0) return false;
        System.arraycopy(ring, got, ring, 0, ring.length - got);
        for (int i = 0; i < got; i++) ring[ring.length - got + i] = pcm[i] / 256.0;
        int n = ring.length;
        for (int i = 0; i < n; i++) {
            re[i] = ring[i] * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)));
            im[i] = 0;
        }
        fft(re, im);
        double hz = (double) MIC_RATE / n;
        int lowEnd = (int) (250 / hz), midEnd = (int) (4000 / hz);
        band[0] = band[1] = band[2] = 0;
        for (int k = 1; k < n / 2; k++) {
            double p = re[k] * re[k] + im[k] * im[k];
            band[k < lowEnd ? 0 : k < midEnd ? 1 : 2] += p / n;
        }
        return true;
    }

    /** In-place radix-2 FFT; the length must be a power of two. */
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    double t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
    }

    // ---- the controller ----------------------------------------------------------------------------

    /** The controller's own discovery: it answers "ip,MAC,model". Blocking: call off the main thread. */
    static String[] discover() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(1500);
            byte[] ask = "HF-A11ASSISTHREAD".getBytes(StandardCharsets.US_ASCII);
            s.send(new DatagramPacket(ask, ask.length, InetAddress.getByName("255.255.255.255"), 48899));
            byte[] buf = new byte[256];
            DatagramPacket reply = new DatagramPacket(buf, buf.length);
            s.receive(reply);
            String[] f = new String(buf, 0, reply.getLength(), StandardCharsets.US_ASCII).trim().split(",");
            return f.length >= 2 ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Looks for the controller now; done gets true when one answered. */
    void find(java.util.function.Consumer<Boolean> done) {
        net.post(() -> {
            String[] f = discover();
            if (f != null) {
                prefs.edit().putString("ip", f[0]).putString("mac", f[1]).apply();
                closeSocket();
                failedAt = 0;
                apply();
            }
            if (done != null) done.accept(f != null);
        });
    }

    private static byte[] power(boolean on) {
        return withSum(new byte[]{0x71, (byte) (on ? 0x23 : 0x24), 0x0F});
    }

    /** keep false for the many updates of Sound mode, so they don't wear the controller's memory. */
    private static byte[] colour(int c, boolean keep) {
        return withSum(new byte[]{0x31, (byte) Color.red(c), (byte) Color.green(c), (byte) Color.blue(c), 0, 0,
                (byte) (keep ? 0x0F : 0xF0)});
    }

    private static int scaled(int c, float f) {
        return Color.rgb(Math.round(Color.red(c) * f), Math.round(Color.green(c) * f), Math.round(Color.blue(c) * f));
    }

    private static byte[] withSum(byte[] b) {
        byte[] out = new byte[b.length + 1];
        int sum = 0;
        for (int i = 0; i < b.length; i++) {
            out[i] = b[i];
            sum += b[i] & 0xFF;
        }
        out[b.length] = (byte) sum;
        return out;
    }

    /** One connection kept open; the controller answers most commands, so its replies are read away each time. */
    private void send(byte[] msg) {
        // Unreachable: drop updates for 15 s rather than queue a 2 s connect for each of Sound's 20 a second.
        if (failedAt != 0 && SystemClock.uptimeMillis() - failedAt < 15_000) return;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (socket == null || socket.isClosed()) {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(prefs.getString("ip", ""), PORT), 2000);
                    socket.setTcpNoDelay(true);
                }
                OutputStream out = socket.getOutputStream();
                out.write(msg);
                out.flush();
                InputStream in = socket.getInputStream();
                int waiting = in.available();
                if (waiting > 0) in.skip(waiting);
                reachable = true;
                failedAt = 0;
                retries = 0;
                net.removeCallbacks(retry);
                return;
            } catch (Exception e) {
                closeSocket();
                // The router may have given the controller a new address: find it again, at most every 30 s.
                long now = SystemClock.uptimeMillis();
                if (attempt == 0 && now - lastRediscover > 30_000) {
                    lastRediscover = now;
                    String[] f = discover();
                    if (f != null) prefs.edit().putString("ip", f[0]).putString("mac", f[1]).apply();
                }
            }
        }
        reachable = false;
        failedAt = SystemClock.uptimeMillis();
        net.removeCallbacks(retry);
        if (screenOn && mode() != UNSET && retries++ < 3) net.postDelayed(retry, 20_000);
    }

    private void closeSocket() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        socket = null;
    }
}
