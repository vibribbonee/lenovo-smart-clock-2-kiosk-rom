package org.kioskclock.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

/** Receives the PackageInstaller result for Kiosk Satellite updates. Never launches UI. */
public class InstallResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String r;
        if (status == PackageInstaller.STATUS_SUCCESS) {
            r = "installed";
        } else if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Not a privileged installer (stock firmware test build): Android wants on-screen confirmation.
            r = "needs user confirmation (no INSTALL_PACKAGES); not installed";
        } else {
            r = "install failed: status " + status + " " + msg;
        }
        Log.i(App.TAG, "kiosk update: " + r);
        BridgeService svc = BridgeService.get();
        if (svc != null && svc.updater != null) svc.updater.lastResult = r;
    }
}
