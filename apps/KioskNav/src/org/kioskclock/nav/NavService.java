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

    private android.widget.FrameLayout volumeView;
    private View volumeFill;
    private boolean volumeShown;
    private final Runnable hideVolume = this::hideVolumeBar;
    private VolumeReceiver volumeReceiver;

    @Override
    protected void onServiceConnected() {
        wm = getSystemService(WindowManager.class);
        addStrip();
        buildBar();
        buildVolumeBar();
        volumeReceiver = new VolumeReceiver(this);
        registerReceiver(volumeReceiver, new android.content.IntentFilter(VolumeReceiver.ACTION));
        panel = new QuickPanel(this);
        dockReceiver = new DockReceiver(panel);
        registerReceiver(dockReceiver, new android.content.IntentFilter(DockReceiver.ACTION));
        micIndicator = new MicIndicator(this);
        micReceiver = new MicReceiver(panel, micIndicator);
        registerReceiver(micReceiver, new android.content.IntentFilter(MicReceiver.ACTION));
        addTopStrip();
        blackout = new Blackout(this);
        screenReceiver = new ScreenReceiver(this);
        registerReceiver(screenReceiver, new android.content.IntentFilter(ScreenReceiver.ACTION),
                "org.kioskclock.permission.CONTROL", null);
    }

    private Blackout blackout;
    private ScreenReceiver screenReceiver;

    void blackout() { hideBar(); if (panel != null) panel.hide(); blackout.on(); }

    void wake() { blackout.off(); }

    private QuickPanel panel;
    private DockReceiver dockReceiver;
    private MicReceiver micReceiver;
    private MicIndicator micIndicator;
    private View topStrip;

    /** Swipe down from the top edge opens the quick panel. */
    private void addTopStrip() {
        topStrip = new View(this);
        topStrip.setBackgroundColor(Color.TRANSPARENT);
        topStrip.setOnTouchListener(new StripTouch(-1, this::showPanel));
        WindowManager.LayoutParams lp = params(STRIP_PX, false);
        lp.gravity = Gravity.TOP;
        wm.addView(topStrip, lp);
    }

    void showPanel() { hideBar(); panel.show(); }

    /** Slim bar at the top centre: there is no SystemUI volume dialog on this firmware. */
    private void buildVolumeBar() {
        volumeView = new android.widget.FrameLayout(this);
        GradientDrawable track = new GradientDrawable();
        track.setColor(0xCC000000);
        track.setCornerRadius(12);
        volumeView.setBackground(track);
        volumeView.setPadding(6, 6, 6, 6);
        volumeFill = new View(this);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Color.WHITE);
        fill.setCornerRadius(8);
        volumeFill.setBackground(fill);
        volumeView.addView(volumeFill, new android.widget.FrameLayout.LayoutParams(0, android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
    }

    void showVolume(int percent) {
        int width = 320, height = 28;
        android.widget.FrameLayout.LayoutParams fl = (android.widget.FrameLayout.LayoutParams) volumeFill.getLayoutParams();
        fl.width = Math.max(0, (width - 12) * percent / 100);
        volumeFill.setLayoutParams(fl);
        if (!volumeShown) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(width, height,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = 24;
            wm.addView(volumeView, lp);
            volumeShown = true;
        }
        handler.removeCallbacks(hideVolume);
        handler.postDelayed(hideVolume, 1500);
    }

    private void hideVolumeBar() {
        handler.removeCallbacks(hideVolume);
        if (volumeShown) { wm.removeView(volumeView); volumeShown = false; }
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
        strip.setOnTouchListener(new StripTouch(+1, this::showBar));
        wm.addView(strip, params(STRIP_PX, false));
    }

    /**
     * Fires once the finger has moved SWIPE_PX away from the edge: direction +1 = upward (bottom
     * strip), -1 = downward (top strip).
     * Static nested on purpose: d8 34.0.0 crashes on javac 21 output for non-static inner classes.
     */
    private static final class StripTouch implements View.OnTouchListener {
        private final int direction;
        private final Runnable onSwipe;
        private float downY;
        private boolean fired;

        StripTouch(int direction, Runnable onSwipe) { this.direction = direction; this.onSwipe = onSwipe; }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downY = e.getRawY();
                fired = false;
            } else if (action == MotionEvent.ACTION_MOVE && !fired && (downY - e.getRawY()) * direction > SWIPE_PX) {
                fired = true;
                onSwipe.run();
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
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Kiosk Satellite opening a window (voice UI after the wake word, alarm, notification
        // overlay) lifts the manual black screen, so voice works while it's black.
        if (blackout != null && blackout.isOn() && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && "me.jxl.kiosk_satellite".contentEquals(String.valueOf(event.getPackageName()))) {
            wake();
        }
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        hideBar();
        hideVolumeBar();
        if (volumeReceiver != null) unregisterReceiver(volumeReceiver);
        if (dockReceiver != null) unregisterReceiver(dockReceiver);
        if (micReceiver != null) unregisterReceiver(micReceiver);
        if (micIndicator != null) micIndicator.destroy();
        if (screenReceiver != null) unregisterReceiver(screenReceiver);
        if (blackout != null) blackout.off();
        if (panel != null) panel.hide();
        if (topStrip != null) wm.removeView(topStrip);
        if (strip != null) wm.removeView(strip);
        super.onDestroy();
    }
}
