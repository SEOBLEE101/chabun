package ai.pitin.vehicle;

import android.app.Activity;
import android.app.Instrumentation;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Synthetic demo Activity acceptance checks. No network, health data or real vehicle writes. */
public final class GraphiteInstrumentation extends Instrumentation {
    private MainActivity activity;
    private RestSession session;
    private int assertions;
    private final StringBuilder evidence = new StringBuilder();
    private File output;
    private String scenario = "full";

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null) scenario = arguments.getString("scenario", "full");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            // The demo flavor (".demo", or ".demo.redesign" for the redesign build) runs on synthetic signals only.
            if (!BuildConfig.SIMULATED_VEHICLE || !getTargetContext().getPackageName().matches(".*\\.demo(\\.redesign)?"))
                throw new AssertionError("This acceptance runner only permits the synthetic demo flavor.");
            output = new File(getTargetContext().getFilesDir(), "graphite-acceptance");
            if (!output.isDirectory() && !output.mkdirs()) throw new AssertionError("Cannot create evidence directory");
            evidence.append("Synthetic vehicle demo instrumentation. ")
                    .append("preparation-entry".equals(scenario) ? "Native controls only; no elapsed-time injection.\n" : "real-duration".equals(scenario)
                            ? "Real monotonic duration; no elapsed-time injection. Not a breath-count or sensor measurement.\n"
                            : "Boundary captures inject elapsed active time in test code only.\n");
            // The redesign's launch intro would cover the screen during the first checks; review builds can skip it.
            Intent intent = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("reviewSkipIntro", true);
            activity = (MainActivity) startActivitySync(intent);
            waitForIdleSync();
            session = (RestSession) field(activity, "session");
            evidence.append("Scenario: ").append(scenario).append('\n');
            if ("preparation-entry".equals(scenario)) {
                preparationEntry();
                complete(result);
                return;
            }
            if ("real-duration".equals(scenario)) {
                realDuration();
                complete(result);
                return;
            }
            if ("system-reduced".equals(scenario)) {
                systemReduced();
                complete(result);
                return;
            }
            if ("paused-resize".equals(scenario)) {
                pausedResize();
                complete(result);
                return;
            }
            onUi(this::prepareSession);
            capture("running");
            settle();
            onUi(() -> { visible("endButton"); visible("pauseButton"); visible("timerText"); });
            final long[] frozen = new long[1];
            final double[] shape = new double[1];
            onUi(() -> {
                ((Button) field(activity, "pauseButton")).performClick();
                frozen[0] = session.elapsedActiveMs(SystemClock.elapsedRealtime());
                shape[0] = session.expansion(SystemClock.elapsedRealtime());
            });
            SystemClock.sleep(10_000);
            settle();
            onUi(() -> {
                check(session.elapsedActiveMs(SystemClock.elapsedRealtime()) == frozen[0], "real 10-second pause freezes active clock");
                check(session.expansion(SystemClock.elapsedRealtime()) == shape[0], "real 10-second pause freezes point geometry");
                check(session.state() == RestSession.State.PAUSED, "pause remains manual");
                visible("resumeButton"); visible("endButton"); visible("timerText");
            });
            capture("paused-after-10-real-seconds");
            onUi(() -> {
                ((Button) field(activity, "resumeButton")).performClick();
                check(session.state() == RestSession.State.ACTIVE, "native Continue resumes explicitly");
                ((VehicleEnvironment) field(activity, "environment")).simulate("call"); render();
                check(session.state() == RestSession.State.PAUSED, "synthetic call pauses Activity session");
                Button resume = (Button) field(activity, "resumeButton");
                check(resume.isEnabled() && resume.getText().toString().equals("재개 준비하기"), "blocked main offers preparation route");
                resume.performClick();
                // Redesign: the preparation route opens the start screen (not the legacy setup panel), so the
                // legacy button may stay enabled; what must hold is that no press can resume while the call blocks.
                check(session.state() == RestSession.State.PAUSED, "preparation route never resumes while call blocks Continue");
                if (resume.isEnabled()) resume.performClick();
                check(session.state() == RestSession.State.PAUSED, "a second press still cannot resume while the call blocks");
                activity.showScreen(MainActivity.Screen.SESSION);
                ((VehicleEnvironment) field(activity, "environment")).simulate("parked"); render();
                check(session.state() == RestSession.State.PAUSED, "parking recovery never resumes automatically");
                // Leaving the rest screen cleared the on-screen confirmation (spec decision 3): resume through the
                // redesign's own press (시작 › 이어서 쉬기), which is the confirmation.
                activity.startRest();
                check(session.state() == RestSession.State.ACTIVE, "the redesign press resumes once parked again");
                phase(176_000);
                String label176 = ((TextView) field(activity, "titleText")).getText().toString();
                check(label176.equals("자연스럽게 쉬세요"), "176-second native label is natural rest (was '" + label176 + "', state " + session.state() + ")");
                check(session.expansion(SystemClock.elapsedRealtime()) == 0, "176-second normal shape is start point");
                // A 2560×1440 capture can take longer than the 4 s left; hold the 1 s tick so it cannot complete meanwhile.
                ((android.os.Handler) field(activity, "main")).removeCallbacks((Runnable) field(activity, "tick"));
            });
            capture("synthetic-phase-176-natural-rest");
            onUi(() -> {
                phase(178_000);
                ((android.os.Handler) field(activity, "main")).post((Runnable) field(activity, "tick"));
                ((Button) field(activity, "pauseButton")).performClick();
                check(session.secondsRemaining(SystemClock.elapsedRealtime()) == 2, "native pause at synthetic 178 seconds retains 2 seconds");
                check(session.state() == RestSession.State.PAUSED, "178-second pause is PAUSED (was " + session.state() + ", screen " + activity.screen + ")");
            });
            capture("synthetic-phase-178-paused");
            onUi(() -> {
                ((Button) field(activity, "resumeButton")).performClick();
                ((android.os.Handler) field(activity, "main")).removeCallbacks((Runnable) field(activity, "tick"));
                ((View) field(activity, "orb")).setVisibility(View.INVISIBLE);
            });
            SystemClock.sleep(2_300);
            onUi(() -> {
                check(session.state() == RestSession.State.COMPLETE, "independent deadline completes with tick removed and renderer hidden");
                check(session.elapsedActiveMs(SystemClock.elapsedRealtime()) == 180_000, "deadline caps active elapsed at180000");
                check(!session.resume(SystemClock.elapsedRealtime(), safe()), "late resume cannot resurrect completion");
                ((View) field(activity, "orb")).setVisibility(View.VISIBLE); render();
                check(((TextView) field(activity, "titleText")).getText().toString().replace('\n', ' ').equals("3분 쉬기를 마쳤어요"), "completed native label");
            });
            capture("completed-by-independent-deadline");
            onUi(() -> {
                // Redesign: 3분 쉬기 on 시작 is the start and the on-screen confirmation (spec decision 3).
                // The tick was held above, so refresh the synthetic parked reading before the gate looks at it.
                ((VehicleEnvironment) field(activity, "environment")).simulate("parked");
                ((android.os.Handler) field(activity, "main")).post((Runnable) field(activity, "tick"));
                activity.startRest();
                check(session.state() == RestSession.State.ACTIVE, "the redesign press starts a new rest");
                ((Button) field(activity, "endButton")).performClick();
                check(session.state() == RestSession.State.ENDED, "native early End remains distinct");
                check(((TextView) field(activity, "titleText")).getText().toString().equals("쉬기를 마쳤어요"), "early-end native label");
                check(!session.resume(SystemClock.elapsedRealtime(), safe()), "late resume cannot resurrect early end");
                render();
                check(((Button) field(activity, "resumeButton")).getVisibility() == View.GONE, "ended has no Continue control");
                set(activity, "appReducedMotion", true);
                render();
                activity.startRest();
                phase(176_000);
                check(session.expansion(SystemClock.elapsedRealtime()) == .5, "app reduced motion stays .5 at176");
            });
            capture("synthetic-phase-176-reduced-motion");
            onUi(() -> {
                ((Button) field(activity, "endButton")).performClick();
                set(activity, "appReducedMotion", false); render();
            });
            delayedDisconnect();
            complete(result);
        } catch (Throwable failure) {
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            evidence.append("FAIL: ").append(trace).append('\n');
            try { saveReport(); } catch (Exception ignored) { }
            result.putString("stream", evidence.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void complete(Bundle result) throws Exception {
        onUi(() -> activity.finish());
        evidence.append("PASS: ").append(assertions).append(" native assertions.\n");
        result.putString("stream", evidence.toString());
        result.putInt("assertions", assertions);
        result.putString("evidencePath", output.getAbsolutePath());
        saveReport();
        finish(Activity.RESULT_OK, result);
    }

    /** Redesign start: choose the rest, synthetic parking, then the 3분 쉬기 press (start + on-screen confirmation). */
    private void prepareSession() {
        set(activity, "selectedPreset", "quiet_rest");
        ((VehicleEnvironment) field(activity, "environment")).simulate("parked");
        render();
        activity.startRest();
        check(session.state() == RestSession.State.ACTIVE, "explicit Start becomes ACTIVE");
    }

    /** The redesign's way in: 시작 → (시작 보류 when not parked) → 3분 쉬기. Nothing starts without the press. */
    private void preparationEntry() throws Exception {
        onUi(() -> {
            check(activity.screen == MainActivity.Screen.HOME, "first screen is 시작 (Home)");
            check(session.state() != RestSession.State.ACTIVE, "opening the app never starts a rest");
            ((VehicleEnvironment) field(activity, "environment")).simulate("unknown"); render();
            activity.startRest();
            check(session.state() != RestSession.State.ACTIVE, "unknown vehicle state blocks the 3분 쉬기 press");
            check(activity.screen == MainActivity.Screen.BLOCKED, "a refused start shows 시작 보류 with reasons");
            activity.showScreen(MainActivity.Screen.PREPARATION);
            check(session.state() != RestSession.State.ACTIVE, "opening 시작 전에 바꾸기 never starts the session");
            activity.commitRestChoice("quiet_rest", false);
            check("quiet_rest".equals(activity.selectedPreset), "chosen rest is kept");
            activity.showScreen(MainActivity.Screen.HOME);
            ((VehicleEnvironment) field(activity, "environment")).simulate("parked"); render();
            check(session.state() != RestSession.State.ACTIVE, "parking alone never starts a rest");
        });
        capture("preparation-ready");
        onUi(() -> {
            activity.startRest();
            check(session.state() == RestSession.State.ACTIVE, "the 3분 쉬기 press starts once parked");
            check(activity.screen == MainActivity.Screen.SESSION, "start shows the rest screen");
            ((Button) field(activity, "endButton")).performClick();
            check(session.state() == RestSession.State.ENDED, "마치기 ends the rest");
            activity.showScreen(MainActivity.Screen.HOME);
            check(activity.screen == MainActivity.Screen.HOME && session.state() == RestSession.State.ENDED, "going home never restarts a finished rest");
            activity.showScreen(MainActivity.Screen.CONNECTIONS);
            check(session.state() == RestSession.State.ENDED, "폰 연결 never starts a rest");
            activity.pairingId = "ui-review-fixture"; activity.pairingStatus = "paired";
            activity.proposal = RecoveryProposal.manual(System.currentTimeMillis());
            activity.manualMode = true; activity.markConnectionVerified(); activity.render();
            check(!activity.hasRemoteProposal(), "a manual proposal is never shown as a received summary");
            activity.connectionVerified = false; activity.render();
            check(!activity.isConnectionCurrent(), "an unverified connection is not shown as current");
            activity.markConnectionVerified(); activity.connectionVerifiedAtMs -= 10_001; activity.render();
            check(!activity.isConnectionCurrent(), "an expired verification is not shown as current");
            activity.pairingId = null; activity.clearProposal(); activity.manualMode = false;
            activity.showScreen(MainActivity.Screen.HOME);
        });
    }

    private boolean clickText(View view, String text) {
        if (view instanceof Button && ((Button) view).getText().toString().equals(text)) {
            check(view.isEnabled(), "enabled control: " + text);
            view.performClick();
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (clickText(group.getChildAt(i), text)) return true;
        }
        if (view == activity.getWindow().getDecorView()) throw new AssertionError("Missing control: " + text);
        return false;
    }

    private void realDuration() throws Exception {
        final long[] startMs = new long[1];
        onUi(() -> {
            render();
            check(ValueAnimator.areAnimatorsEnabled(), "normal real-duration requires system animators enabled");
            check(!session.reducedMotion(), "normal real-duration requires effective reduced motion off before start");
            evidence.append("MODE beforeStart systemAnimatorsEnabled=true effectiveReducedMotion=false\n");
            prepareSession();
            long observed = SystemClock.elapsedRealtime();
            startMs[0] = observed - session.elapsedActiveMs(observed);
            check(session.elapsedActiveMs(observed) < 1_000, "real-duration begins near zero without time injection");
        });
        progress("REAL_DURATION_STARTED monotonicStartMs=" + startMs[0]);
        capture("real-running");
        waitUntil(startMs[0] + 176_000);
        onUi(() -> {
            render();
            long elapsed = session.elapsedActiveMs(SystemClock.elapsedRealtime());
            check(elapsed >= 176_000 && elapsed < 180_000, "real monotonic176-second observation is inside natural-rest interval");
            check(session.state() == RestSession.State.ACTIVE, "real176-second session is still ACTIVE");
            check(session.segment(SystemClock.elapsedRealtime()) == RestSession.Segment.NATURAL_REST, "real176-second segment is natural rest");
            check(((TextView) field(activity, "titleText")).getText().toString().equals("자연스럽게 쉬세요"), "real176-second native natural-rest label");
            check(!session.reducedMotion(), "normal motion remains effective at176 seconds");
            check(session.expansion(SystemClock.elapsedRealtime()) == 0, "normal real176-second point is at path start");
            evidence.append("MODE naturalRest effectiveReducedMotion=false expansion=0\n");
            evidence.append("OBSERVED naturalRestWallMs=").append(SystemClock.elapsedRealtime() - startMs[0]).append('\n');
        });
        progress("REAL_DURATION_176_NATURAL_REST");
        capture("real-natural176");
        waitUntil(startMs[0] + 180_200);
        onUi(() -> {
            check(session.state() == RestSession.State.COMPLETE, "real180-second session reaches COMPLETE");
            check(session.elapsedActiveMs(SystemClock.elapsedRealtime()) == 180_000, "real-duration clock caps at180000ms");
            check(((TextView) field(activity, "titleText")).getText().toString().replace('\n', ' ').equals("3분 쉬기를 마쳤어요"), "real180-second native completion label");
            check(!session.reducedMotion(), "normal motion remains effective at completion");
            check(session.expansion(SystemClock.elapsedRealtime()) == 0, "normal real completion point is at path start");
            evidence.append("MODE completed effectiveReducedMotion=false expansion=0\n");
            evidence.append("OBSERVED completionWallMs=").append(SystemClock.elapsedRealtime() - startMs[0]).append('\n');
        });
        progress("REAL_DURATION_180_COMPLETE");
        capture("real-complete");
    }

    private void waitUntil(long deadlineMs) {
        while (SystemClock.elapsedRealtime() < deadlineMs)
            SystemClock.sleep(Math.min(500, Math.max(1, deadlineMs - SystemClock.elapsedRealtime())));
        waitForIdleSync();
    }

    private void progress(String message) {
        evidence.append(message).append('\n');
        Bundle update = new Bundle(); update.putString("stream", message + "\n"); sendStatus(1, update);
    }

    private void systemReduced() throws Exception {
        onUi(() -> {
            check(!ValueAnimator.areAnimatorsEnabled(), "host system animator-disable preference is active");
            set(activity, "appReducedMotion", false);
            prepareSession();
            phase(2_000);
            check(session.reducedMotion(), "system preference wins when app reduced motion is off");
            check(session.expansion(SystemClock.elapsedRealtime()) == .5, "system-only reduced motion fixes point at .5");
            set(activity, "appReducedMotion", true); render();
            check(session.expansion(SystemClock.elapsedRealtime()) == .5, "both reduced motion settings retain .5");
            set(activity, "appReducedMotion", false); phase(176_000);
            check(session.expansion(SystemClock.elapsedRealtime()) == .5, "app toggle cannot weaken system setting at176");
            ((Button) field(activity, "pauseButton")).performClick();
        });
        capture("system-only-reduced-motion-176-paused");
        onUi(() -> { visible("endButton"); visible("resumeButton"); visible("timerText"); });
    }

    private void pausedResize() throws Exception {
        final long[] elapsed = new long[1];
        final double[] expansion = new double[1];
        final int[] initialWidth = new int[1];
        onUi(() -> {
            prepareSession(); phase(2_000);
            ((Button) field(activity, "pauseButton")).performClick();
            elapsed[0] = session.elapsedActiveMs(SystemClock.elapsedRealtime());
            expansion[0] = session.expansion(SystemClock.elapsedRealtime());
            initialWidth[0] = activity.getWindow().getDecorView().getWidth();
        });
        capture("paused-before-host-resize");
        Bundle ready = new Bundle();
        ready.putString("stream", "WAIT_FOR_RESIZE: change this emulator's actual window/display width within20s.\n");
        sendStatus(1, ready);
        final int[] currentWidth = {initialWidth[0]};
        long timeout = SystemClock.elapsedRealtime() + 20_000;
        while (currentWidth[0] == initialWidth[0] && SystemClock.elapsedRealtime() < timeout) {
            SystemClock.sleep(200);
            onUi(() -> currentWidth[0] = activity.getWindow().getDecorView().getWidth());
        }
        onUi(() -> {
            check(currentWidth[0] != initialWidth[0], "actual window width changed from" + initialWidth[0] + " to" + currentWidth[0]);
            check(field(activity, "session") == session, "resize preserves same in-memory session");
            check(session.state() == RestSession.State.PAUSED, "resize never resumes session");
            check(session.elapsedActiveMs(SystemClock.elapsedRealtime()) == elapsed[0], "resize preserves frozen active time");
            check(session.expansion(SystemClock.elapsedRealtime()) == expansion[0], "resize preserves frozen path fraction");
        });
        capture("paused-after-host-resize");
        onUi(() -> { visible("endButton"); visible("resumeButton"); visible("timerText"); });
    }

    private void delayedDisconnect() throws Exception {
        CountDownLatch releaseOldResponse = new CountDownLatch(1);
        AtomicBoolean oldAccepted = new AtomicBoolean();
        AtomicBoolean currentAccepted = new AtomicBoolean();
        onUi(() -> {
            request(() -> { releaseOldResponse.await(5, TimeUnit.SECONDS); return true; }, ignored -> {
                oldAccepted.set(true);
                set(activity, "proposal", RecoveryProposal.manual(System.currentTimeMillis()));
            });
            invoke("disconnect");
            request(() -> true, ignored -> currentAccepted.set(true));
            check(field(activity, "proposal") == null, "disconnect immediately clears displayed summary");
        });
        releaseOldResponse.countDown();
        ((ExecutorService) field(activity, "network")).submit(() -> { }).get(5, TimeUnit.SECONDS);
        waitForIdleSync();
        onUi(() -> {
            check(!oldAccepted.get(), "pre-disconnect delayed response is rejected by epoch");
            check(field(activity, "proposal") == null, "late callback cannot restore revoked summary");
            check(currentAccepted.get(), "current post-disconnect callback still completes");
        });
    }
    private void request(Callable<Boolean> task, Consumer<Boolean> callback) {
        try {
            Method method = MainActivity.class.getDeclaredMethod("request", Callable.class, Consumer.class);
            method.setAccessible(true); method.invoke(activity, task, callback);
        } catch (Exception error) { throw new AssertionError(error); }
    }

    /** Lets layout and short transitions finish before a visibility check. */
    private void settle() { waitForIdleSync(); SystemClock.sleep(600); waitForIdleSync(); }
    private void phase(long activeMs) {
        set(session, "elapsedMs", activeMs); set(session, "anchorMs", SystemClock.elapsedRealtime());
        render();
    }
    private SafetyGate.Decision safe() {
        long now = SystemClock.elapsedRealtime();
        return SafetyGate.preview(new SafetyGate.Snapshot(true, SafetyGate.Gear.PARK, 0.0,
                false, false, true, now), now, true, true);
    }
    private void visible(String name) {
        View view = (View) field(activity, name);
        Rect visible = new Rect();
        check(view.getGlobalVisibleRect(visible) && visible.height() == view.getHeight()
                && visible.width() == view.getWidth(), name + " fully visible in actual app bounds");
    }
    private void capture(String name) throws Exception {
        waitForIdleSync(); SystemClock.sleep(500);
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("Native screenshot unavailable");
        File file = new File(output, name + ".png");
        try (FileOutputStream stream = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        bitmap.recycle();
        evidence.append("SCREENSHOT ").append(file.getName()).append('\n');
    }
    private void saveReport() throws Exception {
        if (output == null) return;
        try (FileOutputStream stream = new FileOutputStream(new File(output, "native-report.txt"))) {
            stream.write(evidence.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
    private void onUi(Runnable action) {
        final Throwable[] problem = new Throwable[1];
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { problem[0] = error; } });
        if (problem[0] != null) throw new AssertionError("UI scenario failed", problem[0]);
        waitForIdleSync();
    }
    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++; evidence.append("PASS ").append(message).append('\n');
    }
    private void render() {
        invoke("render");
    }
    private void invoke(String name) {
        try { Method method = MainActivity.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(activity); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static Object field(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static void set(Object object, String name, Object value) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
