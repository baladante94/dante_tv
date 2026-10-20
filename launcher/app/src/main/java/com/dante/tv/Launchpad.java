package com.dante.tv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * All Apps, the way Mac OS X Launchpad did it: the home screen blurs behind a full-screen grid of icons
 * that zooms in, pages of 7 by 4 that slide sideways, and dots for the page you are on.
 * Built when opened and dropped when closed, so it costs nothing while Home is idle.
 */
final class Launchpad {

    private static final int COLS = 7, ROWS = 4, PER_PAGE = COLS * ROWS;

    private final Activity activity;
    private final FrameLayout root;
    private final AppAdapter adapter;
    private final float density;
    private final boolean animations;

    private FrameLayout overlay;
    private LinearLayout pages;
    private LinearLayout dots;
    private final List<View> cells = new ArrayList<>();
    /** The home screen views hidden under the overlay: it covers them, so drawing them every frame is waste. */
    private final List<View> covered = new ArrayList<>();
    private int page;

    Launchpad(Activity activity, FrameLayout root, AppAdapter adapter, boolean animations) {
        this.activity = activity;
        this.root = root;
        this.adapter = adapter;
        this.density = activity.getResources().getDisplayMetrics().density;
        this.animations = animations;
    }

    boolean isOpen() {
        return overlay != null;
    }

    /** Opens over the home screen; the views in {@code leaveOut} (the menu) are kept out of the blurred backdrop. */
    void open(View... leaveOut) {
        if (overlay != null) return;
        List<AppAdapter.Entry> apps = adapter.apps();
        if (apps.isEmpty()) return;
        int w = root.getWidth(), h = root.getHeight();

        overlay = new FrameLayout(activity);
        ImageView backdrop = new ImageView(activity);
        backdrop.setScaleType(ImageView.ScaleType.FIT_XY);
        int[] was = new int[leaveOut.length];
        for (int i = 0; i < leaveOut.length; i++) {
            was[i] = leaveOut[i].getVisibility();
            leaveOut[i].setVisibility(View.INVISIBLE);
        }
        backdrop.setImageBitmap(blurredSnapshot());
        for (int i = 0; i < leaveOut.length; i++) leaveOut[i].setVisibility(was[i]);
        overlay.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View tint = new View(activity);
        tint.setBackgroundColor(0x8C05070C);
        overlay.addView(tint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(activity);
        title.setText(R.string.nav_apps);
        title.setTextColor(0xE6FFFFFF);
        title.setTextSize(22);
        title.setTypeface(activity.getResources().getFont(R.font.outfit_medium));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.TOP;
        tlp.topMargin = (int) (34 * density);
        overlay.addView(title, tlp);

        int pageCount = (apps.size() + PER_PAGE - 1) / PER_PAGE;
        pages = new LinearLayout(activity);
        pages.setOrientation(LinearLayout.HORIZONTAL);
        pages.setClipChildren(false);
        int top = (int) (84 * density), bottom = (int) (56 * density), side = (int) (80 * density);
        cells.clear();
        for (int p = 0; p < pageCount; p++) {
            LinearLayout pg = new LinearLayout(activity);
            pg.setOrientation(LinearLayout.VERTICAL);
            pg.setPadding(side, top, side, bottom);
            pg.setClipChildren(false);
            pg.setClipToPadding(false);
            for (int r = 0; r < ROWS; r++) {
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                for (int c = 0; c < COLS; c++) {
                    int i = p * PER_PAGE + r * COLS + c;
                    View cell = i < apps.size() ? cell(apps.get(i), i) : new View(activity);
                    row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                }
                pg.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            }
            pages.addView(pg, new LinearLayout.LayoutParams(w, h));
        }
        overlay.addView(pages, new FrameLayout.LayoutParams(w * pageCount, h));

        dots = new LinearLayout(activity);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        for (int p = 0; p < pageCount && pageCount > 1; p++) {
            View dot = new View(activity);
            dot.setBackgroundResource(R.drawable.page_dot);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams((int) (8 * density), (int) (8 * density));
            dlp.setMargins((int) (6 * density), 0, (int) (6 * density), 0);
            dots.addView(dot, dlp);
        }
        FrameLayout.LayoutParams dotsLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotsLp.gravity = android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.BOTTOM;
        dotsLp.bottomMargin = (int) (34 * density);
        overlay.addView(dots, dotsLp);

        root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page = 0;
        showPage(0, false);

        // Launchpad's entrance: the backdrop fades while the icons zoom down into place.
        if (animations) {
            overlay.setAlpha(0f);
            overlay.animate().alpha(1f).setDuration(220).start();
            pages.setPivotX(w / 2f);
            pages.setPivotY(h / 2f);
            pages.setScaleX(1.12f);
            pages.setScaleY(1.12f);
            pages.animate().scaleX(1f).scaleY(1f).setDuration(320)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        }
        cells.get(0).requestFocus();
        Runnable cover = () -> {
            if (overlay == null) return;
            for (int i = 0; i < root.getChildCount(); i++) {
                View v = root.getChildAt(i);
                if (v != overlay && v.getVisibility() == View.VISIBLE) {
                    v.setVisibility(View.INVISIBLE);
                    covered.add(v);
                }
            }
        };
        if (animations) overlay.postDelayed(cover, 340);
        else cover.run();
    }

    void close(boolean animate) {
        if (overlay == null) return;
        final FrameLayout gone = overlay;
        overlay = null;
        cells.clear();
        for (View v : covered) v.setVisibility(View.VISIBLE);
        covered.clear();
        if (animate && animations) {
            pages.animate().scaleX(1.08f).scaleY(1.08f).setDuration(200).start();
            gone.animate().alpha(0f).setDuration(200).withEndAction(() -> root.removeView(gone)).start();
        } else {
            root.removeView(gone);
        }
    }

    private View cell(AppAdapter.Entry e, int index) {
        View v = activity.getLayoutInflater().inflate(R.layout.item_launchpad, null, false);
        ImageView icon = v.findViewById(R.id.icon);
        View halo = v.findViewById(R.id.halo);
        TextView label = v.findViewById(R.id.label);
        icon.setImageDrawable(e.icon != null ? e.icon : e.banner);
        // Launchpad's rounded squares, whatever shape the app's own icon has.
        icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getWidth() * 0.23f);
            }
        });
        icon.setClipToOutline(true);
        label.setText(e.label);
        v.setOnClickListener(x -> {
            close(false);
            adapter.launch(e);
        });
        v.setOnLongClickListener(x -> {
            adapter.openAppInfo(e.pkg);
            return true;
        });
        v.setOnKeyListener((x, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN && move(index, keyCode));
        v.setOnFocusChangeListener((x, focused) -> {
            icon.animate().cancel();
            if (animations) {
                icon.animate().scaleX(focused ? 1.22f : 1f).scaleY(focused ? 1.22f : 1f)
                        .setDuration(focused ? 260 : 160)
                        .setInterpolator(focused ? new OvershootInterpolator(1.6f) : new DecelerateInterpolator())
                        .start();
                halo.animate().alpha(focused ? 1f : 0f).setDuration(200).start();
                label.animate().alpha(focused ? 1f : 0.8f).setDuration(160).start();
            } else {
                icon.setScaleX(focused ? 1.22f : 1f);
                icon.setScaleY(focused ? 1.22f : 1f);
                halo.setAlpha(focused ? 1f : 0f);
                label.setAlpha(focused ? 1f : 0.8f);
            }
            if (focused) showPage(index / PER_PAGE, true);
        });
        cells.add(v);
        return v;
    }

    /** D-pad inside the grid, worked out by position so focus never leaks to the home screen behind. */
    private boolean move(int i, int keyCode) {
        int n = cells.size();
        int p = i / PER_PAGE, r = (i % PER_PAGE) / COLS, c = i % COLS;
        int target = i;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (c < COLS - 1 && i + 1 < n) target = i + 1;
                else if ((p + 1) * PER_PAGE < n) target = Math.min((p + 1) * PER_PAGE + r * COLS, n - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (c > 0) target = i - 1;
                else if (p > 0) target = (p - 1) * PER_PAGE + r * COLS + COLS - 1;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (r > 0) target = i - COLS;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (r < ROWS - 1 && i + COLS < n) target = i + COLS;
                else if (r < ROWS - 1 && (i / COLS + 1) * COLS < n) target = n - 1;
                break;
            default:
                return false;
        }
        if (target != i && target >= 0 && target < n) cells.get(target).requestFocus();
        return true;
    }

    private void showPage(int p, boolean animate) {
        if (pages == null) return;
        float x = -p * (float) root.getWidth();
        if (animate && animations && p != page) {
            pages.animate().translationX(x).setDuration(420).setInterpolator(new DecelerateInterpolator(2f)).start();
        } else {
            pages.setTranslationX(x);
        }
        page = p;
        for (int d = 0; d < dots.getChildCount(); d++) {
            dots.getChildAt(d).animate().alpha(d == p ? 1f : 0.35f).setDuration(animate ? 200 : 0).start();
        }
    }

    /**
     * The home screen as a small soft picture: drawn at 1/8 size and box-blurred three times, then stretched
     * back over the screen. One 240x135 bitmap instead of a live blur, so the overlay is as cheap as a wallpaper.
     */
    private Bitmap blurredSnapshot() {
        int sw = Math.max(1, root.getWidth() / 8), sh = Math.max(1, root.getHeight() / 8);
        Bitmap bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.scale(sw / (float) root.getWidth(), sh / (float) root.getHeight());
        root.draw(c);
        int[] px = new int[sw * sh];
        bmp.getPixels(px, 0, sw, 0, 0, sw, sh);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, sw, sh, 3, true);
            boxBlur(px, sw, sh, 3, false);
        }
        bmp.setPixels(px, 0, sw, 0, 0, sw, sh);
        return bmp;
    }

    private static void boxBlur(int[] px, int w, int h, int radius, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        int[] out = new int[len];
        for (int line = 0; line < lines; line++) {
            for (int k = 0; k < len; k++) {
                int a = 0, r = 0, g = 0, b = 0, count = 0;
                for (int d = -radius; d <= radius; d++) {
                    int q = Math.max(0, Math.min(len - 1, k + d));
                    int p = horizontal ? px[line * w + q] : px[q * w + line];
                    a += p >>> 24;
                    r += (p >> 16) & 0xFF;
                    g += (p >> 8) & 0xFF;
                    b += p & 0xFF;
                    count++;
                }
                out[k] = (a / count) << 24 | (r / count) << 16 | (g / count) << 8 | (b / count);
            }
            for (int k = 0; k < len; k++) {
                if (horizontal) px[line * w + k] = out[k];
                else px[k * w + line] = out[k];
            }
        }
    }
}
