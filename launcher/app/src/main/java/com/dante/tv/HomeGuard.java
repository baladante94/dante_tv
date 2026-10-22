package com.dante.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

/**
 * Keeps Dante TV in front after a TV software update hands Home back to the stock launcher (which an update can
 * re-enable and make the default again). At boot it opens Dante TV when it is no longer the default home, and
 * InputKeyService answers the Home key the same way. Nothing changes while Dante TV is the default.
 * Fixing it for good needs ADB (set-home-activity), so MainActivity explains that once.
 */
public class HomeGuard extends BroadcastReceiver {

    static final String EXTRA_HOME_LOST = "com.dante.tv.HOME_LOST";

    static boolean isDefaultHome(Context ctx) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = ctx.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && ctx.getPackageName().equals(ri.activityInfo.packageName);
    }

    /** Dante TV, as if it were still the home screen. */
    static void openHome(Context ctx, boolean lost) {
        Intent i = new Intent(ctx, MainActivity.class).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (lost) i.putExtra(EXTRA_HOME_LOST, true);
        try {
            ctx.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    /**
     * Home screens of other launchers (the stock one included, disabled or not), by activity class. Leaves out the
     * setup wizard, Settings' FallbackHome and factory-test launchers: they answer HOME but are not a home screen.
     */
    static java.util.Set<String> otherHomes(Context ctx) {
        java.util.Set<String> out = new java.util.HashSet<>();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || pkg.contains("setupwraith") || pkg.contains("setupwizard")
                    || pkg.endsWith(".settings") || pkg.contains("factorytest") || pkg.contains("factorymode")) continue;
            out.add(ri.activityInfo.name);
        }
        return out;
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!isDefaultHome(ctx)) openHome(ctx, true);
    }
}
