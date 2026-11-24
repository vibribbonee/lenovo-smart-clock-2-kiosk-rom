package org.kioskclock.nav;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/** Short confirmation at the top of the screen (same look as the mic switch message). */
final class Snackbar {
    private static final long SHOW_MS = 2500;
    private final Context ctx;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hide = this::hide;
    private View shown;

    Snackbar(Context ctx) {
        this.ctx = ctx;
        this.wm = ctx.getSystemService(WindowManager.class);
    }

    void show(String text) {
        hide();
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        t.setPadding(32, 14, 32, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0303134);
        bg.setCornerRadius(100);
        t.setBackground(bg);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        p.y = 20;
        shown = t;
        wm.addView(t, p);
        handler.postDelayed(hide, SHOW_MS);
    }

    void hide() {
        handler.removeCallbacks(hide);
        if (shown != null) { wm.removeView(shown); shown = null; }
    }
}
