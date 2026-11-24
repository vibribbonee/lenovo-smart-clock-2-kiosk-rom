package org.kioskclock.nav;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** ClockBridge's org.kioskclock.bridge.MIC (extra "muted" when the switch state is known). */
public class MicReceiver extends BroadcastReceiver {
    static final String ACTION = "org.kioskclock.bridge.MIC";
    private final QuickPanel panel;
    private final MicIndicator indicator;

    MicReceiver(QuickPanel panel, MicIndicator indicator) { this.panel = panel; this.indicator = indicator; }

    @Override
    public void onReceive(Context context, Intent intent) {
        Boolean muted = intent.hasExtra("muted") ? intent.getBooleanExtra("muted", false) : null;
        panel.onMic(muted);
        if (muted != null) indicator.set(muted, true);
    }
}
