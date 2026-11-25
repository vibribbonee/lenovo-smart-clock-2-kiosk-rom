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
 * Foreground service hosting the dock poller, the HTTP API, events and the updaters.
 * (The mic anchor runs in ClockCore, the persistent app; its state is in kioskclock_mic_anchor_state.)
 * Broadcasts org.kioskclock.bridge.DOCK_STATE (extras: light, phone_present, phone_charging,
 * charger_state) whenever the dock state changes, for Kiosk Satellite or other local consumers.
 */
public class BridgeService extends Service {
    static final String ACTION_DOCK_STATE = "org.kioskclock.bridge.DOCK_STATE";
    static final int HTTP_PORT = 8765;

    private static volatile BridgeService instance;

    final ChargeBase dock = new ChargeBase();
    volatile ChargeBase.Status lastStatus = new ChargeBase.Status();
    private HttpApi http;
    private Discovery discovery;
    WakeLight wakeLight;
    final Pairing pairing = new Pairing(this);
    final Extras extras = new Extras(this);
    private Thread wakeThread;
    private Thread poller;
    KioskUpdater updater;
    private Thread updaterThread;
    OwnUpdater ownUpdater;
    private Thread ownUpdaterThread;
    final Webhook webhook = new Webhook(this);
    OemKeyReceiver oemKeys;
    private OemBridge oem;
    /** Mic switch position from the OEM service; null until it has reported. */
    volatile Boolean micMuted;
    private volatile boolean running;

    static BridgeService get() { return instance; }

    SharedPreferences prefs() {
        return createDeviceProtectedStorageContext().getSharedPreferences("bridge", Context.MODE_PRIVATE);
    }

    String token() { return token(this); }

    /** The API token, generated on first use. Static so ControlReceiver can read it without the service. */
    static synchronized String token(Context ctx) {
        SharedPreferences p = ctx.createDeviceProtectedStorageContext().getSharedPreferences("bridge", Context.MODE_PRIVATE);
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

    String micState() {
        String s = android.provider.Settings.Global.getString(getContentResolver(), "kioskclock_mic_anchor_state");
        return s == null ? "unknown (ClockCore not running)" : s;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("bridge", "Clock Bridge", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "bridge")
                .setContentTitle("Clock Bridge")
                .setContentText("Dock, Home Assistant and updates")
                .setSmallIcon(android.R.drawable.presence_audio_online)
                .setOngoing(true)
                .build();
        startForeground(1, n);
        running = true;

        Provisioner.init(this);
        Provisioner.runOnce(this);
        Provisioner.applyGrants(this);

        org.json.JSONObject buttons = Provisioner.config() == null ? null : Provisioner.config().optJSONObject("buttons");
        oemKeys = new OemKeyReceiver(this,
                buttons == null ? "voice" : buttons.optString("hold_volume_up", "voice"),
                buttons == null ? "dock_light" : buttons.optString("hold_volume_down", "dock_light"),
                buttons == null ? "none" : buttons.optString("tap_single", "none"),
                buttons == null ? "none" : buttons.optString("tap_double", "none"));
        registerReceiver(oemKeys, oemKeys.filter());
        oem = new OemBridge(this, this::onMicState);
        oem.start();

        poller = new Thread(this::pollDock, "dock-poller");
        poller.start();

        updater = new KioskUpdater(this);
        updaterThread = new Thread(updater::loop, "kiosk-updater");
        updaterThread.setPriority(Thread.MIN_PRIORITY);
        updaterThread.start();
        ownUpdater = new OwnUpdater(this);
        ownUpdaterThread = new Thread(ownUpdater::loop, "app-updater");
        ownUpdaterThread.setPriority(Thread.MIN_PRIORITY);
        ownUpdaterThread.start();

        token();
        http = new HttpApi(this, HTTP_PORT);
        http.start();
        discovery = new Discovery(this, HTTP_PORT);
        discovery.start();
        wakeLight = new WakeLight(this);
        wakeThread = new Thread(wakeLight, "wake-light");
        wakeThread.start();
    }

    /**
     * Mic switch position (from OemBridge, on a binder thread). Published as Settings.Global
     * kioskclock_mic_muted (1/0, read by KioskNav and the HTTP API), an org.kioskclock.bridge.MIC
     * broadcast and, on changes, a webhook event.
     */
    void onMicState(boolean muted) {
        Boolean prev = micMuted;
        micMuted = muted;
        android.provider.Settings.Global.putString(getContentResolver(), "kioskclock_mic_muted", muted ? "1" : "0");
        sendBroadcast(new Intent(OemKeyReceiver.ACTION_MIC).putExtra("muted", muted));
        Log.i(App.TAG, "mic switch " + (muted ? "muted" : "on") + (prev == null ? " (initial)" : ""));
        if (prev != null && prev != muted) webhook.send("mic", muted ? "muted" : "unmuted");
    }

    /** Runs one update check off the caller's thread. */
    void checkUpdateAsync() {
        new Thread(() -> { updater.checkNow(); ownUpdater.checkNow(); }, "update-now").start();
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
        if (oem != null) oem.stop();
        if (discovery != null) discovery.stop();
        if (wakeLight != null) wakeLight.stop();
        if (wakeThread != null) wakeThread.interrupt();
        if (http != null) http.shutdown();
        if (poller != null) poller.interrupt();
        if (updaterThread != null) updaterThread.interrupt();
        if (ownUpdaterThread != null) ownUpdaterThread.interrupt();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
