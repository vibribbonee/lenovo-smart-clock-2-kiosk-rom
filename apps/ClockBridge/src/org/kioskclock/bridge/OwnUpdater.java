package org.kioskclock.bridge;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.UserManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * Keeps the ROM's own apps current from this project's GitHub releases, so new features arrive
 * over Wi-Fi instead of a USB flash: KioskNav first, then ClockBridge itself (installing it
 * restarts this app; BootReceiver brings it back on MY_PACKAGE_REPLACED). ClockCore (persistent,
 * mic anchor) can't be updated this way and doesn't need to be.
 *
 * Before installing: right package, signed by the project key (pinned certificate, the same one
 * the ROM build checks), and a higher versionCode than installed (never downgrades). Drafts and
 * pre-releases are ignored (GitHub's releases/latest). Settings.Global kioskclock_app_updates=0
 * turns it off; kioskclock_update_url points it at another release JSON (testing; the pin still applies).
 */
final class OwnUpdater {
    static final String RELEASES = "https://api.github.com/repos/vibribbonee/lenovo-smart-clock-2-kiosk-rom/releases/latest";
    static final String PINNED_CERT_SHA256 = "cd2a24e18ae6346eee70813a1001bb1febb401a384c52cc085e966c9303e4026";
    static final String SETTING = "kioskclock_app_updates", URL_SETTING = "kioskclock_update_url";
    /** package, release asset; ClockBridge last because installing it restarts us. */
    private static final String[][] TARGETS = {{"org.kioskclock.nav", "KioskNav.apk"}, {"org.kioskclock.bridge", "ClockBridge.apk"}};
    private static final long FIRST_CHECK_MS = 10 * 60_000L, INTERVAL_MS = 12L * 60 * 60 * 1000;

    private final BridgeService svc;
    volatile String lastResult = "not run yet";
    volatile long lastCheck;

    OwnUpdater(BridgeService svc) { this.svc = svc; }

    boolean enabled() { return !"0".equals(setting(SETTING)); }

    void loop() {
        KioskUpdater.sleep(FIRST_CHECK_MS);
        while (!Thread.currentThread().isInterrupted()) {
            if (enabled() && svc.getSystemService(UserManager.class).isUserUnlocked() && svc.updater.online()) {
                checkNow();
                KioskUpdater.sleep(INTERVAL_MS);
            } else {
                KioskUpdater.sleep(60_000);
            }
        }
    }

    synchronized String checkNow() {
        lastCheck = System.currentTimeMillis();
        String url = setting(URL_SETTING);
        if (url == null || !url.startsWith("https://")) url = RELEASES;
        StringBuilder done = new StringBuilder();
        try {
            JSONObject rel = new JSONObject(new String(KioskUpdater.get(url), "UTF-8"));
            String tag = rel.optString("tag_name", "?");
            JSONArray assets = rel.getJSONArray("assets");
            for (String[] t : TARGETS) {
                String assetUrl = null;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (t[1].equals(a.getString("name"))) { assetUrl = a.getString("browser_download_url"); break; }
                }
                if (assetUrl == null) continue;
                File apk = new File(svc.getCacheDir(), "own-" + t[1]);
                try {
                    KioskUpdater.download(assetUrl, apk);
                    String problem = verify(apk, t[0]);
                    if (problem == null) {
                        Log.i(App.TAG, "app update: installing " + t[0] + " from " + tag);
                        if (done.length() > 0) done.append(", ");
                        done.append(t[0].substring(t[0].lastIndexOf('.') + 1));
                        KioskUpdater.install(svc, apk, t[0]);
                    } else {
                        Log.i(App.TAG, "app update: " + t[0] + " " + problem);
                    }
                } finally {
                    if (apk.exists() && !apk.delete()) Log.w(App.TAG, "could not delete " + apk);
                }
            }
            return result(done.length() == 0 ? "up to date (" + tag + ")" : "installing " + done + " from " + tag);
        } catch (Exception e) {
            return result("error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** null if acceptable, else why not (including "not newer", the normal case). */
    private String verify(File apk, String pkg) throws Exception {
        PackageManager pm = svc.getPackageManager();
        PackageInfo pi = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (pi == null) return "not a valid APK";
        if (!pkg.equals(pi.packageName)) return "unexpected package " + pi.packageName;
        if (pi.signingInfo == null || pi.signingInfo.hasMultipleSigners()) return "unexpected signers";
        Signature[] sigs = pi.signingInfo.getApkContentsSigners();
        if (sigs.length != 1 || !PINNED_CERT_SHA256.equals(KioskUpdater.sha256(sigs[0].toByteArray())))
            return "signing certificate does not match";
        try {
            long cur = pm.getPackageInfo(pkg, 0).getLongVersionCode();
            if (pi.getLongVersionCode() <= cur) return "not newer (" + pi.getLongVersionCode() + " <= " + cur + ")";
        } catch (PackageManager.NameNotFoundException ignored) { }
        return null;
    }

    private String result(String r) {
        lastResult = r;
        Log.i(App.TAG, "app update: " + r);
        return r;
    }

    private String setting(String key) {
        return android.provider.Settings.Global.getString(svc.getContentResolver(), key);
    }
}
