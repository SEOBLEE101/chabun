package ai.pitin.vehicle;

public final class SafetyGateTest {
    private static int checks;
    private static final long NOW = 10_000;

    private static SafetyGate.Snapshot state(boolean demo, SafetyGate.Gear gear, Double speed,
            Boolean call, Boolean restricted, boolean permission, long time) {
        return new SafetyGate.Snapshot(demo, gear, speed, call, restricted, permission, time);
    }
    private static SafetyGate.Snapshot safe() {
        return state(true, SafetyGate.Gear.PARK, 0.0, false, false, true, NOW);
    }
    private static SafetyGate.Decision gate(SafetyGate.Snapshot state) {
        return SafetyGate.preview(state, NOW, true, true);
    }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void denied(SafetyGate.Snapshot value, String message) {
        check(!gate(value).previewAllowed, message);
        check(!SafetyGate.permitsVehicleWrite(value), "writes must always stay disabled: " + message);
    }

    public static void main(String[] args) {
        check(gate(safe()).previewAllowed, "explicit safe simulated preview should start");
        check(!SafetyGate.permitsVehicleWrite(safe()), "even safe demo cannot write");
        denied(null, "null state");
        for (SafetyGate.Gear gear : SafetyGate.Gear.values()) {
            if (gear != SafetyGate.Gear.PARK) denied(state(true, gear, 0.0, false, false, true, NOW), "gear " + gear);
        }
        for (Double speed : new Double[]{null, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, -0.01, 0.01, 30.0})
            denied(state(true, SafetyGate.Gear.PARK, speed, false, false, true, NOW), "speed " + speed);
        denied(state(true, null, 0.0, false, false, true, NOW), "null gear");
        denied(state(true, SafetyGate.Gear.PARK, 0.0, null, false, true, NOW), "unknown call");
        denied(state(true, SafetyGate.Gear.PARK, 0.0, true, false, true, NOW), "active call");
        denied(state(true, SafetyGate.Gear.PARK, 0.0, false, null, true, NOW), "unknown UX");
        denied(state(true, SafetyGate.Gear.PARK, 0.0, false, true, true, NOW), "restricted UX");
        denied(state(true, SafetyGate.Gear.PARK, 0.0, false, false, false, NOW), "missing permission");
        denied(state(true, SafetyGate.Gear.PARK, 0.0, false, false, true, NOW - 5_000), "exact stale boundary");
        denied(state(true, SafetyGate.Gear.PARK, 0.0, false, false, true, NOW + 1), "future sample");
        check(gate(state(true, SafetyGate.Gear.PARK, 0.0, false, false, true, NOW - 4_999)).previewAllowed,
                "last fresh millisecond");
        denied(state(false, SafetyGate.Gear.PARK, 0.0, false, false, true, NOW), "live adapter never enabled");
        check(!SafetyGate.preview(safe(), NOW, false, true).previewAllowed, "no locally selected preset");
        check(!SafetyGate.preview(safe(), NOW, true, false).previewAllowed, "no local confirmation");

        RestSession session = new RestSession();
        check(session.start(NOW, gate(safe())), "start timer");
        check(!session.start(NOW + 1, gate(safe())), "start never resets an active session");
        check(session.secondsRemaining(NOW) == 180, "three-minute duration");
        check(session.expansion(NOW) == 0, "guided cycle begins contracted");
        check(session.expansion(NOW + 4_000) == 1, "four-second inhale endpoint");
        check(session.expansion(NOW + 8_000) == 0, "eight-second cycle endpoint");
        check(session.segment(NOW + 175_999) == RestSession.Segment.GUIDED, "last guided millisecond");
        check(session.segment(NOW + 176_000) == RestSession.Segment.NATURAL_REST, "176-second boundary");
        check(session.expansion(NOW + 178_000) == 0, "natural rest has no guided expansion");
        check(session.check(NOW + 179_999, gate(safe())) == RestSession.State.ACTIVE, "not prematurely complete");
        check(session.check(NOW + 180_000, gate(safe())) == RestSession.State.COMPLETE, "completion boundary");
        check(session.expansion(NOW + 200_000) == 0, "normal final expansion");
        session.setReducedMotion(true);
        check(session.expansion(NOW + 200_000) == 0.5, "reduced final expansion");

        RestSession paused = new RestSession();
        paused.start(NOW, gate(safe()));
        check(paused.check(NOW + 8_000, gate(null)) == RestSession.State.PAUSED, "unknown state pauses immediately");
        check(paused.elapsedActiveMs(NOW + 90_000) == 8_000, "paused time never counts");
        check(paused.check(NOW + 90_000, gate(safe())) == RestSession.State.PAUSED, "never automatically resume");
        check(!paused.start(NOW + 90_000, gate(safe())), "start cannot reset paused progress");
        check(!paused.resume(NOW + 90_000, gate(null)), "resume requires current valid gates");
        check(paused.resume(NOW + 90_000, gate(safe())), "explicit safe resume");
        check(paused.elapsedActiveMs(NOW + 91_000) == 9_000, "resume preserves elapsed active time");
        check(paused.finish(NOW + 91_500), "explicit early end");
        check(paused.state() == RestSession.State.ENDED, "early end differs from completion");
        check(paused.elapsedActiveMs(NOW + 200_000) == 9_500, "ended progress is frozen");

        RestSession latePause = new RestSession();
        latePause.start(NOW, gate(safe()));
        check(latePause.pause(NOW + 178_000), "pause at 178 seconds");
        check(latePause.secondsRemaining(NOW + 1_000_000) == 2, "178-second pause retains exactly two seconds");
        check(latePause.check(NOW + 1_000_000, gate(safe())) == RestSession.State.PAUSED, "natural rest does not complete while paused");
        check(latePause.expansion(NOW + 1_000_000) == 0, "natural-rest shape stays frozen");
        latePause.resume(NOW + 1_000_000, gate(safe()));
        check(latePause.check(NOW + 1_001_999, gate(safe())) == RestSession.State.ACTIVE, "last resumed millisecond");
        check(latePause.check(NOW + 1_002_000, gate(safe())) == RestSession.State.COMPLETE, "complete after remaining active time only");

        RestSession shapePause = new RestSession();
        shapePause.start(NOW, gate(safe()));
        shapePause.pause(NOW + 4_000);
        check(shapePause.expansion(NOW + 400_000) == 1, "pause freezes exact guided shape");
        shapePause.setReducedMotion(true);
        check(shapePause.expansion(NOW + 400_000) == 0.5, "reduced motion static expansion");

        RestSession clockRollback = new RestSession();
        clockRollback.start(NOW, gate(safe()));
        check(clockRollback.check(NOW - 1, gate(safe())) == RestSession.State.PAUSED, "clock rollback pauses");
        check(clockRollback.elapsedActiveMs(NOW + 1_000) == 0, "rollback adds no time");
        check(!new RestSession().start(NOW, gate(null)), "cannot start with unknown state");

        RestSession delayedFrame = new RestSession();
        delayedFrame.start(NOW, gate(safe()));
        delayedFrame.check(NOW + 175_000, gate(safe()));
        check(delayedFrame.elapsedActiveMs(NOW + 185_000) == 180_000, "delayed frame caps active time at 180 seconds");
        check(delayedFrame.state() == RestSession.State.COMPLETE, "clock observation commits completion without a render check");
        check(!delayedFrame.resume(NOW + 190_000, gate(safe())), "completed session cannot resume from a late callback");
        check(delayedFrame.check(NOW + 190_000, gate(null)) == RestSession.State.COMPLETE, "late blocker cannot resurrect completed session");
        check(!paused.resume(NOW + 300_000, gate(safe())), "ended session cannot resume from a late callback");
        check(paused.check(NOW + 300_000, gate(safe())) == RestSession.State.ENDED, "late recovery cannot resurrect ended session");

        RestSession multipleBlockers = new RestSession();
        multipleBlockers.start(NOW, gate(safe()));
        multipleBlockers.check(NOW + 2_000, gate(state(true, SafetyGate.Gear.DRIVE, 30.0, true, false, true, NOW)));
        check(!multipleBlockers.resume(NOW + 3_000, gate(state(true, SafetyGate.Gear.PARK, 0.0, true, false, true, NOW))), "parking recovery does not clear active call blocker");
        check(multipleBlockers.elapsedActiveMs(NOW + 12_000) == 2_000, "ten-second pause is excluded from active clock");
        System.out.println("PASS: " + checks + " safety and session assertions; real writes always denied.");
    }
}
