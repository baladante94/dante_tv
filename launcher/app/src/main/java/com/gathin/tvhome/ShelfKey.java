package com.gathin.tvhome;

import android.content.Context;
import android.provider.Settings;

import java.util.Locale;

/**
 * The owner's own TMDB key and region for the Top Shelf. The key is entered on the TV's setup screen (from a
 * phone) and kept in the launcher's private storage, never in the code or a theme file, so every TV that runs
 * Dante TV brings its own.
 */
final class ShelfKey {

    private static final String PREFS = "top_shelf";

    private ShelfKey() {
    }

    static String token(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tmdb", "");
    }

    static void save(Context ctx, String token) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("tmdb", token.trim()).apply();
    }

    static void clear(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("tmdb").apply();
    }

    /**
     * Two-letter country for what is trending and where it streams: a theme's "region" if set, else the TV's
     * language country (en-IN), else the country picked during the TV's setup, else US.
     */
    static String region(Context ctx, String override) {
        if (override != null && override.trim().length() == 2) return override.trim().toUpperCase(Locale.ROOT);
        String country = Locale.getDefault().getCountry();
        if (country != null && country.length() == 2) return country.toUpperCase(Locale.ROOT);
        String setup = Settings.Global.getString(ctx.getContentResolver(), "tv_setup_country");
        if (setup != null && setup.length() == 3) {
            for (String iso : Locale.getISOCountries()) {
                try {
                    if (new Locale("", iso).getISO3Country().equalsIgnoreCase(setup)) return iso;
                } catch (Exception ignored) {
                }
            }
        }
        return "US";
    }

    /** The country's name as the TV would show it, for the setup screen. */
    static String regionName(String iso) {
        return new Locale("", iso).getDisplayCountry();
    }
}
