package ai.pitin.vehicle;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.security.NetworkSecurityPolicy;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.json.JSONObject;

/** Local-only transport acceptance. Uses the production client and platform certificate verification. */
public final class TlsTransportInstrumentation extends Instrumentation {
    private Bundle arguments;
    private final StringBuilder report = new StringBuilder();
    private int checks;

    @Override public void onCreate(Bundle input) {
        super.onCreate(input); arguments = input == null ? new Bundle() : input; start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int status = Activity.RESULT_CANCELED;
        try {
            report.append("Production RelayClient /api/health only. No credentials, pairing or health data.\n");
            String good = origin("goodOrigin");
            JSONObject health = new RelayClient(good, false).request("GET", "/api/health", null, null);
            check("pit-in-relay".equals(health.optString("service")) && "tls".equals(health.optString("transport")),
                    "trusted certificate and matching hostname return relay health JSON over HTTPS");
            for (String host : new String[]{"localhost", "127.0.0.1", "10.0.2.2", "example.com"})
                check(!NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host), "cleartext denied for " + host);
            reject("wrongCertOrigin", false);
            reject("wrongHostnameOrigin", true);
            if (arguments.containsKey("expiredOrigin")) reject("expiredOrigin", false);
            report.append("PASS: ").append(checks).append(" TLS transport assertions.\n");
            status = Activity.RESULT_OK;
        } catch (Throwable failure) {
            report.append("FAIL: ").append(failure.getClass().getSimpleName()).append(": ")
                    .append(failure.getMessage()).append('\n');
        }
        try {
            File file = new File(getTargetContext().getFilesDir(), "tls-transport-report.txt");
            try (FileOutputStream stream = new FileOutputStream(file)) {
                stream.write(report.toString().getBytes(StandardCharsets.UTF_8));
            }
            result.putString("evidencePath", file.getAbsolutePath());
        } catch (Exception failure) {
            report.append("FAIL: could not save transport evidence\n"); status = Activity.RESULT_CANCELED;
        }
        result.putString("stream", report.toString()); result.putInt("assertions", checks);
        finish(status, result);
    }

    private String origin(String argument) throws Exception {
        String value = arguments.getString(argument);
        if (value == null) throw new AssertionError("Missing " + argument);
        URI uri = new URI(value);
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || !("localhost".equals(host) || "127.0.0.1".equals(host)
                || "10.0.2.2".equals(host)) || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath())))
            throw new AssertionError("Test origin must be an exact local HTTPS origin: " + argument);
        return value;
    }

    private void reject(String argument, boolean hostname) throws Exception {
        String value = origin(argument);
        try {
            new RelayClient(value, false).request("GET", "/api/health", null, null);
            throw new AssertionError(argument + " unexpectedly accepted invalid TLS");
        } catch (SSLPeerUnverifiedException failure) {
            check(hostname, argument + " rejected during hostname verification");
        } catch (SSLHandshakeException failure) {
            if (hostname) throw new AssertionError("Wrong-host test did not reach hostname verification; verify its trusted certificate chain", failure);
            Throwable cause = failure;
            boolean certificateFailure = false;
            boolean expired = false;
            while (cause != null) {
                if (cause instanceof java.security.cert.CertificateException
                        || cause instanceof java.security.cert.CertPathValidatorException) certificateFailure = true;
                if (cause instanceof java.security.cert.CertificateExpiredException) expired = true;
                cause = cause.getCause();
            }
            if ("expiredOrigin".equals(argument)) check(expired, "expired certificate is specifically rejected as expired");
            check(certificateFailure, argument + " rejected by certificate validation (not a connection failure)");
        }
    }

    private void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++; report.append("PASS ").append(description).append('\n');
    }
}
