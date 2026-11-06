package ai.pitin.vehicle;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 함께 쓰는 쉼 (car shared presets), in the redesign's car layout and the car's current mood.
 * Authenticated shared preferences only. No rest-session or vehicle-control access.
 * The link itself (credential, approval, identity, appearance reporting) is the process-scoped
 * {@link PresetLink}, so leaving this screen keeps an approved link and the car's theme reports.
 */
public final class ConnectedPresetsActivity extends Activity implements PresetLink.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private PresetLink link;
    private PresetSyncClient client;
    private final PresetSyncPolicy policy = new PresetSyncPolicy();
    private final PendingRevocation revocation = new PendingRevocation();
    private final ArrayList<View> writes = new ArrayList<>();
    private CarKit k;
    private CarTheme t;
    private String message = "웹에서 차량용 연결 번호를 만든 뒤 입력해 주세요.";
    private boolean busy, reading, detailsExpanded;
    private long lastObservedAtMs;
    private JSONObject state, uncertain, preparationSnapshot;
    private String renderedContentKey;
    private LinearLayout content, confirmBox;
    private TextView live, connOverline, connTitle, connBody, comparisonNumber, status, appearanceStatus, preparationHint;
    private TextView confirmText, confirmCancel, confirmOk;
    private EditText code;
    private TextView claim, retry, disconnect, prepare;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!policy.foreground) return;
            expireRevocation();
            if (!busy && !reading && link.token != null) read();
            else render();
            main.postDelayed(this, 2000);
        }
    };
    private static JSONObject object(Object... pairs) {
        JSONObject value = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]); }
        catch (Exception e) { throw new IllegalArgumentException(e); }
        return value;
    }
    /** A submission build opened without a relay address: there is no server to claim a number on. */
    private boolean noServer() {
        String origin = getIntent() == null ? null : getIntent().getStringExtra("relayOrigin");
        return !BuildConfig.DEBUG && (origin == null || origin.trim().isEmpty());
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        link = PresetLink.get(this); client = link.client; link.addListener(this);
        if (getIntent() != null) link.useRelayOrigin(getIntent().getStringExtra("relayOrigin"));
        if (link.approved) message = "승인된 연결 · 공유 선호를 다시 읽는 중";
        else if (link.token != null) message = "비교 번호 " + link.comparison + " · 웹에서 일치 확인 후 승인해 주세요.";
        else if (noServer()) message = "이 빌드는 연결 서버 없이 동작해서 취향 함께 쓰기를 쓸 수 없어요.";
        SharedPreferences prefs = getSharedPreferences(CarShell.PREFS, MODE_PRIVATE);
        t = CarTheme.of(prefs.getString("car_mood", CarTheme.GRAPHITE_ID));
        k = new CarKit(this, t, CarKit.unitFor(this), prefs.getBoolean("reduced_motion", false));

        FrameLayout root = new FrameLayout(this);
        root.setBackground(CarTheme.GRAPHITE_ID.equals(t.id) ? new GraphiteBackground(this) : new CarShell.MoodGround(this, t));
        LinearLayout col = k.column(root);
        col.setPadding(k.px(76), k.px(CarKit.FRAME_TOP), k.px(76), k.px(60));
        col.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        col.setClipChildren(false);

        LinearLayout header = k.header(col);
        TextView back = k.backButton(header, this::requestFinish);
        back.setContentDescription("폰 연결로 돌아가기");
        // The title tucks over the back button's empty right edge (not touchable), where the artboard puts it.
        CarKit.margins(k.title(header, "함께 쓰는 쉼", 40), -k.px(24), 0, 0, 0);
        k.flex(header);
        live = k.live(header, "", 24, true);

        LinearLayout body = k.row(col);
        body.setGravity(Gravity.TOP);
        body.setLayoutParams(CarKit.lp(-1, 0, 1));
        body.setPadding(0, k.px(40), 0, k.px(40));

        // Left: the link itself.
        LinearLayout conn = k.card(body);
        conn.setLayoutParams(CarKit.lp(0, -2, .9f));
        conn.setPadding(k.px(44), k.px(44), k.px(44), k.px(44));
        connOverline = k.overline(conn, "연결", 18);
        connTitle = k.title(conn, "", 44);
        CarKit.margins(connTitle, 0, k.px(18), 0, 0);
        connBody = k.body(conn, "");
        CarKit.margins(connBody, 0, k.px(16), 0, 0);
        code = new EditText(this);
        code.setHint("차량용 6자리 번호");
        code.setTextColor(t.ink); code.setHintTextColor(t.ink3);
        code.setTextSize(TypedValue.COMPLEX_UNIT_PX, k.px(34));
        code.setTypeface(CarKit.face(CarKit.W400));
        // Digits are spaced like the phone's number; the hint stays plain words.
        code.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                boolean digits = s.length() > 0;
                code.setLetterSpacing(digits ? .12f : 0f);
                code.setTypeface(digits ? CarKit.numFace() : CarKit.face(CarKit.W400));
                code.setTextSize(TypedValue.COMPLEX_UNIT_PX, k.px(digits ? 48 : 34));
            }
        });
        code.setSingleLine(true);
        code.setGravity(Gravity.CENTER);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setBackground(CarKit.round(t.surface2, k.px(28), t.line2, Math.max(1, k.px(1))));
        code.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_DONE);
        code.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(code.getWindowToken(), 0);
            code.clearFocus();
            return true;
        });
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        code.setContentDescription("웹에서 만든 차량용 6자리 연결 번호");
        LinearLayout.LayoutParams codeLp = CarKit.lp(-1, k.px(104));
        codeLp.topMargin = k.px(36);
        conn.addView(code, codeLp);
        claim = k.primary(conn, "연결 요청", this::claim);
        claim.setLayoutParams(withTop(CarKit.lp(-1, -2), 20));
        comparisonNumber = k.num(conn, "", 120, t.ink);
        comparisonNumber.setLetterSpacing(.2f);
        CarKit.margins(comparisonNumber, 0, k.px(24), 0, 0);
        status = k.caption(conn, "");
        CarKit.margins(status, 0, k.px(28), 0, 0);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        appearanceStatus = k.caption(conn, link.appearanceStatus());
        CarKit.margins(appearanceStatus, 0, k.px(10), 0, 0);
        confirmBox = k.column(conn);
        confirmBox.setBackground(CarKit.round(t.surface2, k.px(28), t.line2, Math.max(1, k.px(1))));
        confirmBox.setPadding(k.px(32), k.px(28), k.px(32), k.px(28));
        confirmBox.setLayoutParams(withTop(CarKit.lp(-1, -2), 28));
        confirmText = k.text(confirmBox, "", 26, t.ink, CarKit.W500);
        LinearLayout confirmRow = k.row(confirmBox);
        confirmRow.setLayoutParams(withTop(CarKit.lp(-1, -2), 20));
        confirmCancel = k.secondary(confirmRow, "", () -> confirmBox.setVisibility(View.GONE));
        confirmCancel.setLayoutParams(CarKit.lp(0, -2, 1));
        k.space(confirmRow, 16);
        confirmOk = k.primary(confirmRow, "", null);
        confirmOk.setLayoutParams(CarKit.lp(0, -2, 1));
        confirmBox.setVisibility(View.GONE);
        retry = k.secondary(conn, "결과 확인 · 같은 요청 다시 보내기", () -> { if (uncertain != null && !busy) send(uncertain); });
        retry.setLayoutParams(withTop(CarKit.lp(-1, -2), 24));
        disconnect = k.ghost(conn, "공유 선호 연결 해제", null, () -> {
            if (revocation.pending()) retryRevocation();
            else ask("공유 선호 연결을 해제할까요? 이 차량의 연결 정보를 비우고 서버에 해제를 요청해요. 저장된 공유 선호와 운동 요약 연결은 그대로예요.",
                    "취소", "해제 요청", this::disconnect);
        });
        disconnect.setLayoutParams(withTop(CarKit.lp(-2, -2), 16));
        CarKit.margins(disconnect, -k.px(24), k.px(16), 0, 0);

        k.space(body, 48);
        // Right: what is shared.
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        body.addView(scroll, CarKit.lp(0, -1, 1.25f));
        content = k.column(null);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout footer = k.footer(col);
        preparationHint = k.caption(footer, "");
        preparationHint.setLayoutParams(CarKit.lp(0, -2, 1));
        k.space(footer, 24);
        prepare = k.primary(footer, "이 선호로 쉬기 준비", this::prepareRest);
        prepare.setMinWidth(k.px(380));

        setContentView(root);
        getWindow().setStatusBarColor(t.bg);
        getWindow().setNavigationBarColor(t.bg);
        if (android.os.Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(t.dark ? 0 : light, light);
        }
        render();
    }
    private LinearLayout.LayoutParams withTop(LinearLayout.LayoutParams lp, float top) { lp.topMargin = k.px(top); return lp; }
    /** An in-place confirmation in the link card (instead of a system dialog). */
    private void ask(String text, String cancelLabel, String okLabel, Runnable ok) {
        confirmText.setText(text);
        confirmCancel.setText(cancelLabel);
        confirmOk.setText(okLabel);
        confirmOk.setOnClickListener(v -> { confirmBox.setVisibility(View.GONE); ok.run(); });
        confirmBox.setVisibility(View.VISIBLE);
        confirmOk.requestFocus();
    }
    @Override public void onStart() {
        super.onStart(); policy.start(); link.enterForeground(); busy = false; reading = false;
        expireRevocation(); render(); main.post(poll);
    }
    @Override public void onStop() {
        policy.stop(); link.exitForeground(); busy = false; reading = false; main.removeCallbacks(poll);
        if (revocation.pending()) message = PendingRevocation.message(PendingRevocation.Outcome.PENDING);
        super.onStop();
    }
    @Override public void onDestroy() {
        // The approved link stays in process memory (PresetLink); only this screen's view state goes.
        link.removeListener(this);
        state = null; uncertain = null; revocation.clear();
        network.shutdownNow(); super.onDestroy();
    }
    @Override public void onLinkUpdated() { render(); }
    @Override public void onLinkEnded() {
        // Revoked or expired while reporting in the background: same clearing as a 401/403 read.
        policy.generation++; busy = false; reading = false; state = null; uncertain = null; preparationSnapshot = null;
        policy.failed(); policy.resolved(); message = "연결 만료 또는 해제 · 웹에서 다시 연결해 주세요.";
        render();
    }
    @Override public void onBackPressed() { requestFinish(); }
    private void requestFinish() {
        if (!revocation.pending()) { finish(); return; }
        ask("서버 해제 확인이 남아 있어요. 이 화면을 닫으면 현재 연결로 해제를 다시 요청할 수 없어요.",
                "여기서 확인", "확인 없이 나가기", this::finish);
    }
    private void prepareRest() {
        if (!policy.canWrite() || busy || preparationSnapshot == null || revocation.pending()
                || !PresetSyncPolicy.validComfort(preparationSnapshot.optString("comfort"))) return;
        setResult(RESULT_OK, new Intent().putExtra("presetSnapshot", preparationSnapshot.toString())); finish();
    }
    private void claim() {
        String value = code.getText().toString();
        if (link.token != null || busy || revocation.pending()) return;
        if (!value.matches("[0-9]{6}")) { message = "6자리 번호를 입력해 주세요."; render(); return; }
        exchange("POST", "/pairings/claim", null, object("role", "vehicle", "code", value), false, reply -> {
            if (reply.status == 200 || reply.status == 201) {
                link.claimed(reply.body.getString("token"), reply.body.getString("comparisonCode"),
                        PendingRevocation.deadline(reply.body.optString("expiresAt")));
                code.setText(""); message = "비교 번호 " + link.comparison + " · 웹과 같으면 웹에서 승인해 주세요.";
            } else message = "연결 요청 거절 (" + reply.status + "). 웹에서 새 차량용 번호를 만들어 주세요.";
        });
    }
    private void read() {
        final String bearer = link.token;
        exchange("GET", link.approved ? "/state" : "/pairings/status", bearer, null, false, reply -> {
            if (!bearer.equals(link.token)) return; // the link changed while this read was in flight
            if (reply.status != 200) { policy.failed(); message = "읽기 실패 (" + reply.status + ") · 마지막 관측값"; return; }
            if (!link.approved) {
                JSONObject sync = reply.body.getJSONObject("sync");
                if (!"vehicle".equals(sync.getString("role"))) throw new IllegalArgumentException();
                link.comparison = reply.body.getString("comparisonCode");
                link.approved = "approved".equals(reply.body.getString("status"));
                link.credentialExpiresAtMs = PendingRevocation.deadline(sync.optString("expiresAt"));
                message = link.approved ? "승인됨 · 공유 상태를 읽는 중" : "비교 번호 " + link.comparison + " · 웹에서 일치 확인 후 승인해 주세요.";
                // Approval is known now: the car can report its theme even if this screen closes before /state.
                if (link.approved) link.observed(sync.getString("epoch"), sync.getString("deviceId"));
            } else observe(reply.body);
        });
    }
    private void observe(JSONObject wrapper) throws Exception {
        JSONObject next = wrapper.getJSONObject("state"), sync = wrapper.getJSONObject("sync");
        if (!"vehicle".equals(sync.getString("role"))) throw new IllegalArgumentException();
        if (policy.observe(policy.generation, sync.getString("epoch"), next.getLong("revision"))) {
            state = next;
            link.credentialExpiresAtMs = PendingRevocation.deadline(sync.optString("expiresAt"));
            lastObservedAtMs = SystemClock.elapsedRealtime();
            message = (policy.restarted ? "서버가 재시작되었어요 · " : "서버 승인 확인 · ") + "공유 선호를 읽었어요.";
            // Identity and server epoch for the process-scoped theme reports (an epoch change resets them).
            link.observed(policy.epoch, sync.getString("deviceId"));
        }
    }
    private void command(String action, JSONObject payload) {
        if (!policy.canWrite() || busy) return;
        JSONObject request = object("action", action, "payload", payload, "expectedEpoch", policy.epoch,
                "expectedRevision", policy.revision, "requestId", UUID.randomUUID().toString());
        uncertain = request; policy.beginCommand(); send(request);
    }
    private void send(JSONObject request) {
        exchange("POST", "/commands", link.token, request, true, reply -> {
            if (reply.status >= 200 && reply.status < 300) {
                if (!request.getString("requestId").equals(reply.body.optString("acknowledgedRequestId"))) throw new IllegalArgumentException();
                observe(reply.body); uncertain = null; policy.resolved();
            } else if (reply.status >= 400 && reply.status < 500) {
                uncertain = null; policy.resolved(); policy.failed();
                if (reply.body.has("state") && reply.body.has("sync")) observe(reply.body);
                message = reply.status == 409 ? "다른 화면에서 변경했어요. 최신 값을 검토하고 다시 선택해 주세요."
                        : "요청 거절 (" + reply.status + "). 상태를 확인해 주세요.";
            } else { policy.failed(); message = "결과 미확인 · 같은 요청으로 직접 재시도해 주세요."; }
        });
    }
    private void disconnect() {
        if (revocation.pending()) { retryRevocation(); return; }
        if (link.token == null || busy) return;
        revocation.retain(link.token, link.credentialExpiresAtMs);
        // Explicit disconnect clears the process-scoped link (credential, approval, identity, reports).
        policy.generation++; link.clear(); state = null; uncertain = null;
        preparationSnapshot = null;
        policy.failed(); policy.resolved(); message = "기기 내 연결 삭제 · 서버 해제 확인 중";
        retryRevocation();
    }
    private void retryRevocation() {
        if (busy || !revocation.pending() || expireRevocation()) return;
        final long attempt = revocation.generation();
        message = "이 차량의 연결은 비웠어요. 서버 해제를 확인하고 있어요.";
        exchange("DELETE", "/session", revocation.token(), null, false, reply -> {
            PendingRevocation.Outcome result = revocation.resolve(attempt, reply.status, Boolean.TRUE.equals(reply.body.opt("deleted")));
            if (result != PendingRevocation.Outcome.NONE) message = PendingRevocation.message(result);
        });
    }
    private boolean expireRevocation() {
        if (!revocation.expire(System.currentTimeMillis())) return false;
        policy.generation++; busy = false; reading = false;
        message = PendingRevocation.message(PendingRevocation.Outcome.EXPIRED); render();
        return true;
    }
    private interface Receiver { void accept(PresetSyncClient.Reply reply) throws Exception; }
    private void exchange(String method, String path, String bearer, JSONObject body, boolean command, Receiver receiver) {
        boolean readRequest = "GET".equals(method);
        if (busy || (readRequest && reading)) return;
        if (readRequest) reading = true;
        else { busy = true; reading = false; policy.generation++; }
        final long generation = policy.generation; render();
        network.submit(() -> {
            try {
                PresetSyncClient.Reply reply = client.request(method, path, bearer, body);
                main.post(() -> {
                    if (generation != policy.generation || !policy.foreground) return;
                    if (readRequest) reading = false; else busy = false;
                    if (!"DELETE".equals(method) && (reply.status == 401 || reply.status == 403)) {
                        // Revocation or credential expiry: the process-scoped link is cleared too.
                        if (bearer == null || bearer.equals(link.token)) link.clear();
                        state = null; uncertain = null;
                        policy.failed(); policy.resolved(); message = "연결 만료 또는 해제 · 웹에서 다시 연결해 주세요.";
                    } else {
                        try { receiver.accept(reply); }
                        catch (Exception invalid) { policy.failed(); message = "DELETE".equals(method)
                                ? PendingRevocation.message(PendingRevocation.Outcome.PENDING)
                                : command ? "결과 미확인 · 같은 요청으로 재시도해 주세요." : "상태 응답을 확인할 수 없어요."; }
                    }
                    render();
                });
            } catch (Exception unavailable) {
                main.post(() -> {
                    if (generation != policy.generation || !policy.foreground) return;
                    if (readRequest) reading = false; else busy = false;
                    policy.failed();
                    message = "DELETE".equals(method) ? PendingRevocation.message(PendingRevocation.Outcome.PENDING)
                            : command ? "결과 미확인 · 같은 요청으로 직접 재시도해 주세요."
                            : "연결을 확인할 수 없어요. 마지막으로 읽은 선호를 표시하고 변경은 잠갔어요.";
                    render();
                });
            }
        });
    }
    private void render() {
        if (status == null) return;
        boolean revoking = revocation.pending(), pending = link.token != null && !link.approved;
        String deviceId = link.observedDeviceId;
        // Link card: one state at a time.
        connOverline.setText(revoking ? "연결 정리" : link.approved ? "이어짐" : pending ? "승인 기다리는 중" : "연결");
        connTitle.setText(revoking ? "서버 해제를\n확인하고 있어요" : link.approved ? "웹과 폰의 쉼을\n함께 쓰고 있어요"
                : pending ? "두 화면의 숫자가\n같은가요?" : "웹에서 만든\n차량용 번호를 입력해요");
        connBody.setText(revoking ? "이 차량의 연결 정보는 이미 비웠어요."
                : link.approved ? "폰에서 보낸 쉼과 차 안 설정이 여기로 와요. 시작은 언제나 차 화면에서 직접 눌러요."
                : pending ? "같을 때만 웹에서 승인해 주세요. 승인되면 공유 선호를 읽어와요."
                : "연결은 선택이에요. 연결하지 않아도 이 차의 쉼은 그대로 쓸 수 있어요.");
        live.setText(revoking ? "해제 확인 중" : link.approved ? (policy.fresh ? "이어져 있어요" : "다시 확인하는 중") : pending ? "승인 기다리는 중" : "연결 안 함");
        k.liveState(live, !(link.approved && policy.fresh));
        comparisonNumber.setText(pending ? link.comparison : "");
        comparisonNumber.setVisibility(pending ? View.VISIBLE : View.GONE);
        appearanceStatus.setText(link.appearanceStatus() + (deviceId == null || !BuildConfig.DEBUG ? "" : " · 출처 "
                + deviceId.substring(Math.max(0, deviceId.length() - 6))));
        appearanceStatus.setVisibility(link.approved && state != null ? View.VISIBLE : View.GONE);
        status.setText(message + (uncertain == null ? "" : "\n결과 미확인 요청이 있어 새 변경은 잠겼어요.")
                + (state != null ? "\n마지막 확인 " + Math.max(0, (SystemClock.elapsedRealtime() - lastObservedAtMs) / 1000) + "초 전"
                + (!policy.fresh ? " · 다시 확인하기 전에는 변경할 수 없어요." : " · 실제 기기 적용 확인은 아니에요.") : ""));
        boolean canClaim = link.token == null && !revoking && !noServer();
        CarKit.enable(claim, canClaim && !busy); code.setEnabled(canClaim && !busy);
        claim.setVisibility(canClaim ? View.VISIBLE : View.GONE); code.setVisibility(canClaim ? View.VISIBLE : View.GONE);
        disconnect.setVisibility(link.token != null || revoking ? View.VISIBLE : View.GONE);
        disconnect.setText(revoking ? "서버 해제 다시 확인" : "공유 선호 연결 해제");
        CarKit.enable(disconnect, (link.token != null || revoking) && !busy);
        retry.setVisibility(uncertain == null ? View.GONE : View.VISIBLE);
        CarKit.enable(retry, link.token != null && !busy);
        prepare.setVisibility(state == null ? View.GONE : View.VISIBLE);
        preparationHint.setVisibility(state == null ? View.GONE : View.VISIBLE);
        String contentKey = state == null ? revoking ? "revoking" : link.token != null ? "waiting" : "disconnected"
                : policy.epoch + ":" + detailsExpanded + ":" + state.toString();
        if (contentKey.equals(renderedContentKey)) { updateWriteButtons(); return; }
        renderedContentKey = contentKey;
        content.removeAllViews(); writes.clear();
        preparationSnapshot = null;
        if (state == null) {
            LinearLayout intro = section();
            k.overline(intro, "이 연결에서 오가는 것", 18);
            k.title(intro, revoking ? "서버 해제 확인이 남아 있어요" : link.token != null ? "웹에서 승인해 주세요" : "선호를 이어 쓰는 연결", 36)
                    .setLayoutParams(withTop(CarKit.lp(-2, -2), 16));
            k.body(intro, revoking ? "이 차량의 공유 선호 화면은 비웠어요. 왼쪽의 ‘서버 해제 다시 확인’으로 결과를 확인할 수 있어요."
                    : "쉼 선호와 차량 앱의 화면 분위기를 주고받아요. 운동 요약은 별도 연결이에요.")
                    .setLayoutParams(withTop(CarKit.lp(-1, -2), 16));
            k.caption(intro, cabinControl() ? "차 안 설정은 주차 중 쉼을 시작할 때 차에서 맞추고, 마치면 돌려요."
                    : "차량 공조·시트·조명·소리는 직접 조절해 주세요.").setLayoutParams(withTop(CarKit.lp(-1, -2), 20));
            return;
        }
        JSONArray presets = state.optJSONArray("presets");
        String selected = state.optString("selectedId"), defaultId = state.optString("defaultId");
        boolean automatic = state.optBoolean("autoDefaultOnRest");
        preparationSnapshot = findPreset(automatic ? defaultId : selected);
        JSONObject devices = state.optJSONObject("devices");
        JSONObject vehicle = devices == null ? null : devices.optJSONObject("vehicle");
        JSONObject proposal = vehicle == null ? null : vehicle.optJSONObject("proposal");

        if (proposal != null && proposal.has("cabin")) {
            // A rest sent from the phone with its 차 안 설정: prepared on 시작, started and applied only by a press there.
            LinearLayout arrived = section();
            arrived.setBackground(CarKit.round(t.surface, k.px(36), t.accent, Math.max(2, k.px(2))));
            k.live(arrived, "폰에서 보낸 쉼", 22, false);
            k.title(arrived, proposal.optString("name"), 40).setLayoutParams(withTop(CarKit.lp(-2, -2), 16));
            k.body(arrived, phoneRestSummary(proposal)).setLayoutParams(withTop(CarKit.lp(-1, -2), 12));
            k.caption(arrived, "시작 화면에서 확인하고 직접 시작해요. 운동 기록은 함께 오지 않아요.")
                    .setLayoutParams(withTop(CarKit.lp(-1, -2), 16));
            final JSONObject phoneRest = proposal;
            TextView take = k.primary(arrived, "이 쉼으로 준비하기", () -> {
                if (busy || revocation.pending()) return;
                setResult(RESULT_OK, new Intent().putExtra("presetSnapshot", phoneRest.toString())); finish();
            });
            take.setLayoutParams(withTop(CarKit.lp(-2, -2), 24));
            writes.add(take);
        }

        LinearLayout current = section();
        k.overline(current, "이번 쉼에 쓸 선호", 18);
        k.title(current, preparationSnapshot == null ? "선호를 확인해 주세요" : preparationSnapshot.optString("name"), 40)
                .setLayoutParams(withTop(CarKit.lp(-2, -2), 16));
        k.body(current, automatic ? "저장한 기본 선호로 준비해요. 아래에서 기본값을 바꿀 수 있어요." : "목록에서 고른 선호로 준비해요.")
                .setLayoutParams(withTop(CarKit.lp(-1, -2), 12));
        k.caption(current, cabinControl() ? "고른 쉼으로 시작하면 차 안을 맞춰요. 시작은 차 화면에서 직접 눌러요."
                : "공조·시트 설정은 미리보기예요. 차량에서 직접 조절해 주세요.").setLayoutParams(withTop(CarKit.lp(-1, -2), 16));
        preparationHint.setText("준비 화면에서 다시 확인하고 직접 시작해요. 지금 쉼이 시작되지는 않아요.");

        TextView listTitle = k.title(content, "내 선호", 30);
        listTitle.setLayoutParams(withTop(CarKit.lp(-2, -2), 40));
        LinearLayout list = section();
        list.setPadding(k.px(36), k.px(12), k.px(36), k.px(12));
        if (presets != null) for (int i = 0; i < presets.length(); i++) {
            JSONObject preset = presets.optJSONObject(i); if (preset == null) continue;
            final String id = preset.optString("id");
            if (i > 0) k.rule(list, t.line);
            LinearLayout row = k.row(list);
            row.setPadding(0, k.px(24), 0, k.px(24));
            LinearLayout words = k.column(row);
            words.setLayoutParams(CarKit.lp(0, -2, 1));
            k.text(words, preset.optString("name") + (id.equals(defaultId) ? " · 기본" : ""), 30, t.ink, CarKit.W600);
            k.caption(words, detail(preset)).setLayoutParams(withTop(CarKit.lp(-1, -2), 8));
            k.space(row, 24);
            boolean on = id.equals(selected);
            TextView pick = k.chip(row, on ? "선택됨" : "고르기", () -> { if (!id.equals(selected)) command("select_preset", object("id", id)); });
            k.chipState(pick, on);
            pick.setContentDescription(preset.optString("name") + (on ? ", 선택됨" : ", 고르기"));
            writes.add(pick);
        }

        TextView toggle = k.ghost(content, detailsExpanded ? "선호 관리 접기" : "선호 관리 · 기본값과 공유 제안", null,
                () -> { detailsExpanded = !detailsExpanded; render(); });
        CarKit.margins(toggle, -k.px(24), k.px(20), 0, 0);
        if (detailsExpanded) {
            LinearLayout management = section();
            k.overline(management, "기본 선호", 18);
            k.caption(management, "기본 자동선택은 쉼 준비에 쓸 선호만 정해요. 쉼을 자동 시작하지 않아요.")
                    .setLayoutParams(withTop(CarKit.lp(-1, -2), 12));
            writes.add(manageButton(management, "선택을 기본으로 · 자동선택 켜기", () -> command("set_default", object("id", selected, "autoDefaultOnRest", true))));
            writes.add(manageButton(management, "선택을 기본으로 · 자동선택 끄기", () -> command("set_default", object("id", selected, "autoDefaultOnRest", false))));
            writes.add(manageButton(management, "선택한 선호를 공유 제안으로 보내기", () -> command("propose_vehicle", object("id", selected))));
        }
        if (proposal != null && !proposal.has("cabin")) {
            LinearLayout suggested = section();
            k.overline(suggested, "공유된 제안", 18);
            k.title(suggested, proposal.optString("name"), 34).setLayoutParams(withTop(CarKit.lp(-2, -2), 12));
            k.caption(suggested, detail(proposal)).setLayoutParams(withTop(CarKit.lp(-1, -2), 8));
            k.caption(suggested, "확인은 시연용 공유 상태에만 기록해요. 실제 차량 설정은 바뀌지 않아요.")
                    .setLayoutParams(withTop(CarKit.lp(-1, -2), 12));
            writes.add(manageButton(suggested, "이 제안 확인 · 합성 상태만", () -> command("accept_vehicle", object("origin", "vehicle"))));
        }
        JSONObject plan = state.optJSONObject("plan");
        LinearLayout arrival = section();
        k.overline(arrival, "귀가 준비 · 시연", 18);
        k.text(arrival, plan == null ? "예약 없음" : plan.optString("presetName") + " · " + planStatus(plan.optString("status")),
                28, t.ink, CarKit.W600).setLayoutParams(withTop(CarKit.lp(-1, -2), 12));
        if (plan != null && !plan.optString("message").isEmpty())
            k.caption(arrival, plan.optString("message")).setLayoutParams(withTop(CarKit.lp(-1, -2), 8));
        k.caption(arrival, "합성 시계와 집 관측값을 보여줘요. 실제 집 기기 상태는 확인하지 않았어요.")
                .setLayoutParams(withTop(CarKit.lp(-1, -2), 12));
        if (detailsExpanded && plan != null) k.caption(arrival, "요청: " + plan.optString("requestedAt", "미요청")
                + "\n관측 확인: " + plan.optString("confirmedAt", "미확인")).setLayoutParams(withTop(CarKit.lp(-1, -2), 8));
        if (detailsExpanded && devices != null && devices.optJSONObject("home") != null) {
            JSONObject home = devices.optJSONObject("home");
            k.caption(arrival, "합성 집 관측 · " + values(home) + "\n관측 시각: " + home.optString("observedAt", "미확인"))
                    .setLayoutParams(withTop(CarKit.lp(-1, -2), 8));
        }
        updateWriteButtons();
    }
    /** A card in the right column, spaced from the one above. */
    private LinearLayout section() {
        LinearLayout v = k.card(content);
        v.setLayoutParams(withTop(CarKit.lp(-1, -2), content.getChildCount() == 1 ? 0 : 24));
        return v;
    }
    private TextView manageButton(LinearLayout parent, String label, Runnable action) {
        TextView b = k.secondary(parent, label, action);
        b.setLayoutParams(withTop(CarKit.lp(-1, -2), 16));
        return b;
    }
    private void updateWriteButtons() {
        boolean available = policy.canWrite() && !busy && !revocation.pending();
        for (View button : writes) CarKit.enable(button, available);
        CarKit.enable(prepare, available && preparationSnapshot != null);
    }
    private JSONObject findPreset(String id) {
        JSONArray presets = state.optJSONArray("presets");
        if (presets != null) for (int i = 0; i < presets.length(); i++) { JSONObject item = presets.optJSONObject(i); if (item != null && id.equals(item.optString("id"))) return item; }
        return null;
    }
    private String detail(JSONObject preset) {
        JSONObject car = preset.optJSONObject("vehicle");
        String seat = car != null && "manual_comfort".equals(car.optString("seat")) ? "시트는 직접" : "시트 그대로";
        return "차 " + values(car) + " · " + seat + "   ·   집 " + values(preset.optJSONObject("home"));
    }
    /** ‘24° · 바람 약하게 · 시트 통풍 2단계 · 조명 올리브 60%’: what the phone's rest will set when started here. */
    private static String phoneRestSummary(JSONObject proposal) {
        ArrayList<String> parts = new ArrayList<>();
        JSONObject car = proposal.optJSONObject("vehicle");
        if (car != null && !car.isNull("tempC")) parts.add(car.optString("tempC") + "°");
        if (car != null && !"keep".equals(car.optString("fan"))) parts.add("바람 " + fanLabel(car.optString("fan")));
        CabinSettings cabin = MainActivity.cabinOf(proposal.optJSONObject("cabin"));
        if (cabin != null) {
            if ("custom".equals(cabin.driverClimate.mode)) parts.add("에어컨 " + CabinSettings.climateText(cabin.driverClimate));
            String seat = CabinSettings.seatText(cabin.driverSeat);
            if (!"그대로".equals(seat)) parts.add("시트 " + seat);
            String light = CabinSettings.lightText(cabin.light);
            if (!"그대로".equals(light)) parts.add("조명 " + light);
        }
        return parts.isEmpty() ? "차 안은 그대로 둬요" : String.join(" · ", parts);
    }
    private String values(JSONObject value) {
        return value == null ? "미확인" : (value.isNull("tempC") ? "온도 유지" : value.optString("tempC") + "°C")
                + " · 바람 " + fanLabel(value.optString("fan"));
    }
    private static String fanLabel(String value) {
        switch (value) { case "low": return "약하게"; case "medium": return "보통"; case "high": return "강하게";
            case "auto": return "자동"; default: return "유지"; }
    }
    private static String planStatus(String value) {
        switch (value) { case "armed": return "예약됨"; case "requested": return "요청됨 · 관측 대기";
            case "confirmed_demo": return "합성 관측 확인"; case "failed": return "요청 실패";
            case "cancelled": return "취소됨"; case "expired": return "시간 만료";
            case "needs_review": return "다시 확인 필요"; default: return "상태 미확인"; }
    }
    /** Builds that set the cabin when a rest starts: the car-connected build, and the demo build on its pretend car. */
    private static boolean cabinControl() { return BuildConfig.LIVE_CONTROL || BuildConfig.SIMULATED_VEHICLE; }
}
