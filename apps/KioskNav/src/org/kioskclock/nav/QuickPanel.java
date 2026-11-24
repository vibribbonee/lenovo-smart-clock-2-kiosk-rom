package org.kioskclock.nav;

import android.accessibilityservice.AccessibilityService;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.function.IntConsumer;

/**
 * Swipe-down quick panel (this firmware has no SystemUI quick settings).
 * Brightness/Auto, media volume, dock light (when docked), Wi-Fi, Bluetooth, Do Not Disturb,
 * Night Light, Screen off (long-press: restart). Privileged actions go to ClockBridge via
 * org.kioskclock.bridge.CONTROL (signature permission).
 */
final class QuickPanel {
    private static final int WIDTH = 760;
    private static final long AUTO_HIDE_MS = 8000;
    static final String CONTROL = "org.kioskclock.bridge.CONTROL";
    static final String BRIDGE = "org.kioskclock.bridge";

    private final NavService svc;
    private final ClockSettings settings;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hide = this::hide;
    private final LinearLayout root;
    private SeekBar brightness, volume, light;
    private Switch auto;
    private View lightRow;
    private TextView wifi, bt, night;
    private boolean shown;
    private TextView status;
    private Boolean micMuted;

    QuickPanel(NavService svc) {
        this.svc = svc;
        this.settings = new ClockSettings(svc);
        this.wm = svc.getSystemService(WindowManager.class);
        root = new LinearLayout(svc);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 20, 28, 20);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0202124);
        bg.setCornerRadii(new float[]{0, 0, 0, 0, 28, 28, 28, 28});
        root.setBackground(bg);
        root.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { hide(); return true; }
            restartTimer();
            return false;
        });
        build();
    }

    private void build() {
        LinearLayout r1 = row();
        r1.addView(icon("☀"));
        brightness = slider(BrightnessActivity.MAX - BrightnessActivity.MIN,
                p -> { if (!isAuto()) putBrightness(p); }, p -> { if (isAuto()) putBrightness(p); });
        r1.addView(brightness, weight());
        auto = new Switch(svc);
        auto.setText("Auto");
        auto.setTextColor(Color.WHITE);
        auto.setOnCheckedChangeListener((v, on) -> {
            Settings.System.putInt(svc.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    on ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            restartTimer();
        });
        r1.addView(auto);
        root.addView(r1);

        LinearLayout r2 = row();
        r2.addView(icon("🔊"));
        AudioManager am = svc.getSystemService(AudioManager.class);
        volume = slider(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                p -> am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0), p -> { });
        r2.addView(volume, weight());
        root.addView(r2);

        LinearLayout r3 = row();
        r3.addView(icon("💡"));
        light = slider(10, p -> { }, p -> control(new Intent(CONTROL).putExtra("cmd", "light").putExtra("level", p)));
        r3.addView(light, weight());
        lightRow = r3;
        lightRow.setVisibility(View.GONE);
        root.addView(r3);

        // Tiles: tap = main action, long-press = the matching settings page (as on stock Android).
        LinearLayout r4 = row();
        wifi = tile(v -> openSettings(Settings.ACTION_WIFI_SETTINGS), Settings.ACTION_WIFI_SETTINGS);
        bt = tile(v -> toggleBluetooth(), Settings.ACTION_BLUETOOTH_SETTINGS);
        night = tile(v -> {
            control(new Intent(CONTROL).putExtra("cmd", "night").putExtra("on", !isNight()));
            handler.postDelayed(this::refresh, 400);
        }, Settings.ACTION_DISPLAY_SETTINGS);
        TextView off = tile(v -> { hide(); svc.blackout(); }, null);
        off.setOnLongClickListener(v -> { hide(); control(new Intent(CONTROL).putExtra("cmd", "reboot")); return true; });
        setTile(off, "\uD83C\uDF19", "Screen off", "Hold: restart", false);
        // Tap = the ROM's own settings; hold = Android Settings (the nav bar has only Back / Home).
        TextView clock = tile(v -> { hide(); settings.show(); }, Settings.ACTION_SETTINGS);
        setTile(clock, "\u2699", "Settings", "Hold: Android", false);
        for (TextView t : new TextView[]{wifi, bt, night, off, clock}) r4.addView(t, weight());
        root.addView(r4);

        status = new TextView(svc);
        status.setTextColor(0xFFBDC1C6);
        status.setTextSize(13);
        status.setPadding(8, 10, 8, 0);
        status.setVisibility(View.GONE);
        root.addView(status);
    }

    // ---- show / hide --------------------------------------------------------------------------

    boolean isShown() { return shown; }

    void show() {
        refresh();
        control(new Intent(CONTROL).putExtra("cmd", "query"));
        if (!shown) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WIDTH,
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            wm.addView(root, lp);
            shown = true;
        }
        restartTimer();
    }

    void hide() {
        handler.removeCallbacks(hide);
        if (shown) { wm.removeView(root); shown = false; }
    }

    private void restartTimer() {
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, AUTO_HIDE_MS);
    }

    /** Hardware mic switch, from ClockBridge's org.kioskclock.bridge.MIC broadcast (null = unknown). */
    void onMic(Boolean muted) {
        micMuted = muted;
        if (muted == null) { status.setVisibility(View.GONE); return; }
        status.setText(muted ? "\uD83C\uDF99  Microphone muted (switch on the bottom)" : "\uD83C\uDF99  Microphone on");
        status.setVisibility(View.VISIBLE);
    }

    /** Called with ClockBridge's org.kioskclock.bridge.DOCK_STATE broadcast. */
    void onDockState(boolean valid, int level) {
        lightRow.setVisibility(valid ? View.VISIBLE : View.GONE);
        if (valid) light.setProgress(level);
    }

    // ---- state --------------------------------------------------------------------------------

    private void refresh() {
        brightness.setProgress(Math.max(0, Settings.System.getInt(svc.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, 102) - BrightnessActivity.MIN));
        auto.setChecked(isAuto());
        volume.setProgress(svc.getSystemService(AudioManager.class).getStreamVolume(AudioManager.STREAM_MUSIC));
        ConnectivityManager cm = svc.getSystemService(ConnectivityManager.class);
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        boolean wifiUp = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        setTile(wifi, "\uD83D\uDCF6", "Wi-Fi", wifiUp ? "Connected" : "Not connected", wifiUp);
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        String btState = "Unavailable";
        boolean btOn = false;
        if (a != null) {
            int st = a.getState();
            btOn = st == BluetoothAdapter.STATE_ON;
            boolean connected = btOn && (a.getProfileConnectionState(android.bluetooth.BluetoothProfile.A2DP) == BluetoothAdapter.STATE_CONNECTED
                    || a.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET) == BluetoothAdapter.STATE_CONNECTED);
            btState = st == BluetoothAdapter.STATE_TURNING_ON ? "Turning on\u2026"
                    : st == BluetoothAdapter.STATE_TURNING_OFF ? "Turning off\u2026"
                    : connected ? "Connected" : btOn ? "On" : "Off";
        }
        setTile(bt, "\u16D2", "Bluetooth", btState, btOn);
        boolean nightOn = isNight();
        setTile(night, "\uD83C\uDF05", "Night light", nightOn ? "On" : "Off", nightOn);
    }

    private boolean isAuto() {
        return Settings.System.getInt(svc.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
    }

    private boolean isNight() { return Settings.Secure.getInt(svc.getContentResolver(), "night_display_activated", 0) != 0; }

    private void putBrightness(int p) {
        Settings.System.putInt(svc.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, p + BrightnessActivity.MIN);
    }

    private void toggleBluetooth() {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) return;
        if (a.isEnabled()) a.disable(); else a.enable();
        handler.postDelayed(this::refresh, 300);
        handler.postDelayed(this::refresh, 2500);
    }

    private void control(Intent i) {
        svc.sendBroadcast(i.setPackage(BRIDGE), "org.kioskclock.permission.CONTROL");
        restartTimer();
    }

    // ---- small view helpers -------------------------------------------------------------------

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(svc);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(0, 8, 0, 8);
        return l;
    }

    private static LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(6, 0, 6, 0);
        return p;
    }

    private TextView icon(String s) {
        TextView t = new TextView(svc);
        t.setText(s);
        t.setTextSize(22);
        t.setTextColor(Color.WHITE);
        t.setMinWidth(56);
        return t;
    }

    private SeekBar slider(int max, IntConsumer onChange, IntConsumer onRelease) {
        SeekBar s = new SeekBar(svc);
        s.setMax(max);
        // Taller touch target (the default ~32 px row is hard to hit on this 240 dpi panel).
        s.setMinimumHeight(64);
        s.setPadding(28, 22, 28, 22);
        s.setScaleY(1.3f);
        s.setOnSeekBarChangeListener(new Seek(this, onChange, onRelease));
        return s;
    }

    private void openSettings(String action) {
        hide();
        svc.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private TextView tile(View.OnClickListener click, String longPressSettings) {
        TextView t = new TextView(svc);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(8, 16, 8, 16);
        t.setOnClickListener(v -> { click.onClick(v); restartTimer(); });
        if (longPressSettings != null) t.setOnLongClickListener(v -> { openSettings(longPressSettings); return true; });
        return t;
    }

    /** Android-style tile: icon + name on the first line, current state below; filled when on. */
    private static void setTile(TextView t, String icon, String label, String state, boolean on) {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        b.append(icon).append("  ").append(label).append("\n");
        int start = b.length();
        b.append(state);
        b.setSpan(new android.text.style.RelativeSizeSpan(0.85f), start, b.length(), 0);
        b.setSpan(new android.text.style.ForegroundColorSpan(on ? 0xFF3C4043 : 0xFFBDC1C6), start, b.length(), 0);
        t.setText(b);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(24);
        d.setColor(on ? 0xFF8AB4F8 : 0xFF3C4043);
        t.setBackground(d);
        t.setTextColor(on ? 0xFF202124 : Color.WHITE);
    }

    /** Static nested on purpose (d8 34.0.0 + javac 21 inner-class issue, see build_apps.sh). */
    private static final class Seek implements SeekBar.OnSeekBarChangeListener {
        private final QuickPanel panel;
        private final IntConsumer onChange, onRelease;
        Seek(QuickPanel panel, IntConsumer onChange, IntConsumer onRelease) {
            this.panel = panel; this.onChange = onChange; this.onRelease = onRelease;
        }
        @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) { onChange.accept(p); panel.restartTimer(); } }
        @Override public void onStartTrackingTouch(SeekBar s) { panel.restartTimer(); }
        @Override public void onStopTrackingTouch(SeekBar s) { onRelease.accept(s.getProgress()); panel.restartTimer(); }
    }
}
