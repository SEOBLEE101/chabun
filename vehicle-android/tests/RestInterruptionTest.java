package ai.pitin.vehicle;

/** When a rest ends by itself (2026-11-06): the car moved, or a pause was left too long. Pure Java, no Android. */
public final class RestInterruptionTest {
    private static int assertions;

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    private static SafetyGate.Snapshot car(SafetyGate.Gear gear, Double speed) {
        return new SafetyGate.Snapshot(false, gear, speed, false, false, true, 1_000);
    }

    public static void main(String[] args) {
        RestSession.State active = RestSession.State.ACTIVE, paused = RestSession.State.PAUSED;
        SafetyGate.Snapshot parked = car(SafetyGate.Gear.PARK, 0.0);

        // Moving: any known gear other than P, or a known speed.
        for (SafetyGate.Gear g : new SafetyGate.Gear[]{SafetyGate.Gear.DRIVE, SafetyGate.Gear.REVERSE, SafetyGate.Gear.NEUTRAL})
            check(RestInterruption.carMoved(car(g, 0.0)), "gear " + g + " is moving");
        check(RestInterruption.carMoved(car(SafetyGate.Gear.PARK, 12.0)), "speed in P is moving");
        check(RestInterruption.carMoved(car(SafetyGate.Gear.UNKNOWN, 30.0)), "known speed with unknown gear is moving");
        check(!RestInterruption.carMoved(parked), "P and 0 km/h is parked");
        check(!RestInterruption.carMoved(car(SafetyGate.Gear.PARK, 0.4)), "sensor jitter under 1 km/h is not moving");

        // Unknown or stale values only pause; they never end a rest by themselves.
        check(!RestInterruption.carMoved(car(SafetyGate.Gear.UNKNOWN, null)), "unknown is not moving");
        check(!RestInterruption.carMoved(car(null, null)), "missing gear is not moving");
        check(!RestInterruption.carMoved(car(SafetyGate.Gear.PARK, Double.NaN)), "NaN speed is not moving");
        check(!RestInterruption.carMoved(null), "no snapshot is not moving");

        // A running or paused rest ends when the car moves.
        check(RestInterruption.check(active, car(SafetyGate.Gear.DRIVE, 5.0), 0, 10_000) == RestInterruption.Reason.MOVED, "active + D");
        check(RestInterruption.check(paused, car(SafetyGate.Gear.REVERSE, 0.0), 5_000, 10_000) == RestInterruption.Reason.MOVED, "paused + R");

        // Only a rest that is running or paused can end.
        for (RestSession.State s : new RestSession.State[]{RestSession.State.IDLE, RestSession.State.COMPLETE, RestSession.State.ENDED})
            check(RestInterruption.check(s, car(SafetyGate.Gear.DRIVE, 40.0), 1, 10_000_000) == RestInterruption.Reason.NONE, s + " stays");

        // A pause ends at the limit, not before; a running rest has no pause clock.
        long limit = RestInterruption.PAUSE_LIMIT_MS;
        check(RestInterruption.check(paused, parked, 1_000, 1_000 + limit - 1) == RestInterruption.Reason.NONE, "just under the limit");
        check(RestInterruption.check(paused, parked, 1_000, 1_000 + limit) == RestInterruption.Reason.PAUSED_TOO_LONG, "at the limit");
        check(RestInterruption.check(paused, parked, 0, 99_000_000) == RestInterruption.Reason.NONE, "unknown pause start never expires");
        check(RestInterruption.check(active, parked, 1_000, 1_000 + limit * 3) == RestInterruption.Reason.NONE, "active is not a pause");
        check(RestInterruption.check(paused, car(SafetyGate.Gear.UNKNOWN, null), 1_000, 2_000) == RestInterruption.Reason.NONE,
                "stale values keep the pause");

        // Words.
        check("차가 움직여서 쉼을 마쳤어요".equals(RestInterruption.note(RestInterruption.Reason.MOVED)), "moved words");
        check("오래 멈춘 쉼을 마쳤어요".equals(RestInterruption.note(RestInterruption.Reason.PAUSED_TOO_LONG)), "pause words");
        check(RestInterruption.note(RestInterruption.Reason.NONE) == null, "no words");
        System.out.println("PASS " + assertions + " rest interruption assertions");
    }
}
