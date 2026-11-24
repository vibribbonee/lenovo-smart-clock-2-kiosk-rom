package org.kioskclock.bridge;

import android.app.Application;
import android.content.Intent;
import android.util.Log;

/** Starts the bridge whenever the process starts (boot, after an update, or any broadcast). */
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
