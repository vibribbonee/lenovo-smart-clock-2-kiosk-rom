package org.kioskclock.nav;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives ClockBridge's volume changes (org.kioskclock.bridge.VOLUME, extra "percent"). */
public class VolumeReceiver extends BroadcastReceiver {
    static final String ACTION = "org.kioskclock.bridge.VOLUME";
    /** AudioService's own broadcast for every stream volume change (any app, Bluetooth, HA). */
    static final String SYSTEM = "android.media.VOLUME_CHANGED_ACTION";
    private final NavService svc;

    VolumeReceiver(NavService svc) { this.svc = svc; }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (SYSTEM.equals(intent.getAction())) {
            if (intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != android.media.AudioManager.STREAM_MUSIC) return;
            int v = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);
            int prev = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1);
            if (v < 0 || v == prev) return;
            int max = context.getSystemService(android.media.AudioManager.class).getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            svc.showVolume(max <= 0 ? 0 : Math.round(v * 100f / max));
            return;
        }
        int pct = intent.getIntExtra("percent", -1);
        if (pct >= 0 && pct <= 100) svc.showVolume(pct);
    }
}
