package app.unscroll;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.util.List;

/** Small helpers for text and app info. */
final class Fmt {
    private Fmt() {}

    /** "1 h 05 min", "23 min", "<1 min" */
    static String mins(long ms) {
        long m = ms / Engine.MIN;
        if (m < 1) return ms > 0 ? "<1 min" : "0 min";
        if (m < 60) return m + " min";
        return (m / 60) + " h " + String.format(java.util.Locale.ROOT, "%02d", m % 60) + " min";
    }

    /** "4:05" */
    static String clock(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        return (s / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60);
    }

    static String modeName(Engine.Rule r) {
        switch (r.mode) {
            case Engine.MODE_WARN: return "Warns after " + r.warnMin + " min";
            case Engine.MODE_LIMIT: return "Daily limit · " + r.limitMin + " min · " + waitText(r.unlockWaitSec) + " unlock wait";
            case Engine.MODE_BLOCK: return "Always blocked · " + waitText(r.unlockWaitSec) + " unlock wait";
            default: return "Not restricted";
        }
    }

    /** Full description, used when showing what a change would do. */
    static String ruleSummary(Engine.Rule r) {
        switch (r.mode) {
            case Engine.MODE_WARN: return "Warn after " + r.warnMin + " min a day";
            case Engine.MODE_LIMIT: return "Daily limit " + r.limitMin + " min, " + unlockSummary(r);
            case Engine.MODE_BLOCK: return "Always blocked, " + unlockSummary(r);
            default: return "Not restricted";
        }
    }

    /** getApplicationInfo with the new flags API on Android 13+. */
    @SuppressWarnings("deprecation")
    static ApplicationInfo appInfo(PackageManager pm, String pkg) throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= 33) {
            return pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0));
        }
        return pm.getApplicationInfo(pkg, 0);
    }

    /** queryIntentActivities with the new flags API on Android 13+. */
    @SuppressWarnings("deprecation")
    static List<ResolveInfo> activities(PackageManager pm, Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0));
        }
        return pm.queryIntentActivities(intent, 0);
    }

    private static String unlockSummary(Engine.Rule r) {
        return "unlocks of " + r.unlockMin + " min after a " + waitText(r.unlockWaitSec) + " wait";
    }

    /** "15 s", "2 min", "1 min 30 s" */
    static String waitText(int sec) {
        if (sec < 60) return sec + " s";
        int m = sec / 60, s = sec % 60;
        return s == 0 ? m + " min" : m + " min " + s + " s";
    }

    static String label(Context c, Engine.Rule r) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(appInfo(pm, r.pkg)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return r.label != null ? r.label : r.pkg;
        }
    }

    static boolean installed(Context c, String pkg) {
        try {
            appInfo(c.getPackageManager(), pkg);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static Drawable icon(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return c.getDrawable(android.R.drawable.sym_def_app_icon);
        }
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
