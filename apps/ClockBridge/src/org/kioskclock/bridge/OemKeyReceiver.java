package org.kioskclock.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Lenovo's PhoneWindowManager (system_server) turns the clock's hardware events into broadcasts:
 *   com.tonly.KEYCODE_TAP             tap on the top of the clock (accelerometer, KEYCODE_TAP=287)
 *   com.tonly.ACCESSORIES_INSERT/DROP clock placed on / lifted off the dock (keys 284/290)
 *   com.tonly.KEYCODE_VOLUME_UP/DOWN  volume buttons, extra EXTRA_DOWN = 1 (pressed) / 0 (released)
 * On stock the Google OEM app consumes these. Here they become events (webhook + local broadcast),
 * and the volume buttons change the media volume when no Google Assistant is installed.
 */
public class OemKeyReceiver extends BroadcastReceiver {
    static final String TAP = "com.tonly.KEYCODE_TAP";
    static final String DOCK_IN = "com.tonly.ACCESSORIES_INSERT";
    static final String DOCK_OUT = "com.tonly.ACCESSORIES_DROP";
    static final String VOL_UP = "com.tonly.KEYCODE_VOLUME_UP";
    static final String VOL_DOWN = "com.tonly.KEYCODE_VOLUME_DOWN";
    static final String ACTION_EVENT = "org.kioskclock.bridge.EVENT";
    /** Shown by KioskNav as a short volume bar (extra "percent"). */
    static final String ACTION_VOLUME = "org.kioskclock.bridge.VOLUME";
    private static final long DOUBLE_TAP_MS = 450;

    private final BridgeService svc;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable singleTap = () -> emit("tap", "single");
    private long lastTap;
    private final boolean handleVolume;

    OemKeyReceiver(BridgeService svc) {
        this.svc = svc;
        handleVolume = !installed(svc, "com.google.assistant.core");
        Log.i(App.TAG, "volume buttons handled here: " + handleVolume);
    }

    static boolean installed(Context c, String pkg) {
        try { return c.getPackageManager().getApplicationInfo(pkg, 0).enabled; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    IntentFilter filter() {
        IntentFilter f = new IntentFilter();
        f.addAction(TAP);
        f.addAction(DOCK_IN);
        f.addAction(DOCK_OUT);
        f.addAction(VOL_UP);
        f.addAction(VOL_DOWN);
        return f;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (TAP.equals(a)) {
            long now = android.os.SystemClock.uptimeMillis();
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
        } else if (handleVolume && (VOL_UP.equals(a) || VOL_DOWN.equals(a))) {
            if (intent.getIntExtra("EXTRA_DOWN", 0) == 1) {
                // This firmware's media volume range is 0..100, so ADJUST_RAISE (+1) is inaudible.
                // Step by 1/15 of the range (the usual Android step count) instead.
                AudioManager am = context.getSystemService(AudioManager.class);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int min = am.getStreamMinVolume(AudioManager.STREAM_MUSIC);
                int step = Math.max(1, Math.round(max / 15f));
                int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                int next = Math.max(min, Math.min(max, cur + (VOL_UP.equals(a) ? step : -step)));
                am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
                int pct = max == 0 ? 0 : Math.round(next * 100f / max);
                svc.sendBroadcast(new Intent(ACTION_VOLUME).putExtra("percent", pct));
                emit("volume", String.valueOf(pct));
            }
        }
    }

    private void emit(String type, String value) {
        Log.i(App.TAG, "event " + type + "=" + value);
        svc.sendBroadcast(new Intent(ACTION_EVENT).putExtra("type", type).putExtra("value", value));
        svc.webhook.send(type, value);
    }
}
