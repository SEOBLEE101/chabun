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
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Test APK only. Real UI/result/network flow and unaccelerated monotonic time.
 * Phone selection and pairing are performed by the host, never fabricated here.
 * No production fields, safety values, snapshots or clocks are assigned by this runner.
 */
public final class RestFlowInstrumentation extends Instrumentation {
    private MainActivity activity;
    private ConnectedPresetsActivity connected;
    private RestSession session;
    private File output;
    private int assertions;
    private final StringBuilder evidence = new StringBuilder();
    private long nextProgress;
    private long started;

    /** Optional {@code -e relayOrigin https://127.0.0.1:9443}: a local test relay reached through adb reverse (review builds). */
    private String relayOrigin;

    @Override public void onCreate(Bundle args) { super.onCreate(args); if (args != null) relayOrigin = args.getString("relayOrigin"); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check(BuildConfig.SIMULATED_VEHICLE && getTargetContext().getPackageName().matches(".*\\.demo(\\.redesign)?"), "demo flavor only");
            output = new File(getTargetContext().getFilesDir(), "rest-flow-acceptance");
            if (!output.isDirectory() && !output.mkdirs()) throw new AssertionError("Cannot create report directory");
            File proceed = new File(getTargetContext().getFilesDir(), "continue-rest-flow");
            File release = new File(getTargetContext().getFilesDir(), "release-rest-flow");
            check(!proceed.exists() && !release.exists(), "host handshake files absent before run");
            progress("TEST_SCOPE synthetic vehicle controls; real phone/server selection and real180 active seconds; no time injection");
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("reviewSkipIntro", true)
                    .putExtra("reviewRelayOrigin", relayOrigin));
            waitForIdleSync();
            session = (RestSession) field(activity, "session");
            onUi(() -> {
                check(session.state() == RestSession.State.IDLE, "new MainActivity starts IDLE");
                check(!(Boolean) field(activity, "localConfirmation"), "no on-screen confirmation before a press");
                check(ValueAnimator.areAnimatorsEnabled(), "normal-motion acceptance requires system animators enabled");
                check(!session.reducedMotion(), "normal-motion acceptance requires effective reduced motion off");
            });
            // Redesign navigation: 시작 › 폰 연결 › 함께 쓰는 쉼 목록.
            clickText(activity, "폰 연결");
            ActivityMonitor monitor = addMonitor(ConnectedPresetsActivity.class.getName(), null, false);
            clickText(activity, "함께 쓰는 쉼 목록  ›");
            Activity opened = waitForMonitorWithTimeout(monitor, 10_000);
            removeMonitor(monitor);
            check(opened instanceof ConnectedPresetsActivity, "real navigation opened ConnectedPresetsActivity");
            connected = (ConnectedPresetsActivity) opened;
            progress("READY_FOR_PAIRING write files/continue-rest-flow with exact selected preset name UTF-8 only after phone quiet_rest selection");
            awaitFile(proceed, 600_000, "WAITING_FOR_PHONE_SELECTION");
            String expectedName = new String(Files.readAllBytes(proceed.toPath()), StandardCharsets.UTF_8).trim();
            check(!expectedName.isEmpty(), "host supplied expected shared preset name");
            awaitSelection(expectedName);
            onUi(() -> {
                check(session.state() == RestSession.State.IDLE, "shared selection did not start rest");
                check(!(Boolean) field(activity, "localConfirmation"), "shared selection did not grant confirmation");
            });
            clickText(connected, "이 선호로 쉬기 준비");
            awaitPrepared(expectedName);
            capture("01-prepared-no-confirmation");
            clickText(activity, "이 설정으로");
            onUi(() -> {
                check(activity.screen == MainActivity.Screen.HOME, "preparation returns to 시작");
                activity.shell.openDevSheet();
            });
            clickText(activity, "주차");
            onUi(() -> {
                activity.shell.openDevSheet(); // closes the demo sheet again
                check(session.state() == RestSession.State.IDLE, "the demo 주차 control does not start a rest");
                check(!(Boolean) field(activity, "localConfirmation"), "parking grants no confirmation");
            });
            clickText(activity, "3분 쉬기");
            started = SystemClock.elapsedRealtime();
            onUi(() -> {
                check(session.state() == RestSession.State.ACTIVE, "the 3분 쉬기 press creates an ACTIVE session");
                check(session.elapsedActiveMs(SystemClock.elapsedRealtime()) < 1500, "session begins near zero without clock injection");
            });
            capture("02-started");
            waitActive(20_000);
            clickText(activity, "일시정지"); // whichever pause control the current mood shows
            final long[] frozen = new long[1];
            final double[] shape = new double[1];
            final String[] timer = new String[1];
            onUi(() -> {
                check(session.state() == RestSession.State.PAUSED, "actual Pause pauses session");
                frozen[0] = session.elapsedActiveMs(SystemClock.elapsedRealtime());
                shape[0] = session.expansion(SystemClock.elapsedRealtime());
                timer[0] = ((TextView) field(activity, "timerText")).getText().toString();
                check(frozen[0] >= 20_000 && frozen[0] < 24_000, "manual pause after about20 real active seconds");
            });
            capture("03-paused-before-wait");
            waitReal(10_000, "MANUAL_PAUSE_10_SECONDS");
            onUi(() -> {
                check(session.state() == RestSession.State.PAUSED, "10-second pause remains paused");
                check(session.elapsedActiveMs(SystemClock.elapsedRealtime()) == frozen[0], "10-second pause freezes active clock");
                check(session.expansion(SystemClock.elapsedRealtime()) == shape[0], "10-second pause freezes shape fraction");
                check(((TextView) field(activity, "timerText")).getText().toString().equals(timer[0]), "10-second pause freezes displayed timer");
            });
            capture("04-paused-after-10-seconds");
            clickText(activity, "계속하기");
            onUi(() -> check(session.state() == RestSession.State.ACTIVE, "first actual Continue resumes"));
            waitReal(2_000, "ACTIVE_AFTER_MANUAL_RESUME");
            // The demo sheet's 통화 chip runs exactly this (the sheet is not reachable over the rest screen).
            onUi(() -> { activity.environment.simulate("call"); activity.render(); });
            final long[] interrupted = new long[1];
            onUi(() -> {
                check(session.state() == RestSession.State.PAUSED, "demo call interrupts rest");
                interrupted[0] = session.elapsedActiveMs(SystemClock.elapsedRealtime());
            });
            onUi(() -> {
                Button resume = (Button) field(activity, "resumeButton");
                if (resume.isEnabled()) resume.performClick();
                check(session.state() == RestSession.State.PAUSED, "no Continue press resumes while the call blocks");
            });
            capture("05-call-paused");
            onUi(() -> { activity.environment.simulate("parked"); activity.render(); });
            onUi(() -> check(session.state() == RestSession.State.PAUSED, "parked recovery does not resume"));
            waitReal(5_000, "RECOVERED_WAITING_FOR_MANUAL_RESUME");
            onUi(() -> {
                check(session.state() == RestSession.State.PAUSED, "recovery remains paused after5 real seconds");
                check(session.elapsedActiveMs(SystemClock.elapsedRealtime()) == interrupted[0], "recovery wait consumes no active time");
                check(activity.screen == MainActivity.Screen.HOME, "the blocked Continue opened 시작 (이어서 쉬어갈까요?)");
            });
            capture("06-recovered-still-paused");
            clickText(activity, "계속하기");
            onUi(() -> check(session.state() == RestSession.State.ACTIVE, "second press (시작 › 계속하기) required to resume"));
            waitActive(176_100);
            awaitNaturalLabel();
            onUi(() -> {
                long now = SystemClock.elapsedRealtime();
                long elapsed = session.elapsedActiveMs(now);
                check(elapsed >= 176_000 && elapsed < 180_000, "real active clock inside176-to180 natural rest interval");
                check(session.state() == RestSession.State.ACTIVE, "natural rest remains ACTIVE");
                check(session.segment(now) == RestSession.Segment.NATURAL_REST, "natural-rest segment reached without injection");
                check(session.expansion(now) == (session.reducedMotion() ? 0.5 : 0.0), "natural-rest shape obeys effective motion preference");
            });
            capture("07-natural-rest-176");
            waitReal(4_500, "WAITING_FOR_NATURAL_COMPLETION");
            onUi(() -> {
                // State first: observe the production deadline/tick having completed, before querying elapsed.
                check(session.state() == RestSession.State.COMPLETE, "production deadline naturally reaches COMPLETE");
                check(session.elapsedActiveMs(SystemClock.elapsedRealtime()) == 180_000, "completion has exactly180000 active milliseconds");
                check(((TextView) field(activity, "titleText")).getText().toString().replace('\n', ' ').equals("3분 쉬기를 마쳤어요"), "actual completed UI label");
                check(((TextView) field(activity, "timerText")).getText().toString().equals("0:00"), "actual timer shows0:00");
                check(SystemClock.elapsedRealtime() - started >= 195_000, "wall duration includes real180 active seconds and pause waits");
            });
            capture("08-complete-180");
            progress("PASS " + assertions + " assertions; HOLDING_COMPLETED_SCREEN until files/release-rest-flow or10min; Activity.finish is never called");
            result.putInt("assertions", assertions);
            result.putString("evidencePath", output.getAbsolutePath());
            long holdUntil = SystemClock.elapsedRealtime() + 600_000;
            while (!release.exists() && SystemClock.elapsedRealtime() < holdUntil) {
                periodic("HOLDING_COMPLETED_SCREEN PASS; waiting for host release");
                SystemClock.sleep(500);
            }
            result.putString("stream", evidence.toString());
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace));
            evidence.append("FAIL ").append(trace).append('\n');
            saveReport();
            result.putString("stream", evidence.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void awaitSelection(String expectedName) {
        long until = SystemClock.elapsedRealtime() + 20_000;
        final boolean[] ready = {false};
        while (!ready[0] && SystemClock.elapsedRealtime() < until) {
            onUi(() -> {
                JSONObject state = (JSONObject) field(connected, "state");
                if (state == null || !"quiet_rest".equals(state.optString("selectedId"))) return;
                JSONArray presets = state.optJSONArray("presets");
                if (presets == null) return;
                for (int i = 0; i < presets.length(); i++) {
                    JSONObject chosen = presets.optJSONObject(i);
                    if (chosen == null || !"quiet_rest".equals(chosen.optString("id"))) continue;
                    if (!expectedName.equals(chosen.optString("name"))) return;
                    if (state.optBoolean("autoDefaultOnRest") && !"quiet_rest".equals(state.optString("defaultId"))) return;
                    TextView prepare = findButton(connected.getWindow().getDecorView(), "이 선호로 쉬기 준비");
                    if (prepare == null || !prepare.isEnabled()) return;
                    check("quiet_rest".equals(chosen.optString("comfort")), "shared quiet_rest comfort verified");
                    check(expectedName.equals(chosen.optString("name")), "shared name exactly matches host phone-selection expectation");
                    evidence.append("SHARED_SELECTION id=quiet_rest name=").append(expectedName)
                            .append(" revision=").append(state.optLong("revision")).append('\n');
                    ready[0] = true;
                }
            });
            if (!ready[0]) SystemClock.sleep(200);
        }
        check(ready[0], "fresh connected state selected quiet_rest and ready choice agrees (autoDefault must not override)");
    }

    private void awaitPrepared(String expectedName) {
        long until = SystemClock.elapsedRealtime() + 10_000;
        final boolean[] ready = {false};
        while (!ready[0] && SystemClock.elapsedRealtime() < until) {
            onUi(() -> ready[0] = (Boolean) field(activity, "foreground")
                    && "quiet_rest".equals(field(activity, "selectedPreset"))
                    && ((String) field(activity, "connectedPresetDescription")).startsWith("연결된 선호: " + expectedName + "\n"));
            if (!ready[0]) SystemClock.sleep(100);
        }
        check(ready[0], "real prepare Activity result reached MainActivity with exact shared choice/name");
        onUi(() -> {
            check(connected.isFinishing() || connected.isDestroyed(), "connected screen exited by actual prepare action");
            check(session.state() == RestSession.State.IDLE, "prepare result never automatically starts");
            check(!(Boolean) field(activity, "localConfirmation"), "prepare result carries no on-screen confirmation");
            check(activity.screen == MainActivity.Screen.PREPARATION, "prepare result opens 시작 전에 바꾸기, not the rest");
        });
    }

    private void awaitNaturalLabel() {
        long until = SystemClock.elapsedRealtime() + 2_000;
        final boolean[] matched = {false};
        while (!matched[0] && SystemClock.elapsedRealtime() < until) {
            onUi(() -> matched[0] = ((TextView) field(activity, "titleText")).getText().toString().equals("자연스럽게 쉬세요"));
            if (!matched[0]) SystemClock.sleep(50);
        }
        check(matched[0], "production UI tick shows natural-rest label");
    }

    private void waitActive(long threshold) {
        long timeout = SystemClock.elapsedRealtime() + 200_000;
        final long[] elapsed = {0};
        while (elapsed[0] < threshold) {
            onUi(() -> {
                if (session.state() != RestSession.State.ACTIVE) throw new AssertionError("Unexpected non-ACTIVE state while awaiting" + threshold);
                elapsed[0] = session.elapsedActiveMs(SystemClock.elapsedRealtime());
            });
            periodic("ACTIVE elapsedMs=" + elapsed[0]);
            if (SystemClock.elapsedRealtime() > timeout) throw new AssertionError("Active clock timeout");
            if (elapsed[0] < threshold) SystemClock.sleep(Math.min(200, threshold - elapsed[0]));
        }
    }

    private void waitReal(long duration, String label) {
        long until = SystemClock.elapsedRealtime() + duration;
        while (SystemClock.elapsedRealtime() < until) {
            periodic(label);
            SystemClock.sleep(Math.min(200, Math.max(1, until - SystemClock.elapsedRealtime())));
        }
        waitForIdleSync();
    }

    private void awaitFile(File file, long timeout, String label) {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (!file.exists() && SystemClock.elapsedRealtime() < until) { periodic(label); SystemClock.sleep(200); }
        check(file.isFile(), "host continuation arrived within10 minutes");
    }

    private void clickField(String name) { click((View) field(activity, name)); }
    private void clickText(Activity owner, String text) {
        final TextView[] target = new TextView[1];
        onUi(() -> target[0] = findButton(owner.getWindow().getDecorView(), text));
        if (target[0] == null) throw new AssertionError("No actual button: " + text);
        click(target[0]);
    }
    private void click(View view) {
        // Redesign pages slide and fade in; wait until the control has settled inside the window.
        final boolean[] settled = {false};
        long until = SystemClock.elapsedRealtime() + 3_000;
        while (!settled[0] && SystemClock.elapsedRealtime() < until) {
            onUi(() -> { Rect r = new Rect(); settled[0] = view.isShown() && view.getGlobalVisibleRect(r)
                    && r.width() == view.getWidth() && view.getAlpha() == 1f && view.getTranslationX() == 0f; });
            if (!settled[0]) SystemClock.sleep(100);
        }
        onUi(() -> {
            check(view.isShown() && view.isEnabled(), "actual UI control shown/enabled: " + controlName(view));
            view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true);
        });
        onUi(() -> {
            Rect bounds = new Rect();
            check(view.getGlobalVisibleRect(bounds), "actual UI control within viewport: " + controlName(view));
            if (view instanceof CheckBox) {
                boolean before = ((CheckBox) view).isChecked();
                view.performClick();
                check(((CheckBox) view).isChecked() != before, "actual checkbox click toggles checked state: " + controlName(view));
            } else {
                check(view.performClick(), "actual UI performClick: " + controlName(view));
            }
        });
    }
    private static String controlName(View view) { return view instanceof TextView ? ((TextView) view).getText().toString() : view.getClass().getSimpleName(); }
    /** A shown, clickable control with this text (redesign buttons are TextViews; hidden legacy ones are skipped). */
    private static TextView findButton(View view, String text) {
        if (view instanceof TextView && view.isClickable() && view.isShown() && ((TextView) view).getText().toString().equals(text)) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = findButton(((ViewGroup) view).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private void capture(String name) throws Exception {
        waitForIdleSync();
        SystemClock.sleep(500); // Allow the compositor to present the state already asserted on the UI thread.
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("Screenshot unavailable");
        try (FileOutputStream stream = new FileOutputStream(new File(output, name + ".png"))) {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream), "PNG encoded " + name);
        } finally { bitmap.recycle(); }
        progress("SCREENSHOT " + name + ".png");
    }
    private void periodic(String message) {
        if (SystemClock.elapsedRealtime() >= nextProgress) progress(message);
    }
    private void progress(String message) {
        nextProgress = SystemClock.elapsedRealtime() + 30_000;
        evidence.append("wallUtcMs=").append(System.currentTimeMillis()).append(" monotonicMs=")
                .append(SystemClock.elapsedRealtime()).append(' ').append(message).append('\n');
        saveReport();
        Bundle update = new Bundle(); update.putString("stream", message + "\n"); sendStatus(1, update);
    }
    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++; evidence.append("PASS ").append(SystemClock.elapsedRealtime()).append(' ').append(message).append('\n');
    }
    private void saveReport() {
        if (output == null) return;
        try (FileOutputStream stream = new FileOutputStream(new File(output, "native-report.txt"))) {
            stream.write(evidence.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) { throw new AssertionError("Cannot save acceptance evidence", failure); }
    }
    private void onUi(Runnable action) {
        final Throwable[] error = new Throwable[1];
        runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error[0] = failure; } });
        if (error[0] != null) throw new AssertionError("UI scenario failed", error[0]);
        waitForIdleSync();
    }
    private static Object field(Object object, String name) {
        try { Field value = object.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(object); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
}
