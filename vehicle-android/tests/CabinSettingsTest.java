package ai.pitin.vehicle;

/** 차 안 설정 value rules (spec 6-2, 6-2-1): rest / keep / own values, draft vs saved, strict storage and v1 reading. */
public final class CabinSettingsTest {
    private static int assertions;

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        CabinSettings saved = new CabinSettings();
        check("쉼 따르기".equals(CabinSettings.seatText(saved.driverSeat)), "default seat follows the rest");
        check("쉼 따르기".equals(CabinSettings.climateText(saved.driverClimate)), "default climate follows the rest");
        check("쉼 따르기".equals(CabinSettings.lightText(saved.light)), "default light follows the rest");
        CabinSettings still = new CabinSettings();
        check(CabinSettings.setSeatComfort(still.driverSeat, "keep") && "그대로".equals(CabinSettings.seatText(still.driverSeat)), "keep words");
        check(CabinSettings.setClimateMode(still.driverClimate, "keep") && "그대로".equals(CabinSettings.climateText(still.driverClimate)), "keep climate");
        check(CabinSettings.setLightMode(still.light, "keep") && "그대로".equals(CabinSettings.lightText(still.light)), "keep light");

        CabinSettings draft = saved.copy();
        check(draft.changedCount(saved) == 0, "copy unchanged");
        // Seat: heat and vent are one choice; level only while heating or ventilating.
        check(!CabinSettings.setSeatLevel(draft.driverSeat, 2), "level needs heat/vent");
        check(CabinSettings.setSeatComfort(draft.driverSeat, "heat"), "heat");
        check(CabinSettings.setSeatLevel(draft.driverSeat, 2), "level 2");
        check(!CabinSettings.setSeatLevel(draft.driverSeat, 4) && !CabinSettings.setSeatLevel(draft.driverSeat, 0), "level 1-3");
        check(CabinSettings.setSeatComfort(draft.driverSeat, "vent") && "vent".equals(draft.driverSeat.comfort), "vent replaces heat");
        check(!CabinSettings.setSeatComfort(draft.driverSeat, "both"), "no both");
        check(CabinSettings.setSeatPosition(draft.passengerSeat, "manual"), "manual position");
        check(!CabinSettings.setSeatPosition(draft.passengerSeat, "recline"), "no angle commands");
        check("통풍 2단계".equals(CabinSettings.seatText(draft.driverSeat)), "seat words");
        check("쉼 따르기 · 자세 직접".equals(CabinSettings.seatText(draft.passengerSeat)), "position words");
        check(draft.changedCount(saved) == 2, "two seat groups changed");

        // Climate: keep means untouched; temperature 18–28 in 0.5 steps.
        check(!CabinSettings.stepTemp(draft.driverClimate, 1), "no temp while keep");
        check(!CabinSettings.setFan(draft.driverClimate, "high"), "no fan while keep");
        check(CabinSettings.setClimateMode(draft.driverClimate, "custom"), "custom");
        check(CabinSettings.stepTemp(draft.driverClimate, -1) && draft.driverClimate.tempTenths == 235, "23.5");
        for (int i = 0; i < 30; i++) CabinSettings.stepTemp(draft.driverClimate, -1);
        check(draft.driverClimate.tempTenths == 180, "floor 18.0");
        for (int i = 0; i < 30; i++) CabinSettings.stepTemp(draft.driverClimate, 1);
        check(draft.driverClimate.tempTenths == 280, "ceiling 28.0");
        check(CabinSettings.setFan(draft.driverClimate, "low") && CabinSettings.setDirection(draft.driverClimate, "upper")
                && CabinSettings.setAc(draft.driverClimate, "on"), "climate options");
        check(!CabinSettings.setFan(draft.driverClimate, "turbo") && !CabinSettings.setDirection(draft.driverClimate, "left"), "enums only");
        check("28.0° 약하게 위로 냉방 켜기".equals(CabinSettings.climateText(draft.driverClimate)), "climate words");

        // Light: colours and brightness 10–100 by 5.
        check(!CabinSettings.setColor(draft.light, "sage"), "no colour while keep");
        check(CabinSettings.setLightMode(draft.light, "custom") && CabinSettings.setColor(draft.light, "sage"), "sage");
        check(CabinSettings.setBright(draft.light, 47) && draft.light.bright == 45, "snap to 5");
        check(!CabinSettings.setBright(draft.light, 5) && !CabinSettings.setBright(draft.light, 105), "range");
        check("올리브 45%".equals(CabinSettings.lightText(draft.light)), "light words");
        check(CabinSettings.setLightMode(draft.light, "off") && "끄기".equals(CabinSettings.lightText(draft.light)), "off words");
        check(draft.changedCount(saved) == 4, "four groups changed");

        // Save keeps the draft; revert returns to the last save.
        CabinSettings kept = draft.copy();
        check(kept.changedCount(draft) == 0 && kept.encode().equals(draft.encode()), "save copies");
        CabinSettings.stepTemp(draft.driverClimate, -2);
        check(draft.changedCount(kept) == 1, "edit after save");
        draft = kept.copy();
        check(draft.changedCount(kept) == 0, "revert to last save");

        // Strict storage round trip.
        CabinSettings back = CabinSettings.decode(kept.encode());
        check(back.encode().equals(kept.encode()), "round trip");
        String[] broken = {null, "", "v2;sd=keep,keep,1", "v1;sd=keep,keep,1", kept.encode().replace("280", "285"),
                kept.encode().replace("sage", "pink"), kept.encode() + ";x=1", kept.encode().replace(",45", ",7"),
                kept.encode().replace("vent,2", "vent,9"), "v1;" + "a".repeat(500), "v3" + kept.encode().substring(2)};
        for (String b : broken) check(CabinSettings.decode(b).encode().equals(new CabinSettings().encode()), "broken → defaults");
        // A v1 line had no ‘쉼 따르기’: its "keep" let the rest bring its own climate, so it reads as following the rest.
        CabinSettings old = CabinSettings.decode("v1;sd=keep,keep,1;sp=keep,heat,2;cd=keep,240,auto,keep,keep;cp=custom,220,low,keep,keep;l=keep,cream,60");
        check("rest".equals(old.driverSeat.comfort) && "heat".equals(old.passengerSeat.comfort), "v1 seats");
        check("rest".equals(old.driverClimate.mode) && "custom".equals(old.passengerClimate.mode), "v1 climate");
        check("rest".equals(old.light.mode) && old.encode().startsWith("v2;"), "v1 light, saved as v2");

        // SafetyGate's own switch stays off: cabin writes go through CabinControl's parked gate in the pleos build only.
        check(!SafetyGate.permitsVehicleWrite(null), "SafetyGate.permitsVehicleWrite stays false");
        System.out.println("PASS " + assertions + " cabin settings assertions; SafetyGate.permitsVehicleWrite stays false");
    }
}
