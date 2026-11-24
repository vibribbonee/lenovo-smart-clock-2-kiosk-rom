package org.kioskclock.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * Lenovo's PhoneWindowManager (system_server) turns the clock's hardware events into broadcasts:
 *   com.tonly.KEYCODE_TAP             tap on the top of the clock (accelerometer, KEYCODE_TAP=287)
 *   com.tonly.ACCESSORIES_INSERT/DROP clock placed on / lifted off the dock (keys 284/290)
 *   com.tonly.KEYCODE_VOLUME_UP/DOWN  volume buttons, extra EXTRA_DOWN = 1 (pressed) / 0 (released)
 *   com.tonly.MIC_MUTE_STATE_CHANGE   hardware mic switch moved
 * On stock the Google OEM app consumes these. Here they become events (webhook + local broadcast).
 * When no Google Assistant is installed, the volume buttons are handled here:
 *   short press = media volume step (1/15 of the 0..100 range) + a short click at the new level,
 *   hold (>= HOLD_MS) = configurable shortcut (provision.json "buttons"), the volume step is undone.
 */
public class OemKeyReceiver extends BroadcastReceiver {
    static final String TAP = "com.tonly.KEYCODE_TAP";
    static final String DOCK_IN = "com.tonly.ACCESSORIES_INSERT";
    static final String DOCK_OUT = "com.tonly.ACCESSORIES_DROP";
    static final String VOL_UP = "com.tonly.KEYCODE_VOLUME_UP";
    static final String VOL_DOWN = "com.tonly.KEYCODE_VOLUME_DOWN";
    static final String MIC = "com.tonly.MIC_MUTE_STATE_CHANGE";
    static final String ACTION_EVENT = "org.kioskclock.bridge.EVENT";
    /** Shown by KioskNav as a short volume bar (extra "percent"). */
    static final String ACTION_VOLUME = "org.kioskclock.bridge.VOLUME";
    /** For KioskNav's panel: extra "muted" (boolean) when the switch state is known. */
    static final String ACTION_MIC = "org.kioskclock.bridge.MIC";
    private static final long DOUBLE_TAP_MS = 450;
    private static final long HOLD_MS = 700;

    private final BridgeService svc;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable singleTap = () -> emit("tap", "single");
    private long lastTap;
    private final boolean handleVolume;
    private final String holdUp, holdDown;

    // state of the volume key currently held
    private String heldKey;
    private int volumeBefore = -1;
    private boolean holdFired;
    private final Runnable holdCheck = this::onHold;
    private int lastLight = 5;

    OemKeyReceiver(BridgeService svc, String holdUp, String holdDown) {
        this.svc = svc;
        this.holdUp = holdUp;
        this.holdDown = holdDown;
        handleVolume = !installed(svc, "com.google.assistant.core");
        Log.i(App.TAG, "volume buttons handled here: " + handleVolume + " (hold +: " + holdUp + ", hold -: " + holdDown + ")");
    }

    static boolean installed(Context c, String pkg) {
        try { return c.getPackageManager().getApplicationInfo(pkg, 0).enabled; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    IntentFilter filter() {
        IntentFilter f = new IntentFilter();
        for (String a : new String[]{TAP, DOCK_IN, DOCK_OUT, VOL_UP, VOL_DOWN, MIC}) f.addAction(a);
        return f;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (TAP.equals(a)) {
            long now = SystemClock.uptimeMillis();
            if (now - lastTap < DOUBLE_TAP_MS) {
                handler.removeCallbacks(singleTap);
                lastTap = 0;
                emit("tap", "double");
            } else {
                lastTap = now;
                handler.postDelayed(singleTap, DOUBLE_TAP_MS);
            }
        } else if (DOCK_IN.equals(a)) {
            emit("dock", "attached");
        } else if (DOCK_OUT.equals(a)) {
            emit("dock", "removed");
        } else if (MIC.equals(a)) {
            onMicSwitch(intent);
        } else if (handleVolume && (VOL_UP.equals(a) || VOL_DOWN.equals(a))) {
            if (intent.getIntExtra("EXTRA_DOWN", 0) == 1) onVolumeDown(a); else onVolumeUp(a);
        }
    }

    // ---- volume keys ------------------------------------------------------------------------

    private AudioManager audio() { return svc.getSystemService(AudioManager.class); }

    private void onVolumeDown(String key) {
        if (key.equals(heldKey)) return;            // auto-repeat while held
        heldKey = key;
        holdFired = false;
        volumeBefore = audio().getStreamVolume(AudioManager.STREAM_MUSIC);
        stepVolume(VOL_UP.equals(key) ? 1 : -1);    // immediate feedback, undone if it becomes a hold
        handler.removeCallbacks(holdCheck);
        handler.postDelayed(holdCheck, HOLD_MS);
    }

    private void onVolumeUp(String key) {
        handler.removeCallbacks(holdCheck);
        if (!holdFired && key.equals(heldKey)) click();
        heldKey = null;
    }

    private void onHold() {
        if (heldKey == null) return;
        holdFired = true;
        if (volumeBefore >= 0) setVolume(volumeBefore);   // a hold is a shortcut, not a volume change
        String action = VOL_UP.equals(heldKey)
                ? live("kioskclock_hold_volume_up", holdUp) : live("kioskclock_hold_volume_down", holdDown);
        emit("button", (VOL_UP.equals(heldKey) ? "hold_up" : "hold_down") + ":" + action);
        runShortcut(action);
    }

    /** Live override from Settings.Global (set via /api/settings), else the provision.json default. */
    private String live(String key, String def) {
        String v = android.provider.Settings.Global.getString(svc.getContentResolver(), key);
        return v == null || v.isEmpty() ? def : v;
    }

    private void stepVolume(int dir) {
        AudioManager am = audio();
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int step = Math.max(1, Math.round(max / 15f));
        setVolume(am.getStreamVolume(AudioManager.STREAM_MUSIC) + dir * step);
    }

    private void setVolume(int v) {
        AudioManager am = audio();
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int min = am.getStreamMinVolume(AudioManager.STREAM_MUSIC);
        int next = Math.max(min, Math.min(max, v));
        am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
        int pct = max == 0 ? 0 : Math.round(next * 100f / max);
        svc.sendBroadcast(new Intent(ACTION_VOLUME).putExtra("percent", pct));
        emit("volume", String.valueOf(pct));
    }

    /** Short click on the media stream, so you hear the new level (stock media volume is silent). */
    private void click() {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 60);
            handler.postDelayed(tg::release, 250);
        } catch (RuntimeException e) {
            Log.w(App.TAG, "volume click failed: " + e);
        }
    }

    private void runShortcut(String action) {
        switch (action) {
            case "voice":
                svc.sendBroadcast(new Intent("me.jxl.kiosk_satellite.action.VOICE_WAKE").setPackage(KioskUpdater.PKG));
                break;
            case "dock_light":
                new Thread(() -> {
                    ChargeBase.Status s = svc.dock.status();
                    if (!s.valid) return;
                    int cur = Math.min(10, s.light);
                    if (cur > 0) lastLight = cur;
                    svc.dock.setLight(cur > 0 ? 0 : lastLight);
                    svc.requestDockBroadcast();
                }, "dock-toggle").start();
                break;
            case "screen_off":
                svc.sendBroadcast(new Intent("org.kioskclock.nav.SCREEN").setPackage("org.kioskclock.nav").putExtra("on", false));
                break;
            default:
                break;
        }
    }

    // ---- mic switch -------------------------------------------------------------------------

    private void onMicSwitch(Intent intent) {
        // The broadcast has no state; OemBridge reports the real position. Only fall back to a
        // stateless "changed" event when the OEM service isn't available.
        if (svc.micMuted != null) return;
        Bundle b = intent.getExtras();
        StringBuilder raw = new StringBuilder();
        Boolean muted = null;
        if (b != null) {
            for (String k : b.keySet()) {
                Object v = b.get(k);
                raw.append(k).append('=').append(v).append(' ');
                String lk = k.toLowerCase();
                if (lk.contains("mute") || lk.contains("state")) {
                    if (v instanceof Boolean) muted = (Boolean) v;
                    else if (v instanceof Integer) muted = ((Integer) v) != 0;
                    else if (v instanceof String) muted = "1".equals(v) || "true".equalsIgnoreCase((String) v) || "muted".equalsIgnoreCase((String) v);
                }
            }
        }
        Log.i(App.TAG, "mic switch broadcast: " + raw);
        Intent out = new Intent(ACTION_MIC);
        if (muted != null) out.putExtra("muted", muted.booleanValue());
        svc.sendBroadcast(out);
        emit("mic", muted == null ? "changed" : muted ? "muted" : "unmuted");
    }

    private void emit(String type, String value) {
        Log.i(App.TAG, "event " + type + "=" + value);
        svc.sendBroadcast(new Intent(ACTION_EVENT).putExtra("type", type).putExtra("value", value));
        svc.webhook.send(type, value);
    }
}
