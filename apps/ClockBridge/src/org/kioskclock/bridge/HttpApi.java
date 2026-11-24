package org.kioskclock.bridge;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Tiny HTTP/1.0 API for Home Assistant. Every endpoint needs "Authorization: Bearer <token>".
 *   GET  /api/dock              -> {"dock":true,"light":0,"phone_present":false,"phone_charging":false,"charger_state":1}
 *   POST /api/dock/light?level=N  (N = 0..10)
 *   GET  /api/status            -> mic anchor state, dock firmware, WebView and Kiosk Satellite versions
 *   POST /api/update/check      -> check GitHub for a newer Kiosk Satellite and newer own apps now
 *   POST /api/screen/off|on     -> KioskNav black screen (backlight 0) / wake
 *   GET  /api/settings          -> the kioskclock_* settings below
 *   POST /api/settings?key=K&value=V  -> change one (whitelisted keys, validated values; applies live;
 *                                        value=default resets the key)
 *   POST /api/pair?name=N       -> NO AUTH: {"id","code"}; the clock asks its user to Allow (409 if one is pending)
 *   GET  /api/pair?id=ID        -> NO AUTH: {"state": pending|allowed|denied|expired|done|unknown, "token" once}
 *   GET  /api/info              -> {"id":..,"name":..,"model":..,"version":..,"api":..} (device identity)
 *   GET  /api/webhook           -> {"url":..} (null when not set)
 *   POST /api/webhook?url=U     -> set the event webhook (empty url clears it); used by the HA integration
 *   POST /api/gesture?name=G    -> run the action set for gesture G (hold_up, hold_down, tap_single, tap_double)
 *   POST /api/action?name=A     -> run action A directly (voice, play_pause, mute, dock_light, night_light,
 *                                  screen_off, screen_toggle, ha_event, none)
 * One request per connection, small bodies only, 5 s socket timeout.
 */
final class HttpApi extends Thread {
    /** Settings Home Assistant may change via /api/settings, each with the values it accepts. */
    static final java.util.Map<String, java.util.regex.Pattern> SETTINGS = new java.util.LinkedHashMap<>();
    static {
        SETTINGS.put("kioskclock_mic_indicator", java.util.regex.Pattern.compile("dot|dot_quiet|google|pill|edge|banner|hidden"));
        SETTINGS.put("kioskclock_mic_color", java.util.regex.Pattern.compile("#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})"));
        SETTINGS.put("kioskclock_mic_size", java.util.regex.Pattern.compile("[0-9]{1,2}"));
        for (String key : OemKeyReceiver.GESTURES.values()) SETTINGS.put(key, java.util.regex.Pattern.compile(OemKeyReceiver.ACTIONS));
        SETTINGS.put(OemKeyReceiver.ALARM_TAP_SINGLE, java.util.regex.Pattern.compile(OemKeyReceiver.ALARM_ACTIONS));
        SETTINGS.put(OemKeyReceiver.ALARM_TAP_DOUBLE, java.util.regex.Pattern.compile(OemKeyReceiver.ALARM_ACTIONS));
        SETTINGS.put(OemKeyReceiver.SETTING_DOUBLE_TAP, java.util.regex.Pattern.compile("[3-8][0-9]{2}|900"));
        SETTINGS.put(OwnUpdater.SETTING, java.util.regex.Pattern.compile("0|1"));
        SETTINGS.put("kioskclock_mic_anchor", java.util.regex.Pattern.compile("0|1"));
        SETTINGS.put("kioskclock_media_controls", java.util.regex.Pattern.compile("auto|manual|off"));
        SETTINGS.put(WakeLight.MODE, java.util.regex.Pattern.compile("off|dock|ha|both"));
        SETTINGS.put(WakeLight.MINUTES, java.util.regex.Pattern.compile("[5-9]|[1-5][0-9]|60"));
        SETTINGS.put(WakeLight.MAX, java.util.regex.Pattern.compile("[1-9]|10"));
    }

    private final BridgeService svc;
    private final int port;
    private ServerSocket server;
    private volatile boolean running = true;

    HttpApi(BridgeService svc, int port) {
        super("http-api");
        this.svc = svc;
        this.port = port;
        setDaemon(true);
    }

    void shutdown() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
    }

    @Override
    public void run() {
        while (running) {
            try (ServerSocket s = new ServerSocket(port)) {
                server = s;
                Log.i(App.TAG, "HTTP API on port " + port);
                while (running) {
                    Socket c = s.accept();
                    c.setSoTimeout(5000);
                    try { handle(c); } catch (Exception e) { Log.w(App.TAG, "request failed: " + e); }
                    finally { try { c.close(); } catch (IOException ignored) { } }
                }
            } catch (IOException e) {
                if (!running) return;
                Log.w(App.TAG, "HTTP server error, retrying: " + e);
                try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private void handle(Socket c) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        String line = in.readLine();
        if (line == null) return;
        String[] parts = line.split(" ");
        if (parts.length < 2) { reply(c, 400, "{\"error\":\"bad request\"}"); return; }
        String method = parts[0], target = parts[1];
        String auth = null;
        for (String h; (h = in.readLine()) != null && !h.isEmpty(); ) {
            int i = h.indexOf(':');
            if (i > 0 && h.substring(0, i).trim().equalsIgnoreCase("Authorization")) auth = h.substring(i + 1).trim();
        }
        String path = target, query = "";
        int q = target.indexOf('?');
        if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
        Map<String, String> params = parse(query);

        // Pairing is the one thing that works without the token: the person at the clock approves it.
        if (path.equals("/api/pair")) {
            if (method.equals("POST")) {
                String asker = params.get("name");
                String[] r = svc.pairing.start(asker == null || asker.isEmpty() ? "Home Assistant" : asker);
                if (r == null) { reply(c, 409, "{\"error\":\"another pairing is waiting on the clock, or one was just denied\"}"); return; }
                reply(c, 200, "{\"id\":\"" + r[0] + "\",\"code\":\"" + r[1] + "\",\"expires_in\":" + Pairing.TTL_MS / 1000 + "}");
            } else {
                String[] r = svc.pairing.poll(params.get("id"));
                reply(c, 200, "{\"state\":\"" + r[0] + "\"" + (r[1] == null ? "" : ",\"token\":\"" + r[1] + "\"") + "}");
            }
            return;
        }
        if (!authorized(auth)) { reply(c, 401, "{\"error\":\"unauthorized\"}"); return; }

        if (method.equals("GET") && path.equals("/api/dock")) {
            reply(c, 200, dockJson(svc.lastStatus));
        } else if (method.equals("POST") && path.equals("/api/dock/light")) {
            int level;
            try { level = Integer.parseInt(params.get("level")); } catch (Exception e) { reply(c, 400, "{\"error\":\"level must be 0..10\"}"); return; }
            if (level < 0 || level > 10) { reply(c, 400, "{\"error\":\"level must be 0..10\"}"); return; }
            boolean ok = svc.dock.setLight(level);
            ChargeBase.Status s = svc.dock.status();
            svc.lastStatus = s;
            reply(c, ok ? 200 : 503, dockJson(s));
        } else if (method.equals("GET") && path.equals("/api/status")) {
            android.content.pm.PackageInfo wv = android.webkit.WebView.getCurrentWebViewPackage();
            KioskUpdater u = svc.updater;
            reply(c, 200, "{\"mic_anchor\":\"" + esc(svc.micState()) + "\",\"mic_muted\":" + svc.micMuted + ",\"dock_service\":" + svc.dock.available()
                    + ",\"dock_mcu\":\"" + esc(String.valueOf(svc.dock.mcuVersion())) + "\",\"dock_wireless\":\""
                    + esc(String.valueOf(svc.dock.firmwareVersion())) + "\""
                    + ",\"webview\":\"" + (wv == null ? "none" : esc(wv.packageName + " " + wv.versionName)) + "\""
                    + ",\"kiosk_version\":\"" + esc(String.valueOf(u.installedVersion())) + "\""
                    + ",\"webhook_configured\":" + (svc.webhook.url() != null)
                    + ",\"kiosk_auto_update\":" + u.enabled()
                    + ",\"kiosk_update_result\":\"" + esc(u.lastResult) + "\",\"kiosk_update_checked\":" + u.lastCheck
                    + ",\"bridge_version\":\"" + esc(bridgeVersion(svc)) + "\""
                    + ",\"apps_update_result\":\"" + esc(svc.ownUpdater == null ? "" : svc.ownUpdater.lastResult) + "\""
                    + ",\"next_alarm\":" + (svc.wakeLight == null ? 0 : svc.wakeLight.nextAlarm())
                    + ",\"wake_light_active\":" + (svc.wakeLight != null && svc.wakeLight.active()) + "}");
        } else if (method.equals("POST") && (path.equals("/api/screen/on") || path.equals("/api/screen/off"))) {
            svc.sendBroadcast(new android.content.Intent("org.kioskclock.nav.SCREEN").setPackage("org.kioskclock.nav")
                    .putExtra("on", path.endsWith("/on")));
            reply(c, 200, "{\"screen\":\"" + (path.endsWith("/on") ? "on" : "black") + "\"}");
        } else if (path.equals("/api/settings") && method.equals("GET")) {
            StringBuilder sb = new StringBuilder("{");
            for (String k : SETTINGS.keySet()) {
                String v = android.provider.Settings.Global.getString(svc.getContentResolver(), k);
                if (sb.length() > 1) sb.append(',');
                sb.append('"').append(k).append("\":").append(v == null ? "null" : "\"" + esc(v) + "\"");
            }
            reply(c, 200, sb.append('}').toString());
        } else if (path.equals("/api/settings") && method.equals("POST")) {
            String key = params.get("key"), value = params.get("value");
            java.util.regex.Pattern allowed = key == null ? null : SETTINGS.get(key);
            if (allowed == null) { reply(c, 400, "{\"error\":\"unknown key\",\"keys\":\"" + SETTINGS.keySet() + "\"}"); return; }
            if ("default".equals(value)) {   // back to the clock's default
                android.provider.Settings.Global.putString(svc.getContentResolver(), key, null);
                reply(c, 200, "{\"" + key + "\":null}");
                return;
            }
            if (value == null || !allowed.matcher(value).matches()) { reply(c, 400, "{\"error\":\"invalid value\",\"allowed\":\"" + esc(allowed.pattern()) + "\"}"); return; }
            android.provider.Settings.Global.putString(svc.getContentResolver(), key, value);
            reply(c, 200, "{\"" + key + "\":\"" + esc(value) + "\"}");
        } else if (method.equals("GET") && path.equals("/api/info")) {
            reply(c, 200, "{\"id\":\"" + esc(deviceId(svc)) + "\",\"name\":\"" + esc(deviceName(svc))
                    + "\",\"model\":\"" + esc(android.os.Build.MODEL) + "\",\"version\":\"" + esc(bridgeVersion(svc))
                    + "\",\"api\":" + Discovery.API_REVISION + "}");
        } else if (method.equals("GET") && path.equals("/api/webhook")) {
            String u = svc.webhook.url();
            reply(c, 200, "{\"url\":" + (u == null ? "null" : "\"" + esc(u) + "\"") + "}");
        } else if (method.equals("POST") && path.equals("/api/webhook")) {
            String u = params.get("url");
            if (u != null && !u.isEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) {
                reply(c, 400, "{\"error\":\"url must start with http:// or https://\"}"); return;
            }
            svc.webhook.setUrl(u);
            Log.i(App.TAG, u == null || u.isEmpty() ? "webhook cleared over HTTP" : "webhook set over HTTP");
            reply(c, 200, "{\"url\":" + (u == null || u.isEmpty() ? "null" : "\"" + esc(u) + "\"") + "}");
        } else if (method.equals("POST") && (path.equals("/api/gesture") || path.equals("/api/action"))) {
            OemKeyReceiver keys = svc.oemKeys;
            String name = params.get("name");
            boolean gesture = path.equals("/api/gesture");
            if (keys == null) { reply(c, 503, "{\"error\":\"not ready\"}"); return; }
            boolean ok = gesture ? keys.runGesture(name) : keys.runAction(name);
            if (!ok) {
                reply(c, 400, "{\"error\":\"unknown " + (gesture ? "gesture" : "action") + "\",\"allowed\":\""
                        + (gesture ? String.join("|", OemKeyReceiver.GESTURES.keySet()) : OemKeyReceiver.ACTIONS) + "\"}");
                return;
            }
            reply(c, 200, "{\"" + (gesture ? "gesture" : "action") + "\":\"" + esc(name) + "\""
                    + (gesture ? ",\"action\":\"" + esc(String.valueOf(keys.actionFor(name))) + "\"" : "") + "}");
        } else if (method.equals("POST") && path.equals("/api/update/check")) {
            svc.checkUpdateAsync();
            reply(c, 200, "{\"started\":true}");
        } else {
            reply(c, 404, "{\"error\":\"not found\"}");
        }
    }

    private boolean authorized(String header) {
        if (header == null || !header.startsWith("Bearer ")) return false;
        byte[] given = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        byte[] expected = svc.token().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, expected);
    }

    /** Stable per-device id (ANDROID_ID), used by Home Assistant as the device's unique id. */
    static String deviceId(android.content.Context ctx) {
        String id = android.provider.Settings.Secure.getString(ctx.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        return id == null ? "unknown" : id;
    }

    /** Human name for discovery: "Smart Clock 2 ABCD" (last four id characters, to tell clocks apart). */
    static String deviceName(android.content.Context ctx) {
        String id = deviceId(ctx);
        return "Smart Clock 2 " + id.substring(Math.max(0, id.length() - 4)).toUpperCase(java.util.Locale.ROOT);
    }

    static String bridgeVersion(android.content.Context ctx) {
        try { return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; }
        catch (Exception e) { return "unknown"; }
    }

    static String dockJson(ChargeBase.Status s) {
        return "{\"dock\":" + s.valid + ",\"light\":" + s.light + ",\"phone_present\":" + s.phonePresent()
                + ",\"phone_charging\":" + s.phoneCharging() + ",\"charger_state\":" + s.charger
                + ",\"raw\":\"" + s.raw + "\"}";
    }

    private static Map<String, String> parse(String q) {
        Map<String, String> m = new HashMap<>();
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            try { m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8")); }
            catch (Exception ignored) { }
        }
        return m;
    }

    private static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

    private static void reply(Socket c, int code, String body) throws IOException {
        String status = code == 200 ? "OK" : code == 400 ? "Bad Request" : code == 401 ? "Unauthorized"
                : code == 404 ? "Not Found" : code == 409 ? "Conflict" : "Service Unavailable";
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = c.getOutputStream();
        out.write(("HTTP/1.0 " + code + " " + status + "\r\nContent-Type: application/json\r\nContent-Length: "
                + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }
}
