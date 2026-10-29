package ai.pitin.vehicle;

import java.util.List;

/** 차 안 맞추기 (spec 6-2-1): plans, the parked write gate, voice values and the car's ranges. */
public final class CabinControlTest {
    private static int assertions;

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    private static SafetyGate.Snapshot state(SafetyGate.Gear gear, Double speed, boolean verified, long at) {
        return new SafetyGate.Snapshot(false, gear, speed, null, false, verified, at);
    }

    private static boolean has(List<CabinControl.Step> steps, CabinControl.Item item, boolean driver, int value) {
        for (CabinControl.Step s : steps) if (s.item == item && s.driver == driver && s.value == value) return true;
        return false;
    }

    private static int index(List<CabinControl.Step> steps, CabinControl.Item item) {
        for (int i = 0; i < steps.size(); i++) if (steps.get(i).item == item) return i;
        return -1;
    }

    /** 차 안 설정 with the driver's climate set to 21.0°. */
    private static CabinSettings s0() {
        CabinSettings s = new CabinSettings();
        CabinSettings.setClimateMode(s.driverClimate, "custom");
        CabinSettings.stepTemp(s.driverClimate, -6);
        return s;
    }

    public static void main(String[] args) {
        long now = 100_000;
        // Gate: only parked, stopped, fresh and verified.
        check(CabinControl.writeBlocked(state(SafetyGate.Gear.PARK, 0.0, true, now - 100), now) == null, "parked allows");
        check(CabinControl.writeBlocked(null, now) != null, "unknown state blocks");
        check(CabinControl.writeBlocked(state(SafetyGate.Gear.DRIVE, 0.0, true, now), now).contains("P"), "drive blocks");
        check(CabinControl.writeBlocked(state(SafetyGate.Gear.PARK, 3.0, true, now), now) != null, "moving blocks");
        check(CabinControl.writeBlocked(state(SafetyGate.Gear.PARK, null, true, now), now) != null, "unknown speed blocks");
        check(CabinControl.writeBlocked(state(SafetyGate.Gear.PARK, 0.0, false, now), now) != null, "unverified blocks");
        check(CabinControl.writeBlocked(state(SafetyGate.Gear.PARK, 0.0, true, now - SafetyGate.MAX_STATE_AGE_MS), now) != null, "stale blocks");
        check(CabinControl.writeBlocked(state(SafetyGate.Gear.PARK, 0.0, true, now + 1), now) != null, "future sample blocks");
        check(CabinControl.writeBlocked(new SafetyGate.Snapshot(false, SafetyGate.Gear.PARK, 0.0, true, false, true, now), now) == null,
                "a call does not stop a parked climate change");
        check(CabinControl.writeBlocked(new SafetyGate.Snapshot(false, SafetyGate.Gear.PARK, 0.0, false, true, true, now), now) != null,
                "a UX restriction blocks");
        check(CabinControl.writeBlocked(new SafetyGate.Snapshot(false, SafetyGate.Gear.PARK, 0.0, false, null, true, now), now) != null,
                "an unknown UX state blocks");

        // Live rest: SafetyGate still judges real values; only the read-only switch is lifted.
        SafetyGate.Snapshot live = new SafetyGate.Snapshot(false, SafetyGate.Gear.PARK, 0.0, false, false, true, now);
        check(!SafetyGate.preview(live, now, true, true).previewAllowed, "read-only build stays blocked");
        check(SafetyGate.preview(CabinControl.forLiveRest(live), now, true, true).previewAllowed, "live adapter passes when parked");
        SafetyGate.Snapshot driving = new SafetyGate.Snapshot(false, SafetyGate.Gear.DRIVE, 0.0, false, false, true, now);
        check(!SafetyGate.preview(CabinControl.forLiveRest(driving), now, true, true).previewAllowed, "live adapter still needs P");
        SafetyGate.Snapshot calling = new SafetyGate.Snapshot(false, SafetyGate.Gear.PARK, 0.0, null, false, true, now);
        check(!SafetyGate.preview(CabinControl.forLiveRest(calling), now, true, true).previewAllowed, "unknown call state blocks a live rest");
        check(CabinControl.forLiveRest(null) == null, "null stays null");

        // Plans: keep means untouched.
        CabinSettings keep = new CabinSettings(); // defaults: every group follows the rest (쉼 따르기)
        check(CabinControl.plan(keep).isEmpty(), "following no rest plans nothing");
        // 그대로 really keeps: no power, auto or fan write even with a rest chosen.
        CabinSettings still = new CabinSettings();
        for (boolean d : new boolean[]{true, false}) { still.seat(d).comfort = "keep"; still.climate(d).mode = "keep"; }
        still.light.mode = "keep";
        check(CabinControl.plan(still, "quiet_rest", new CabinControl.Shared(225, "high"), keep).isEmpty(), "keep keeps everything");
        // The phone's 차 안 설정 for the rest (shared cabin) fills the groups that follow the rest.
        CabinSettings phone = new CabinSettings();
        for (boolean d : new boolean[]{true, false}) { phone.seat(d).comfort = "keep"; phone.climate(d).mode = "keep"; }
        phone.light.mode = "keep";
        phone.driverSeat.comfort = "vent"; phone.driverSeat.level = 2;
        phone.light.mode = "off";
        List<CabinControl.Step> fromPhone = CabinControl.plan(keep, "quiet_rest", new CabinControl.Shared(230, "keep"), phone);
        check(has(fromPhone, CabinControl.Item.SEAT_VENT, true, 2) && has(fromPhone, CabinControl.Item.LIGHT_SWITCH, true, 0)
                && has(fromPhone, CabinControl.Item.TEMPERATURE, true, 230) && !has(fromPhone, CabinControl.Item.FAN, true, CabinControl.FAN_LOW),
                "phone seat, light and the shared rest's temperature: " + fromPhone);
        check(CabinControl.followText("seat", true, "quiet_rest", null, phone).equals("시트 통풍 2단계"), "follow words: seat");
        check(CabinControl.followText("light", true, "quiet_rest", null, null).isEmpty(), "follow words: nothing");
        check(CabinControl.followText("climate", true, "quiet_rest", null, null).contains("24.0°"), "follow words: rest climate");
        // A car's own value still wins over the phone's.
        CabinSettings own = new CabinSettings();
        own.driverSeat.comfort = "heat"; own.driverSeat.level = 1;
        check(has(CabinControl.plan(own, "quiet_rest", null, phone), CabinControl.Item.SEAT_HEAT, true, 1), "own seat wins");
        List<CabinControl.Step> quiet = CabinControl.plan(keep, "quiet_rest");
        check(quiet.size() == 4 && quiet.get(0).item == CabinControl.Item.CLIMATE_POWER, "rest climate starts with power");
        check(has(quiet, CabinControl.Item.TEMPERATURE, true, 240) && has(quiet, CabinControl.Item.FAN, true, CabinControl.FAN_LOW), "quiet rest 24.0 low");
        check(has(quiet, CabinControl.Item.CLIMATE_AUTO, true, 0), "auto off before a fixed fan");
        List<CabinControl.Step> gentle = CabinControl.plan(keep, "gentle_refresh");
        check(gentle.size() == 2 && has(gentle, CabinControl.Item.TEMPERATURE, true, 250), "gentle refresh leaves the fan");
        check(CabinControl.plan(keep, "unknown").isEmpty(), "unknown rest plans nothing");
        // A preset shared from the phone replaces the rest's own climate; its "keep" leaves items alone.
        List<CabinControl.Step> sharedRest = CabinControl.plan(keep, "quiet_rest", new CabinControl.Shared(225, "high"));
        check(has(sharedRest, CabinControl.Item.TEMPERATURE, true, 225) && has(sharedRest, CabinControl.Item.FAN, true, CabinControl.FAN_HIGH)
                && !has(sharedRest, CabinControl.Item.TEMPERATURE, true, 240), "phone values win");
        List<CabinControl.Step> phoneTemp = CabinControl.plan(keep, "cool_down", new CabinControl.Shared(230, "keep"));
        check(phoneTemp.size() == 2 && has(phoneTemp, CabinControl.Item.TEMPERATURE, true, 230), "phone keeps the fan");
        check(CabinControl.plan(keep, "quiet_rest", new CabinControl.Shared(null, "keep")).isEmpty(), "phone keeps everything");
        check(CabinControl.plan(s0(), "quiet_rest", new CabinControl.Shared(225, "low")).stream()
                .anyMatch(x -> x.item == CabinControl.Item.TEMPERATURE && x.value == 210), "own 차 안 설정 still wins over the phone");

        CabinSettings s = new CabinSettings();
        CabinSettings.setClimateMode(s.driverClimate, "custom");
        CabinSettings.stepTemp(s.driverClimate, -4); // 22.0
        CabinSettings.setFan(s.driverClimate, "medium");
        CabinSettings.setDirection(s.driverClimate, "lower");
        CabinSettings.setAc(s.driverClimate, "off");
        CabinSettings.setSeatComfort(s.driverSeat, "vent");
        CabinSettings.setSeatLevel(s.driverSeat, 2);
        CabinSettings.setSeatComfort(s.passengerSeat, "heat");
        CabinSettings.setSeatPosition(s.passengerSeat, "manual");
        CabinSettings.setLightMode(s.light, "custom");
        CabinSettings.setColor(s.light, "sage");
        CabinSettings.setBright(s.light, 40);
        List<CabinControl.Step> p = CabinControl.plan(s, "cool_down");
        check(has(p, CabinControl.Item.TEMPERATURE, true, 220), "own climate wins over the rest's");
        check(!has(p, CabinControl.Item.TEMPERATURE, true, 235), "rest climate not added on top");
        check(has(p, CabinControl.Item.DIRECTION, true, CabinControl.DIR_LOWER) && has(p, CabinControl.Item.AC, true, 0), "direction and ac");
        check(has(p, CabinControl.Item.SEAT_HEAT, true, 0) && has(p, CabinControl.Item.SEAT_VENT, true, 2), "vent turns heat off");
        check(has(p, CabinControl.Item.SEAT_VENT, false, 0) && has(p, CabinControl.Item.SEAT_HEAT, false, 1), "passenger heat 1");
        check(has(p, CabinControl.Item.LIGHT_SWITCH, true, 1) && has(p, CabinControl.Item.LIGHT_BRIGHTNESS, true, 40)
                && has(p, CabinControl.Item.LIGHT_COLOR, true, 0xA5B198), "light on, 40%, sage");
        check(index(p, CabinControl.Item.LIGHT_SWITCH) < index(p, CabinControl.Item.LIGHT_BRIGHTNESS), "switch before brightness");
        for (CabinControl.Step step : p) check(step.item != null, "no seat motion items exist");
        check(CabinControl.describe(p).contains("22.0°") && CabinControl.describe(p).contains("통풍 2단계")
                && CabinControl.describe(p).contains("올리브 40%"), "description: " + CabinControl.describe(p));

        // The car's own ranges.
        check(CabinControl.fanSpeed(CabinControl.FAN_LOW, 1, 7) == 2 && CabinControl.fanSpeed(CabinControl.FAN_MEDIUM, 1, 7) == 4
                && CabinControl.fanSpeed(CabinControl.FAN_HIGH, 1, 7) == 6, "fan 1..7");
        check(CabinControl.fanSpeed(CabinControl.FAN_LOW, 0, 4) == 1, "fan never 0 (off)");
        check(CabinControl.seatLevel(2, 0, 3) == 2 && CabinControl.seatLevel(3, 0, 2) == 2 && CabinControl.seatLevel(0, 0, 3) == 0, "seat clamps");
        check(CabinControl.seatLevel(1, 0, 0) == -1, "no seat heat");
        check(CabinControl.seatLevel(0, 1, 3) == -1, "off is never turned into level 1");
        check(CabinControl.brightness(40, 0, 100) == 40 && CabinControl.brightness(50, 1, 11) == 6, "brightness maps");
        check(CabinControl.matches(CabinControl.Item.TEMPERATURE, 220, 222) && !CabinControl.matches(CabinControl.Item.TEMPERATURE, 220, 230), "0.5° grid");
        check(!CabinControl.matches(CabinControl.Item.FAN, 2, null) && CabinControl.matches(CabinControl.Item.FAN, 2, 2), "exact fan");

        // Voice values.
        List<CabinControl.Step> v = CabinControl.voiceSteps("{\"temperature\": 22}");
        check(v != null && v.size() == 2 && v.get(0).item == CabinControl.Item.CLIMATE_POWER && has(v, CabinControl.Item.TEMPERATURE, true, 220), "22 degrees");
        check(has(CabinControl.voiceSteps("{\"temperature\":22.3}"), CabinControl.Item.TEMPERATURE, true, 225), "rounds to 0.5");
        v = CabinControl.voiceSteps("{\"temperature\":21.5,\"fan\":\"high\"}");
        check(v.get(0).item == CabinControl.Item.CLIMATE_POWER && v.size() == 4 && has(v, CabinControl.Item.FAN, true, CabinControl.FAN_HIGH), "temp and fan, power once and first");
        check(has(CabinControl.voiceSteps("{\"seat\":\"vent\",\"seatLevel\":2,\"seatSide\":\"passenger\"}"), CabinControl.Item.SEAT_VENT, false, 2), "passenger vent 2");
        check(has(CabinControl.voiceSteps("{\"seat\":\"heat\"}"), CabinControl.Item.SEAT_HEAT, true, 1), "heat defaults to 1");
        v = CabinControl.voiceSteps("{\"lightColor\":\"mist\",\"brightness\":30}");
        check(has(v, CabinControl.Item.LIGHT_SWITCH, true, 1) && has(v, CabinControl.Item.LIGHT_COLOR, true, 0xA4BBC9), "color turns the light on");
        check(CabinControl.voiceSteps("{\"light\":\"off\"}").size() == 1, "light off");
        check(CabinControl.describe(CabinControl.voiceSteps("{\"ac\":\"off\"}")).equals("에어컨 냉방 끄기"), "ac off words");
        String[] bad = {null, "", "{}", "[]", "{\"temperature\":35}", "{\"temperature\":\"22\"}", "{\"fan\":\"max\"}",
                "{\"seat\":\"massage\"}", "{\"seatLevel\":2}", "{\"seatSide\":\"driver\"}", "{\"seat\":\"off\",\"seatLevel\":2}",
                "{\"light\":\"off\",\"brightness\":30}", "{\"brightness\":5}", "{\"brightness\":30.5}", "{\"window\":\"open\"}",
                "{\"temperature\":22,\"temperature\":23}", "{\"fan\":\"lo\\u0077\"}", "{\"a\":{\"b\":1}}", "{\"fan\":true}",
                "{\"fan\":\"low\"} x", "{\"temperature\":1e2}", "{\"temperature\":-22}", "{\"fan\":\"\"}",
                "{\"lightColor\":\"red\"}", "{\"seat\":\"heat\",\"seatLevel\":4}", "{\"temperature\":NaN}",
                "{\"temperature\":2.25e999}", "{\"temperature\":022}", "{\"temperature\":1234567890123456789012345}"};
        for (String b : bad) check(CabinControl.voiceSteps(b) == null, "rejects " + b);
        StringBuilder longText = new StringBuilder("{\"fan\":\"low\"");
        while (longText.length() <= CabinControl.MAX_VOICE_LENGTH) longText.append("    ");
        check(CabinControl.voiceSteps(longText.append('}').toString()) == null, "rejects long input");
        // Equal JSON numbers are equal values.
        check(has(CabinControl.voiceSteps("{\"temperature\":22.500}"), CabinControl.Item.TEMPERATURE, true, 225), "22.500");
        check(has(CabinControl.voiceSteps("{\"temperature\":2.25e1}"), CabinControl.Item.TEMPERATURE, true, 225), "2.25e1");
        check(has(CabinControl.voiceSteps("{\"seat\":\"vent\",\"seatLevel\":2.0}"), CabinControl.Item.SEAT_VENT, true, 2), "2.0 is a whole level");

        // SET_CABIN modes.
        check(CabinControl.voice("{\"mode\":\"rest\"}").rest, "mode rest");
        check(CabinControl.voice("{\"mode\":\"rest\",\"temperature\":22}") == null, "rest takes no values");
        check(!CabinControl.voice("{\"mode\":\"custom\",\"fan\":\"low\"}").rest
                && has(CabinControl.voice("{\"mode\":\"custom\",\"fan\":\"low\"}").steps, CabinControl.Item.FAN, true, CabinControl.FAN_LOW), "mode custom");
        check(CabinControl.voice("{\"fan\":\"low\"}") != null, "mode may be left out");
        check(CabinControl.voice("{\"mode\":\"custom\"}") == null && CabinControl.voice("{\"mode\":\"auto\"}") == null, "custom needs values, unknown mode refused");

        // Gleo: three actions; SET_CABIN only from Gleo, never during a rest, never a start.
        java.util.Map<String, Object> temp = java.util.Map.of("ACTION_PARAM", "{\"temperature\":22}");
        java.util.Map<String, Object> rest = java.util.Map.of("ACTION_PARAM", "{\"mode\":\"rest\"}");
        String gleo = "ai.umos.ambientai";
        check(GleoActionPolicy.recognizes(GleoActionPolicy.SET) && !GleoActionPolicy.recognizes("APPLY_REST_CABIN"), "recognised");
        check(GleoActionPolicy.evaluate(GleoActionPolicy.SET, temp, RestSession.State.IDLE, gleo, false) == GleoActionPolicy.Result.SET_CABIN, "set while idle");
        check(GleoActionPolicy.evaluate(GleoActionPolicy.SET, rest, RestSession.State.IDLE, gleo, false) == GleoActionPolicy.Result.APPLY_CABIN, "rest plan");
        check(GleoActionPolicy.evaluate(GleoActionPolicy.SET, temp, RestSession.State.ACTIVE, gleo, false) == GleoActionPolicy.Result.SESSION_BUSY, "not during a rest");
        check(GleoActionPolicy.evaluate(GleoActionPolicy.SET, temp, RestSession.State.PAUSED, gleo, false) == GleoActionPolicy.Result.SESSION_BUSY, "not while paused");
        check(GleoActionPolicy.evaluate(GleoActionPolicy.SET, temp, RestSession.State.IDLE, "com.example.other", false) == GleoActionPolicy.Result.UNTRUSTED, "other app refused");
        check(GleoActionPolicy.evaluate(GleoActionPolicy.SET, temp, RestSession.State.IDLE, null, false) == GleoActionPolicy.Result.UNTRUSTED, "unknown sender refused");
        check(GleoActionPolicy.evaluate(GleoActionPolicy.SET, temp, RestSession.State.IDLE, "com.android.shell", false) == GleoActionPolicy.Result.UNTRUSTED, "shell refused in release");
        check(GleoActionPolicy.evaluate(GleoActionPolicy.SET, temp, RestSession.State.IDLE, "com.android.shell", true) == GleoActionPolicy.Result.SET_CABIN, "shell allowed in debug");
        check(GleoActionPolicy.evaluate(GleoActionPolicy.SET, java.util.Map.of("ACTION_PARAM", "{\"temperature\":40}"), RestSession.State.IDLE, gleo, false)
                == GleoActionPolicy.Result.REJECT, "set rejects bad values");
        check(GleoActionPolicy.evaluate(GleoActionPolicy.SET, java.util.Map.of("ACTION_PARAM", "{\"temperature\":22}", "android.intent.extra.REFERRER_NAME", gleo),
                RestSession.State.IDLE, gleo, false) == GleoActionPolicy.Result.REJECT, "a forged referrer extra is an extra key");
        check(GleoActionPolicy.evaluate(GleoActionPolicy.SET, java.util.Map.of(), RestSession.State.IDLE, gleo, false) == GleoActionPolicy.Result.REJECT, "set needs values");
        check(GleoActionPolicy.evaluate("START_REST", java.util.Map.of(), RestSession.State.IDLE) == GleoActionPolicy.Result.REJECT, "no voice start");

        // Words.
        check(CabinControl.outcome(4, 0, 0, 0, 0, 0).equals("차 안을 맞췄어요"), "all confirmed");
        check(CabinControl.outcome(3, 0, 1, 0, 0, 0).equals("3개 맞췄어요 · 1개는 바뀌었는지 확인하지 못했어요"), "partial");
        check(CabinControl.outcome(0, 0, 0, 0, 0, 0).equals("바꿀 값이 없어요"), "nothing");
        check(CabinControl.outcome(1, 0, 0, 1, 1, 2).equals("1개 맞췄어요 · 1개는 이 차에서 안 돼요 · 1개는 지금 값을 못 읽어 그대로 뒀어요 · 2개는 보내지 않았어요"), "every kind");
        check(CabinControl.restoreOutcome(2, 0, 0).equals("차 안을 쉬기 전으로 돌렸어요."), "restored");
        check(CabinControl.restoreOutcome(1, 1, 1).equals("1개는 쉬기 전으로 돌렸어요 · 1개는 손으로 바꾼 값이라 그대로 뒀어요 · 1개는 아직 못 돌렸어요 · 주차 중에 다시 돌릴게요."), "mixed restore");
        System.out.println("CabinControlTest: " + assertions + " assertions passed");
    }
}
