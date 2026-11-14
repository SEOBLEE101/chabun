package ai.pitin.vehicle;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Native parked prototype. The demo and read-only builds never write to the car. The pleos build changes climate,
 * seat heating/ventilation and ambient light while parked (차 안 맞추기, {@link CabinControl}); never seat motion,
 * audio, navigation or other car properties.
 */
public final class MainActivity extends Activity {
    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService network = Executors.newSingleThreadExecutor();
    final GraphiteViews views = new GraphiteViews(this);
    final RestSession session = new RestSession();
    VehicleEnvironment environment;
    RelayClient relay;
    final PendingRevocation revocation = new PendingRevocation();
    RelayClient revocationRelay;
    String revocationPairingId;
    long pairingExpiresAtMs;
    String pairingId, vehicleToken, confirmation, pairingStatus = "waiting", selectedPreset = "", connectedPresetDescription = "", restFeedback = "";
    String connectionMessage = "차량 연결 번호를 만들거나 운동 요약 없이 바로 쉼을 준비할 수 있어요.";
    RecoveryProposal proposal;
    /** PREPARATION = 시작 전에 바꾸기, CONNECTIONS = 폰 연결; SESSION is the protected rest screen. */
    enum Screen { HOME, PREPARATION, CONNECTIONS, SESSION, BLOCKED, FEATURES, CABIN }
    Screen screen = Screen.HOME;
    boolean foreground, busy, localConfirmation, manualMode, appReducedMotion, naturalRestMode, privacyMasked = true;
    int epoch, deadlineEpoch;
    long lastPollMs;
    boolean connectionVerified;
    long connectionVerifiedAtMs;
    TextView modeText, pairingText, summaryText, presetText, vehicleText, gateText, timerText, guideLabel, titleText, supportText;
    TextView homePreference, homeConnection, homeReadiness;
    EditText originInput;
    CheckBox consentBox;
    Button pairButton, confirmPairButton, startButton, pauseButton, resumeButton, endButton, settingsButton;
    Button homeTab, preparationTab, connectionTab, privacyButton, disconnectButton, retryDeleteButton;
    RestOrbView orb;
    ScrollView setupPanel, homePanel;
    LinearLayout restPanel, feedbackPanel, preparationPanel, connectionPanel, navigation;
    private Runnable deadline;
    // Redesign shell (car screens other than the protected rest screen) and the finish text layer.
    CarShell shell;
    String pairingCode;
    long proposalReceivedAtMs;
    LinearLayout finishPanel, finishStatus;
    TextView timerCaption, footerThemeText;
    android.view.View timerDash;
    float settingsTextSizePx;
    int settingsMinHeight;
    /** The Pleos intro plays once per process (cold start only). */
    static boolean introPlayedThisProcess;
    /** 차 안 맞추기 for the current rest: progress/result line, and what the finish screen says about the cabin. */
    String cabinRestNote = "", cabinFinishNote;
    /** The running rest's cabin ledger (null when the rest changed nothing in the car). */
    CabinActuator.Session restSession;
    private long lastRestoreTryMs;
    private int restoreTries;
    /** Submission builds carry no relay address (no server, no local CA): 폰 연결 says so instead of failing. */
    static final String RELEASE_NO_SERVER = "이 빌드는 연결 서버 없이 동작해서 폰 연결을 쓸 수 없어요.";
    /** When the running rest was first seen paused (elapsedRealtime), 0 when it is not paused. */
    private long pausedAtMs;
    /** Why the rest ended by itself (the car moved, a long pause, leaving the screen), shown with the restore result. */
    private String restEndReason;
    /** The reason of the last rest that ended by itself, kept for a restore finished later. */
    private String finishReason;
    /** 차 안 values of the rest chosen on the phone (함께 쓰는 쉼), or null. */
    CabinSettings sharedCabin;
    /** Name of the rest the phone sent (‘폰에서 고른 쉼’), or null. */
    String sharedRestName;
    /** Id of the last phone proposal taken from the preset link, so the same one is not taken twice. */
    String takenProposalId;
    /** Car values of the preset chosen from the phone (함께 쓰는 쉼), or null when the rest's own climate applies. */
    CabinControl.Shared sharedClimate;
    private RestSession.State lastRestState = RestSession.State.IDLE;

    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            expireRevocation();
            environment.refresh();
            endInterruptedRest();
            if (proposal != null && !proposal.isCurrent(System.currentTimeMillis())) {
                proposal = null; connectionMessage = "수신한 운동 요약의 보관 시간이 만료되어 화면에서 비웠어요.";
            }
            retryOwedRestores();
            render();
            long now = SystemClock.elapsedRealtime();
            if (!busy && relay != null && pairingId != null && now - lastPollMs >= 3_000) { lastPollMs = now; pollPairing(); }
            main.postDelayed(this, 1_000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        environment = VehicleEnvironmentFactory.create(this);
        // Redesign review build only: start from the synthetic parked signal so the intro can play. Still synthetic.
        if (BuildConfig.SIMULATED_VEHICLE && BuildConfig.DEMO_START_PARKED) environment.simulate("parked");
        // Every cabin write (and restore write) asks this gate right before it is sent.
        if (environment.cabin() != null) environment.cabin().setGate(this::cabinBlocked);
        // A build that rests on real values needs the speed reading (a runtime permission) before it can verify parking.
        if (BuildConfig.LIVE_REST && savedInstanceState == null) {
            java.util.List<String> missing = new java.util.ArrayList<>();
            for (String p : environment.requiredPermissions())
                if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) missing.add(p);
            if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 41);
        }
        android.content.SharedPreferences prefs = getSharedPreferences(CarShell.PREFS, MODE_PRIVATE);
        selectedPreset = prefs.getString("last_preset", "quiet_rest");
        naturalRestMode = prefs.getBoolean("natural_mode", false);
        appReducedMotion = prefs.getBoolean("reduced_motion", false);
        // The server keeps the last phone rest; remember which one was taken so reopening does not take it again.
        takenProposalId = prefs.getString("taken_proposal", null);
        restFeedback = "";
        MainScreen.build(this);
        shell = new CarShell(this);
        shell.install();
        render();
        Intent start = getIntent();
        // Gleo may send "<package>.<function>"; a cold start by voice must skip the intro and the 시작 보류 check too.
        boolean voice = start != null && GleoActionPolicy.recognizes(GleoActionPolicy.functionName(start.getAction(), getPackageName()));
        shell.handleDebugExtras(start); // demo debug builds only; no-op otherwise
        if (savedInstanceState == null) GleoActions.handle(this, start);
        boolean skipIntro = start != null && start.getBooleanExtra("reviewSkipIntro", false) && BuildConfig.DEBUG;
        // Cold start = a new process (AAOS may restore the task with saved state; the intro still plays once).
        if (!introPlayedThisProcess && !voice && screen == Screen.HOME) {
            introPlayedThisProcess = true;
            if (!parkingVerified()) showScreen(Screen.BLOCKED); // no intro when the stop is not verified: 시작 보류
            else if (!skipIntro) shell.playIntro(BuildConfig.DEBUG && start != null ? start.getLongExtra("reviewIntroFreezeMs", -1) : -1);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        shell.handleDebugExtras(intent);
        GleoActions.handle(this, intent);
    }
    @Override public void onStart() {
        main.removeCallbacks(endRestAfterLeaving); // back within the moment: the rest stays paused, ‘계속하기’ resumes it
        main.removeCallbacks(keepReadingWhileRestoring);
        super.onStart(); foreground = true; expireRevocation(); main.post(tick);
        PresetLink.get(this).addListener(restIntake);
        PresetLink.get(this).enterForeground(); // theme reports run while a 차분 screen is in front
    }

    @Override public void onStop() {
        // Leaving the screen (not a rotation) ends a running or paused rest, so its cabin values do not stay behind.
        // The car is read again first and the rest ends a moment later, so a just-changed gear is seen before
        // anything is put back (put back while parked, or kept owed until parked with this screen open again).
        RestSession.State leaving = session.state();
        if (!isChangingConfigurations() && (leaving == RestSession.State.ACTIVE || leaving == RestSession.State.PAUSED)) {
            environment.refresh();
            main.postDelayed(endRestAfterLeaving, LEAVE_SETTLE_MS);
        }
        PresetLink.get(this).removeListener(restIntake);
        PresetLink.get(this).exitForeground();
        connectionVerified = false;
        foreground = false; epoch++; busy = false; main.removeCallbacks(tick);
        if (revocation.pending()) connectionMessage = PendingRevocation.message(PendingRevocation.Outcome.PENDING);
        session.pause(SystemClock.elapsedRealtime()); cancelDeadline();
        localConfirmation = false; consentBox.setChecked(false); super.onStop();
    }

    @Override public void onDestroy() {
        main.removeCallbacks(endRestAfterLeaving); // an unfinished rest's ledger stays stored and is put back next run
        main.removeCallbacks(keepReadingWhileRestoring);
        epoch++; cancelDeadline(); environment.close(); network.shutdownNow();
        vehicleToken = null; proposal = null; revocation.clear();
        revocationRelay = null; revocationPairingId = null; super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        String origin = originInput.getText().toString(); MainScreen.build(this); shell.install(); originInput.setText(origin); render();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        environment.refresh();
        render();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 52 || resultCode != RESULT_OK || data == null) return;
        try {
            JSONObject snapshot = new JSONObject(data.getStringExtra("presetSnapshot"));
            String comfort = snapshot.getString("comfort");
            if (!PresetSyncPolicy.validComfort(comfort)) return;
            selectedPreset = comfort;
            connectedPresetDescription = "연결된 선호: " + snapshot.getString("name")
                    + "\n차: " + connectedValues(snapshot.getJSONObject("vehicle"))
                    + "\n집: " + connectedValues(snapshot.getJSONObject("home")) + "\n시트는 직접 조절해 주세요.";
            if (snapshot.has("cabin")) PresetSyncResponsePolicy.cabin(PresetSyncClient.plain(snapshot.getJSONObject("cabin"), 0));
            sharedClimate = sharedClimateOf(snapshot.getJSONObject("vehicle"));
            sharedCabin = cabinOf(snapshot.optJSONObject("cabin"));
            sharedRestName = snapshot.getString("name");
            String seen = data.getStringExtra("seenProposalId");
            if (seen != null && seen.startsWith("phone-")) {
                takenProposalId = seen;
                getSharedPreferences(CarShell.PREFS, MODE_PRIVATE).edit().putString("taken_proposal", seen).apply();
            }
            localConfirmation = false; consentBox.setChecked(false); openPreparation();
        } catch (Exception invalid) { /* Untrusted activity data never changes the rest session. */ }
    }

    /** Rests the phone sends (propose_rest) arrive through the preset link while this screen is in front. */
    private final PresetLink.Listener restIntake = new PresetLink.Listener() {
        @Override public void onLinkUpdated() { }
        @Override public void onLinkEnded() { }
        @Override public void onRestProposed(JSONObject proposal) { takePhoneRest(proposal); }
    };

    /**
     * A rest chosen on the phone becomes the rest of 시작, with its climate and 차 안 설정 (shown before starting).
     * Only the phone's own proposals ("phone-…"), each once, never while a rest runs; starting stays a press here.
     */
    void takePhoneRest(JSONObject p) {
        String id = p.optString("id");
        if (!id.startsWith("phone-") || id.equals(takenProposalId)) return;
        RestSession.State st = session.state();
        if (st == RestSession.State.ACTIVE || st == RestSession.State.PAUSED) return; // taken after the rest ends
        String comfort = p.optString("comfort");
        if (!PresetSyncPolicy.validComfort(comfort)) return;
        takenProposalId = id;
        getSharedPreferences(CarShell.PREFS, MODE_PRIVATE).edit().putString("taken_proposal", id).apply();
        commitRestChoice(comfort, naturalRestMode);
        sharedRestName = p.optString("name", CarCopy.restName(comfort));
        sharedClimate = sharedClimateOf(p.optJSONObject("vehicle"));
        sharedCabin = cabinOf(p.optJSONObject("cabin"));
        proposalReceivedAtMs = System.currentTimeMillis();
        localConfirmation = false;
        if (consentBox != null) consentBox.setChecked(false);
        if (shell != null) shell.showPill("폰에서 고른 쉼이 도착했어요 · 시작은 화면에서", Screen.HOME);
        render();
    }

    private static CabinControl.Shared sharedClimateOf(JSONObject car) {
        if (car == null) return null;
        Integer tenths = null;
        if (!car.isNull("tempC")) {
            double t = car.optDouble("tempC", Double.NaN);
            if (t >= 18 && t <= 28 && t * 2 == Math.floor(t * 2)) tenths = (int) Math.round(t * 10);
        }
        String fan = car.optString("fan", "keep");
        return new CabinControl.Shared(tenths, fan.matches("low|medium|high") ? fan : "keep");
    }

    /** The phone's 차 안 설정 (already validated by PresetSyncResponsePolicy.cabin) as the car's model; "keep" stays keep. */
    static CabinSettings cabinOf(JSONObject c) {
        if (c == null) return null;
        try {
            CabinSettings out = new CabinSettings();
            for (boolean driver : new boolean[]{true, false}) {
                JSONObject s = c.getJSONObject(driver ? "seatDriver" : "seatPassenger");
                CabinSettings.Seat seat = out.seat(driver);
                seat.position = s.getString("position"); seat.comfort = s.getString("comfort"); seat.level = s.getInt("level");
                JSONObject k = c.getJSONObject(driver ? "climDriver" : "climPassenger");
                CabinSettings.Climate climate = out.climate(driver);
                climate.mode = k.getString("mode"); climate.tempTenths = (int) Math.round(k.getDouble("temp") * 10);
                climate.fan = k.getString("fan"); climate.dir = k.getString("dir"); climate.ac = k.getString("ac");
            }
            JSONObject l = c.getJSONObject("light");
            out.light.mode = l.getString("mode"); out.light.color = l.getString("color");
            out.light.bright = Math.max(CabinSettings.MIN_BRIGHT, Math.min(CabinSettings.MAX_BRIGHT, Math.round(l.getInt("bright") / 5f) * 5));
            return out;
        } catch (Exception malformed) { return null; }
    }

    private String connectedValues(JSONObject value) {
        return (value.isNull("tempC") ? "온도 유지" : value.optString("tempC") + "°C") + " · 바람 " + value.optString("fan");
    }

    void cancelDeadline() {
        deadlineEpoch++;
        if (deadline != null) main.removeCallbacks(deadline);
        deadline = null;
    }

    void scheduleDeadline() {
        cancelDeadline();
        if (session.state() != RestSession.State.ACTIVE) return;
        final int scheduledEpoch = deadlineEpoch;
        long remaining = RestSession.DURATION_MS - session.elapsedActiveMs(SystemClock.elapsedRealtime());
        deadline = () -> {
            if (scheduledEpoch != deadlineEpoch || !foreground) return;
            session.check(SystemClock.elapsedRealtime(), decision());
            render();
        };
        main.postDelayed(deadline, remaining);
    }

    void createPairing() {
        if (busy || pairingId != null || revocation.pending()) return;
        if (!BuildConfig.DEBUG && originInput.getText().toString().trim().isEmpty()) {
            connectionMessage = RELEASE_NO_SERVER; render(); return;
        }
        try { relay = new RelayClient(originInput.getText().toString(), BuildConfig.DEBUG); }
        catch (Exception e) { connectionMessage = "유효한 HTTPS 중계 서버 주소를 입력해 주세요 (디버그: 로컬 HTTP 허용)."; render(); return; }
        final RelayClient currentRelay = relay;
        request(() -> currentRelay.request("POST", "/api/pairings", null, new JSONObject()), response -> {
            try {
                pairingId = response.getString("pairingId");
                RelayClient.pairingPath(pairingId);
                vehicleToken = response.getString("vehicleToken");
                pairingCode = response.getString("code");
                pairingExpiresAtMs = PendingRevocation.deadline(response.optString("expiresAt"));
                pairingStatus = "waiting";
                connectionVerified = false;
                connectionMessage = "휴대폰 앱에 입력할 6자리 연결 번호: " + response.getString("code")
                        + "\n만료 시각: " + response.getString("expiresAt");
            } catch (Exception e) { connectionMessage = "연결 응답 형식을 확인하지 못했어요. 다시 시도해 주세요."; pairingId = null; vehicleToken = null; }
            render();
        });
    }

    void confirmPairing() {
        if (busy || confirmation == null || pairingId == null || !"claimed".equals(pairingStatus)) return;
        final String expected = confirmation, id = pairingId, token = vehicleToken;
        final RelayClient currentRelay = relay;
        request(() -> currentRelay.request("POST", RelayClient.pairingPath(id) + "/confirm", token,
                new JSONObject().put("confirmation", expected)), response -> {
            pairingStatus = response.optString("status", "waiting");
            pairingExpiresAtMs = PendingRevocation.deadline(response.optString("expiresAt"));
            markConnectionVerified();
            confirmation = null;
            connectionMessage = "paired".equals(pairingStatus)
                    ? "연결됨 · 휴대폰이 승인됐어요. 공유된 운동 요약을 기다리고 있어요."
                    : "연결 승인이 완료되지 않았어요.";
            render();
        });
    }

    private void pollPairing() {
        final String id = pairingId, token = vehicleToken;
        final boolean receiveSummary = !manualMode;
        final RelayClient currentRelay = relay;
        request(() -> {
            JSONObject pairing = currentRelay.request("GET", RelayClient.pairingPath(id), token, null);
            JSONObject result = new JSONObject().put("pairing", pairing);
            if (receiveSummary && "paired".equals(pairing.optString("status")))
                result.put("envelope", currentRelay.request("GET", RelayClient.pairingPath(id) + "/summary", token, null));
            return result;
        }, response -> {
            try {
                JSONObject pairing = response.getJSONObject("pairing");
                pairingStatus = pairing.getString("status");
                pairingExpiresAtMs = PendingRevocation.deadline(pairing.optString("expiresAt"));
                if ("claimed".equals(pairingStatus)) {
                    confirmation = pairing.getString("confirmation");
                    connectionMessage = "두 화면의 4자리 확인 번호 비교: " + confirmation + "\n번호가 일치할 때만 승인해 주세요.";
                }
                if ("paired".equals(pairingStatus) && response.has("envelope")) {
                    if (manualMode) return;
                    RecoveryProposal received = RecoveryProposal.fromEnvelope(response.getJSONObject("envelope"), System.currentTimeMillis());
                    if (received != null && (proposal == null || !received.id.equals(proposal.id))) {
                        proposal = received;
                        proposalReceivedAtMs = System.currentTimeMillis();
                        RestSession.State st = session.state();
                        if (!received.comfort.isEmpty() && st != RestSession.State.ACTIVE && st != RestSession.State.PAUSED) {
                            selectedPreset = received.comfort;
                            localConfirmation = false;
                            if (consentBox != null) consentBox.setChecked(false);
                        }
                    } else if (received == null && proposal != null && !proposal.id.startsWith("local-manual-")) {
                        proposal = null;
                    }
                    connectionMessage = received == null
                            ? "연결됨 · 아직 공유된 운동 요약이 없어요."
                            : "연결됨 · 운동 요약과 선호 제안을 받았어요. 차량 화면에서 직접 확인해 주세요.";
                }
                if ("paired".equals(pairingStatus) && manualMode)
                    connectionMessage = "서버 승인 확인 · 운동 요약 수신을 사용하지 않고 있어요.";
                markConnectionVerified();
            } catch (Exception e) { connectionVerified = false; proposal = null; connectionMessage = "만료되었거나 형식이 맞지 않는 요약을 비웠어요."; }
            render();
        });
    }

    void disconnect() {
        if (revocation.pending()) { retryRevocation(); return; }
        boolean canRevoke = pairingId != null && vehicleToken != null && relay != null;
        if (canRevoke) {
            revocationRelay = relay;
            revocationPairingId = pairingId;
            revocation.retain(vehicleToken, pairingExpiresAtMs);
        }
        epoch++; busy = false; clearProposal();
        pairingId = null; vehicleToken = null; confirmation = null; relay = null; pairingCode = null;
        pairingExpiresAtMs = 0;
        pairingStatus = "waiting";
        connectionVerified = false;
        connectionMessage = canRevoke ? "이 차량의 연결과 요약을 비웠어요. 서버 해제를 확인하고 있어요."
                : "이 차량의 연결과 요약을 비웠어요. 서버 연결은 확인되지 않았어요.";
        render();
        if (canRevoke) retryRevocation();
    }

    void retryRevocation() {
        if (busy || !revocation.pending() || expireRevocation()) return;
        final RelayClient currentRelay = revocationRelay;
        final String path = RelayClient.pairingPath(revocationPairingId), token = revocation.token();
        final long attempt = revocation.generation();
        connectionMessage = "이 차량의 데이터는 비웠어요. 서버 해제를 확인하고 있어요.";
        request(() -> currentRelay.request("DELETE", path, token, null), response -> {
            finishRevocation(revocation.resolve(attempt, 200, Boolean.TRUE.equals(response.opt("deleted"))));
        }, error -> {
            int status = error instanceof RelayClient.HttpFailure ? ((RelayClient.HttpFailure) error).status : -1;
            finishRevocation(revocation.resolve(attempt, status, false));
        });
    }

    private void finishRevocation(PendingRevocation.Outcome outcome) {
        if (outcome == PendingRevocation.Outcome.NONE) return;
        if (!revocation.pending()) { revocationRelay = null; revocationPairingId = null; }
        connectionMessage = PendingRevocation.message(outcome);
        render();
    }

    private boolean expireRevocation() {
        if (!revocation.expire(System.currentTimeMillis())) return false;
        epoch++; busy = false;
        finishRevocation(PendingRevocation.Outcome.EXPIRED);
        return true;
    }

    void clearProposal() { proposal = null; }

    boolean hasRemoteProposal() { return proposal != null && !proposal.id.startsWith("local-manual-"); }

    void markConnectionVerified() {
        connectionVerified = true;
        connectionVerifiedAtMs = SystemClock.elapsedRealtime();
    }

    boolean isConnectionCurrent() {
        return connectionVerified && SystemClock.elapsedRealtime() - connectionVerifiedAtMs < 10_000;
    }

    /** A paused rest is continued from 시작 (‘계속하기’); otherwise preparation is 시작 전에 바꾸기. */
    void openPreparation() {
        showScreen(session.state() == RestSession.State.PAUSED ? Screen.HOME : Screen.PREPARATION);
    }

    void showScreen(Screen next) {
        if (next != Screen.SESSION && session.state() == RestSession.State.ACTIVE) {
            session.pause(SystemClock.elapsedRealtime()); cancelDeadline();
        }
        // The on-screen confirmation is the press of ‘3분 쉬기’/‘계속하기’ itself; leaving clears it.
        if (next != Screen.SESSION && next != Screen.BLOCKED) localConfirmation = false;
        if (next == Screen.PREPARATION && screen != Screen.PREPARATION && shell != null) {
            shell.draftPreset = selectedPreset.isEmpty() ? "quiet_rest" : selectedPreset;
            shell.draftNatural = naturalRestMode;
        }
        if (next == Screen.CONNECTIONS && screen != Screen.CONNECTIONS && shell != null) {
            shell.pairCompareStep = "claimed".equals(pairingStatus) && confirmation != null;
            shell.pairWaitingHint = false;
        }
        screen = next; render();
    }

    /**
     * ‘3분 쉬기’ / ‘계속하기’ pressed on the vehicle screen. The press is the person's confirmation here
     * (open decision 3); SafetyGate still decides, and a refusal shows 시작 보류 with per-item reasons.
     * Nothing else (phone data, Gleo, a received summary) ever reaches this method.
     */
    void startRest() {
        long now = SystemClock.elapsedRealtime();
        RestSession.State state = session.state();
        if (state == RestSession.State.ACTIVE) { screen = Screen.SESSION; render(); return; }
        if (selectedPreset.isEmpty()) { showScreen(Screen.PREPARATION); return; }
        localConfirmation = true;
        boolean ok;
        if (state == RestSession.State.PAUSED) ok = session.resume(now, decision());
        else { restFeedback = ""; ok = session.start(now, decision()); }
        if (ok && state != RestSession.State.PAUSED) applyRestCabin();
        if (ok) { screen = Screen.SESSION; scheduleDeadline(); render(); }
        else showScreen(Screen.BLOCKED);
    }

    // ── 차 안 맞추기 ─────────────────────────────────────────────

    /** Null when the cabin may be changed now, otherwise the reason in on-screen words. */
    String cabinBlocked() {
        if (environment.cabin() == null) return "이 빌드는 차 안을 바꾸지 않아요";
        long now = SystemClock.elapsedRealtime();
        return CabinControl.writeBlocked(environment.snapshot(now), now);
    }

    /** True while the car values are too old to judge parking (read again each second in front, or by refresh()). */
    boolean cabinStateStale() {
        long now = SystemClock.elapsedRealtime();
        return CabinControl.stale(environment.snapshot(now), now);
    }

    /**
     * The person pressed ‘3분 쉬기’: set the chosen rest's climate and 차 안 설정 (seat heat/ventilation, light),
     * remembering what the car had so the end of the rest can put it back.
     */
    private void applyRestCabin() {
        CabinActuator cabin = environment.cabin();
        cabinFinishNote = null;
        finishReason = null;
        cabinRestNote = "";
        if (cabin == null && BuildConfig.LIVE_REST) cabinRestNote = "차 안은 직접 맞춰 주세요";
        if (cabin == null || shell == null) return;
        java.util.List<CabinControl.Step> plan = restPlan();
        if (plan.isEmpty()) return;
        String blocked = cabinBlocked();
        if (blocked != null) { cabinRestNote = "차 안은 그대로예요 · " + blocked; return; }
        // Each rest owns its own ledger; an earlier rest's restore stays queued ahead of this one. A rest whose end
        // was not handled yet (completed within this very pass) is ended first, so its ledger is never orphaned.
        if (restSession != null) cabin.endSession(restSession, null);
        restSession = cabin.beginSession();
        cabinRestNote = "차 안을 맞추는 중";
        cabin.apply(plan, restSession, 0, result -> {
            cabinRestNote = result.brief();
            CabinActuator.log.accept("rest apply: " + result.summary());
            shell.readCabinNow();
            render();
        });
    }

    /** The one plan the start of a rest uses; 시작 화면 and Gleo describe the same plan. */
    java.util.List<CabinControl.Step> restPlan() {
        return shell == null ? java.util.Collections.emptyList()
                : CabinControl.plan(shell.cabinSaved, selectedPreset, sharedClimate, sharedCabin);
    }

    /**
     * The rest ended (finished or 마치기): its unsent steps are dropped and what it changed is put back while
     * parked. Anything it could not put back stays owed and is retried once the car is parked again.
     */
    private void restoreRestCabin() {
        CabinActuator cabin = environment.cabin();
        CabinActuator.Session ended = restSession;
        restSession = null;
        final String reason = restEndReason;
        restEndReason = null;
        finishReason = reason;
        if (cabin == null || ended == null) { cabinFinishNote = reason; return; }
        cabinFinishNote = withReason(reason, "차 안을 쉬기 전으로 돌리고 있어요.");
        cabin.endSession(ended, result -> {
            if (!result.restore) return; // the cancelled apply answers first; the restore answer follows
            cabinFinishNote = result.items.isEmpty() ? reason : withReason(reason, result.brief());
            CabinActuator.log.accept("rest restore: " + result.summary());
            if (shell != null) shell.readCabinNow();
            render();
        });
    }

    private static String withReason(String reason, String note) {
        return reason == null ? note : note == null ? reason : reason + " · " + note;
    }

    /**
     * Owed restores (blocked by driving or unconfirmed) are tried again while parked with this screen open:
     * every 15 s for the first five tries, then every minute, for as long as something is owed.
     */
    private void retryOwedRestores() {
        CabinActuator cabin = environment.cabin();
        if (cabin == null || restSession != null || !cabin.owesRestore() || cabin.busy()) return;
        RestSession.State st = session.state();
        if (st == RestSession.State.ACTIVE || st == RestSession.State.PAUSED || cabinBlocked() != null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastRestoreTryMs < (restoreTries < 5 ? 15_000 : 60_000)) return;
        lastRestoreTryMs = now;
        restoreTries++;
        cabin.retryRestores(result -> {
            if (!result.items.isEmpty()) cabinFinishNote = withReason(finishReason, result.brief());
            if (shell != null) shell.readCabinNow();
            render();
        });
    }

    /**
     * A rest ends by itself when the car moves (a gear other than P, or speed) or a pause is left for
     * {@link RestInterruption#PAUSE_LIMIT_MS}; render() then puts the cabin back (now, or once parked again).
     */
    private void endInterruptedRest() {
        long now = SystemClock.elapsedRealtime();
        RestSession.State st = session.state();
        if (st != RestSession.State.PAUSED) pausedAtMs = 0;
        else if (pausedAtMs == 0) pausedAtMs = now;
        RestInterruption.Reason why = RestInterruption.check(st, environment.snapshot(now), pausedAtMs, now);
        if (why != RestInterruption.Reason.NONE) endRest(now, RestInterruption.note(why));
    }

    /** How long after leaving the screen the rest ends, so the fresh gear and speed reading has arrived. */
    static final long LEAVE_SETTLE_MS = 1_500;

    private final Runnable endRestAfterLeaving = () -> {
        if (foreground) return;
        long now = SystemClock.elapsedRealtime();
        boolean moved = RestInterruption.carMoved(environment.snapshot(now));
        if (endRest(now, moved ? RestInterruption.note(RestInterruption.Reason.MOVED) : "화면을 떠나서 쉼을 마쳤어요")) {
            render();
            main.postDelayed(this.keepReadingWhileRestoring, 1_000);
        }
    };

    /** While that restore runs off screen, keep the parking values fresh (every write still asks the gate). */
    private final Runnable keepReadingWhileRestoring = new Runnable() {
        @Override public void run() {
            CabinActuator cabin = environment.cabin();
            if (foreground || cabin == null || !cabin.busy()) return;
            environment.refresh();
            main.postDelayed(this, 1_000);
        }
    };

    /** Ends a running or paused rest like ‘마치기’, with the reason shown next to the restore result. */
    private boolean endRest(long now, String reason) {
        RestSession.State st = session.state();
        if (st != RestSession.State.ACTIVE && st != RestSession.State.PAUSED) return false;
        if (!session.finish(now)) return false;
        restEndReason = reason;
        pausedAtMs = 0;
        cancelDeadline();
        screen = Screen.SESSION;
        return true;
    }

    /** ‘쉬기 마치기’ from 시작 while paused: same as the rest screen's ‘마치기’. */
    void endPausedRest() {
        if (session.finish(SystemClock.elapsedRealtime())) { cancelDeadline(); screen = Screen.SESSION; }
        render();
    }

    void commitRestChoice(String preset, boolean natural) {
        if (!preset.equals(selectedPreset)) { selectedPreset = preset; clearSharedRest(); }
        naturalRestMode = natural;
        localConfirmation = false;
        getSharedPreferences(CarShell.PREFS, MODE_PRIVATE).edit().putString("last_preset", selectedPreset)
                .putBoolean("natural_mode", naturalRestMode).apply();
    }

    /** Drops the values of a rest chosen on the phone; the built-in rest's own climate applies again. */
    void clearSharedRest() {
        connectedPresetDescription = "";
        sharedClimate = null;
        sharedCabin = null;
        sharedRestName = null;
    }

    /** Parking checks for the intro: fresh state, gear P and 0 km/h (SafetyGate itself is unchanged). */
    boolean parkingVerified() {
        long now = SystemClock.elapsedRealtime();
        SafetyGate.Snapshot s = environment.snapshot(now);
        return s != null && now - s.sampledAtMs >= 0 && now - s.sampledAtMs < SafetyGate.MAX_STATE_AGE_MS
                && s.gear == SafetyGate.Gear.PARK && s.speedKph != null && s.speedKph == 0.0;
    }

    /** OPEN_CABIN_SETTINGS: open 차 안 설정 on a tab. Never while a rest session is running (policy refuses first). */
    void openCabin(String section) {
        if (shell == null) return;
        shell.cabinTab = section;
        showScreen(Screen.CABIN);
    }

    @Override public void onBackPressed() {
        if (session.state() == RestSession.State.ACTIVE) {
            session.pause(SystemClock.elapsedRealtime()); cancelDeadline(); render();
        } else if (screen != Screen.HOME) showScreen(Screen.HOME);
        else super.onBackPressed();
    }

    SafetyGate.Decision decision() {
        return SafetyGate.preview(restSnapshot(SystemClock.elapsedRealtime()),
                SystemClock.elapsedRealtime(), !selectedPreset.isEmpty(),
                foreground && localConfirmation && !selectedPreset.isEmpty());
    }

    /** What SafetyGate judges: the demo's synthetic state, or in the pleos builds the car's real values. */
    SafetyGate.Snapshot restSnapshot(long now) {
        SafetyGate.Snapshot s = environment.snapshot(now);
        return BuildConfig.LIVE_REST ? CabinControl.forLiveRest(s) : s;
    }

    void render() {
        RestSession.State restState = session.state();
        noteRestState(restState);
        MainScreen.render(this, decision());
        // MainScreen.render runs the session's own check, which can be the moment the three minutes complete.
        // That end is handled in this pass, not a tick later: ‘다시 3분 쉬기’ must never start over an unended rest.
        RestSession.State after = session.state();
        if (after != restState) noteRestState(after);
        if (shell != null) shell.render();
    }

    /** The end of a rest (finished or ended) is seen once: the cabin goes back to what it was. */
    private void noteRestState(RestSession.State restState) {
        if ((restState == RestSession.State.COMPLETE || restState == RestSession.State.ENDED)
                && (lastRestState == RestSession.State.ACTIVE || lastRestState == RestSession.State.PAUSED)) {
            lastRestState = restState;
            restoreRestCabin();
        }
        lastRestState = restState;
    }

    private <T> void request(Callable<T> task, Consumer<T> success) {
        request(task, success, this::requestFailed);
    }

    private <T> void request(Callable<T> task, Consumer<T> success, Consumer<Exception> failure) {
        if (busy) return;
        busy = true;
        int requestEpoch = epoch;
        network.submit(() -> {
            try {
                T response = task.call();
                main.post(() -> {
                    if (requestEpoch != epoch) return;
                    busy = false;
                    if (foreground) success.accept(response);
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (requestEpoch != epoch) return;
                    busy = false;
                    if (!foreground) return;
                    failure.accept(e);
                });
            }
        });
        render();
    }

    private void requestFailed(Exception error) {
        connectionVerified = false;
        clearProposal();
        if (error instanceof RelayClient.HttpFailure) {
            int code = ((RelayClient.HttpFailure) error).status;
            if (code == 401 || code == 403 || code == 404 || code == 410) {
                pairingId = null; vehicleToken = null; confirmation = null; pairingExpiresAtMs = 0; pairingCode = null;
                pairingStatus = "waiting"; relay = null;
            }
        }
        connectionMessage = pairingId == null
                ? "연결을 확인하지 못했어요. 이 차량의 수신 요약을 비웠어요."
                : "연결을 확인할 수 없어 운동 요약을 비웠어요. 로컬 안전 조건만으로 쉼을 준비할 수 있어요.";
        render();
    }
}
