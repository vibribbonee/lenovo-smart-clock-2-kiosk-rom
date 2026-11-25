package org.kioskclock.bridge;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Keeps Kiosk Satellite current from its GitHub releases.
 * The ROM ships a known-good copy in /product/app; this installs newer releases over it.
 *
 * Safety checks before installing:
 *  - the APK's package name is me.jxl.kiosk_satellite,
 *  - it is signed by the pinned Kiosk Satellite certificate,
 *  - its versionCode is higher than the installed one (never downgrades).
 * Silent install needs INSTALL_PACKAGES (privileged, granted to the ROM's priv-app). Without it
 * Android answers STATUS_PENDING_USER_ACTION and nothing is installed.
 */
final class KioskUpdater {
    static final String PKG = "me.jxl.kiosk_satellite";
    static final String RELEASES = "https://api.github.com/repos/jxlarrea/kiosk-satellite/releases/latest";
    /** SHA-256 of the Kiosk Satellite signing certificate (CN=Kiosk Satellite, OU=jxlarrea). */
    static final String PINNED_CERT_SHA256 = "2a77134ce11f65ba9d08bed669fdcb742a25633c5665a6135aeca30040b068fc";
    static final long INTERVAL_MS = 12L * 60 * 60 * 1000;
    static final String ACTION_RESULT = "org.kioskclock.bridge.INSTALL_RESULT";

    private final Context ctx;
    volatile String lastResult = "not run yet";
    volatile long lastCheck;

    KioskUpdater(Context ctx) { this.ctx = ctx; }

    private SharedPreferences prefs() {
        return ctx.createDeviceProtectedStorageContext().getSharedPreferences("updater", Context.MODE_PRIVATE);
    }

    boolean enabled() { return prefs().getBoolean("enabled", true); }

    void setEnabled(boolean on) { prefs().edit().putBoolean("enabled", on).apply(); }

    String installedVersion() {
        try { return ctx.getPackageManager().getPackageInfo(PKG, 0).versionName; }
        catch (PackageManager.NameNotFoundException e) { return null; }
    }

    /** Loop: wait for user unlock and network, check, then every INTERVAL_MS. */
    void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            if (enabled() && ctx.getSystemService(UserManager.class).isUserUnlocked() && online()) {
                checkNow();
                sleep(INTERVAL_MS);
            } else {
                sleep(60_000);
            }
        }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    boolean online() {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /** One check; returns a short human-readable result. */
    synchronized String checkNow() {
        lastCheck = System.currentTimeMillis();
        File apk = new File(ctx.getCacheDir(), "kiosk-update.apk");
        try {
            JSONObject rel = new JSONObject(new String(get(RELEASES), "UTF-8"));
            String tag = rel.getString("tag_name");
            String installed = installedVersion();
            if (tag.equals(installed)) return result("up to date (" + tag + ")");
            if (tag.equals(prefs().getString("rejected_tag", null))) return result("skipping " + tag + " (rejected before)");

            String abi = Build.SUPPORTED_ABIS[0];
            String url = null;
            JSONArray assets = rel.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.getString("name").endsWith("." + abi + ".apk")) { url = a.getString("browser_download_url"); break; }
            }
            if (url == null) return result("no " + abi + " APK in release " + tag);

            Log.i(App.TAG, "downloading Kiosk Satellite " + tag + " for " + abi);
            download(url, apk);
            String problem = verify(apk);
            if (problem != null) {
                prefs().edit().putString("rejected_tag", tag).apply();
                return result("rejected " + tag + ": " + problem);
            }
            install(apk);
            return result("installing " + tag + " (was " + installed + ")");
        } catch (Exception e) {
            return result("error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            // The installer session has its own copy once written; never keep large files around.
            if (apk.exists() && !apk.delete()) Log.w(App.TAG, "could not delete " + apk);
        }
    }

    private String result(String r) {
        lastResult = r;
        Log.i(App.TAG, "kiosk update: " + r);
        return r;
    }

    /** Returns null if the APK is acceptable, otherwise the reason. */
    private String verify(File apk) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        PackageInfo pi = archiveInfo(pm, apk);
        if (pi == null) return "not a valid APK";
        if (!PKG.equals(pi.packageName)) return "unexpected package " + pi.packageName;
        Signature[] sigs = signers(pi);
        if (sigs == null) return "unexpected signers";
        if (sigs.length != 1 || !PINNED_CERT_SHA256.equals(sha256(sigs[0].toByteArray()))) return "signing certificate does not match";
        try {
            long cur = pm.getPackageInfo(PKG, 0).getLongVersionCode();
            if (pi.getLongVersionCode() <= cur) return "not newer than installed versionCode " + cur;
        } catch (PackageManager.NameNotFoundException ignored) { }
        return null;
    }

    private void install(File apk) throws Exception { install(ctx, apk, PKG); }

    /** Silent install through PackageInstaller (INSTALL_PACKAGES); the result goes to InstallResultReceiver. */
    static void install(Context ctx, File apk, String pkg) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(pkg);
        p.setSize(apk.length());
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(ctx, InstallResultReceiver.class).setAction(ACTION_RESULT).putExtra("pkg", pkg);
            PendingIntent pend = PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(pend.getIntentSender());
        }
    }

    /** An APK file's package info including its signer (both APIs: see {@link #signers}). */
    @SuppressWarnings("deprecation")
    static PackageInfo archiveInfo(PackageManager pm, File apk) {
        return pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES);
    }

    /**
     * The APK's single signer, or null if it has none or several. On Android 10 getPackageArchiveInfo
     * leaves signingInfo null for a file that isn't installed (platform quirk); the older
     * PackageInfo.signatures is filled in, so fall back to it.
     */
    @SuppressWarnings("deprecation")
    static Signature[] signers(PackageInfo pi) {
        if (pi.signingInfo != null) {
            if (pi.signingInfo.hasMultipleSigners()) return null;
            Signature[] s = pi.signingInfo.getApkContentsSigners();
            if (s != null && s.length == 1) return s;
        }
        return pi.signatures != null && pi.signatures.length == 1 ? pi.signatures : null;
    }

    static byte[] get(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = new BufferedInputStream(c.getInputStream())) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                bo.write(buf, 0, n);
                if (bo.size() > 4 << 20) throw new IllegalStateException("release metadata too large");
            }
            return bo.toByteArray();
        } finally { c.disconnect(); }
    }

    static void download(String url, File dst) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = new BufferedInputStream(c.getInputStream()); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            long total = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                total += n;
                if (total > 600L << 20) throw new IllegalStateException("download larger than 600 MB");
            }
        } finally { c.disconnect(); }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(60_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "kioskclock-bridge");
        c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
        int code = c.getResponseCode();
        if (code != 200) throw new IllegalStateException("HTTP " + code + " for " + url);
        return c;
    }

    static String sha256(byte[] b) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }
}
