package ai.pitin.vehicle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Synthetic decoded responses, including snapshots whose original preset was deleted. */
final class PresetSyncFixtures {
    static final String TIME = "2026-10-26T00:00:00Z";
    static Map<String, Object> object(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    static Map<String, Object> preset(String id) {
        return object("id", id, "name", id, "kind", "custom", "comfort", "quiet_rest",
                "vehicle", object("tempC", 24.0, "fan", "low", "seat", "manual_comfort"),
                "home", object("tempC", null, "fan", "auto", "power", "on"));
    }
    static Map<String, Object> sync() {
        return object("epoch", "epoch-a", "deviceId", "vehicle-a", "role", "vehicle", "expiresAt", TIME);
    }
    static Map<String, Object> claim() {
        return object("pairingId", "pair-a", "token", "a".repeat(43), "comparisonCode", "0042", "expiresAt", TIME);
    }
    static Map<String, Object> status() {
        return object("pairingId", "pair-a", "status", "pending", "comparisonCode", "0042", "sync", sync());
    }
    static Map<String, Object> response() {
        Map<String, Object> home = object("id", "home-ac", "name", "home", "tempC", 27.0,
                "fan", "auto", "power", "off", "revision", 0, "online", true, "updatedBy", "fixture", "observedAt", TIME);
        Map<String, Object> vehicle = object("parked", true, "simulated", true,
                "proposal", preset("deleted_source"), "confirmedPresetId", "deleted_source");
        Map<String, Object> plan = object("id", "plan-a", "version", 1, "presetId", "deleted_source",
                "presetName", "Saved snapshot", "presetSnapshot", preset("deleted_source"), "targetId", "home-ac",
                "createdAt", TIME, "arrivalAt", TIME, "executeAt", TIME, "leadMinutes", 5,
                "status", "armed", "enabled", true, "targetRevision", 0, "message", "Synthetic plan",
                "requestedAt", null, "confirmedAt", null);
        Map<String, Object> state = object("demo", true, "revision", 4, "now", TIME,
                "presets", new ArrayList<>(Arrays.asList(preset("quiet_rest"), preset("cool_down"), preset("gentle_refresh"))),
                "selectedId", "quiet_rest", "defaultId", "cool_down", "autoDefaultOnRest", false,
                "feeling", "skip", "recommendation", object("presetId", "quiet_rest", "reason", "Chosen feeling"),
                "plan", plan, "devices", object("home", home, "vehicle", vehicle));
        return object("state", state, "sync", sync(), "acknowledgedRequestId", "request-a");
    }
}
