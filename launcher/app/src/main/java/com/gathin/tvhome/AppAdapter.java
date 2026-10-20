package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order),
 * then the module cards the owner chose to show (by label), the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for a D-pad direction key on a tile. Return true when handled (focus stays put). */
        boolean onTileVerticalKey(int position, int keyCode);

        /** OK on a tile: run {@code launch} now or after a short flourish. */
        void onTileClicked(int position, Runnable launch);

        /** Hold OK on a tile: its options (Move, App info). */
        void onTileLongPressed(int position);
    }

    static final class Entry {
        static final int APP = 0, HOME_SETTINGS = 1, CARD = 2, SPACER = 3;

        final int kind;
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** Text under the tile when it is focused in the grid: the label, plus the caption for a card. */
        final String name;
        /** Ambient glow colour; 0 until sampled from the banner, or the card's accent from the start. */
        int ambient;
        /** Identity for focus and item ids: the component, marked when this is a dock app's copy in Jump back in. */
        final String key;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(APP, label, component, banner, icon, null, 0);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption, int accent) {
            this.kind = kind;
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.name = caption == null ? label : label + " \u00B7 " + caption;
            this.ambient = accent;
            this.key = component.flattenToShortString();
        }

        /** The same app again, for Jump back in when it is also in the dock. */
        private Entry(Entry dock) {
            kind = dock.kind;
            label = dock.label;
            component = dock.component;
            pkg = dock.pkg;
            banner = dock.banner;
            icon = dock.icon;
            name = dock.name;
            ambient = dock.ambient;
            key = dock.key + "#recent";
        }
    }

    static final float FOCUS_SCALE = 1.10f;

    private static final String RECENTS = "recent_apps";

    /** D-pad key that is moving focus to the next tile, so it can tilt in from that side; 0 for none. */
    static int pendingMove;

    /**
     * Tiles already built, by component, so returning Home does not reload every banner from its APK.
     * Static to survive the activity being recreated; a package broadcast forgets that package's entries.
     */
    private static final Map<String, Entry> built = new HashMap<>();

    static void forget(String pkg) {
        if (pkg == null) {
            built.clear();
            return;
        }
        built.values().removeIf(e -> e.pkg.equals(pkg));
    }
    /** Design size of a tile (dp); tiles shrink from here when the columns don't fit, see tileWidthPx. */
    static final float DESIGN_W_DP = 180f, DESIGN_H_DP = 101f;

    /**
     * Tile width for a grid of this inner width: the cell minus room for the focus zoom and the glass
     * highlight, capped at the design size so 4 columns on a 1080p TV look exactly as before.
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return design;
        int cell = innerWidthPx / columns;
        int fit = (int) ((cell - 12 * density) / FOCUS_SCALE);
        return Math.max(Math.round(80 * density), Math.min(design, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    /** Height of one grid item: the tile, the name label under it and the item's own padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return tileHeightPx + Math.round(45 * density);
    }

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock order (config.xml dock_apps, or a theme's config.json). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final boolean animations;
    private final boolean advancedEffects;
    private final boolean showHomeSettings;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): no overshoot, shorter fades. */
    private final boolean reducedMotion;
    private final List<Entry> items = new ArrayList<>();
    private static final String ORDER = "app_order";
    /** The owner's own order (Move), as tile keys; empty until they first move a tile. The dock is its first row. */
    private List<String> savedOrder = Collections.emptyList();
    /** Moving a tile: no Jump back in row, so the grid on screen is exactly the order being edited. */
    private boolean reordering;
    private String movingKey;
    /** Labels over the rows that start a section (Jump back in, All apps), by position. */
    private final Map<Integer, String> rowLabels = new HashMap<>();

    AppAdapter(Context ctx, TileListener listener, int dockSize, Config cfg) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = cfg.dockApps;
        this.hidden = new HashSet<>(cfg.hiddenApps);
        this.animations = cfg.animations;
        this.advancedEffects = cfg.animations && cfg.advancedEffects;
        this.showHomeSettings = cfg.showHomeSettingsShortcut;
        this.reducedMotion = cfg.reducedMotion;
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    /**
     * Rebuilds the tiles. A shown module card stands in for every tile of its package (same component,
     * so focus follows it); hidden_apps wins over a card, and a card's package in dock_apps takes that slot.
     */
    void reload(List<Cards.Card> cards) {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        Map<String, Cards.Card> cardByPkg = new HashMap<>();
        for (Cards.Card c : cards) cardByPkg.put(c.pkg, c);
        List<Entry> fresh = new ArrayList<>();
        // Some system apps declare two leanback entry points under one name (Sony's Timers & Clock on the
        // BRAVIA VU31), which would show as two identical tiles: keep the first per package and label.
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Cards.Card card = cardByPkg.get(pkg);
            if (card != null && !card.component.equals(component)) continue;   // the card stands in for this tile
            if (card != null) {
                if (!seen.add(pkg + '\n' + card.label)) continue;
                fresh.add(new Entry(Entry.CARD, card.label, component, loadBanner(pm, ri), ri.loadIcon(pm),
                        card.caption, card.accent));
                continue;
            }
            String key = component.flattenToShortString();
            Entry cached = built.get(key);
            if (cached == null) {
                // Same cleaner as the card prompt: an app label with a bidi override or line separator must not
                // redraw the tile name as something else (#117).
                cached = new Entry(Cards.cleanLabel(String.valueOf(ri.loadLabel(pm)), pkg), component,
                        loadBanner(pm, ri), ri.loadIcon(pm));
                built.put(key, cached);
            }
            if (!seen.add(pkg + '\n' + cached.label)) continue;
            fresh.add(cached);
        }
        String saved = ctx.getSharedPreferences(ORDER, Context.MODE_PRIVATE).getString("keys", "");
        savedOrder = saved.isEmpty() ? Collections.emptyList() : Arrays.asList(saved.split("\n"));
        Collections.sort(fresh, (a, b) -> {
            // The owner's own order first; tiles it does not know (new installs) follow by the usual rule.
            int sa = savedOrder.indexOf(a.key), sb = savedOrder.indexOf(b.key);
            if (sa >= 0 || sb >= 0) {
                if (sa < 0) return 1;
                if (sb < 0) return -1;
                return Integer.compare(sa, sb);
            }
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            // Cards come right after the configured dock apps, before the alphabetical grid.
            boolean ca = a.kind == Entry.CARD, cb = b.kind == Entry.CARD;
            if (ca != cb) return ca ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        // Sections after the dock, each padded to whole rows so every row lines up with the dock:
        // Jump back in (apps opened recently), then all apps.
        // A dock app opened recently appears again as a copy; any other app moves up out of All apps.
        int dockEnd = Math.min(dockSize, fresh.size());
        Set<String> docked = new HashSet<>();
        for (int i = 0; i < dockEnd; i++) docked.add(fresh.get(i).pkg);
        List<Entry> recent = new ArrayList<>();
        for (Entry e : reordering ? Collections.<Entry>emptyList() : recentApps(fresh)) {
            if (docked.contains(e.pkg)) {
                recent.add(new Entry(e));
            } else {
                fresh.remove(e);
                recent.add(e);
            }
        }
        int at = dockEnd;
        rowLabels.clear();
        List<Entry> sections = new ArrayList<>();
        addSection(sections, at, ctx.getString(R.string.section_recent), recent);
        if (!rowLabels.isEmpty()) rowLabels.put(at + sections.size(), ctx.getString(R.string.section_all));
        fresh.addAll(at, sections);
        // Retain the optional Default-home tile until dependent tile-ordering work is merged.
        if (showHomeSettings
                && pm.resolveActivity(new Intent(Settings.ACTION_HOME_SETTINGS), 0) != null) {
            fresh.add(new Entry(Entry.HOME_SETTINGS, ctx.getString(R.string.home_settings),
                    new ComponentName(ctx, HomeSettingsActivity.class), null,
                    ctx.getDrawable(R.drawable.ic_home_settings), null, 0));
        }
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    /** Move mode on or off; takes effect at the next reload(). */
    void setReordering(boolean on, String key) {
        reordering = on;
        movingKey = on ? key : null;
    }

    boolean rowLabelsHidden() {
        return reordering;
    }

    /** Tiles that can move: everything but the Default-home tile, which stays last. */
    int movableCount() {
        int n = items.size();
        return n > 0 && items.get(n - 1).kind == Entry.HOME_SETTINGS ? n - 1 : n;
    }

    /** Moves one tile; the others shift by one. Stable ids keep the moved tile's view, and its focus. */
    void move(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= movableCount() || to >= movableCount()) return;
        items.add(to, items.remove(from));
        notifyItemMoved(from, to);
        // The dock hides names by position: rebind the tiles whose side of the dock edge changed.
        if (from < dockSize || to < dockSize) {
            int lo = Math.min(from, to), hi = Math.max(from, to);
            notifyItemRangeChanged(lo, hi - lo + 1);
        }
    }

    /** Saves the order on screen; the dock is its first row from now on. */
    void saveOrder() {
        StringBuilder keys = new StringBuilder();
        for (int i = 0; i < movableCount(); i++) {
            Entry e = items.get(i);
            if (e.kind != Entry.APP && e.kind != Entry.CARD) continue;
            if (keys.length() > 0) keys.append('\n');
            keys.append(e.key);
        }
        ctx.getSharedPreferences(ORDER, Context.MODE_PRIVATE).edit().putString("keys", keys.toString()).apply();
    }

    /** Back to the dock_apps order and alphabetical grid. */
    void resetOrder() {
        ctx.getSharedPreferences(ORDER, Context.MODE_PRIVATE).edit().remove("keys").apply();
    }

    /** The label over the row starting at this position, or null. */
    String rowLabel(int position) {
        return rowLabels.get(position);
    }

    private void addSection(List<Entry> sections, int offset, String label, List<Entry> entries) {
        if (entries.isEmpty()) return;
        rowLabels.put(offset + sections.size(), label);
        sections.addAll(entries.subList(0, Math.min(dockSize, entries.size())));
        for (int i = entries.size(); i < dockSize; i++) {
            sections.add(new Entry(Entry.SPACER, "", new ComponentName(ctx, "spacer" + sections.size() + label), null, null, null, 0));
        }
    }


    /** Records an app as opened, for Jump back in (also used by the Top Shelf cards). */
    static void opened(Context ctx, String pkg) {
        ctx.getSharedPreferences(RECENTS, Context.MODE_PRIVATE).edit().putLong(pkg, System.currentTimeMillis()).apply();
    }

    /** Apps opened from the launcher, newest first: the Jump back in row. No permission needed. */
    private List<Entry> recentApps(List<Entry> all) {
        Map<String, ?> opened = ctx.getSharedPreferences(RECENTS, Context.MODE_PRIVATE).getAll();
        List<Entry> candidates = new ArrayList<>();
        for (Entry e : all) {
            if (e.kind == Entry.APP && opened.get(e.pkg) instanceof Long) candidates.add(e);
        }
        Collections.sort(candidates, (a, b) -> Long.compare((Long) opened.get(b.pkg), (Long) opened.get(a.pkg)));
        return new ArrayList<>(candidates.subList(0, Math.min(dockSize, candidates.size())));
    }

    private Drawable loadBanner(PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                // Keep at least xhdpi detail rather than downsampling artwork before the focus zoom.
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, ctx.getResources().getDisplayMetrics().densityDpi);
                return resources.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        return ri.activityInfo.loadBanner(pm);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
        int w = tileWidthPx(inner, dockSize, density), h = tileHeightPx(w);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round(7 * density);
        name.setLayoutParams(nameLp);
        return new Holder(v, listener, animations, advancedEffects, reducedMotion);
    }

    /** An empty cell padding out the Jump back in row: never shown, never focused. */
    boolean isSpacer(int position) {
        return position >= 0 && position < items.size() && items.get(position).kind == Entry.SPACER;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        boolean spacer = e.kind == Entry.SPACER;
        h.itemView.setVisibility(spacer ? View.INVISIBLE : View.VISIBLE);
        h.card.setFocusable(!spacer);
        if (spacer) return;
        h.name.setText(e.name);
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused && animations ? focusScale() : 1f);
        h.card.setScaleY(focused && animations ? focusScale() : 1f);
        float d = ctx.getResources().getDisplayMetrics().density;
        boolean lifted = focused && animations && (advancedEffects || !reducedMotion);
        boolean fullMotion = animations && !reducedMotion;
        h.card.setTranslationY(lifted ? (fullMotion ? -5f : -4f) * d : 0f);
        h.card.setTranslationZ(lifted ? (fullMotion ? 16f : 8f) * d : 0f);
        h.banner.setScaleX(focused && advancedEffects ? 1.035f : 1f);
        h.banner.setScaleY(focused && advancedEffects ? 1.035f : 1f);
        h.card.setRotationX(0f);
        h.card.setRotationY(0f);
        h.banner.setTranslationX(0f);
        h.banner.setTranslationY(0f);
        h.glint.animate().cancel();
        h.glint.setAlpha(0f);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> listener.onTileClicked(h.getBindingAdapterPosition(), () -> launch(e)));
        h.card.setOnLongClickListener(v -> {
            listener.onTileLongPressed(h.getBindingAdapterPosition());
            return true;
        });
        // Moving a tile: it stays bright, the rest dim.
        h.itemView.setAlpha(reordering && !e.key.equals(movingKey) ? 0.4f : 1f);
    }

    /** Every app tile, dock first, for the All Apps overlay; the Default-home tile is left out. */
    List<Entry> apps() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : items) {
            if ((e.kind == Entry.APP || e.kind == Entry.CARD) && !e.key.endsWith("#recent")) out.add(e);
        }
        return out;
    }

    /** Opens the tile's activity by explicit component, with no extras: a card can name nothing else. */
    void launch(Entry e) {

        if (e.kind == Entry.HOME_SETTINGS) {
            ctx.startActivity(new Intent(ctx, HomeSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
            opened(ctx, e.pkg);
            if (advancedEffects && ctx instanceof Activity) {
                ((Activity) ctx).overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
            }
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    void openAppInfo(String pkg) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).key.hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).key : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key.equals(key)) return i;
        }
        return -1;
    }

    String pkgAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).pkg : "";
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    float focusScale() {
        return !animations ? 1f : advancedEffects ? 1.12f : 1.10f;
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry; a card's accent when it set one. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    /** Corner radius for a tile of this width: 16dp at the design width, proportionally less when smaller. */
    static float cornerRadius(int tileWidthPx, float density) {
        return 16f * density * Math.min(1f, tileWidthPx / (DESIGN_W_DP * density));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        final View glint;
        final boolean animations;
        final boolean advancedEffects;
        final boolean reducedMotion;

        Holder(View v, TileListener listener, boolean animations, boolean advancedEffects,
               boolean reducedMotion) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            glint = v.findViewById(R.id.glint);
            this.animations = animations;
            this.advancedEffects = advancedEffects;
            this.reducedMotion = reducedMotion;

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    // 16dp at the design width, scaled down with the tile so small tiles keep the same shape.
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);
            // Far camera: the tilt reads as a gentle lean, not a door swinging open.
            card.setCameraDistance(9000f * density);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                view.animate().cancel();
                banner.animate().cancel();
                name.animate().cancel();
                int move = focused ? pendingMove : 0;
                if (focused) pendingMove = 0;
                float scale = !animations ? 1f : advancedEffects ? 1.12f : 1.10f;
                float s = focused ? scale : 1f;
                if (!animations) {
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                    view.setTranslationY(0f);
                    view.setTranslationZ(0f);
                    banner.setScaleX(1f);
                    banner.setScaleY(1f);
                    name.setAlpha(focused ? 1f : 0f);
                    name.setTranslationY(0f);
                } else if (reducedMotion) {
                    view.animate().scaleX(s).scaleY(s)
                            .translationY(focused && advancedEffects ? -4f * density : 0f)
                            .translationZ(focused && advancedEffects ? 8f * density : 0f)
                            .setDuration(focused ? 120 : 80)
                            .setInterpolator(new DecelerateInterpolator())
                            .withLayer().start();
                    name.animate().alpha(focused ? 1f : 0f).setDuration(100).start();
                } else if (focused) {
                    // tvOS focus: the tile lifts toward you, leans in from the side focus came from and
                    // settles, its artwork drifting against the lean for depth, while a glint crosses it.
                    float lean = 7f, drift = 7f * density;
                    float rotY = move == KeyEvent.KEYCODE_DPAD_RIGHT ? lean : move == KeyEvent.KEYCODE_DPAD_LEFT ? -lean : 0f;
                    float rotX = move == KeyEvent.KEYCODE_DPAD_DOWN ? -lean : move == KeyEvent.KEYCODE_DPAD_UP ? lean : 0f;
                    view.setRotationY(rotY);
                    view.setRotationX(rotX);
                    view.animate().scaleX(s).scaleY(s).rotationX(0f).rotationY(0f)
                            .translationY(-5f * density)
                            .translationZ(16f * density)
                            .setDuration(420)
                            .setInterpolator(new OvershootInterpolator(1.1f))
                            .start();   // no withLayer: the glint moving inside would re-render the layer every frame
                    banner.setTranslationX(rotY == 0f ? 0f : (rotY > 0 ? -drift : drift));
                    banner.setTranslationY(rotX == 0f ? 0f : (rotX > 0 ? drift : -drift));
                    banner.animate().translationX(0f).translationY(0f)
                            .scaleX(advancedEffects ? 1.035f : 1f).scaleY(advancedEffects ? 1.035f : 1f)
                            .setDuration(520).setInterpolator(new DecelerateInterpolator(1.6f)).start();
                    int w = view.getWidth();
                    ViewGroup.LayoutParams glp = glint.getLayoutParams();
                    if (glp.width != w / 2) {
                        glp.width = w / 2;
                        glint.setLayoutParams(glp);
                    }
                    glint.setRotation(18f);
                    glint.setScaleY(1.6f);
                    glint.setTranslationX(-w * 0.6f);
                    glint.setAlpha(1f);
                    glint.animate().translationX(w * 1.1f).setStartDelay(90).setDuration(560)
                            .setInterpolator(new DecelerateInterpolator(1.2f))
                            .withEndAction(() -> glint.setAlpha(0f)).start();
                    name.animate().alpha(1f).translationY(density * 4f).setDuration(220).start();
                } else {
                    view.animate().scaleX(1f).scaleY(1f).rotationX(0f).rotationY(0f)
                            .translationY(0f).translationZ(0f)
                            .setDuration(200)
                            .setInterpolator(new DecelerateInterpolator())
                            .withLayer().start();
                    banner.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(200).start();
                    glint.animate().cancel();
                    glint.setAlpha(0f);
                    name.animate().alpha(0f).translationY(-density * 6f).setDuration(160).start();
                }
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
