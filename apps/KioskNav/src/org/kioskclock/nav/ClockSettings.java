package org.kioskclock.nav;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;

/**
 * On-clock settings for the ROM's own features (quick panel → "Clock" tile). The same keys can be
 * changed from Home Assistant (ClockBridge HTTP API) or adb; every change applies live.
 * Drawn as a full-screen accessibility overlay, not an Activity: Kiosk Satellite brings itself back to
 * the front within seconds and would cover an Activity.
 * Writes Settings.Global, which needs WRITE_SECURE_SETTINGS (granted to KioskNav by ClockBridge at boot).
 */
final class ClockSettings {
    private static final String[][] STYLE = {{"dot", "Dot"}, {"dot_quiet", "Quiet dot"}, {"google", "Badge"},
            {"pill", "Pill"}, {"edge", "Edge line"}, {"banner", "Banner only"}, {"hidden", "Hidden"}};
    private static final String[][] COLOR = {{"#D93025", "Red"}, {"#F29900", "Amber"}, {"#FFFFFF", "White"},
            {"#66FFFFFF", "Dim"}};
    private static final String[][] SIZE = {{"10", "Small"}, {null, "Default"}, {"22", "Large"}};
    private static final String[][] TAP_SPEED = {{"300", "Fast"}, {null, "Normal"}, {"700", "Relaxed"}};
    private static final String[][] WAKE = {{null, "Off"}, {"dock", "Dock light"}, {"ha", "Home Assistant lights"},
            {"both", "Both"}};
    private static final String[][] WAKE_MIN = {{"10", "10 min"}, {null, "20 min"}, {"30", "30 min"}, {"45", "45 min"}};
    private static final String[][] WAKE_MAX = {{"3", "Low"}, {"6", "Medium"}, {null, "Full"}};
    private static final String[][] HOLD = {{"alarm_snooze", "Snooze alarm"}, {"alarm_stop", "Stop alarm"},
            {"voice", "Voice assistant"}, {"play_pause", "Play / pause"},
            {"mute", "Mute speaker"}, {"dock_light", "Dock light"}, {"night_light", "Night light"},
            {"screen_off", "Screen off"}, {"screen_toggle", "Screen on / off"}, {"ha_event", "Home Assistant only"},
            {"none", "Nothing"}};

    private final Context ctx;
    private final WindowManager wm;
    private ScrollView scroll;
    private LinearLayout list;
    /** API token shown after "Show token" (fetched from ClockBridge); cleared when the screen closes. */
    private String token;

    ClockSettings(Context ctx) { this.ctx = ctx; this.wm = ctx.getSystemService(WindowManager.class); }

    boolean isShown() { return scroll != null; }

    void show() {
        if (scroll != null) return;
        scroll = new ScrollView(ctx);
        scroll.setBackgroundColor(0xFF202124);
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(32, 24, 32, 32);
        scroll.addView(list);
        build();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        wm.addView(scroll, lp);
    }

    void hide() {
        if (scroll == null) return;
        wm.removeView(scroll);
        scroll = null;
        token = null;
    }

    private void build() {
        list.removeAllViews();
        TextView title = text("Clock settings", 22, Color.WHITE);
        title.setPadding(0, 0, 0, 8);
        list.addView(title);

        section("Mute indicator", "Shown while the mic switch on the back is set to mute");
        chips("kioskclock_mic_indicator", STYLE, "dot");
        section("Indicator colour", null);
        chips("kioskclock_mic_color", COLOR, "#D93025");
        section("Indicator size", null);
        chips("kioskclock_mic_size", SIZE, null);
        section("Hold volume +", null);
        chips("kioskclock_hold_volume_up", HOLD, "voice");
        section("Hold volume −", null);
        chips("kioskclock_hold_volume_down", HOLD, "dock_light");
        section("Tap on top", "A firm tap on the top of the clock");
        chips("kioskclock_tap_single", HOLD, "alarm_snooze");
        section("Double tap on top", null);
        chips("kioskclock_tap_double", HOLD, "alarm_stop");
        section("Double-tap speed", "How quickly the second tap must follow. Relaxed is easier, but a single tap then waits longer to act.");
        chips("kioskclock_double_tap_ms", TAP_SPEED, null);

        section("Wake-up light", "Fades in before the next Kiosk alarm. The screen has its own sunrise in Kiosk's alarm settings;"
                + " pick the Home Assistant lights in the Kiosk Clock integration's options.");
        chips("kioskclock_wake_light", WAKE, null);
        section("Fade length", null);
        chips("kioskclock_wake_minutes", WAKE_MIN, null);
        section("Dock light limit", "How bright the dock light gets by the alarm");
        chips("kioskclock_wake_max", WAKE_MAX, null);

        String ip = ipAddress();
        section("Home Assistant", "Install the Kiosk Clock integration (HACS). The clock is found automatically;"
                + " otherwise add it by address: " + (ip == null ? "<clock-ip>" : ip) + " (port 8765).\n"
                + "When Home Assistant asks, tap Allow on the clock. (Or type the token below.)");
        if (token == null) {
            TextView show = chip("Show token", false);
            show.setOnClickListener(v -> requestToken());
            LinearLayout r = new LinearLayout(ctx);
            r.addView(show);
            list.addView(r);
        } else {
            TextView t = text(group(token), 20, Color.WHITE);
            t.setTypeface(android.graphics.Typeface.MONOSPACE);
            t.setPadding(0, 4, 0, 0);
            list.addView(t);
        }
        TextView done = chip("Done", true);
        done.setOnClickListener(v -> hide());
        LinearLayout row = new LinearLayout(ctx);
        row.setPadding(0, 24, 0, 0);
        row.addView(done);
        list.addView(row);
    }

    /** Asks ClockBridge for the token (ordered broadcast; both apps hold the signature permission). */
    private void requestToken() {
        android.content.Intent i = new android.content.Intent(QuickPanel.CONTROL).setPackage("org.kioskclock.bridge")
                .putExtra("cmd", "token");
        ctx.sendOrderedBroadcast(i, "org.kioskclock.permission.CONTROL", new TokenResult(this), null, 0, null, null);
    }

    private void onToken(String t) {
        if (scroll == null) return;
        if (t == null || t.isEmpty()) {
            Toast.makeText(ctx, "ClockBridge did not answer (update the ROM)", Toast.LENGTH_LONG).show();
            return;
        }
        token = t;
        int y = scroll.getScrollY();
        build();
        list.post(() -> scroll.scrollTo(0, y));
    }

    /** Groups of four for reading off the screen; the HA integration ignores the spaces. */
    private static String group(String t) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i += 4) {
            if (i > 0) sb.append(i % 16 == 0 ? '\n' : ' ');
            sb.append(t, i, Math.min(t.length(), i + 4));
        }
        return sb.toString();
    }

    private static final class TokenResult extends android.content.BroadcastReceiver {
        private final ClockSettings owner;
        TokenResult(ClockSettings owner) { this.owner = owner; }
        @Override
        public void onReceive(Context c, android.content.Intent i) { owner.onToken(getResultData()); }
    }

    private void section(String name, String hint) {
        TextView t = text(name, 16, Color.WHITE);
        t.setPadding(0, 28, 0, 4);
        list.addView(t);
        if (hint != null) {
            TextView h = text(hint, 13, 0xFFBDC1C6);
            h.setPadding(0, 0, 0, 8);
            list.addView(h);
        }
    }

    private void chips(String key, String[][] options, String def) {
        String cur = Settings.Global.getString(ctx.getContentResolver(), key);
        if (cur == null) cur = def;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String[] o : options) {
            boolean selected = o[0] == null ? cur == null : o[0].equalsIgnoreCase(cur == null ? "" : cur);
            TextView c = chip(o[1], selected);
            c.setOnClickListener(v -> set(key, o[0]));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 4, 12, 4);
            row.addView(c, lp);
        }
        HorizontalScrollView h = new HorizontalScrollView(ctx);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(row);
        list.addView(h);
    }

    private void set(String key, String value) {
        try {
            if (value == null) Settings.Global.putString(ctx.getContentResolver(), key, null);
            else Settings.Global.putString(ctx.getContentResolver(), key, value);
        } catch (SecurityException e) {
            Toast.makeText(ctx, "Not allowed to change settings yet (restart the clock once)", Toast.LENGTH_LONG).show();
            return;
        }
        int y = ((View) list.getParent()).getScrollY();
        build();
        list.post(() -> ((View) list.getParent()).scrollTo(0, y));
    }

    private TextView chip(String label, boolean selected) {
        TextView c = text(label, 15, selected ? 0xFF202124 : Color.WHITE);
        c.setGravity(Gravity.CENTER);
        c.setPadding(28, 16, 28, 16);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(40);
        d.setColor(selected ? 0xFF8AB4F8 : 0xFF3C4043);
        c.setBackground(d);
        return c;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private String ipAddress() {
        try {
            android.net.ConnectivityManager cm = ctx.getSystemService(android.net.ConnectivityManager.class);
            android.net.LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
            if (lp == null) return null;
            for (android.net.LinkAddress a : lp.getLinkAddresses()) {
                if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
            }
        } catch (RuntimeException ignored) { }
        return null;
    }
}
