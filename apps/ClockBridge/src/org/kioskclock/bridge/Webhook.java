package org.kioskclock.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pushes events to a Home Assistant webhook, e.g. http://homeassistant.local:8123/api/webhook/<id>.
 * Body: {"type":"tap","value":"single","device":"smart-clock-2","ts":<ms>}
 * Not configured = disabled. Set with the CommandReceiver "set_webhook" command.
 */
final class Webhook {
    private final Context ctx;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    Webhook(Context ctx) { this.ctx = ctx; }

    private SharedPreferences prefs() {
        return ctx.createDeviceProtectedStorageContext().getSharedPreferences("webhook", Context.MODE_PRIVATE);
    }

    String url() { return prefs().getString("url", null); }

    void setUrl(String url) {
        if (url == null || url.isEmpty()) prefs().edit().remove("url").apply();
        else prefs().edit().putString("url", url).apply();
    }

    void send(String type, String value) {
        final String url = url();
        if (url == null) return;
        final String body = "{\"type\":\"" + type + "\",\"value\":\"" + value
                + "\",\"device\":\"smart-clock-2\",\"ts\":" + System.currentTimeMillis() + "}";
        exec.execute(() -> post(url, body));
    }

    private static void post(String url, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) { out.write(b); }
            int code = c.getResponseCode();
            if (code >= 300) Log.w(App.TAG, "webhook HTTP " + code);
        } catch (Exception e) {
            Log.w(App.TAG, "webhook failed: " + e);
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
