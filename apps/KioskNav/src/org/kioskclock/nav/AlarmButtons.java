package org.kioskclock.nav;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

/**
 * Snooze / stop a ringing Kiosk Satellite alarm by pressing the alarm screen's own buttons, the way
 * a finger would. Kiosk Satellite has no outside command for this (only voice wake/cancel
 * broadcasts), and an alarm clock must not depend on Home Assistant being up, so this stays local.
 * Triggered by org.kioskclock.nav.ALARM (extra "cmd": snooze | stop), sent by ClockBridge for the
 * tap / hold gestures. Signature-permission protected, like SCREEN. Does nothing if no alarm shows.
 */
final class AlarmButtons extends BroadcastReceiver {
    static final String ACTION = "org.kioskclock.nav.ALARM";
    private static final String KIOSK = "me.jxl.kiosk_satellite";
    private static final String TAG = "KioskNav";

    private final AccessibilityService svc;

    AlarmButtons(AccessibilityService svc) { this.svc = svc; }

    @Override
    public void onReceive(Context c, Intent i) {
        String cmd = i.getStringExtra("cmd");
        if ("snooze".equals(cmd) || "stop".equals(cmd)) press(cmd);
    }

    /** True if a matching button was found and clicked. */
    boolean press(String cmd) {
        List<AccessibilityWindowInfo> windows = svc.getWindows();
        for (AccessibilityWindowInfo w : windows) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            if (!KIOSK.contentEquals(String.valueOf(root.getPackageName()))) continue;
            AccessibilityNodeInfo hit = find(root, cmd);
            if (hit == null) continue;
            AccessibilityNodeInfo target = hit;
            while (target != null && !target.isClickable()) target = target.getParent();
            boolean ok = target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Log.i(TAG, "alarm " + cmd + ": pressed \"" + label(hit) + "\" -> " + ok);
            return ok;
        }
        Log.i(TAG, "alarm " + cmd + ": no alarm button on screen");
        return false;
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, String cmd) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty() && seen++ < 400) {
            AccessibilityNodeInfo n = queue.poll();
            if (n.isVisibleToUser() && matches(label(n), cmd)) return n;
            for (int k = 0; k < n.getChildCount(); k++) {
                AccessibilityNodeInfo ch = n.getChild(k);
                if (ch != null) queue.add(ch);
            }
        }
        return null;
    }

    /** Text or content description, lower case, first line only (Flutter joins a button's texts with newlines). */
    private static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        if (t == null) return "";
        String s = t.toString().trim().toLowerCase(Locale.ROOT);
        int nl = s.indexOf('\n');
        return nl >= 0 ? s.substring(0, nl).trim() : s;
    }

    static boolean matches(String label, String cmd) {
        if (cmd.equals("snooze")) return label.equals("snooze") || label.startsWith("snooze ");
        return label.equals("stop") || label.equals("stop alarm") || label.equals("dismiss");
    }
}
