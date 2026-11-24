package org.kioskclock.nav;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Hardware mic switch feedback. Style from Settings.Global "kioskclock_mic_indicator"
 * (default from provision.json, switchable live, e.g. `settings put global kioskclock_mic_indicator dot`):
 *   pill (default)  red pill with a crossed-out mic in the top-right corner while muted + 2 s banner on change
 *   dot             small red dot in the corner while muted + 2 s banner on change
 *   dot_quiet       small red dot only
 *   edge            thin red line along the bottom edge while muted (Echo Show light-bar style) + banner
 *   banner          2 s banner on change only
 *   hidden          nothing on screen (the panel status line and Home Assistant events remain)
 * All overlays are non-touchable, so the dashboard underneath keeps working.
 */
final class MicIndicator {
    static final String SETTING = "kioskclock_mic_indicator";
    private static final long BANNER_MS = 2000;
    private static final String MIC_OFF = "🎙️⃠";
    private static final String MIC_ON = "🎙️";

    private final Context ctx;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView pill, banner;
    private final Runnable hideBanner = this::removeBanner;
    private Boolean lastMuted;

    MicIndicator(Context ctx) {
        this.ctx = ctx;
        this.wm = ctx.getSystemService(WindowManager.class);
    }

    String style() {
        String s = Settings.Global.getString(ctx.getContentResolver(), SETTING);
        return s == null || s.isEmpty() ? "pill" : s;
    }

    void set(boolean muted, boolean announce) {
        lastMuted = muted;
        String style = style();
        removePill();
        if (muted && style.equals("edge")) showEdge();
        else if (muted && (style.equals("pill") || style.equals("dot") || style.equals("dot_quiet"))) {
            showPill(style.startsWith("dot"));
        }
        boolean banner = style.equals("pill") || style.equals("dot") || style.equals("banner") || style.equals("edge");
        if (announce && banner) showBanner(muted ? MIC_OFF + "  Microphone off" : MIC_ON + "  Microphone on", muted);
    }

    /** Re-apply after the style setting changed (no banner). */
    void restyle() { if (lastMuted != null) set(lastMuted, false); }

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

    private void showPill(boolean dot) {
        if (pill != null) return;
        if (dot) {
            pill = label("", 0xFFD93025, 1, 0, 0);
            pill.setWidth(14);
            pill.setHeight(14);
            wm.addView(pill, lp(Gravity.TOP | Gravity.END, 14, 12));
        } else {
            pill = label(MIC_OFF, 0xE6D93025, 13, 14, 4);
            wm.addView(pill, lp(Gravity.TOP | Gravity.END, 10, 8));
        }
    }

    private void showEdge() {
        if (pill != null) return;
        pill = new TextView(ctx);
        pill.setBackgroundColor(0xFFD93025);
        WindowManager.LayoutParams p = lp(Gravity.BOTTOM, 0, 0);
        p.width = WindowManager.LayoutParams.MATCH_PARENT;
        p.height = 5;
        wm.addView(pill, p);
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
