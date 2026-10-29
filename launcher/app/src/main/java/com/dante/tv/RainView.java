package com.dante.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import org.json.JSONObject;

import java.util.Random;

/**
 * Rain falling over a wallpaper that is painted in the rain (a wall's json "rain"). Thin streaks in three depths,
 * fanning out as if falling toward the viewer (straight down in the middle, "angle" degrees at the edges), the near ones longer, faster and brighter, each depth drawn with one call. On its own surface above the
 * window, like rain on the glass: Home itself is not redrawn, the TV only composites this layer. About 30 frames a
 * second while Home is in front, nothing while it is away.
 */
final class RainView extends SurfaceView implements SurfaceHolder.Callback {

    private static final int LAYERS = 3;
    private static final long FRAME_MS = 33;

    private final int count;
    private final float slant, speed, density;
    private final Random random = new Random();
    private final Paint[] paints = new Paint[LAYERS];
    private float[] x, y, len, vel, tilt;
    private int[] layer;
    private final float[][] lines = new float[LAYERS][];
    private final int[] used = new int[LAYERS];
    private int width, height;
    private volatile boolean active, hasSurface;
    private Thread loop;

    RainView(Context ctx, JSONObject spec) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        count = Math.max(10, Math.min(400, spec.optInt("count", 140)));
        slant = (float) Math.tan(Math.toRadians(spec.optDouble("angle", 8)));
        speed = (float) Math.max(0.2, Math.min(3, spec.optDouble("speed", 1)));
        float alpha = (float) Math.max(0.05, Math.min(1, spec.optDouble("alpha", 0.35)));
        int colour = 0xFFFFFF & parse(spec.optString("color"), 0xFFDCE6F0);
        for (int k = 0; k < LAYERS; k++) {
            paints[k] = new Paint(Paint.ANTI_ALIAS_FLAG);
            paints[k].setStrokeCap(Paint.Cap.ROUND);
            paints[k].setStrokeWidth((0.6f + 0.45f * k) * density);
            paints[k].setColor(Math.round(255 * alpha * (0.45f + 0.3f * k)) << 24 | colour);
            lines[k] = new float[count * 4];
        }
        setZOrderOnTop(true);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        getHolder().addCallback(this);
    }

    private static int parse(String s, int fallback) {
        try {
            return android.graphics.Color.parseColor(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** A new drop above the screen (or anywhere on it, at the start), its depth deciding its size and pace. */
    private void drop(int i, boolean anywhere) {
        int k = random.nextInt(10) < 5 ? 0 : (random.nextInt(10) < 7 ? 1 : 2);
        layer[i] = k;
        len[i] = (14 + 14 * k + random.nextInt(12)) * density;
        vel[i] = (700 + 450 * k + random.nextInt(250)) * density * speed;
        y[i] = anywhere ? random.nextFloat() * height : -len[i] - random.nextFloat() * height * 0.3f;
        x[i] = (random.nextFloat() * 1.2f - 0.1f) * width;
        tilt[i] = slant * (x[i] - width / 2f) / (width / 2f);
    }

    void setActive(boolean on) {
        active = on;
        update();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        synchronized (this) {
            if (w != width || h != height || x == null) {
                width = w;
                height = h;
                x = new float[count];
                y = new float[count];
                len = new float[count];
                vel = new float[count];
                tilt = new float[count];
                layer = new int[count];
                for (int i = 0; i < count; i++) drop(i, true);
            }
        }
        hasSurface = true;
        update();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        hasSurface = false;
        stopLoop();
    }

    private synchronized void update() {
        if (active && hasSurface && loop == null) {
            loop = new Thread(this::run, "rain");
            loop.start();
        } else if (!(active && hasSurface)) {
            stopLoop();
        }
    }

    private void stopLoop() {
        Thread t;
        synchronized (this) {
            t = loop;
            loop = null;
        }
        if (t == null) return;
        t.interrupt();
        try {
            t.join(200);
        } catch (InterruptedException ignored) {
        }
    }

    private void run() {
        long last = SystemClock.uptimeMillis();
        while (!Thread.currentThread().isInterrupted()) {
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.1f, (now - last) / 1000f);
            last = now;
            Canvas canvas;
            try {
                canvas = getHolder().getSurface().lockHardwareCanvas();
            } catch (Exception e) {
                return;
            }
            try {
                canvas.drawColor(0, PorterDuff.Mode.CLEAR);
                step(canvas, dt);
            } finally {
                getHolder().getSurface().unlockCanvasAndPost(canvas);
            }
            long sleep = FRAME_MS - (SystemClock.uptimeMillis() - now);
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private synchronized void step(Canvas canvas, float dt) {
        java.util.Arrays.fill(used, 0);
        for (int i = 0; i < count; i++) {
            float d = vel[i] * dt;
            y[i] += d;
            x[i] += d * tilt[i];
            if (y[i] - len[i] > height) drop(i, false);
            float[] l = lines[layer[i]];
            int n = used[layer[i]];
            l[n] = x[i];
            l[n + 1] = y[i];
            l[n + 2] = x[i] - len[i] * tilt[i];
            l[n + 3] = y[i] - len[i];
            used[layer[i]] = n + 4;
        }
        for (int k = 0; k < LAYERS; k++) canvas.drawLines(lines[k], 0, used[k], paints[k]);
    }
}
