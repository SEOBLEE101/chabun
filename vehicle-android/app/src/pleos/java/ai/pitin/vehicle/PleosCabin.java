package ai.pitin.vehicle;

import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.api.HVAC;
import ai.pleos.playground.vehicle.api.Light;
import ai.pleos.playground.vehicle.api.Seat;
import ai.pleos.playground.vehicle.api.model.area.SeatArea;
import ai.pleos.playground.vehicle.api.model.capability.AccessType;
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds;
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig;
import ai.pleos.playground.vehicle.constant.area.SeatValue;
import ai.pleos.playground.vehicle.constant.control.FanDirectionType;
import ai.pleos.playground.vehicle.constant.control.LightSwitch;
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/**
 * Pleos Vehicle SDK 2.3.2 writes for 차 안 맞추기: climate (power, auto, temperature, fan, direction, A/C), seat
 * heating/ventilation and ambient light. Seats never move. Each item is checked against the car's capabilities first
 * and read back after the write; the SDK's own completion callback is not taken as proof.
 */
final class PleosCabin extends CabinActuator {
    private static final FanDirectionType[] DIRECTIONS = {FanDirectionType.UNKNOWN, FanDirectionType.FACE,
            FanDirectionType.FLOOR, FanDirectionType.FACE_AND_FLOOR, FanDirectionType.DEFROST,
            FanDirectionType.FACE_AND_DEFROST, FanDirectionType.DEFROST_AND_FLOOR, FanDirectionType.FACE_AND_FLOOR_AND_DEFROST};

    private final Vehicle vehicle;
    private final java.util.function.BooleanSupplier driverOnRight;
    /** The car's zone for each step key, learned from its capabilities. */
    private final Map<String, SeatArea> zones = new java.util.concurrent.ConcurrentHashMap<>(); // SDK callbacks write, the main thread reads
    /** Items whose zone was asked for once in this process (found or not), so a read asks at most once. */
    private final java.util.Set<String> zoneAsked = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Scheduler clock;

    /** {@code driverOnRight}: the car's INFO_DRIVER_SEAT says the driver sits on the right (false when unknown). */
    PleosCabin(Vehicle vehicle, Scheduler clock, Store store, java.util.function.BooleanSupplier driverOnRight) {
        super(clock, store);
        this.clock = clock;
        this.vehicle = vehicle;
        this.driverOnRight = driverOnRight;
    }

    private SeatValue seatOf(boolean driver) {
        boolean right = driverOnRight.getAsBoolean();
        return driver == right ? SeatValue.ROW_1_RIGHT : SeatValue.ROW_1_LEFT;
    }

    @Override boolean live() { return true; }

    // ── capabilities: which zone, which range ─────────────────────────

    @Override void resolve(CabinControl.Step s, Answer out) {
        HVAC h = vehicle.getHVAC();
        Seat seat = vehicle.getSeat();
        Light light = vehicle.getLight();
        Function1<Exception, Unit> fail = e -> { out.value(null); return Unit.INSTANCE; };
        switch (s.item) {
            case CLIMATE_POWER:
                h.checkHvacPowerCapabilities((CapabilityConfig c, List<SeatArea> list) -> unit(() -> same(s, c, list, out)), fail); break;
            case CLIMATE_AUTO:
                h.checkHvacAutoCapabilities((CapabilityConfig c, List<SeatArea> list) -> unit(() -> same(s, c, list, out)), fail); break;
            case AC:
                h.checkHvacAcCapabilities((CapabilityConfig c, List<SeatArea> list) -> unit(() -> same(s, c, list, out)), fail); break;
            case TEMPERATURE:
                h.checkHvacTemperatureCapabilities((CapabilityConfig c, Map<SeatArea, AreaBounds<Float>> m) -> unit(() -> {
                    SeatArea zone = zone(s, c, m == null ? null : m.keySet());
                    if (zone == null) { out.value(UNSUPPORTED); return; }
                    AreaBounds<Float> b = m.get(zone);
                    int low = (int) Math.ceil(b.getMinValue() * 10), high = (int) Math.floor(b.getMaxValue() * 10);
                    out.value(Math.max(low, Math.min(high, s.value)));
                }), fail); break;
            case FAN:
                h.checkHvacFanSpeedCapabilities((CapabilityConfig c, Map<SeatArea, AreaBounds<Integer>> m) -> unit(() -> {
                    SeatArea zone = zone(s, c, m == null ? null : m.keySet());
                    if (zone == null) { out.value(UNSUPPORTED); return; }
                    out.value(CabinControl.fanSpeed(s.value, m.get(zone).getMinValue(), m.get(zone).getMaxValue()));
                }), fail); break;
            case DIRECTION:
                h.checkHvacFanDirectionCapabilities((CapabilityConfig c, Map<SeatArea, ? extends List<? extends FanDirectionType>> m) -> unit(() -> {
                    SeatArea zone = zone(s, c, m == null ? null : m.keySet());
                    FanDirectionType wanted = DIRECTIONS[s.value];
                    out.value(zone != null && m.get(zone).contains(wanted) ? s.value : UNSUPPORTED);
                }), fail); break;
            case SEAT_HEAT:
                seat.checkSeatTemperatureCapabilities((CapabilityConfig c, Map<SeatArea, AreaBounds<Integer>> m) -> unit(() -> level(s, c, m, out)), fail); break;
            case SEAT_VENT:
                seat.checkSeatVentilationCapabilities((CapabilityConfig c, Map<SeatArea, AreaBounds<Integer>> m) -> unit(() -> level(s, c, m, out)), fail); break;
            case LIGHT_SWITCH:
                light.checkAmbientLightSwitchCapabilities((CapabilityConfig c, Map<SeatArea, ? extends List<? extends LightSwitch>> m) -> unit(() -> {
                    SeatArea zone = zone(s, c, m == null ? null : m.keySet());
                    LightSwitch wanted = s.value == 1 ? LightSwitch.ON : LightSwitch.OFF;
                    out.value(zone != null && m.get(zone).contains(wanted) ? s.value : UNSUPPORTED);
                }), fail); break;
            case LIGHT_BRIGHTNESS:
                light.checkAmbientLightBrightnessCapabilities((CapabilityConfig c, Map<SeatArea, AreaBounds<Integer>> m) -> unit(() -> {
                    SeatArea zone = zone(s, c, m == null ? null : m.keySet());
                    if (zone == null) { out.value(UNSUPPORTED); return; }
                    out.value(CabinControl.brightness(s.value, m.get(zone).getMinValue(), m.get(zone).getMaxValue()));
                }), fail); break;
            case LIGHT_COLOR:
                light.checkAmbientLightBrightnessColorCapabilities((CapabilityConfig c, List<SeatArea> list) -> unit(() -> same(s, c, list, out)), fail); break;
            default: out.value(UNSUPPORTED);
        }
    }

    private void same(CabinControl.Step s, CapabilityConfig c, Collection<SeatArea> list, Answer out) {
        out.value(zone(s, c, list) == null ? UNSUPPORTED : s.value);
    }

    private void level(CabinControl.Step s, CapabilityConfig c, Map<SeatArea, AreaBounds<Integer>> m, Answer out) {
        SeatArea zone = zone(s, c, m == null ? null : m.keySet());
        if (zone == null) { out.value(UNSUPPORTED); return; }
        int v = CabinControl.seatLevel(s.value, m.get(zone).getMinValue(), m.get(zone).getMaxValue());
        out.value(v < 0 ? UNSUPPORTED : v);
    }

    /** The writable zone that covers the seat (light: any listed zone), remembered for the read and write. */
    private SeatArea zone(CabinControl.Step s, CapabilityConfig c, Collection<? extends SeatArea> zones) {
        if (c == null || !(c.getAccessType() == AccessType.WRITE || c.getAccessType() == AccessType.READ_WRITE)
                || zones == null || zones.isEmpty()) return null;
        SeatArea found;
        if ("light".equals(s.group())) found = zones.iterator().next();
        else {
            found = covering(zones, seatOf(s.driver));
            // One zone for both front seats: only the driver's value is sent, the passenger's is not on this car.
            if (found != null && !s.driver && found.equals(covering(zones, seatOf(true)))) found = null;
        }
        if (found != null) this.zones.put(s.key(), found);
        return found;
    }

    private static SeatArea covering(Collection<? extends SeatArea> zones, SeatValue seat) {
        for (SeatArea z : zones) for (SeatValue v : z.getValues()) if (v == seat) return z;
        return null;
    }

    /**
     * Asks the car which zone this item uses, then runs {@code then} on the scheduler. A restore after a restart
     * (and the first ‘지금 차’ read) comes here before any {@link #resolve}: without it the guessed zone makes the
     * light and fan unreadable, and those items could never be put back.
     */
    private void learnZone(CabinControl.Step s, Runnable then) {
        HVAC h = vehicle.getHVAC();
        Seat seat = vehicle.getSeat();
        Light light = vehicle.getLight();
        Runnable next = () -> clock.post(then, 0);
        Function1<Exception, Unit> fail = e -> { next.run(); return Unit.INSTANCE; };
        switch (s.item) {
            case CLIMATE_POWER:
                h.checkHvacPowerCapabilities((CapabilityConfig c, List<SeatArea> list) -> learned(s, c, list, next), fail); break;
            case CLIMATE_AUTO:
                h.checkHvacAutoCapabilities((CapabilityConfig c, List<SeatArea> list) -> learned(s, c, list, next), fail); break;
            case AC:
                h.checkHvacAcCapabilities((CapabilityConfig c, List<SeatArea> list) -> learned(s, c, list, next), fail); break;
            case TEMPERATURE:
                h.checkHvacTemperatureCapabilities((CapabilityConfig c, Map<SeatArea, AreaBounds<Float>> m) -> learned(s, c, m == null ? null : m.keySet(), next), fail); break;
            case FAN:
                h.checkHvacFanSpeedCapabilities((CapabilityConfig c, Map<SeatArea, AreaBounds<Integer>> m) -> learned(s, c, m == null ? null : m.keySet(), next), fail); break;
            case DIRECTION:
                h.checkHvacFanDirectionCapabilities((CapabilityConfig c, Map<SeatArea, ? extends List<? extends FanDirectionType>> m) -> learned(s, c, m == null ? null : m.keySet(), next), fail); break;
            case SEAT_HEAT:
                seat.checkSeatTemperatureCapabilities((CapabilityConfig c, Map<SeatArea, AreaBounds<Integer>> m) -> learned(s, c, m == null ? null : m.keySet(), next), fail); break;
            case SEAT_VENT:
                seat.checkSeatVentilationCapabilities((CapabilityConfig c, Map<SeatArea, AreaBounds<Integer>> m) -> learned(s, c, m == null ? null : m.keySet(), next), fail); break;
            case LIGHT_SWITCH:
                light.checkAmbientLightSwitchCapabilities((CapabilityConfig c, Map<SeatArea, ? extends List<? extends LightSwitch>> m) -> learned(s, c, m == null ? null : m.keySet(), next), fail); break;
            case LIGHT_BRIGHTNESS:
                light.checkAmbientLightBrightnessCapabilities((CapabilityConfig c, Map<SeatArea, AreaBounds<Integer>> m) -> learned(s, c, m == null ? null : m.keySet(), next), fail); break;
            case LIGHT_COLOR:
                light.checkAmbientLightBrightnessColorCapabilities((CapabilityConfig c, List<SeatArea> list) -> learned(s, c, list, next), fail); break;
            default: next.run();
        }
    }

    /** Remembers the zone (when the car names one) and always goes on, whatever the car answered. */
    private Unit learned(CabinControl.Step s, CapabilityConfig c, Collection<? extends SeatArea> zones, Runnable next) {
        try { zone(s, c, zones); }
        catch (RuntimeException e) { CabinActuator.log.accept("zone of " + s.key() + " not learned: " + e); }
        next.run();
        return Unit.INSTANCE;
    }

    private SeatArea zoneFor(CabinControl.Step s) {
        SeatArea z = zones.get(s.key());
        return z != null ? z : new SeatArea("light".equals(s.group()) ? SeatValue.ROW_1_LEFT : seatOf(s.driver));
    }

    // ── read and write ────────────────────────────────────────────────

    @Override void read(CabinControl.Step s, Answer out) {
        if (!zones.containsKey(s.key()) && zoneAsked.add(s.key())) { learnZone(s, () -> read(s, out)); return; }
        HVAC h = vehicle.getHVAC();
        Seat seat = vehicle.getSeat();
        Light light = vehicle.getLight();
        SeatArea z = zoneFor(s);
        Function1<Exception, Unit> fail = e -> { out.value(null); return Unit.INSTANCE; };
        switch (s.item) {
            case CLIMATE_POWER: h.getHvacPowerState(z, (Boolean on) -> unit(() -> out.value(bit(on))), fail); break;
            case CLIMATE_AUTO: h.getHvacAutoState(z, (Boolean on) -> unit(() -> out.value(bit(on))), fail); break;
            case AC: h.getHvacAcState(z, (Boolean on) -> unit(() -> out.value(bit(on))), fail); break;
            case TEMPERATURE: h.getHvacTemperature(z, TemperatureUnit.CELSIUS, (Float t) -> unit(() -> out.value(tenths(t))), fail); break;
            case FAN: h.getHvacFanSpeed(z, (Integer v) -> unit(() -> out.value(v)), fail); break;
            case DIRECTION: h.getHvacFanDirection(z, (FanDirectionType t) -> unit(() -> out.value(code(t))), fail); break;
            case SEAT_HEAT: seat.getHvacSeatTemperature(z, (Integer v) -> unit(() -> out.value(v)), fail); break;
            case SEAT_VENT: seat.getHvacSeatVentilationState(z, (Integer v) -> unit(() -> out.value(v)), fail); break;
            case LIGHT_SWITCH: light.getAmbientLightSwitch(z, (LightSwitch v) -> unit(() -> out.value(switchBit(v))), fail); break;
            case LIGHT_BRIGHTNESS: light.getAmbientLightBrightness(z, (Integer v) -> unit(() -> out.value(v)), fail); break;
            case LIGHT_COLOR: light.getAmbientLightBrightnessColor(z, (Integer v) -> unit(() -> out.value(v == null ? null : v & 0xFFFFFF)), fail); break;
            default: out.value(null);
        }
    }

    @Override void write(CabinControl.Step s, int raw, Answer out) {
        HVAC h = vehicle.getHVAC();
        Seat seat = vehicle.getSeat();
        Light light = vehicle.getLight();
        SeatArea z = zoneFor(s);
        // The SDK's failure callback is a definite refusal; the actuator still reads the car to decide.
        Function1<Exception, Unit> fail = e -> { out.value(REFUSED); return Unit.INSTANCE; };
        switch (s.item) {
            case CLIMATE_POWER: h.setHvacPower(z, raw == 1, (SeatArea a, Boolean on) -> unit(() -> out.value(bit(on))), fail); break;
            case CLIMATE_AUTO: h.setHvacAuto(z, raw == 1, (SeatArea a, Boolean on) -> unit(() -> out.value(bit(on))), fail); break;
            case AC: h.setHvacAc(z, raw == 1, (SeatArea a, Boolean on) -> unit(() -> out.value(bit(on))), fail); break;
            case TEMPERATURE: h.setHvacTemperature(z, raw / 10f, TemperatureUnit.CELSIUS,
                    (SeatArea a, Float t) -> unit(() -> out.value(tenths(t))), fail); break;
            case FAN: h.setHvacFanSpeed(z, raw, (SeatArea a, Integer v) -> unit(() -> out.value(v)), fail); break;
            case DIRECTION:
                if (raw <= 0 || raw >= DIRECTIONS.length) { out.value(REFUSED); break; }
                h.setHvacFanDirection(z, DIRECTIONS[raw], (SeatArea a, FanDirectionType t) -> unit(() -> out.value(code(t))), fail); break;
            case SEAT_HEAT: seat.setHvacSeatTemperature(z, raw, (SeatArea a, Integer v) -> unit(() -> out.value(v)), fail); break;
            case SEAT_VENT: seat.setHvacSeatVentilation(z, raw, (SeatArea a, Integer v) -> unit(() -> out.value(v)), fail); break;
            case LIGHT_SWITCH: light.setAmbientLightSwitch(z, raw == 1 ? LightSwitch.ON : LightSwitch.OFF,
                    (SeatArea a, LightSwitch v) -> unit(() -> out.value(switchBit(v))), fail); break;
            case LIGHT_BRIGHTNESS: light.setAmbientLightBrightness(z, raw, (SeatArea a, Integer v) -> unit(() -> out.value(v)), fail); break;
            case LIGHT_COLOR: light.setAmbientLightBrightnessColor(z, raw, (SeatArea a, Integer v) -> unit(() -> out.value(v)), fail); break;
            default: out.value(null);
        }
    }

    @Override boolean same(CabinControl.Step step, int asked, Integer reported) {
        if (step.item == CabinControl.Item.LIGHT_COLOR) return reported != null && (asked & 0xFFFFFF) == (reported & 0xFFFFFF);
        return super.same(step, asked, reported);
    }

    /** A failing callback body must not take the SDK's thread down; the actuator's call timeout then answers null. */
    private static Unit unit(Runnable r) {
        try { r.run(); } catch (RuntimeException e) { CabinActuator.log.accept("car callback failed: " + e); }
        return Unit.INSTANCE;
    }
    private static Integer bit(Boolean on) { return on == null ? null : on ? 1 : 0; }
    private static Integer tenths(Float t) { return t == null || !Float.isFinite(t) ? null : Math.round(t * 10); }
    private static Integer switchBit(LightSwitch v) { return v == LightSwitch.ON ? Integer.valueOf(1) : v == LightSwitch.OFF ? Integer.valueOf(0) : null; }

    private static Integer code(FanDirectionType t) {
        if (t == null) return null;
        for (int i = 0; i < DIRECTIONS.length; i++) if (DIRECTIONS[i] == t) return i;
        return null;
    }
}
