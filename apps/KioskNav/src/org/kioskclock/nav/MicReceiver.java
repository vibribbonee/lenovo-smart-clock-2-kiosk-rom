package org.kioskclock.nav;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** ClockBridge's org.kioskclock.bridge.MIC (extra "muted" when the switch state is known). */
public class MicReceiver extends BroadcastReceiver {
    static final String ACTION = "org.kioskclock.bridge.MIC";
    private final QuickPanel panel;

    MicReceiver(QuickPanel panel) { this.panel = panel; }

    @Override
    public void onReceive(Context context, Intent intent) {
        panel.onMic(intent.hasExtra("muted") ? intent.getBooleanExtra("muted", false) : null);
    }
}
