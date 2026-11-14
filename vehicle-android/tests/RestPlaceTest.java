package ai.pitin.vehicle;

public final class RestPlaceTest {
    private static int assertions;
    private static void check(boolean ok, String what) { assertions++; if (!ok) throw new AssertionError("RestPlace: " + what); }

    public static void main(String[] args) {
        RestPlace p = RestPlace.of("place-1", " 한강 산책로 ", "서울 성동구", 37.5432, 127.0456);
        check(p != null && p.name.equals("한강 산책로"), "trim");
        check(p.hasCoordinates() && p.routeKind().equals("route") && p.buttonLabel().equals("길안내 시작"), "route");
        double[] ll = p.latLon();
        check(ll[0] == 37.5432 && ll[1] == 127.0456, "latitude first");
        RestPlace named = RestPlace.of("place-2", "동네 공원", "", null, null);
        check(named != null && !named.hasCoordinates() && named.routeKind().equals("search") && named.buttonLabel().equals("내비에서 찾기"), "name only");
        check(named.summary().contains("이름만"), "name-only summary");
        boolean threw = false; try { named.latLon(); } catch (IllegalStateException e) { threw = true; }
        check(threw, "no coordinates throws");
        check(RestPlace.of("place-3", "곳", "", 37.5, null) == null, "lat without lon");
        check(RestPlace.of("place-3", "곳", "", null, 127.0) == null, "lon without lat");
        check(RestPlace.of("place-3", "곳", "", 48.85, 2.35) == null, "outside Korea");
        check(RestPlace.of("place-3", "곳", "", Double.NaN, 127.0) == null, "NaN");
        check(RestPlace.of("place-3", " ", "", null, null) == null, "blank name");
        check(RestPlace.of("place-3", "가".repeat(41), "", null, null) == null, "long name");
        check(RestPlace.of("place-3", "곳", "가".repeat(121), null, null) == null, "long address");
        check(RestPlace.of("phone-1", "곳", "", null, null) == null, "id prefix");
        check(RestPlace.of(null, "곳", "", null, null) == null, "null id");
        check(RestPlace.of("place-" + "x".repeat(60), "곳", "", null, null) == null, "long id");
        check(RestPlace.of("place-5", "줄\n바꿈", "", null, null) == null, "control char in name");
        check(RestPlace.of("place-5", "곳", "주\t소", null, null) == null, "control char in address");
        check(RestPlace.of("place-", "곳", "", null, null) == null, "empty id suffix");
        check(RestPlace.of("place-5", "곳", "", Double.POSITIVE_INFINITY, 127.0) == null, "infinite latitude");
        check(RestPlace.of("place-4", "곳", null, 33.0, 124.0) != null && RestPlace.of("place-4", "곳", null, 39.0, 132.0) != null, "range edges");
        System.out.println("PASS: " + assertions + " RestPlace assertions.");
    }
}
