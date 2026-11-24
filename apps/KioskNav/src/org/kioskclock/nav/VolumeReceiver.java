package org.kioskclock.nav;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives ClockBridge's volume changes (org.kioskclock.bridge.VOLUME, extra "percent"). */
public class VolumeReceiver extends BroadcastReceiver {
    static final String ACTION = "org.kioskclock.bridge.VOLUME";
    private final NavService svc;

    VolumeReceiver(NavService svc) { this.svc = svc; }

    @Override
    public void onReceive(Context context, Intent intent) {
        int pct = intent.getIntExtra("percent", -1);
        if (pct >= 0 && pct <= 100) svc.showVolume(pct);
    }
}
