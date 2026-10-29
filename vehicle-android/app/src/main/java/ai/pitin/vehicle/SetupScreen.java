package ai.pitin.vehicle;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Preparation and optional connections share the navigation shell, not their actions. */
final class SetupScreen {
    private SetupScreen() { }

    static void build(MainActivity a, FrameLayout content) {
        a.setupPanel = new ScrollView(a);
        content.addView(a.setupPanel, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout column = new LinearLayout(a); column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, a.views.dp(16), 0, a.views.dp(18)); a.setupPanel.addView(column);
        a.preparationPanel = a.views.column(column); a.connectionPanel = a.views.column(column);
        buildPreparation(a, a.preparationPanel); buildConnections(a, a.connectionPanel);
    }

    private static LinearLayout columns(MainActivity a, LinearLayout parent) {
        LinearLayout row = a.views.row(parent);
        row.setOrientation(a.getResources().getConfiguration().screenWidthDp >= 900
                ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        row.setGravity(android.view.Gravity.TOP); return row;
    }
    private static LinearLayout panel(MainActivity a, LinearLayout parent) {
        LinearLayout card = a.views.card(parent);
        boolean wide = parent.getOrientation() == LinearLayout.HORIZONTAL;
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 1 : 0);
        p.setMargins(0, a.views.dp(8), a.views.dp(wide ? 16 : 0), a.views.dp(12)); card.setLayoutParams(p);
        return card;
    }
    private static Button option(MainActivity a, LinearLayout parent, String title, String tag, Runnable action) {
        Button button = a.views.quietAction(parent, title, action);
        button.setTag(tag); button.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = a.views.dp(10);
        button.setLayoutParams(p); button.setMinHeight(a.views.dp(68)); return button;
    }

    private static void buildPreparation(MainActivity a, LinearLayout column) {
        a.views.text(column, "오늘은 어떻게 쉬고 싶나요?", 30);
        a.views.text(column, "분위기를 고르고, 준비가 되면 아래에서 직접 시작하세요.", 19).setTextColor(GraphitePalette.MUTED);
        LinearLayout row = columns(a, column);
        LinearLayout choices = panel(a, row);
        a.views.text(choices, "01   내 쉼 고르기", 23);
        preset(a, choices, "산뜻하게 식히기\n더운 몸을 편안하게 · 공조는 직접 조절", "cool_down");
        preset(a, choices, "조용히 쉬기\n자극을 줄이고 잠깐 머무는 시간", "quiet_rest");
        preset(a, choices, "부드럽게 기분 전환\n편안한 분위기로 다시 시작할 준비", "gentle_refresh");
        a.views.gap(choices, 14); a.views.text(choices, "쉬는 방식", 20);
        option(a, choices, "안내에 맞춰 쉬기 · 호흡 박자", "mode:guided", () -> { a.naturalRestMode = false; a.render(); });
        option(a, choices, "자연스럽게 쉬기 · 박자 안내 없음", "mode:natural", () -> { a.naturalRestMode = true; a.render(); });
        a.views.checkBox(choices, "동작 줄이기 · 점을 정지해 표시", 56, a.appReducedMotion,
                (view, checked) -> { a.appReducedMotion = checked; a.render(); });

        LinearLayout ready = panel(a, row);
        a.views.text(ready, "02   시작 전 확인", 23);
        a.presetText = a.views.text(ready, "", 24);
        a.views.text(ready, "3분 · 소리 끔\n차량 공조·시트·소리는 직접 조절해 주세요.", 18).setTextColor(GraphitePalette.MUTED);
        a.views.gap(ready, 12);
        a.consentBox = a.views.checkBox(ready, "선택한 선호를 이 차량 화면에서 확인했어요", 72,
                a.localConfirmation, (view, checked) -> {
                    a.localConfirmation = checked;
                    if (!checked) a.session.pause(SystemClock.elapsedRealtime());
                    a.render();
                });
        a.consentBox.setTextSize(20);
        a.views.divider(ready); a.vehicleText = a.views.text(ready, "", 18);
        a.gateText = a.views.text(ready, "", 21);
        if (BuildConfig.SIMULATED_VEHICLE) {
            a.views.gap(ready, 16);
            a.views.text(ready, "DEMO 체험 준비", 16).setTextColor(GraphitePalette.MUTED);
            a.views.quietAction(ready, "DEMO · 주차 상태로 체험하기", () -> { a.environment.simulate("parked"); a.render(); });
            a.views.text(ready, "합성 주차 신호를 선택합니다.\n실제 차량의 연결·주차 확인이 아니에요.", 16).setTextColor(GraphitePalette.MUTED);
        } else a.views.quietAction(ready, "차량 상태 읽기 권한 확인", () -> a.requestPermissions(a.environment.requiredPermissions(), 41));
    }

    private static void preset(MainActivity a, LinearLayout parent, String label, String id) {
        option(a, parent, label, "preset:" + id, () -> {
            a.selectedPreset = id; a.connectedPresetDescription = "";
            a.localConfirmation = false; a.consentBox.setChecked(false);
            a.session.pause(SystemClock.elapsedRealtime()); a.render();
        });
    }

    private static void buildConnections(MainActivity a, LinearLayout column) {
        a.views.text(column, "필요한 것만 연결하세요", 30);
        a.views.text(column, "연결은 선택사항이에요. 받은 정보만 표시하고 쉼은 직접 시작해요.", 19).setTextColor(GraphitePalette.MUTED);
        LinearLayout row = columns(a, column);
        LinearLayout health = panel(a, row);
        a.views.text(health, "운동 요약", 26);
        a.views.text(health, "휴대폰에서 고른 최소 요약과 선호를 받아요.", 18).setTextColor(GraphitePalette.MUTED);
        a.pairingText = a.views.text(health, "", 21);
        a.pairButton = a.views.quietAction(health, "휴대폰 연결 번호 만들기", a::createPairing);
        a.confirmPairButton = a.views.quietAction(health, "두 화면의 확인 번호 일치 · 승인", a::confirmPairing);
        a.retryDeleteButton = a.views.quietAction(health, "서버 해제 다시 확인", a::retryRevocation);
        a.summaryText = a.views.text(health, "", 19);
        a.privacyButton = a.views.quietAction(health, "요약 보기", () -> { a.privacyMasked = !a.privacyMasked; a.render(); });
        a.views.quietAction(health, "요약 없이 쉼 준비", () -> {
            a.clearProposal(); a.manualMode = true; a.proposal = RecoveryProposal.manual(System.currentTimeMillis()); a.openPreparation();
        });
        a.views.quietAction(health, "공유 요약 다시 받기", () -> { a.clearProposal(); a.manualMode = false; a.lastPollMs = 0; a.render(); });
        a.disconnectButton = a.views.quietAction(health, "연결 해제 및 공유 요약 삭제", () ->
                new AlertDialog.Builder(a).setTitle("이 연결을 해제할까요?")
                        .setMessage("차량에 표시한 요약을 지우고 서버에 연결·공유 요약 삭제를 요청합니다. 진행 중인 쉼을 자동으로 끝내지는 않아요.")
                        .setNegativeButton("취소", null).setPositiveButton("해제 요청", (dialog, which) -> a.disconnect()).show());

        LinearLayout shared = panel(a, row);
        a.views.text(shared, "공유 프리셋", 26);
        a.views.text(shared, "웹·폰에서 고른 선호를 함께 사용해요.\n운동 요약과는 별도로 연결합니다.", 19).setTextColor(GraphitePalette.MUTED);
        a.views.quietAction(shared, "공유 프리셋 관리  ›", () -> {
            Intent intent = new Intent(a, ConnectedPresetsActivity.class);
            intent.putExtra("relayOrigin", a.originInput.getText().toString()); a.startActivityForResult(intent, 52);
        });
        a.views.gap(shared, 20); a.views.divider(shared);
        a.views.text(shared, "연결 상태 읽는 법", 20);
        a.views.text(shared, "승인됨: 서버가 연결을 승인했어요.\n수신됨: 이 차량 화면에 요약이 도착했어요.\n실제 차량·집 기기 제어를 뜻하지 않아요.", 18).setTextColor(GraphitePalette.MUTED);
        LinearLayout advanced = a.views.column(column); advanced.setVisibility(View.GONE);
        a.originInput = a.views.uriInput(advanced, "HTTPS 중계 서버 주소", BuildConfig.DEBUG ? a.getString(R.string.relay_default_origin) : "");
        a.views.quietAction(column, "고급 연결 설정", () -> advanced.setVisibility(advanced.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        if (BuildConfig.SIMULATED_VEHICLE) {
            LinearLayout demo = a.views.column(column); demo.setVisibility(View.GONE);
            a.views.text(demo, "합성 신호 체험 · 실제 차량 제어 없음", 18);
            LinearLayout controls = columns(a, demo);
            for (String[] item : new String[][]{{"주차", "parked"}, {"주행", "driving"}, {"통화", "call"}, {"미확인", "unknown"}, {"화면 제한", "restricted"}}) {
                Button signal = a.views.quietAction(controls, item[0], () -> { a.environment.simulate(item[1]); a.render(); });
                if (controls.getOrientation() == LinearLayout.HORIZONTAL) {
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
                    p.setMargins(0, a.views.dp(5), a.views.dp(8), a.views.dp(5)); signal.setLayoutParams(p);
                }
            }
            a.views.quietAction(column, "DEMO 시연 도구", () -> demo.setVisibility(demo.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        }
    }

    static void render(MainActivity a, SafetyGate.Decision gate) {
        boolean connections = a.screen == MainActivity.Screen.CONNECTIONS;
        a.preparationPanel.setVisibility(connections ? View.GONE : View.VISIBLE);
        a.connectionPanel.setVisibility(connections ? View.VISIBLE : View.GONE);
        a.pairingText.setText(a.connectionMessage);
        a.pairButton.setVisibility(a.pairingId == null && !a.revocation.pending() ? View.VISIBLE : View.GONE);
        a.pairButton.setEnabled(!a.busy); a.originInput.setEnabled(!a.busy && a.pairingId == null && !a.revocation.pending());
        a.retryDeleteButton.setVisibility(a.revocation.pending() ? View.VISIBLE : View.GONE);
        a.retryDeleteButton.setEnabled(!a.busy);
        a.confirmPairButton.setVisibility("claimed".equals(a.pairingStatus) && a.confirmation != null ? View.VISIBLE : View.GONE);
        a.confirmPairButton.setEnabled(!a.busy);
        a.disconnectButton.setVisibility(a.pairingId != null ? View.VISIBLE : View.GONE);
        a.disconnectButton.setEnabled(!a.busy);
        boolean received = a.hasRemoteProposal();
        a.privacyButton.setVisibility(received ? View.VISIBLE : View.GONE);
        a.privacyButton.setText(a.privacyMasked ? "요약 보기" : "요약 숨기기");
        if (!received) a.summaryText.setText("수신한 운동 요약 없음 · 건강 기록 없이도 쉴 수 있어요.");
        else a.summaryText.setText((a.privacyMasked ? "운동 요약이 도착했어요 · 동승자 보호로 숨김" : a.proposal.description)
                + (a.proposal.comfort.isEmpty() ? "" : "\n공유한 선호: " + HomeScreen.preferenceName(a.proposal.comfort)));
        a.presetText.setText(HomeScreen.preferenceName(a.selectedPreset) + "\n" + (a.naturalRestMode ? "자연스럽게 쉬기" : "안내에 맞춰 쉬기"));
        if (!a.connectedPresetDescription.isEmpty()) a.presetText.append("\n" + a.connectedPresetDescription);
        for (String id : new String[]{"cool_down", "quiet_rest", "gentle_refresh"})
            a.views.quietChoice((Button) a.preparationPanel.findViewWithTag("preset:" + id), id.equals(a.selectedPreset));
        a.views.quietChoice((Button) a.preparationPanel.findViewWithTag("mode:guided"), !a.naturalRestMode);
        a.views.quietChoice((Button) a.preparationPanel.findViewWithTag("mode:natural"), a.naturalRestMode);
        SafetyGate.Snapshot state = a.environment.snapshot(SystemClock.elapsedRealtime());
        String stateLabel = state == null || state.gear == SafetyGate.Gear.UNKNOWN ? "미확인"
                : Boolean.TRUE.equals(state.callActive) ? "통화 중"
                : Boolean.TRUE.equals(state.uxRestricted) ? "화면 제한"
                : state.gear == SafetyGate.Gear.PARK ? "주차 선택됨" : "주행 상태";
        a.vehicleText.setText(BuildConfig.SIMULATED_VEHICLE ? "합성 차량 상태 · " + stateLabel : "차량 상태 읽기 전용");
        a.gateText.setText(gate.previewAllowed ? a.session.state() == RestSession.State.PAUSED
                ? "준비됐어요. ‘계속하기’를 직접 눌러 주세요." : "준비됐어요. ‘3분 쉬기’를 직접 눌러 주세요." : RestGateMessages.message(gate.reason));
    }
}
