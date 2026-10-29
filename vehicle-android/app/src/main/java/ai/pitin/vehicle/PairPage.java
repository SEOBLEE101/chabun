package ai.pitin.vehicle;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * 폰 연결 (car-pair): big 6-digit number → big 4-digit confirmation → approve. A remaining-time bar
 * replaces the ISO expiry. Pairing still runs through the existing relay calls in MainActivity.
 */
final class PairPage extends CarPage {
    private static final long CODE_LIFETIME_MS = 5 * 60_000L;
    private LinearLayout header, left, card, footer;
    private TextView step, display, body, overline, bigNumber, note, footNote, primary, secondary, ghost;
    private View bar, barFill;
    private Runnable primaryAction = () -> { }, secondaryAction = () -> { }, ghostAction = () -> { };
    private TextView live;

    PairPage(CarShell shell) { super(shell); }

    @Override View build() {
        FrameLayout root = new FrameLayout(a);
        LinearLayout col = frame(root);
        header = k.header(col);
        TextView back = k.backButton(header, () -> a.showScreen(MainActivity.Screen.HOME));
        // The title tucks over the back button's empty right edge (not touchable), where the artboard puts it.
        CarKit.margins(k.title(header, "폰 연결", 40), -k.px(24), 0, 0, 0);
        k.flex(header);
        k.caption(header, "연결하지 않아도 쉴 수 있어요");
        k.space(header, 24);
        // 공유 프리셋(취향 함께 쓰기) 입구 — 이전 SetupScreen과 같은 요청 코드·서버 주소로 연다
        k.ghost(header, "함께 쓰는 쉼 목록  ›", null, () -> {
            Intent intent = new Intent(a, ConnectedPresetsActivity.class);
            String origin = a.originInput != null ? a.originInput.getText().toString()
                    : (BuildConfig.DEBUG ? a.getString(R.string.relay_default_origin) : "");
            intent.putExtra("relayOrigin", origin);
            a.startActivityForResult(intent, 52);
        });

        LinearLayout main = k.row(col);
        main.setLayoutParams(CarKit.lp(-1, 0, 1));
        left = k.column(main);
        left.setLayoutParams(CarKit.lp(0, -2, 1));
        step = k.text(left, "1 / 2", 26, t.ink2, CarKit.W500);
        display = k.display(left, "", 76);
        CarKit.margins(display, 0, k.px(28), 0, 0);
        body = k.body(left, "");
        CarKit.margins(body, 0, k.px(28), 0, 0);
        k.space(main, 72);
        card = k.card(main);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(k.px(40), k.px(64), k.px(40), k.px(52));
        card.setLayoutParams(CarKit.lp(0, -2, 1));
        overline = k.overline(card, "연결 번호", 18);
        bigNumber = k.num(card, "", 132, t.ink);
        bigNumber.setGravity(Gravity.CENTER);
        CarKit.margins(bigNumber, 0, k.px(26), 0, 0);
        FrameLayout track = new FrameLayout(a);
        track.setBackground(CarKit.round(t.line2, k.px(2), 0, 0));
        track.setClipToOutline(true);
        barFill = new View(a);
        barFill.setBackground(CarKit.round(t.ink, k.px(2), 0, 0));
        track.addView(barFill, new FrameLayout.LayoutParams(k.px(360), -1));
        LinearLayout.LayoutParams bp = CarKit.lp(k.px(360), k.px(4));
        bp.topMargin = k.px(26);
        card.addView(track, bp);
        bar = track;
        live = k.live(card, "", 22, true);
        CarKit.margins(live, 0, k.px(26), 0, 0);
        note = k.caption(card, "");
        note.setGravity(Gravity.CENTER);
        CarKit.margins(note, 0, k.px(26), 0, 0);
        ghost = k.ghost(card, "", null, () -> ghostAction.run());
        CarKit.margins(ghost, 0, k.px(10), 0, 0);

        footer = k.footer(col);
        footNote = k.caption(footer, "");
        k.flex(footer);
        secondary = k.secondary(footer, "", () -> secondaryAction.run());
        k.space(footer, 20);
        primary = k.primary(footer, "", () -> primaryAction.run());
        primary.setMinWidth(k.px(340));
        return root;
    }

    private static String spaced(String code) {
        return code != null && code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : String.valueOf(code);
    }

    @Override void update() {
        String preview = shell.pairPreview;
        long now = System.currentTimeMillis();
        boolean revoking = a.revocation.pending();
        boolean paired = "paired".equals(a.pairingStatus) && a.pairingId != null;
        boolean compare = preview != null ? "compare".equals(preview)
                : a.pairingId != null && "claimed".equals(a.pairingStatus) && a.confirmation != null && shell.pairCompareStep;
        boolean code = preview != null ? "code".equals(preview) : a.pairingId != null && !paired && !compare;
        String codeValue = preview != null ? "319052" : a.pairingCode;
        String confirmValue = preview != null ? "4827" : a.confirmation;
        long left = preview != null ? (long) (CODE_LIFETIME_MS * .72f) : a.pairingExpiresAtMs - now;

        bar.setVisibility(View.GONE); live.setVisibility(View.GONE); ghost.setVisibility(View.GONE);
        secondary.setVisibility(View.VISIBLE);
        CarKit.enable(primary, !a.busy || preview != null);
        CarKit.enable(secondary, !a.busy || preview != null);
        bigNumber.setTextColor(t.ink);
        bigNumber.setLetterSpacing(0);
        bigNumber.setPadding(0, 0, 0, 0);
        bigNumber.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, k.px(96));
        if (revoking) {
            step.setText("연결 정리");
            display.setText("연결을 끊고\n확인하고 있어요");
            body.setText("이 차의 연결과 받은 기록은 이미 지웠어요.");
            overline.setText("서버 확인");
            bigNumber.setText("…");
            note.setText(a.connectionMessage);
            footNote.setText("확인되지 않으면 다시 확인할 수 있어요.");
            secondary.setVisibility(View.GONE);
            primary.setText("삭제 다시 확인");
            primaryAction = a::retryRevocation;
        } else if (paired && preview == null) {
            step.setText("이어짐");
            display.setText("폰과\n이어져 있어요");
            body.setText("운동 기록은 폰에서 따로 물어보고 보내요. 받은 기록으로 쉼이 저절로 시작되지는 않아요.");
            overline.setText("연결 상태");
            bigNumber.setText(a.isConnectionCurrent() ? "이어져 있어요" : "확인이 필요해요");
            bigNumber.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, k.px(44));
            live.setVisibility(View.VISIBLE);
            k.liveState(live, !a.isConnectionCurrent());
            live.setText(a.hasRemoteProposal() ? (a.privacyMasked ? "운동 기록이 도착했어요 · 동승자 보호로 숨김" : a.proposal.description)
                    : "아직 도착한 운동 기록이 없어요");
            note.setText(a.connectionMessage);
            ghost.setVisibility(a.hasRemoteProposal() ? View.VISIBLE : View.GONE);
            ghost.setText(a.privacyMasked ? "기록 보기" : "기록 숨기기");
            ghostAction = () -> { a.privacyMasked = !a.privacyMasked; a.render(); };
            footNote.setText("연결은 승인 후 2시간 뒤 자동으로 끊겨요.");
            secondary.setText("연결 끊기");
            secondaryAction = a::disconnect;
            primary.setText("확인");
            primaryAction = () -> a.showScreen(MainActivity.Screen.HOME);
        } else if (compare) {
            step.setText("2 / 2");
            display.setText("두 화면의 숫자가\n같은가요?");
            body.setText("같을 때만 승인해 주세요. 운동 기록은 승인한 뒤에도 폰에서 따로 물어보고 보내요.");
            overline.setText("확인 숫자");
            bigNumber.setText(confirmValue);
            bigNumber.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, k.px(148));
            bigNumber.setLetterSpacing(.2f);
            bigNumber.setPadding(k.px(148 * .2f), 0, 0, 0); // balance the trailing letter space (artboard: margin-right -.2em)
            live.setVisibility(View.VISIBLE);
            k.liveState(live, true);
            live.setText("폰에도 같은 숫자가 보여요");
            note.setText("");
            footNote.setText("승인하면 2시간 동안 이어져요.");
            secondary.setText("달라요");
            secondaryAction = preview != null ? () -> { shell.pairPreview = "code"; a.render(); } : () -> { shell.pairCompareStep = false; a.disconnect(); };
            primary.setText("같아요 · 승인");
            primaryAction = preview != null ? () -> { shell.pairPreview = null; a.showScreen(MainActivity.Screen.HOME); } : a::confirmPairing;
        } else if (code) {
            step.setText("1 / 2");
            display.setText("폰의 차분에\n이 번호를 입력하세요");
            body.setText("연결 탭 › 차량에서 입력할 수 있어요.");
            overline.setText("연결 번호");
            bigNumber.setText(spaced(codeValue));
            bigNumber.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, k.px(132));
            bigNumber.setLetterSpacing(.08f);
            bigNumber.setPadding(k.px(132 * .08f), 0, 0, 0);
            bar.setVisibility(View.VISIBLE);
            float f = Math.max(0, Math.min(1, left / (float) CODE_LIFETIME_MS));
            barFill.setScaleX(f);
            barFill.setPivotX(0);
            note.setText(left > 0 ? CarCopy.remaining(left) + " 동안 쓸 수 있어요" : "시간이 지났어요 · 새 번호를 만들어 주세요");
            if (preview == null && a.connectionMessage != null && a.connectionMessage.startsWith("연결")) note.setText(note.getText() + "\n" + a.connectionMessage);
            footNote.setText("번호는 5분 뒤 바뀌어요.");
            secondary.setText("새 번호");
            secondaryAction = preview != null ? () -> { } : a::disconnect;
            boolean claimed = preview != null || ("claimed".equals(a.pairingStatus) && a.confirmation != null);
            primary.setText("폰에 입력했어요");
            primaryAction = () -> {
                if (shell.pairPreview != null) shell.pairPreview = "compare";
                else if ("claimed".equals(a.pairingStatus) && a.confirmation != null) shell.pairCompareStep = true;
                else shell.pairWaitingHint = true;
                a.render();
            };
            if (!claimed && shell.pairWaitingHint) {
                live.setVisibility(View.VISIBLE);
                k.liveState(live, true);
                live.setText("폰에서 번호를 입력하면 확인 숫자가 떠요");
            }
        } else {
            step.setText("1 / 2");
            display.setText("폰의 차분에\n번호를 입력해 이어요");
            body.setText("번호를 만들면 5분 동안 쓸 수 있어요. 연결은 선택이에요.");
            overline.setText("연결 번호");
            bigNumber.setText("––– –––");
            bigNumber.setTextColor(t.ink3);
            bigNumber.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, k.px(132));
            bigNumber.setLetterSpacing(.08f);
            note.setText(a.connectionMessage != null && !a.connectionMessage.startsWith("차량 연결 번호를 만들거나") ? a.connectionMessage : "번호를 만들면 여기에 크게 보여요");
            footNote.setText("번호는 5분 뒤 바뀌어요.");
            secondary.setVisibility(View.GONE);
            primary.setText("번호 만들기");
            primaryAction = a::createPairing;
        }
    }

    @Override List<View> cascadeTargets() { return list(bar(header), step, display, body, card, bar(footer)); }
}
