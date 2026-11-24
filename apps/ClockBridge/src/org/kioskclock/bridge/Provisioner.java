package org.kioskclock.bridge;

import android.app.AlarmManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * First-boot setup after a fresh /data (first boot or factory reset), driven by
 * /product/etc/kioskclock/provision.json (rom/product/etc/kioskclock/provision.json in the repo).
 * Runs once per config "version"; each step is idempotent, independent and logged, so one failing
 * step never blocks the others. Only enables/sets things; never touches user data.
 * Needs WRITE_SECURE_SETTINGS, CHANGE_COMPONENT_ENABLED_STATE, SET_TIME_ZONE (privapp allowlist).
 */
final class Provisioner {
    static final String CONFIG = "/product/etc/kioskclock/provision.json";

    private static SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("provision", Context.MODE_PRIVATE);
    }

    static void reset(Context c) { prefs(c).edit().clear().apply(); }

    static void runOnce(Context c) {
        JSONObject cfg = load();
        if (cfg == null) { Log.i(App.TAG, "provisioning: no " + CONFIG); return; }
        int version = cfg.optInt("version", 1);
        if (prefs(c).getInt("done_version", 0) >= version) return;
        if (c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            Log.i(App.TAG, "provisioning skipped: WRITE_SECURE_SETTINGS not held");
            return;
        }
        int failures = 0;
        failures += step("components", () -> enableComponents(c, cfg.optJSONArray("enable_components")));
        failures += step("input method", () -> inputMethod(c, cfg.optString("input_method", null)));
        failures += step("accessibility", () -> accessibility(c, cfg.optJSONArray("accessibility_services")));
        failures += step("disable packages", () -> disablePackages(c, cfg.optJSONArray("disable_packages")));
        failures += step("secure settings", () -> putAll(c, cfg.optJSONObject("secure_settings"), 's'));
        failures += step("system settings", () -> putAll(c, cfg.optJSONObject("system_settings"), 'y'));
        failures += step("global settings", () -> putAll(c, cfg.optJSONObject("global_settings"), 'g'));
        failures += step("time zone", () -> timeZone(c, cfg.optString("time_zone", null)));
        if (failures == 0) prefs(c).edit().putInt("done_version", version).apply();
        Log.i(App.TAG, "provisioning v" + version + " finished, failures=" + failures);
    }

    /**
     * "grants" in provision.json: special access (app-ops) and runtime permissions for named apps.
     * Runs at every start, not once: it's idempotent, and an app installed or reset later gets them too.
     * Needs MANAGE_APP_OPS_MODES / GRANT_RUNTIME_PERMISSIONS, which Android gives ClockBridge as the
     * package verifier (see VerifierReceiver); without them each grant is logged and skipped.
     */
    static void applyGrants(Context c) {
        JSONObject cfg = load();
        JSONObject grants = cfg == null ? null : cfg.optJSONObject("grants");
        if (grants == null) return;
        PackageManager pm = c.getPackageManager();
        for (Iterator<String> it = grants.keys(); it.hasNext(); ) {
            String pkg = it.next();
            if (pkg.startsWith("_")) continue;
            JSONObject g = grants.optJSONObject(pkg);
            int uid;
            try { uid = pm.getApplicationInfo(pkg, 0).uid; } catch (PackageManager.NameNotFoundException e) { continue; }
            JSONArray ops = g.optJSONArray("appops");
            for (int i = 0; ops != null && i < ops.length(); i++) {
                String op = ops.optString(i);
                step("appop " + op + " " + pkg, () -> {
                    android.app.AppOpsManager aom = c.getSystemService(android.app.AppOpsManager.class);
                    aom.getClass().getMethod("setMode", String.class, int.class, String.class, int.class)
                            .invoke(aom, op, uid, pkg, android.app.AppOpsManager.MODE_ALLOWED);
                });
            }
            JSONArray perms = g.optJSONArray("permissions");
            for (int i = 0; perms != null && i < perms.length(); i++) {
                String perm = perms.optString(i);
                if (pm.checkPermission(perm, pkg) == PackageManager.PERMISSION_GRANTED) continue;
                step("grant " + perm + " " + pkg, () ->
                        pm.getClass().getMethod("grantRuntimePermission", String.class, String.class, android.os.UserHandle.class)
                                .invoke(pm, pkg, perm, android.os.Process.myUserHandle()));
            }
            Log.i(App.TAG, "grants applied for " + pkg);
        }
    }

    private interface Step { void run() throws Exception; }

    private static int step(String name, Step s) {
        try { s.run(); return 0; }
        catch (Exception e) { Log.w(App.TAG, "provisioning step '" + name + "' failed: " + e); return 1; }
    }

    /** The parsed provision.json (also used for the button shortcuts), or null. */
    static JSONObject config() { return load(); }

    private static JSONObject load() {
        File f = new File(CONFIG);
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            return new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(App.TAG, "provisioning config unreadable: " + e);
            return null;
        }
    }

    private static void enableComponents(Context c, JSONArray list) throws Exception {
        if (list == null) return;
        PackageManager pm = c.getPackageManager();
        for (int i = 0; i < list.length(); i++) {
            ComponentName cn = ComponentName.unflattenFromString(list.getString(i));
            if (cn == null) continue;
            try { pm.getPackageInfo(cn.getPackageName(), 0); } catch (PackageManager.NameNotFoundException e) { continue; }
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            Log.i(App.TAG, "enabled component " + cn.flattenToShortString());
        }
    }

    private static void inputMethod(Context c, String ime) {
        if (ime == null || ime.isEmpty()) return;
        String pkg = ime.substring(0, ime.indexOf('/'));
        try { c.getPackageManager().getPackageInfo(pkg, 0); } catch (PackageManager.NameNotFoundException e) { return; }
        String enabled = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_INPUT_METHODS);
        if (enabled == null || enabled.isEmpty()) enabled = ime;
        else if (!(":" + enabled + ":").contains(":" + ime)) enabled = enabled + ":" + ime;
        Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_INPUT_METHODS, enabled);
        Settings.Secure.putString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD, ime);
        Log.i(App.TAG, "input method " + ime);
    }

    private static void accessibility(Context c, JSONArray list) throws Exception {
        if (list == null) return;
        for (int i = 0; i < list.length(); i++) {
            String comp = list.getString(i);
            ComponentName cn = ComponentName.unflattenFromString(comp);
            if (cn == null) continue;
            try { c.getPackageManager().getPackageInfo(cn.getPackageName(), 0); } catch (PackageManager.NameNotFoundException e) { continue; }
            String cur = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null) cur = "";
            boolean present = false;
            for (String s : cur.split(":")) if (s.equalsIgnoreCase(comp)) present = true;
            if (!present) {
                Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        cur.isEmpty() ? comp : cur + ":" + comp);
            }
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i(App.TAG, "accessibility service " + comp);
        }
    }

    private static void disablePackages(Context c, JSONArray list) throws Exception {
        if (list == null) return;
        PackageManager pm = c.getPackageManager();
        for (int i = 0; i < list.length(); i++) {
            String p = list.getString(i);
            try { pm.getPackageInfo(p, 0); } catch (PackageManager.NameNotFoundException e) { continue; }
            // DISABLED_USER is what "Disable" in Settings does: reversible from Settings → Apps.
            pm.setApplicationEnabledSetting(p, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0);
            Log.i(App.TAG, "disabled " + p);
        }
    }

    private static void putAll(Context c, JSONObject map, char table) throws Exception {
        if (map == null) return;
        for (Iterator<String> it = map.keys(); it.hasNext(); ) {
            String k = it.next(), v = map.getString(k);
            boolean ok = table == 's' ? Settings.Secure.putString(c.getContentResolver(), k, v)
                    : table == 'y' ? Settings.System.putString(c.getContentResolver(), k, v)
                    : Settings.Global.putString(c.getContentResolver(), k, v);
            Log.i(App.TAG, "setting " + table + ":" + k + "=" + v + (ok ? "" : " (refused)"));
        }
    }

    private static void timeZone(Context c, String tz) {
        if (tz == null || tz.isEmpty()) return;
        c.getSystemService(AlarmManager.class).setTimeZone(tz);
        Log.i(App.TAG, "time zone " + tz);
    }
}
