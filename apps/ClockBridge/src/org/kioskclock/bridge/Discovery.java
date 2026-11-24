package org.kioskclock.bridge;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

/**
 * Advertises the HTTP API on the local network as a "_kioskclock._tcp" mDNS service, so the Home
 * Assistant integration (ha/custom_components/kioskclock) finds the clock by itself.
 * TXT records: id (stable per device), model, version (ClockBridge), api (API revision).
 * The Android mDNS daemon keeps the record alive across Wi-Fi reconnects.
 */
final class Discovery implements NsdManager.RegistrationListener {
    static final String SERVICE_TYPE = "_kioskclock._tcp";
    /**
     * Bumped when the HTTP API gains endpoints the integration may use
     * (2 = /api/info, /api/webhook; 3 = /api/gesture, /api/action, tap settings; 4 = /api/pair).
     */
    static final String API_REVISION = "4";

    private final NsdManager nsd;
    private final NsdServiceInfo info = new NsdServiceInfo();
    private boolean registered;

    Discovery(Context ctx, int port) {
        nsd = ctx.getSystemService(NsdManager.class);
        info.setServiceName(HttpApi.deviceName(ctx));
        info.setServiceType(SERVICE_TYPE);
        info.setPort(port);
        info.setAttribute("id", HttpApi.deviceId(ctx));
        info.setAttribute("model", android.os.Build.MODEL);
        info.setAttribute("version", HttpApi.bridgeVersion(ctx));
        info.setAttribute("api", API_REVISION);
    }

    void start() {
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, this);
            registered = true;
        } catch (RuntimeException e) {
            Log.w(App.TAG, "mDNS advertising failed: " + e);
        }
    }

    void stop() {
        if (!registered) return;
        registered = false;
        try { nsd.unregisterService(this); } catch (RuntimeException ignored) { }
    }

    @Override
    public void onServiceRegistered(NsdServiceInfo s) {
        Log.i(App.TAG, "advertising " + s.getServiceName() + "." + SERVICE_TYPE + " via mDNS");
    }

    @Override
    public void onRegistrationFailed(NsdServiceInfo s, int error) {
        registered = false;
        Log.w(App.TAG, "mDNS registration failed: " + error);
    }

    @Override
    public void onServiceUnregistered(NsdServiceInfo s) { }

    @Override
    public void onUnregistrationFailed(NsdServiceInfo s, int error) { }
}
