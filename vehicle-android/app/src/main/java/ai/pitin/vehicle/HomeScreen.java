package ai.pitin.vehicle;

import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** An entry to the existing rest and connection flows; it never starts a session. */
final class HomeScreen {
    private HomeScreen() { }

    static void build(MainActivity a, FrameLayout content, boolean wide) {
        a.homePanel = new ScrollView(a); a.homePanel.setFillViewport(true);
        content.addView(a.homePanel, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout layout = new LinearLayout(a);
        layout.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(0, a.views.dp(24), 0, a.views.dp(24));
        a.homePanel.addView(layout);
        LinearLayout hero = a.views.column(layout);
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 1.2f : 0);
        heroParams.setMargins(0, 0, a.views.dp(wide ? 48 : 0), a.views.dp(wide ? 0 : 20));
        hero.setLayoutParams(heroParams);
        a.views.text(hero, "GRAPHITE  ·  잠깐 쉬어가는 시간", 18).setTextColor(GraphitePalette.MUTED);
        TextView title = a.views.text(hero, "나를 위한\n조용한 3분", wide ? 60 : 38);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); title.setLetterSpacing(-.035f);
        a.views.gap(hero, 16);
        a.views.text(hero, "편안한 분위기를 고르고,\n준비가 되면 직접 시작하세요.", wide ? 24 : 20).setTextColor(GraphitePalette.MUTED);
        a.views.gap(hero, 24);
        a.views.text(hero, "3분   ·   소리 끔   ·   건강 연결은 선택", 18);
        LinearLayout details = a.views.column(layout);
        details.setLayoutParams(new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 1 : 0));
        LinearLayout preference = a.views.card(details);
        a.views.text(preference, "이번 쉼", 18).setTextColor(GraphitePalette.MUTED);
        a.homePreference = a.views.text(preference, "", 28);
        a.homeReadiness = a.views.text(preference, "", 18);
        a.views.quietAction(preference, "선호와 쉬는 방식 고르기  ›", a::openPreparation);
        LinearLayout connection = a.views.card(details);
        a.views.text(connection, "내 연결", 18).setTextColor(GraphitePalette.MUTED);
        a.homeConnection = a.views.text(connection, "", 21);
        a.views.text(connection, "운동 요약과 공유 프리셋은 각각 연결해요.\n연결 없이도 쉼을 준비할 수 있어요.", 17).setTextColor(GraphitePalette.MUTED);
        a.views.quietAction(connection, "연결 관리  ›", () -> a.showScreen(MainActivity.Screen.CONNECTIONS));
    }

    static String preferenceName(String id) {
        switch (id) {
            case "cool_down": return "산뜻하게 식히기";
            case "quiet_rest": return "조용히 쉬기";
            case "gentle_refresh": return "부드럽게 기분 전환";
            default: return "내 쉼을 골라 주세요";
        }
    }

    static void render(MainActivity a, SafetyGate.Decision gate) {
        a.homePreference.setText(preferenceName(a.selectedPreset));
        a.homeReadiness.setText(a.session.state() == RestSession.State.PAUSED ? "일시정지한 쉼이 있어요 · 직접 이어갈 수 있어요."
                : gate.previewAllowed ? "시작할 준비가 됐어요 · 차량 설정은 직접 조절" : "선호와 시작 조건을 확인해 주세요.");
        a.homeConnection.setText(a.revocation.pending() ? "운동 요약 · 서버 해제 확인 필요"
                : a.pairingId == null ? "운동 요약 · 미연결"
                : "paired".equals(a.pairingStatus) ? !a.isConnectionCurrent() ? "운동 요약 · 현재 연결 재확인 필요"
                : a.manualMode ? "서버 승인 확인 · 요약 없이 사용"
                : a.hasRemoteProposal() ? "운동 요약 · 이 화면에 수신됨" : "서버 승인 확인 · 공유 요약 대기"
                : "운동 요약 · 승인 대기");
    }
}
