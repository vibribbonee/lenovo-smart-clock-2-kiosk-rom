package org.kioskclock.bridge;

import android.app.Application;
import android.content.Intent;
import android.util.Log;

/** Starts the bridge as early as possible. As a persistent system app this runs before HOME starts. */
public class App extends Application {
    static final String TAG = "ClockBridge";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            startForegroundService(new Intent(this, BridgeService.class));
        } catch (RuntimeException e) {
            // On a non-system install, a background start can be refused; BootReceiver retries.
            Log.w(TAG, "early start refused: " + e);
        }
    }
}
