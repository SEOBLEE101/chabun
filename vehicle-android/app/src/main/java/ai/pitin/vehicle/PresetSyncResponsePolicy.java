package ai.pitin.vehicle;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Decoded JSON validation before callers may commit state or resolve an uncertain command. */
final class PresetSyncResponsePolicy {
    private PresetSyncResponsePolicy() { }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid preset response");
    }
    private static Map<?, ?> object(Object value) {
        require(value instanceof Map); return (Map<?, ?>) value;
    }
    private static String string(Object value, int max) {
        require(value instanceof String);
        String text = (String) value;
        require(!text.trim().isEmpty() && text.length() <= max); return text;
    }
    private static void bool(Object value) { require(value instanceof Boolean); }
    private static long integer(Object value, long minimum) {
        require(value instanceof Number);
        double number = ((Number) value).doubleValue();
        require(Double.isFinite(number) && number >= minimum && number <= 9007199254740991.0
                && number == Math.floor(number));
        return (long) number;
    }
    private static void time(Object value) { Instant.parse(string(value, 80)); }
    private static void one(Object value, String... allowed) {
        require(value instanceof String);
        for (String candidate : allowed) if (candidate.equals(value)) return;
        throw new IllegalArgumentException("Invalid preset enum");
    }
    private static void temperature(Map<?, ?> values, double maximum) {
        require(values.containsKey("tempC"));
        Object value = values.get("tempC");
        if (value == null) return;
        require(value instanceof Number);
        double number = ((Number) value).doubleValue();
        require(Double.isFinite(number) && number >= 18 && number <= maximum
                && number * 2 == Math.floor(number * 2));
    }
    private static String preset(Object value) {
        Map<?, ?> p = object(value);
        String id = string(p.get("id"), 80);
        require(id.matches("[a-zA-Z0-9_-]+"));
        string(p.get("name"), 40); one(p.get("kind"), "builtin", "custom");
        one(p.get("comfort"), "cool_down", "quiet_rest", "gentle_refresh");
        Map<?, ?> car = object(p.get("vehicle")), home = object(p.get("home"));
        temperature(car, 28); one(car.get("fan"), "keep", "low", "medium", "high");
        one(car.get("seat"), "keep", "manual_comfort");
        temperature(home, 30); one(home.get("fan"), "keep", "auto", "low", "medium", "high");
        one(home.get("power"), "keep", "on");
        if (p.containsKey("cabin")) cabin(p.get("cabin"));
        return id;
    }

    /** The phone's 차 안 설정 for a rest (cabin contract v1), exactly these groups and values. */
    static void cabin(Object value) {
        Map<?, ?> c = object(value);
        require(c.size() == 5);
        for (String side : new String[]{"seatDriver", "seatPassenger"}) {
            Map<?, ?> s = object(c.get(side));
            require(s.size() == 3);
            one(s.get("position"), "keep", "manual"); one(s.get("comfort"), "keep", "off", "heat", "vent");
            long level = integer(s.get("level"), 1); require(level <= 3);
        }
        for (String side : new String[]{"climDriver", "climPassenger"}) {
            Map<?, ?> k = object(c.get(side));
            require(k.size() == 5);
            one(k.get("mode"), "keep", "custom");
            require(k.get("temp") instanceof Number);
            double t = ((Number) k.get("temp")).doubleValue();
            require(Double.isFinite(t) && t >= 18 && t <= 28 && t * 2 == Math.floor(t * 2));
            one(k.get("fan"), "auto", "low", "medium", "high"); one(k.get("dir"), "keep", "upper", "lower", "both");
            one(k.get("ac"), "keep", "on", "off");
        }
        Map<?, ?> l = object(c.get("light"));
        require(l.size() == 3);
        one(l.get("mode"), "keep", "off", "custom"); one(l.get("color"), "cream", "neutral", "sage", "mist");
        long bright = integer(l.get("bright"), 10); require(bright <= 100);
    }
    private static void sync(Object value) {
        Map<?, ?> s = object(value);
        string(s.get("epoch"), 80); string(s.get("deviceId"), 80);
        one(s.get("role"), "vehicle"); time(s.get("expiresAt"));
    }
    private static void state(Object value) {
        Map<?, ?> s = object(value);
        require(Boolean.TRUE.equals(s.get("demo"))); integer(s.get("revision"), 0); time(s.get("now"));
        require(s.get("presets") instanceof List);
        List<?> presets = (List<?>) s.get("presets");
        require(presets.size() >= 3 && presets.size() <= 53);
        Set<String> ids = new HashSet<>();
        for (Object p : presets) require(ids.add(preset(p)));
        require(ids.contains(string(s.get("selectedId"), 80)) && ids.contains(string(s.get("defaultId"), 80)));
        bool(s.get("autoDefaultOnRest")); one(s.get("feeling"), "usual", "warm", "quiet", "tired", "cold", "skip");
        require(s.containsKey("recommendation") && s.containsKey("plan"));
        if (s.get("recommendation") != null) {
            Map<?, ?> r = object(s.get("recommendation"));
            require(ids.contains(string(r.get("presetId"), 80))); string(r.get("reason"), 512);
        }
        Map<?, ?> devices = object(s.get("devices")), home = object(devices.get("home"));
        require("home-ac".equals(home.get("id"))); string(home.get("name"), 512); temperature(home, 30);
        one(home.get("fan"), "keep", "auto", "low", "medium", "high"); one(home.get("power"), "keep", "on", "off");
        integer(home.get("revision"), 0); bool(home.get("online")); string(home.get("updatedBy"), 512); time(home.get("observedAt"));
        Map<?, ?> car = object(devices.get("vehicle"));
        bool(car.get("parked")); require(Boolean.TRUE.equals(car.get("simulated")));
        if (car.get("proposal") != null) preset(car.get("proposal"));
        if (car.get("confirmedPresetId") != null) string(car.get("confirmedPresetId"), 80);
        // A place the phone sent; RestPlace checks the rest of its shape before anything is shown.
        if (car.get("place") != null) {
            Map<?, ?> place = object(car.get("place")); string(place.get("id"), 64); string(place.get("name"), 40);
            Object address = place.get("address"), lat = place.get("lat"), lon = place.get("lon");
            require(address instanceof String && ((String) address).length() <= 120);
            require((lat == null) == (lon == null) && (lat == null || (lat instanceof Number && lon instanceof Number)));
        }
        if (s.get("plan") != null) plan(s.get("plan"));
    }
    private static void plan(Object value) {
        Map<?, ?> p = object(value);
        string(p.get("id"), 80); integer(p.get("version"), 1); string(p.get("presetId"), 80);
        string(p.get("presetName"), 40);
        // Snapshots intentionally outlive edits/deletion of their source preset.
        require(preset(p.get("presetSnapshot")).equals(p.get("presetId")));
        require("home-ac".equals(p.get("targetId")));
        time(p.get("createdAt")); time(p.get("arrivalAt")); time(p.get("executeAt"));
        require(integer(p.get("leadMinutes"), 1) <= 30);
        one(p.get("status"), "armed", "requested", "confirmed_demo", "failed", "cancelled", "expired", "needs_review");
        bool(p.get("enabled")); integer(p.get("targetRevision"), 0); string(p.get("message"), 512);
        if (p.get("requestedAt") != null) time(p.get("requestedAt"));
        if (p.get("confirmedAt") != null) time(p.get("confirmedAt"));
    }
    static void validate(String path, int code, Object value, String expectedRequestId) {
        Map<?, ?> body = object(value);
        require(code < 300 || code >= 400);
        if (code < 200 || code >= 300) {
            if (body.containsKey("state") || body.containsKey("sync")) {
                state(body.get("state")); sync(body.get("sync"));
            }
            return;
        }
        switch (path) {
            case "/pairings/claim":
                string(body.get("pairingId"), 80);
                require(string(body.get("token"), 256).length() >= 32);
                require(string(body.get("comparisonCode"), 4).matches("[0-9]{4}")); time(body.get("expiresAt"));
                break;
            case "/pairings/status":
                one(body.get("status"), "pending", "approved"); string(body.get("pairingId"), 80);
                require(string(body.get("comparisonCode"), 4).matches("[0-9]{4}")); sync(body.get("sync"));
                break;
            case "/state": case "/commands":
                state(body.get("state")); sync(body.get("sync"));
                if (path.equals("/commands")) require(expectedRequestId != null
                        && expectedRequestId.equals(body.get("acknowledgedRequestId")));
                break;
            case "/session": require(Boolean.TRUE.equals(body.get("deleted"))); break;
            default: throw new IllegalArgumentException("Unknown preset response");
        }
    }
}
