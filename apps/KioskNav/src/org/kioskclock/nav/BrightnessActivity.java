package org.kioskclock.nav;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Brightness popup. Handles com.android.intent.action.SHOW_BRIGHTNESS_DIALOG, which Settings'
 * "Brightness level" entry starts; on stock Android SystemUI provides it, and this firmware has no
 * SystemUI, so Settings crashed. Writes the normal system settings (WRITE_SETTINGS is granted to
 * preinstalled apps), so Android's own display/auto-brightness logic does the rest.
 */
public class BrightnessActivity extends Activity {
    static final int MIN = 5, MAX = 255;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setGravity(Gravity.TOP);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setDimAmount(0.2f);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(32, 20, 32, 20);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE202124);
        bg.setCornerRadius(28);
        root.setBackground(bg);

        TextView sun = new TextView(this);
        sun.setText("☀");
        sun.setTextSize(24);
        sun.setTextColor(Color.WHITE);
        sun.setPadding(0, 0, 16, 0);
        root.addView(sun);

        SeekBar bar = new SeekBar(this);
        bar.setMax(MAX - MIN);
        bar.setProgress(Math.max(0, get(Settings.System.SCREEN_BRIGHTNESS, 102) - MIN));
        bar.setOnSeekBarChangeListener(new BrightnessListener(this));
        root.addView(bar, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch auto = new Switch(this);
        auto.setText("Auto");
        auto.setTextColor(Color.WHITE);
        auto.setPadding(16, 0, 0, 0);
        auto.setChecked(get(Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        auto.setOnCheckedChangeListener((v, on) -> Settings.System.putInt(getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                on ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL));
        root.addView(auto);

        setContentView(root);
        getWindow().setLayout(720, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    int get(String key, int def) { return Settings.System.getInt(getContentResolver(), key, def); }

    boolean isAuto() { return get(Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC; }

    void setBrightness(int progress) {
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, progress + MIN);
    }

    /** Static nested on purpose (d8 34.0.0 + javac 21 inner-class issue, see build_apps.sh). */
    private static final class BrightnessListener implements SeekBar.OnSeekBarChangeListener {
        private final BrightnessActivity a;
        BrightnessListener(BrightnessActivity a) { this.a = a; }
        // Manual: follow the finger. Auto: Android treats a brightness change as a "short-term"
        // nudge of the adaptive curve, so send one value on release (as SystemUI does), not dozens.
        @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { if (fromUser && !a.isAuto()) a.setBrightness(p); }
        @Override public void onStartTrackingTouch(SeekBar s) { }
        @Override public void onStopTrackingTouch(SeekBar s) { if (a.isAuto()) a.setBrightness(s.getProgress()); }
    }
}
