package ai.pitin.vehicle;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pleos emulator check: hands a fixed test place (a public landmark, not user data) to the car's navigation through
 * PlaceNavigatorFactory, then a name-only place, and reports what the navigation answered. Run with
 * -PplaceNavAcceptance=true. It does not drive the car or change cabin values.
 */
public final class PlaceNavigationInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle out = new Bundle();
        StringBuilder report = new StringBuilder();
        int code = Activity.RESULT_OK;
        try {
            RestPlace point = RestPlace.of("place-test-1", "서울시청", "서울 중구 세종대로 110", 37.5663, 126.9779);
            RestPlace named = RestPlace.of("place-test-2", "서울숲", "", null, null);
            if (point == null || named == null) throw new AssertionError("fixture");
            for (RestPlace p : new RestPlace[] { point, named }) {
                AtomicReference<String> answer = new AtomicReference<>();
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<PlaceNavigator> nav = new AtomicReference<>();
                runOnMainSync(() -> {
                    nav.set(PlaceNavigatorFactory.create(getTargetContext()));
                    nav.get().go(p, (ok, message) -> { answer.set((ok ? "OK " : "NO ") + message); done.countDown(); });
                });
                boolean called = done.await(25, TimeUnit.SECONDS);
                report.append(p.routeKind()).append(" live=").append(nav.get().live()).append(" → ")
                        .append(called ? answer.get() : "NO CALLBACK in 25 s").append('\n');
                if (!called) code = Activity.RESULT_CANCELED;
                runOnMainSync(() -> nav.get().close());
                Thread.sleep(3000);
            }
        } catch (Throwable t) {
            code = Activity.RESULT_CANCELED;
            report.append("FAILED: ").append(t);
        }
        out.putString(Instrumentation.REPORT_KEY_STREAMRESULT, report.toString());
        finish(code, out);
    }
}
