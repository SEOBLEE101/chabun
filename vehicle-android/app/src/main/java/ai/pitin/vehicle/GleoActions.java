package ai.pitin.vehicle;

import android.content.Intent;
import android.os.Bundle;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements the public Gleo Intent/Callback interface. It never starts a rest. SET_CABIN comes only from a Gleo
 * package and not during a rest; each value still passes the parked gate when sent, and Gleo gets one answer within
 * {@link #ANSWER_WITHIN_MS} (Gleo cancels an action after 7 s).
 */
final class GleoActions {
    static final String RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE";
    static final long ANSWER_WITHIN_MS = 6_000;

    private GleoActions() { }

    static void handle(MainActivity activity, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!GleoActionPolicy.recognizes(action)) return;
        CabinActuator cabinClock = activity.environment.cabin();
        long deadline = cabinClock == null ? 0 : cabinClock.now() + ANSWER_WITHIN_MS;
        GleoActionPolicy.Result result;
        String section = null;
        List<CabinControl.Step> cabinSteps = null;
        try {
            Bundle bundle = intent.getExtras();
            Map<String, Object> extras = new HashMap<>();
            if (bundle != null) for (String key : bundle.keySet()) extras.put(key, bundle.get(key));
            // Read before setIntent below: the package Android reports as this intent's sender.
            android.net.Uri referrer = activity.getReferrer();
            String caller = referrer == null ? null : referrer.getHost();
            result = GleoActionPolicy.evaluate(action, extras, activity.session.state(), caller, BuildConfig.DEBUG);
            if (result == GleoActionPolicy.Result.OPEN_CABIN) section = GleoActionPolicy.cabinSection(extras);
            if (result == GleoActionPolicy.Result.SET_CABIN) cabinSteps = GleoActionPolicy.cabinRequest(extras).steps;
            if (result == GleoActionPolicy.Result.APPLY_CABIN) cabinSteps = activity.restPlan();
            if (result == GleoActionPolicy.Result.UNTRUSTED)
                android.util.Log.w(CabinActuator.TAG, "SET_CABIN from an untrusted sender was refused: " + caller);
        } catch (RuntimeException invalid) {
            result = GleoActionPolicy.Result.REJECT;
        }
        // A recreated Activity must not replay an old voice request or repeat its callback.
        activity.setIntent(new Intent(activity, MainActivity.class).setAction(Intent.ACTION_MAIN));
        boolean resting = activity.session.state() == RestSession.State.ACTIVE
                || activity.session.state() == RestSession.State.PAUSED;
        if (result == GleoActionPolicy.Result.SET_CABIN || result == GleoActionPolicy.Result.APPLY_CABIN) {
            changeCabin(activity, action, cabinSteps, deadline, resting ? "silent" : "voice");
            return;
        }
        String status;
        String message;
        switch (result) {
            case PREPARE:
                // Same path as choosing on screen: a phone-shared rest's values are dropped with it.
                activity.commitRestChoice("quiet_rest", activity.naturalRestMode);
                activity.clearSharedRest();
                activity.localConfirmation = false;
                activity.consentBox.setChecked(false);
                // The preparation screen of the redesign is 시작 with the voice pill; starting stays a press on screen.
                activity.showScreen(MainActivity.Screen.HOME);
                if (activity.shell != null) activity.shell.showPill("조용히 쉬기를 골라 뒀어요 · 시작은 화면에서", MainActivity.Screen.HOME);
                status = "asking";
                message = "Quiet rest is selected. Check the parked vehicle conditions and confirm on the vehicle screen before starting.";
                break;
            case OPEN:
                if (activity.shell != null && activity.screen != MainActivity.Screen.SESSION)
                    activity.shell.showPill("Gleo로 열었어요", activity.screen);
                status = "success";
                message = "Chabun is open.";
                break;
            case OPEN_CABIN:
                activity.openCabin(section);
                if (activity.shell != null) activity.shell.showPill("Gleo로 열었어요", MainActivity.Screen.CABIN);
                status = "success";
                message = "Cabin settings are open on the " + section + " tab. No vehicle setting was changed.";
                break;
            case SESSION_BUSY:
                status = "fail";
                message = GleoActionPolicy.SET.equals(action)
                        ? "A rest session is in progress and owns the cabin settings until it ends. Nothing was changed."
                        : "A rest session is in progress, so the rest screen stays as it is. Nothing was changed.";
                break;
            case UNTRUSTED:
                status = "fail";
                message = "Cabin settings can only be changed through Gleo. Nothing was changed.";
                break;
            case KEEP_SESSION:
                status = "asking";
                message = "The existing rest session is unchanged. Use the vehicle screen to continue or finish it.";
                break;
            default:
                status = "fail";
                message = GleoActionPolicy.CABIN.equals(action)
                        ? "This action needs section seat, climate or light. Nothing was changed."
                        : GleoActionPolicy.SET.equals(action)
                        ? "Give mode rest, or temperature 18-28, fan, ac, seat heat/vent with level 1-3, or light, color and brightness. Nothing was changed."
                        : "This action does not accept parameters. Nothing was changed.";
        }
        sendResponse(activity, action, status, message, resting ? "silent" : "voice");
    }

    /**
     * Runs a voice cabin request while parked and answers once: when the car has reported back or at the deadline,
     * whichever is first. The answer names what the car now has (fitted to its range), not what was asked.
     */
    private static void changeCabin(MainActivity activity, String action, List<CabinControl.Step> steps, long deadline, String mode) {
        CabinActuator cabin = activity.environment.cabin();
        MainActivity.Screen at = activity.screen;
        if (cabin == null) {
            sendResponse(activity, action, "fail", "This Chabun build does not change the cabin. Nothing was changed.", mode);
            return;
        }
        if (steps == null || steps.isEmpty()) {
            if (activity.shell != null) activity.shell.showPill("Gleo · 바꿀 값이 없어요 · 이 쉼은 차 안을 바꾸지 않아요", at);
            sendResponse(activity, action, "fail", "The selected rest and the saved cabin settings change nothing. Nothing was changed.", mode);
            return;
        }
        String blocked = activity.cabinBlocked();
        String words = CabinControl.describe(steps);
        android.util.Log.i(CabinActuator.TAG, action + " " + steps + (blocked == null ? "" : " blocked: " + blocked));
        if (blocked != null) {
            if (activity.shell != null) activity.shell.showPill("Gleo · " + blocked, at);
            sendResponse(activity, action, "fail", "The car is not parked, stopped and unrestricted, so nothing was changed.", mode);
            return;
        }
        if (activity.shell != null) activity.shell.showPill("Gleo · " + words + " · 맞추는 중", at);
        cabin.apply(steps, null, deadline, result -> {
            List<CabinControl.Step> applied = result.applied();
            if (activity.shell != null) {
                activity.shell.showPill("Gleo · " + (applied.isEmpty() ? words : CabinControl.describe(applied)) + " · " + result.brief(), activity.screen);
                activity.shell.readCabinNow();
            }
            activity.render();
            int done = applied.size(), total = result.items.size();
            String status = done == total ? "success" : done > 0 ? "success" : "fail";
            String message = done == total ? "Done. The car confirmed: " + english(applied) + "."
                    : done > 0 ? done + " of " + total + " changes were confirmed by the car: " + english(applied) + "."
                    + (result.count(CabinControl.Status.UNKNOWN) > 0 ? " Some changes could not be confirmed yet." : "")
                    : result.count(CabinControl.Status.UNKNOWN) > 0 ? "The change was sent but the car has not confirmed it yet."
                    : "Nothing was changed.";
            sendResponse(activity, action, status, message, mode);
        });
    }

    /** The callback stays in fixed English words (no health or pairing data), like the other answers. */
    private static String english(List<CabinControl.Step> steps) {
        List<String> parts = new java.util.ArrayList<>();
        for (CabinControl.Step s : steps) {
            switch (s.item) {
                case TEMPERATURE: parts.add(String.format(java.util.Locale.ROOT, "temperature %d.%d C", s.value / 10, s.value % 10)); break;
                case FAN: parts.add("fan " + (s.value == CabinControl.FAN_LOW ? "low" : s.value == CabinControl.FAN_MEDIUM ? "medium" : "high")); break;
                case CLIMATE_AUTO: if (s.value == 1) parts.add("fan auto"); break;
                case AC: parts.add("A/C " + (s.value == 1 ? "on" : "off")); break;
                case DIRECTION: parts.add("air direction"); break;
                case SEAT_HEAT: if (s.value > 0) parts.add((s.driver ? "driver" : "passenger") + " seat heat " + s.value); break;
                case SEAT_VENT: if (s.value > 0) parts.add((s.driver ? "driver" : "passenger") + " seat ventilation " + s.value); break;
                case LIGHT_SWITCH: parts.add("ambient light " + (s.value == 1 ? "on" : "off")); break;
                case LIGHT_BRIGHTNESS: parts.add("brightness " + s.value + "%"); break;
                case LIGHT_COLOR: parts.add("light color"); break;
                default: break;
            }
        }
        return parts.isEmpty() ? "seat heating and ventilation off" : String.join(", ", parts);
    }

    private static void sendResponse(MainActivity activity, String action, String status, String message, String mode) {
        try {
            JSONObject response = new JSONObject().put("functionName", action).put("status", status)
                    .put("message", message).put("mode", mode);
            // Public callback is intentionally limited to fixed UI outcomes: no health or pairing data.
            activity.sendBroadcast(new Intent(RESPONSE_ACTION).putExtra("packageName", activity.getPackageName())
                    .putExtra("response", response.toString()));
        } catch (JSONException | SecurityException unavailable) {
            // Callback delivery does not start a session or change the user's existing choice.
        }
    }
}
