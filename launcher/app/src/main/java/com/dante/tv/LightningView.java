package com.dante.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import org.json.JSONObject;

import java.io.File;
import java.util.Random;

/**
 * Lightning behind the figure in a wallpaper (a wall's json "lightning"). The mask is the sky and background as a
 * picture the size of the wallpaper (cool white, opaque where the clouds are bright, clear over the figure). Every
 * so often it flickers in, twice, and fades, with a bolt drawn inside the mask. Drawn only during a flash.
 */
final class LightningView extends View {

    private final Bitmap mask;
    private final float aspect, density;
    private final long minMs, maxMs;
    private final boolean bolts;
    private final Random random = new Random();
    private final RectF dst = new RectF();
    private final Paint maskPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint boltPaint = new Paint(Paint.ANTI_ALIAS_FLAG), glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clipPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path bolt = new Path();
    private long flashAt = -1;
    private boolean active;

    LightningView(Context ctx, JSONObject spec, float imageAspect, File dir) {
        super(ctx);
        aspect = imageAspect;
        density = ctx.getResources().getDisplayMetrics().density;
        String name = spec.optString("mask", "");
        mask = name.isEmpty() || name.contains("/") || dir == null ? null
                : BitmapFactory.decodeFile(new File(dir, name).getPath());
        minMs = Math.round(Math.max(3, spec.optDouble("min", 7)) * 1000);
        maxMs = Math.max(minMs + 1000, Math.round(spec.optDouble("max", 18) * 1000));
        bolts = spec.optBoolean("bolt", true);
        boltPaint.setStyle(Paint.Style.STROKE);
        boltPaint.setStrokeJoin(Paint.Join.ROUND);
        boltPaint.setColor(0xFFF4F8FF);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);
        glowPaint.setColor(0x669FC0FF);
        clipPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        setWillNotDraw(mask == null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float pw = Math.max(w, h * aspect), ph = pw / aspect;
        dst.set((w - pw) / 2f, (h - ph) / 2f, (w + pw) / 2f, (h + ph) / 2f);
    }

    private final Runnable strike = this::strike;

    void setActive(boolean on) {
        if (active == on || mask == null) return;
        active = on;
        removeCallbacks(strike);
        if (on) schedule();
    }

    private void schedule() {
        postDelayed(strike, minMs + (long) (random.nextDouble() * (maxMs - minMs)));
    }

    private void strike() {
        if (!active) return;
        if (isShown()) {
            flashAt = SystemClock.uptimeMillis();
            makeBolt();
            invalidate();
        }
        schedule();
    }

    /** A jagged bolt from the top of the screen, in whichever side of the sky has more room, with a fork or two. */
    private void makeBolt() {
        bolt.reset();
        if (!bolts) return;
        int w = getWidth(), h = getHeight();
        boolean left = random.nextBoolean();
        float x = left ? w * (0.06f + 0.14f * random.nextFloat()) : w * (0.8f + 0.15f * random.nextFloat());
        float y = 0, end = h * (0.35f + 0.25f * random.nextFloat()), step = h * 0.035f;
        bolt.moveTo(x, y);
        while (y < end) {
            x += (random.nextFloat() - 0.5f) * step * 1.6f;
            y += step * (0.6f + random.nextFloat() * 0.8f);
            bolt.lineTo(x, y);
            if (random.nextInt(7) == 0) {
                float fx = x, fy = y, dir = random.nextBoolean() ? 1 : -1;
                bolt.moveTo(fx, fy);
                for (int k = 0; k < 4; k++) {
                    fx += dir * step * (0.4f + random.nextFloat() * 0.6f);
                    fy += step * (0.4f + random.nextFloat() * 0.6f);
                    bolt.lineTo(fx, fy);
                }
                bolt.moveTo(x, y);
            }
        }
    }

    /** Brightness over the flash: a strike, a dip, a second strike, then a slow fade. */
    private static float level(long t) {
        if (t < 60) return t / 60f;
        if (t < 140) return 1f - 0.75f * (t - 60) / 80f;
        if (t < 200) return 0.25f + 0.6f * (t - 140) / 60f;
        if (t < 900) return 0.85f * (1f - (t - 200) / 700f);
        return -1f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (flashAt < 0) return;
        long t = SystemClock.uptimeMillis() - flashAt;
        float f = level(t);
        if (f < 0) {
            flashAt = -1;
            return;
        }
        maskPaint.setAlpha(Math.round(255 * f));
        canvas.drawBitmap(mask, null, dst, maskPaint);
        if (t < 260 && !bolt.isEmpty()) {
            // The bolt only in the sky: drawn, then cut to the mask's shape.
            int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
            glowPaint.setStrokeWidth(9 * density);
            canvas.drawPath(bolt, glowPaint);
            boltPaint.setStrokeWidth(2.2f * density);
            canvas.drawPath(bolt, boltPaint);
            canvas.drawBitmap(mask, null, dst, clipPaint);
            canvas.restoreToCount(layer);
        }
        postInvalidateOnAnimation();
    }
}
