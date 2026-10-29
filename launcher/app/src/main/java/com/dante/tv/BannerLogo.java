package com.dante.tv;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

/**
 * A banner as a white logo on the dark tile (Launcher settings: App icons, White logos). The banner's background is
 * the colour along its edges; whatever stands out from it is the logo, kept as white with that much opacity. A banner
 * on a gradient or picture gives its near-white lettering instead; one with neither stays as it is (shown in black
 * and white). Worked out once per app.
 */
final class BannerLogo {

    private static final Map<String, Drawable> cache = new HashMap<>();

    private BannerLogo() {}

    static Drawable of(Resources res, String key, Drawable banner) {
        if (cache.containsKey(key)) {
            Drawable d = cache.get(key);
            return d != null ? d : banner;
        }
        Drawable logo = make(res, banner);
        cache.put(key, logo);
        return logo != null ? logo : banner;
    }

    private static Drawable make(Resources res, Drawable banner) {
        int w = banner.getIntrinsicWidth(), h = banner.getIntrinsicHeight();
        if (w <= 0 || h <= 0) return null;
        float scale = Math.min(1f, 480f / w);
        w = Math.max(1, Math.round(w * scale));
        h = Math.max(1, Math.round(h * scale));
        Bitmap src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(src);
        banner.setBounds(0, 0, w, h);
        banner.draw(c);
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        src.recycle();
        int bg = edgeColour(px, w, h);
        int br = Color.red(bg), bgG = Color.green(bg), bb = Color.blue(bg);
        int[] orig = px.clone();
        int covered = 0;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int a = Color.alpha(p);
            int d = a < 128 ? 0 : Math.max(Math.abs(Color.red(p) - br),
                    Math.max(Math.abs(Color.green(p) - bgG), Math.abs(Color.blue(p) - bb)));
            int alpha = Math.max(0, Math.min(255, (d - 38) * 5));
            if (alpha > 128) covered++;
            px[i] = (alpha << 24) | 0xFFFFFF;
        }
        float share = covered / (float) px.length;
        if (share < 0.01f || share > 0.55f) {
            // No even background (a gradient or a picture): the near-white lettering on it is the logo, if any.
            covered = 0;
            for (int i = 0; i < px.length; i++) {
                int p = orig[i];
                int lo = Color.alpha(p) < 128 ? 0 : Math.min(Color.red(p), Math.min(Color.green(p), Color.blue(p)));
                int alpha = Math.max(0, Math.min(255, (lo - 170) * 4));
                if (alpha > 128) covered++;
                px[i] = (alpha << 24) | 0xFFFFFF;
            }
            share = covered / (float) px.length;
            if (share < 0.01f || share > 0.4f) return null;
        }
        Bitmap out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        return new BitmapDrawable(res, out);
    }

    /** The commonest colour along the border, in coarse steps (gradients and anti-aliasing average out). */
    private static int edgeColour(int[] px, int w, int h) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int i = 0; i < w; i++) {
            tally(counts, px[i]);
            tally(counts, px[(h - 1) * w + i]);
        }
        for (int j = 0; j < h; j++) {
            tally(counts, px[j * w]);
            tally(counts, px[j * w + w - 1]);
        }
        int top = 0, n = -1;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() > n) {
                n = e.getValue();
                top = e.getKey();
            }
        }
        return top;
    }

    private static void tally(Map<Integer, Integer> counts, int p) {
        int q = quant(p);
        Integer n = counts.get(q);
        counts.put(q, n == null ? 1 : n + 1);
    }

    private static int quant(int p) {
        return Color.rgb(Color.red(p) / 16 * 16 + 8, Color.green(p) / 16 * 16 + 8, Color.blue(p) / 16 * 16 + 8);
    }
}
