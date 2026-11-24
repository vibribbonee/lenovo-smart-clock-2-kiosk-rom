package org.kioskclock.bridge;

import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import java.security.SecureRandom;

/**
 * Pairing without typing the token: Home Assistant asks (POST /api/pair, no auth), the clock shows
 * "Home Assistant wants to connect, code 1234 [Deny] [Allow]" (KioskNav), and once someone at the
 * clock taps Allow the asker gets the token from GET /api/pair?id=... exactly once.
 * One request at a time, 2 minutes to answer; only the request id the asker got can collect.
 */
final class Pairing {
    static final long TTL_MS = 120_000;
    private static final SecureRandom RNG = new SecureRandom();

    private final BridgeService svc;
    private String id, code, state;     // state: pending | allowed | denied | done
    private long created;

    Pairing(BridgeService svc) { this.svc = svc; }

    /** Starts a request; null while another one is still waiting for an answer. Returns {id, code}. */
    synchronized String[] start(String asker) {
        if ("pending".equals(state) && !expired()) return null;
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        id = sb.toString();
        code = String.format(java.util.Locale.ROOT, "%04d", RNG.nextInt(10_000));
        state = "pending";
        created = SystemClock.elapsedRealtime();
        Log.i(App.TAG, "pairing requested by " + asker + ", waiting for Allow on the clock");
        svc.sendBroadcast(new Intent("org.kioskclock.nav.PAIR").setPackage("org.kioskclock.nav")
                .putExtra("id", id).putExtra("code", code).putExtra("name", asker),
                "org.kioskclock.permission.CONTROL");
        return new String[]{id, code};
    }

    /** From KioskNav's prompt. */
    synchronized void answer(String forId, boolean allow) {
        if (forId == null || !forId.equals(id) || !"pending".equals(state) || expired()) return;
        state = allow ? "allowed" : "denied";
        Log.i(App.TAG, "pairing " + (allow ? "allowed" : "denied") + " on the clock");
    }

    /** {state, token-or-null}; the token only once, after Allow. */
    synchronized String[] poll(String forId) {
        if (forId == null || !forId.equals(id)) return new String[]{"unknown", null};
        if ("pending".equals(state) && expired()) state = "expired";
        if ("allowed".equals(state)) {
            state = "done";
            return new String[]{"allowed", BridgeService.token(svc)};
        }
        return new String[]{state, null};
    }

    private boolean expired() { return SystemClock.elapsedRealtime() - created > TTL_MS; }
}
