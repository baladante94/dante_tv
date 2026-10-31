package com.dante.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import org.json.JSONObject;

import java.io.File;

/**
 * A wallpaper that moves (a wall's json "live"): the picture drawn through a mesh, each point pushed by how much it
 * belongs to the hair, sky, background or cloak in a small weight picture (R hair, G sky, B slides with the background
 * only, A cloak). The hair sways, the cloak breathes, the sky drifts, and the background slides against the figure as
 * focus moves. Weights are zero on the face, so the eyes stay lined up. On its own surface behind the window, so Home
 * is not redrawn for it; 20 frames a second while Home sits idle, holding still while focus moves (it shares the render
 * thread with the tiles), nothing while Home is away.
 */
final class LiveWallView extends SurfaceView implements SurfaceHolder.Callback2 {

    private static final long FRAME_MS = 50, QUIET_MS = 700;
    private static final int COLS = 48, ROWS = 30;
    private static final float TAU = (float) (2 * Math.PI);

    private final Bitmap wall;
    private final float aspect;
    private final float hairAmp, cloakAmp, skyAmp, parallaxAmp;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final int count = (COLS + 1) * (ROWS + 1);
    private final float[] hair = new float[count], sky = new float[count], slide = new float[count], cloak = new float[count];
    private final float[] baseX = new float[count], baseY = new float[count], verts = new float[count * 2];
    private final float[] rowHair = new float[ROWS + 1], rowSky = new float[ROWS + 1], colSky = new float[COLS + 1];
    private Bitmap mask;
    private int width, height;
    private float parallax, elapsed;
    private volatile long quietUntil;
    private volatile float parallaxTarget;
    private volatile boolean active, hasSurface;
    private Thread loop;

    LiveWallView(Context ctx, JSONObject spec, Bitmap wall, float imageAspect, File dir) {
        super(ctx);
        this.wall = wall;
        aspect = imageAspect;
        hairAmp = (float) spec.optDouble("hair", 7);
        cloakAmp = (float) spec.optDouble("cloak", 3.5);
        skyAmp = (float) spec.optDouble("sky", 9);
        parallaxAmp = (float) spec.optDouble("parallax", 14);
        String name = spec.optString("mask", "");
        if (!name.isEmpty() && !name.contains("/") && dir != null) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPremultiplied = false;   // the alpha channel is a weight like the others
            mask = BitmapFactory.decodeFile(new File(dir, name).getPath(), opts);
        }
        getHolder().addCallback(this);
    }

    /** The background slides against the figure, away from what has focus. */
    void lookAt(float x) {
        quietUntil = SystemClock.uptimeMillis() + QUIET_MS;
        if (width > 0) parallaxTarget = Math.max(-1f, Math.min(1f, (width / 2f - x) / (width / 2f)));
    }

    void setActive(boolean on) {
        active = on;
        update();
    }

    private void buildMesh() {
        float pw = Math.max(width, height * aspect), ph = pw / aspect;
        float left = (width - pw) / 2f, top = (height - ph) / 2f;
        for (int r = 0; r <= ROWS; r++) {
            for (int c = 0; c <= COLS; c++) {
                int i = r * (COLS + 1) + c;
                baseX[i] = left + pw * c / COLS;
                baseY[i] = top + ph * r / ROWS;
                // The picture's own sides stay put, so no gap opens at the screen's edge.
                float edge = Math.min(1f, Math.min(c, COLS - c) / 3f);
                if (mask == null) continue;
                int p = mask.getPixel(Math.min(mask.getWidth() - 1, c * mask.getWidth() / COLS),
                        Math.min(mask.getHeight() - 1, r * mask.getHeight() / ROWS));
                hair[i] = Color.red(p) / 255f * edge;
                sky[i] = Color.green(p) / 255f * edge;
                slide[i] = Color.blue(p) / 255f * edge;
                cloak[i] = Color.alpha(p) / 255f * edge;
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        synchronized (this) {
            if (w != width || h != height) {
                width = w;
                height = h;
                buildMesh();
            }
        }
        hasSurface = true;
        update();
    }

    /** One frame before the window shows, so coming back to Home never flashes black. */
    @Override
    public void surfaceRedrawNeeded(SurfaceHolder holder) {
        if (loop == null) drawFrame();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        hasSurface = false;
        stopLoop();
    }

    private synchronized void update() {
        if (active && hasSurface && loop == null) {
            loop = new Thread(this::run, "live-wall");
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
            if (now >= quietUntil) {
                elapsed += Math.min(0.1f, (now - last) / 1000f);
                if (!drawFrame()) return;
            }
            last = now;
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

    private boolean drawFrame() {
        Canvas canvas;
        try {
            canvas = getHolder().getSurface().lockHardwareCanvas();
        } catch (Exception e) {
            return false;
        }
        try {
            step(elapsed);
            canvas.drawBitmapMesh(wall, COLS, ROWS, verts, 0, null, 0, paint);
        } finally {
            getHolder().getSurface().unlockCanvasAndPost(canvas);
        }
        return true;
    }

    private synchronized void step(float t) {
        float k = width / 1920f;
        parallax += (parallaxTarget - parallax) * 0.06f;
        float par = parallax * parallaxAmp;
        float breath = (float) Math.sin(TAU * t / 4.8f);
        for (int r = 0; r <= ROWS; r++) {
            float y = r / (float) ROWS;
            rowHair[r] = 0.7f * (float) Math.sin(TAU * t / 5.5f + y * 2.5f) + 0.3f * (float) Math.sin(TAU * t / 2.3f + y * 6f);
            rowSky[r] = (float) Math.sin(TAU * t / 14f + y * 4f);
        }
        for (int c = 0; c <= COLS; c++) colSky[c] = (float) Math.sin(TAU * t / 9f + c / (float) COLS * 3f);
        for (int r = 0, i = 0; r <= ROWS; r++) {
            for (int c = 0; c <= COLS; c++, i++) {
                float dx = hair[i] * hairAmp * rowHair[r] + cloak[i] * 0.43f * cloakAmp * breath
                        + sky[i] * (skyAmp * rowSky[r] + par) + slide[i] * par;
                float dy = -cloak[i] * cloakAmp * breath + sky[i] * 0.22f * skyAmp * colSky[c];
                verts[i * 2] = baseX[i] + dx * k;
                verts[i * 2 + 1] = baseY[i] + dy * k;
            }
        }
    }
}
