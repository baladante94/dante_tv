package com.dante.tv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Eyes in the wallpaper that follow focus and blink. The theme's wallpaper has its irises painted out (eye whites
 * only); config.json "eyes" gives each eye's opening (a polygon), its iris (centre and radius) and a style, all as
 * fractions of the picture. This view sits over the wallpaper with the same centre crop, and draws each iris
 * inside its opening, glancing toward what has focus, with an eyelid now and then. It only draws when the eyes
 * move or blink: a glance is about 0.2 s, a blink 0.16 s every few seconds, and nothing while Home is away.
 */
final class EyesView extends View {

    private static final class Eye {
        float[] hull;
        float ix, iy, ir;
        String style;
        int lid, color, sclera;
        boolean fillSocket;
        // A sprite iris: the artwork's own iris, cut out (file beside the wallpaper), and its half size.
        android.graphics.Bitmap sprite;
        float spriteR;
        final RectF spriteRect = new RectF();
        float range = 1f;
        final Path path = new Path();
        final RectF bounds = new RectF();
        float cx, cy, r;
        // The eye's own axis (it may slant), the opening's extent along and across it from the iris centre,
        // and how far the iris may travel each way.
        float ux, uy, angle, alongMin, alongMax, acrossMin, acrossMax, maxAlong, maxAcross;
        Paint iris;
    }

    private final List<Eye> eyes = new ArrayList<>();
    private final float aspect;
    private final float density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tomoe = new Path();
    private final Paint spritePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Random random = new Random();
    private float gx, gy, spin, lid;
    private ValueAnimator glance, blink;
    private boolean active;

    /** imageAspect is the wallpaper's width over height: the fractions are mapped through the same centre crop. */
    EyesView(Context ctx, JSONArray spec, float imageAspect, java.io.File dir) {
        super(ctx);
        aspect = imageAspect;
        density = ctx.getResources().getDisplayMetrics().density;
        for (int i = 0; i < spec.length(); i++) {
            JSONObject o = spec.optJSONObject(i);
            if (o == null) continue;
            JSONArray h = o.optJSONArray("hull"), c = o.optJSONArray("iris");
            if (h == null || c == null || h.length() < 6 || c.length() < 3) continue;
            Eye e = new Eye();
            e.hull = new float[h.length()];
            for (int k = 0; k < h.length(); k++) e.hull[k] = (float) h.optDouble(k);
            e.ix = (float) c.optDouble(0);
            e.iy = (float) c.optDouble(1);
            e.ir = (float) c.optDouble(2);
            e.style = o.optString("style", "black");
            e.lid = parse(o.optString("lid"), 0xFFE0E0E0);
            e.color = parse(o.optString("color"), 0xFFD3202E);
            e.sclera = parse(o.optString("sclera"), 0xFF000000);
            // fill: paint the whole opening in the socket colour first, so no trace of the painted-out iris shows.
            e.fillSocket = o.optBoolean("fill", false);
            e.range = (float) Math.max(0, Math.min(1, o.optDouble("range", 1)));
            String sprite = o.optString("sprite", "");
            if (!sprite.isEmpty() && dir != null && !sprite.contains("/")) {
                e.sprite = android.graphics.BitmapFactory.decodeFile(new java.io.File(dir, sprite).getPath());
                e.spriteR = (float) o.optDouble("sprite_r", e.ir);
            }
            eyes.add(e);
        }
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        setWillNotDraw(eyes.isEmpty());
    }

    /** The colour darkened toward black (f = how much of it is kept). */
    private static int mix(int c, float f) {
        return Color.rgb(Math.round(Color.red(c) * f), Math.round(Color.green(c) * f), Math.round(Color.blue(c) * f));
    }

    private static int lighten(int c) {
        return Color.rgb((Color.red(c) + 255) / 2, (Color.green(c) + 255) / 2, (Color.blue(c) + 255) / 2);
    }

    private static int parse(String s, int fallback) {
        try {
            return Color.parseColor(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        // CENTER_CROP of a picture with this aspect: the scaled picture's size and where it starts on screen.
        float pw = Math.max(w, h * aspect), ph = pw / aspect, ox = (w - pw) / 2f, oy = (h - ph) / 2f;
        for (Eye e : eyes) {
            e.path.reset();
            for (int k = 0; k + 1 < e.hull.length; k += 2) {
                float x = ox + e.hull[k] * pw, y = oy + e.hull[k + 1] * ph;
                if (k == 0) e.path.moveTo(x, y);
                else e.path.lineTo(x, y);
            }
            e.path.close();
            e.path.computeBounds(e.bounds, true);
            e.cx = ox + e.ix * pw;
            e.cy = oy + e.iy * ph;
            e.r = e.ir * ph;
            axis(e, ox, oy, pw, ph);
            float sr = e.spriteR * e.r / Math.max(1e-6f, e.ir);
            e.spriteRect.set(-sr, -sr, sr, sr);
            // How far the iris may glance: along the eye, keeping most of it in view; a little across.
            e.maxAlong = Math.max(0f, Math.min(-e.alongMin, e.alongMax) - e.r * 0.75f) * e.range;
            e.maxAcross = e.r * 0.22f * e.range;
            e.iris = new Paint(Paint.ANTI_ALIAS_FLAG);
            if ("sharingan".equals(e.style)) {
                e.iris.setShader(new RadialGradient(0, 0, e.r, new int[]{0xFF7A0A12, 0xFFD3202E, 0xFFFF4B55},
                        new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            } else if ("slit".equals(e.style)) {
                // A glowing iris: hot near the centre, the theme colour, darker at the rim.
                e.iris.setShader(new RadialGradient(0, 0, e.r, new int[]{0xFFFFD2B8, e.color, mix(e.color, 0.45f)},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            } else if ("ring".equals(e.style)) {
                e.iris.setShader(new RadialGradient(0, 0, e.r, new int[]{mix(e.color, 0.35f), e.color,
                        lighten(e.color), mix(e.color, 0.3f)}, new float[]{0.35f, 0.6f, 0.85f, 1f}, Shader.TileMode.CLAMP));
            } else {
                e.iris.setColor(0xFF050505);
            }
        }
    }

    /** The opening's long axis from its outline (principal direction), and its extent around the iris centre. */
    private static void axis(Eye e, float ox, float oy, float pw, float ph) {
        int n = e.hull.length / 2;
        float mx = 0, my = 0;
        for (int k = 0; k < n; k++) {
            mx += ox + e.hull[2 * k] * pw;
            my += oy + e.hull[2 * k + 1] * ph;
        }
        mx /= n;
        my /= n;
        float sxx = 0, syy = 0, sxy = 0;
        for (int k = 0; k < n; k++) {
            float dx = ox + e.hull[2 * k] * pw - mx, dy = oy + e.hull[2 * k + 1] * ph - my;
            sxx += dx * dx;
            syy += dy * dy;
            sxy += dx * dy;
        }
        double a = 0.5 * Math.atan2(2 * sxy, sxx - syy);
        e.ux = (float) Math.cos(a);
        e.uy = (float) Math.sin(a);
        e.angle = (float) Math.toDegrees(a);
        e.alongMin = e.acrossMin = Float.MAX_VALUE;
        e.alongMax = e.acrossMax = -Float.MAX_VALUE;
        for (int k = 0; k < n; k++) {
            float dx = ox + e.hull[2 * k] * pw - e.cx, dy = oy + e.hull[2 * k + 1] * ph - e.cy;
            float along = dx * e.ux + dy * e.uy, across = -dx * e.uy + dy * e.ux;
            e.alongMin = Math.min(e.alongMin, along);
            e.alongMax = Math.max(e.alongMax, along);
            e.acrossMin = Math.min(e.acrossMin, across);
            e.acrossMax = Math.max(e.acrossMax, across);
        }
    }

    // ---- behaviour ---------------------------------------------------------------------------------

    /** Home in front: blink on a timer. Away: no timers, no frames. */
    void setActive(boolean on) {
        if (active == on) return;
        active = on;
        removeCallbacks(blinkSoon);
        if (on) scheduleBlink();
    }

    /** Glance toward a point on screen (a focused tile's centre). */
    void lookAt(float x, float y) {
        if (eyes.isEmpty() || getWidth() == 0) return;
        float mx = 0, my = 0;
        for (Eye e : eyes) {
            mx += e.cx;
            my += e.cy;
        }
        mx /= eyes.size();
        my /= eyes.size();
        float tx = clamp((x - mx) / (getWidth() * 0.42f)), ty = clamp((y - my) / (getHeight() * 0.35f));
        if (Math.abs(tx - gx) < 0.02f && Math.abs(ty - gy) < 0.02f) return;
        if (glance != null) glance.cancel();
        float fx = gx, fy = gy, fs = spin, ts = spin + 120f;
        glance = ValueAnimator.ofFloat(0f, 1f).setDuration(220);
        glance.setInterpolator(new DecelerateInterpolator(1.8f));
        glance.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            gx = fx + (tx - fx) * f;
            gy = fy + (ty - fy) * f;
            spin = fs + (ts - fs) * f;   // a Sharingan's tomoe turn a third as the eye moves
            invalidate();
        });
        glance.start();
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    private final Runnable blinkSoon = this::blinkNow;

    private void scheduleBlink() {
        postDelayed(blinkSoon, 2500 + random.nextInt(4500));
    }

    private void blinkNow() {
        if (!active) return;
        if (isShown()) blinkOnce(random.nextInt(5) == 0);
        scheduleBlink();
    }

    private void blinkOnce(boolean twice) {
        if (blink != null) blink.cancel();
        blink = ValueAnimator.ofFloat(0f, 1f, 0f).setDuration(160);
        blink.addUpdateListener(a -> {
            lid = (float) a.getAnimatedValue();
            invalidate();
        });
        if (twice) {
            blink.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    postDelayed(() -> blinkOnce(false), 90);
                }
            });
        }
        blink.start();
    }

    // ---- drawing -----------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        for (Eye e : eyes) {
            if (e.iris == null) continue;
            canvas.save();
            canvas.clipPath(e.path);
            if (e.fillSocket) canvas.drawColor(e.sclera);
            // The glance, turned into the eye's own frame: along its axis, and a little across it.
            float along = (gx * e.ux + gy * e.uy) * e.maxAlong, across = (-gx * e.uy + gy * e.ux) * e.maxAcross;
            float x = e.cx + along * e.ux - across * e.uy, y = e.cy + along * e.uy + across * e.ux;
            canvas.save();
            canvas.translate(x, y);
            if (e.sprite != null) {
                canvas.drawBitmap(e.sprite, null, e.spriteRect, spritePaint);
            } else if ("sharingan".equals(e.style)) {
                sharingan(canvas, e);
            } else if ("slit".equals(e.style)) {
                canvas.drawCircle(0, 0, e.r, e.iris);
                fill.setColor(0xFF120000);
                canvas.drawOval(-e.r * 0.11f, -e.r * 0.8f, e.r * 0.11f, e.r * 0.8f, fill);
            } else if ("ring".equals(e.style)) {
                canvas.drawCircle(0, 0, e.r, e.iris);
                fill.setColor(0xFF050505);
                canvas.drawCircle(0, 0, e.r * 0.42f, fill);
                fill.setColor(0xCCFFFFFF);
                canvas.drawCircle(-e.r * 0.3f, -e.r * 0.3f, e.r * 0.1f, fill);   // a catch light
            } else {
                canvas.drawCircle(0, 0, e.r, e.iris);
            }
            canvas.restore();
            if (lid > 0f) {
                // The lid comes down square to the eye's axis, its edge a dark lash line.
                canvas.save();
                canvas.rotate(e.angle, e.cx, e.cy);
                float pad = 4 * density, top = e.cy + e.acrossMin - pad;
                float edge = top + (e.acrossMax - e.acrossMin + 2 * pad) * lid;
                float left = e.cx + e.alongMin - pad, right = e.cx + e.alongMax + pad;
                fill.setColor(e.lid);
                canvas.drawRect(left, top, right, edge, fill);
                line.setColor(0xFF120808);
                line.setStrokeWidth(Math.max(2f, e.r * 0.07f));
                canvas.drawLine(left, edge, right, edge, line);
                canvas.restore();
            }
            canvas.restore();
        }
    }

    private void sharingan(Canvas c, Eye e) {
        float r = e.r;
        c.drawCircle(0, 0, r, e.iris);
        line.setColor(0xFF1A0003);
        line.setStrokeWidth(r * 0.05f);
        c.drawCircle(0, 0, r * 0.975f, line);
        line.setColor(0x55200006);
        line.setStrokeWidth(r * 0.025f);
        c.drawCircle(0, 0, r * 0.56f, line);
        fill.setColor(0xFF0A0002);
        c.drawCircle(0, 0, r * 0.19f, fill);
        for (int k = 0; k < 3; k++) {
            c.save();
            c.rotate(spin + k * 120f);
            float d = r * 0.56f, s = r * 0.12f;
            c.drawCircle(0, -d, s, fill);
            // The comma's tail sweeps along the ring, tapering to a point.
            tomoe.reset();
            tomoe.moveTo(-s * 0.95f, -d - s * 0.2f);
            tomoe.quadTo(-s * 0.4f, -d - s * 2.3f, s * 1.9f, -d - s * 1.5f);
            tomoe.quadTo(s * 0.4f, -d - s * 1.2f, s * 0.6f, -d - s * 0.6f);
            tomoe.close();
            c.drawPath(tomoe, fill);
            c.restore();
        }
    }
}
