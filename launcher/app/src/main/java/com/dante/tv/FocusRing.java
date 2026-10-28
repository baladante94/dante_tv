package com.dante.tv;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.animation.DecelerateInterpolator;

/**
 * The focused tile's border, drawn as the card's foreground: a cyan-to-violet edge that draws itself outward from
 * the side focus came from (so it reads as flowing over from the last tile), with a bright spark running once round
 * it. About 0.45 s of frames, then a still image: Home at rest draws nothing.
 */
final class FocusRing extends Drawable {

    private static final int CYAN = 0xFF00E5FF, VIOLET = 0xFF7C4DFF, PINK = 0xFFE040FB;
    private static final long DURATION_MS = 450;

    private final float density;
    private float radius;
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG),
            spark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), part = new Path();
    private final PathMeasure measure = new PathMeasure();
    private float length, entry, trace = 1f, sparkAt = -1f, alpha;
    private ValueAnimator anim;

    FocusRing(float density) {
        this.density = density;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(2.5f * density);
        edge.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(7f * density);
        glow.setStrokeCap(Paint.Cap.ROUND);
        glow.setAlpha(70);
        spark.setStyle(Paint.Style.STROKE);
        spark.setStrokeWidth(3.5f * density);
        spark.setStrokeCap(Paint.Cap.ROUND);
        spark.setColor(Color.WHITE);
    }

    /** Focus arrived; move is the D-pad key it came with (0 when unknown). */
    void show(int move, boolean animate) {
        cancel();
        alpha = 1f;
        if (!animate) {
            trace = 1f;
            sparkAt = -1f;
            invalidateSelf();
            return;
        }
        entry = entryFor(move);
        anim = ValueAnimator.ofFloat(0f, 1f).setDuration(DURATION_MS);
        anim.setInterpolator(new DecelerateInterpolator(1.4f));
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            trace = Math.min(1f, f / 0.35f);
            sparkAt = f < 1f ? f : -1f;
            invalidateSelf();
        });
        anim.start();
    }

    /** At once, for a rebound tile: shown still, or gone. */
    void set(boolean on) {
        cancel();
        alpha = on ? 1f : 0f;
        trace = 1f;
        sparkAt = -1f;
        invalidateSelf();
    }

    void hide() {
        cancel();
        if (alpha == 0f) return;
        anim = ValueAnimator.ofFloat(alpha, 0f).setDuration(110);
        anim.addUpdateListener(a -> {
            alpha = (float) a.getAnimatedValue();
            sparkAt = -1f;
            invalidateSelf();
        });
        anim.start();
    }

    private void cancel() {
        if (anim != null) anim.cancel();
        anim = null;
    }

    /** Where along the outline (from the top's middle, clockwise) the edge facing the last tile sits. */
    private float entryFor(int move) {
        Rect b = getBounds();
        float w = b.width(), h = b.height(), r = Math.min(radius, Math.min(w, h) / 2f);
        float corner = (float) (Math.PI * r / 2);
        float rightMid = (w / 2 - r) + corner + (h / 2 - r);
        switch (move) {
            case KeyEvent.KEYCODE_DPAD_RIGHT: return length / 2 + rightMid;   // came from the left
            case KeyEvent.KEYCODE_DPAD_LEFT: return rightMid;
            case KeyEvent.KEYCODE_DPAD_UP: return length / 2;                 // came from below
            default: return 0f;
        }
    }

    @Override
    protected void onBoundsChange(Rect b) {
        radius = AppAdapter.cornerRadius(b.width(), density);   // the card's own outline
        float inset = edge.getStrokeWidth() / 2f;
        RectF rf = new RectF(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
        float r = Math.max(0f, radius - inset);
        // Built by hand from the top's middle, clockwise, so entryFor's distances match.
        path.reset();
        path.moveTo(rf.centerX(), rf.top);
        path.lineTo(rf.right - r, rf.top);
        path.arcTo(new RectF(rf.right - 2 * r, rf.top, rf.right, rf.top + 2 * r), -90, 90);
        path.lineTo(rf.right, rf.bottom - r);
        path.arcTo(new RectF(rf.right - 2 * r, rf.bottom - 2 * r, rf.right, rf.bottom), 0, 90);
        path.lineTo(rf.left + r, rf.bottom);
        path.arcTo(new RectF(rf.left, rf.bottom - 2 * r, rf.left + 2 * r, rf.bottom), 90, 90);
        path.lineTo(rf.left, rf.top + r);
        path.arcTo(new RectF(rf.left, rf.top, rf.left + 2 * r, rf.top + 2 * r), 180, 90);
        path.close();
        measure.setPath(path, false);
        length = measure.getLength();
        SweepGradient sweep = new SweepGradient(rf.centerX(), rf.centerY(),
                new int[]{CYAN, VIOLET, PINK, VIOLET, CYAN}, null);
        edge.setShader(sweep);
        glow.setShader(sweep);
    }

    @Override
    public void draw(Canvas canvas) {
        if (alpha <= 0f || length == 0f) return;
        int a = Math.round(255 * alpha);
        edge.setAlpha(a);
        glow.setAlpha(Math.round(70 * alpha));
        if (trace >= 1f) {
            canvas.drawPath(path, glow);
            canvas.drawPath(path, edge);
        } else {
            float half = trace * length / 2;
            segment(entry - half, entry + half);
            canvas.drawPath(part, glow);
            canvas.drawPath(part, edge);
        }
        if (sparkAt >= 0f) {
            float head = entry + sparkAt * length, tail = head - length * 0.10f;
            spark.setAlpha(Math.round(255 * alpha * (1f - sparkAt)));
            segment(tail, head);
            canvas.drawPath(part, spark);
        }
    }

    /** The outline between two distances, wrapping past the start. */
    private void segment(float from, float to) {
        part.reset();
        float span = Math.min(length, Math.max(0f, to - from));
        from = ((from % length) + length) % length;
        float end = from + span;
        if (end <= length) {
            measure.getSegment(from, end, part, true);
        } else {
            measure.getSegment(from, length, part, true);
            measure.getSegment(0f, end - length, part, true);
        }
    }

    @Override
    public void setAlpha(int a) {
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
