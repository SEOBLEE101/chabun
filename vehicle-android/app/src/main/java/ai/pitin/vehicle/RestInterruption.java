package ai.pitin.vehicle;

/**
 * When a started rest stops being a rest (2026-11-06): the car moved, or a pause was left too long. The rest then
 * ends like ‘마치기’, so what it changed is put back while parked (right away, or once the car is parked again).
 * Pure Java, checked by RestInterruptionTest; SafetyGate and RestSession are unchanged.
 */
final class RestInterruption {
    /** A rest paused this long (the pause button, a call or a screen limit) ends. */
    static final long PAUSE_LIMIT_MS = 10 * 60_000L;
    /** Above this speed the car is moving; below it a reading is taken as standing still. */
    static final double MOVING_KPH = 1.0;

    enum Reason { NONE, MOVED, PAUSED_TOO_LONG }

    private RestInterruption() { }

    /**
     * True only when the car's own values say it is not parked: a known gear other than P, or a known speed of
     * {@link #MOVING_KPH} or more. Unknown or stale values are not "moved" (they only pause the rest).
     */
    static boolean carMoved(SafetyGate.Snapshot s) {
        if (s == null) return false;
        if (s.gear != SafetyGate.Gear.UNKNOWN && s.gear != SafetyGate.Gear.PARK) return true;
        return s.speedKph != null && Double.isFinite(s.speedKph) && s.speedKph >= MOVING_KPH;
    }

    /** {@code pausedAtMs}: when the rest was first seen paused, 0 when it is not paused. */
    static Reason check(RestSession.State state, SafetyGate.Snapshot s, long pausedAtMs, long nowMs) {
        if (state != RestSession.State.ACTIVE && state != RestSession.State.PAUSED) return Reason.NONE;
        if (carMoved(s)) return Reason.MOVED;
        if (state == RestSession.State.PAUSED && pausedAtMs > 0 && nowMs - pausedAtMs >= PAUSE_LIMIT_MS)
            return Reason.PAUSED_TOO_LONG;
        return Reason.NONE;
    }

    /** The line shown with the restore result when a rest ended this way. */
    static String note(Reason reason) {
        switch (reason) {
            case MOVED: return "차가 움직여서 쉼을 마쳤어요";
            case PAUSED_TOO_LONG: return "오래 멈춘 쉼을 마쳤어요";
            default: return null;
        }
    }
}
