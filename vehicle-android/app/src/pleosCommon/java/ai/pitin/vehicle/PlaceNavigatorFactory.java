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
 * about 7 s and answered with onRouteStarted (not onRoutingCompleted as the document says), so either counts.
 * onDestinationChanged does not count: the driver may have changed the destination on the navigation screen.
 * The listener is attached only while a route request waits, so an answer can only finish the request it belongs to.
 */
final class PlaceNavigatorFactory {
    static PlaceNavigator create(Context context) { return new PleosPlaceNavigator(context.getApplicationContext()); }

    private static final class PleosPlaceNavigator implements PlaceNavigator, NaviHelperEventListener {
        private static final long TIMEOUT_MS = 20_000;
        private static final String NO_LINK = "이 차의 내비와 연결하지 못했어요. 내비 화면에서 직접 찾아 주세요.";
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Context appContext;
        private NaviHelper navi;
        private boolean ready, initializing, closed, listening;
        private Callback pending;
        private RestPlace waiting;
        private java.util.function.BooleanSupplier stillParked;
        /** Read on the binder thread that delivers navigation events. */
        private volatile long generation;
        private long initSeq;
        private final Runnable timeout = this::timedOut;

        PleosPlaceNavigator(Context context) { appContext = context; navi = new NaviHelper(context); }

        public boolean live() { return true; }

        public void go(RestPlace place, Callback callback) { go(place, () -> true, callback); }

        @Override public void go(RestPlace place, java.util.function.BooleanSupplier parked, Callback callback) {
            if (closed) { callback.done(false, "화면이 닫혀 길안내를 보내지 않았어요."); return; }
            if (pending != null) { callback.done(false, "앞의 요청을 기다리는 중이에요."); return; }
            pending = callback; waiting = place; stillParked = parked; generation++;
            main.postDelayed(timeout, TIMEOUT_MS);
            if (ready) { send(); return; }
            if (initializing) return; // the running initialize sends the waiting place when it connects
            if (initSeq > 0) {
                // An earlier connect timed out: start over with a fresh helper instead of initializing the same one twice.
                detach();
                try { navi.release(); } catch (RuntimeException ignored) { }
                navi = new NaviHelper(appContext);
            }
            initializing = true;
            final long seq = ++initSeq;
            try {
                navi.initialize(() -> { main.post(() -> connected(seq)); return Unit.INSTANCE; },
                        e -> { main.post(() -> {
                            if (closed || ready || seq != initSeq) return; // a late failure of an attempt already given up
                            initializing = false; finish(false, NO_LINK);
                        }); return Unit.INSTANCE; });
            } catch (RuntimeException e) {
                initializing = false;
                finish(false, NO_LINK);
            }
        }

        private void connected(long seq) {
            if (closed || ready || seq != initSeq) return;
            initializing = false; ready = true;
            if (pending != null) send(); // a request that already timed out is not sent late
        }

        private void timedOut() {
            // The next press starts a fresh connect; a late answer to this one before then still makes the helper ready.
            if (!ready) initializing = false;
            finish(false, "내비가 응답하지 않았어요. 내비 화면에서 직접 찾아 주세요.");
        }

        private void send() {
            RestPlace place = waiting;
            if (place == null) return;
            if (stillParked != null && !stillParked.getAsBoolean()) {
                finish(false, "내비 연결을 기다리는 사이 주차 상태를 다시 확인하지 못했어요. 주차한 채로 다시 눌러 주세요.");
                return;
            }
            try {
                if (place.hasCoordinates()) {
                    if (!listening) { navi.addListener(this); listening = true; }
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
        }

        private void finish(boolean ok, String message) {
            main.removeCallbacks(timeout);
            detach();
            Callback c = pending; pending = null; waiting = null; stillParked = null;
            if (c != null) c.done(ok, message);
        }

        private void detach() {
            if (!listening) return;
            try { navi.removeListener(this); } catch (RuntimeException ignored) { }
            listening = false;
        }

        /** Only a request of this generation that still waits may be finished by an event. */
        private void event(long seen, boolean ok, String message) { if (seen == generation && pending != null) finish(ok, message); }

        @Override public void onRouteStarted(ai.pleos.playground.navi.data.RouteStartInfo info) { long g = generation; main.post(() -> event(g, true, "길안내를 시작했어요. 내비 화면을 확인해 주세요.")); }
        @Override public void onRoutingCompleted() { long g = generation; main.post(() -> event(g, true, "길안내를 시작했어요. 내비 화면을 확인해 주세요.")); }
        @Override public void onDestinationChanged() { /* may be the driver's own change on the navigation screen: not ours */ }
        @Override public void onError(NaviErrorCode code) {
            long g = generation;
            main.post(() -> event(g, false, code == NaviErrorCode.CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE
                    ? "너무 가까워 길안내를 만들지 않았어요." : "내비가 길을 찾지 못했어요. 내비 화면에서 직접 찾아 주세요."));
        }

        public void close() {
            if (closed) return;
            closed = true; main.removeCallbacks(timeout); pending = null; waiting = null;
            detach();
            try { navi.release(); } catch (RuntimeException ignored) { }
        }
    }
    private PlaceNavigatorFactory() { }
}
