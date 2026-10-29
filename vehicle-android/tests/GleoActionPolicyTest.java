package ai.pitin.vehicle;

import java.util.HashMap;
import java.util.Map;

public final class GleoActionPolicyTest {
    private static int assertions;
    private static void expect(GleoActionPolicy.Result expected, String action, Map<String, ?> extras,
            RestSession.State state) {
        assertions++;
        if (GleoActionPolicy.evaluate(action, extras, state) != expected)
            throw new AssertionError("Gleo policy assertion " + assertions);
    }

    public static void main(String[] args) {
        for (RestSession.State state : RestSession.State.values()) {
            expect(GleoActionPolicy.Result.OPEN, GleoActionPolicy.OPEN, Map.of(), state);
            expect(state == RestSession.State.ACTIVE || state == RestSession.State.PAUSED
                    ? GleoActionPolicy.Result.KEEP_SESSION : GleoActionPolicy.Result.PREPARE,
                    GleoActionPolicy.PREPARE, Map.of("ACTION_PARAM", "{}"), state);
        }
        expect(GleoActionPolicy.Result.PREPARE, GleoActionPolicy.PREPARE, null, RestSession.State.IDLE);
        expect(GleoActionPolicy.Result.PREPARE, GleoActionPolicy.PREPARE,
                Map.of("ACTION_PARAM", " \t{ \n}\r "), RestSession.State.IDLE);
        for (Object invalid : new Object[] { "", "null", "[]", "true", "{\"start\":true}", "{}{}",
                "{ } trailing", "\"{}\"", " ".repeat(129) + "{}", 1, false, Map.of() }) {
            expect(GleoActionPolicy.Result.REJECT, GleoActionPolicy.PREPARE,
                    Map.of("ACTION_PARAM", invalid), RestSession.State.IDLE);
        }
        Map<String, Object> nullParameter = new HashMap<>();
        nullParameter.put("ACTION_PARAM", null);
        expect(GleoActionPolicy.Result.REJECT, GleoActionPolicy.OPEN, nullParameter, RestSession.State.IDLE);
        expect(GleoActionPolicy.Result.REJECT, GleoActionPolicy.PREPARE,
                Map.of("consent", true), RestSession.State.IDLE);
        expect(GleoActionPolicy.Result.REJECT, GleoActionPolicy.PREPARE,
                Map.of("ACTION_PARAM", "{}", "comfort", "cool_down"), RestSession.State.IDLE);
        expect(GleoActionPolicy.Result.REJECT, GleoActionPolicy.PREPARE,
                Map.of("ACTION_PARAM", "{\"resume\":true}"), RestSession.State.PAUSED);
        expect(GleoActionPolicy.Result.REJECT, "START_REST", Map.of(), RestSession.State.IDLE);
        expect(GleoActionPolicy.Result.REJECT, "RESUME_REST", Map.of(), RestSession.State.PAUSED);
        expect(GleoActionPolicy.Result.REJECT, "SET_TEMPERATURE", Map.of(), RestSession.State.IDLE);
        expect(GleoActionPolicy.Result.REJECT, null, Map.of(), RestSession.State.IDLE);
        expect(GleoActionPolicy.Result.REJECT, GleoActionPolicy.PREPARE, Map.of(), null);
        System.out.println("PASS " + assertions + " Gleo action boundary assertions");
    }
}
