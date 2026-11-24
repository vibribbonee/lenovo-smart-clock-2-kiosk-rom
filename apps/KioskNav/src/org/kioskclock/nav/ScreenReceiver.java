package org.kioskclock.nav;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * org.kioskclock.nav.SCREEN (extra "on": true = wake, false = black), sent by ClockBridge for
 * Home Assistant. Registered with the project's signature permission, so only our apps can send it.
 */
public class ScreenReceiver extends BroadcastReceiver {
    static final String ACTION = "org.kioskclock.nav.SCREEN";
    private final NavService svc;

    ScreenReceiver(NavService svc) { this.svc = svc; }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getBooleanExtra("on", true)) svc.wake(); else svc.blackout();
    }
}
