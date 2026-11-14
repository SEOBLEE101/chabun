package ai.pitin.vehicle;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import ai.pleos.playground.navi.constants.NaviErrorCode;
import ai.pleos.playground.navi.constants.RouteOption;
import ai.pleos.playground.navi.data.NavigationScreenRequest;
import ai.pleos.playground.navi.data.RouteInfo;
import ai.pleos.playground.navi.helper.NaviHelper;
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener;
import kotlin.Unit;

/**
 * Pleos builds: hands the place to the car's own navigation through NaviHelper 3.0.5 ("Ready to Use", no approval).
 * RouteInfo's constructor is (latitude, longitude, displayLatitude, displayLongitude, poiName, poiId, address,
 * poiSubId, routeOption, poiProvider, isMobileRequest) — read from the AAR bytecode on 2026-11-14, because the
 * document's Kotlin and Java samples disagree on the first two. The six String/enum arguments must not be null.
 * A successful request is not a finished route. On the Pleos Connect emulator (navi service 3.0.4) the route search took
 * about 7 s and answered with onRouteStarted (not onRoutingCompleted as the document says), so any of the three counts.
 */
final class PlaceNavigatorFactory {
    static PlaceNavigator create(Context context) { return new PleosPlaceNavigator(context.getApplicationContext()); }

    private static final class PleosPlaceNavigator implements PlaceNavigator, NaviHelperEventListener {
        private static final long TIMEOUT_MS = 20_000;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final NaviHelper navi;
        private boolean ready, closed;
        private Callback pending;
        private final Runnable timeout = () -> finish(false, "내비가 응답하지 않았어요. 내비 화면에서 직접 찾아 주세요.");

        PleosPlaceNavigator(Context context) { navi = new NaviHelper(context); }

        public boolean live() { return true; }

        public void go(RestPlace place, Callback callback) {
            if (closed) { callback.done(false, "화면이 닫혀 길안내를 보내지 않았어요."); return; }
            if (pending != null) { callback.done(false, "앞의 요청을 기다리는 중이에요."); return; }
            pending = callback;
            main.postDelayed(timeout, TIMEOUT_MS);
            Runnable send = () -> {
                try {
                    if (place.hasCoordinates()) {
                        double[] p = place.latLon();
                        navi.requestRoute(new RouteInfo(p[0], p[1], null, null, place.name, "", place.address, "",
                                RouteOption.RECOMMENDED, "", false));
                    } else {
                        navi.requestNavigationScreen(new NavigationScreenRequest.SearchPlace(place.name));
                        // A search screen has no routing callback: the driver picks the result there.
                        finish(true, "내비에서 ‘" + place.name + "’을 찾았어요. 내비 화면에서 골라 주세요.");
                    }
                } catch (RuntimeException e) {
                    finish(false, "내비에 요청하지 못했어요. 내비 화면에서 직접 찾아 주세요.");
                }
            };
            if (ready) { send.run(); return; }
            try {
                navi.initialize(() -> { main.post(() -> { if (closed) return; ready = true; navi.addListener(this); send.run(); }); return Unit.INSTANCE; },
                        e -> { main.post(() -> finish(false, "이 차의 내비와 연결하지 못했어요. 내비 화면에서 직접 찾아 주세요.")); return Unit.INSTANCE; });
            } catch (RuntimeException e) {
                finish(false, "이 차의 내비와 연결하지 못했어요. 내비 화면에서 직접 찾아 주세요.");
            }
        }

        private void finish(boolean ok, String message) {
            main.removeCallbacks(timeout);
            Callback c = pending; pending = null;
            if (c != null) c.done(ok, message);
        }

        @Override public void onRouteStarted(ai.pleos.playground.navi.data.RouteStartInfo info) { main.post(() -> finish(true, "길안내를 시작했어요. 내비 화면을 확인해 주세요.")); }
        @Override public void onRoutingCompleted() { main.post(() -> finish(true, "길안내를 시작했어요. 내비 화면을 확인해 주세요.")); }
        @Override public void onDestinationChanged() { main.post(() -> finish(true, "목적지를 바꿨어요. 내비 화면을 확인해 주세요.")); }
        @Override public void onError(NaviErrorCode code) {
            main.post(() -> finish(false, code == NaviErrorCode.CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE
                    ? "너무 가까워 길안내를 만들지 않았어요." : "내비가 길을 찾지 못했어요. 내비 화면에서 직접 찾아 주세요."));
        }

        public void close() {
            if (closed) return;
            closed = true; main.removeCallbacks(timeout); pending = null;
            try { if (ready) navi.removeListener(this); navi.release(); } catch (RuntimeException ignored) { }
        }
    }
    private PlaceNavigatorFactory() { }
}
