package org.kioskclock.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * Optional extra apps, installed on request (Clock settings → Extras) from their official GitHub
 * releases and kept up to date with the ROM's own apps. Today: SmartTube (YouTube for Android TV
 * without Google services; a cast target for the YouTube app via "connect using TV code").
 *
 * Checks: exact package name; first install remembers the signing certificate (the project
 * publishes no fingerprint), later ones must match it (Android enforces the same for updates);
 * never downgrades. State for the settings screen: Settings.Global kioskclock_extra_<id>.
 */
final class Extras {
    static final class Extra {
        final String id, pkg, releases, assetPrefix, assetSuffix;
        Extra(String id, String pkg, String releases, String assetPrefix, String assetSuffix) {
            this.id = id; this.pkg = pkg; this.releases = releases; this.assetPrefix = assetPrefix; this.assetSuffix = assetSuffix;
        }
    }

    static final Extra[] ALL = {
        new Extra("smarttube", "org.smarttube.stable", "https://api.github.com/repos/yuliskov/SmartTube/releases/latest",
                "SmartTube_stable_", "_armeabi-v7a.apk"),
    };

    private final BridgeService svc;

    Extras(BridgeService svc) { this.svc = svc; }

    static Extra find(String id) {
        for (Extra e : ALL) if (e.id.equals(id)) return e;
        return null;
    }

    private SharedPreferences prefs() {
        return svc.createDeviceProtectedStorageContext().getSharedPreferences("extras", Context.MODE_PRIVATE);
    }

    boolean installed(Extra e) {
        try { svc.getPackageManager().getPackageInfo(e.pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException x) { return false; }
    }

    void installAsync(String id) {
        Extra e = find(id);
        if (e == null) return;
        state(e, "installing");
        new Thread(() -> state(e, install(e, false)), "extra-" + id).start();
    }

    /** Periodic: newer releases of extras that are installed (called by OwnUpdater's loop). */
    void updateInstalled() {
        for (Extra e : ALL) if (installed(e)) { String r = install(e, true); Log.i(App.TAG, "extra " + e.id + ": " + r); }
    }

    private synchronized String install(Extra e, boolean onlyIfNewer) {
        File apk = new File(svc.getCacheDir(), "extra-" + e.id + ".apk");
        try {
            JSONObject rel = new JSONObject(new String(KioskUpdater.get(e.releases), "UTF-8"));
            String tag = rel.optString("tag_name", "?");
            if (onlyIfNewer && sameVersion(e, tag)) return "up to date (" + tag + ")";
            String url = null;
            JSONArray assets = rel.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                String n = assets.getJSONObject(i).getString("name");
                if (n.startsWith(e.assetPrefix) && n.endsWith(e.assetSuffix)) { url = assets.getJSONObject(i).getString("browser_download_url"); break; }
            }
            if (url == null) return "failed: no " + e.assetPrefix + "*" + e.assetSuffix + " in " + tag;
            KioskUpdater.download(url, apk);
            PackageManager pm = svc.getPackageManager();
            PackageInfo pi = KioskUpdater.archiveInfo(pm, apk);
            if (pi == null) return "failed: not a valid APK";
            if (!e.pkg.equals(pi.packageName)) return "failed: unexpected package " + pi.packageName;
            Signature[] sigs = KioskUpdater.signers(pi);
            if (sigs == null) return "failed: unexpected signers";
            String cert = KioskUpdater.sha256(sigs[0].toByteArray());
            String known = prefs().getString("cert_" + e.id, null);
            if (known != null && !known.equals(cert)) return "failed: signing certificate changed";
            try {
                long cur = pm.getPackageInfo(e.pkg, 0).getLongVersionCode();
                if (pi.getLongVersionCode() <= cur) return "up to date (" + tag + ")";
            } catch (PackageManager.NameNotFoundException ignored) { }
            prefs().edit().putString("cert_" + e.id, cert).apply();
            Log.i(App.TAG, "extra " + e.id + ": installing " + tag + " (certificate " + cert.substring(0, 16) + "…)");
            KioskUpdater.install(svc, apk, e.pkg);
            return "installing " + tag;
        } catch (Exception x) {
            return "failed: " + x.getClass().getSimpleName() + ": " + x.getMessage();
        } finally {
            if (apk.exists() && !apk.delete()) Log.w(App.TAG, "could not delete " + apk);
        }
    }

    private boolean sameVersion(Extra e, String tag) {
        try {
            String v = svc.getPackageManager().getPackageInfo(e.pkg, 0).versionName;
            return v != null && tag.replaceAll("[^0-9.]", "").equals(v.replaceAll("[^0-9.]", ""));
        } catch (PackageManager.NameNotFoundException x) { return false; }
    }

    /** The installer reports back here (InstallResultReceiver). */
    void onInstalled(String pkg, boolean ok, String msg) {
        for (Extra e : ALL) {
            if (!e.pkg.equals(pkg)) continue;
            state(e, ok ? "installed" : "failed: " + msg);
            if (ok) Provisioner.applyGrants(svc);   // e.g. SmartTube may update itself
        }
    }

    private void state(Extra e, String s) {
        try { Settings.Global.putString(svc.getContentResolver(), "kioskclock_extra_" + e.id, s); }
        catch (SecurityException ignored) { }
        Log.i(App.TAG, "extra " + e.id + ": " + s);
    }
}
