package ai.pitin.vehicle;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Process-scoped preset-sync link: origin, bearer credential, approval, observed device id/epoch and
 * the appearance report bookkeeping. It lives in process memory only (never on disk) so closing the
 * 함께 쓰는 쉼 목록 screen no longer forgets an approved link, and the car keeps reporting its CURRENT
 * rendered theme while any 차분 screen is in the foreground. Explicit disconnect, revocation (401/403),
 * credential expiry and process death clear it. Appearance is only the app's rendered surface: this
 * class never sends a cabin or vehicle command and never touches health-summary credentials.
 * Main thread only, except the captured values handed to the network executor.
 */
final class PresetLink {
    /** Screens that show the link (the shared-presets screen) listen for background changes. */
    interface Listener {
        void onLinkUpdated();
        /** The link was cleared from the background (401/403 or credential expiry). */
        void onLinkEnded();
        /** The shared state's current vehicle proposal (validated), read every few seconds; listeners skip repeats. */
        default void onRestProposed(JSONObject proposal) { }
    }

    private static PresetLink instance;

    /** Main thread. The first call reads the car's stored, rendered mood so the report is never guessed. */
    static PresetLink get(Context context) {
        if (instance == null) {
            instance = new PresetLink();
            String mood = context.getApplicationContext().getSharedPreferences(CarShell.PREFS, Context.MODE_PRIVATE)
                    .getString("car_mood", CarTheme.GRAPHITE_ID);
            instance.themeChanged(CarTheme.of(mood).id);
        }
        return instance;
    }

    final PresetSyncClient client = new PresetSyncClient();
    final AppearancePublisher appearance = new AppearancePublisher();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ArrayList<Listener> listeners = new ArrayList<>();

    // Link state, read and written by ConnectedPresetsActivity on the main thread. Memory only.
    String token, comparison = "", observedDeviceId;
    boolean approved;
    long credentialExpiresAtMs;

    private int startedScreens;
    private String sharedThemeId;
    private boolean sharedFresh, reportProblem, stateReading;
    private long stateReadAt;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (startedScreens == 0) return;
            expireCredential();
            maybePublish();
            maybeReadProposal();
            main.postDelayed(this, 1_000);
        }
    };

    /**
     * While a 차분 screen is in front, reads the shared state every 4 s so a rest sent from the phone (with its
     * 차 안 설정) reaches the car without opening the shared-presets screen. Read only: nothing is confirmed here.
     */
    private void maybeReadProposal() {
        if (!approved || token == null || stateReading) return;
        long now = SystemClock.elapsedRealtime();
        if (now - stateReadAt < 4_000) return;
        stateReadAt = now;
        stateReading = true;
        final String bearer = token;
        network.submit(() -> {
            int status = 0;
            JSONObject proposal = null;
            try {
                PresetSyncClient.Reply reply = client.request("GET", "/state", bearer, null); // validated by the client
                status = reply.status;
                if (status == 200) proposal = reply.body.getJSONObject("state").getJSONObject("devices")
                        .getJSONObject("vehicle").optJSONObject("proposal");
            } catch (Exception unavailable) { status = -1; }
            final int code = status;
            final JSONObject found = proposal;
            main.post(() -> {
                stateReading = false;
                if (!bearer.equals(token)) return;
                if (code == 401 || code == 403) { end(); updated(); return; }
                if (found != null) for (Listener l : new ArrayList<>(listeners)) l.onRestProposed(found);
            });
        });
    }

    private PresetLink() { }

    private static JSONObject object(Object... pairs) {
        JSONObject value = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]); }
        catch (Exception e) { throw new IllegalArgumentException(e); }
        return value;
    }

    void addListener(Listener listener) { if (!listeners.contains(listener)) listeners.add(listener); }
    void removeListener(Listener listener) { listeners.remove(listener); }
    private void updated() { for (Listener l : new ArrayList<>(listeners)) l.onLinkUpdated(); }

    /** Origin follows the relay only while no credential is held: a token belongs to its own server. */
    void useRelayOrigin(String relayOrigin) {
        if (token == null && !approved) client.setOriginFromRelay(relayOrigin);
    }

    /** A 차분 screen (main app or shared presets) started. Reporting runs while at least one is started. */
    void enterForeground() {
        if (startedScreens++ == 0) { appearance.start(); main.removeCallbacks(tick); main.post(tick); }
    }
    void exitForeground() {
        if (startedScreens == 0) return;
        if (--startedScreens == 0) { appearance.stop(); main.removeCallbacks(tick); }
    }

    /** The car's rendered mood changed ({@link CarTheme#id}); report it promptly. */
    void themeChanged(String carThemeId) {
        if (!appearance.setTheme(AppearancePublisher.wireTheme(carThemeId))) return;
        maybePublish();
        updated();
    }
    String themeLabel() { return AppearancePublisher.label(appearance.theme()); }

    void claimed(String newToken, String comparisonCode, long expiresAtMs) {
        token = newToken; comparison = comparisonCode; approved = false; observedDeviceId = null;
        credentialExpiresAtMs = expiresAtMs; resetReportState();
    }

    /** A validated vehicle envelope from this credential: identity and server epoch for reporting. */
    void observed(String epoch, String deviceId) {
        if (token == null || !approved || deviceId == null || deviceId.isEmpty()) return;
        observedDeviceId = deviceId;
        appearance.observeEpoch(epoch);
        maybePublish();
    }

    /** Clears credential, approval, identity and report bookkeeping (explicit disconnect, 401/403). */
    void clear() {
        token = null; approved = false; comparison = ""; observedDeviceId = null; credentialExpiresAtMs = 0;
        resetReportState();
    }
    private void resetReportState() {
        appearance.reset(); sharedThemeId = null; sharedFresh = false; reportProblem = false;
    }
    private void end() {
        clear();
        for (Listener l : new ArrayList<>(listeners)) l.onLinkEnded();
    }
    private void expireCredential() {
        if (token != null && credentialExpiresAtMs > 0 && System.currentTimeMillis() >= credentialExpiresAtMs) end();
    }

    /** The shared-presets status line, naming the car's real current theme. */
    String appearanceStatus() {
        String current = appearance.theme();
        String text = "화면 스타일 · " + AppearancePublisher.label(current);
        if (reportProblem) return text + " · 폰과의 화면 연결 확인 중";
        if (current.equals(sharedThemeId)) return text + (sharedFresh ? " · 폰 연결용 정보 공유됨" : " · 새로 공유할 예정");
        if (sharedThemeId != null) return text + " · 바뀐 분위기를 공유하는 중";
        return text;
    }

    private void maybePublish() {
        if (startedScreens == 0 || !approved || token == null || observedDeviceId == null) return;
        expireCredential();
        if (token == null) return;
        AppearancePublisher.Attempt attempt = appearance.begin(SystemClock.elapsedRealtime());
        if (attempt == null) return;
        final String bearer = token, deviceId = observedDeviceId;
        AppearancePublisher.Report pending = appearance.pending(attempt);
        if (pending != null) { post(attempt, bearer, deviceId, pending); return; }
        exchange(attempt, bearer, deviceId, "GET", null, reply -> {
            if (reply.status != 200) { failed(attempt, false); return; }
            JSONObject sync = reply.body.getJSONObject("sync");
            if (!deviceId.equals(sync.getString("deviceId")) || !"vehicle".equals(sync.getString("role")))
                throw new IllegalArgumentException("Appearance session changed");
            String epoch = sync.getString("epoch");
            if (!attempt.epoch.equals(epoch)) {
                // Server epoch changed: drop the old report bookkeeping, as the screen used to on its read.
                appearance.observeEpoch(epoch); sharedThemeId = null; sharedFresh = false; return;
            }
            JSONObject own = ownReport(reply.body, deviceId);
            AppearancePublisher.Report report = appearance.prepare(attempt, epoch,
                    own == null ? 0 : own.getLong("revision"), UUID.randomUUID().toString());
            post(attempt, bearer, deviceId, report);
        });
    }

    private void post(AppearancePublisher.Attempt attempt, String bearer, String deviceId,
                      AppearancePublisher.Report report) {
        // Identical payload for every retry of the same request id, including its theme.
        JSONObject request = object("schemaVersion", AppearancePublisher.SCHEMA_VERSION,
                "themeId", report.themeId, "paletteVersion", AppearancePublisher.PALETTE_VERSION,
                "expectedEpoch", report.epoch, "expectedRevision", report.expectedRevision, "requestId", report.requestId);
        exchange(attempt, bearer, deviceId, "POST", request, reply -> {
            if (reply.status != 200) { failed(attempt, reply.status >= 400 && reply.status < 500); return; }
            JSONObject sync = reply.body.getJSONObject("sync");
            if (!attempt.epoch.equals(sync.getString("epoch")) || !deviceId.equals(sync.getString("deviceId"))
                    || !"vehicle".equals(sync.getString("role"))) throw new IllegalArgumentException("Appearance session changed");
            JSONObject own = ownReport(reply.body, deviceId);
            if (own == null || !AppearancePublisher.confirms(report, own.getString("themeId"), own.getLong("revision"),
                    reply.body.optString("acknowledgedRequestId", null)))
                throw new IllegalArgumentException("Unconfirmed appearance report");
            boolean fresh = Instant.parse(own.getString("expiresAt")).isAfter(Instant.parse(
                    reply.body.getJSONObject("appearance").getString("serverTime")));
            if (appearance.acknowledge(attempt, report.requestId)) {
                sharedThemeId = report.themeId; sharedFresh = fresh; reportProblem = false;
                maybePublish(); // a theme change during this report is sent right away
            }
        });
    }

    private void failed(AppearancePublisher.Attempt attempt, boolean definiteRejection) {
        appearance.failed(attempt, definiteRejection);
        reportProblem = true;
    }

    private static JSONObject ownReport(JSONObject wrapper, String deviceId) throws Exception {
        JSONArray vehicles = wrapper.getJSONObject("appearance").getJSONArray("vehicles");
        for (int i = 0; i < vehicles.length(); i++) {
            JSONObject report = vehicles.getJSONObject(i);
            if (deviceId.equals(report.getString("deviceId"))) return report;
        }
        return null;
    }

    private boolean current(AppearancePublisher.Attempt attempt, String bearer, String deviceId) {
        return appearance.current(attempt) && startedScreens > 0 && approved && bearer.equals(token)
                && deviceId.equals(observedDeviceId);
    }

    private interface Receiver { void accept(PresetSyncClient.Reply reply) throws Exception; }

    private void exchange(AppearancePublisher.Attempt attempt, String bearer, String deviceId,
                          String method, JSONObject body, Receiver receiver) {
        // Own queue: never clears preset freshness or uncertain commands of the shared-presets screen.
        network.submit(() -> {
            if (!appearance.current(attempt)) return;
            try {
                PresetSyncClient.Reply reply = client.request(method, "/appearance", bearer, body);
                main.post(() -> {
                    if (!current(attempt, bearer, deviceId)) { appearance.failed(attempt, false); return; }
                    if (reply.status == 401 || reply.status == 403) { end(); updated(); return; }
                    try { receiver.accept(reply); }
                    catch (Exception invalid) { failed(attempt, false); }
                    updated();
                });
            } catch (Exception unavailable) {
                main.post(() -> {
                    if (!current(attempt, bearer, deviceId)) { appearance.failed(attempt, false); return; }
                    failed(attempt, false); updated();
                });
            }
        });
    }
}
