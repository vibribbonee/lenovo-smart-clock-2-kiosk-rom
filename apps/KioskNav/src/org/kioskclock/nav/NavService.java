package org.kioskclock.nav;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Edge strip + swipe-up navigation bar for a device without SystemUI.
 * Back and Home use performGlobalAction, which system_server handles by injecting key events,
 * so they work without SystemUI. Recents is intentionally absent (it needs SystemUI).
 */
public class NavService extends AccessibilityService {
    private static final int STRIP_PX = 12;      // touch strip height at the bottom edge
    private static final int BAR_PX = 72;        // button bar height (~48dp at 240 dpi)
    private static final int SWIPE_PX = 30;      // upward distance that opens the bar
    private static final long AUTO_HIDE_MS = 4000;

    private WindowManager wm;
    private View strip;
    private LinearLayout bar;
    private boolean barShown;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hide = this::hideBar;

    @Override
    protected void onServiceConnected() {
        wm = getSystemService(WindowManager.class);
        addStrip();
        buildBar();
    }

    private WindowManager.LayoutParams params(int height, boolean watchOutside) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (watchOutside) flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        return lp;
    }

    private void addStrip() {
        strip = new View(this);
        strip.setBackgroundColor(Color.TRANSPARENT);
        strip.setOnTouchListener(new StripTouch(this));
        wm.addView(strip, params(STRIP_PX, false));
    }

    /**
     * Opens the bar once the finger has moved SWIPE_PX upward from the strip.
     * Static nested on purpose: d8 34.0.0 crashes on javac 21 output for non-static inner classes.
     */
    private static final class StripTouch implements View.OnTouchListener {
        private final NavService svc;
        private float downY;
        private boolean fired;

        StripTouch(NavService svc) { this.svc = svc; }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downY = e.getRawY();
                fired = false;
            } else if (action == MotionEvent.ACTION_MOVE && !fired && downY - e.getRawY() > SWIPE_PX) {
                fired = true;
                svc.showBar();
            }
            return true;
        }
    }

    private TextView button(String label, String description) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setContentDescription(description);
        t.setTextColor(Color.WHITE);
        t.setTextSize(24);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        return t;
    }

    private void buildBar() {
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC000000);
        bg.setCornerRadii(new float[]{24, 24, 24, 24, 0, 0, 0, 0});
        bar.setBackground(bg);

        TextView back = button("◀", "Back");
        back.setOnClickListener(v -> { hideBar(); performGlobalAction(GLOBAL_ACTION_BACK); });
        TextView home = button("●", "Home");
        home.setOnClickListener(v -> { hideBar(); performGlobalAction(GLOBAL_ACTION_HOME); });
        TextView settings = button("⚙", "Settings (hold)");
        settings.setOnClickListener(v -> {
            Toast.makeText(this, "Hold to open Settings", Toast.LENGTH_SHORT).show();
            restartTimer();
        });
        settings.setOnLongClickListener(v -> {
            hideBar();
            Intent i = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        });
        bar.addView(back);
        bar.addView(home);
        bar.addView(settings);
        bar.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { hideBar(); return true; }
            return false;
        });
    }

    private void showBar() {
        if (barShown) { restartTimer(); return; }
        wm.addView(bar, params(BAR_PX, true));
        barShown = true;
        restartTimer();
    }

    private void restartTimer() {
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, AUTO_HIDE_MS);
    }

    private void hideBar() {
        handler.removeCallbacks(hide);
        if (barShown) { wm.removeView(bar); barShown = false; }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        hideBar();
        if (strip != null) wm.removeView(strip);
        super.onDestroy();
    }
}
