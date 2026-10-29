package ai.pitin.vehicle;

import android.animation.ValueAnimator;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

/** Cohesive screen layout and state presentation for MainActivity. */
final class MainScreen {
    private MainScreen() { }

    static void build(MainActivity a) {
        int screenWidth = a.getResources().getConfiguration().screenWidthDp;
        boolean wide = screenWidth >= 700;
        int inset = wide ? Math.max(24, Math.min(48, screenWidth / 40)) : 16;
        int titleSize = wide ? Math.max(66, Math.min(100, Math.round(screenWidth * .057f)))
                : Math.max(28, Math.min(40, Math.round(screenWidth * .085f)));
        int supportSize = wide ? Math.max(22, Math.min(36, Math.round(screenWidth * .021f))) : 18;
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GraphiteBackground(a));
        root.setPadding(a.views.dp(inset), a.views.dp(wide ? 20 : 8), a.views.dp(inset), a.views.dp(wide ? 24 : 8));
        LinearLayout header = a.views.row(root);
        TextView brand = a.views.text(header, "차분", wide ? 36 : 28);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        a.endButton = a.views.button(header, "마치기", () -> {
            a.session.finish(SystemClock.elapsedRealtime()); a.cancelDeadline(); a.screen = MainActivity.Screen.SESSION; a.render();
        });
        a.views.secondaryButton(a.endButton);
        a.navigation = a.views.row(root);
        a.homeTab = a.views.secondary(a.navigation, "홈", () -> a.showScreen(MainActivity.Screen.HOME));
        a.preparationTab = a.views.secondary(a.navigation, "쉼 준비", a::openPreparation);
        a.connectionTab = a.views.secondary(a.navigation, "연결", () -> a.showScreen(MainActivity.Screen.CONNECTIONS));
        for (android.widget.Button tab : new android.widget.Button[]{a.homeTab, a.preparationTab, a.connectionTab}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(a.views.dp(wide ? 132 : 96), a.views.dp(56));
            p.setMargins(0, a.views.dp(4), a.views.dp(12), a.views.dp(8)); tab.setLayoutParams(p);
        }
        a.modeText = a.views.text(root, "", 16);
        a.modeText.setTextColor(GraphitePalette.MUTED);
        a.views.divider(root);
        FrameLayout content = new FrameLayout(a);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        HomeScreen.build(a, content, wide);
        buildRestPanel(a, content, wide, titleSize, supportSize);
        SetupScreen.build(a, content);
        a.views.divider(root);
        buildFooter(a, root, wide, screenWidth);
        a.getWindow().setStatusBarColor(GraphitePalette.BACKGROUND);
        a.getWindow().setNavigationBarColor(GraphitePalette.BACKGROUND);
        a.setContentView(root);
    }

    private static void buildRestPanel(MainActivity a, FrameLayout content, boolean wide, int titleSize, int supportSize) {
        a.restPanel = new LinearLayout(a);
        a.restPanel.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        a.restPanel.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(a.restPanel, new FrameLayout.LayoutParams(-1, -1));
        ScrollView copyScroll = new ScrollView(a);
        copyScroll.setFillViewport(true); copyScroll.setClipToPadding(false);
        LinearLayout copy = new LinearLayout(a);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(wide ? Gravity.CENTER_VERTICAL : Gravity.TOP);
        copy.setPadding(a.views.dp(wide ? 24 : 0), a.views.dp(wide ? 24 : 12), a.views.dp(wide ? 20 : 0), a.views.dp(16));
        copyScroll.addView(copy, new ScrollView.LayoutParams(-1, -2));
        a.restPanel.addView(copyScroll, wide ? new LinearLayout.LayoutParams(0, -1, .47f)
                : new LinearLayout.LayoutParams(-1, 0, .62f));
        a.guideLabel = a.views.text(copy, "안내에 맞춰 쉬기", wide ? 26 : 17);
        a.guideLabel.setTextColor(GraphitePalette.MUTED);
        a.guideLabel.setPadding(0, 0, 0, a.views.dp(wide ? 24 : 8));
        a.titleText = a.views.text(copy, "잠깐 쉬어갈까요?", titleSize);
        a.titleText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        a.titleText.setLetterSpacing(-.045f); a.titleText.setIncludeFontPadding(false);
        a.titleText.setLineSpacing(a.views.dp(6), 1f);
        a.titleText.setPadding(0, 0, 0, a.views.dp(wide ? 24 : 12));
        if (Build.VERSION.SDK_INT >= 29)
            a.titleText.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED);
        a.titleText.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        a.supportText = a.views.text(copy, "안내는 참고만 해도 좋아요.", supportSize);
        a.supportText.setTextColor(GraphitePalette.MUTED);
        a.supportText.setLineSpacing(a.views.dp(wide ? 8 : 4), 1f);
        a.supportText.setPadding(0, 0, 0, 0);
        View timerDash = new View(a);
        timerDash.setBackgroundColor(GraphitePalette.MUTED);
        LinearLayout.LayoutParams dashParams = new LinearLayout.LayoutParams(a.views.dp(wide ? 42 : 28), a.views.dp(1));
        dashParams.setMargins(0, a.views.dp(wide ? 36 : 16), 0, a.views.dp(wide ? 16 : 8));
        copy.addView(timerDash, dashParams);
        a.timerDash = timerDash;
        a.timerText = a.views.text(copy, "03:00", wide ? 62 : 36);
        a.timerText.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        a.timerText.setFontFeatureSettings("tnum"); a.timerText.setPadding(0, 0, 0, 0);
        a.timerText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        (a.timerCaption = a.views.text(copy, "남은 시간", wide ? 19 : 15)).setTextColor(GraphitePalette.MUTED);
        FinishLayer.build(a, copy, wide); // finish text layer only: question, feedback chips, note
        a.orb = new RestOrbView(a, a.session);
        a.restPanel.addView(a.orb, wide ? new LinearLayout.LayoutParams(0, -1, .53f)
                : new LinearLayout.LayoutParams(-1, 0, .38f));
    }

    private static void buildFooter(MainActivity a, LinearLayout root, boolean wide, int screenWidth) {
        LinearLayout footer = a.views.row(root);
        footer.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        footer.setPadding(a.views.dp(wide ? 16 : 0), a.views.dp(wide ? 24 : 8), a.views.dp(wide ? 16 : 0), 0);
        LinearLayout footerDetails = a.views.row(footer);
        footerDetails.setLayoutParams(wide ? new LinearLayout.LayoutParams(0, -2, 1)
                : new LinearLayout.LayoutParams(-1, -2));
        TextView themeText = a.views.text(footerDetails, "3분의 쉼  ·  소리 끔", wide ? 20 : 14);
        themeText.setTextColor(GraphitePalette.MUTED);
        themeText.setLetterSpacing(.06f);
        themeText.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        a.settingsButton = a.views.button(footerDetails, "연결·설정", () -> {
            if (a.screen == MainActivity.Screen.SESSION) a.openPreparation();
            else a.showScreen(MainActivity.Screen.HOME);
        });
        a.views.secondaryButton(a.settingsButton);
        LinearLayout actions = a.views.row(footer);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                wide ? a.views.dp(Math.max(220, Math.min(320, screenWidth / 5))) : -1, -2);
        actionParams.setMargins(a.views.dp(wide ? 24 : 0), 0, 0, 0);
        actions.setLayoutParams(actionParams);
        a.startButton = a.views.button(actions, "3분 쉬기", () -> {
            RestSession.State current = a.session.state();
            if (a.screen == MainActivity.Screen.SESSION && (current == RestSession.State.COMPLETE || current == RestSession.State.ENDED)) {
                a.showScreen(MainActivity.Screen.HOME); return;
            }
            if (a.screen != MainActivity.Screen.PREPARATION) { a.openPreparation(); return; }
            if (!a.decision().previewAllowed) { a.openPreparation(); return; }
            a.restFeedback = "";
            if (a.session.start(SystemClock.elapsedRealtime(), a.decision())) { a.screen = MainActivity.Screen.SESSION; a.scheduleDeadline(); }
            a.render();
        });
        a.pauseButton = a.views.button(actions, "일시정지", () -> {
            a.session.pause(SystemClock.elapsedRealtime()); a.cancelDeadline(); a.render();
        });
        a.resumeButton = a.views.button(actions, "계속하기", () -> {
            if (a.screen == MainActivity.Screen.HOME || a.screen == MainActivity.Screen.CONNECTIONS) { a.openPreparation(); return; }
            if (!a.decision().previewAllowed) { a.openPreparation(); return; }
            if (a.session.resume(SystemClock.elapsedRealtime(), a.decision())) { a.screen = MainActivity.Screen.SESSION; a.scheduleDeadline(); }
            a.render();
        });
        a.views.stylePrimaryActions(wide, a.startButton, a.pauseButton, a.resumeButton);
    }

    static void render(MainActivity a, SafetyGate.Decision gate) {
        if (a.modeText == null) return;
        a.session.setReducedMotion(a.appReducedMotion || !ValueAnimator.areAnimatorsEnabled());
        RestSession.State state = a.session.check(SystemClock.elapsedRealtime(), gate);
        boolean sessionScreen = a.screen == MainActivity.Screen.SESSION;
        boolean preparation = a.screen == MainActivity.Screen.PREPARATION;
        boolean complete = state == RestSession.State.COMPLETE || state == RestSession.State.ENDED;
        a.homePanel.setVisibility(a.screen == MainActivity.Screen.HOME ? View.VISIBLE : View.GONE);
        a.setupPanel.setVisibility(preparation || a.screen == MainActivity.Screen.CONNECTIONS ? View.VISIBLE : View.GONE);
        a.restPanel.setVisibility(sessionScreen ? View.VISIBLE : View.GONE);
        a.navigation.setVisibility(state == RestSession.State.ACTIVE ? View.GONE : View.VISIBLE);
        a.views.choice(a.homeTab, a.screen == MainActivity.Screen.HOME);
        a.views.choice(a.preparationTab, preparation);
        a.views.choice(a.connectionTab, a.screen == MainActivity.Screen.CONNECTIONS);
        a.settingsButton.setVisibility(state == RestSession.State.ACTIVE || a.screen == MainActivity.Screen.HOME ? View.GONE : View.VISIBLE);
        a.settingsButton.setText(sessionScreen ? complete ? "다시 3분 쉬기" : "준비 확인" : "홈으로");
        a.modeText.setText(BuildConfig.SIMULATED_VEHICLE ? "DEMO · 합성 차량 상태 · 실제 차량 제어 없음" : "PLEOS · 읽기 전용 · 실제 쉼 차단");
        if (a.guideLabel != null) a.guideLabel.setText(a.naturalRestMode ? "자연스럽게 쉬기" : "안내에 맞춰 쉬기");
        SetupScreen.render(a, gate);
        HomeScreen.render(a, gate);
        if (BuildConfig.SIMULATED_VEHICLE) {
            String status = state == RestSession.State.ACTIVE ? "쉬는 중" : state == RestSession.State.PAUSED
                    ? "일시정지" : state == RestSession.State.COMPLETE || state == RestSession.State.ENDED ? "마침" : "시작 전";
            a.modeText.setText("DEMO · " + status + " · 합성 차량 상태");
        }
        if (state != RestSession.State.ACTIVE) a.cancelDeadline();
        boolean preparationEntry = !preparation && !(sessionScreen && complete);
        a.startButton.setEnabled((gate.previewAllowed || preparationEntry || (sessionScreen && complete)) && state != RestSession.State.ACTIVE && state != RestSession.State.PAUSED);
        a.startButton.setVisibility(state == RestSession.State.ACTIVE || state == RestSession.State.PAUSED ? View.GONE : View.VISIBLE);
        a.startButton.setText(sessionScreen && complete ? "닫기" : preparationEntry ? "쉼 준비하기" : "3분 쉬기");
        a.pauseButton.setVisibility(state == RestSession.State.ACTIVE ? View.VISIBLE : View.GONE);
        a.resumeButton.setVisibility(state == RestSession.State.PAUSED ? View.VISIBLE : View.GONE);
        if (a.pauseButton != null) a.pauseButton.setEnabled(state == RestSession.State.ACTIVE);
        if (a.resumeButton != null) {
            boolean resumePreparation = !preparation && (!sessionScreen || !gate.previewAllowed);
            a.resumeButton.setText(resumePreparation ? "재개 준비하기" : "계속하기");
            a.resumeButton.setEnabled(state == RestSession.State.PAUSED && (gate.previewAllowed || resumePreparation));
        }
        if (a.endButton != null) a.endButton.setEnabled(state == RestSession.State.ACTIVE || state == RestSession.State.PAUSED);
        a.endButton.setVisibility(state == RestSession.State.ACTIVE || state == RestSession.State.PAUSED ? View.VISIBLE : View.GONE);
        if (a.feedbackPanel != null) {
            a.feedbackPanel.setVisibility(state == RestSession.State.COMPLETE || state == RestSession.State.ENDED ? View.VISIBLE : View.GONE);
        }
        if (a.orb != null) { a.orb.setNaturalRestMode(a.naturalRestMode); a.orb.invalidate(); }
        long seconds = a.session.secondsRemaining(SystemClock.elapsedRealtime());
        a.timerText.setText((seconds / 60) + ":" + String.format(Locale.ROOT, "%02d", seconds % 60));
        a.timerText.setContentDescription("남은 시간 " + seconds + "초");
        if (state == RestSession.State.ACTIVE) {
            boolean natural = a.naturalRestMode || a.session.segment(SystemClock.elapsedRealtime()) == RestSession.Segment.NATURAL_REST;
            a.titleText.setText(natural ? "자연스럽게 쉬세요" : a.session.inhaling(SystemClock.elapsedRealtime()) ? "들이쉬기" : "내쉬기");
            a.supportText.setText(a.naturalRestMode ? "호흡 박자 안내 없이 고요하게 머무는 시간이에요." : "안내는 참고만 해도 좋아요.\n무리하지 않아도 괜찮아요.");
        } else if (state == RestSession.State.COMPLETE) {
            a.titleText.setText("3분 쉬기를\n마쳤어요");
            a.supportText.setText("이번 쉼은 어땠나요?");
        } else if (state == RestSession.State.PAUSED) {
            a.titleText.setText("잠시 멈췄어요");
            a.supportText.setText(gate.previewAllowed ? "이어서 쉬어갈까요?\n계속하려면 직접 눌러 주세요." : RestGateMessages.message(gate.reason));
        } else if (state == RestSession.State.ENDED) {
            a.titleText.setText("쉬기를 마쳤어요");
            a.supportText.setText("이번 쉼은 어땠나요?");
        } else {
            a.titleText.setText("잠깐 쉬어갈까요?");
            String modeNote = a.naturalRestMode ? "3분 · 자연스럽게 쉬기 · 소리 끔\n박자 안내 없이 편안하게 쉬어가세요." : "3분 · 안내에 맞춰 쉬기 · 소리 끔\n안내는 참고만 해도 좋아요.";
            a.supportText.setText(gate.previewAllowed ? modeNote : RestGateMessages.message(gate.reason));
        }
        FinishLayer.render(a, state);
    }
}
