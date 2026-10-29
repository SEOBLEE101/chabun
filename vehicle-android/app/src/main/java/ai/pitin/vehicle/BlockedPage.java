package ai.pitin.vehicle;

import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 지금은 시작할 수 없어요 (car-blocked, spec S01). Every parking/stop/call/screen item gets its own reason;
 * a dimmed button never carries the reason alone. ‘다시 확인’ only reads state and never starts a rest.
 */
final class BlockedPage extends CarPage {
    private static final int ROWS = 6;
    private LinearLayout header, left, list, footer;
    private TextView status, eyebrow, display, reason, start;
    private final List<LinearLayout> rows = new ArrayList<>();
    private final List<FrameLayout> dots = new ArrayList<>();
    private final List<ImageView> marks = new ArrayList<>();
    private final List<TextView> titles = new ArrayList<>(), subs = new ArrayList<>();
    private long recheckedAt;

    BlockedPage(CarShell shell) { super(shell); }

    @Override View build() {
        FrameLayout root = new FrameLayout(a);
        LinearLayout col = frame(root);
        header = k.header(col);
        TextView word = k.wordmark(header, 34);
        word.setOnLongClickListener(v -> { shell.openDevSheet(); return true; });
        k.sep(header, 40);
        status = k.live(header, "상태 확인 중", 24, true);
        k.flex(header);
        k.ghost(header, "시작 화면", CarIcon.BACK, () -> a.showScreen(MainActivity.Screen.HOME));

        LinearLayout main = k.row(col);
        main.setLayoutParams(CarKit.lp(-1, 0, 1));
        left = k.column(main);
        left.setLayoutParams(CarKit.lp(0, -2, 47));
        eyebrow = k.text(left, "", 26, t.ink2, CarKit.W500);
        display = k.display(left, "지금은\n시작할 수 없어요", 92);
        CarKit.margins(display, 0, k.px(30), 0, 0);
        reason = k.body(left, "");
        CarKit.margins(reason, 0, k.px(30), 0, 0);
        k.space(main, 40);
        LinearLayout card = k.card(main);
        card.setPadding(k.px(40), k.px(12), k.px(40), k.px(12));
        card.setLayoutParams(CarKit.lp(0, -2, 53));
        list = card;
        for (int i = 0; i < ROWS; i++) {
            LinearLayout row = k.row(card);
            row.setMinimumHeight(k.px(108));
            row.setLayoutParams(CarKit.lp(-1, -2));
            row.setPadding(0, k.px(12), 0, k.px(12));
            FrameLayout dot = new FrameLayout(a);
            ImageView mark = new ImageView(a);
            dot.addView(mark, new FrameLayout.LayoutParams(k.px(24), k.px(24), Gravity.CENTER));
            row.addView(dot, CarKit.lp(k.px(44), k.px(44)));
            LinearLayout texts = k.column(row);
            LinearLayout.LayoutParams tp = CarKit.lp(0, -2, 1);
            tp.leftMargin = k.px(24);
            texts.setLayoutParams(tp);
            TextView title = k.text(texts, "", 28, t.ink, CarKit.W600);
            title.setLetterSpacing(-0.02f);
            TextView sub = k.text(texts, "", 22, t.ink2, CarKit.W400);
            CarKit.margins(sub, 0, k.px(4), 0, 0);
            rows.add(row); dots.add(dot); marks.add(mark); titles.add(title); subs.add(sub);
            if (i > 0) {
                View rule = new View(a);
                rule.setBackgroundColor(t.line);
                card.addView(rule, card.indexOfChild(row), CarKit.lp(-1, Math.max(1, k.px(1))));
                row.setTag(rule);
            }
        }

        footer = k.footer(col);
        k.caption(footer, "‘다시 확인’은 상태만 읽어요. 저절로 시작하지 않아요.");
        k.flex(footer);
        k.secondary(footer, "다시 확인", this::recheck);
        k.space(footer, 20);
        start = k.primary(footer, "3분 쉬기", a::startRest);
        start.setMinWidth(k.px(340));
        return root;
    }

    private void recheck() {
        a.environment.refresh();
        recheckedAt = SystemClock.elapsedRealtime();
        a.render();
    }

    private void item(int i, String title, String sub, int state) {
        LinearLayout row = rows.get(i);
        row.setVisibility(title == null ? View.GONE : View.VISIBLE);
        Object rule = row.getTag();
        if (rule instanceof View) ((View) rule).setVisibility(title == null ? View.GONE : View.VISIBLE);
        if (title == null) return;
        titles.get(i).setText(title);
        subs.get(i).setText(sub);
        FrameLayout dot = dots.get(i);
        ImageView mark = marks.get(i);
        if (state == 1) {
            dot.setBackground(CarKit.round(t.ink, k.px(22), t.ink, Math.max(1, k.px(1.5f))));
            mark.setImageDrawable(new CarIcon(CarIcon.CHECK, t.bg, 2.2f));
        } else {
            dot.setBackground(CarKit.round(0, k.px(22), t.warn, Math.max(1, k.px(1.5f))));
            mark.setImageDrawable(new CarIcon(CarIcon.ALERT, t.warn, 2f));
        }
        row.setContentDescription(title + ", " + sub + (state == 1 ? ", 확인됨" : ", 확인 필요"));
    }

    @Override void update() {
        long now = SystemClock.elapsedRealtime();
        SafetyGate.Snapshot s = a.environment.snapshot(now);
        // ‘3분 쉬기’ itself is the confirmation on this screen, so readiness assumes it (SafetyGate unchanged).
        SafetyGate.Decision ready = SafetyGate.preview(a.restSnapshot(now), now, !a.selectedPreset.isEmpty(), true);
        boolean fresh = s != null && now - s.sampledAtMs >= 0 && now - s.sampledAtMs < SafetyGate.MAX_STATE_AGE_MS;
        String when = recheckedAt > 0 && now - recheckedAt < 60_000 ? "방금 확인" : "확인함";
        int i = 0;
        if (s == null || !fresh) {
            item(i++, "차 상태", "읽은 값이 오래됐어요 · 다시 확인해 주세요", 0);
        }
        if (s != null) {
            String gear = s.gear == SafetyGate.Gear.PARK ? "P" : s.gear == SafetyGate.Gear.DRIVE ? "D"
                    : s.gear == SafetyGate.Gear.REVERSE ? "R" : s.gear == SafetyGate.Gear.NEUTRAL ? "N" : null;
            item(i++, "주차", gear == null ? "확인할 수 없어요" : "P".equals(gear) ? "P · " + when : gear + " · 기어를 P로 두면 시작할 수 있어요",
                    "P".equals(gear) ? 1 : 0);
            boolean stopped = s.speedKph != null && Double.isFinite(s.speedKph) && s.speedKph == 0.0;
            item(i++, "정차", s.speedKph == null ? "확인할 수 없어요" : stopped ? "0 km/h · " + when
                    : String.format(Locale.ROOT, "%.0f km/h · 멈춘 뒤에 쉴 수 있어요", s.speedKph), stopped ? 1 : 0);
            item(i++, "통화", s.callActive == null ? (s.simulation ? "확인할 수 없어요" : "이 차에서 아직 읽을 수 없어요") : s.callActive ? "통화 중이에요" : "통화 없음",
                    Boolean.FALSE.equals(s.callActive) ? 1 : 0);
            item(i++, "화면 사용", s.uxRestricted == null ? "확인할 수 없어요" : s.uxRestricted ? "지금은 화면 사용이 제한돼요" : "제한 없음",
                    Boolean.FALSE.equals(s.uxRestricted) ? 1 : 0);
            if (!s.permissionVerified) item(i++, "차 상태 읽기", "읽기 권한을 아직 확인하지 못했어요", 0);
            else if (!s.simulation && !BuildConfig.LIVE_CONTROL) item(i++, "이 앱", "읽기 전용이라 실제 차에서는 아직 시작하지 않아요", 0);
        }
        while (i < ROWS) item(i++, null, null, 0);

        boolean paused = a.session.state() == RestSession.State.PAUSED;
        eyebrow.setText(CarCopy.restName(a.selectedPreset) + " · 3분");
        status.setText(ready.previewAllowed ? "확인됐어요" : "상태 확인 중");
        k.liveState(status, !ready.previewAllowed);
        display.setText(ready.previewAllowed ? (paused ? "이제\n이어갈 수 있어요" : "이제\n시작할 수 있어요")
                : (paused ? "지금은\n이어갈 수 없어요" : "지금은\n시작할 수 없어요"));
        String first = firstReason(s, fresh);
        reason.setText(ready.previewAllowed ? "준비됐어요. ‘" + (paused ? "계속하기" : "3분 쉬기") + "’를 직접 눌러 주세요."
                : first + "\n확인되면 ‘" + (paused ? "계속하기" : "3분 쉬기") + "’를 직접 눌러 주세요.");
        start.setText(paused ? "계속하기" : "3분 쉬기");
        CarKit.enable(start, ready.previewAllowed);
    }

    private String firstReason(SafetyGate.Snapshot s, boolean fresh) {
        if (s == null || !fresh) return "차 상태를 다시 읽어야 해요.";
        if (s.gear != SafetyGate.Gear.PARK) return s.gear == SafetyGate.Gear.UNKNOWN ? "주차 상태를 확인할 수 없어요." : "기어가 P가 아니에요.";
        if (s.speedKph == null || s.speedKph != 0.0) return s.speedKph == null ? "정차 상태를 확인할 수 없어요." : "차가 움직이고 있어요.";
        if (s.callActive == null) return "통화 상태를 확인할 수 없어요.";
        if (s.callActive) return "통화 중이에요.";
        if (s.uxRestricted == null) return "화면 사용 조건을 확인할 수 없어요.";
        if (s.uxRestricted) return "지금은 화면 사용이 제한돼요.";
        if (!s.permissionVerified) return "차 상태 읽기 권한을 확인해 주세요.";
        if (!s.simulation) return "이 앱은 차 상태를 읽기만 해요.";
        return "선택한 쉼을 확인해 주세요.";
    }

    @Override List<View> cascadeTargets() { return list(bar(header), eyebrow, display, reason, list, bar(footer)); }
}
