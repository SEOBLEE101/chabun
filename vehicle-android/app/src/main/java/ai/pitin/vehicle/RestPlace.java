package ai.pitin.vehicle;

import java.util.Locale;

/**
 * A "쉬어 갈 곳" the phone sent (name, address, optional coordinates), read from the shared state. Pure Java so the
 * policy checks run without Android. It never carries visit counts or favourites; the car only shows it and, on the
 * driver's press, asks the car's navigation for a route.
 */
final class RestPlace {
    static final int NAME_MAX = 40;
    static final int ADDRESS_MAX = 120;

    final String id;
    final String name;
    final String address;
    final Double lat;
    final Double lon;

    private RestPlace(String id, String name, String address, Double lat, Double lon) {
        this.id = id; this.name = name; this.address = address; this.lat = lat; this.lon = lon;
    }

    /** Null when anything is out of shape, so a bad state can never reach the navigation. */
    static RestPlace of(String id, String name, String address, Double lat, Double lon) {
        if (id == null || !id.startsWith("place-") || id.length() > 64) return null;
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > NAME_MAX) return null;
        String a = address == null ? "" : address.trim();
        if (a.length() > ADDRESS_MAX) return null;
        if (hasControl(n) || hasControl(a) || id.length() == "place-".length()) return null;
        if ((lat == null) != (lon == null)) return null;
        if (lat != null) {
            if (lat.isNaN() || lon.isNaN() || lat.isInfinite() || lon.isInfinite() || lat < 33.0 || lat > 39.0 || lon < 124.0 || lon > 132.0) return null;
        }
        return new RestPlace(id, n, a, lat, lon);
    }

    private static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c < ' ' || c == 0x7f) return true; }
        return false;
    }

    boolean hasCoordinates() { return lat != null && lon != null; }

    /** What the car asks the navigation for: a route to the point when there are coordinates, else a name search. */
    String routeKind() { return hasCoordinates() ? "route" : "search"; }

    String buttonLabel() { return hasCoordinates() ? "길안내 시작" : "내비에서 찾기"; }

    String summary() {
        String where = address.isEmpty() ? "주소 없음" : address;
        return hasCoordinates() ? where : where + " · 위치 없이 이름만 왔어요";
    }

    /** Coordinates as the navigation expects them: latitude first, then longitude (checked in the NaviHelper 3.0.5 AAR). */
    double[] latLon() {
        if (!hasCoordinates()) throw new IllegalStateException("no coordinates");
        return new double[] { lat, lon };
    }

    @Override public String toString() {
        return hasCoordinates() ? String.format(Locale.US, "%s (%.4f, %.4f)", name, lat, lon) : name;
    }
}
