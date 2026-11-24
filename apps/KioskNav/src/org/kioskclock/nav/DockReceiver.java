package org.kioskclock.nav;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** ClockBridge's dock state (org.kioskclock.bridge.DOCK_STATE) → quick panel's dock-light row. */
public class DockReceiver extends BroadcastReceiver {
    static final String ACTION = "org.kioskclock.bridge.DOCK_STATE";
    private final QuickPanel panel;

    DockReceiver(QuickPanel panel) { this.panel = panel; }

    @Override
    public void onReceive(Context context, Intent intent) {
        panel.onDockState(intent.getBooleanExtra("valid", false), intent.getIntExtra("light", 0));
    }
}
