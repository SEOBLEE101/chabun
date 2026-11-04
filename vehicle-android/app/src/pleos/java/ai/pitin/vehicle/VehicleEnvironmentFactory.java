package ai.pitin.vehicle;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import ai.pleos.playground.vehicle.Vehicle;

/**
 * The Pleos build that changes the cabin (차 안 맞추기). Parking facts come from AAOS (gear, speed, UX restriction),
 * a call from the audio mode; cabin writes go through {@link PleosCabin} and only while {@link CabinControl#writeBlocked}
 * finds the car parked.
 */
final class VehicleEnvironmentFactory {
    static VehicleEnvironment create(Context context) { return new PleosEnvironment(context); }

    private static final class PleosEnvironment implements VehicleEnvironment {
        private final Vehicle vehicle;
        private final ParkingSignals parking;
        private final AudioManager audio;
        private final PleosCabin cabin;
        private String serviceNote = "";

        PleosEnvironment(Context context) {
            Context app = context.getApplicationContext();
            vehicle = new Vehicle(app);
            ParkingSignals availableParking = null;
            try {
                Class.forName("android.car.Car");
                availableParking = new ParkingSignals(app);
            } catch (ClassNotFoundException | LinkageError unavailable) {
                // Without the AAOS client library the car's gear and speed stay unknown, so nothing is changed.
            }
            parking = availableParking;
            audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            try { vehicle.initialize(); }
            catch (Exception e) { serviceNote = "Pleos Vehicle Service is unavailable. "; }
            ParkingSignals signals = parking;
            cabin = new PleosCabin(vehicle, AndroidCabinRuntime.scheduler(), AndroidCabinRuntime.store(app, "pleos"),
                    () -> signals != null && signals.driverOnRight());
        }

        public SafetyGate.Snapshot snapshot(long nowMs) {
            if (parking == null) return new SafetyGate.Snapshot(false, SafetyGate.Gear.UNKNOWN, null, null, null, false, 0);
            SafetyGate.Snapshot s = parking.snapshot(nowMs);
            return new SafetyGate.Snapshot(false, s.gear, s.speedKph, CallSignal.active(audio), s.uxRestricted, s.permissionVerified, s.sampledAtMs);
        }

        public void refresh() { if (parking != null) parking.refresh(); }

        public String description() {
            return "Pleos Vehicle 2.3.2 · cabin control while parked. " + serviceNote
                    + (parking != null ? parking.description().replace(" Live rest is disabled.", "")
                    : "AAOS state library unavailable: gear and speed unknown, so the cabin is never changed.");
        }

        public String[] requiredPermissions() {
            return new String[]{"android.car.permission.CAR_SPEED", "android.car.permission.CAR_POWERTRAIN",
                    "pleos.car.permission.CAR_CLIMATE", "pleos.car.permission.CONTROL_CAR_CLIMATE",
                    "pleos.car.permission.CAR_SEATS", "pleos.car.permission.CONTROL_CAR_SEATS",
                    "pleos.car.permission.CAR_LIGHTS", "pleos.car.permission.CONTROL_CAR_LIGHTS"};
        }

        public CabinActuator cabin() { return cabin; }

        public void close() {
            cabin.close();
            if (parking != null) parking.close();
            try { vehicle.release(); } catch (Exception ignored) { }
        }
    }
    private VehicleEnvironmentFactory() { }
}
