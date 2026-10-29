package ai.pitin.vehicle;

import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Dedicated preset service; never shares health credentials or weakens platform TLS. */
final class PresetSyncClient {
    // Shared by the shared-presets screen and the process-scoped appearance link (two worker threads).
    private volatile String baseOrigin = "https://10.0.2.2:9447";

    void setOriginFromRelay(String relayOrigin) {
        if (relayOrigin == null || relayOrigin.trim().isEmpty()) return;
        try {
            URI uri = URI.create(relayOrigin.trim().replaceAll("/+$", ""));
            String host = uri.getHost();
            if ("10.0.2.2".equals(host) || "127.0.0.1".equals(host) || "localhost".equals(host)) {
                baseOrigin = "https://" + host + ":9447";
            }
        } catch (Exception ignored) { }
    }

    static Object plain(Object value, int depth) throws Exception {
        if (depth > 24) throw new IllegalArgumentException("Preset response nesting limit");
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            java.util.Map<String, Object> result = new java.util.HashMap<>();
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) { String key = keys.next(); result.put(key, plain(object.get(key), depth + 1)); }
            return result;
        }
        if (value instanceof org.json.JSONArray) {
            org.json.JSONArray array = (org.json.JSONArray) value;
            java.util.List<Object> result = new java.util.ArrayList<>();
            for (int i = 0; i < array.length(); i++) result.add(plain(array.get(i), depth + 1));
            return result;
        }
        return value;
    }

    static final class Reply {
        final int status; final JSONObject body;
        Reply(int status, JSONObject body) { this.status = status; this.body = body; }
    }

    Reply request(String method, String path, String token, JSONObject body) throws Exception {
        if (!path.matches("/(state|commands|pairings/claim|pairings/status|session|appearance)")) throw new IllegalArgumentException();
        HttpsURLConnection c = (HttpsURLConnection) new URL(baseOrigin + "/api/preset-sync/v1" + path).openConnection();
        try {
            c.setSSLSocketFactory(new ModernTlsSocketFactory(c.getSSLSocketFactory()));
            c.setInstanceFollowRedirects(false); c.setRequestMethod(method);
            c.setConnectTimeout(5000); c.setReadTimeout(5000); c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("Cache-Control", "no-store");
            if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                if (data.length > 16384) throw new IllegalArgumentException();
                c.setRequestProperty("Content-Type", "application/json"); c.setDoOutput(true); c.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = c.getOutputStream()) { out.write(data); }
            }
            int status = c.getResponseCode();
            if (status >= 300 && status < 400) throw new java.io.IOException("Redirect rejected");
            try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in != null) {
                    byte[] chunk = new byte[4096]; int count;
                    while ((count = in.read(chunk)) != -1) { if (out.size() + count > 262144) throw new java.io.IOException(); out.write(chunk, 0, count); }
                }
                String raw = out.toString(StandardCharsets.UTF_8.name());
                if (status == 401 || status == 403) {
                    JSONObject authError;
                    try { authError = raw.isEmpty() ? new JSONObject().put("error", "unauthorized") : new JSONObject(raw); }
                    catch (Exception ignored) { authError = new JSONObject().put("error", "unauthorized"); }
                    return new Reply(status, authError);
                }
                if (in == null) throw new java.io.IOException();
                JSONObject decoded = new JSONObject(raw);
                String expectedRequestId = body == null ? null : body.optString("requestId", null);
                if ("/appearance".equals(path)) AppearanceResponsePolicy.validate(status, plain(decoded, 0), expectedRequestId);
                else PresetSyncResponsePolicy.validate(path, status, plain(decoded, 0), expectedRequestId);
                return new Reply(status, decoded);
            }
        } finally { c.disconnect(); }
    }
}
