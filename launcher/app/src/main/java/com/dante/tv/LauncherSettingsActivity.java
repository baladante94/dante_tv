package com.dante.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private TextView dockChoice;
    private TextView shelfChoice;
    private TextView wallChoice;
    private int columns;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                changed();
                refresh();
            });
        }
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        dockChoice = findViewById(R.id.dockChoice);
        dockChoice.setOnClickListener(v -> {
            int next = (DockDecoration.style(this) + 1) % 3;
            choices.edit().putInt(DockDecoration.PREF, next).apply();
            changed();
            refresh();
        });
        shelfChoice = findViewById(R.id.shelfChoice);
        shelfChoice.setOnClickListener(v -> {
            choices.edit().putBoolean("top_shelf", !choices.getBoolean("top_shelf", true)).apply();
            changed();
            refresh();
        });
        wallChoice = findViewById(R.id.wallChoice);
        wallChoice.setOnClickListener(v -> {
            // Theme default, then each picture in the walls folder, round and round.
            java.util.List<String> walls = Config.walls(this);
            int at = walls.indexOf(choices.getString(Config.WALLPAPER_PICK, ""));
            String next = at + 1 < walls.size() ? walls.get(at + 1) : "";
            choices.edit().putString(Config.WALLPAPER_PICK, next).apply();
            changed();
            refresh();
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove("animations").remove("advanced_effects").commit();
            Config current = Config.load(this);
            columns = current.columns;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            changed();
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)));
        refresh();
        columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    /**
     * Counts every change made here. Home compares it on each return, so the change applies however this screen
     * was left: Back delivers the activity result, but the Home key skips it.
     */
    static volatile int version;

    private void changed() {
        version++;
        setResult(RESULT_OK);
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        followThemeChoice.setSelected(!hasOnTvChoices());
        int[] names = {R.string.dock_dark, R.string.dock_light, R.string.dock_transparent};
        dockChoice.setText(getString(R.string.dock_state, getString(names[DockDecoration.style(this)])));
        String wall = choices.getString(Config.WALLPAPER_PICK, "");
        if (!Config.walls(this).contains(wall)) wall = "";
        wallChoice.setText(getString(R.string.wall_state, wall.isEmpty() ? getString(R.string.wall_default)
                : Character.toUpperCase(wall.charAt(0)) + wall.substring(1).replace('-', ' ')));
        shelfChoice.setText(getString(R.string.shelf_state,
                getString(choices.getBoolean("top_shelf", true) ? R.string.on : R.string.off)));
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains("animations")
                || choices.contains("advanced_effects");
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
