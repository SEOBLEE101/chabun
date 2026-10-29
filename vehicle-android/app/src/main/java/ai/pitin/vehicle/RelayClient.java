package ai.pitin.vehicle;

import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Own relay transport. Tokens stay in memory, in headers, and never in logs or URLs. */
final class RelayClient {
    static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status) { super("Relay request failed (HTTP " + status + ")."); this.status = status; }
    }
    private final String base;

    RelayClient(String value, boolean debug) throws Exception {
        URI uri = new URI(value.trim());
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean localDebug = debug && "http".equals(scheme)
                && ("10.0.2.2".equals(host) || "127.0.0.1".equals(host) || "localhost".equals(host));
        if (host == null || (!"https".equals(scheme) && !localDebug)
                || uri.getRawUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath())))
            throw new IllegalArgumentException("Use your relay's HTTPS origin. Debug HTTP permits emulator/loopback only.");
        base = value.trim().replaceAll("/+$", "");
    }

    static String pairingPath(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("Invalid pairing identifier");
        return "/api/pairings/" + id;
    }

    JSONObject request(String method, String path, String token, JSONObject body) throws Exception {
        if (!path.startsWith("/api/") || path.contains("..") || path.contains("?"))
            throw new IllegalArgumentException("Invalid relay path");
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            if (connection instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) connection;
                https.setSSLSocketFactory(new ModernTlsSocketFactory(https.getSSLSocketFactory()));
            }
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-store");
            if (token != null && !token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new HttpFailure(status);
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[4_096];
                int count;
                while ((count = in.read(chunk)) != -1) {
                    if (out.size() + count > 32_768) throw new IOException("Relay response is too large.");
                    out.write(chunk, 0, count);
                }
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        } finally { connection.disconnect(); }
    }
}
