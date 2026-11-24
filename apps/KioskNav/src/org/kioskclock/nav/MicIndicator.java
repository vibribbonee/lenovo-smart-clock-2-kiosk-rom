package org.kioskclock.nav;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Hardware mic switch feedback, configurable live through Settings.Global (defaults from provision.json):
 *
 *   kioskclock_mic_indicator   dot (default) | dot_quiet | google | pill | edge | banner | hidden
 *       dot        small dot in the top-right corner while muted + short message on change
 *       dot_quiet  the dot only
 *       google     Nest Hub style: round mic-off badge in the corner + message at the bottom
 *       pill       mic-off pill in the corner (stock Lenovo style) + message at the top
 *       edge       thin line along the bottom edge (Echo Show style) + message
 *       banner     message on change only
 *       hidden     nothing on screen (KioskNav panel status + Home Assistant events remain)
 *   kioskclock_mic_color       #RRGGBB / #AARRGGBB, default #D93025 (red)
 *   kioskclock_mic_size        dot/badge size in px, default 14 (dot) / 36 (badge)
 *
 * Persistent indicators shift by up to 2 px every 10 minutes (LCD image-retention hygiene).
 * All overlays are non-touchable, so the dashboard underneath keeps working.
 */
final class MicIndicator {
    static final String SETTING = "kioskclock_mic_indicator";
    static final String COLOR = "kioskclock_mic_color";
    static final String SIZE = "kioskclock_mic_size";
    private static final long MESSAGE_MS = 2000;
    private static final long SHIFT_MS = 10 * 60 * 1000;
    private static final int[][] SHIFTS = {{0, 0}, {2, 0}, {2, 2}, {0, 2}};

    private final Context ctx;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private View persistent, message;
    private WindowManager.LayoutParams persistentLp;
    private int baseX, baseY, shiftIndex;
    private final Runnable hideMessage = this::removeMessage;
    private final Runnable shift = this::shiftPersistent;
    private Boolean lastMuted;

    MicIndicator(Context ctx) {
        this.ctx = ctx;
        this.wm = ctx.getSystemService(WindowManager.class);
    }

    // ---- settings -----------------------------------------------------------------------------

    String style() {
        String s = Settings.Global.getString(ctx.getContentResolver(), SETTING);
        return s == null || s.isEmpty() ? "dot" : s;
    }

    private int color() {
        try {
            String s = Settings.Global.getString(ctx.getContentResolver(), COLOR);
            if (s != null && !s.isEmpty()) return Color.parseColor(s);
        } catch (IllegalArgumentException ignored) { }
        return 0xFFD93025;
    }

    private int size(int def) {
        try {
            String s = Settings.Global.getString(ctx.getContentResolver(), SIZE);
            if (s != null && !s.isEmpty()) return Math.max(6, Math.min(96, Integer.parseInt(s.trim())));
        } catch (NumberFormatException ignored) { }
        return def;
    }

    // ---- state --------------------------------------------------------------------------------

    void set(boolean muted, boolean announce) {
        lastMuted = muted;
        String style = style();
        removePersistent();
        if (muted) {
            switch (style) {
                case "dot": case "dot_quiet": showDot(); break;
                case "google": showBadge(); break;
                case "pill": showPill(); break;
                case "edge": showEdge(); break;
                default: break;
            }
        }
        if (announce && !style.equals("dot_quiet") && !style.equals("hidden")) {
            showMessage(muted, style.equals("google") ? Gravity.BOTTOM : Gravity.TOP);
        }
    }

    /** Re-apply after a setting changed (no message). */
    void restyle() { if (lastMuted != null) set(lastMuted, false); }

    void destroy() { removeMessage(); removePersistent(); }

    // ---- views --------------------------------------------------------------------------------

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

    private static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private void addPersistent(View v, WindowManager.LayoutParams p) {
        persistent = v;
        persistentLp = p;
        baseX = p.x;
        baseY = p.y;
        shiftIndex = 0;
        wm.addView(v, p);
        handler.postDelayed(shift, SHIFT_MS);
    }

    private void shiftPersistent() {
        if (persistent == null || persistentLp.width == WindowManager.LayoutParams.MATCH_PARENT) return;
        shiftIndex = (shiftIndex + 1) % SHIFTS.length;
        persistentLp.x = baseX + SHIFTS[shiftIndex][0];
        persistentLp.y = baseY + SHIFTS[shiftIndex][1];
        wm.updateViewLayout(persistent, persistentLp);
        handler.postDelayed(shift, SHIFT_MS);
    }

    private void showDot() {
        int s = size(14);
        View dot = new View(ctx);
        dot.setBackground(round(color(), s));
        WindowManager.LayoutParams p = lp(Gravity.TOP | Gravity.END, 14, 12);
        p.width = s;
        p.height = s;
        addPersistent(dot, p);
    }

    private void showBadge() {
        int s = size(36);
        MicOffIcon badge = new MicOffIcon(ctx, s, color(), Color.WHITE, true);
        addPersistent(badge, lp(Gravity.TOP | Gravity.END, 12, 10));
    }

    private void showPill() {
        LinearLayout pill = new LinearLayout(ctx);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(12, 4, 12, 4);
        pill.setBackground(round(color(), 100));
        pill.addView(new MicOffIcon(ctx, 26, 0x00000000, Color.WHITE, true));
        addPersistent(pill, lp(Gravity.TOP | Gravity.END, 10, 8));
    }

    private void showEdge() {
        View line = new View(ctx);
        line.setBackgroundColor(color());
        WindowManager.LayoutParams p = lp(Gravity.BOTTOM, 0, 0);
        p.width = WindowManager.LayoutParams.MATCH_PARENT;
        p.height = 5;
        addPersistent(line, p);
    }

    private void removePersistent() {
        handler.removeCallbacks(shift);
        if (persistent != null) { wm.removeView(persistent); persistent = null; }
    }

    /** Short message with the vector icon, Material snackbar-like. */
    private void showMessage(boolean muted, int gravity) {
        removeMessage();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(22, 10, 28, 10);
        box.setBackground(round(muted ? color() : 0xF0303134, 100));
        box.addView(new MicOffIcon(ctx, 30, 0x00000000, Color.WHITE, muted));
        TextView t = new TextView(ctx);
        t.setText(muted ? "Microphone is off" : "Microphone is on");
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setPadding(14, 0, 0, 0);
        box.addView(t);
        message = box;
        wm.addView(box, lp(gravity | Gravity.CENTER_HORIZONTAL, 0, gravity == Gravity.BOTTOM ? 28 : 20));
        handler.postDelayed(hideMessage, MESSAGE_MS);
    }

    private void removeMessage() {
        handler.removeCallbacks(hideMessage);
        if (message != null) { wm.removeView(message); message = null; }
    }
}
