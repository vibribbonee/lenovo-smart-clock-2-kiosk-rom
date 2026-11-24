package org.kioskclock.nav;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * "Screen off" for a clock without a power key: backlight to 0 plus a full-screen black layer.
 * Any touch (or a wake command from ClockBridge / Home Assistant) restores the previous brightness
 * and mode. A real sleep (lockNow) would need a power key to wake up again.
 */
final class Blackout {
    private final Context ctx;
    private final WindowManager wm;
    private View cover;
    private int savedBrightness, savedMode;

    Blackout(Context ctx) {
        this.ctx = ctx;
        this.wm = ctx.getSystemService(WindowManager.class);
    }

    boolean isOn() { return cover != null; }

    void on() {
        if (cover != null) return;
        savedBrightness = Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 102);
        savedMode = Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
        Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 0);
        cover = new View(ctx);
        cover.setBackgroundColor(Color.BLACK);
        cover.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) off();
            return true;   // swallow the wake-up tap so it doesn't press something underneath
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE);
        wm.addView(cover, lp);
    }

    void off() {
        if (cover == null) return;
        wm.removeView(cover);
        cover = null;
        Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, savedBrightness);
        Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, savedMode);
    }
}
