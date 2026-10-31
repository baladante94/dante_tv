package com.dante.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.Calendar;

/**
 * The Home clock with seconds, each changed digit rolling up out of view as the new one rolls in (Launcher settings:
 * Clock seconds). Ticks only while it is on screen: about 0.3 s of frames a second, nothing while Home is away.
 */
final class RollingClock extends View {

    private static final long ROLL_MS = 320;

    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DecelerateInterpolator ease = new DecelerateInterpolator(1.6f);
    private String shown = "", previous = "";
    private long changedAt;
    private boolean running;

    RollingClock(Context ctx, Typeface face) {
        super(ctx);
        float sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1, ctx.getResources().getDisplayMetrics());
        big.setTypeface(face);
        big.setTextSize(40 * sp);
        big.setColor(0xFFFFFFFF);
        big.setLetterSpacing(-0.04f);
        small.setTypeface(face);
        small.setTextSize(24 * sp);
        small.setColor(0xB3FFFFFF);
        shown = previous = now();
    }

    /** "h:mm" then ":ss": the minutes part big, the seconds smaller, like the clock it stands in for. */
    private String now() {
        Calendar c = Calendar.getInstance();
        boolean h24 = DateFormat.is24HourFormat(getContext());
        int hour = h24 ? c.get(Calendar.HOUR_OF_DAY) : (c.get(Calendar.HOUR) == 0 ? 12 : c.get(Calendar.HOUR));
        return String.format(java.util.Locale.US, h24 ? "%02d:%02d:%02d" : "%d:%02d:%02d",
                hour, c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            String t = now();
            if (!t.equals(shown)) {
                previous = shown;
                shown = t;
                changedAt = SystemClock.uptimeMillis();
                postInvalidateOnAnimation();
            }
            // Checked against the real size: the time also jumps on coming back to Home, between ticks.
            if (measuredWidth(shown) != getMeasuredWidth()) requestLayout();
            postDelayed(this, 1000 - System.currentTimeMillis() % 1000 + 5);
        }
    };

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        running = visibility == VISIBLE;
        removeCallbacks(tick);
        if (running) {
            shown = previous = now();
            post(tick);
        }
    }

    private int split(String t) {
        return t.lastIndexOf(':');
    }

    /** Seconds digits take the widest digit's room, so the seconds never shift or cut off as they tick. */
    private static float digitWidth(Paint p) {
        float w = 0;
        for (char d = '0'; d <= '9'; d++) w = Math.max(w, p.measureText(String.valueOf(d)));
        return w;
    }

    private float advance(String t, int i) {
        boolean seconds = i > split(t);
        Paint p = seconds ? small : big;
        return seconds && Character.isDigit(t.charAt(i)) ? digitWidth(p) : p.measureText(String.valueOf(t.charAt(i)));
    }

    private float width(String t) {
        float w = 0;
        for (int i = 0; i < t.length(); i++) w += advance(t, i);
        return w;
    }

    private int measuredWidth(String t) {
        return (int) Math.ceil(width(t) + 2);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        Paint.FontMetrics fm = big.getFontMetrics();
        setMeasuredDimension(measuredWidth(shown), (int) Math.ceil(-fm.ascent + fm.descent * 0.3f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float f = Math.min(1f, (SystemClock.uptimeMillis() - changedAt) / (float) ROLL_MS);
        float k = ease.getInterpolation(f);
        float base = -big.getFontMetrics().ascent;
        int s = split(shown);
        float x = 0;
        for (int i = 0; i < shown.length(); i++) {
            Paint p = i < s ? big : small;
            String ch = String.valueOf(shown.charAt(i));
            float cw = advance(shown, i);
            float dx = (cw - p.measureText(ch)) / 2;
            boolean rolled = f < 1f && (previous.length() != shown.length() || previous.charAt(i) != shown.charAt(i));
            if (rolled) {
                // The old digit rolls up and fades, the new one rises in from below.
                float lift = p.getTextSize() * 0.9f;
                int alpha = p.getAlpha();
                canvas.save();
                canvas.clipRect(x - 2, 0, x + cw + 2, getHeight());
                if (i < previous.length()) {
                    String old = String.valueOf(previous.charAt(i));
                    p.setAlpha(Math.round(alpha * (1 - k)));
                    canvas.drawText(old, x + (cw - p.measureText(old)) / 2, base - lift * k, p);
                }
                p.setAlpha(Math.round(alpha * k));
                canvas.drawText(ch, x + dx, base + lift * (1 - k), p);
                p.setAlpha(alpha);
                canvas.restore();
            } else {
                canvas.drawText(ch, x + dx, base, p);
            }
            x += cw;
        }
        if (f < 1f) postInvalidateOnAnimation();
    }
}
