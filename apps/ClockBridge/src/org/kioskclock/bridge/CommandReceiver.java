package org.kioskclock.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Admin commands, only accepted from senders holding WRITE_SECURE_SETTINGS (adb shell, root, system):
 *   am broadcast -a org.kioskclock.bridge.COMMAND -n org.kioskclock.bridge/.CommandReceiver --es cmd set_token --es token <32+ chars>
 *   am broadcast -a org.kioskclock.bridge.COMMAND -n org.kioskclock.bridge/.CommandReceiver --es cmd reprovision
 *   am broadcast -a org.kioskclock.bridge.COMMAND -n org.kioskclock.bridge/.CommandReceiver --es cmd check_update
 *   am broadcast -a org.kioskclock.bridge.COMMAND -n org.kioskclock.bridge/.CommandReceiver --es cmd set_webhook --es url http://ha:8123/api/webhook/ID
 *   am broadcast -a org.kioskclock.bridge.COMMAND -n org.kioskclock.bridge/.CommandReceiver --es cmd test_event
 *   am broadcast -a org.kioskclock.bridge.COMMAND -n org.kioskclock.bridge/.CommandReceiver --es cmd auto_update --es value off
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
        } else if ("set_webhook".equals(cmd)) {
            if (svc == null) { setResultData("service not running"); return; }
            String url = intent.getStringExtra("url");
            if (url != null && !url.isEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                setResultData("url must start with http:// or https://"); return;
            }
            svc.webhook.setUrl(url);
            setResultData(url == null || url.isEmpty() ? "webhook cleared" : "webhook set");
        } else if ("test_event".equals(cmd)) {
            if (svc == null) { setResultData("service not running"); return; }
            svc.webhook.send("test", "hello");
            setResultData("sent");
        } else if ("auto_update".equals(cmd)) {
            if (svc == null) { setResultData("service not running"); return; }
            boolean on = !"off".equals(intent.getStringExtra("value"));
            svc.updater.setEnabled(on);
            setResultData("auto_update " + (on ? "on" : "off"));
        } else if ("check_update".equals(cmd)) {
            if (svc == null) { setResultData("service not running"); return; }
            svc.checkUpdateAsync();
            setResultData("check started");
        } else if ("reprovision".equals(cmd)) {
            Provisioner.reset(context);
            Provisioner.runOnce(context);
            setResultData("ok");
        } else {
            setResultData("unknown command");
        }
    }
}
