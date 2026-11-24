package org.kioskclock.nav;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;

/**
 * Follows the media session that matters (playing first, else the most recent) of any app:
 * Bluetooth audio from a phone (AVRCP), Kiosk Satellite's player, SmartTube, ... and tells the
 * listener (MediaUi) what's playing. Reads sessions with MEDIA_CONTENT_CONTROL (privileged): this
 * Android Go build has no notification listeners, the usual route.
 */
final class MediaWatcher {
    interface Listener { void onMedia(MediaWatcher w); }

    static final class Now {
        String title, artist, source;
        Bitmap art;
        boolean playing, canPrev, canNext, canSeek;
        long duration, position, positionAt;
    }

    private static final String TAG = "KioskNav";
    private final Context ctx;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaSessionManager msm;
    private MediaController current;
    private final Callback callback = new Callback(this);
    private final Sessions sessions = new Sessions(this);

    MediaWatcher(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    void start() {
        msm = ctx.getSystemService(MediaSessionManager.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessions, null, handler);
            pick(msm.getActiveSessions(null));
        } catch (SecurityException e) {
            // Not privileged (e.g. an adb install over a ROM without it in priv-app): no media controls.
            Log.w(TAG, "media controls unavailable: " + e.getMessage());
        }
    }

    void stop() {
        if (msm != null) { try { msm.removeOnActiveSessionsChangedListener(sessions); } catch (RuntimeException ignored) { } }
        if (current != null) current.unregisterCallback(callback);
        current = null;
    }

    void pick(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
                if (best == null) best = c;     // the list is most recent first
            }
        }
        if (best != null && current != null && best.getSessionToken().equals(current.getSessionToken())) {
            listener.onMedia(this);
            return;
        }
        if (current != null) current.unregisterCallback(callback);
        current = best;
        if (current != null) current.registerCallback(callback, handler);
        listener.onMedia(this);
    }

    boolean has() { return current != null && now() != null; }

    /** What's playing, or null when nothing sensible is (no title). */
    Now now() {
        MediaController c = current;
        if (c == null) return null;
        MediaMetadata m = c.getMetadata();
        PlaybackState s = c.getPlaybackState();
        Now n = new Now();
        if (m != null) {
            n.title = first(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
            n.artist = first(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                    MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            n.art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (n.art == null) n.art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (n.art == null) n.art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            n.duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        }
        if (n.title == null || n.title.isEmpty()) return null;
        if (s != null) {
            n.playing = s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING;
            long a = s.getActions();
            n.canPrev = (a & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0;
            n.canNext = (a & PlaybackState.ACTION_SKIP_TO_NEXT) != 0;
            n.canSeek = (a & PlaybackState.ACTION_SEEK_TO) != 0 && n.duration > 0;
            n.position = s.getPosition();
            n.positionAt = s.getLastPositionUpdateTime();
        }
        n.source = sourceName(c.getPackageName());
        return n;
    }

    /** Position now, extrapolated while playing. */
    static long position(Now n) {
        if (!n.playing || n.positionAt <= 0) return n.position;
        return Math.min(n.duration > 0 ? n.duration : Long.MAX_VALUE,
                n.position + (android.os.SystemClock.elapsedRealtime() - n.positionAt));
    }

    String key() {
        Now n = now();
        return n == null ? null : n.title + "\u0000" + n.artist;
    }

    void playPause() {
        Now n = now();
        if (current == null || n == null) return;
        if (n.playing) current.getTransportControls().pause(); else current.getTransportControls().play();
    }

    void next() { if (current != null) current.getTransportControls().skipToNext(); }

    void previous() { if (current != null) current.getTransportControls().skipToPrevious(); }

    void seekTo(long ms) { if (current != null) current.getTransportControls().seekTo(ms); }

    private String sourceName(String pkg) {
        if ("com.android.bluetooth".equals(pkg)) return "Bluetooth";
        if ("me.jxl.kiosk_satellite".equals(pkg)) return "Kiosk Satellite";
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static String first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return null;
    }

    private static final class Callback extends MediaController.Callback {
        private final MediaWatcher w;
        Callback(MediaWatcher w) { this.w = w; }
        @Override public void onPlaybackStateChanged(PlaybackState state) { w.listener.onMedia(w); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { w.listener.onMedia(w); }
        @Override public void onSessionDestroyed() { w.pick(w.msm == null ? null : w.msm.getActiveSessions(null)); }
    }

    private static final class Sessions implements MediaSessionManager.OnActiveSessionsChangedListener {
        private final MediaWatcher w;
        Sessions(MediaWatcher w) { this.w = w; }
        @Override public void onActiveSessionsChanged(List<MediaController> controllers) { w.pick(controllers); }
    }
}
