package ai.pitin.vehicle;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Untrusted voice actions can open a screen or ask for cabin values, never authorize or run a rest session.
 * Gleo allows three actions per app (PREPARE_QUIET_REST, OPEN_CABIN_SETTINGS, SET_CABIN). A cabin request comes only
 * from a Gleo package, not during a rest, and still has to pass the parked write gate when each value is sent.
 */
public final class GleoActionPolicy {
    /** Not in the schema any more (three actions); still opens the app if something sends it. */
    public static final String OPEN = "OPEN_PIT_IN";
    public static final String PREPARE = "PREPARE_QUIET_REST";
    /** Opens 차 안 설정 on one tab. Never changes a vehicle setting. */
    public static final String CABIN = "OPEN_CABIN_SETTINGS";
    /** 차 안 맞추기 by voice: {"mode":"rest"} for the chosen rest's plan, or climate, seat or light values. */
    public static final String SET = "SET_CABIN";
    public enum Result { OPEN, PREPARE, KEEP_SESSION, REJECT, OPEN_CABIN, SESSION_BUSY, SET_CABIN, APPLY_CABIN, UNTRUSTED }

    /**
     * Packages allowed to ask for cabin values: the Gleo apps seen on the Pleos image. The sender is the package
     * Android reports as the launcher of the intent; extra keys (such as a forged referrer) are already refused.
     * Pleos has not published a caller contract yet (asked in the inquiry), so this list may need its answer.
     */
    static final Set<String> GLEO_CALLERS = Set.of("ai.umos.ambientai", "ai.pleos.gleoai.model.provider");

    /** Exactly {"section":"seat"|"climate"|"light"}: one key, a plain string, nothing else, no escapes. */
    private static final Pattern SECTION = Pattern.compile(
            "[\\t\\r\\n ]*\\{[\\t\\r\\n ]*\"section\"[\\t\\r\\n ]*:[\\t\\r\\n ]*\"(seat|climate|light)\"[\\t\\r\\n ]*\\}[\\t\\r\\n ]*");

    private GleoActionPolicy() { }

    public static boolean recognizes(String action) {
        return OPEN.equals(action) || PREPARE.equals(action) || CABIN.equals(action) || SET.equals(action);
    }

    public static Result evaluate(String action, Map<String, ?> extras, RestSession.State state) {
        return evaluate(action, extras, state, GLEO_CALLERS.iterator().next(), false);
    }

    /** {@code caller}: the launching package; {@code debugShell}: debug builds also accept adb's shell for tests. */
    public static Result evaluate(String action, Map<String, ?> extras, RestSession.State state, String caller, boolean debugShell) {
        if (!recognizes(action) || state == null) return Result.REJECT;
        if (SET.equals(action)) {
            CabinControl.Voice voice = cabinRequest(extras);
            if (voice == null) return Result.REJECT;
            if (!trusted(caller, debugShell)) return Result.UNTRUSTED;
            // First submission: no direct cabin change while a rest owns the cabin (its restore would overwrite it).
            if (state == RestSession.State.ACTIVE || state == RestSession.State.PAUSED) return Result.SESSION_BUSY;
            return voice.rest ? Result.APPLY_CABIN : Result.SET_CABIN;
        }
        if (CABIN.equals(action)) {
            if (cabinSection(extras) == null) return Result.REJECT;
            // The rest screen is protected: keep it and answer fail (silently, since a rest is running).
            if (state == RestSession.State.ACTIVE || state == RestSession.State.PAUSED) return Result.SESSION_BUSY;
            return Result.OPEN_CABIN;
        }
        if (!validExtras(extras)) return Result.REJECT;
        if (OPEN.equals(action)) return Result.OPEN;
        if (state == RestSession.State.ACTIVE || state == RestSession.State.PAUSED) return Result.KEEP_SESSION;
        return Result.PREPARE;
    }

    static boolean trusted(String caller, boolean debugShell) {
        return caller != null && (GLEO_CALLERS.contains(caller) || (debugShell && "com.android.shell".equals(caller)));
    }

    /** The validated tab for OPEN_CABIN_SETTINGS, or null for anything malformed. */
    public static String cabinSection(Map<String, ?> extras) {
        if (extras == null || extras.size() != 1 || !extras.containsKey("ACTION_PARAM")) return null;
        Object value = extras.get("ACTION_PARAM");
        if (!(value instanceof String) || ((String) value).length() > 128) return null;
        Matcher m = SECTION.matcher((String) value);
        return m.matches() ? m.group(1) : null;
    }

    /** The validated SET_CABIN request: exactly one ACTION_PARAM string that CabinControl accepts, or null. */
    public static CabinControl.Voice cabinRequest(Map<String, ?> extras) {
        if (extras == null || extras.size() != 1 || !extras.containsKey("ACTION_PARAM")) return null;
        Object value = extras.get("ACTION_PARAM");
        return value instanceof String ? CabinControl.voice((String) value) : null;
    }

    private static boolean validExtras(Map<String, ?> extras) {
        if (extras == null || extras.isEmpty()) return true;
        if (extras.size() != 1 || !extras.containsKey("ACTION_PARAM")) return false;
        Object value = extras.get("ACTION_PARAM");
        if (!(value instanceof String) || ((String) value).length() > 128) return false;
        // These functions take no parameters. Accept only an empty JSON object.
        return ((String) value).matches("[\\t\\r\\n ]*\\{[\\t\\r\\n ]*\\}[\\t\\r\\n ]*");
    }
}
