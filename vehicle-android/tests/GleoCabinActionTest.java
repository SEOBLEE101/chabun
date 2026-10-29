package ai.pitin.vehicle;

import java.util.HashMap;
import java.util.Map;

/** OPEN_CABIN_SETTINGS(section): strict argument, opens a tab only, never over a running rest. */
public final class GleoCabinActionTest {
    private static int assertions;

    private static void expect(GleoActionPolicy.Result expected, Map<String, ?> extras, RestSession.State state) {
        assertions++;
        GleoActionPolicy.Result actual = GleoActionPolicy.evaluate(GleoActionPolicy.CABIN, extras, state);
        if (actual != expected) throw new AssertionError("cabin assertion " + assertions + ": " + actual + " for " + extras + " in " + state);
    }

    private static void section(String expected, Map<String, ?> extras) {
        assertions++;
        String actual = GleoActionPolicy.cabinSection(extras);
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("section assertion " + assertions + ": " + actual);
    }

    public static void main(String[] args) {
        if (!GleoActionPolicy.recognizes(GleoActionPolicy.CABIN)) throw new AssertionError("cabin action recognised");
        assertions++;
        for (String tab : new String[]{"seat", "climate", "light"}) {
            Map<String, ?> ok = Map.of("ACTION_PARAM", "{\"section\":\"" + tab + "\"}");
            section(tab, ok);
            section(tab, Map.of("ACTION_PARAM", " \n{ \"section\" :\t\"" + tab + "\" }\r "));
            for (RestSession.State state : RestSession.State.values()) {
                boolean resting = state == RestSession.State.ACTIVE || state == RestSession.State.PAUSED;
                expect(resting ? GleoActionPolicy.Result.SESSION_BUSY : GleoActionPolicy.Result.OPEN_CABIN, ok, state);
            }
            expect(GleoActionPolicy.Result.REJECT, ok, null);
        }
        // Required argument, exact enum, exact shape. Anything else fails without a screen change.
        Object[] invalid = {
                "{}", "", "null", "[]", "\"seat\"", "{\"section\":\"Seat\"}", "{\"section\":\"SEAT\"}",
                "{\"section\":\"seats\"}", "{\"section\":\"door\"}", "{\"section\":seat}", "{\"section\":1}",
                "{\"section\":null}", "{\"section\":[\"seat\"]}", "{\"section\":\"seat\",\"seat\":\"driver\"}",
                "{\"section\":\"climate\",\"temperature\":24}", "{\"tab\":\"seat\"}", "{\"section\":\"seat\"}{}",
                "{\"section\":\"seat\"} trailing", "{\"section\":\"se\\u0061t\"}", "{\"section\":\"seat\"",
                " ".repeat(120) + "{\"section\":\"seat\"}", 1, true, Map.of("section", "seat")
        };
        for (Object value : invalid) {
            Map<String, Object> extras = new HashMap<>();
            extras.put("ACTION_PARAM", value);
            section(null, extras);
            expect(GleoActionPolicy.Result.REJECT, extras, RestSession.State.IDLE);
            expect(GleoActionPolicy.Result.REJECT, extras, RestSession.State.ACTIVE);
        }
        Map<String, Object> nullParam = new HashMap<>();
        nullParam.put("ACTION_PARAM", null);
        expect(GleoActionPolicy.Result.REJECT, nullParam, RestSession.State.IDLE);
        expect(GleoActionPolicy.Result.REJECT, null, RestSession.State.IDLE);
        expect(GleoActionPolicy.Result.REJECT, Map.of(), RestSession.State.IDLE);
        expect(GleoActionPolicy.Result.REJECT, Map.of("section", "seat"), RestSession.State.IDLE);
        expect(GleoActionPolicy.Result.REJECT, Map.of("ACTION_PARAM", "{\"section\":\"seat\"}", "start", true), RestSession.State.IDLE);
        // The two existing functions keep their exact behaviour, including with a cabin-style argument.
        assertions++;
        if (GleoActionPolicy.evaluate(GleoActionPolicy.OPEN, Map.of("ACTION_PARAM", "{\"section\":\"seat\"}"), RestSession.State.IDLE)
                != GleoActionPolicy.Result.REJECT) throw new AssertionError("OPEN still takes no parameters");
        assertions++;
        if (GleoActionPolicy.evaluate(GleoActionPolicy.PREPARE, Map.of(), RestSession.State.IDLE) != GleoActionPolicy.Result.PREPARE)
            throw new AssertionError("PREPARE unchanged");
        // A voice request never yields a start or resume.
        for (GleoActionPolicy.Result r : GleoActionPolicy.Result.values()) {
            assertions++;
            if (r.name().contains("START") || r.name().contains("RESUME")) throw new AssertionError("no start/resume result");
        }
        System.out.println("PASS " + assertions + " Gleo cabin action assertions");
    }
}
