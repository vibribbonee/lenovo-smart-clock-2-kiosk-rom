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

/**
 * Hardware mic switch feedback, like the stock firmware / smart displays:
 *  - while muted: a small red pill with a crossed-out mic in the top-right corner,
 *  - when the switch moves: a 2 s banner "Microphone off" / "Microphone on" at the top.
 * Both are non-touchable, so the dashboard underneath keeps working.
 */
final class MicIndicator {
    private static final long BANNER_MS = 2000;
    private final Context ctx;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView pill, banner;
    private final Runnable hideBanner = this::removeBanner;

    MicIndicator(Context ctx) {
        this.ctx = ctx;
        this.wm = ctx.getSystemService(WindowManager.class);
    }

    void set(boolean muted, boolean announce) {
        if (muted) showPill(); else removePill();
        if (announce) showBanner(muted ? "🎙️⃠  Microphone off" : "🎙️  Microphone on", muted);
    }

    private WindowManager.LayoutParams lp(int gravity, int x, int y) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = gravity;
        p.x = x;
        p.y = y;
        return p;
    }

    private TextView label(String text, int bg, float size, int padH, int padV) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(size);
        t.setPadding(padH, padV, padH, padV);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(100);
        t.setBackground(d);
        return t;
    }

    private void showPill() {
        if (pill != null) return;
        pill = label("🎙️⃠", 0xE6D93025, 13, 14, 4);
        wm.addView(pill, lp(Gravity.TOP | Gravity.END, 10, 8));
    }

    private void removePill() {
        if (pill != null) { wm.removeView(pill); pill = null; }
    }

    private void showBanner(String text, boolean muted) {
        removeBanner();
        banner = label(text, muted ? 0xF0D93025 : 0xF0303134, 16, 28, 12);
        wm.addView(banner, lp(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 20));
        handler.postDelayed(hideBanner, BANNER_MS);
    }

    private void removeBanner() {
        handler.removeCallbacks(hideBanner);
        if (banner != null) { wm.removeView(banner); banner = null; }
    }

    void destroy() { removeBanner(); removePill(); }
}
