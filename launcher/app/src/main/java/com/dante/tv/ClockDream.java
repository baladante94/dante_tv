package com.dante.tv;

import android.animation.ArgbEvaluator;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.View;
import android.widget.ImageView;

import java.util.Random;

/**
 * Screensaver: the launcher's thin clock and date over the theme's wallpaper (dimmed, with its eyes and lightning)
 * and a slow ambient glow.
 * Every minute the clock drifts to a new spot and the glow shifts
 * colour, so nothing burns in. No network: cheap enough for a 1 GB TV.
 */
public class ClockDream extends DreamService {

    private static final long MOVE_MS = 60_000L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private View content;
    private EyesView eyes;
    private LightningView lightning;
    private GradientDrawable glow;
    private int colorIndex;
    private int glowColor;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            shiftGlow();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);

        content = findViewById(R.id.dreamContent);
        Config cfg = Config.load(this);
        if (cfg.wallpaper != null) {
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
            android.graphics.Bitmap wall = android.graphics.BitmapFactory.decodeFile(cfg.wallpaper.getPath(), opts);
            if (wall != null) {
                ImageView w = findViewById(R.id.dreamWallpaper);
                w.setImageBitmap(wall);
                w.setVisibility(View.VISIBLE);
                android.view.ViewGroup root = (android.view.ViewGroup) w.getParent();
                float aspect = wall.getWidth() / (float) wall.getHeight();
                int at = root.indexOfChild(w) + 1;
                if (cfg.lightning != null) {
                    lightning = new LightningView(this, cfg.lightning, aspect, cfg.wallpaper.getParentFile());
                    lightning.setAlpha(w.getAlpha());
                    root.addView(lightning, at++, new android.widget.FrameLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
                }
                if (cfg.eyes != null && cfg.eyes.length() > 0) {
                    // The wallpaper's eyes are painted out for EyesView: put them back, dimmed like the picture.
                    eyes = new EyesView(this, cfg.eyes, aspect, cfg.wallpaper.getParentFile());
                    eyes.setAlpha(w.getAlpha());
                    root.addView(eyes, at, new android.widget.FrameLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
                }
            }
        }
        View ambient = findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.postDelayed(tick, MOVE_MS);
        if (eyes != null) eyes.setActive(true);
        if (lightning != null) lightning.setActive(true);
    }

    @Override
    public void onDreamingStopped() {
        if (eyes != null) eyes.setActive(false);
        if (lightning != null) lightning.setActive(false);
        handler.removeCallbacks(tick);
        cancelGlowSteps();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        cancelGlowSteps();
        super.onDetachedFromWindow();
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        }).start();
    }

    /** A random spot that keeps the whole clock on screen, with the glow centred behind it. */
    private void placeClock() {
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        float y = maxY * (0.1f + 0.8f * random.nextFloat());
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    /** Glow colour steps still queued; dropped when the screensaver stops. */
    private final java.util.List<Runnable> glowSteps = new java.util.ArrayList<>();

    /**
     * The glow is a full-screen gradient: changing it every frame for 8 s drew about 280 frames a minute. 16 steps
     * over the same 8 s (one every 500 ms) look the same on that dark, wide gradient and draw a fraction of them.
     */
    private void shiftGlow() {
        cancelGlowSteps();
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        int to = PALETTE[colorIndex];
        ArgbEvaluator mix = new ArgbEvaluator();
        for (int i = 1; i <= 16; i++) {
            float f = i / 16f;
            Runnable step = () -> setGlow((int) mix.evaluate(f, from, to));
            glowSteps.add(step);
            handler.postDelayed(step, i * 500L);
        }
    }

    private void cancelGlowSteps() {
        for (Runnable r : glowSteps) handler.removeCallbacks(r);
        glowSteps.clear();
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
