package org.kioskclock.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Admin commands, only accepted from senders holding WRITE_SECURE_SETTINGS (adb shell, root, system):
 *   am broadcast -a org.kioskclock.bridge.COMMAND -n org.kioskclock.bridge/.CommandReceiver --es cmd set_token --es token <32+ chars>
 *   am broadcast -a org.kioskclock.bridge.COMMAND -n org.kioskclock.bridge/.CommandReceiver --es cmd reprovision
 */
public class CommandReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String cmd = intent.getStringExtra("cmd");
        BridgeService svc = BridgeService.get();
        if ("set_token".equals(cmd)) {
            String t = intent.getStringExtra("token");
            if (t == null || t.length() < 32) { setResultData("token must be at least 32 characters"); return; }
            if (svc == null) { setResultData("service not running"); return; }
            svc.setToken(t);
            setResultData("ok");
            Log.i(App.TAG, "API token replaced");
        } else if ("reprovision".equals(cmd)) {
            Provisioner.reset(context);
            Provisioner.runOnce(context);
            setResultData("ok");
        } else {
            setResultData("unknown command");
        }
    }
}
