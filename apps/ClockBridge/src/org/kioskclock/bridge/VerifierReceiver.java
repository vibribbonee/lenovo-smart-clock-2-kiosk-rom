package org.kioskclock.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * ClockBridge is the clock's package verifier (stock firmware has none: "Required: null").
 * Holding that role is what lets Android grant ClockBridge MANAGE_APP_OPS_MODES and
 * GRANT_RUNTIME_PERMISSIONS, which {@link Provisioner#applyGrants} uses to give Kiosk Satellite its
 * special access (install updates, usage access, …) without taps.
 * As verifier it must answer every install; it always allows (same as having no verifier). If it ever
 * fails to answer, Android allows the install after its 10 s verification timeout.
 */
public final class VerifierReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_PACKAGE_NEEDS_VERIFICATION.equals(intent.getAction())) return;
        int id = intent.getIntExtra(PackageManager.EXTRA_VERIFICATION_ID, -1);
        if (id < 0) return;
        try {
            context.getPackageManager().verifyPendingInstall(id, PackageManager.VERIFICATION_ALLOW);
        } catch (RuntimeException e) {
            Log.w(App.TAG, "verifyPendingInstall " + id + ": " + e);
        }
    }
}
