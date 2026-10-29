package ai.pitin.vehicle;

import android.content.Context;
import android.os.SystemClock;

final class VehicleEnvironmentFactory {
    static VehicleEnvironment create(Context context) { return new DemoEnvironment(context.getApplicationContext()); }

    private static final class DemoEnvironment implements VehicleEnvironment {
        private final Context context;
        private String scenario = "unknown";
        private long sampledAtMs = SystemClock.elapsedRealtime();
        private CabinActuator cabin;

        DemoEnvironment(Context context) { this.context = context; }
        public SafetyGate.Snapshot snapshot(long nowMs) {
            boolean parked = scenario.equals("parked") || scenario.equals("call") || scenario.equals("restricted");
            boolean known = !scenario.equals("unknown");
            return new SafetyGate.Snapshot(true,
                    parked ? SafetyGate.Gear.PARK : known ? SafetyGate.Gear.DRIVE : SafetyGate.Gear.UNKNOWN,
                    known ? parked ? 0.0 : 30.0 : null,
                    known ? scenario.equals("call") : null,
                    known ? scenario.equals("restricted") : null,
                    known, sampledAtMs);
        }
        public void refresh() { sampledAtMs = SystemClock.elapsedRealtime(); }
        public void simulate(String value) { scenario = value; refresh(); }
        public String description() { return "Synthetic vehicle state: " + scenario + ". Cabin changes go to a pretend car only."; }
        /** A pretend car, so the demo can show 요청함 → 확인됨 without touching a vehicle. */
        public CabinActuator cabin() {
            // The pretend car forgets its values with the process, so its ledger is not kept either.
            if (cabin == null) cabin = new CabinActuator.Simulated(AndroidCabinRuntime.scheduler(), null);
            return cabin;
        }
        public void close() { if (cabin != null) cabin.close(); }
    }
    private VehicleEnvironmentFactory() { }
}
