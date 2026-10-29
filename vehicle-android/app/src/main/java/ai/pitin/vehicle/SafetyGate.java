package ai.pitin.vehicle;

/** Pure Java policy. No Android or Pleos dependency; tested by test-core.sh. */
public final class SafetyGate {
    public static final long MAX_STATE_AGE_MS = 5_000;

    public enum Gear { UNKNOWN, PARK, DRIVE, REVERSE, NEUTRAL }

    public static final class Snapshot {
        public final boolean simulation;
        public final Gear gear;
        public final Double speedKph;
        public final Boolean callActive;
        public final Boolean uxRestricted;
        public final boolean permissionVerified;
        public final long sampledAtMs;

        public Snapshot(boolean simulation, Gear gear, Double speedKph,
                        Boolean callActive, Boolean uxRestricted,
                        boolean permissionVerified, long sampledAtMs) {
            this.simulation = simulation;
            this.gear = gear == null ? Gear.UNKNOWN : gear;
            this.speedKph = speedKph;
            this.callActive = callActive;
            this.uxRestricted = uxRestricted;
            this.permissionVerified = permissionVerified;
            this.sampledAtMs = sampledAtMs;
        }
    }

    public static final class Decision {
        public final boolean previewAllowed;
        public final String reason;
        private Decision(boolean allowed, String reason) {
            this.previewAllowed = allowed;
            this.reason = reason;
        }
    }

    public static Decision preview(Snapshot state, long nowMs, boolean localChoiceAvailable,
                                   boolean locallyConfirmed) {
        if (!localChoiceAvailable) return deny("Choose a comfort preset here; workout data is optional.");
        if (!locallyConfirmed) return deny("Choose a preset and confirm on this screen.");
        if (state == null) return deny("Vehicle state is unknown.");
        long age = nowMs - state.sampledAtMs;
        if (age < 0 || age >= MAX_STATE_AGE_MS) return deny("Vehicle state is stale or invalid.");
        if (state.gear != Gear.PARK) return deny("Park state is not verified.");
        if (state.speedKph == null || !Double.isFinite(state.speedKph)
                || state.speedKph != 0.0) return deny("Zero speed is not verified.");
        if (!Boolean.FALSE.equals(state.callActive)) return deny("Call state is active or unknown.");
        if (!Boolean.FALSE.equals(state.uxRestricted)) return deny("UX restriction state is active or unknown.");
        if (!state.permissionVerified) return deny("Required state access is not verified.");
        if (!state.simulation) return deny("This build is read only; a live rest-session adapter is not implemented.");
        return new Decision(true, "Simulated parked state verified. Demo preview only.");
    }

    /** No combination of phone data, user confirmation or demo state enables a real write. */
    public static boolean permitsVehicleWrite(Snapshot state) { return false; }

    private static Decision deny(String reason) { return new Decision(false, reason); }
    private SafetyGate() { }
}
