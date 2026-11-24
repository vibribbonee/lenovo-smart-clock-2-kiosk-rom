package org.kioskclock.nav;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * "Home Assistant wants to connect" prompt for pairing (org.kioskclock.nav.PAIR from ClockBridge,
 * extras id, code, name; signature-permission protected). The answer goes back as a CONTROL
 * broadcast cmd=pair_answer. Closes by itself when the request expires.
 */
final class PairPrompt extends BroadcastReceiver {
    static final String ACTION = "org.kioskclock.nav.PAIR";
    private static final long TIMEOUT_MS = 120_000;

    private final NavService svc;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::hide;
    private View card;

    PairPrompt(NavService svc) {
        this.svc = svc;
        this.wm = svc.getSystemService(WindowManager.class);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        String id = i.getStringExtra("id"), code = i.getStringExtra("code"), name = i.getStringExtra("name");
        if (id == null || code == null) return;
        show(id, code, name == null ? "Home Assistant" : name);
    }

    private void show(String id, String code, String name) {
        hide();
        svc.wake();
        LinearLayout box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(40, 32, 40, 28);
        box.setBackground(round(0xF5202124, 36));

        box.addView(text(name + " wants to connect", 20, Color.WHITE));
        TextView sub = text("Check that it shows the same code", 15, 0xFFBDC1C6);
        sub.setPadding(0, 6, 0, 10);
        box.addView(sub);
        TextView big = text(code.charAt(0) + " " + code.charAt(1) + " " + code.charAt(2) + " " + code.charAt(3), 44, Color.WHITE);
        big.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        big.setPadding(0, 0, 0, 18);
        box.addView(big);

        LinearLayout row = new LinearLayout(svc);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(button("Deny", false, v -> answer(id, false)), weight());
        row.addView(button("Allow", true, v -> answer(id, true)), weight());
        box.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(620, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.CENTER;
        card = box;
        wm.addView(box, p);
        handler.postDelayed(timeout, TIMEOUT_MS);
    }

    private void answer(String id, boolean allow) {
        svc.sendBroadcast(new Intent(QuickPanel.CONTROL).setPackage("org.kioskclock.bridge")
                .putExtra("cmd", "pair_answer").putExtra("id", id).putExtra("allow", allow),
                "org.kioskclock.permission.CONTROL");
        hide();
    }

    void hide() {
        handler.removeCallbacks(timeout);
        if (card != null) { wm.removeView(card); card = null; }
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(svc);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView button(String label, boolean primary, View.OnClickListener click) {
        TextView b = text(label, 18, primary ? 0xFF202124 : Color.WHITE);
        b.setPadding(20, 22, 20, 22);
        b.setBackground(round(primary ? 0xFF8AB4F8 : 0xFF3C4043, 60));
        b.setOnClickListener(click);
        return b;
    }

    private static LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(10, 0, 10, 0);
        return lp;
    }

    private static GradientDrawable round(int color, float r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(r);
        return d;
    }
}
