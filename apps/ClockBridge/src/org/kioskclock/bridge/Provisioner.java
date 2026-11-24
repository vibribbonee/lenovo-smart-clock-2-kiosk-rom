package org.kioskclock.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/**
 * One-time settings after a fresh /data (first boot or factory reset). Idempotent and additive:
 * it only enables things, never disables user choices, and records completion per data wipe.
 * Requires WRITE_SECURE_SETTINGS (privapp permission in the ROM, or `pm grant` for testing).
 */
final class Provisioner {
    static final String NAV_SERVICE = "org.kioskclock.nav/org.kioskclock.nav.NavService";

    private static SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("provision", Context.MODE_PRIVATE);
    }

    static void reset(Context c) { prefs(c).edit().clear().apply(); }

    static void runOnce(Context c) {
        if (prefs(c).getBoolean("done_v1", false)) return;
        if (c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            Log.i(App.TAG, "provisioning skipped: WRITE_SECURE_SETTINGS not held");
            return;
        }
        try {
            if (isInstalled(c, "org.kioskclock.nav")) enableAccessibilityService(c, NAV_SERVICE);
            prefs(c).edit().putBoolean("done_v1", true).apply();
            Log.i(App.TAG, "provisioning done");
        } catch (RuntimeException e) {
            Log.w(App.TAG, "provisioning failed: " + e);
        }
    }

    private static boolean isInstalled(Context c, String pkg) {
        try { c.getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private static void enableAccessibilityService(Context c, String component) {
        String cur = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (cur == null) cur = "";
        for (String s : cur.split(":")) if (s.equalsIgnoreCase(component)) return;
        String next = cur.isEmpty() ? component : cur + ":" + component;
        Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
        Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Log.i(App.TAG, "enabled accessibility service " + component);
    }
}
