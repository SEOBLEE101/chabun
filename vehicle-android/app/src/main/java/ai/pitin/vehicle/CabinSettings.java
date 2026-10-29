package ai.pitin.vehicle;

import java.util.Locale;

/**
 * 차 안 설정 (spec 6-2, 6-2-1): seat · climate · light values for the car's rests. Pure Java, no Android.
 * Each group has three meanings: "rest" (쉼 따르기, the default) uses what the chosen rest brings — the phone's
 * 차 안 설정 for that rest, else the rest's own climate; "keep" (그대로) leaves the car's value alone; anything else
 * (직접 정하기) is this car's own value. {@link CabinControl#plan} turns them into one plan; in the pleos build that plan
 * is written to the car while parked. {@link SafetyGate#permitsVehicleWrite} itself stays false and is not that path.
 */
public final class CabinSettings {
    public static final String[] SEAT_POSITIONS = {"keep", "manual"};
    public static final String[] SEAT_COMFORTS = {"rest", "keep", "off", "heat", "vent"};
    public static final String[] CLIMATE_MODES = {"rest", "keep", "custom"};
    public static final String[] FANS = {"auto", "low", "medium", "high"};
    public static final String[] DIRECTIONS = {"keep", "upper", "lower", "both"};
    public static final String[] AC = {"keep", "on", "off"};
    public static final String[] LIGHT_MODES = {"rest", "keep", "off", "custom"};
    public static final String[] COLORS = {"cream", "neutral", "sage", "mist"};
    public static final int MIN_TEMP_TENTHS = 180, MAX_TEMP_TENTHS = 280, MIN_BRIGHT = 10, MAX_BRIGHT = 100;

    public static final class Seat {
        public String position = "keep", comfort = "rest";
        public int level = 1;
        Seat copy() { Seat s = new Seat(); s.position = position; s.comfort = comfort; s.level = level; return s; }
        String encode() { return position + "," + comfort + "," + level; }
        @Override public boolean equals(Object o) { return o instanceof Seat && encode().equals(((Seat) o).encode()); }
        @Override public int hashCode() { return encode().hashCode(); }
    }

    public static final class Climate {
        public String mode = "rest", fan = "auto", dir = "keep", ac = "keep";
        public int tempTenths = 240;
        Climate copy() { Climate c = new Climate(); c.mode = mode; c.fan = fan; c.dir = dir; c.ac = ac; c.tempTenths = tempTenths; return c; }
        String encode() { return mode + "," + tempTenths + "," + fan + "," + dir + "," + ac; }
        @Override public boolean equals(Object o) { return o instanceof Climate && encode().equals(((Climate) o).encode()); }
        @Override public int hashCode() { return encode().hashCode(); }
    }

    public static final class Light {
        public String mode = "rest", color = "cream";
        public int bright = 60;
        Light copy() { Light l = new Light(); l.mode = mode; l.color = color; l.bright = bright; return l; }
        String encode() { return mode + "," + color + "," + bright; }
        @Override public boolean equals(Object o) { return o instanceof Light && encode().equals(((Light) o).encode()); }
        @Override public int hashCode() { return encode().hashCode(); }
    }

    public Seat driverSeat = new Seat(), passengerSeat = new Seat();
    public Climate driverClimate = new Climate(), passengerClimate = new Climate();
    public Light light = new Light();

    public Seat seat(boolean driver) { return driver ? driverSeat : passengerSeat; }
    public Climate climate(boolean driver) { return driver ? driverClimate : passengerClimate; }

    public CabinSettings copy() {
        CabinSettings c = new CabinSettings();
        c.driverSeat = driverSeat.copy(); c.passengerSeat = passengerSeat.copy();
        c.driverClimate = driverClimate.copy(); c.passengerClimate = passengerClimate.copy();
        c.light = light.copy();
        return c;
    }

    // ── validated edits (handoff value rules) ─────────────────────────
    public static boolean setSeatComfort(Seat s, String v) { if (!in(v, SEAT_COMFORTS)) return false; s.comfort = v; return true; }
    public static boolean setSeatPosition(Seat s, String v) { if (!in(v, SEAT_POSITIONS)) return false; s.position = v; return true; }
    /** Levels 1–3, and only meaningful while heating or ventilating (heat and vent are exclusive by design). */
    public static boolean setSeatLevel(Seat s, int level) {
        if (level < 1 || level > 3 || !("heat".equals(s.comfort) || "vent".equals(s.comfort))) return false;
        s.level = level; return true;
    }
    public static boolean setClimateMode(Climate c, String v) { if (!in(v, CLIMATE_MODES)) return false; c.mode = v; return true; }
    public static boolean setFan(Climate c, String v) { if (!in(v, FANS) || !"custom".equals(c.mode)) return false; c.fan = v; return true; }
    public static boolean setDirection(Climate c, String v) { if (!in(v, DIRECTIONS) || !"custom".equals(c.mode)) return false; c.dir = v; return true; }
    public static boolean setAc(Climate c, String v) { if (!in(v, AC) || !"custom".equals(c.mode)) return false; c.ac = v; return true; }
    /** 18.0–28.0 in 0.5 steps; returns false at the bounds so the stepper can stay put. */
    public static boolean stepTemp(Climate c, int deltaHalfSteps) {
        if (!"custom".equals(c.mode)) return false;
        int next = c.tempTenths + deltaHalfSteps * 5;
        if (next < MIN_TEMP_TENTHS || next > MAX_TEMP_TENTHS) return false;
        c.tempTenths = next; return true;
    }
    public static boolean setLightMode(Light l, String v) { if (!in(v, LIGHT_MODES)) return false; l.mode = v; return true; }
    public static boolean setColor(Light l, String v) { if (!in(v, COLORS) || !"custom".equals(l.mode)) return false; l.color = v; return true; }
    /** 10–100 in steps of 5. */
    public static boolean setBright(Light l, int v) {
        if (!"custom".equals(l.mode)) return false;
        int snapped = Math.round(v / 5f) * 5;
        if (snapped < MIN_BRIGHT || snapped > MAX_BRIGHT) return false;
        l.bright = snapped; return true;
    }

    /** Number of the five groups (seat×2, climate×2, light) that differ from {@code saved}. */
    public int changedCount(CabinSettings saved) {
        int n = 0;
        if (!driverSeat.equals(saved.driverSeat)) n++;
        if (!passengerSeat.equals(saved.passengerSeat)) n++;
        if (!driverClimate.equals(saved.driverClimate)) n++;
        if (!passengerClimate.equals(saved.passengerClimate)) n++;
        if (!light.equals(saved.light)) n++;
        return n;
    }

    // ── words on screen (spec 4 glossary) ─────────────────────────────
    public static String temp(int tenths) { return String.format(Locale.ROOT, "%d.%d°", tenths / 10, tenths % 10); }

    public static String fanLabel(String fan) {
        switch (fan) { case "low": return "약하게"; case "medium": return "중간"; case "high": return "세게"; default: return "자동"; }
    }

    public static String dirLabel(String dir) {
        switch (dir) { case "upper": return "위로"; case "lower": return "아래로"; case "both": return "위아래"; default: return "방향 그대로"; }
    }

    public static String colorLabel(String color) {
        switch (color) { case "neutral": return "차분한 빛"; case "sage": return "올리브"; case "mist": return "옅은 블루"; default: return "따뜻한 빛"; }
    }

    public static String seatText(Seat s) {
        StringBuilder b = new StringBuilder();
        if ("rest".equals(s.comfort)) b.append("쉼 따르기");
        if ("off".equals(s.comfort)) b.append("열선·통풍 끄기");
        if ("heat".equals(s.comfort)) b.append("열선 ").append(s.level).append("단계");
        if ("vent".equals(s.comfort)) b.append("통풍 ").append(s.level).append("단계");
        if ("manual".equals(s.position)) b.append(b.length() > 0 ? " · " : "").append("자세 직접");
        return b.length() == 0 ? "그대로" : b.toString();
    }

    public static String climateText(Climate c) {
        if ("rest".equals(c.mode)) return "쉼 따르기";
        if (!"custom".equals(c.mode)) return "그대로";
        StringBuilder b = new StringBuilder(temp(c.tempTenths)).append(' ').append(fanLabel(c.fan));
        if (!"keep".equals(c.dir)) b.append(' ').append(dirLabel(c.dir));
        if (!"keep".equals(c.ac)) b.append(' ').append("on".equals(c.ac) ? "냉방 켜기" : "냉방 끄기");
        return b.toString();
    }

    public static String lightText(Light l) {
        if ("rest".equals(l.mode)) return "쉼 따르기";
        if ("off".equals(l.mode)) return "끄기";
        if (!"custom".equals(l.mode)) return "그대로";
        return colorLabel(l.color) + " " + l.bright + "%";
    }

    public String summary() {
        return "시트 " + seatText(driverSeat) + "  ·  에어컨 " + climateText(driverClimate) + "  ·  조명 " + lightText(light);
    }

    // ── storage (a plain line, so it stays readable and testable without org.json) ──
    public String encode() {
        return "v2;sd=" + driverSeat.encode() + ";sp=" + passengerSeat.encode() + ";cd=" + driverClimate.encode()
                + ";cp=" + passengerClimate.encode() + ";l=" + light.encode();
    }

    /**
     * Strict decode; anything malformed yields the defaults (all "rest"). A v1 line had no "rest": its "keep"
     * already let the rest bring its own climate, so it reads as "rest" (the meaning it had).
     */
    public static CabinSettings decode(String value) {
        CabinSettings out = new CabinSettings();
        boolean v1 = value != null && value.startsWith("v1;");
        if (value == null || !(v1 || value.startsWith("v2;")) || value.length() > 400) return out;
        try {
            CabinSettings c = new CabinSettings();
            int seen = 0;
            for (String part : value.substring(3).split(";")) {
                int eq = part.indexOf('=');
                if (eq < 0) return out;
                String key = part.substring(0, eq);
                String[] v = part.substring(eq + 1).split(",", -1);
                switch (key) {
                    case "sd": if (!seat(c.driverSeat, v)) return out; seen |= 1; break;
                    case "sp": if (!seat(c.passengerSeat, v)) return out; seen |= 2; break;
                    case "cd": if (!climate(c.driverClimate, v)) return out; seen |= 4; break;
                    case "cp": if (!climate(c.passengerClimate, v)) return out; seen |= 8; break;
                    case "l": if (!light(c.light, v)) return out; seen |= 16; break;
                    default: return out;
                }
            }
            if (seen != 31) return out;
            if (v1) {
                for (Seat s : new Seat[]{c.driverSeat, c.passengerSeat}) if ("keep".equals(s.comfort)) s.comfort = "rest";
                for (Climate k : new Climate[]{c.driverClimate, c.passengerClimate}) if ("keep".equals(k.mode)) k.mode = "rest";
                if ("keep".equals(c.light.mode)) c.light.mode = "rest";
            }
            return c;
        } catch (RuntimeException invalid) {
            return out;
        }
    }

    private static boolean seat(Seat s, String[] v) {
        if (v.length != 3 || !in(v[0], SEAT_POSITIONS) || !in(v[1], SEAT_COMFORTS)) return false;
        int level = Integer.parseInt(v[2]);
        if (level < 1 || level > 3) return false;
        s.position = v[0]; s.comfort = v[1]; s.level = level; return true;
    }

    private static boolean climate(Climate c, String[] v) {
        if (v.length != 5 || !in(v[0], CLIMATE_MODES) || !in(v[2], FANS) || !in(v[3], DIRECTIONS) || !in(v[4], AC)) return false;
        int t = Integer.parseInt(v[1]);
        if (t < MIN_TEMP_TENTHS || t > MAX_TEMP_TENTHS || t % 5 != 0) return false;
        c.mode = v[0]; c.tempTenths = t; c.fan = v[2]; c.dir = v[3]; c.ac = v[4]; return true;
    }

    private static boolean light(Light l, String[] v) {
        if (v.length != 3 || !in(v[0], LIGHT_MODES) || !in(v[1], COLORS)) return false;
        int b = Integer.parseInt(v[2]);
        if (b < MIN_BRIGHT || b > MAX_BRIGHT || b % 5 != 0) return false;
        l.mode = v[0]; l.color = v[1]; l.bright = b; return true;
    }

    private static boolean in(String v, String[] options) {
        if (v == null) return false;
        for (String o : options) if (o.equals(v)) return true;
        return false;
    }
}
