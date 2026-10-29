package ai.pitin.vehicle;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** App-side integration checks with synthetic vehicle fixtures, not a Gleo voice test. */
public final class GleoInstrumentation extends Instrumentation {
    private MainActivity activity;
    private int assertions;
    private String launchExtraTypes = "";
    private final LinkedBlockingQueue<String> callbacks = new LinkedBlockingQueue<>();
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (getTargetContext().getPackageName().equals(intent.getStringExtra("packageName"))) {
                String response = intent.getStringExtra("response");
                if (response != null) callbacks.add(response);
            }
        }
    };

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void callActivityOnCreate(Activity target, Bundle savedInstanceState) {
        if (target instanceof MainActivity && target.getIntent().getExtras() != null) {
            Bundle extras = target.getIntent().getExtras();
            StringBuilder types = new StringBuilder();
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                types.append(key).append(':').append(value == null ? "null" : value.getClass().getSimpleName()).append(';');
            }
            launchExtraTypes = types.toString();
        }
        super.callActivityOnCreate(target, savedInstanceState);
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        boolean registered = false;
        try {
            check(BuildConfig.SIMULATED_VEHICLE, "synthetic demo flavor only");
            Context context = getTargetContext();
            JSONObject schema;
            try (var input = context.getAssets().open("schema.json")) {
                schema = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            check(context.getPackageName().equals(schema.getString("packageName")), "schema package matches installed flavor");
            check(BuildConfig.VERSION_NAME.equals(schema.getString("version")), "schema version matches APK");
            check(schema.getJSONArray("actionCalls").length() == 3, "Gleo allows three actions per app");
            java.util.Set<String> names = new java.util.HashSet<>();
            for (int i = 0; i < 3; i++) names.add(schema.getJSONArray("actionCalls").getJSONObject(i).getJSONObject("function").getString("name"));
            check(names.equals(java.util.Set.of(GleoActionPolicy.PREPARE, GleoActionPolicy.CABIN, GleoActionPolicy.SET)), "the three actions are prepare, open cabin settings and set cabin");
            context.registerReceiver(receiver, new IntentFilter(GleoActions.RESPONSE_ACTION), Context.RECEIVER_EXPORTED);
            registered = true;
            activity = (MainActivity) startActivitySync(new Intent(context, MainActivity.class)
                    .setAction(GleoActionPolicy.PREPARE).putExtra("ACTION_PARAM", "{}")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            callback(GleoActionPolicy.PREPARE, "asking", "voice");
            onUi(() -> {
                check(activity.session.state() == RestSession.State.IDLE, "cold preparation never starts");
                check(activity.selectedPreset.equals("quiet_rest"), "quiet rest selected");
                // Redesign: the preparation screen is 시작 with the voice pill; starting stays a press there.
                check(activity.screen == MainActivity.Screen.HOME, "시작 opened for the press");
                check(!activity.localConfirmation, "preparation cannot grant the on-screen confirmation");
                check(Intent.ACTION_MAIN.equals(activity.getIntent().getAction()), "consumed request cannot replay on recreation");
            });
            send(GleoActionPolicy.PREPARE, "{\"start\":true}");
            callback(GleoActionPolicy.PREPARE, "fail", "voice");
            onUi(() -> check(activity.session.state() == RestSession.State.IDLE, "invalid extras do not start"));
            // SET_CABIN from anything but Gleo (here: the test process) is refused and changes nothing.
            send(GleoActionPolicy.SET, "{\"mode\":\"rest\"}");
            callback(GleoActionPolicy.SET, "fail", "voice");
            onUi(() -> check(activity.session.state() == RestSession.State.IDLE, "an untrusted cabin request starts nothing"));
            onUi(() -> {
                activity.environment.simulate("parked");
                activity.render();
                activity.startRest();
                check(activity.session.state() == RestSession.State.ACTIVE, "test starts via the on-screen 3분 쉬기 press");
            });
            send(GleoActionPolicy.PREPARE, "{}");
            callback(GleoActionPolicy.PREPARE, "asking", "silent");
            onUi(() -> {
                check(activity.session.state() == RestSession.State.ACTIVE, "voice preparation preserves active session");
                check(activity.screen == MainActivity.Screen.SESSION, "voice preparation cannot obscure active rest");
                check(activity.localConfirmation, "voice preparation leaves active confirmation unchanged");
            });
            send(GleoActionPolicy.CABIN, "{\"section\":\"climate\"}");
            callback(GleoActionPolicy.CABIN, "fail", "silent");
            onUi(() -> check(activity.screen == MainActivity.Screen.SESSION, "opening cabin settings during a rest keeps the rest screen"));
            onUi(() -> activity.pauseButton.performClick());
            final long[] frozen = new long[1];
            onUi(() -> frozen[0] = activity.session.elapsedActiveMs(SystemClock.elapsedRealtime()));
            send(GleoActionPolicy.PREPARE, "{}");
            callback(GleoActionPolicy.PREPARE, "asking", "silent");
            send(GleoActionPolicy.OPEN, null);
            callback(GleoActionPolicy.OPEN, "success", "silent");
            onUi(() -> {
                check(activity.session.state() == RestSession.State.PAUSED, "open and prepare cannot resume");
                check(activity.session.elapsedActiveMs(SystemClock.elapsedRealtime()) == frozen[0], "paused clock unchanged");
                check(!SafetyGate.permitsVehicleWrite(null), "SafetyGate's own write switch stays off");
            });
            result.putString("stream", "PASS " + assertions + " app-side Gleo integration assertions; no voice recognition tested\n");
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError failure) {
            result.putString("stream", "FAIL " + failure.getClass().getSimpleName() + ": " + failure.getMessage()
                    + "; launch extra types=" + launchExtraTypes + "\n");
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (registered) getTargetContext().unregisterReceiver(receiver);
        }
    }

    private void send(String action, String parameters) {
        onUi(() -> {
            Intent intent = new Intent(getTargetContext(), MainActivity.class).setAction(action);
            if (parameters != null) intent.putExtra("ACTION_PARAM", parameters);
            activity.onNewIntent(intent);
        });
    }

    private void callback(String action, String status, String mode) throws Exception {
        String raw = callbacks.poll(5, TimeUnit.SECONDS);
        check(raw != null, "callback received");
        JSONObject response = new JSONObject(raw);
        check(action.equals(response.getString("functionName")), "callback identifies requested function");
        check(status.equals(response.getString("status")), "callback expected " + status + ", received " + raw);
        check(mode.equals(response.getString("mode")), "active rest callback stays silent");
        check(response.length() == 4 && !response.has("data"), "callback contains no workout or connection data");
    }

    private void onUi(Runnable work) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { work.run(); } catch (Throwable problem) { failure.set(problem); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError("assertion " + assertions + ": " + description);
    }
}
