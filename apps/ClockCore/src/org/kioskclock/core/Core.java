package org.kioskclock.core;

import android.app.Application;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * Persistent process (started by Android before HOME) that runs the mic anchor and nothing else.
 * kioskclock_mic_anchor = 0 stops it, anything else (or unset) runs it; applies live.
 * Publishes kioskclock_mic_anchor_state for ClockBridge's status API.
 */
public class Core extends Application {
    static final String TAG = "ClockCore";
    static final String SETTING = "kioskclock_mic_anchor", STATE = "kioskclock_mic_anchor_state";

    private MicAnchor anchor;
    private Thread thread;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable publish = this::publish;
    /** RECORD_AUDIO can arrive a little after boot (ClockBridge grants it on a clock that had no ClockCore yet). */
    private final Runnable retry = this::apply;

    @Override
    public void onCreate() {
        super.onCreate();
        getContentResolver().registerContentObserver(Settings.Global.getUriFor(SETTING), false,
                new Observer(handler, this));
        apply();
    }

    void apply() {
        boolean want = !"0".equals(Settings.Global.getString(getContentResolver(), SETTING));
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted yet; mic anchor waits");
            put("waiting for RECORD_AUDIO");
            handler.removeCallbacks(retry);
            handler.postDelayed(retry, 5000);
            return;
        }
        if (want && anchor == null) {
            anchor = new MicAnchor();
            thread = new Thread(anchor, "mic-anchor");
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
            handler.postDelayed(publish, 2000);
        } else if (!want && anchor != null) {
            anchor.stop();
            thread.interrupt();
            anchor = null;
            Log.i(TAG, "mic anchor switched off");
            put("off (kioskclock_mic_anchor=0)");
        } else if (!want) {
            put("off (kioskclock_mic_anchor=0)");
        }
    }

    private void publish() {
        if (anchor == null) return;
        put(anchor.state);
        handler.postDelayed(publish, 30_000);
    }

    private void put(String state) {
        try { Settings.Global.putString(getContentResolver(), STATE, state); }
        catch (SecurityException e) { Log.w(TAG, "can't publish state: " + e); }
    }

    private static final class Observer extends ContentObserver {
        private final Core core;
        Observer(Handler h, Core core) { super(h); this.core = core; }
        @Override public void onChange(boolean selfChange) { core.apply(); }
    }
}
