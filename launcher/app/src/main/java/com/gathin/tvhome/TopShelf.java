package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Above the dock, the way Free My TV and tvOS do it: three picks for the focused dock app, a wide card and two
 * posters, with the title and a details line under the wide one. Netflix shows its Top 10, Apple TV and
 * Crunchyroll what is popular on them, any other app a mix. Explore, Movies and Shows tabs filter them, and the
 * three cards move on through the list every 5 minutes.
 */
final class TopShelf {

    interface Host {
        /** Down from a card: back to the dock tile the shelf belongs to. */
        void leaveShelf();

        /** Left from the first card or tab: into the side menu, as from the dock. */
        void openMenuFromShelf();
    }

    private static final int PICKS = 3;
    static final long ROTATE_MS = 5 * 60_000L;

    private final Activity activity;
    private final Host host;
    private final float density;
    private final boolean animations;
    final LinearLayout view;
    private final TextView heading;
    private final LinearLayout cards;
    private final View[] slots = new View[PICKS];
    private List<Trending.Item> items = Collections.emptyList();
    private List<Trending.Item> shown = Collections.emptyList();
    private String shownKey;
    /** Explore, Movies or Shows; kept while moving along the dock. */
    private static int tab;
    private final TextView[] tabs = new TextView[3];
    private String pkg = "";
    /** Decoded pictures, so moving along the dock never decodes the same image twice. */
    private final LruCache<String, Bitmap> bitmaps = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    TopShelf(Activity activity, FrameLayout root, int index, Host host, boolean animations) {
        this.activity = activity;
        this.host = host;
        this.animations = animations;
        density = activity.getResources().getDisplayMetrics().density;
        view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setClipChildren(false);
        view.setVisibility(View.GONE);
        heading = new TextView(activity);
        heading.setTextColor(0xFFFFFFFF);
        heading.setTextSize(30);
        heading.setTypeface(activity.getResources().getFont(R.font.outfit_light));
        heading.setSingleLine(true);
        // Heading, then the Explore / Movies / Shows tabs on the same line, so the shelf stays the same height.
        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        top.addView(heading);
        int[] names = {R.string.tab_explore, R.string.tab_movies, R.string.tab_shows};
        for (int i = 0; i < tabs.length; i++) {
            TextView t = new TextView(activity);
            t.setText(names[i]);
            t.setTextSize(17);
            t.setTypeface(activity.getResources().getFont(R.font.outfit_medium));
            t.setTextColor(activity.getColorStateList(R.drawable.pill_text));
            t.setBackgroundResource(R.drawable.tab_bg);
            t.setPadding((int) (14 * density), (int) (5 * density), (int) (14 * density), (int) (6 * density));
            t.setFocusable(true);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = (int) ((i == 0 ? 26 : 4) * density);
            top.addView(t, tlp);
            tabs[i] = t;
            final int at = i;
            t.setOnFocusChangeListener((v, focused) -> {
                if (focused && tab != at) {
                    tab = at;
                    showFor(pkg);
                }
                markTabs();
            });
            t.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    slots[0].findViewById(R.id.shelfImage).requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && at == 0) {
                    host.openMenuFromShelf();
                    return true;
                }
                return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && at == tabs.length - 1;
            });
        }
        markTabs();
        view.addView(top);
        cards = new LinearLayout(activity);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setClipChildren(false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = (int) (12 * density);
        view.addView(cards, clp);
        for (int i = 0; i < PICKS; i++) {
            View card = activity.getLayoutInflater().inflate(R.layout.shelf_card, cards, false);
            ImageView img = card.findViewById(R.id.shelfImage);
            boolean wide = i == 0;
            // 170 high keeps the row (wide card, two posters) clear of the clock and date at the top right,
            // even for the longest dates such as WEDNESDAY 30 SEPTEMBER.
            int ch = 170;
            int h = (int) (ch * density), w = (int) ((wide ? ch * 16 / 9f : ch * 2 / 3f) * density);
            ViewGroup.LayoutParams ilp = img.getLayoutParams();
            ilp.width = w;
            ilp.height = h;
            img.setLayoutParams(ilp);
            img.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 14 * density);
                }
            });
            img.setClipToOutline(true);
            card.findViewById(R.id.shelfMeta).setVisibility(wide ? View.VISIBLE : View.GONE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = (int) (18 * density);
            cards.addView(card, lp);
            slots[i] = card;
            final int at = i;
            img.setOnFocusChangeListener((v, focused) -> {
                v.animate().cancel();
                float s = focused ? 1.06f : 1f;
                if (animations) {
                    v.animate().scaleX(s).scaleY(s).translationZ(focused ? 14 * density : 0f).setDuration(200)
                            .setInterpolator(new DecelerateInterpolator()).start();
                } else {
                    v.setScaleX(s);
                    v.setScaleY(s);
                }
            });
            img.setOnClickListener(v -> open(at));
            img.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    host.leaveShelf();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    tabs[tab].requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && at == 0) {
                    host.openMenuFromShelf();
                    return true;
                }
                return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && at == PICKS - 1;
            });
        }
        // Room around the cards so a focused card's zoom and shadow are not cut at the shelf's edge.
        int pad = (int) (24 * density);
        view.setPadding(pad, pad, pad, pad);
        view.setClipToPadding(false);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.leftMargin = (int) (124 * density) - pad;
        vlp.topMargin = (int) (26 * density) - pad;
        root.addView(view, index, vlp);
    }

    boolean hasItems() {
        return !items.isEmpty();
    }

    void setItems(List<Trending.Item> items) {
        this.items = items;
        shownKey = null;
        view.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Shows the picks for this dock app: its own trending list, or a mix for apps without one. */
    void showFor(String pkg) {
        if (items.isEmpty()) return;
        this.pkg = pkg;
        // Movies and Shows keep only films or only series; Explore keeps both.
        List<Trending.Item> pool = new ArrayList<>();
        for (Trending.Item t : items) if (tab == 0 || t.series == (tab == 2)) pool.add(t);
        if (pool.isEmpty()) pool = items;
        List<Trending.Item> own = new ArrayList<>();
        for (Trending.Item t : pool) if (t.pkg.equals(pkg)) own.add(t);
        String title;
        if (own.isEmpty()) {
            // A mix: the top title of each service, then the next ones.
            List<List<Trending.Item>> bySection = new ArrayList<>();
            for (Trending.Item t : pool) {
                List<Trending.Item> list = null;
                for (List<Trending.Item> l : bySection) if (l.get(0).section.equals(t.section)) list = l;
                if (list == null) bySection.add(list = new ArrayList<>());
                list.add(t);
            }
            for (int rank = 0; own.size() < pool.size(); rank++) {
                for (List<Trending.Item> l : bySection) if (rank < l.size()) own.add(l.get(rank));
            }
            title = activity.getString(R.string.trending_now);
        } else {
            // Short headings, so the tabs beside them stay clear of the clock.
            title = own.get(0).section.replace("Top 10 on Netflix", "Netflix Top 10").replace("Popular on ", "");
        }
        // The next three every 5 minutes, round the whole list.
        int pages = (own.size() + PICKS - 1) / PICKS;
        int start = pages > 1 ? (int) ((System.currentTimeMillis() / ROTATE_MS) % pages) * PICKS : 0;
        List<Trending.Item> picks = new ArrayList<>();
        for (int i = 0; i < PICKS && i < own.size(); i++) picks.add(own.get((start + i) % own.size()));
        String key = tab + title + picks.size() + (picks.isEmpty() ? "" : picks.get(0).title);
        if (key.equals(shownKey)) return;
        shownKey = key;
        Runnable bind = () -> {
            heading.setText(title);
            shown = picks;
            for (int i = 0; i < PICKS; i++) {
                View card = slots[i];
                card.setVisibility(i < picks.size() ? View.VISIBLE : View.INVISIBLE);
                if (i >= picks.size()) continue;
                Trending.Item t = picks.get(i);
                setImage(card.findViewById(R.id.shelfImage), i == 0 ? t.backdrop.getPath() : t.poster.getPath());
                ((TextView) card.findViewById(R.id.shelfName)).setText(t.title);
                ((TextView) card.findViewById(R.id.shelfMeta)).setText(t.meta);
            }
        };
        if (!animations || view.getAlpha() == 0f) {
            bind.run();
            return;
        }
        // withLayer: each half of the fade draws the cards once into a layer and fades that, instead of redrawing
        // three large pictures at partial alpha on every frame.
        cards.animate().cancel();
        heading.animate().cancel();
        cards.animate().alpha(0f).setDuration(90).withLayer().withEndAction(() -> {
            bind.run();
            cards.animate().alpha(1f).setDuration(180).withLayer().start();
        }).start();
        heading.animate().alpha(0f).setDuration(90).withLayer()
                .withEndAction(() -> heading.animate().alpha(1f).setDuration(180).withLayer().start()).start();
    }

    /** Fades the shelf out while the grid is scrolled (the dock moves up into its place) and back for the dock. */
    void setShown(boolean shown) {
        if (items.isEmpty()) return;
        float target = shown ? 1f : 0f;
        if (view.getAlpha() == target && view.getVisibility() == (shown ? View.VISIBLE : View.INVISIBLE)) return;
        view.animate().cancel();
        if (shown) view.setVisibility(View.VISIBLE);
        view.animate().alpha(target).setDuration(animations ? 160 : 0)
                .withEndAction(() -> { if (!shown) view.setVisibility(View.INVISIBLE); }).start();
    }

    /** Moves on to the next three when 5 minutes have passed and the shelf is showing a dock app. */
    void rotate() {
        if (view.getVisibility() == View.VISIBLE && view.getAlpha() > 0f && !view.hasFocus()) showFor(pkg);
    }

    /** The chosen tab is bright, the others dim; the focused one gets the white pill. */
    private void markTabs() {
        for (int i = 0; i < tabs.length; i++) tabs[i].setAlpha(i == tab || tabs[i].isFocused() ? 1f : 0.5f);
    }

    /** Up from the dock lands on the wide card. */
    boolean focusFirst() {
        if (view.getVisibility() != View.VISIBLE || shown.isEmpty()) return false;
        return slots[0].findViewById(R.id.shelfImage).requestFocus();
    }

    private void open(int at) {
        if (at >= shown.size()) return;
        Intent app = activity.getPackageManager().getLeanbackLaunchIntentForPackage(shown.get(at).pkg);
        if (app == null) return;
        activity.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AppAdapter.opened(activity, shown.get(at).pkg);
    }

    /** Decodes off the main thread, one at a time; a card shows its picture as soon as it is ready. */
    private final java.util.concurrent.ExecutorService decoder = java.util.concurrent.Executors.newSingleThreadExecutor();

    private void setImage(ImageView img, String path) {
        img.setTag(path);
        Bitmap b = bitmaps.get(path);
        img.setImageBitmap(b);
        if (b != null) return;
        decoder.execute(() -> {
            Bitmap d = decode(path);
            img.post(() -> {
                if (path.equals(img.getTag())) img.setImageBitmap(d);
            });
        });
    }

    /** Warms the cache with the first three picks for each of these apps, so moving along the dock never decodes. */
    void prefetch(List<String> pkgs) {
        if (items.isEmpty()) return;
        for (String p : pkgs) {
            List<Trending.Item> own = new ArrayList<>();
            for (Trending.Item t : items) if (t.pkg.equals(p)) own.add(t);
            if (own.isEmpty()) own = items;
            for (int i = 0; i < PICKS && i < own.size(); i++) {
                final Trending.Item t = own.get(i);
                final boolean wide = i == 0;
                decoder.execute(() -> decode(wide ? t.backdrop.getPath() : t.poster.getPath()));
            }
        }
    }

    private Bitmap decode(String path) {
        Bitmap b = bitmaps.get(path);
        if (b != null) return b;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        b = BitmapFactory.decodeFile(path, o);
        if (b != null) bitmaps.put(path, b);
        return b;
    }
}
