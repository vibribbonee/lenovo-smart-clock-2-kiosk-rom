package org.kioskclock.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * Lenovo's PhoneWindowManager (system_server) turns the clock's hardware events into broadcasts:
 *   com.tonly.KEYCODE_TAP             tap on the top of the clock (accelerometer, KEYCODE_TAP=287)
 *   com.tonly.ACCESSORIES_INSERT/DROP clock placed on / lifted off the dock (keys 284/290)
 *   com.tonly.KEYCODE_VOLUME_UP/DOWN  volume buttons, extra EXTRA_DOWN = 1 (pressed) / 0 (released)
 *   com.tonly.MIC_MUTE_STATE_CHANGE   hardware mic switch moved
 * On stock the Google OEM app consumes these. Here they become events (webhook + local broadcast).
 * When no Google Assistant is installed, the volume buttons are handled here:
 *   short press = media volume step (1/15 of the 0..100 range) + a short click at the new level,
 *   hold (>= HOLD_MS) = configurable shortcut (provision.json "buttons"), the volume step is undone.
 * Gestures (hold_up, hold_down, tap_single, tap_double) each run a shortcut chosen in the
 * kioskclock_hold_volume_up/down and kioskclock_tap_single/double settings (see ACTIONS), and are
 * always sent to Home Assistant too. Home Assistant can also run a gesture or an action directly.
 */
public class OemKeyReceiver extends BroadcastReceiver {
    static final String TAP = "com.tonly.KEYCODE_TAP";
    static final String DOCK_IN = "com.tonly.ACCESSORIES_INSERT";
    static final String DOCK_OUT = "com.tonly.ACCESSORIES_DROP";
    static final String VOL_UP = "com.tonly.KEYCODE_VOLUME_UP";
    static final String VOL_DOWN = "com.tonly.KEYCODE_VOLUME_DOWN";
    static final String MIC = "com.tonly.MIC_MUTE_STATE_CHANGE";
    static final String ACTION_EVENT = "org.kioskclock.bridge.EVENT";
    /** Shown by KioskNav as a short volume bar (extra "percent"). */
    static final String ACTION_VOLUME = "org.kioskclock.bridge.VOLUME";
    /** For KioskNav's panel: extra "muted" (boolean) when the switch state is known. */
    static final String ACTION_MIC = "org.kioskclock.bridge.MIC";
    private static final long DOUBLE_TAP_MS = 450;
    private static final long HOLD_MS = 700;

    private final BridgeService svc;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Shortcuts a gesture can run (the same list for every gesture). */
    static final String ACTIONS = "alarm_snooze|alarm_stop|voice|play_pause|mute|dock_light|night_light|screen_off|screen_toggle|ha_event|none";
    /** gesture -> Settings.Global key holding its action */
    static final java.util.Map<String, String> GESTURES = new java.util.LinkedHashMap<>();
    static {
        GESTURES.put("hold_up", "kioskclock_hold_volume_up");
        GESTURES.put("hold_down", "kioskclock_hold_volume_down");
        GESTURES.put("tap_single", "kioskclock_tap_single");
        GESTURES.put("tap_double", "kioskclock_tap_double");
    }

    private final Runnable singleTap = () -> onTap("single");
    private long lastTap;
    private final boolean handleVolume;
    private final String holdUp, holdDown, tapSingle, tapDouble;

    // state of the volume key currently held
    private String heldKey;
    private int volumeBefore = -1;
    /** Stream the held key changes: the alarm stream while an alarm rings, else media. */
    private int keyStream = AudioManager.STREAM_MUSIC;
    private boolean holdFired;
    private final Runnable holdCheck = this::onHold;
    private int lastLight = 5;

    OemKeyReceiver(BridgeService svc, String holdUp, String holdDown, String tapSingle, String tapDouble) {
        this.svc = svc;
        this.holdUp = holdUp;
        this.holdDown = holdDown;
        this.tapSingle = tapSingle;
        this.tapDouble = tapDouble;
        handleVolume = !installed(svc, "com.google.assistant.core");
        Log.i(App.TAG, "volume buttons handled here: " + handleVolume + " (hold +: " + holdUp + ", hold -: " + holdDown + ")");
    }

    static boolean installed(Context c, String pkg) {
        try { return c.getPackageManager().getApplicationInfo(pkg, 0).enabled; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    IntentFilter filter() {
        IntentFilter f = new IntentFilter();
        for (String a : new String[]{TAP, DOCK_IN, DOCK_OUT, VOL_UP, VOL_DOWN, MIC}) f.addAction(a);
        return f;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (TAP.equals(a)) {
            long now = SystemClock.uptimeMillis();
            if (now - lastTap < DOUBLE_TAP_MS) {
                handler.removeCallbacks(singleTap);
                lastTap = 0;
                onTap("double");
            } else {
                lastTap = now;
                handler.postDelayed(singleTap, DOUBLE_TAP_MS);
            }
        } else if (DOCK_IN.equals(a)) {
            emit("dock", "attached");
        } else if (DOCK_OUT.equals(a)) {
            emit("dock", "removed");
        } else if (MIC.equals(a)) {
            onMicSwitch(intent);
        } else if (handleVolume && (VOL_UP.equals(a) || VOL_DOWN.equals(a))) {
            if (intent.getIntExtra("EXTRA_DOWN", 0) == 1) onVolumeDown(a); else onVolumeUp(a);
        }
    }

    // ---- volume keys ------------------------------------------------------------------------

    private AudioManager audio() { return svc.getSystemService(AudioManager.class); }

    private void onVolumeDown(String key) {
        if (key.equals(heldKey)) return;            // auto-repeat while held
        heldKey = key;
        holdFired = false;
        keyStream = alarmPlaying() ? AudioManager.STREAM_ALARM : AudioManager.STREAM_MUSIC;
        volumeBefore = audio().getStreamVolume(keyStream);
        stepVolume(VOL_UP.equals(key) ? 1 : -1);    // immediate feedback, undone if it becomes a hold
        handler.removeCallbacks(holdCheck);
        handler.postDelayed(holdCheck, HOLD_MS);
    }

    private void onVolumeUp(String key) {
        handler.removeCallbacks(holdCheck);
        if (!holdFired && key.equals(heldKey) && keyStream == AudioManager.STREAM_MUSIC) click();   // the alarm itself is the feedback
        heldKey = null;
    }

    private void onHold() {
        if (heldKey == null) return;
        holdFired = true;
        if (volumeBefore >= 0) setVolume(volumeBefore);   // a hold is a shortcut, not a volume change
        String gesture = VOL_UP.equals(heldKey) ? "hold_up" : "hold_down";
        String action = actionFor(gesture);
        emit("button", gesture + ":" + action);
        runShortcut(action);
    }

    /** A hard slam often registers as several taps; ignore alarm actions this soon after one. */
    private static final long ALARM_GUARD_MS = 2000;
    private long lastAlarmTap;

    private void onTap(String kind) {
        String action = actionFor("tap_" + kind);
        emit("tap", kind);
        if (action != null && action.startsWith("alarm_")) {
            long now = SystemClock.uptimeMillis();
            if (now - lastAlarmTap < ALARM_GUARD_MS) { Log.i(App.TAG, "tap " + kind + ": ignored (just handled the alarm)"); return; }
            lastAlarmTap = now;
        }
        runShortcut(action);
    }

    /** The action configured for a gesture: live setting, else the provision.json default. */
    String actionFor(String gesture) {
        switch (gesture) {
            case "hold_up": return live(GESTURES.get(gesture), holdUp);
            case "hold_down": return live(GESTURES.get(gesture), holdDown);
            case "tap_single": return live(GESTURES.get(gesture), tapSingle);
            case "tap_double": return live(GESTURES.get(gesture), tapDouble);
            default: return null;
        }
    }

    /**
     * Runs a gesture's action as if the gesture happened on the clock (for Home Assistant). Not sent
     * back to Home Assistant as an event, so an automation can't loop on it. False if unknown.
     */
    boolean runGesture(String gesture) {
        String action = actionFor(gesture);
        if (action == null) return false;
        Log.i(App.TAG, "gesture " + gesture + " -> " + action + " (from Home Assistant)");
        handler.post(() -> runShortcut(action));
        return true;
    }

    /** Runs one action directly (for Home Assistant). False if unknown. */
    boolean runAction(String action) {
        if (action == null || !action.matches(ACTIONS)) return false;
        Log.i(App.TAG, "action " + action + " (from Home Assistant)");
        handler.post(() -> runShortcut(action));
        return true;
    }

    /** Live override from Settings.Global (set via /api/settings), else the provision.json default. */
    private String live(String key, String def) {
        String v = android.provider.Settings.Global.getString(svc.getContentResolver(), key);
        return v == null || v.isEmpty() ? def : v;
    }

    /** True while something plays with alarm usage (a ringing Kiosk Satellite alarm). */
    private boolean alarmPlaying() {
        try {
            for (android.media.AudioPlaybackConfiguration c : audio().getActivePlaybackConfigurations()) {
                if (c.getAudioAttributes().getUsage() == android.media.AudioAttributes.USAGE_ALARM) return true;
            }
        } catch (RuntimeException e) {
            Log.w(App.TAG, "playback query failed: " + e);
        }
        return false;
    }

    private void stepVolume(int dir) {
        AudioManager am = audio();
        int max = am.getStreamMaxVolume(keyStream);
        int step = Math.max(1, Math.round(max / 15f));
        setVolume(am.getStreamVolume(keyStream) + dir * step);
    }

    private void setVolume(int v) {
        AudioManager am = audio();
        int stream = keyStream;
        int max = am.getStreamMaxVolume(stream);
        int min = am.getStreamMinVolume(stream);
        int next = Math.max(min, Math.min(max, v));
        am.setStreamVolume(stream, next, 0);
        int pct = max == 0 ? 0 : Math.round(next * 100f / max);
        svc.sendBroadcast(new Intent(ACTION_VOLUME).putExtra("percent", pct));
        emit(stream == AudioManager.STREAM_ALARM ? "alarm_volume" : "volume", String.valueOf(pct));
    }

    /** Short click on the media stream, so you hear the new level (stock media volume is silent). */
    private void click() {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 60);
            handler.postDelayed(tg::release, 250);
        } catch (RuntimeException e) {
            Log.w(App.TAG, "volume click failed: " + e);
        }
    }

    private void runShortcut(String action) {
        switch (action) {
            case "voice":
                svc.sendBroadcast(new Intent("me.jxl.kiosk_satellite.action.VOICE_WAKE").setPackage(KioskUpdater.PKG));
                break;
            case "dock_light":
                new Thread(() -> {
                    ChargeBase.Status s = svc.dock.status();
                    if (!s.valid) return;
                    int cur = Math.min(10, s.light);
                    if (cur > 0) lastLight = cur;
                    svc.dock.setLight(cur > 0 ? 0 : lastLight);
                    svc.requestDockBroadcast();
                }, "dock-toggle").start();
                break;
            case "screen_off":
                svc.sendBroadcast(new Intent("org.kioskclock.nav.SCREEN").setPackage("org.kioskclock.nav").putExtra("on", false));
                break;
            case "alarm_snooze":   // a ringing Kiosk Satellite alarm; KioskNav presses its button (no-op otherwise)
            case "alarm_stop":
                svc.sendBroadcast(new Intent("org.kioskclock.nav.ALARM").setPackage("org.kioskclock.nav")
                        .putExtra("cmd", action.substring(6)), "org.kioskclock.permission.CONTROL");
                break;
            case "screen_toggle":   // black screen <-> on; a tap on top can wake it again
                svc.sendBroadcast(new Intent("org.kioskclock.nav.SCREEN").setPackage("org.kioskclock.nav").putExtra("toggle", true));
                break;
            case "play_pause": {   // whatever is playing on the clock (Kiosk media player, Music Assistant, …)
                AudioManager am = audio();
                am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
                am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
                break;
            }
            case "mute": {
                AudioManager am = audio();
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
                boolean muted = am.isStreamMute(AudioManager.STREAM_MUSIC);
                svc.sendBroadcast(new Intent(ACTION_VOLUME).putExtra("percent", muted ? 0
                        : Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)))));
                emit("speaker", muted ? "muted" : "unmuted");
                break;
            }
            case "night_light": {
                android.content.ContentResolver cr = svc.getContentResolver();
                int on = android.provider.Settings.Secure.getInt(cr, "night_display_activated", 0);
                android.provider.Settings.Secure.putInt(cr, "night_display_activated", on == 1 ? 0 : 1);
                break;
            }
            // "ha_event": nothing on the clock; the hold is sent to Home Assistant (button event above) for your automations.
            default:
                break;
        }
    }

    // ---- mic switch -------------------------------------------------------------------------

    private void onMicSwitch(Intent intent) {
        // The broadcast has no state; OemBridge reports the real position. Only fall back to a
        // stateless "changed" event when the OEM service isn't available.
        if (svc.micMuted != null) return;
        Bundle b = intent.getExtras();
        StringBuilder raw = new StringBuilder();
        Boolean muted = null;
        if (b != null) {
            for (String k : b.keySet()) {
                Object v = b.get(k);
                raw.append(k).append('=').append(v).append(' ');
                String lk = k.toLowerCase();
                if (lk.contains("mute") || lk.contains("state")) {
                    if (v instanceof Boolean) muted = (Boolean) v;
                    else if (v instanceof Integer) muted = ((Integer) v) != 0;
                    else if (v instanceof String) muted = "1".equals(v) || "true".equalsIgnoreCase((String) v) || "muted".equalsIgnoreCase((String) v);
                }
            }
        }
        Log.i(App.TAG, "mic switch broadcast: " + raw);
        Intent out = new Intent(ACTION_MIC);
        if (muted != null) out.putExtra("muted", muted.booleanValue());
        svc.sendBroadcast(out);
        emit("mic", muted == null ? "changed" : muted ? "muted" : "unmuted");
    }

    private void emit(String type, String value) {
        Log.i(App.TAG, "event " + type + "=" + value);
        svc.sendBroadcast(new Intent(ACTION_EVENT).putExtra("type", type).putExtra("value", value));
        svc.webhook.send(type, value);
    }
}
