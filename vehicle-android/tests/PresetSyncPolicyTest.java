package ai.pitin.vehicle;

public final class PresetSyncPolicyTest {
    static int assertions;
    static void check(boolean value) { assertions++; if (!value) throw new AssertionError("check " + assertions); }
    public static void main(String[] args) {
        PresetSyncPolicy p = new PresetSyncPolicy();
        check(!p.canWrite());
        p.start(); long initial = p.generation;
        check(p.observe(initial, "one", 4)); check(p.canWrite());
        check(!p.observe(initial, "one", 3));
        p.beginCommand(); check(!p.canWrite());
        check(!p.observe(initial, "one", 5));
        p.failed(); check(!p.canWrite());
        check(p.observe(p.generation, "one", 5)); check(!p.canWrite());
        p.resolved(); check(p.canWrite());
        p.stop(); check(!p.observe(p.generation - 1, "one", 7)); check(!p.canWrite());
        p.start(); check(!p.canWrite()); check(p.observe(p.generation, "two", 0));
        check(p.restarted); check(p.canWrite());
        check(PresetSyncPolicy.validComfort("quiet_rest")); check(!PresetSyncPolicy.validComfort("arbitrary"));
        responses();
        reviewOrigins();
        System.out.println("PASS " + assertions + " preset sync lifecycle and decoded-response assertions");
    }

    /** The debug relay-origin hook accepts only the local HTTPS relay on port 9443. */
    static void reviewOrigins() {
        check("https://127.0.0.1:9443".equals(PresetSyncPolicy.reviewRelayOrigin("https://127.0.0.1:9443")));
        check("https://127.0.0.1:9443".equals(PresetSyncPolicy.reviewRelayOrigin(" https://127.0.0.1:9443/ ")));
        check("https://localhost:9443".equals(PresetSyncPolicy.reviewRelayOrigin("https://localhost:9443")));
        check("https://10.0.2.2:9443".equals(PresetSyncPolicy.reviewRelayOrigin("https://10.0.2.2:9443")));
        for (String refused : new String[]{null, "", "http://127.0.0.1:9443", "https://127.0.0.1",
                "https://127.0.0.1:9447", "https://127.0.0.1:443", "https://example.com:9443",
                "https://127.0.0.2:9443", "https://user@127.0.0.1:9443", "https://127.0.0.1:9443/api",
                "https://127.0.0.1:9443?x=1", "https://127.0.0.1:9443#x", "https://127.0.0.1.example.com:9443",
                "file:///etc/hosts", "not a uri"})
            check(PresetSyncPolicy.reviewRelayOrigin(refused) == null);
    }

    @SuppressWarnings("unchecked")
    static void change(java.util.Map<String, Object> root, String path, Object value, boolean remove) {
        String[] keys = path.split("\\."); Object current = root;
        for (int i = 0; i < keys.length - 1; i++) current = current instanceof java.util.List
                ? ((java.util.List<?>) current).get(Integer.parseInt(keys[i])) : ((java.util.Map<?, ?>) current).get(keys[i]);
        java.util.Map<String, Object> parent = (java.util.Map<String, Object>) current;
        if (remove) parent.remove(keys[keys.length - 1]); else parent.put(keys[keys.length - 1], value);
    }
    static void valid(String path, int code, Object body, String request) {
        PresetSyncResponsePolicy.validate(path, code, body, request); check(true);
    }
    static void reject(String path, int code, Object body, String request) {
        boolean rejected = false;
        try { PresetSyncResponsePolicy.validate(path, code, body, request); }
        catch (IllegalArgumentException | java.time.DateTimeException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("Accepted malformed response: " + path);
        check(true);
    }
    static void responses() {
        valid("/state", 200, PresetSyncFixtures.response(), null);
        valid("/commands", 200, PresetSyncFixtures.response(), "request-a");
        valid("/commands", 409, PresetSyncFixtures.response(), "request-a");
        valid("/pairings/claim", 201, PresetSyncFixtures.claim(), null);
        valid("/pairings/status", 200, PresetSyncFixtures.status(), null);
        var approved = PresetSyncFixtures.status(); approved.put("status", "approved");
        valid("/pairings/status", 200, approved, null);
        valid("/session", 200, PresetSyncFixtures.object("deleted", true), null);
        valid("/state", 401, PresetSyncFixtures.object("error", "revoked"), null);
        var nullable = PresetSyncFixtures.response();
        for (String path : new String[]{"state.recommendation", "state.plan", "state.devices.vehicle.proposal", "state.devices.vehicle.confirmedPresetId"})
            change(nullable, path, null, false);
        valid("/state", 200, nullable, null);
        for (String field : new String[]{"state", "sync", "state.presets", "state.selectedId", "state.defaultId",
                "state.autoDefaultOnRest", "state.recommendation", "state.plan", "state.devices", "state.presets.0.vehicle.tempC"}) {
            var body = PresetSyncFixtures.response(); change(body, field, null, true);
            reject("/state", 200, body, null);
        }
        Object[][] bad = {
            {"state.demo", false}, {"state.revision", "4"}, {"state.revision", -1}, {"state.revision", 4.5},
            {"state.revision", Double.NaN}, {"state.revision", 9007199254740992.0}, {"state.now", "tomorrow"},
            {"state.presets", java.util.List.of()}, {"state.selectedId", "missing"}, {"state.defaultId", "missing"},
            {"state.autoDefaultOnRest", "false"}, {"state.feeling", "unknown"}, {"state.recommendation.presetId", "missing"},
            {"state.presets.0.id", "cool_down"}, {"state.presets.0.id", "bad/id"}, {"state.presets.0.name", " "},
            {"state.presets.0.kind", "other"}, {"state.presets.0.comfort", "other"}, {"state.presets.0.vehicle.tempC", 28.5},
            {"state.presets.0.vehicle.tempC", "24"}, {"state.presets.0.vehicle.tempC", 24.25},
            {"state.presets.0.vehicle.fan", "auto"}, {"state.presets.0.vehicle.seat", "automatic"},
            {"state.presets.0.home.tempC", 17.5}, {"state.presets.0.home.fan", "bad"}, {"state.presets.0.home.power", "off"},
            {"state.devices.home.online", "yes"}, {"state.devices.home.id", "other"}, {"state.devices.home.observedAt", false},
            {"state.devices.vehicle.parked", "true"}, {"state.devices.vehicle.simulated", false},
            {"state.devices.vehicle.proposal", PresetSyncFixtures.object("id", "bad")},
            {"state.plan", PresetSyncFixtures.object("status", "armed")}, {"state.plan.version", 0},
            {"state.plan.presetId", "mismatch"}, {"state.plan.targetId", "other"}, {"state.plan.leadMinutes", 31},
            {"state.plan.status", "done"}, {"state.plan.enabled", "true"}, {"state.plan.targetRevision", -1},
            {"state.plan.requestedAt", "bad"}, {"state.plan.confirmedAt", false},
            {"sync.epoch", ""}, {"sync.deviceId", null}, {"sync.role", "phone"}, {"sync.expiresAt", "bad"}
        };
        for (Object[] mutation : bad) {
            var body = PresetSyncFixtures.response(); change(body, (String) mutation[0], mutation[1], false);
            reject("/state", 200, body, null);
            reject("/commands", 200, body, "request-a");
        }
        var tooMany = PresetSyncFixtures.response();
        change(tooMany, "state.presets", java.util.Collections.nCopies(54, PresetSyncFixtures.preset("a")), false);
        reject("/state", 200, tooMany, null);
        for (Object[] mutation : new Object[][]{{"token", "short"}, {"token", 42}, {"comparisonCode", "123"},
                {"comparisonCode", "12x4"}, {"comparisonCode", 42}, {"expiresAt", false}, {"pairingId", ""}}) {
            var body = PresetSyncFixtures.claim(); body.put((String) mutation[0], mutation[1]); reject("/pairings/claim", 200, body, null);
        }
        for (Object[] mutation : new Object[][]{{"status", "unknown"}, {"comparisonCode", "1x34"}, {"pairingId", null}, {"sync", null}}) {
            var body = PresetSyncFixtures.status(); body.put((String) mutation[0], mutation[1]); reject("/pairings/status", 200, body, null);
        }
        reject("/commands", 200, PresetSyncFixtures.response(), "wrong-request");
        reject("/commands", 200, PresetSyncFixtures.response(), null);
        reject("/session", 200, PresetSyncFixtures.object("deleted", "true"), null);
        reject("/state", 302, PresetSyncFixtures.response(), null);
        var brokenConflict = PresetSyncFixtures.response(); brokenConflict.remove("state");
        reject("/commands", 409, brokenConflict, "request-a");
        brokenConflict = PresetSyncFixtures.response(); brokenConflict.remove("sync");
        reject("/commands", 409, brokenConflict, "request-a");
        PresetSyncPolicy policy = new PresetSyncPolicy(); policy.start(); policy.beginCommand();
        var broken = PresetSyncFixtures.response(); change(broken, "state.selectedId", "missing", false);
        try {
            PresetSyncResponsePolicy.validate("/commands", 200, broken, "request-a");
            policy.observe(policy.generation, "epoch-a", 4); policy.resolved();
        } catch (IllegalArgumentException expected) { policy.failed(); }
        check(!policy.fresh && policy.unresolved && !policy.canWrite());
        policy.observe(policy.generation, "epoch-a", 4);
        check(policy.unresolved && !policy.canWrite());
    }
}
