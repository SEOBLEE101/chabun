package ai.pitin.vehicle;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Separate appearance envelope. This cannot authorize a preset or a physical vehicle action. */
final class AppearanceResponsePolicy {
    private AppearanceResponsePolicy() { }
    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Invalid appearance response");
    }
    private static Map<?, ?> object(Object value) { require(value instanceof Map); return (Map<?, ?>) value; }
    private static void keys(Map<?, ?> value, String... expected) {
        require(value.keySet().equals(new HashSet<>(Arrays.asList(expected))));
    }
    private static String string(Object value) {
        require(value instanceof String);
        String text = (String) value; require(!text.trim().isEmpty() && text.length() <= 80); return text;
    }
    private static long integer(Object value, long minimum) {
        require(value instanceof Number);
        double number = ((Number) value).doubleValue();
        require(Double.isFinite(number) && number >= minimum && number <= 9007199254740991.0
                && number == Math.floor(number)); return (long) number;
    }
    private static Instant time(Object value) { return Instant.parse(string(value)); }
    static void validate(int status, Object decoded, String expectedRequestId) {
        Map<?, ?> body = object(decoded);
        require(status < 300 || status >= 400);
        if (status < 200 || status >= 300) {
            // The service's shared 409 wrapper may include preset state/sync. Ignore all such
            // payloads here: only the rejection status is consumed, never an observation.
            return;
        }
        if (expectedRequestId == null) keys(body, "appearance", "sync");
        else {
            keys(body, "appearance", "sync", "acknowledgedRequestId");
            require(expectedRequestId.equals(body.get("acknowledgedRequestId")));
        }
        envelope(body);
    }
    private static void envelope(Map<?, ?> body) {
        Map<?, ?> sync = object(body.get("sync"));
        keys(sync, "epoch", "deviceId", "role", "expiresAt");
        string(sync.get("epoch")); string(sync.get("deviceId"));
        require("vehicle".equals(sync.get("role"))); time(sync.get("expiresAt"));
        Map<?, ?> appearance = object(body.get("appearance"));
        keys(appearance, "schemaVersion", "serverTime", "vehicles");
        require(integer(appearance.get("schemaVersion"), 1) == 1); time(appearance.get("serverTime"));
        require(appearance.get("vehicles") instanceof List);
        Set<String> ids = new HashSet<>();
        for (Object item : (List<?>) appearance.get("vehicles")) {
            Map<?, ?> report = object(item);
            keys(report, "deviceId", "themeId", "paletteVersion", "revision", "reportedAt", "expiresAt");
            require(ids.add(string(report.get("deviceId"))));
            // graphite | paper (Pearl) | umber only; exact case-sensitive wire ids.
            require(AppearancePublisher.supportedTheme(report.get("themeId")));
            require(integer(report.get("paletteVersion"), 1) == AppearancePublisher.PALETTE_VERSION);
            integer(report.get("revision"), 1);
            Instant reported = time(report.get("reportedAt")), expires = time(report.get("expiresAt"));
            require(!expires.isBefore(reported) && !expires.isAfter(reported.plusSeconds(120)));
        }
    }
}
