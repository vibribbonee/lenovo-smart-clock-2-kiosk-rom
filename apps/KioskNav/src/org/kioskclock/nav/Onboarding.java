package org.kioskclock.nav;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;

/**
 * First-boot tour: the edge controls (this firmware has no SystemUI, so nobody would guess them),
 * the hardware gestures, and how to finish setup from a phone. Shown once (kioskclock_onboarded=1),
 * again from quick panel → Clock → "Show the tour". Three cards: Next / Done, Skip.
 */
final class Onboarding {
    static final String DONE = "kioskclock_onboarded";

    private final NavService svc;
    private final WindowManager wm;
    private View card;
    private int page;

    Onboarding(NavService svc) {
        this.svc = svc;
        this.wm = svc.getSystemService(WindowManager.class);
    }

    static boolean done(NavService svc) { return "1".equals(Settings.Global.getString(svc.getContentResolver(), DONE)); }

    void show() { page = 0; render(); }

    void hide() {
        if (card != null) { wm.removeView(card); card = null; }
    }

    private void finish() {
        hide();
        try { Settings.Global.putString(svc.getContentResolver(), DONE, "1"); }
        catch (SecurityException ignored) { }   // shown again next boot; harmless
    }

    private void render() {
        hide();
        svc.wake();
        LinearLayout box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40, 30, 40, 26);
        box.setBackground(round(0xF7202124, 36));

        String ip = ipAddress(svc);
        String[][] pages = {
            {"Welcome to your Kiosk Clock",
             "Swipe down from the top edge: quick settings (brightness, volume, dock light, Wi-Fi, screen off, Clock settings).\n\n"
                + "Swipe up from the bottom edge: Back and Home."},
            {"Buttons and taps",
             "Tap the top firmly while an alarm or timer rings: snooze. Double tap: stop.\n\n"
                + "Hold Vol +: voice assistant. Hold Vol −: dock light.\n\n"
                + "Change all of these in quick settings → Clock."},
            {"Finish setup from your phone",
             (ip == null
                ? "Not on Wi-Fi yet: tap Wi-Fi below first.\n\n"
                : "On your phone or computer, open\nhttp://" + ip + ":2324\nto connect Kiosk Satellite to Home Assistant.\n\n")
                + "In Home Assistant, the clock shows up under Settings → Devices → Discovered (Kiosk Clock integration). "
                + "When it asks, tap Allow here."},
        };
        TextView title = text(pages[page][0], 22, Color.WHITE);
        title.setPadding(0, 0, 0, 14);
        box.addView(title);
        box.addView(text(pages[page][1], 16, 0xFFE8EAED));

        LinearLayout row = new LinearLayout(svc);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        row.setPadding(0, 24, 0, 0);
        TextView dots = text((page + 1) + " / " + pages.length, 14, 0xFF9AA0A6);
        row.addView(dots, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        boolean last = page == pages.length - 1;
        if (last && ip == null) {
            row.addView(button("Wi-Fi", false, v -> {
                hide();
                svc.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }));
        } else if (!last) {
            row.addView(button("Skip", false, v -> finish()));
        }
        row.addView(button(last ? "Done" : "Next", true, v -> {
            if (last) finish(); else { page++; render(); }
        }));
        box.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(700, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.CENTER;
        card = box;
        wm.addView(box, p);
    }

    static String ipAddress(android.content.Context ctx) {
        try {
            android.net.ConnectivityManager cm = ctx.getSystemService(android.net.ConnectivityManager.class);
            android.net.LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
            if (lp == null) return null;
            for (android.net.LinkAddress a : lp.getLinkAddresses()) {
                if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(svc);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView button(String label, boolean primary, View.OnClickListener click) {
        TextView b = text(label, 17, primary ? 0xFF202124 : Color.WHITE);
        b.setPadding(34, 18, 34, 18);
        b.setBackground(round(primary ? 0xFF8AB4F8 : 0xFF3C4043, 60));
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(14, 0, 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private static GradientDrawable round(int color, float r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(r);
        return d;
    }
}
