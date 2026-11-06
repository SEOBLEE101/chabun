package ai.pitin.vehicle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 차 안 맞추기 (spec 6-2-1): turns 차 안 설정 values, the chosen rest's climate and Gleo voice values into
 * vehicle requests, and decides when a request may be sent. Pure Java, no Android or Pleos; the actuators
 * (demo stand-in, Pleos Vehicle SDK) only execute the steps planned here.
 */
public final class CabinControl {
    public enum Item { CLIMATE_POWER, CLIMATE_AUTO, TEMPERATURE, FAN, DIRECTION, AC, SEAT_HEAT, SEAT_VENT,
        LIGHT_SWITCH, LIGHT_BRIGHTNESS, LIGHT_COLOR }
    /**
     * CONFIRMED: the car reads back the asked value. UNCHANGED: it already had it (nothing sent, nothing owned).
     * FAILED: refused and still the old value. UNKNOWN: sent, but no read confirmed either way. UNSUPPORTED: not on
     * this car. UNREADABLE: the current value could not be read, so a rest did not change it. SKIPPED: never sent
     * (blocked, ended, cancelled or out of time). KEPT: a restore left a value the person changed by hand.
     */
    public enum Status { WAITING, REQUESTED, CONFIRMED, UNCHANGED, FAILED, UNKNOWN, UNSUPPORTED, UNREADABLE, SKIPPED, KEPT }

    /** Fan words as levels 1–3 (the actuator maps them into the car's own range). */
    public static final int FAN_LOW = 1, FAN_MEDIUM = 2, FAN_HIGH = 3;
    /** Directions as the actuator's codes. */
    public static final int DIR_UPPER = 1, DIR_LOWER = 2, DIR_BOTH = 3;
    public static final int MAX_VOICE_LENGTH = 256;

    /**
     * One request. {@code value} units: TEMPERATURE tenths of °C; FAN 1–3; DIRECTION 1–3; seat levels 0–3;
     * LIGHT_BRIGHTNESS percent; LIGHT_COLOR 0xRRGGBB; switches 1 = on, 0 = off. Light steps ignore {@code driver}.
     */
    public static final class Step {
        public final Item item;
        public final boolean driver;
        public final int value;

        public Step(Item item, boolean driver, int value) { this.item = item; this.driver = driver; this.value = value; }

        /** Same item in the same zone: a later request replaces an earlier one. */
        public String key() { return item + (isLight(item) ? "" : driver ? ":driver" : ":passenger"); }

        public String group() {
            switch (item) {
                case SEAT_HEAT: case SEAT_VENT: return "seat";
                case LIGHT_SWITCH: case LIGHT_BRIGHTNESS: case LIGHT_COLOR: return "light";
                default: return "climate";
            }
        }

        @Override public boolean equals(Object o) {
            return o instanceof Step && key().equals(((Step) o).key()) && value == ((Step) o).value;
        }
        @Override public int hashCode() { return key().hashCode() * 31 + value; }
        @Override public String toString() { return key() + "=" + value; }
    }

    private CabinControl() { }

    private static boolean isLight(Item item) {
        return item == Item.LIGHT_SWITCH || item == Item.LIGHT_BRIGHTNESS || item == Item.LIGHT_COLOR;
    }

    // ── when a request may be sent ─────────────────────────────────────

    /**
     * Null when the car may be changed now, otherwise the reason in on-screen words.
     * Writes need the same parked facts as a rest (fresh state, gear P, 0 km/h, verified access); a phone call
     * or a UX restriction does not stop a climate change while parked.
     */
    public static String writeBlocked(SafetyGate.Snapshot state, long nowMs) {
        if (state == null) return "차 상태를 읽지 못했어요";
        long age = nowMs - state.sampledAtMs;
        if (age < 0 || age >= SafetyGate.MAX_STATE_AGE_MS) return "차 상태를 다시 확인하고 있어요";
        if (!state.permissionVerified) return "차 상태를 읽는 권한을 확인하지 못했어요";
        if (state.gear != SafetyGate.Gear.PARK) return "기어가 P일 때만 맞출 수 있어요";
        if (state.speedKph == null || !Double.isFinite(state.speedKph) || state.speedKph != 0.0)
            return "차가 멈춰 있을 때만 맞출 수 있어요";
        // Pleos ties seat and vehicle settings to the UX restriction state: only a known, unrestricted state allows it.
        if (!Boolean.FALSE.equals(state.uxRestricted)) return "지금은 차 화면 사용이 제한돼 맞출 수 없어요";
        return null;
    }

    /**
     * The live rest adapter: SafetyGate stays the judge and runs every check on the car's real values; only its
     * read-only switch (simulation required) is lifted for the Pleos builds that rest on real values. It allows a
     * rest, not a cabin write: writes still need {@link #writeBlocked} and a build that has a cabin actuator.
     */
    public static SafetyGate.Snapshot forLiveRest(SafetyGate.Snapshot live) {
        if (live == null) return null;
        return new SafetyGate.Snapshot(true, live.gear, live.speedKph, live.callActive, live.uxRestricted,
                live.permissionVerified, live.sampledAtMs);
    }

    // ── plans ─────────────────────────────────────────────────────────

    /** Steps for ‘지금 차에 맞추기’ on 차 안 설정: exactly the values on screen; "keep" stays untouched. */
    public static List<Step> plan(CabinSettings s) { return plan(s, null); }

    /**
     * Steps for the start of a rest: 차 안 설정, and where the driver's climate is "keep", the chosen rest's own
     * climate (조용히 쉬기 24.0° 약하게 …). Seats never move; only heating and ventilation change.
     */
    public static List<Step> plan(CabinSettings s, String restPreset) { return plan(s, restPreset, null); }

    /** The car values of a preset shared from the phone (함께 쓰는 쉼): tenths or null = keep, fan low|medium|high|keep. */
    public static final class Shared {
        public final Integer tempTenths;
        public final String fan;
        public Shared(Integer tempTenths, String fan) { this.tempTenths = tempTenths; this.fan = fan; }
    }

    /**
     * As above, but a preset chosen from the phone replaces the rest's own climate, so the phone's ‘세팅값’ reach the car.
     * Its "keep" values leave those items alone.
     */
    public static List<Step> plan(CabinSettings s, String restPreset, Shared shared) { return plan(s, restPreset, shared, null); }

    /**
     * The one plan for a rest (화면 · Gleo · 실제 제어 all use it). Per group: "keep" leaves it alone; 직접 정하기 uses
     * this car's value; "rest" (쉼 따르기) uses the phone's 차 안 설정 for the chosen rest ({@code sharedCabin}, its
     * "keep" also leaving the group alone), and for the driver's climate, when the phone did not set one, the shared
     * rest's temperature and fan ({@code shared}), or else the rest's own built-in climate.
     */
    public static List<Step> plan(CabinSettings s, String restPreset, Shared shared, CabinSettings sharedCabin) {
        Map<String, Step> out = new LinkedHashMap<>();
        for (boolean driver : new boolean[]{true, false}) {
            CabinSettings.Climate c = s.climate(driver);
            if ("custom".equals(c.mode)) customClimate(out, driver, c);
            else if ("rest".equals(c.mode)) {
                CabinSettings.Climate phone = sharedCabin == null ? null : sharedCabin.climate(driver);
                if (phone != null && "custom".equals(phone.mode)) customClimate(out, driver, phone);
                else if (driver && shared != null) {
                    int t = shared.tempTenths == null ? 0 : shared.tempTenths;
                    int fan = shared.fan == null ? 0 : fanLevel(shared.fan);
                    if (t > 0 || fan > 0) climate(out, true, t, fan, false, 0, 0);
                } else if (driver && restPreset != null) restClimate(out, restPreset);
            }
        }
        for (boolean driver : new boolean[]{true, false}) {
            CabinSettings.Seat seat = s.seat(driver);
            if ("rest".equals(seat.comfort)) seat = sharedCabin == null ? null : sharedCabin.seat(driver);
            if (seat != null) seat(out, driver, seat);
        }
        CabinSettings.Light l = s.light;
        if ("rest".equals(l.mode)) l = sharedCabin == null ? null : sharedCabin.light;
        if (l != null && "off".equals(l.mode)) put(out, new Step(Item.LIGHT_SWITCH, true, 0));
        else if (l != null && "custom".equals(l.mode)) {
            put(out, new Step(Item.LIGHT_SWITCH, true, 1));
            put(out, new Step(Item.LIGHT_BRIGHTNESS, true, l.bright));
            put(out, new Step(Item.LIGHT_COLOR, true, color(l.color)));
        }
        return new ArrayList<>(out.values());
    }

    private static void customClimate(Map<String, Step> out, boolean driver, CabinSettings.Climate c) {
        climate(out, driver, c.tempTenths, fanLevel(c.fan), "auto".equals(c.fan), dir(c.dir), ac(c.ac));
    }

    private static void seat(Map<String, Step> out, boolean driver, CabinSettings.Seat seat) {
        switch (seat.comfort) {
            case "heat": put(out, new Step(Item.SEAT_VENT, driver, 0)); put(out, new Step(Item.SEAT_HEAT, driver, seat.level)); break;
            case "vent": put(out, new Step(Item.SEAT_HEAT, driver, 0)); put(out, new Step(Item.SEAT_VENT, driver, seat.level)); break;
            case "off": put(out, new Step(Item.SEAT_HEAT, driver, 0)); put(out, new Step(Item.SEAT_VENT, driver, 0)); break;
            default: break;
        }
    }

    /**
     * What ‘쉼 따르기’ brings for one group ("seat", "climate", "light"), in on-screen words, for the 차 안 설정 card;
     * empty when the chosen rest leaves that group alone.
     */
    public static String followText(String group, boolean driver, String restPreset, Shared shared, CabinSettings sharedCabin) {
        CabinSettings only = new CabinSettings();
        for (boolean d : new boolean[]{true, false}) { only.seat(d).comfort = "keep"; only.climate(d).mode = "keep"; }
        only.light.mode = "keep";
        if ("seat".equals(group)) only.seat(driver).comfort = "rest";
        else if ("climate".equals(group)) only.climate(driver).mode = "rest";
        else only.light.mode = "rest";
        return describe(plan(only, restPreset, shared, sharedCabin));
    }

    private static void restClimate(Map<String, Step> out, String preset) {
        switch (preset) {
            case "cool_down": climate(out, true, 235, FAN_MEDIUM, false, 0, 0); break;
            case "quiet_rest": climate(out, true, 240, FAN_LOW, false, 0, 0); break;
            case "gentle_refresh": climate(out, true, 250, 0, false, 0, 0); break; // 바람은 그대로
            default: break;
        }
    }

    /** tenths/fan 0 = leave them; dir/ac 0 = leave them; ac 1 = on, 2 = off. Power first, as the car needs it. */
    private static void climate(Map<String, Step> out, boolean driver, int tenths, int fan, boolean auto, int dir, int ac) {
        put(out, new Step(Item.CLIMATE_POWER, driver, 1));
        if (tenths > 0) put(out, new Step(Item.TEMPERATURE, driver, tenths));
        if (auto) put(out, new Step(Item.CLIMATE_AUTO, driver, 1));
        else if (fan > 0) { put(out, new Step(Item.CLIMATE_AUTO, driver, 0)); put(out, new Step(Item.FAN, driver, fan)); }
        if (dir > 0) put(out, new Step(Item.DIRECTION, driver, dir));
        if (ac > 0) put(out, new Step(Item.AC, driver, ac == 1 ? 1 : 0));
    }

    /** A later request for the same item replaces the earlier value but keeps its place (power stays first). */
    private static void put(Map<String, Step> out, Step step) { out.put(step.key(), step); }

    static int fanLevel(String fan) {
        switch (fan) { case "low": return FAN_LOW; case "medium": return FAN_MEDIUM; case "high": return FAN_HIGH; default: return 0; }
    }

    private static int dir(String d) {
        switch (d) { case "upper": return DIR_UPPER; case "lower": return DIR_LOWER; case "both": return DIR_BOTH; default: return 0; }
    }

    private static int ac(String a) { return "on".equals(a) ? 1 : "off".equals(a) ? 2 : 0; }

    /** The swatch colors of 조명 (CabinArtView), as the car's ambient color. */
    public static int color(String name) {
        switch (name) { case "neutral": return 0xE8E6DE; case "sage": return 0xA5B198; case "mist": return 0xA4BBC9; default: return 0xE8CFA6; }
    }

    // ── mapping into the car's own ranges (used by the actuators) ─────

    /** A fan level 1–3 inside the car's bounds: about a fifth, half and most of the range. */
    public static int fanSpeed(int level, int min, int max) {
        if (max <= min) return max;
        double share = level == FAN_LOW ? 0.2 : level == FAN_MEDIUM ? 0.5 : 0.85;
        int v = (int) Math.round(min + (max - min) * share);
        return Math.max(Math.max(min, 1), Math.min(max, v));
    }

    /** Seat level 0–3 inside the car's bounds; -1 when the car cannot do it. */
    public static int seatLevel(int level, int min, int max) {
        if (level <= 0) return min <= 0 ? 0 : -1; // off must stay off, never become the lowest heat level
        if (max < 1) return -1;
        return Math.min(max, Math.max(Math.max(1, min), level));
    }

    /** Brightness percent inside the car's bounds. */
    public static int brightness(int percent, int min, int max) {
        if (max <= min) return max;
        int p = Math.max(0, Math.min(100, percent));
        return (int) Math.round(min + (max - min) * p / 100.0);
    }

    /** Whether what the car reports back is what was asked (temperatures may land on the car's own 0.5° grid). */
    public static boolean matches(Item item, int asked, Integer reported) {
        if (reported == null) return false;
        if (item == Item.TEMPERATURE) return Math.abs(asked - reported) <= 3;
        return asked == reported;
    }

    // ── Gleo voice values (SET_CABIN) ─────────────────────────────────

    /**
     * Steps for one voice request, or null for anything malformed. ACTION_PARAM is a flat JSON object with only
     * these keys: temperature (18–28), fan (low|medium|high|auto), ac (on|off), seat (heat|vent|off), seatLevel (1–3),
     * seatSide (driver|passenger), light (on|off), lightColor (cream|neutral|sage|mist), brightness (10–100).
     */
    public static List<Step> voiceSteps(String actionParam) {
        Map<String, Object> v = flatJson(actionParam);
        return v == null ? null : voiceSteps(v);
    }

    /** One SET_CABIN request: {@code rest} = the chosen rest's plan ({"mode":"rest"} alone), else {@code steps}. */
    public static final class Voice {
        public final boolean rest;
        public final List<Step> steps;
        Voice(boolean rest, List<Step> steps) { this.rest = rest; this.steps = steps; }
    }

    /** SET_CABIN's ACTION_PARAM: {"mode":"rest"}, or values with an optional "mode":"custom". Null when malformed. */
    public static Voice voice(String actionParam) {
        Map<String, Object> v = flatJson(actionParam);
        if (v == null) return null;
        Object mode = v.remove("mode");
        if ("rest".equals(mode)) return v.isEmpty() ? new Voice(true, null) : null;
        if (mode != null && !"custom".equals(mode)) return null;
        List<Step> steps = voiceSteps(v);
        return steps == null ? null : new Voice(false, steps);
    }

    private static List<Step> voiceSteps(Map<String, Object> v) {
        if (v.isEmpty()) return null;
        for (String key : v.keySet())
            if (!key.matches("temperature|fan|ac|seat|seatLevel|seatSide|light|lightColor|brightness")) return null;
        Map<String, Step> out = new LinkedHashMap<>();
        boolean driver = true;
        if (v.containsKey("seatSide")) {
            Object side = v.get("seatSide");
            if ("passenger".equals(side)) driver = false;
            else if (!"driver".equals(side)) return null;
            if (!v.containsKey("seat")) return null;
        }
        if (v.containsKey("temperature")) {
            Double t = number(v.get("temperature"));
            if (t == null || t < 18.0 || t > 28.0) return null;
            int tenths = (int) Math.round(t * 2) * 5;
            put(out, new Step(Item.CLIMATE_POWER, true, 1));
            put(out, new Step(Item.TEMPERATURE, true, tenths));
        }
        if (v.containsKey("fan")) {
            Object fan = v.get("fan");
            if ("auto".equals(fan)) { put(out, new Step(Item.CLIMATE_POWER, true, 1)); put(out, new Step(Item.CLIMATE_AUTO, true, 1)); }
            else if (fan instanceof String && fanLevel((String) fan) > 0) {
                put(out, new Step(Item.CLIMATE_POWER, true, 1));
                put(out, new Step(Item.CLIMATE_AUTO, true, 0));
                put(out, new Step(Item.FAN, true, fanLevel((String) fan)));
            } else return null;
        }
        if (v.containsKey("ac")) {
            Object a = v.get("ac");
            if (!"on".equals(a) && !"off".equals(a)) return null;
            if ("on".equals(a)) put(out, new Step(Item.CLIMATE_POWER, true, 1));
            put(out, new Step(Item.AC, true, "on".equals(a) ? 1 : 0));
        }
        if (v.containsKey("seatLevel") && !v.containsKey("seat")) return null;
        if (v.containsKey("seat")) {
            Object seat = v.get("seat");
            int level = 1;
            if (v.containsKey("seatLevel")) {
                Double l = number(v.get("seatLevel"));
                if (l == null || l != Math.rint(l) || l < 1 || l > 3) return null;
                level = l.intValue();
            }
            if ("heat".equals(seat)) { put(out, new Step(Item.SEAT_VENT, driver, 0)); put(out, new Step(Item.SEAT_HEAT, driver, level)); }
            else if ("vent".equals(seat)) { put(out, new Step(Item.SEAT_HEAT, driver, 0)); put(out, new Step(Item.SEAT_VENT, driver, level)); }
            else if ("off".equals(seat) && !v.containsKey("seatLevel")) {
                put(out, new Step(Item.SEAT_HEAT, driver, 0)); put(out, new Step(Item.SEAT_VENT, driver, 0));
            } else return null;
        }
        boolean lightOff = false;
        if (v.containsKey("light")) {
            Object light = v.get("light");
            if ("off".equals(light)) lightOff = true;
            else if (!"on".equals(light)) return null;
        }
        if (lightOff && (v.containsKey("brightness") || v.containsKey("lightColor"))) return null;
        if (v.containsKey("light") || v.containsKey("brightness") || v.containsKey("lightColor"))
            put(out, new Step(Item.LIGHT_SWITCH, true, lightOff ? 0 : 1));
        if (v.containsKey("brightness")) {
            Double b = number(v.get("brightness"));
            if (b == null || b != Math.rint(b) || b < 10 || b > 100) return null;
            put(out, new Step(Item.LIGHT_BRIGHTNESS, true, b.intValue()));
        }
        if (v.containsKey("lightColor")) {
            Object c = v.get("lightColor");
            if (!(c instanceof String) || !((String) c).matches("cream|neutral|sage|mist")) return null;
            put(out, new Step(Item.LIGHT_COLOR, true, color((String) c)));
        }
        return out.isEmpty() ? null : new ArrayList<>(out.values());
    }

    private static Double number(Object o) {
        return o instanceof Double && Double.isFinite((Double) o) ? (Double) o : null;
    }

    /**
     * A flat JSON object of plain strings (letters, digits, '_', no escapes) and numbers. Null for anything else:
     * nesting, arrays, booleans, escapes, duplicate keys, trailing text or more than {@link #MAX_VOICE_LENGTH} chars.
     */
    static Map<String, Object> flatJson(String text) {
        if (text == null || text.length() > MAX_VOICE_LENGTH) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        int[] at = {0};
        skip(text, at);
        if (!eat(text, at, '{')) return null;
        skip(text, at);
        if (eat(text, at, '}')) return end(text, at) ? out : null;
        while (true) {
            skip(text, at);
            String key = string(text, at);
            if (key == null || out.containsKey(key)) return null;
            skip(text, at);
            if (!eat(text, at, ':')) return null;
            skip(text, at);
            Object value = at[0] < text.length() && text.charAt(at[0]) == '"' ? string(text, at) : num(text, at);
            if (value == null) return null;
            out.put(key, value);
            skip(text, at);
            if (eat(text, at, ',')) continue;
            if (eat(text, at, '}')) return end(text, at) ? out : null;
            return null;
        }
    }

    private static void skip(String s, int[] at) {
        while (at[0] < s.length() && " \t\r\n".indexOf(s.charAt(at[0])) >= 0) at[0]++;
    }

    private static boolean eat(String s, int[] at, char c) {
        if (at[0] < s.length() && s.charAt(at[0]) == c) { at[0]++; return true; }
        return false;
    }

    private static boolean end(String s, int[] at) { skip(s, at); return at[0] == s.length(); }

    private static String string(String s, int[] at) {
        if (!eat(s, at, '"')) return null;
        int start = at[0];
        while (at[0] < s.length()) {
            char c = s.charAt(at[0]);
            if (c == '"') { at[0]++; String v = s.substring(start, at[0] - 1); return v.isEmpty() ? null : v; }
            if (!(Character.isLetterOrDigit(c) && c < 128) && c != '_') return null;
            at[0]++;
        }
        return null;
    }

    /** A JSON number of at most 24 characters (22, 22.500 and 2.25e1 alike); ranges are checked by the caller. */
    private static Double num(String s, int[] at) {
        int start = at[0];
        while (at[0] < s.length() && "0123456789.-+eE".indexOf(s.charAt(at[0])) >= 0) at[0]++;
        String n = s.substring(start, at[0]);
        if (n.length() > 24 || !n.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]{1,2})?")) return null;
        double d = Double.parseDouble(n);
        return Double.isFinite(d) ? d : null;
    }

    // ── words on screen ───────────────────────────────────────────────

    /** A short line for pills and Gleo answers: ‘에어컨 22.0° · 바람 약하게 · 시트 통풍 2단계 · 조명 올리브 40%’. */
    public static String describe(List<Step> steps) {
        List<String> parts = new ArrayList<>();
        StringBuilder climate = new StringBuilder();
        String[] seat = new String[2]; // driver, passenger
        String lightOff = null, lightColor = null, lightBright = null;
        boolean lightOn = false;
        for (Step s : steps) {
            int side = s.driver ? 0 : 1;
            switch (s.item) {
                case TEMPERATURE: append(climate, String.format(Locale.ROOT, "%d.%d°", s.value / 10, s.value % 10)); break;
                case CLIMATE_AUTO: if (s.value == 1) append(climate, "바람 자동"); break;
                case FAN: append(climate, "바람 " + (s.value == FAN_LOW ? "약하게" : s.value == FAN_MEDIUM ? "중간" : "세게")); break;
                case DIRECTION: append(climate, s.value == DIR_UPPER ? "위로" : s.value == DIR_LOWER ? "아래로" : "위아래"); break;
                case AC: append(climate, s.value == 1 ? "냉방 켜기" : "냉방 끄기"); break;
                case SEAT_HEAT: if (s.value > 0) seat[side] = "열선 " + s.value + "단계"; else if (seat[side] == null) seat[side] = "열선·통풍 끄기"; break;
                case SEAT_VENT: if (s.value > 0) seat[side] = "통풍 " + s.value + "단계"; else if (seat[side] == null) seat[side] = "열선·통풍 끄기"; break;
                case LIGHT_SWITCH: if (s.value == 0) lightOff = "끄기"; else lightOn = true; break;
                case LIGHT_COLOR: lightColor = CabinSettings.colorLabel(colorName(s.value)); break;
                case LIGHT_BRIGHTNESS: lightBright = s.value + "%"; break;
                default: break;
            }
        }
        if (climate.length() > 0) parts.add("에어컨 " + climate);
        if (seat[0] != null) parts.add("시트 " + seat[0]);
        if (seat[1] != null) parts.add("동승석 " + seat[1]);
        StringBuilder light = new StringBuilder();
        if (lightOff != null) append(light, lightOff);
        if (lightColor != null) append(light, lightColor);
        if (lightBright != null) append(light, lightBright);
        if (light.length() == 0 && lightOn) light.append("켜기");
        if (light.length() > 0) parts.add("조명 " + light);
        if (parts.isEmpty() && !steps.isEmpty()) parts.add("에어컨 켜기");
        return String.join(" · ", parts);
    }

    private static void append(StringBuilder b, String part) { if (b.length() > 0) b.append(' '); b.append(part); }

    private static String colorName(int rgb) {
        for (String c : CabinSettings.COLORS) if (color(c) == rgb) return c;
        return "cream";
    }

    /** Result line after a plan ran: ‘차 안을 맞췄어요’ / ‘3개 맞췄어요 · 1개는 이 차에서 안 돼요’. */
    public static String outcome(int done, int failed, int unknown, int unsupported, int unreadable, int skipped) {
        if (done + failed + unknown + unsupported + unreadable + skipped == 0) return "바꿀 값이 없어요";
        if (failed + unknown + unsupported + unreadable + skipped == 0) return "차 안을 맞췄어요";
        StringBuilder b = new StringBuilder();
        part(b, done, "개 맞췄어요");
        part(b, unknown, "개는 바뀌었는지 확인하지 못했어요");
        part(b, failed, "개는 차가 받지 않았어요");
        part(b, unsupported, "개는 이 차에서 안 돼요");
        part(b, unreadable, "개는 지금 값을 못 읽어 그대로 뒀어요");
        part(b, skipped, "개는 보내지 않았어요");
        return b.toString();
    }

    /** After a rest: ‘차 안을 쉬기 전으로 돌렸어요.’ / ‘손으로 바꾼 1개는 그대로 뒀어요’ / ‘2개는 아직 못 돌렸어요’. */
    public static String restoreOutcome(int restored, int kept, int owed) {
        if (owed == 0 && kept == 0) return "차 안을 쉬기 전으로 돌렸어요.";
        StringBuilder b = new StringBuilder();
        part(b, restored, "개는 쉬기 전으로 돌렸어요");
        part(b, kept, "개는 손으로 바꾼 값이라 그대로 뒀어요");
        part(b, owed, "개는 아직 못 돌렸어요 · 주차하고 차분을 열면 다시 돌릴게요");
        return b + ".";
    }

    /** One short line for the rest screen, pills and footers (the full breakdown is in {@link #outcome}). */
    public static String briefOutcome(int done, int total, int unknown) {
        if (total == 0) return "바꿀 값이 없어요";
        if (done == total) return "차 안을 맞췄어요";
        if (done == 0) return unknown > 0 ? "차 안이 바뀌었는지 확인하지 못했어요" : "차 안은 그대로예요";
        return "차 안 " + total + "개 중 " + done + "개를 맞췄어요";
    }

    /** One short line after a rest's restore. */
    public static String briefRestore(int kept, int owed) {
        if (owed > 0) return "차 안 " + owed + "개는 아직 못 돌렸어요 · 주차하고 차분을 열면 다시 돌릴게요.";
        if (kept > 0) return "손으로 바꾼 " + kept + "개 말고는 쉬기 전으로 돌렸어요.";
        return "차 안을 쉬기 전으로 돌렸어요.";
    }

    private static void part(StringBuilder b, int n, String words) {
        if (n > 0) b.append(b.length() > 0 ? " · " : "").append(n).append(words);
    }
}
