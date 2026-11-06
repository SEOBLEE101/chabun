package ai.pitin.vehicle;

import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Text/button layer of 마침 (car-finish, UI-05/S04) on top of the protected rest screen. The graphic,
 * timing and the exact finish titles stay in MainScreen; this only tidies words and buttons:
 * no 0:00 remaining time, no leading ‘차량 설정은 바뀌지 않았어요’, feedback chips, one primary.
 */
final class FinishLayer {
    private static final String[][] FEEDBACK = {{"좋았어요", "좋았어요"}, {"괜찮았어요", "괜찮았어요"}, {"바꾸고 싶어요", "바꾸고 싶어요"}};
    private FinishLayer() { }

    /** Called from MainScreen.buildRestPanel in place of the old four feedback buttons. */
    static void build(MainActivity a, LinearLayout copy, boolean wide) {
        CarKit k = new CarKit(a, CarTheme.GRAPHITE, a.getResources().getDisplayMetrics().density);
        a.finishPanel = k.column(copy);
        a.finishPanel.setVisibility(View.GONE);
        TextView question = k.text(a.finishPanel, "이번 쉼은 어땠나요?", wide ? 30 : 20, GraphitePalette.TEXT, CarKit.W400);
        CarKit.margins(question, 0, k.px(wide ? 4 : 2), 0, 0);
        a.feedbackPanel = k.row(a.finishPanel);
        CarKit.margins(a.feedbackPanel, 0, k.px(wide ? 20 : 12), 0, 0);
        for (String[] item : FEEDBACK) {
            TextView chip = k.chip(a.feedbackPanel, item[1], () -> {
                a.restFeedback = item[0].equals(a.restFeedback) ? "" : item[0];
                a.getSharedPreferences("pitin_vehicle_prefs", 0).edit().putString("last_feedback", a.restFeedback).apply();
                a.render();
            });
            chip.setTag(item[0]);
            if (!wide) { chip.setMinHeight(k.px(48)); chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, k.px(16)); chip.setPadding(k.px(16), 0, k.px(16), 0); }
            CarKit.margins(chip, 0, 0, k.px(wide ? 16 : 8), 0);
        }
        a.feedbackPanel.setVisibility(View.GONE);
        TextView note = k.text(a.finishPanel, "남기지 않아도 괜찮아요. 이 차에만 마지막 한 가지를 남겨요.", wide ? 22 : 15, 0xff96979a, CarKit.W400);
        CarKit.margins(note, 0, k.px(wide ? 20 : 10), 0, 0);
    }

    /** Wires header status and the finish footer once the legacy root exists. Active/paused stay pixel-identical. */
    static void install(MainActivity a, CarShell shell) {
        if (!(a.endButton.getParent() instanceof LinearLayout)) return;
        CarKit k = new CarKit(a, CarTheme.GRAPHITE, a.getResources().getDisplayMetrics().density);
        LinearLayout header = (LinearLayout) a.endButton.getParent();
        View brand = header.getChildAt(0);
        boolean wide = a.getResources().getConfiguration().screenWidthDp >= 700;
        if (a.finishStatus == null || a.finishStatus.getParent() != header) {
            brand.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
            LinearLayout status = k.row(null);
            k.sep(status, wide ? 40 : 24);
            k.live(status, BuildConfig.SIMULATED_VEHICLE ? "주차 중 · 데모" : "주차 중", wide ? 24 : 16, false);
            header.addView(status, 1);
            View flex = new View(a);
            header.addView(flex, 2, new LinearLayout.LayoutParams(0, 1, 1));
            status.setVisibility(View.GONE);
            a.finishStatus = status;
        }
        if (a.settingsButton.getParent() instanceof LinearLayout) {
            ViewGroup details = (ViewGroup) a.settingsButton.getParent();
            if (details.getChildAt(0) instanceof TextView) a.footerThemeText = (TextView) details.getChildAt(0);
        }
        a.settingsTextSizePx = a.settingsButton.getTextSize();
        a.settingsMinHeight = a.settingsButton.getMinHeight();
        a.settingsButton.setOnClickListener(v -> {
            RestSession.State s = a.session.state();
            if (a.screen == MainActivity.Screen.SESSION && (s == RestSession.State.COMPLETE || s == RestSession.State.ENDED)) a.startRest();
            else if (a.screen == MainActivity.Screen.SESSION) a.openPreparation();
            else a.showScreen(MainActivity.Screen.HOME);
        });
    }

    /** Called at the end of MainScreen.render. */
    static void render(MainActivity a, RestSession.State state) {
        if (a.finishPanel == null) return;
        boolean finished = a.screen == MainActivity.Screen.SESSION
                && (state == RestSession.State.COMPLETE || state == RestSession.State.ENDED);
        a.finishPanel.setVisibility(finished ? View.VISIBLE : View.GONE);
        a.supportText.setVisibility(finished ? View.GONE : View.VISIBLE);
        a.timerText.setVisibility(finished ? View.GONE : View.VISIBLE);
        if (a.timerCaption != null) a.timerCaption.setVisibility(finished ? View.GONE : View.VISIBLE);
        if (finished) {
            a.navigation.setVisibility(View.GONE);
            a.modeText.setVisibility(View.GONE);
            a.guideLabel.setText(CarCopy.restName(a.selectedPreset) + " · " + CarCopy.mode(a.naturalRestMode));
        } else {
            a.modeText.setVisibility(View.VISIBLE);
        }
        if (a.finishStatus != null) a.finishStatus.setVisibility(finished ? View.VISIBLE : View.GONE);
        if (a.footerThemeText != null) a.footerThemeText.setText(finished
                ? (a.cabinFinishNote != null ? a.cabinFinishNote : "공조와 시트는 그대로예요.")
                : "3분의 쉼  ·  소리 끔" + (a.cabinRestNote.isEmpty() ? "" : "  ·  " + a.cabinRestNote));
        if (BuildConfig.LIVE_CONTROL && a.modeText != null) a.modeText.setText("PLEOS · 차 연결 · 주차 중에만 차 안을 맞춰요");
        else if (BuildConfig.LIVE_REST && a.modeText != null) a.modeText.setText("PLEOS · 읽기 전용 · 주차 중에만 쉬어요");
        if (a.settingsTextSizePx > 0) {
            a.settingsButton.setTextSize(TypedValue.COMPLEX_UNIT_PX, finished ? a.startButton.getTextSize() : a.settingsTextSizePx);
            a.settingsButton.setMinHeight(finished ? a.startButton.getMinHeight() : a.settingsMinHeight);
            ((android.graphics.drawable.GradientDrawable) a.settingsButton.getBackground()).setStroke(finished ? Math.max(1, Math.round(a.getResources().getDisplayMetrics().density)) : 0, 0xff383b3e);
        }
        if (a.feedbackPanel != null && finished) {
            CarKit k = new CarKit(a, CarTheme.GRAPHITE, a.getResources().getDisplayMetrics().density);
            for (int i = 0; i < a.feedbackPanel.getChildCount(); i++) {
                TextView chip = (TextView) a.feedbackPanel.getChildAt(i);
                k.chipState(chip, String.valueOf(chip.getTag()).equals(a.restFeedback));
            }
        }
    }
}
