package ai.pitin.vehicle;

import android.content.Context;

/** The demo build has no navigation: it says so and shows the place, nothing is sent anywhere. */
final class PlaceNavigatorFactory {
    static PlaceNavigator create(Context context) {
        return new PlaceNavigator() {
            public boolean live() { return false; }
            public void go(RestPlace place, Callback callback) {
                callback.done(false, "체험용 차라 길안내는 시작하지 않았어요. 내비에서 ‘" + place.name + "’을 찾아 주세요.");
            }
            public void close() { }
        };
    }
    private PlaceNavigatorFactory() { }
}
