package com.dante.tv;

import java.util.Arrays;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * tvOS style home: a left menu rail, a pinned dock row and an app grid whose tiles lift and lean into focus.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener, TopShelf.Host {

    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    /** Started with this extra (the side panel's Optimize row): run Optimize once Home is up. */
    static final String EXTRA_WIFI_JOIN = "com.dante.tv.WIFI_JOIN";
    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private boolean rtlAppGrid;
    private static final long MINUTE_MS = 60_000L;
    /**
     * 2025-01-01. TVs without a clock battery boot at the epoch (08:00 in China) and keep it until network
     * time arrives; the clock, date and greeting stay hidden before then.
     */
    private static final long TIME_VALID_AFTER_MS = 1_735_689_600_000L;
    private String liveTvPkg;
    private String liveTvAction;
    private String inputAction;
    private String sourcePanelService;
    private String sourcePanelAction;

    private AppAdapter adapter;
    private Launchpad launchpad;
    /** The owner's TMDB key (Top Shelf setup screen, ShelfKey); empty leaves the Top Shelf off. */
    private String tmdbToken = "";
    private String region = "US";
    private TopShelf shelf;
    private boolean safeMode;
    private RecyclerView grid;
    private FrameLayout root;
    private View ambient;
    private TextView heroName;
    private int ambientColor = -1;
    private ValueAnimator ambientAnimator;
    private boolean heroShown;
    private boolean animations;
    private boolean advancedEffects;
    private TextView greeting;
    private TextView wifiLabel;
    private ImageView wifiIcon;
    private View sidebarBg, scrim, navHome, navApps, navInputs, navWifi;
    private final List<TextView> menuLabels = new ArrayList<>();
    private boolean menuOpen;
    /** The tile entrance plays once per process, not on every return to Home. */
    private static boolean entered;
    private View clockBlock;
    /** The network label's icon as last set, so a refresh that changes nothing does not lay the menu out again. */
    private int wifiIconRes;
    /** The Wi-Fi and Inputs labels have been filled in once; later resumes refresh them after the first frame. */
    private boolean pillsShown;
    private TextView shamsiDate;
    private boolean shamsi;
    /** Optional time-of-day backdrop (config.xml, sky_intensity); 0 keeps it hidden. */
    private SkyView sky;
    private String vpnApp;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    private boolean reducedMotion;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** Rebuild the tiles on resume: Home was fully hidden by an app, or Home was pressed. Not after a side panel such as Wi-Fi settings. */
    private boolean rebuildOnResume = true;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs row once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            AppAdapter.forget(intent.getData() != null ? intent.getData().getSchemeSpecificPart() : null);
            reloadApps(false);
        }
    };
    // Right after boot the clock is often wrong until network time arrives; don't wait a minute to catch up.
    private final IntentFilter timeFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClockVisibility();
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            sky.update();
            if (shelf != null && System.currentTimeMillis() / 60_000L % (TopShelf.ROTATE_MS / 60_000L) == 0) shelf.rotate();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        // After repeated crashes, come up without the extras so the TV keeps a working Home.
        safeMode = Watchdog.safeMode(this);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        COLUMNS = cfg.columns;
        animations = cfg.animations;
        advancedEffects = cfg.animations && cfg.advancedEffects;
        reducedMotion = cfg.reducedMotion;
        liveTvPkg = cfg.liveTvPackage;
        liveTvAction = cfg.liveTvAction;
        inputAction = cfg.inputAction;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;

        root = findViewById(R.id.root);
        // Root paints every pixel, so the window's own black fill underneath is wasted work each frame;
        // so is the root colour under an opaque wallpaper.
        getWindow().setBackgroundDrawable(null);
        if (safeMode || cfg.wallpaper == null || !showWallpaper(cfg.wallpaper)) root.setBackgroundColor(cfg.backgroundColor);
        sky = findViewById(R.id.sky);
        sky.setIntensity(safeMode ? 0 : cfg.skyIntensity);
        ambient = findViewById(R.id.ambient);
        // The glow is one bitmap, so its alpha can be applied while drawing it. Otherwise every frame of
        // a fade renders it into a full-screen offscreen buffer first.
        ambient.forceHasOverlappingRendering(false);
        heroName = findViewById(R.id.heroName);
        greeting = findViewById(R.id.greeting);
        wifiLabel = findViewById(R.id.wifiLabel);
        wifiIcon = findViewById(R.id.wifiIcon);
        clockBlock = findViewById(R.id.clockBlock);
        shamsiDate = findViewById(R.id.shamsiDate);
        shamsi = cfg.shamsiDate;
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);

        grid = findViewById(R.id.grid);
        rtlAppGrid = getResources().getBoolean(R.bool.rtl_app_grid);
        // Mirror only the app grid; the header and other locales keep their own layouts.
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS) {
            @Override
            protected boolean isLayoutRTL() {
                return rtlAppGrid;
            }
        });
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        // Keep a few off-screen rows built so scrolling back up rebinds nothing.
        grid.setItemViewCacheSize(COLUMNS * 3);
        grid.addItemDecoration(new DockDecoration(COLUMNS, density));
        grid.addItemDecoration(new SectionDecoration(adapter(cfg), COLUMNS, density,
                getResources().getFont(R.font.outfit_medium)));
        if (animations) {
            int enterAnim = reducedMotion ? R.anim.tile_enter_reduced : R.anim.tile_enter;
            float enterDelay = reducedMotion ? 0f : 0.06f;
            grid.setLayoutAnimation(new LayoutAnimationController(
                    AnimationUtils.loadAnimation(this, enterAnim), enterDelay));
        }
        grid.setAdapter(adapter(cfg));
        launchpad = new Launchpad(this, root, adapter, animations);
        // A key from an older theme file moves into the launcher's own storage once.
        if (ShelfKey.token(this).isEmpty() && !cfg.tmdbToken.isEmpty()) ShelfKey.save(this, cfg.tmdbToken);
        tmdbToken = safeMode ? "" : ShelfKey.token(this);
        region = ShelfKey.region(this, cfg.region);
        if (!tmdbToken.isEmpty()) {
            // Under the menu's scrim and panel (scrim is not looked up yet, so find it here).
            shelf = new TopShelf(this, root, root.indexOfChild(findViewById(R.id.scrim)), this, animations);
            shelf.setItems(Trending.cached(this));
            grid.post(this::prefetchShelf);
        }
        if (safeMode) Toast.makeText(this, R.string.safe_mode, Toast.LENGTH_LONG).show();
        // Running a few minutes without crashing ends safe mode for the next start.
        handler.postDelayed(() -> Watchdog.clear(this), Watchdog.STABLE_MS);
        if (getIntent() != null && getIntent().hasExtra(EXTRA_WIFI_JOIN)) {
            String ssid = getIntent().getStringExtra(EXTRA_WIFI_JOIN);
            handler.postDelayed(() -> askWifiPassword(ssid), 600);
        }

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for
        // rows_below_dock grid rows under it; the rest of the grid stays below until you press Down.
        int rowsBelowDock = cfg.rowsBelowDock;
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            // Item height follows the tile size, which follows the column count (AppAdapter.tileWidthPx).
            int inner = (r - l) - grid.getPaddingLeft() - grid.getPaddingRight();
            int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(AppAdapter.tileWidthPx(inner, COLUMNS, density)), density);
            int dockRow = itemH + (int) ((6 + 12) * density);   // item height plus DockDecoration offsets
            int gridRows = itemH * rowsBelowDock;
            int want = Math.max(0, h - dockRow - gridRows - (int) (30 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        setUpMenu();

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
    }

    private AppAdapter adapter(Config cfg) {
        if (adapter == null) adapter = new AppAdapter(this, this, COLUMNS, cfg);
        return adapter;
    }

    /**
     * A theme's full-screen picture behind everything. Decoded once at the size CENTER_CROP shows, so it
     * costs a screen-sized bitmap (about 4 MB at 1080p), which is the price of a wallpaper on a 1 GB TV.
     * A picture over 4K is refused rather than decoded (#166); the plain colour stays.
     */
    private boolean showWallpaper(File file) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            int w = opts.outWidth, h = opts.outHeight;
            if (!WallpaperSize.accepted(w, h)) return;
            int sample = WallpaperSize.sampleSize(w, h, dm.widthPixels, dm.heightPixels);
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(w / sample, h / sample, dm.widthPixels, dm.heightPixels);
            if (scale != null) {
                opts.inScaled = true;
                opts.inDensity = scale[0];
                opts.inTargetDensity = scale[1];
            }
            opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;   // no alpha needed, half the memory
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            if (bmp == null) return false;
            android.widget.ImageView wall = new android.widget.ImageView(this);
            wall.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            wall.setImageBitmap(bmp);
            root.addView(wall, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return true;
        } catch (Exception | OutOfMemoryError e) {
            // A bad or huge picture must never take the home screen down; the plain colour stays.
            return false;
        }
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the top with the first dock tile focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the app you left from is unchanged (#118).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.hasExtra(EXTRA_WIFI_JOIN)) {
            String ssid = intent.getStringExtra(EXTRA_WIFI_JOIN);
            handler.postDelayed(() -> askWifiPassword(ssid), 300);
            return;
        }
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        grid.scrollToPosition(0);
        launchpad.close(false);
        closeMenu();
        lastFocused = 0;
        lastFocusedKey = adapter.getItemCount() > 0 ? adapter.keyAt(0) : null;
        rebuildOnResume = true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(timeReceiver, timeFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
            registerReceiver(timeReceiver, timeFilter);
        }
        updateClockVisibility();
    }

    @Override
    protected void onResume() {
        super.onResume();
        promptsPaused = false;
        if (rebuildOnResume) {
            tilesReady = false;
            reloadApps(true);
        }
        rebuildOnResume = false;
        ensureServices();
        updateGreeting();
        // The Wi-Fi and Inputs labels keep their state while Home is in the background and callbacks update them, so
        // this refresh only catches anything missed. Its binder calls (WifiManager.getConnectionInfo mostly) would
        // hold up the first frame back on Home, so after the first resume it runs once that frame is drawn.
        if (pillsShown) {
            refreshPillsAfterNextDraw();
        } else {
            updateWifiLabel();
            updateInputsButton();
            pillsShown = true;
        }
        // New trending rows arrive in the background; show them the next time Home is not in use.
        // A refresh can finish while another Home instance is on screen (Home restarts after setup): an empty
        // shelf rereads what is on disk each time Home comes back.
        if (shelf != null && !shelf.hasItems()) {
            shelf.setItems(Trending.cached(this));
            prefetchShelf();
        }
        if (shelf != null && shelfReleased) {
            shelfReleased = false;
            prefetchShelf();
            if (lastFocused < COLUMNS) shelf.showFor(adapter.pkgAt(lastFocused));
        }
        if (shelf != null) Trending.refreshIfStale(this, tmdbToken, region, () -> runOnUiThread(() -> {
            shelf.setItems(Trending.cached(this));
            prefetchShelf();
            if (lastFocused < COLUMNS) shelf.showFor(adapter.pkgAt(lastFocused));
        }));
        sky.update();
        handler.postDelayed(minuteTick, MINUTE_MS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LAUNCHER_SETTINGS_REQUEST && resultCode == RESULT_OK) recreate();
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = animate || grid.hasFocus();
        if (restoreFocus) {
            heroShown = false;
            heroName.setAlpha(0f);
            ambient.setAlpha(0f);
        }
        reloadTiles();
        if (animate && animations && !reducedMotion && !entered) {
            grid.scheduleLayoutAnimation();
            entered = true;
        }

        if (restoreFocus) {
            // Follow the same app, or the nearest remaining slot when it was removed.
            restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.getItemCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            tilesReady = true;
            showCardPrompt();
        }
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<Cards.Card> none = Collections.emptyList();
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, hiddenApps, Cards.load(Cards.storeFile(this))), none) : none;
        adapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none));
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            showCardPrompt();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (moving >= 0) endMove();
        stopAmbientAnimation();
        heroName.animate().cancel();
        ambient.animate().cancel();
        handler.removeCallbacks(minuteTick);
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (openPopup != null) openPopup.dismiss();
    }

    /** A game or film needing memory while Home is hidden gets the Top Shelf pictures back; Home redraws on return. */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        boolean pressure = level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL
                || level >= TRIM_MEMORY_BACKGROUND;
        if (pressure && shelf != null && !hasWindowFocus()) {
            shelf.release();
            shelfReleased = true;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(packageReceiver);
        unregisterReceiver(timeReceiver);
        handler.removeCallbacks(clockCheck);
        rebuildOnResume = true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) showCardPrompt();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back only closes All Apps or the menu.
        if (launchpad.isOpen()) {
            launchpad.close(true);
            focusTile(lastFocused);
        } else if (menuOpen) {
            focusTile(lastFocused);
        }
    }

    // ---- focus -----------------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 8f * density;        // room for the lifted tile's zoom and shadow
        if (shelf != null) {
            shelf.setShown(position < COLUMNS);
            if (position < COLUMNS) shelf.showFor(adapter.pkgAt(position));
        }
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            // A 1px mismatch used to start a scroll on every dock move. Only scroll when the row is
            // actually off; reduced motion does it in one jump so it does not fight the highlight.
            boolean scroll = Math.abs(dy) > (int) (8 * density);
            if (scroll) {
                if (!animations || reducedMotion) grid.scrollBy(0, dy);
                else grid.smoothScrollBy(0, dy);
            }
            showHero(position, scroll ? r.top - dy : r.top);
        } else {
            hideHero();
            // Scroll just enough so the lifted tile (not only its cell) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, reducedMotion);
        }
    }

    @Override
    public void onTileClicked(int position, Runnable launch) {
        launch.run();
    }

    // ---- rearranging -------------------------------------------------------------------------------

    /** The tile being moved, by position; -1 when not moving. */
    private int moving = -1;

    /** Hold OK on a tile: Move it, or open its App info. */
    @Override
    public void onTileLongPressed(int position) {
        if (moving >= 0 || position < 0 || adapter.isSpacer(position) || position >= adapter.movableCount()) return;
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(position);
        if (vh == null) return;
        View card = vh.itemView.findViewById(R.id.card);
        final String key = adapter.keyAt(position);
        final String pkg = adapter.pkgAt(position);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (220 * density));
        addSettingsMenuRow(rows, popup, R.string.move_app, () -> startMove(key));
        addSettingsMenuRow(rows, popup, R.string.app_info, () -> adapter.openAppInfo(pkg));
        // Above the tile: the dock sits at the bottom of the screen, so a menu below it would be cut off.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.showAsDropDown(card, 0, -(card.getHeight() + panel.getMeasuredHeight() + (int) (12 * density)));
        rows.getChildAt(0).requestFocus();
    }

    private void startMove(String key) {
        // Without Jump back in, the grid on screen is exactly the order being edited.
        key = key.replace("#recent", "");
        if (shelf != null) shelf.setShown(false);
        adapter.setReordering(true, key);
        reloadTiles();
        final String k = key;
        grid.post(() -> {
            moving = adapter.positionOf(k);
            if (moving < 0) {
                endMove();
                return;
            }
            focusTile(moving);
            Toast.makeText(this, getString(R.string.moving_app, adapter.labelAt(moving)), Toast.LENGTH_LONG).show();
        });
    }

    /** While moving, the arrows carry the tile (a row up or down, a place left or right); OK or Back puts it down. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (moving < 0) return super.dispatchKeyEvent(event);
        int code = event.getKeyCode();
        boolean drop = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_BACK;
        if (drop) {
            if (event.getAction() == KeyEvent.ACTION_UP) endMove();
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        int count = adapter.movableCount(), target = moving;
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) target = moving - 1;
        else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) target = moving + 1;
        else if (code == KeyEvent.KEYCODE_DPAD_UP) target = moving - COLUMNS;
        else if (code == KeyEvent.KEYCODE_DPAD_DOWN) target = Math.min(moving + COLUMNS, count - 1);
        else return true;
        if (target < 0 || target >= count || target == moving) return true;
        adapter.move(moving, target);
        moving = target;
        final int at = target;
        grid.post(() -> focusTile(at));
        return true;
    }

    private void endMove() {
        if (moving < 0 && !adapter.rowLabelsHidden()) return;
        int at = moving;
        moving = -1;
        adapter.saveOrder();
        lastFocusedKey = adapter.keyAt(Math.max(0, at));
        lastFocused = Math.max(0, at);
        adapter.setReordering(false, null);
        reloadTiles();
        restoreFocus();
        Toast.makeText(this, R.string.order_saved, Toast.LENGTH_SHORT).show();
    }

    private void prefetchShelf() {
        List<String> dock = new ArrayList<>();
        for (int i = 0; i < COLUMNS; i++) dock.add(adapter.pkgAt(i));
        shelf.prefetch(dock);
    }

    @Override
    public void openMenuFromShelf() {
        openMenu(navHome);
    }

    @Override
    public void leaveShelf() {
        AppAdapter.pendingMove = KeyEvent.KEYCODE_DPAD_DOWN;
        focusTile(lastFocused);
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        if (shelf != null && shelf.hasItems()) {
            // The shelf's heading takes the title's place; keep only the ambient glow.
            heroName.setAlpha(0f);
            setAmbient(adapter.ambientColorAt(position));
            if (!heroShown) {
                ambient.animate().cancel();
                ambient.setAlpha(0f);
                ambient.animate().alpha(1f).setDuration(animations ? 400 : 0).start();
                heroShown = true;
            }
            return;
        }
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (!animations || reducedMotion) {
            // Snap the shelf instead of sliding and fading it: each of those frames repaints the
            // full-screen panel, and this switch exists for the GPUs that cannot afford it.
            heroName.animate().cancel();
            ambient.animate().cancel();
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.setAlpha(1f);
            setAmbient(color);
            if (!animations) {
                ambient.setAlpha(1f);
                heroShown = true;
            } else if (!heroShown) {
                ambient.setAlpha(0f);
                ambient.animate().alpha(1f).setDuration(160).start();
                heroShown = true;
            }
            return;
        }

        if (!heroShown) {
            stopAmbientAnimation();
            heroName.animate().cancel();
            ambient.animate().cancel();
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            if (advancedEffects) heroName.animate().cancel();
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (advancedEffects) {
                transitionAmbient(color);
            } else if (color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            heroName.setY(y);
        }
    }

    /**
     * The glow used to be a full-screen radial GradientDrawable. On Android 10 that drawable is
     * drawn on the CPU, so fading it for half a second redrew 1920×1080 every frame and the
     * highlight looked like it was running at a few frames per second. A small bitmap stretched
     * over the screen is one texture. It is painted once in white and tinted with the app's colour,
     * so changing apps neither repaints it on the CPU (about 11 ms) nor uploads it again.
     */
    private void setAmbient(int color) {
        if (color == ambientColor && ambient.getBackground() != null) return;
        ambientColor = color;
        BitmapDrawable glow = (BitmapDrawable) ambient.getBackground();
        if (glow == null) {
            int w = 480;
            int h = 270;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new RadialGradient(
                    w * 0.32f, h * 0.42f, w * 0.62f,
                    new int[]{0x5CFFFFFF, 0x26FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            glow = new BitmapDrawable(getResources(), bmp);
            glow.setGravity(Gravity.FILL);
            ambient.setBackground(glow);
        }
        // The default tint mode, SRC_IN, keeps the mask's alpha and takes the colour from the tint.
        glow.setTint(0xFF000000 | color);
    }

    private void transitionAmbient(int color) {
        // A rapid focus change continues from the colour actually displayed on screen.
        stopAmbientAnimation();
        ambient.animate().cancel();
        if (color == ambientColor) {
            ambient.animate().alpha(1f).setDuration(180).start();
            return;
        }
        ambient.animate().alpha(1f).setDuration(180).start();
        ambientAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, color);
        ambientAnimator.setDuration(360);
        ambientAnimator.addUpdateListener(animation -> setAmbient((int) animation.getAnimatedValue()));
        ambientAnimator.start();
    }

    private void stopAmbientAnimation() {
        if (ambientAnimator != null) {
            ambientAnimator.cancel();
            ambientAnimator = null;
        }
    }

    private void hideHero() {
        if (!heroShown) return;
        stopAmbientAnimation();
        heroName.animate().cancel();
        ambient.animate().cancel();
        heroShown = false;
        if (animations) {
            heroName.animate().alpha(0f).setDuration(reducedMotion ? 100 : 160).start();
            ambient.animate().alpha(0f).setDuration(reducedMotion ? 140 : 300).start();
        } else {
            heroName.setAlpha(0f);
            ambient.setAlpha(0f);
        }
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or
        // escapes up into the top bar. Mid-row moves fall through to the default focus search.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            boolean left = keyCode == KeyEvent.KEYCODE_DPAD_LEFT;
            boolean edge = GridNavigation.atHorizontalEdge(position, count, COLUMNS, rtlAppGrid, left);
            if (edge && left && !rtlAppGrid) {
                openMenu(position < COLUMNS ? navHome : navApps);
                return true;
            }
            // The end of a part-filled Jump back in row behaves like the edge of the grid.
            if (!left && adapter.isSpacer(position + 1)) edge = true;
            if (!edge) AppAdapter.pendingMove = keyCode;
            return edge;
        }
        AppAdapter.pendingMove = keyCode;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) {                      // dock row: the Top Shelf, if there is one
                AppAdapter.pendingMove = 0;
                if (shelf != null) shelf.focusFirst();
                return true;
            }
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) {                // already on the last row: stay put
                AppAdapter.pendingMove = 0;
                return true;
            }
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) {
            navHome.requestFocus();
            return;
        }
        int clamped = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        while (clamped > 0 && adapter.isSpacer(clamped)) clamped--;   // the nearest app to the left in that row
        final int pos = clamped;
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the top shelf by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        if (!animations) {
            grid.scrollToPosition(pos);
            grid.post(() -> {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
                if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
            });
            return;
        }
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- left menu -------------------------------------------------------------------------------

    private void setUpMenu() {
        sidebarBg = findViewById(R.id.sidebarBg);
        scrim = findViewById(R.id.scrim);
        navHome = findViewById(R.id.navHome);
        navApps = findViewById(R.id.navApps);
        navInputs = findViewById(R.id.navInputs);
        navWifi = findViewById(R.id.navWifi);
        View navSettings = findViewById(R.id.navSettings);
        View navOptimize = findViewById(R.id.navOptimize);
        View navQuick = findViewById(R.id.navQuick);
        navQuick.setOnClickListener(v -> showQuick(navQuick));
        navOptimize.setOnClickListener(v -> optimize(navOptimize));
        View menu = findViewById(R.id.menu);
        sidebarBg.setTranslationX(-280 * density);
        // The panel and the scrim are flat fills: draw their alpha directly instead of via an offscreen buffer.
        sidebarBg.forceHasOverlappingRendering(false);
        scrim.forceHasOverlappingRendering(false);

        navHome.setOnClickListener(v -> {
            grid.scrollToPosition(0);
            AppAdapter.pendingMove = KeyEvent.KEYCODE_DPAD_RIGHT;
            focusTile(0);
        });
        navApps.setOnClickListener(v -> launchpad.open(sidebarBg, scrim, findViewById(R.id.menu)));
        navInputs.setOnClickListener(v -> showInputs());
        navWifi.setOnClickListener(v -> {
            if (vpnApp.isEmpty()) showWifi();
            else openNetworkPill();
        });
        navWifi.setOnLongClickListener(v -> {
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        navSettings.setOnClickListener(v -> open(systemIntent(Settings.ACTION_SETTINGS)));
        navSettings.setOnLongClickListener(v -> {
            showSettingsMenu();
            return true;
        });

        View[] items = {navHome, navApps, navInputs, navWifi, navQuick, navOptimize, navSettings};
        for (int i = 0; i < items.length; i++) {
            View item = items[i];
            menuLabels.add((TextView) ((ViewGroup) item).getChildAt(1));
            final boolean first = i == 0, last = i == items.length - 1;
            item.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    AppAdapter.pendingMove = KeyEvent.KEYCODE_DPAD_RIGHT;
                    focusTile(lastFocused);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return true;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return first;
                return keyCode == KeyEvent.KEYCODE_DPAD_DOWN && last;
            });
            if (animations) {
                item.setOnFocusChangeListener((view, focused) -> view.animate()
                        .scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f)
                        .setDuration(160).start());
            }
        }

        // The menu is open exactly while one of its rows has focus.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean inMenu = newF != null && newF.getParent() == menu;
            if (inMenu) expandMenu();
            else collapseMenu();
        });
    }

    private void openMenu(View row) {
        row.requestFocus();
    }

    private void closeMenu() {
        if (menuOpen) focusTile(lastFocused);
    }

    /** Slides the frosted panel in from the left and fades the labels in; everything is translation and alpha. */
    private void expandMenu() {
        if (menuOpen) return;
        menuOpen = true;
        hideHero();
        long d = animations ? (reducedMotion ? 120 : 260) : 0;
        android.view.animation.Interpolator ease = new android.view.animation.DecelerateInterpolator(1.8f);
        sidebarBg.animate().cancel();
        sidebarBg.animate().translationX(0f).alpha(1f).setDuration(d).setInterpolator(ease).start();
        scrim.animate().alpha(0.55f).setDuration(d).start();
        for (int i = 0; i < menuLabels.size(); i++) {
            TextView label = menuLabels.get(i);
            label.animate().cancel();
            label.setTranslationX(-14 * density);
            label.animate().alpha(1f).translationX(0f).setStartDelay(animations ? 40 + i * 25L : 0)
                    .setDuration(d).setInterpolator(ease).start();
        }
    }

    private void collapseMenu() {
        if (!menuOpen) return;
        menuOpen = false;
        long d = animations ? (reducedMotion ? 100 : 200) : 0;
        sidebarBg.animate().cancel();
        sidebarBg.animate().translationX(-280 * density).alpha(0f).setDuration(d)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.4f)).start();
        scrim.animate().alpha(0f).setDuration(d).start();
        for (TextView label : menuLabels) {
            label.animate().cancel();
            label.animate().alpha(0f).translationX(-14 * density).setStartDelay(0).setDuration(d / 2).start();
        }
    }

    // ---- header and network ----------------------------------------------------------------------

    /** Some TVs (Coocaa OS) set the network time without ACTION_TIME_CHANGED, so keep checking until it is valid. */
    private final Runnable clockCheck = this::updateClockVisibility;

    private void updateClockVisibility() {
        boolean valid = System.currentTimeMillis() >= TIME_VALID_AFTER_MS;
        clockBlock.setVisibility(valid ? View.VISIBLE : View.INVISIBLE);
        handler.removeCallbacks(clockCheck);
        if (valid) {
            updateGreeting();
        } else {
            handler.postDelayed(clockCheck, 1_000L);
        }
    }

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
        if (shamsi) shamsiDate.setText(Shamsi.today());
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The total paddings
            // include the Wi-Fi icon.
            TextPaint paint = wifiLabel.getPaint();
            float room = wifiLabel.getMaxWidth() - wifiLabel.getTotalPaddingStart() - wifiLabel.getTotalPaddingEnd()
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            text = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!TextUtils.equals(wifiLabel.getText(), label)) wifiLabel.setText(label);
        } else if (!TextUtils.equals(wifiLabel.getText(), text)) {
            wifiLabel.setText(text);
        }
        if (icon != wifiIconRes) {
            wifiIconRes = icon;
            wifiIcon.setImageResource(icon);
        }
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        open(i != null ? i : systemIntent(Settings.ACTION_WIFI_SETTINGS));
    }


    /**
     * China-market MIUI TV firmware answers no standard Settings intents at all; open Xiaomi's own
     * settings app (com.xiaomi.mitv.settings) there, so the Settings and Wi-Fi pills keep working.
     * On firmware with a standard settings handler the behaviour is unchanged.
     */
    private Intent systemIntent(String standardAction) {
        Intent i = new Intent(standardAction);
        if (i.resolveActivity(getPackageManager()) != null) return i;
        i = new Intent().setComponent(new ComponentName(
                "com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
        if (i.resolveActivity(getPackageManager()) != null) return i;
        return new Intent(standardAction);   // nothing found: let open() show the can't-open toast
    }

    /** Hides the Inputs row when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput()) {
                    any = true;
                    break;
                }
            }
        }
        navInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        try {
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI,
     *  which the Live TV app handles; where nothing takes that, the configured `input_action` is sent
     *  with the same URI instead (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer). */
    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = navInputs;
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        int width = (int) (300 * density);
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        boolean tunerIsAnalog = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // An HDMI-CEC device (an Apple TV, a soundbar) gets an input of its own whose parent is the
            // port it sits on, so the port and the device would each get a row (Vestel MT7332: "HDMI 1"
            // and "HDMI Apple TV"). The device takes its port's row instead, and a bare port is listed
            // only when nothing on it speaks CEC.
            Set<String> inputIds = new HashSet<>();
            for (TvInputInfo info : inputs) inputIds.add(info.getId());
            Map<String, List<TvInputInfo>> cecDevices = new HashMap<>();
            for (TvInputInfo info : inputs) {
                String parent = info.getParentId();
                if (parent == null || !inputIds.contains(parent)) continue;
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                List<TvInputInfo> onPort = cecDevices.get(parent);
                if (onPort == null) cecDevices.put(parent, onPort = new ArrayList<>());
                onPort.add(info);
            }
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    // Among tuners of the same kind, skip an analog input when another is present.
                    // MediaTek names them AnalogInputService and TunerInputService; alphabetical order
                    // would pick the analog one, and Live TV then opens a dead ATV source.
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    boolean analog = info.getId().toLowerCase(Locale.ROOT).contains("analog");
                    boolean better = tunerId == null
                            || (hw && !tunerIsHw)
                            || (hw == tunerIsHw && tunerIsAnalog && !analog)
                            || (hw == tunerIsHw && analog == tunerIsAnalog
                                    && info.getId().compareTo(tunerId) < 0);
                    if (better) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                        tunerIsAnalog = analog;
                    }
                }
                // Inputs hidden in the TV's Settings > Inputs stay out of the menu too.
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                // A CEC device's row is added with its port, below.
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                CharSequence custom = info.loadCustomLabel(this);
                boolean named = custom != null && custom.length() > 0 && !custom.toString().equals(label);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                List<TvInputInfo> devices = cecDevices.get(info.getId());
                if (devices != null) {
                    // "Apple TV · HDMI 1": the device's own name (or the one given to it or to its port in
                    // Settings > Inputs), then the port. Opening the device's input also makes it the CEC
                    // active source, which wakes it.
                    for (TvInputInfo device : devices) {
                        CharSequence name = device.loadCustomLabel(this);
                        if (name == null || name.length() == 0) name = named ? custom : device.loadLabel(this);
                        String deviceLabel = name == null || name.length() == 0 ? label : name + " \u00B7 " + label;
                        final String deviceId = device.getId();
                        int state = hdmiStateBogus ? -1 : tim.getInputState(deviceId);
                        addInputRow(rows, popup, R.drawable.ic_input, deviceLabel, state, () -> openInput(deviceId));
                    }
                    continue;
                }
                // The name given in Settings > Inputs (e.g. "MacBook Pro") goes first, the port after it.
                if (named) label = custom + " \u00B7 " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            // Tune that input only when the Live TV package itself handles the URI.
            // The unrestricted input player also resolves it (that is how HDMI opens), but on the
            // AIWA AI PONT SA that player stays black, and the Live TV package does not handle the
            // URI at all, which used to toast "Not available on this TV". Keep the leanback activity
            // then: it is the same path as the Live TV tile. Do not drop the package restriction.
            Intent tuned = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId),
                            "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
            if (tuned.resolveActivity(getPackageManager()) != null) liveTv = tuned;
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        dropIn(popup, panel, anchor);
        rows.getChildAt(0).requestFocus();
    }

    private PopupWindow glassPopup(View panel, int width) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Slide in beside a menu row: top-aligned with it, just right of the open panel. */
    private void dropIn(PopupWindow popup, View panel, View anchor) {
        panel.setAlpha(0f);
        panel.setTranslationX(-10 * density);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        popup.showAtLocation(root, Gravity.TOP | Gravity.LEFT, (int) (292 * density), at[1]);
        panel.animate().alpha(1f).translationX(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- Wi-Fi picker ------------------------------------------------------------------------------

    /** Networks around the TV in a panel beside the menu; OK joins one right there, asking for a password when needed. */
    private void showWifi() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return;
        }
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (360 * density));
        fillWifiRows(wm, rows, popup);
        // A fresh scan lands a few seconds later: redraw the list once, keeping focus on the same row.
        BroadcastReceiver scanned = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!popup.isShowing()) return;
                int at = 0;
                for (int i = 0; i < rows.getChildCount(); i++) if (rows.getChildAt(i).hasFocus()) at = i;
                fillWifiRows(wm, rows, popup);
                View row = rows.getChildAt(Math.min(at, rows.getChildCount() - 1));
                if (row != null) row.requestFocus();
            }
        };
        registerReceiver(scanned, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        popup.setOnDismissListener(() -> {
            unregisterReceiver(scanned);
            if (openPopup == popup) openPopup = null;
        });
        wm.startScan();
        int[] at = new int[2];
        navWifi.getLocationInWindow(at);
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), root.getHeight() - at[1] - (int) (34 * density)));
        dropIn(popup, panel, navWifi);
        rows.getChildAt(0).requestFocus();
    }

    private void fillWifiRows(WifiManager wm, LinearLayout rows, PopupWindow popup) {
        rows.removeAllViews();
        WifiInfo info = wm.getConnectionInfo();
        String current = info != null ? unquote(info.getSSID()) : "";
        Map<String, Integer> saved = new HashMap<>();
        try {
            List<WifiConfiguration> configs = wm.getConfiguredNetworks();
            if (configs != null) for (WifiConfiguration c : configs) saved.put(unquote(c.SSID), c.networkId);
        } catch (SecurityException ignored) {
        }
        // One row per network name, the strongest access point for it; the joined network first.
        Map<String, ScanResult> best = new HashMap<>();
        for (ScanResult r : wm.getScanResults()) {
            if (r.SSID == null || r.SSID.isEmpty()) continue;
            ScanResult b = best.get(r.SSID);
            if (b == null || r.level > b.level) best.put(r.SSID, r);
        }
        List<ScanResult> list = new ArrayList<>(best.values());
        Collections.sort(list, (a, b) -> {
            if (a.SSID.equals(current) != b.SSID.equals(current)) return a.SSID.equals(current) ? -1 : 1;
            return Integer.compare(b.level, a.level);
        });
        if (list.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_wifi, getString(R.string.wifi_searching), -1, () -> { });
        }
        for (ScanResult r : list) {
            boolean here = r.SSID.equals(current);
            Integer netId = saved.get(r.SSID);
            String status = here ? getString(R.string.connected)
                    : netId != null ? getString(R.string.wifi_saved)
                    : wifiSecurity(r) == 0 ? getString(R.string.wifi_open) : getString(R.string.wifi_secured);
            addInputRow(rows, popup, R.drawable.ic_wifi, r.SSID, -1, () -> joinWifi(wm, r, netId, here));
            View row = rows.getChildAt(rows.getChildCount() - 1);
            TextView st = row.findViewById(R.id.status);
            st.setText(status);
            st.setVisibility(View.VISIBLE);
            row.findViewById(R.id.dot).setVisibility(here ? View.VISIBLE : View.GONE);
        }
        addSeparator(rows);
        addInputRow(rows, popup, R.drawable.ic_settings, getString(R.string.wifi_settings), -1,
                () -> open(systemIntent(Settings.ACTION_WIFI_SETTINGS)));
    }

    static String unquote(String ssid) {
        return ssid == null ? "" : ssid.replace("\"", "").trim();
    }

    /** 0 open, 1 WEP, 2 WPA/WPA2 personal, 3 WPA3 personal only, 4 enterprise (needs a sign-in). */
    static int wifiSecurity(ScanResult r) {
        String c = r.capabilities == null ? "" : r.capabilities;
        if (c.contains("EAP")) return 4;
        if (c.contains("SAE") && !c.contains("PSK")) return 3;
        if (c.contains("PSK")) return 2;
        if (c.contains("WEP")) return 1;
        return 0;
    }

    private void joinWifi(WifiManager wm, ScanResult r, Integer savedId, boolean here) {
        if (here) return;
        int security = wifiSecurity(r);
        if (security == 4) {
            Toast.makeText(this, getString(R.string.wifi_enterprise, r.SSID), Toast.LENGTH_SHORT).show();
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return;
        }
        // A saved network joins without asking; one saved by Settings may refuse us, so then ask for its password.
        if (savedId != null && wm.enableNetwork(savedId, true)) {
            watchJoin(wm, r.SSID);
            return;
        }
        if (security == 0) {
            connectWifi(wm, r, security, null);
            return;
        }
        askWifiPassword(wm, r, security);
    }

    /** The side panel can't take typing (an overlay gets no keyboard), so it hands secured networks over here. */
    private void askWifiPassword(String ssid) {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null || ssid == null) return;
        ScanResult best = null;
        for (ScanResult r : wm.getScanResults()) {
            if (ssid.equals(r.SSID) && (best == null || r.level > best.level)) best = r;
        }
        if (best != null) askWifiPassword(wm, best, wifiSecurity(best));
    }

    private void askWifiPassword(WifiManager wm, ScanResult r, int security) {
        View panel = getLayoutInflater().inflate(R.layout.dialog_wifi_password, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.wifi_join_title, r.SSID));
        android.widget.EditText password = panel.findViewById(R.id.password);
        TextView reveal = panel.findViewById(R.id.btnShowPassword);
        TextView join = panel.findViewById(R.id.btnJoin);
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (460 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        reveal.setOnClickListener(v -> {
            boolean hidden = password.getTransformationMethod() != null;
            password.setTransformationMethod(hidden ? null : android.text.method.PasswordTransformationMethod.getInstance());
            password.setSelection(password.getText().length());
            reveal.setText(hidden ? R.string.wifi_hide_password : R.string.wifi_show_password);
        });
        Runnable submit = () -> {
            String key = password.getText().toString();
            if (key.length() < (security == 1 ? 5 : 8)) {
                password.requestFocus();
                return;
            }
            dialog.dismiss();
            connectWifi(wm, r, security, key);
        };
        join.setOnClickListener(v -> submit.run());
        password.setOnEditorActionListener((v, actionId, event) -> {
            submit.run();
            return true;
        });
        dialog.show();
        password.requestFocus();
    }

    private void connectWifi(WifiManager wm, ScanResult r, int security, String key) {
        WifiConfiguration c = new WifiConfiguration();
        c.SSID = "\"" + r.SSID + "\"";
        if (security == 0) {
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else if (security == 1) {
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            c.wepKeys[0] = "\"" + key + "\"";
            c.wepTxKeyIndex = 0;
        } else if (security == 3) {
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.SAE);
            c.preSharedKey = "\"" + key + "\"";
        } else {
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            c.preSharedKey = "\"" + key + "\"";
        }
        int id = wm.addNetwork(c);
        if (id == -1 || !wm.enableNetwork(id, true)) {
            Toast.makeText(this, getString(R.string.wifi_failed, r.SSID), Toast.LENGTH_LONG).show();
            return;
        }
        watchJoin(wm, r.SSID);
    }

    /** Says when the TV is on the new network, or that it gave up after 20 seconds (usually a wrong password). */
    private void watchJoin(WifiManager wm, String ssid) {
        if (openPopup != null) openPopup.dismiss();
        Toast.makeText(this, getString(R.string.wifi_connecting, ssid), Toast.LENGTH_SHORT).show();
        final long until = System.currentTimeMillis() + 20_000L;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                WifiInfo info = wm.getConnectionInfo();
                boolean on = info != null && unquote(info.getSSID()).equals(ssid)
                        && info.getSupplicantState() == android.net.wifi.SupplicantState.COMPLETED
                        && info.getIpAddress() != 0;
                if (on) {
                    Toast.makeText(MainActivity.this, getString(R.string.wifi_now_connected, ssid), Toast.LENGTH_SHORT).show();
                    updateWifiLabel();
                } else if (System.currentTimeMillis() < until) {
                    handler.postDelayed(this, 1000);
                } else {
                    Toast.makeText(MainActivity.this, getString(R.string.wifi_failed, ssid), Toast.LENGTH_LONG).show();
                }
            }
        }, 1000);
    }

    // ---- quick panel -------------------------------------------------------------------------------

    private static final int[] SLEEP_STEPS = {0, 15, 30, 60, 90};
    private android.bluetooth.BluetoothA2dp a2dp;

    /** Sleep timer, Picture & sound and Bluetooth headphones, in a panel beside the menu. */
    private void showQuick(View anchor) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (340 * density));
        fillQuick(rows, popup);
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        dropIn(popup, panel, anchor);
        rows.getChildAt(0).requestFocus();
        android.bluetooth.BluetoothAdapter bt = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
        if (bt != null && a2dp == null) {
            bt.getProfileProxy(this, new android.bluetooth.BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, android.bluetooth.BluetoothProfile proxy) {
                    a2dp = (android.bluetooth.BluetoothA2dp) proxy;
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    a2dp = null;
                }
            }, android.bluetooth.BluetoothProfile.A2DP);
        }
    }

    private void fillQuick(LinearLayout rows, PopupWindow popup) {
        rows.removeAllViews();
        // Sleep timer: OK steps through Off, 15, 30, 60, 90 minutes; hold OK to sleep right now.
        addInputRow(rows, popup, R.drawable.ic_moon, getString(R.string.sleep_timer), -1, () -> { });
        View sleep = rows.getChildAt(rows.getChildCount() - 1);
        TextView sleepStatus = sleep.findViewById(R.id.status);
        sleepStatus.setVisibility(View.VISIBLE);
        Runnable showSleep = () -> {
            int left = SleepReceiver.minutesLeft(this);
            sleepStatus.setText(left > 0 ? getString(R.string.sleep_in, left) : getString(R.string.sleep_off));
            sleep.findViewById(R.id.dot).setVisibility(left > 0 ? View.VISIBLE : View.GONE);
        };
        showSleep.run();
        sleep.setOnClickListener(v -> {
            int left = SleepReceiver.minutesLeft(this), next = 0;
            for (int step : SLEEP_STEPS) {
                if (step > left) {
                    next = step;
                    break;
                }
            }
            SleepReceiver.set(this, next);
            Toast.makeText(this, next > 0 ? getString(R.string.sleep_set, next) : getString(R.string.sleep_cancelled),
                    Toast.LENGTH_SHORT).show();
            showSleep.run();
        });
        sleep.setOnLongClickListener(v -> {
            popup.dismiss();
            if (!OptimizeService.sleepNow()) Toast.makeText(this, R.string.sleep_unavailable, Toast.LENGTH_LONG).show();
            return true;
        });
        addInputRow(rows, popup, R.drawable.ic_picture, getString(R.string.picture_sound), -1, () -> {
            try {
                startActivity(new Intent().setClassName("com.android.tv.settings",
                        "com.android.tv.settings.device.displaysound.DisplaySoundActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                open(systemIntent(Settings.ACTION_DISPLAY_SETTINGS));
            }
        });
        android.bluetooth.BluetoothAdapter bt = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
        int paired = 0;
        if (bt != null && bt.getBondedDevices() != null) {
            for (android.bluetooth.BluetoothDevice d : bt.getBondedDevices()) {
                if (d.getBluetoothClass() != null && d.getBluetoothClass().getMajorDeviceClass()
                        == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO) paired++;
            }
        }
        addInputRow(rows, popup, R.drawable.ic_bluetooth, getString(R.string.bluetooth), -1, () -> { });
        View btRow = rows.getChildAt(rows.getChildCount() - 1);
        TextView btStatus = btRow.findViewById(R.id.status);
        btStatus.setText(paired > 0 ? getString(R.string.bt_paired, paired) : getString(R.string.bt_none));
        btStatus.setVisibility(View.VISIBLE);
        btRow.setOnClickListener(v -> {
            fillBluetooth(rows, popup, bt);
            rows.getChildAt(0).requestFocus();
        });
    }

    /** Paired headphones and speakers: OK connects one; Pair new device opens Bluetooth settings. */
    private void fillBluetooth(LinearLayout rows, PopupWindow popup, android.bluetooth.BluetoothAdapter bt) {
        rows.removeAllViews();
        java.util.Set<android.bluetooth.BluetoothDevice> devices = bt != null ? bt.getBondedDevices() : null;
        List<android.bluetooth.BluetoothDevice> connected = a2dp != null ? a2dp.getConnectedDevices() : Collections.emptyList();
        if (devices != null) {
            for (android.bluetooth.BluetoothDevice d : devices) {
                // Headphones and speakers only: the remote and game pads are paired too.
                if (d.getBluetoothClass() == null || d.getBluetoothClass().getMajorDeviceClass()
                        != android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO) continue;
                String name = d.getName() != null ? d.getName() : d.getAddress();
                boolean on = connected.contains(d);
                addInputRow(rows, popup, R.drawable.ic_bluetooth, name, -1, () -> connectBluetooth(d, name));
                View row = rows.getChildAt(rows.getChildCount() - 1);
                TextView st = row.findViewById(R.id.status);
                st.setText(on ? getString(R.string.connected) : "");
                st.setVisibility(on ? View.VISIBLE : View.GONE);
                row.findViewById(R.id.dot).setVisibility(on ? View.VISIBLE : View.GONE);
            }
        }
        if (rows.getChildCount() > 0) addSeparator(rows);
        addInputRow(rows, popup, R.drawable.ic_settings, getString(R.string.bt_pair_new), -1,
                () -> open(bluetoothPairing()));
    }

    /** Google TV pairs headphones from its Accessories screen (CONNECT_INPUT); others have Bluetooth settings. */
    private Intent bluetoothPairing() {
        Intent pair = new Intent("com.google.android.intent.action.CONNECT_INPUT");
        if (pair.resolveActivity(getPackageManager()) != null) return pair;
        return systemIntent(Settings.ACTION_BLUETOOTH_SETTINGS);
    }

    /** BluetoothA2dp.connect is not public API; Android allows it for apps targeting 28. Settings if refused. */
    private void connectBluetooth(android.bluetooth.BluetoothDevice d, String name) {
        boolean asked = false;
        try {
            if (a2dp != null) {
                java.lang.reflect.Method connect = android.bluetooth.BluetoothA2dp.class
                        .getMethod("connect", android.bluetooth.BluetoothDevice.class);
                asked = Boolean.TRUE.equals(connect.invoke(a2dp, d));
            }
        } catch (Exception ignored) {
        }
        if (asked) Toast.makeText(this, getString(R.string.bt_connecting, name), Toast.LENGTH_SHORT).show();
        else open(bluetoothPairing());
    }

    private void refreshPillsAfterNextDraw() {
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                // Posted from inside the frame's traversal, so it runs after that frame is drawn. A post straight
                // from onResume would run first: the frame is only scheduled after onResume returns.
                handler.post(() -> {
                    if (isDestroyed()) return;
                    updateWifiLabel();
                    updateInputsButton();
                });
                return true;
            }
        });
        root.invalidate();   // a resume that changes nothing on screen still draws once, so the refresh runs
    }

    /**
     * Android switches an app's accessibility services off whenever the app is force-stopped. With
     * WRITE_SECURE_SETTINGS (granted once over ADB) Home puts the Input key and Optimize services back on
     * by itself; without it nothing happens and Optimize asks the owner to turn them on in Settings.
     */
    private void ensureServices() {
        if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        try {
            android.content.ContentResolver cr = getContentResolver();
            String on = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            StringBuilder want = new StringBuilder(on == null || "null".equals(on) ? "" : on);
            for (Class<?> svc : new Class<?>[]{InputKeyService.class, OptimizeService.class}) {
                String name = new ComponentName(this, svc).flattenToString();
                String shortName = new ComponentName(this, svc).flattenToShortString();
                if (want.indexOf(name) >= 0 || want.indexOf(shortName) >= 0) continue;
                if (want.length() > 0) want.append(':');
                want.append(shortName);
            }
            if (!want.toString().equals(on)) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, want.toString());
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
        } catch (SecurityException ignored) {
        }
    }

    // ---- optimize ----------------------------------------------------------------------------------

    private boolean optimizing;
    private boolean shelfReleased;

    /**
     * Frees memory by force-stopping the apps that have run since they were last stopped, through
     * OptimizeService (Android 14 no longer lets a launcher close other apps any other way), then says how
     * much came back. The keyboard, accessibility apps, Live TV, the Play Store and core system apps are left alone.
     */
    private void optimize(View row) {
        if (optimizing) return;
        if (!OptimizeService.available()) {
            Toast.makeText(this, R.string.optimize_enable, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        Map<String, String> apps = new java.util.LinkedHashMap<>();
        for (AppAdapter.Entry e : adapter.apps()) if (!apps.containsKey(e.pkg)) apps.put(e.pkg, e.label);
        Map<String, String> targets = OptimizeService.targets(this, apps, liveTvPkg);
        List<String> pkgs = new ArrayList<>(targets.keySet()), labels = new ArrayList<>(targets.values());
        android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
        android.app.ActivityManager.MemoryInfo before = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(before);
        if (pkgs.isEmpty()) {
            showOptimized(row, 0, before.availMem, before.totalMem);
            return;
        }
        optimizing = true;
        OptimizeService.run(pkgs, labels, true, stopped -> handler.postDelayed(() -> {
            optimizing = false;
            if (isFinishing()) return;
            android.app.ActivityManager.MemoryInfo after = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(after);
            if (!menuOpen) row.requestFocus();
            showOptimized(row, after.availMem - before.availMem, after.availMem, after.totalMem);
        }, 900));
    }

    private void showOptimized(View anchor, long freed, long free, long total) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (320 * density));
        String title = freed >= 20L << 20
                ? getString(R.string.optimize_freed, android.text.format.Formatter.formatShortFileSize(this, freed))
                : getString(R.string.optimize_nothing);
        addInputRow(rows, popup, R.drawable.ic_optimize, title, -1, () -> { });
        TextView status = rows.getChildAt(0).findViewById(R.id.status);
        status.setText(getString(R.string.optimize_free_now,
                android.text.format.Formatter.formatShortFileSize(this, free),
                android.text.format.Formatter.formatShortFileSize(this, total)));
        status.setVisibility(View.VISIBLE);
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (320 * density), panel.getMeasuredWidth()));
        dropIn(popup, panel, anchor);
        rows.getChildAt(0).requestFocus();
        handler.postDelayed(() -> {
            if (popup.isShowing()) popup.dismiss();
        }, 4000);
    }

    // ---- launcher settings and module cards -------------------------------------------------------

    /** Keep a normal Settings click for the TV; the launcher's choices live behind hold OK. */
    private void showSettingsMenu() {
        View anchor = findViewById(R.id.navSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (200 * density));
        addSettingsMenuRow(rows, popup, R.string.launcher_settings, () -> startActivityForResult(
                new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST));
        addSettingsMenuRow(rows, popup, R.string.shelf_setup, () -> startActivityForResult(
                new Intent(this, ShelfSetupActivity.class), LAUNCHER_SETTINGS_REQUEST));
        if (moduleCards) addSettingsMenuRow(rows, popup, R.string.module_cards, this::showModuleCards);
        addSettingsMenuRow(rows, popup, R.string.reset_order, () -> {
            adapter.resetOrder();
            reloadApps(false);
            Toast.makeText(this, R.string.order_reset, Toast.LENGTH_SHORT).show();
        });
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (200 * density), panel.getMeasuredWidth()));
        dropIn(popup, panel, anchor);
        rows.getChildAt(0).requestFocus();
    }

    private void addSettingsMenuRow(LinearLayout rows, PopupWindow popup, int title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        // The OK-up after opening the menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        rows.addView(row);
    }

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.navSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - at[1] - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
        dropIn(popup, panel, anchor);
        rows.getChildAt(0).requestFocus();
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }
}
