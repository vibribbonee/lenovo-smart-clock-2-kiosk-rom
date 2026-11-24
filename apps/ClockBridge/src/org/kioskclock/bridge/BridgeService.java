package org.kioskclock.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import java.security.SecureRandom;

/**
 * Foreground service hosting the mic anchor, the dock poller and the HTTP API.
 * Broadcasts org.kioskclock.bridge.DOCK_STATE (extras: light, phone_present, phone_charging,
 * charger_state) whenever the dock state changes, for Kiosk Satellite or other local consumers.
 */
public class BridgeService extends Service {
    static final String ACTION_DOCK_STATE = "org.kioskclock.bridge.DOCK_STATE";
    static final int HTTP_PORT = 8765;

    private static volatile BridgeService instance;

    final ChargeBase dock = new ChargeBase();
    volatile ChargeBase.Status lastStatus = new ChargeBase.Status();
    private MicAnchor anchor;
    private HttpApi http;
    private Thread poller;
    KioskUpdater updater;
    private Thread updaterThread;
    final Webhook webhook = new Webhook(this);
    private OemKeyReceiver oemKeys;
    private volatile boolean running;

    static BridgeService get() { return instance; }

    SharedPreferences prefs() {
        return createDeviceProtectedStorageContext().getSharedPreferences("bridge", Context.MODE_PRIVATE);
    }

    String token() {
        SharedPreferences p = prefs();
        String t = p.getString("token", null);
        if (t == null) {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x & 0xff));
            t = sb.toString();
            p.edit().putString("token", t).apply();
            Log.i(App.TAG, "generated API token (set your own with the SET_TOKEN command): " + t);
        }
        return t;
    }

    void setToken(String t) { prefs().edit().putString("token", t).apply(); }

    String micState() { return anchor == null ? "disabled (no RECORD_AUDIO)" : anchor.state; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("bridge", "Clock Bridge", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "bridge")
                .setContentTitle("Clock Bridge")
                .setContentText("Microphone anchor and dock bridge")
                .setSmallIcon(android.R.drawable.presence_audio_online)
                .setOngoing(true)
                .build();
        startForeground(1, n);
        running = true;

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            anchor = new MicAnchor();
            Thread t = new Thread(anchor, "mic-anchor");
            t.setPriority(Thread.MAX_PRIORITY);
            t.start();
        } else {
            Log.w(App.TAG, "RECORD_AUDIO not granted; mic anchor disabled");
        }

        Provisioner.runOnce(this);

        org.json.JSONObject buttons = Provisioner.config() == null ? null : Provisioner.config().optJSONObject("buttons");
        oemKeys = new OemKeyReceiver(this,
                buttons == null ? "voice" : buttons.optString("hold_volume_up", "voice"),
                buttons == null ? "dock_light" : buttons.optString("hold_volume_down", "dock_light"));
        registerReceiver(oemKeys, oemKeys.filter());

        poller = new Thread(this::pollDock, "dock-poller");
        poller.start();

        updater = new KioskUpdater(this);
        updaterThread = new Thread(updater::loop, "kiosk-updater");
        updaterThread.setPriority(Thread.MIN_PRIORITY);
        updaterThread.start();

        token();
        http = new HttpApi(this, HTTP_PORT);
        http.start();
    }

    /** Runs one update check off the caller's thread. */
    void checkUpdateAsync() {
        new Thread(updater::checkNow, "kiosk-update-now").start();
    }

    private void pollDock() {
        String lastKey = "";
        String phonePrev = null, phoneReported = null;
        while (running) {
            ChargeBase.Status s = dock.status();
            lastStatus = s;
            // Phone events need the same state on two polls in a row: codes 9 and 0 are ~1 s transients.
            String phone = !s.valid ? null : s.phoneCharging() ? "charging" : s.phonePresent() ? "present" : "none";
            if (phone != null && phone.equals(phonePrev) && !phone.equals(phoneReported)) {
                if (phoneReported != null) webhook.send("phone", phone);
                phoneReported = phone;
            }
            phonePrev = phone;
            String key = s.valid + ":" + s.light + ":" + s.charger;
            if (!key.equals(lastKey)) {
                lastKey = key;
                sendBroadcast(dockIntent(s));
                Log.i(App.TAG, "dock state " + key);
            }
            try { Thread.sleep(s.valid ? 2000 : 10000); } catch (InterruptedException e) { return; }
        }
    }

    static Intent dockIntent(ChargeBase.Status s) {
        return new Intent(ACTION_DOCK_STATE)
                .putExtra("valid", s.valid)
                .putExtra("light", Math.min(10, s.light))   // the MCU reports 11 for level 10
                .putExtra("charger_state", s.charger)
                .putExtra("phone_present", s.phonePresent())
                .putExtra("phone_charging", s.phoneCharging());
    }

    /** Fresh dock read + broadcast, for the quick panel (runs on the caller's thread). */
    void requestDockBroadcast() {
        if (Thread.currentThread() == getMainLooper().getThread()) {
            new Thread(this::requestDockBroadcast, "dock-query").start();
            return;
        }
        ChargeBase.Status s = dock.status();
        lastStatus = s;
        sendBroadcast(dockIntent(s));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (oemKeys != null) unregisterReceiver(oemKeys);
        if (anchor != null) anchor.stop();
        if (http != null) http.shutdown();
        if (poller != null) poller.interrupt();
        if (updaterThread != null) updaterThread.interrupt();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
