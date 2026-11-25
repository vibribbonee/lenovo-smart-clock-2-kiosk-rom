package org.kioskclock.nav;

import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Media controls for whatever plays (Bluetooth from a phone, Kiosk Satellite, SmartTube, ...):
 *  - mini player: a small bar at the bottom while something plays; tap = full player, swipe
 *    sideways = hide it until that music stops (kioskclock_media_controls = auto, the default);
 *  - full player: slides in from the right with art, title, artist, source, progress and
 *    previous / play-pause / next; swipe right, tap outside or ✕ closes it;
 *  - the nav bar's third slot shows ♫ while there is something to control (auto and manual).
 * kioskclock_media_controls: auto | manual (only via ♫) | off.
 * Gets out of the way while an alarm or timer rings or the voice assistant is active (Kiosk
 * Satellite's own broadcasts: the mini player would cover the alarm's Snooze / Stop buttons), and
 * an opened full player falls back to the clock after a while without touches (like Nest Hub).
 */
final class MediaUi extends android.content.BroadcastReceiver implements MediaWatcher.Listener {
    static final String SETTING = "kioskclock_media_controls";
    private static final long PAUSED_KEEP_MS = 30_000, FULL_IDLE_MS = 30_000;
    private static final String KS = "me.jxl.kiosk_satellite.event.";
    private static final String[] BUSY = {"ALARM_RINGING", "ALARM_SUNRISE", "VS_TIMER_FINISHED", "VS_STARTED", "INTERCOM_RINGING", "INTERCOM_IN_CALL"};
    private static final String[] FREE = {"ALARM_SNOOZED", "ALARM_STOPPED", "ALARM_SILENCED", "VS_TIMER_DISMISSED",
            "VS_TIMER_CANCELLED", "VS_ENDED", "INTERCOM_ENDED", "INTERCOM_MISSED"};
    private boolean busy;
    private final Runnable idleClose = this::closeFull;
    private final Runnable busyTimeout = () -> { busy = false; refreshMini(); };
    private SeekBar volume;

    private final NavService svc;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MediaWatcher watcher;
    private final Runnable tick = this::tick;
    private final Runnable pausedTimeout = this::refreshMini;

    private View mini;
    private TextView miniTitle, miniArtist, miniPlay;
    private ImageView miniArt;
    private View full;
    private TextView fullTitle, fullArtist, fullSource, fullPlay, fullPrev, fullNext, fullPos, fullDur, fullNoArt;
    private ImageView fullArt;
    private SeekBar seek;
    private boolean seeking;
    /** App whose music the mini player was swiped away for; cleared once that music stops. */
    private String dismissedPkg;
    private Snackbar hint;
    private long pausedSince;

    MediaUi(NavService svc) {
        this.svc = svc;
        this.wm = svc.getSystemService(WindowManager.class);
        this.watcher = new MediaWatcher(svc, this);
    }

    void start() {
        android.content.IntentFilter f = new android.content.IntentFilter();
        for (String a : BUSY) f.addAction(KS + a);
        for (String a : FREE) f.addAction(KS + a);
        svc.registerReceiver(this, f);
        watcher.start();
    }

    void stop() {
        try { svc.unregisterReceiver(this); } catch (IllegalArgumentException ignored) { }
        watcher.stop(); hideMini(); closeFull(); handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onReceive(android.content.Context c, android.content.Intent i) {
        String a = i.getAction() == null ? "" : i.getAction().substring(KS.length());
        busy = java.util.Arrays.asList(BUSY).contains(a);
        handler.removeCallbacks(busyTimeout);
        if (busy) {
            handler.postDelayed(busyTimeout, 15 * 60_000L);   // never stay hidden if an "ended" event is missed
            closeFull();
        }
        refreshMini();
    }

    private String mode() {
        String m = Settings.Global.getString(svc.getContentResolver(), SETTING);
        return m == null ? "auto" : m;
    }

    /** For the nav bar: show the ♫ button? */
    boolean available() { return !"off".equals(mode()) && watcher.has(); }

    /** Re-evaluate the mini player (screen went black/woke, setting changed). */
    void refresh() { refreshMini(); }

    @Override
    public void onMedia(MediaWatcher w) {
        if (w == null) { hideMini(); return; }
        MediaWatcher.Now n = w.now();
        if (n != null && !n.playing && pausedSince == 0) pausedSince = android.os.SystemClock.uptimeMillis();
        if (n != null && n.playing) pausedSince = 0;
        refreshMini();
        if (full != null) { if (n == null) closeFull(); else fillFull(n); }
    }

    // ---- mini player ----------------------------------------------------------------------------

    private void refreshMini() {
        MediaWatcher.Now n = watcher.now();
        boolean paused = n != null && !n.playing;
        boolean pausedLong = paused && pausedSince != 0 && android.os.SystemClock.uptimeMillis() - pausedSince > PAUSED_KEEP_MS;
        if (n == null || pausedLong || (dismissedPkg != null && !dismissedPkg.equals(watcher.pkg()))) dismissedPkg = null;
        boolean want = n != null && "auto".equals(mode()) && full == null && !svc.isBlack() && !pausedLong && !busy
                && dismissedPkg == null;
        if (!want) { hideMini(); return; }
        if (mini == null) buildMini();
        miniTitle.setText(n.title);
        miniArtist.setText(n.artist == null ? n.source : n.artist + " · " + n.source);
        miniPlay.setText(n.playing ? "❚❚" : "▶");
        if (n.art != null) { miniArt.setImageBitmap(n.art); miniArt.setVisibility(View.VISIBLE); }
        else miniArt.setVisibility(View.GONE);
        handler.removeCallbacks(pausedTimeout);
        if (paused) handler.postDelayed(pausedTimeout, PAUSED_KEEP_MS + 500);
    }

    private void buildMini() {
        LinearLayout box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(12, 8, 12, 8);
        box.setBackground(round(0xF0202124, 40));
        miniArt = new ImageView(svc);
        miniArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(miniArt, new LinearLayout.LayoutParams(58, 58));
        LinearLayout texts = new LinearLayout(svc);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(16, 0, 8, 0);
        miniTitle = line(17, Color.WHITE, true);
        miniArtist = line(13, 0xFFBDC1C6, false);
        texts.addView(miniTitle);
        texts.addView(miniArtist);
        box.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        miniPlay = control("▶", 26);
        miniPlay.setOnClickListener(v -> watcher.playPause());
        box.addView(miniPlay, new LinearLayout.LayoutParams(76, 64));
        box.setOnTouchListener(new Swipe(this, false));

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(560, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        p.y = 22;   // clear of the 12 px swipe-up strip
        mini = box;
        wm.addView(box, p);
    }

    private void hideMini() {
        handler.removeCallbacks(pausedTimeout);
        if (mini != null) { wm.removeView(mini); mini = null; }
    }

    void dismissMini() {
        dismissedPkg = watcher.pkg();
        hideMini();
        // First time only: say how to turn the pop-up off for good.
        if (!"1".equals(Settings.Global.getString(svc.getContentResolver(), "kioskclock_media_hint_shown"))) {
            if (hint == null) hint = new Snackbar(svc);
            hint.show("Hidden until the music stops · ♫ → Pop-up player: Off to stop it showing");
            try { Settings.Global.putString(svc.getContentResolver(), "kioskclock_media_hint_shown", "1"); }
            catch (SecurityException ignored) { }
        }
    }

    private void setPopup(boolean on) {
        try { Settings.Global.putString(svc.getContentResolver(), SETTING, on ? null : "manual"); }
        catch (SecurityException ignored) { }
    }

    // ---- full player ----------------------------------------------------------------------------

    void openFull() {
        MediaWatcher.Now n = watcher.now();
        if (n == null || busy) return;
        touched();
        hideMini();
        if (full == null) buildFull();
        fillFull(n);
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    /** Any touch in the full player keeps it open a bit longer. */
    void touched() {
        handler.removeCallbacks(idleClose);
        handler.postDelayed(idleClose, FULL_IDLE_MS);
    }

    void closeFull() {
        handler.removeCallbacks(idleClose);
        handler.removeCallbacks(tick);
        if (full != null) { wm.removeView(full); full = null; }
        refreshMini();
    }

    private void buildFull() {
        LinearLayout box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(28, 12, 28, 12);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF5202124);
        bg.setCornerRadii(new float[]{36, 36, 0, 0, 0, 0, 36, 36});
        box.setBackground(bg);

        LinearLayout top = new LinearLayout(svc);
        top.setGravity(Gravity.CENTER_VERTICAL);
        fullSource = line(14, 0xFF9AA0A6, false);
        top.addView(fullSource, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        popupToggle = control("", 13);
        popupToggle.setPadding(16, 6, 16, 6);
        popupToggle.setOnClickListener(v -> {
            setPopup(!"auto".equals(mode()));
            paintPopupToggle();
            touched();
        });
        top.addView(popupToggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 44));
        paintPopupToggle();
        TextView close = control("✕", 22);
        close.setOnClickListener(v -> closeFull());
        top.addView(close, new LinearLayout.LayoutParams(64, 52));
        box.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout artFrame = new FrameLayout(svc);
        artFrame.setBackground(round(0xFF3C4043, 20));
        fullArt = new ImageView(svc);
        fullArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artFrame.addView(fullArt, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fullNoArt = control("♫", 48);
        artFrame.addView(fullNoArt, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Art beside the text: the panel is 480 px tall, so a stacked layout wouldn't fit.
        LinearLayout info = new LinearLayout(svc);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.addView(artFrame, new LinearLayout.LayoutParams(140, 140));
        LinearLayout texts = new LinearLayout(svc);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(20, 0, 0, 0);
        fullTitle = line(20, Color.WHITE, true);
        fullTitle.setSingleLine(false);
        fullTitle.setMaxLines(2);
        fullArtist = line(15, 0xFFBDC1C6, false);
        texts.addView(fullTitle);
        texts.addView(fullArtist);
        info.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.setMargins(0, 4, 0, 4);
        box.addView(info, ip);

        seek = new SeekBar(svc);
        seek.setPadding(16, 14, 16, 4);
        seek.setOnSeekBarChangeListener(new Seek(this));
        box.addView(seek, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout times = new LinearLayout(svc);
        fullPos = line(12, 0xFF9AA0A6, false);
        fullDur = line(12, 0xFF9AA0A6, false);
        fullDur.setGravity(Gravity.END);
        times.addView(fullPos, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(fullDur, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.setPadding(16, 0, 16, 0);
        box.addView(times, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout ctl = new LinearLayout(svc);
        ctl.setGravity(Gravity.CENTER);
        fullPrev = control("◀◀", 26);
        fullPrev.setOnClickListener(v -> watcher.previous());
        fullPlay = control("▶", 34);
        fullPlay.setBackground(round(0xFF8AB4F8, 60));
        fullPlay.setTextColor(0xFF202124);
        fullPlay.setOnClickListener(v -> watcher.playPause());
        fullNext = control("▶▶", 26);
        fullNext.setOnClickListener(v -> watcher.next());
        ctl.addView(fullPrev, new LinearLayout.LayoutParams(100, 84));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(100, 84);
        pp.setMargins(20, 0, 20, 0);
        ctl.addView(fullPlay, pp);
        ctl.addView(fullNext, new LinearLayout.LayoutParams(100, 84));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, 8, 0, 0);
        box.addView(ctl, cp);

        // Speaker volume (media stream), like the volume slider on other smart displays' now-playing screen.
        LinearLayout vol = new LinearLayout(svc);
        vol.setGravity(Gravity.CENTER_VERTICAL);
        vol.setPadding(8, 10, 8, 0);
        vol.addView(control("🔈", 16), new LinearLayout.LayoutParams(44, 44));
        android.media.AudioManager am = svc.getSystemService(android.media.AudioManager.class);
        volume = new SeekBar(svc);
        volume.setMax(am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
        volume.setOnSeekBarChangeListener(new Volume(this, am));
        vol.addView(volume, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(vol, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.setOnTouchListener(new Swipe(this, true));

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(440, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.END | Gravity.TOP;
        full = box;
        wm.addView(box, p);
        box.setTranslationX(440);
        box.animate().translationX(0).setDuration(180).start();
    }

    private TextView popupToggle;

    /** "Pop-up player: On/Off" in the full player: the automatic mini player, without a trip to settings. */
    private void paintPopupToggle() {
        if (popupToggle == null) return;
        boolean on = "auto".equals(mode());
        popupToggle.setText(on ? "Pop-up player: On" : "Pop-up player: Off");
        popupToggle.setTextColor(on ? 0xFF202124 : Color.WHITE);
        popupToggle.setBackground(round(on ? 0xFF8AB4F8 : 0xFF3C4043, 30));
    }

    private void fillFull(MediaWatcher.Now n) {
        fullSource.setText(n.source);
        fullTitle.setText(n.title);
        fullArtist.setText(n.artist == null ? "" : n.artist);
        fullPlay.setText(n.playing ? "❚❚" : "▶");
        fullPrev.setAlpha(n.canPrev ? 1f : 0.35f);
        fullNext.setAlpha(n.canNext ? 1f : 0.35f);
        if (n.art != null) { fullArt.setImageBitmap(n.art); fullNoArt.setVisibility(View.GONE); }
        else { fullArt.setImageDrawable(null); fullNoArt.setVisibility(View.VISIBLE); }
        boolean timed = n.duration > 0;
        seek.setVisibility(timed ? View.VISIBLE : View.INVISIBLE);
        seek.setEnabled(n.canSeek);
        seek.setMax((int) Math.min(Integer.MAX_VALUE, n.duration / 1000));
        if (!seeking) seek.setProgress((int) (MediaWatcher.position(n) / 1000));
        fullPos.setText(timed ? time(MediaWatcher.position(n)) : "");
        if (volume != null && !volume.isPressed())
            volume.setProgress(svc.getSystemService(android.media.AudioManager.class).getStreamVolume(android.media.AudioManager.STREAM_MUSIC));
        fullDur.setText(timed ? time(n.duration) : "");
    }

    private void tick() {
        if (full == null) return;
        MediaWatcher.Now n = watcher.now();
        if (n != null) fillFull(n);
        handler.postDelayed(tick, 1000);
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static String time(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private TextView line(int sp, int color, boolean bold) {
        TextView t = new TextView(svc);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView control(String label, int sp) {
        TextView t = new TextView(svc);
        t.setText(label);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private static GradientDrawable round(int color, float r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(r);
        return d;
    }

    /** Sideways swipe: mini player = hide for this track; full player = swipe right closes. */
    private static final class Swipe implements View.OnTouchListener {
        private final MediaUi ui;
        private final boolean full;
        private float downX;
        private boolean moved;
        Swipe(MediaUi ui, boolean full) { this.ui = ui; this.full = full; }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (full) ui.touched();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_OUTSIDE:
                    if (full) ui.closeFull();
                    return true;
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX;
                    if (full) { if (dx > 0) v.setTranslationX(dx); }
                    else v.setTranslationX(dx);
                    if (Math.abs(dx) > 20) moved = true;
                    return true;
                case MotionEvent.ACTION_UP:
                    float d = e.getRawX() - downX;
                    if (full && d > 120) { ui.closeFull(); return true; }
                    if (!full && Math.abs(d) > 120) { ui.dismissMini(); return true; }
                    v.animate().translationX(0).setDuration(120).start();
                    if (!moved && !full) ui.openFull();
                    return true;
                default:
                    return false;
            }
        }
    }

    private static final class Volume implements SeekBar.OnSeekBarChangeListener {
        private final MediaUi ui;
        private final android.media.AudioManager am;
        Volume(MediaUi ui, android.media.AudioManager am) { this.ui = ui; this.am = am; }
        @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
            if (!fromUser) return;
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, p, 0);
            ui.touched();
        }
        @Override public void onStartTrackingTouch(SeekBar s) { ui.touched(); }
        @Override public void onStopTrackingTouch(SeekBar s) { }
    }

    private static final class Seek implements SeekBar.OnSeekBarChangeListener {
        private final MediaUi ui;
        Seek(MediaUi ui) { this.ui = ui; }
        @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { }
        @Override public void onStartTrackingTouch(SeekBar s) { ui.seeking = true; ui.touched(); }
        @Override public void onStopTrackingTouch(SeekBar s) { ui.seeking = false; ui.watcher.seekTo(s.getProgress() * 1000L); }
    }
}
