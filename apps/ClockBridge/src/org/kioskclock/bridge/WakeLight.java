package org.kioskclock.bridge;

import android.app.AlarmManager;
import android.util.Log;

/**
 * Wake-up light: fades the dock light (and/or tells Home Assistant to fade its lights) in over the
 * minutes before the next alarm. The alarm time is Android's "next alarm clock", which Kiosk
 * Satellite sets with setAlarmClock, so this runs on the clock without Home Assistant.
 *
 * Settings.Global:
 *   kioskclock_wake_light    off (default) | dock | ha | both
 *   kioskclock_wake_minutes  fade length, 5..60 (default 20)
 *   kioskclock_wake_max      dock light limit, 1..10 (default 10)
 *
 * A fade only starts for an alarm seen before its fade window opened, so a snooze (Kiosk
 * reschedules a few minutes out) or a boot in the middle of the window never jumps the light up.
 * Changing the dock light by hand during the fade stops it there. Skipping or deleting the alarm
 * puts the light back as it was. After the alarm the light stays at its limit.
 * Webhook events: wake_light = start:SECONDS_UNTIL_ALARM | cancel (for HA lights).
 */
final class WakeLight implements Runnable {
    static final String MODE = "kioskclock_wake_light", MINUTES = "kioskclock_wake_minutes", MAX = "kioskclock_wake_max";
    private static final long TICK_MS = 10_000;

    private final BridgeService svc;
    private volatile boolean running = true;

    /** Next alarm seen while its fade window had not opened yet (0 = none). */
    private long armed;
    /** Alarm being faded towards (0 = no fade going). */
    private long fadeFor;
    private long fadeStart;
    private boolean fadeDock;
    private int levelBefore, lastSet;

    WakeLight(BridgeService svc) { this.svc = svc; }

    void stop() { running = false; }

    @Override
    public void run() {
        while (running) {
            try { tick(System.currentTimeMillis()); }
            catch (RuntimeException e) { Log.w(App.TAG, "wake light: " + e); }
            try { Thread.sleep(TICK_MS); } catch (InterruptedException e) { return; }
        }
    }

    /** Next alarm clock in epoch ms, 0 if none. */
    long nextAlarm() {
        AlarmManager.AlarmClockInfo next = svc.getSystemService(AlarmManager.class).getNextAlarmClock();
        return next == null ? 0 : next.getTriggerTime();
    }

    boolean active() { return fadeFor != 0; }

    private void tick(long now) {
        long next = nextAlarm();
        if (fadeFor != 0) {
            if (now >= fadeFor) {                                   // alarm time reached: end at the limit
                if (fadeDock) setDock(max());
                Log.i(App.TAG, "wake light: alarm time, fade done");
                fadeFor = 0;
            } else if (next != fadeFor) {                           // alarm skipped, deleted or moved
                Log.i(App.TAG, "wake light: alarm changed, fade cancelled");
                if (fadeDock && dockLevel() == lastSet) setDock(levelBefore);
                if (sendsToHa(mode())) svc.webhook.send("wake_light", "cancel");
                fadeFor = 0;
            } else if (fadeDock) {
                int cur = dockLevel();
                if (lastSet > 0 && cur >= 0 && cur != lastSet) {     // changed by hand: leave it there
                    Log.i(App.TAG, "wake light: dock light changed by hand, fade stopped");
                    fadeDock = false;
                } else {
                    double f = (double) (now - fadeStart) / (fadeFor - fadeStart);
                    int level = Math.max(1, Math.min(max(), 1 + (int) Math.floor((max() - 1) * f + 1e-9)));
                    if (level != lastSet) setDock(level);
                }
            }
            return;
        }
        String mode = mode();
        if (mode.equals("off") || next == 0) { armed = 0; return; }
        long start = next - minutes() * 60_000L;
        if (now < start) { armed = next; return; }
        if (armed != next || now >= next) return;                  // not seen before its window: no fade
        armed = 0;
        fadeFor = next;
        fadeStart = now;
        fadeDock = (mode.equals("dock") || mode.equals("both")) && svc.lastStatus.valid;
        levelBefore = Math.max(0, dockLevel());
        lastSet = 0;
        long secs = (next - now) / 1000;
        Log.i(App.TAG, "wake light: fading in over " + secs + " s (" + mode + ", dock limit " + max() + ")");
        if (sendsToHa(mode)) svc.webhook.send("wake_light", "start:" + secs);
        if (fadeDock) setDock(1);
    }

    private static boolean sendsToHa(String mode) { return mode.equals("ha") || mode.equals("both"); }

    private int dockLevel() {
        ChargeBase.Status s = svc.lastStatus;
        return s.valid ? Math.min(10, s.light) : -1;
    }

    private void setDock(int level) {
        if (svc.dock.setLight(level)) lastSet = level;
        svc.requestDockBroadcast();
    }

    private String mode() {
        String m = live(MODE, "off");
        return m.matches("off|dock|ha|both") ? m : "off";
    }

    private int minutes() { return clamp(live(MINUTES, "20"), 5, 60, 20); }

    private int max() { return clamp(live(MAX, "10"), 1, 10, 10); }

    private String live(String key, String def) {
        String v = android.provider.Settings.Global.getString(svc.getContentResolver(), key);
        return v == null || v.isEmpty() ? def : v;
    }

    private static int clamp(String v, int lo, int hi, int def) {
        try { return Math.max(lo, Math.min(hi, Integer.parseInt(v.trim()))); }
        catch (NumberFormatException e) { return def; }
    }
}
