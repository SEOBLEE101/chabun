package ai.pitin.vehicle;

import android.car.Car;
import android.car.VehicleGear;
import android.car.VehiclePropertyIds;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** AAOS read-only signals recommended by current Pleos documentation. No call-state inference. */
final class ParkingSignals implements AutoCloseable {
    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean polling = new AtomicBoolean();
    private final Object lock = new Object();
    private volatile Car car;
    private volatile CarPropertyManager properties;
    private volatile CarUxRestrictionsManager restrictions;
    private volatile boolean closed;
    private volatile int generation;
    private SafetyGate.Gear gear = SafetyGate.Gear.UNKNOWN;
    private Double speedKph;
    private Boolean uxRestricted;
    private long gearAt, speedAt, uxAt;
    private boolean motionPermission;
    /** INFO_DRIVER_SEAT (a VehicleAreaSeat value), read once; null until known. */
    private volatile Integer driverSeat;

    /** True only when the car says the driver sits front right. */
    boolean driverOnRight() {
        Integer seat = driverSeat;
        return seat != null && seat == android.car.VehicleAreaSeat.SEAT_ROW_1_RIGHT;
    }

    ParkingSignals(Context context) {
        this.context = context.getApplicationContext();
        try {
            car = Car.createCar(this.context, new Handler(Looper.getMainLooper()),
                    Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, this::serviceChanged);
        } catch (Exception e) { invalidate(); }
    }

    private void serviceChanged(Car service, boolean ready) {
        final int serviceGeneration;
        synchronized (lock) {
            generation++;
            if (closed) return;
            serviceGeneration = generation;
            invalidate();
        }
        if (!ready) { properties = null; restrictions = null; return; }
        car = service;
        try {
            properties = (CarPropertyManager) service.getCarManager(Car.PROPERTY_SERVICE);
            restrictions = (CarUxRestrictionsManager) service.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
            if (restrictions != null) restrictions.registerListener(value -> applyUx(value, serviceGeneration));
        } catch (Exception e) { properties = null; restrictions = null; }
        refresh();
    }

    void refresh() {
        if (closed || !polling.compareAndSet(false, true)) return;
        final int request = generation;
        try {
            io.submit(() -> {
                try {
                    CarPropertyManager manager = properties;
                    SafetyGate.Gear nextGear = SafetyGate.Gear.UNKNOWN;
                    Double nextSpeed = null;
                    long nextGearAt = 0, nextSpeedAt = 0;
                    boolean powertrainAllowed = context.checkSelfPermission(Car.PERMISSION_POWERTRAIN)
                            == PackageManager.PERMISSION_GRANTED;
                    boolean speedAllowed = context.checkSelfPermission(Car.PERMISSION_SPEED)
                            == PackageManager.PERMISSION_GRANTED;
                    if (manager != null && powertrainAllowed) {
                        try {
                            if (readable(manager, VehiclePropertyIds.GEAR_SELECTION)) {
                                CarPropertyValue<Integer> value = manager.getProperty(Integer.class, VehiclePropertyIds.GEAR_SELECTION, 0);
                                if (available(value) && value.getValue() != null) {
                                    nextGear = mapGear(value.getValue());
                                    nextGearAt = SystemClock.elapsedRealtime();
                                }
                            }
                        } catch (Exception ignored) { }
                    }
                    if (manager != null && speedAllowed) {
                        try {
                            if (readable(manager, VehiclePropertyIds.PERF_VEHICLE_SPEED)) {
                                CarPropertyValue<Float> value = manager.getProperty(Float.class, VehiclePropertyIds.PERF_VEHICLE_SPEED, 0);
                                if (available(value) && value.getValue() != null && Float.isFinite(value.getValue())) {
                                    nextSpeed = value.getValue().doubleValue() * 3.6; // AAOS property unit: m/s.
                                    nextSpeedAt = SystemClock.elapsedRealtime();
                                }
                            }
                        } catch (Exception ignored) { }
                    }
                    if (manager != null && driverSeat == null) {
                        try {
                            if (readable(manager, VehiclePropertyIds.INFO_DRIVER_SEAT)) {
                                CarPropertyValue<Integer> value = manager.getProperty(Integer.class, VehiclePropertyIds.INFO_DRIVER_SEAT, 0);
                                if (available(value) && value.getValue() != null) driverSeat = value.getValue();
                            }
                        } catch (Exception ignored) { }
                    }
                    if (closed || request != generation) return;
                    synchronized (lock) {
                        if (closed || request != generation) return;
                        gear = nextGear; speedKph = nextSpeed;
                        gearAt = nextGearAt; speedAt = nextSpeedAt;
                        motionPermission = powertrainAllowed && speedAllowed;
                    }
                    CarUxRestrictionsManager ux = restrictions;
                    try { applyUx(ux == null ? null : ux.getCurrentCarUxRestrictions(), request); }
                    catch (Exception e) { applyUx(null, request); }
                } finally { polling.set(false); }
            });
        } catch (Exception e) { polling.set(false); invalidate(); }
    }

    private static boolean readable(CarPropertyManager manager, int id) {
        CarPropertyConfig<?> config = manager.getCarPropertyConfig(id);
        return config != null && (config.getAccess() == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ
                || config.getAccess() == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ_WRITE);
    }

    private static boolean available(CarPropertyValue<?> value) {
        return value != null && value.getStatus() == CarPropertyValue.STATUS_AVAILABLE;
    }

    private void applyUx(CarUxRestrictions value, int requestGeneration) {
        synchronized (lock) {
            if (closed || requestGeneration != generation) return;
            uxRestricted = value == null ? null : value.isRequiresDistractionOptimization()
                    || value.getActiveRestrictions() != CarUxRestrictions.UX_RESTRICTIONS_BASELINE;
            uxAt = value == null ? 0 : SystemClock.elapsedRealtime();
        }
    }

    SafetyGate.Snapshot snapshot(long now) {
        synchronized (lock) {
            return new SafetyGate.Snapshot(false, fresh(gearAt, now) ? gear : SafetyGate.Gear.UNKNOWN,
                    fresh(speedAt, now) ? speedKph : null, null,
                    fresh(uxAt, now) ? uxRestricted : null, motionPermission,
                    Math.min(gearAt, Math.min(speedAt, uxAt)));
        }
    }

    String description() {
        SafetyGate.Snapshot state = snapshot(SystemClock.elapsedRealtime());
        return "Gear: " + state.gear + "; speed: "
                + (state.speedKph == null ? "unknown" : String.format(Locale.ROOT, "%.1f km/h", state.speedKph))
                + "; UX restricted: " + (state.uxRestricted == null ? "unknown" : state.uxRestricted)
                + "; call state: unknown. Live rest is disabled.";
    }

    private static boolean fresh(long time, long now) { return time > 0 && now >= time && now - time < SafetyGate.MAX_STATE_AGE_MS; }

    private static SafetyGate.Gear mapGear(int value) {
        if (value == VehicleGear.GEAR_PARK) return SafetyGate.Gear.PARK;
        if (value == VehicleGear.GEAR_DRIVE) return SafetyGate.Gear.DRIVE;
        if (value == VehicleGear.GEAR_REVERSE) return SafetyGate.Gear.REVERSE;
        if (value == VehicleGear.GEAR_NEUTRAL) return SafetyGate.Gear.NEUTRAL;
        return SafetyGate.Gear.UNKNOWN;
    }

    private void invalidate() {
        synchronized (lock) {
            gear = SafetyGate.Gear.UNKNOWN; speedKph = null; uxRestricted = null;
            gearAt = speedAt = uxAt = 0; motionPermission = false;
        }
    }

    @Override public void close() {
        synchronized (lock) { closed = true; generation++; invalidate(); }
        io.shutdownNow();
        try { if (restrictions != null) restrictions.unregisterListener(); } catch (Exception ignored) { }
        try { if (car != null) car.disconnect(); } catch (Exception ignored) { }
        invalidate();
    }
}
