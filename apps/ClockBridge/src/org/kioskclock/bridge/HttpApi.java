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
 *   POST /api/update/check      -> check GitHub for a newer Kiosk Satellite now
 * One request per connection, small bodies only, 5 s socket timeout.
 */
final class HttpApi extends Thread {
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
        if (!authorized(auth)) { reply(c, 401, "{\"error\":\"unauthorized\"}"); return; }

        String path = target, query = "";
        int q = target.indexOf('?');
        if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
        Map<String, String> params = parse(query);

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
            reply(c, 200, "{\"mic_anchor\":\"" + esc(svc.micState()) + "\",\"dock_service\":" + svc.dock.available()
                    + ",\"dock_mcu\":\"" + esc(String.valueOf(svc.dock.mcuVersion())) + "\",\"dock_wireless\":\""
                    + esc(String.valueOf(svc.dock.firmwareVersion())) + "\""
                    + ",\"webview\":\"" + (wv == null ? "none" : esc(wv.packageName + " " + wv.versionName)) + "\""
                    + ",\"kiosk_version\":\"" + esc(String.valueOf(u.installedVersion())) + "\""
                    + ",\"webhook_configured\":" + (svc.webhook.url() != null)
                    + ",\"kiosk_auto_update\":" + u.enabled()
                    + ",\"kiosk_update_result\":\"" + esc(u.lastResult) + "\",\"kiosk_update_checked\":" + u.lastCheck + "}");
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
                : code == 404 ? "Not Found" : "Service Unavailable";
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = c.getOutputStream();
        out.write(("HTTP/1.0 " + code + " " + status + "\r\nContent-Type: application/json\r\nContent-Length: "
                + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }
}
