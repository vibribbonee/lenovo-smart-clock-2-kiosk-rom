package org.kioskclock.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.util.function.Consumer;

/**
 * Real position of the mic switch, from Lenovo's stock OEM service (com.google.assistant.oemapp,
 * running as system). The switch's state lives in /sys/.../gpio-keys/micMuteState, which only system
 * apps may read; the com.tonly.MIC_MUTE_STATE_CHANGE broadcast carries no state. The OEM service reads
 * the file and reports it to registered listeners: once right after registering, then on every flip.
 *
 * Interface (from the stock APK): com.google.assistant.IAssistantOemService
 *   6 registerMicMuteStateListener(IMicMuteStateListener)   7 unregister…
 * com.google.assistant.IMicMuteStateListener
 *   1 onMicMuteStateChanged(int state)   STATE_MIC_MUTED = 0, STATE_MIC_UNMUTED = 1
 */
final class OemBridge implements ServiceConnection {
    private static final String OEM_PKG = "com.google.assistant.oemapp";
    private static final String OEM_SERVICE = "com.google.assistant.oemapp.ScoriaAssistantOemService";
    private static final String OEM_DESCRIPTOR = "com.google.assistant.IAssistantOemService";
    private static final int TX_REGISTER_MIC = 6;

    private final Context ctx;
    private final MicListener listener;
    private boolean bound;

    OemBridge(Context ctx, Consumer<Boolean> onMuted) {
        this.ctx = ctx;
        this.listener = new MicListener(onMuted);
    }

    void start() {
        Intent i = new Intent("com.google.assistant.START_OEM_SERVICE").setComponent(new ComponentName(OEM_PKG, OEM_SERVICE));
        try { bound = ctx.bindService(i, this, Context.BIND_AUTO_CREATE); }
        catch (RuntimeException e) { Log.w(App.TAG, "oem service bind: " + e); }
        if (!bound) Log.w(App.TAG, "oem service not available; mic switch state unknown");
    }

    void stop() { if (bound) { ctx.unbindService(this); bound = false; } }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(OEM_DESCRIPTOR);
            d.writeStrongBinder(listener);
            service.transact(TX_REGISTER_MIC, d, r, 0);
            r.readException();
            Log.i(App.TAG, "mic switch listener registered with oem service");
        } catch (RemoteException | RuntimeException e) {
            Log.w(App.TAG, "mic switch listener registration failed: " + e);
        } finally { d.recycle(); r.recycle(); }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Log.w(App.TAG, "oem service disconnected; will re-register when it restarts");
    }

    /** Binder side of IMicMuteStateListener. Static nested on purpose (d8 34 / javac 21, see build_apps.sh). */
    static final class MicListener extends Binder {
        private static final String DESCRIPTOR = "com.google.assistant.IMicMuteStateListener";
        private final Consumer<Boolean> onMuted;

        MicListener(Consumer<Boolean> onMuted) { this.onMuted = onMuted; attachInterface(null, DESCRIPTOR); }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 1) {
                data.enforceInterface(DESCRIPTOR);
                int state = data.readInt();
                onMuted.accept(state == 0);
                if (reply != null) reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }
}
