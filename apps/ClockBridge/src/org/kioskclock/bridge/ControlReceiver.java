package org.kioskclock.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Privileged actions for KioskNav's quick panel. Senders must hold
 * org.kioskclock.permission.CONTROL (signature: only apps signed with the project key).
 *   cmd=light level=0..10   dock night light
 *   cmd=query               re-broadcast the dock state (org.kioskclock.bridge.DOCK_STATE)
 *   cmd=dnd on=true|false   Do Not Disturb (Settings.Global zen_mode: 1 = priority only, 0 = off)
 *   cmd=night on=true|false Night Light (Settings.Secure night_display_activated)
 *   cmd=reboot              restart the device
 *   cmd=token               ordered broadcast; result data = the HTTP API token (for pairing Home Assistant)
 */
public class ControlReceiver extends BroadcastReceiver {
    static final String ACTION = "org.kioskclock.bridge.CONTROL";

    @Override
    public void onReceive(Context c, Intent i) {
        String cmd = i.getStringExtra("cmd");
        BridgeService svc = BridgeService.get();
        if (cmd == null) return;
        switch (cmd) {
            case "light":
                if (svc == null) return;
                int level = i.getIntExtra("level", -1);
                if (level < 0 || level > 10) return;
                final BridgeService s = svc;
                new Thread(() -> { s.dock.setLight(level); s.requestDockBroadcast(); }, "light").start();
                break;
            case "query":
                if (svc != null) svc.requestDockBroadcast();
                break;
            case "dnd":
                Settings.Global.putInt(c.getContentResolver(), "zen_mode", i.getBooleanExtra("on", false) ? 1 : 0);
                break;
            case "night":
                Settings.Secure.putInt(c.getContentResolver(), "night_display_activated", i.getBooleanExtra("on", false) ? 1 : 0);
                break;
            case "token":
                setResultData(BridgeService.token(c));
                break;
            case "reboot":
                Log.i(App.TAG, "reboot requested from the quick panel");
                c.getSystemService(PowerManager.class).reboot(null);
                break;
            default:
                Log.w(App.TAG, "unknown control command " + cmd);
        }
    }
}
