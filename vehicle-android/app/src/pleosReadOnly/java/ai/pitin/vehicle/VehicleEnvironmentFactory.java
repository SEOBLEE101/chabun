package ai.pitin.vehicle;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.api.model.area.SeatArea;
import ai.pleos.playground.vehicle.api.model.capability.AccessType;
import ai.pleos.playground.vehicle.constant.area.SeatValue;
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit;
import kotlin.Unit;

/** Optional, read-only integration with the verified Vehicle 2.3.2 AAR. */
final class VehicleEnvironmentFactory {
    static VehicleEnvironment create(Context context) { return new PleosReadOnlyEnvironment(context); }

    private static final class PleosReadOnlyEnvironment implements VehicleEnvironment {
        private final Context context;
        private final Vehicle vehicle;
        private final ParkingSignals parking;
        private final SeatArea zone = new SeatArea(SeatValue.ROW_1_LEFT);
        private volatile boolean closed;
        private volatile long requestStartedAt;
        private volatile long readAt;
        private volatile int generation;
        private volatile String readout = "Climate data has not been read.";

        PleosReadOnlyEnvironment(Context context) {
            this.context = context.getApplicationContext();
            vehicle = new Vehicle(this.context);
            ParkingSignals availableParking = null;
            try {
                Class.forName("android.car.Car");
                availableParking = new ParkingSignals(this.context);
            } catch (ClassNotFoundException | LinkageError unavailable) {
                // Some Pleos images expose Vehicle SDK without the optional AAOS client library.
            }
            parking = availableParking;
            try { vehicle.initialize(); }
            catch (Exception e) { readout = "Pleos Vehicle Service is unavailable."; }
        }

        public SafetyGate.Snapshot snapshot(long nowMs) {
            return parking != null ? parking.snapshot(nowMs)
                    : new SafetyGate.Snapshot(false, SafetyGate.Gear.UNKNOWN,
                            null, null, null, false, 0);
        }

        public void refresh() {
            if (closed) return;
            if (parking != null) parking.refresh();
            long now = SystemClock.elapsedRealtime();
            if (requestStartedAt != 0 && now - requestStartedAt < 4_000) return;
            if (context.checkSelfPermission("pleos.car.permission.CAR_CLIMATE")
                    != PackageManager.PERMISSION_GRANTED) {
                readout = "Climate read permission is not granted.";
                return;
            }
            requestStartedAt = now;
            final int request = ++generation;
            try {
                // Current three-argument callback includes access type and temperature configuration.
                vehicle.getHVAC().checkHvacTemperatureCapabilities((config, ranges, temperatureConfig) -> {
                    if (closed || request != generation) return Unit.INSTANCE;
                    if (config == null || ranges == null || !ranges.containsKey(zone)
                            || !(config.getAccessType() == AccessType.READ
                            || config.getAccessType() == AccessType.READ_WRITE)) {
                        fail(request, "Front-left temperature read is unsupported.");
                        return Unit.INSTANCE;
                    }
                    vehicle.getHVAC().getHvacTemperature(zone, TemperatureUnit.CELSIUS, value -> {
                        if (!closed && request == generation) {
                            readout = value != null && Float.isFinite(value)
                                    ? "Front-left set temperature: " + value + " °C (read only)."
                                    : "Temperature value is unavailable.";
                            readAt = SystemClock.elapsedRealtime();
                            requestStartedAt = 0;
                        }
                        return Unit.INSTANCE;
                    }, error -> { fail(request, "Climate read failed."); return Unit.INSTANCE; });
                    return Unit.INSTANCE;
                }, error -> { fail(request, "Climate capability read failed."); return Unit.INSTANCE; });
            } catch (Exception e) { fail(request, "Pleos Vehicle Service is unavailable."); }
        }

        private void fail(int request, String message) {
            if (closed || request != generation) return;
            readout = message;
            readAt = 0;
            requestStartedAt = 0;
        }

        public String description() {
            String current = readout;
            if (readAt != 0 && SystemClock.elapsedRealtime() - readAt >= 5_000)
                current = "Climate read is stale.";
            return "Pleos Vehicle 2.3.2 · read only. " + current
                    + "\n" + (parking != null ? parking.description()
                    : "AAOS state library unavailable on this image. Gear, speed, UX and call state: unknown. Live rest is disabled.");
        }

        public String[] requiredPermissions() {
            return parking == null ? new String[]{"pleos.car.permission.CAR_CLIMATE"}
                    : new String[]{"pleos.car.permission.CAR_CLIMATE",
                            "android.car.permission.CAR_SPEED", "android.car.permission.CAR_POWERTRAIN"};
        }

        public void close() {
            closed = true;
            generation++;
            if (parking != null) parking.close();
            try { vehicle.release(); } catch (Exception ignored) { }
        }
    }
    private VehicleEnvironmentFactory() { }
}
