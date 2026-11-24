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
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hide = this::hide;
    private final LinearLayout root;
    private SeekBar brightness, volume, light;
    private Switch auto;
    private View lightRow;
    private TextView wifi, bt, dnd, night;
    private boolean shown;

    QuickPanel(NavService svc) {
        this.svc = svc;
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

        LinearLayout r4 = row();
        wifi = tile("Wi-Fi", v -> { hide(); svc.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); });
        bt = tile("Bluetooth", v -> toggleBluetooth());
        dnd = tile("Do not disturb", v -> {
            boolean on = !isDnd();
            control(new Intent(CONTROL).putExtra("cmd", "dnd").putExtra("on", on));
            handler.postDelayed(this::refresh, 400);
        });
        night = tile("Night light", v -> {
            boolean on = !isNight();
            control(new Intent(CONTROL).putExtra("cmd", "night").putExtra("on", on));
            handler.postDelayed(this::refresh, 400);
        });
        TextView off = tile("Screen off", v -> { hide(); svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN); });
        off.setOnLongClickListener(v -> { hide(); control(new Intent(CONTROL).putExtra("cmd", "reboot")); return true; });
        for (TextView t : new TextView[]{wifi, bt, dnd, night, off}) r4.addView(t, weight());
        root.addView(r4);
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
        setOn(wifi, nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        setOn(bt, a != null && a.isEnabled());
        setOn(dnd, isDnd());
        setOn(night, isNight());
    }

    private boolean isAuto() {
        return Settings.System.getInt(svc.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
    }

    private boolean isDnd() { return Settings.Global.getInt(svc.getContentResolver(), "zen_mode", 0) != 0; }

    private boolean isNight() { return Settings.Secure.getInt(svc.getContentResolver(), "night_display_activated", 0) != 0; }

    private void putBrightness(int p) {
        Settings.System.putInt(svc.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, p + BrightnessActivity.MIN);
    }

    private void toggleBluetooth() {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) return;
        if (a.isEnabled()) a.disable(); else a.enable();
        handler.postDelayed(this::refresh, 1500);
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
        s.setOnSeekBarChangeListener(new Seek(this, onChange, onRelease));
        return s;
    }

    private TextView tile(String label, View.OnClickListener click) {
        TextView t = new TextView(svc);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(8, 22, 8, 22);
        t.setOnClickListener(v -> { click.onClick(v); restartTimer(); });
        setOn(t, false);
        return t;
    }

    private static void setOn(TextView t, boolean on) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(20);
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
