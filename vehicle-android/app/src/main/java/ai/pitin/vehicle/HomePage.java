package ai.pitin.vehicle;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** 시작 (car-home): ‘잠깐 쉬어갈까요?’, the arrived rest, one primary ‘3분 쉬기’. No top tabs. Hosts the Pleos intro. */
final class HomePage extends CarPage {
    private LinearLayout header, copy, footer;
    private TextView phone, status, eyebrow, display, line1, line2, restName, restSub, moodName, change, start;
    private View eyebrowIcon, rule, card;
    private FrameLayout rootView;
    private TextView headerWord, skip;
    private HeroArtView art;
    // 인트로: 글자 하나씩(등장용)과 한 덩어리 로고(비행·착지용)를 겹쳐 둔 상자
    private FrameLayout fly;
    private LinearLayout flyLetters;
    private TextView flySingle;
    private final List<View> letters = new ArrayList<>();
    private boolean introRunning, introCascaded;

    HomePage(CarShell shell) { super(shell); }

    @Override View build() {
        FrameLayout root = new FrameLayout(a);
        rootView = root;
        LinearLayout col = frame(root);

        header = k.header(col);
        headerWord = k.wordmark(header, 34);
        headerWord.setOnLongClickListener(v -> { shell.openDevSheet(); return true; });
        k.sep(header, 40);
        status = k.live(header, "주차 중", 24, false);
        k.flex(header);
        phone = k.ghost(header, "폰 연결", CarIcon.PHONE, () -> a.showScreen(MainActivity.Screen.CONNECTIONS));
        k.sep(header, 40);
        TextView features = k.ghost(header, "", CarIcon.FEATURES, () -> a.showScreen(MainActivity.Screen.FEATURES));
        features.setContentDescription("이 차에서 쓸 기능");
        features.setMinWidth(k.px(88));

        LinearLayout main = k.row(col);
        main.setLayoutParams(CarKit.lp(-1, 0, 1));
        FrameLayout left = new FrameLayout(a);
        main.addView(left, CarKit.lp(0, -1, 47));
        k.space(main, 40);
        FrameLayout right = new FrameLayout(a);
        main.addView(right, CarKit.lp(0, k.px(640), 53));
        // The Umber contour is wider than its column and fades out under a radial mask (CSS overflow: visible).
        main.setClipChildren(false);
        right.setClipChildren(false);
        root.setClipChildren(false);

        copy = k.column(left);
        copy.setLayoutParams(new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));
        LinearLayout eb = k.row(copy);
        ImageView ebIcon = new ImageView(a);
        ebIcon.setImageDrawable(new CarIcon(CarIcon.PHONE, t.ink2));
        eb.addView(ebIcon, CarKit.lp(k.px(28), k.px(28)));
        eyebrowIcon = ebIcon;
        eyebrow = k.text(eb, "", 26, t.ink2, CarKit.W500);
        CarKit.margins(eyebrow, k.px(12), 0, 0, 0);
        display = k.display(copy, "잠깐\n쉬어갈까요?", 92);
        CarKit.margins(display, 0, k.px(30), 0, 0);
        LinearLayout lines = k.column(copy);
        CarKit.margins(lines, 0, k.px(30), 0, 0);
        line1 = k.body(lines, "");
        line1.setTextColor(t.ink);
        line2 = k.body(lines, "");
        CarKit.margins(line2, 0, k.px(10), 0, 0);
        rule = new View(a);
        rule.setBackgroundColor(t.line2);
        LinearLayout.LayoutParams rp = CarKit.lp(k.px(64), k.px(2));
        rp.setMargins(0, k.px(36), 0, k.px(6));
        copy.addView(rule, rp);
        LinearLayout c = k.card(copy);
        c.setPadding(k.px(32), k.px(28), k.px(32), k.px(28));
        LinearLayout.LayoutParams cp = CarKit.lp(-2, -2);
        cp.topMargin = k.px(30);
        c.setLayoutParams(cp);
        c.setMinimumWidth(k.px(560));
        card = c;
        k.overline(c, "오늘의 쉼", 18);
        restName = k.text(c, "", 32, t.ink, CarKit.W600);
        restName.setLetterSpacing(-0.03f);
        CarKit.margins(restName, 0, k.px(6), 0, 0);
        restSub = k.text(c, "", 22, t.ink2, CarKit.W400);
        CarKit.margins(restSub, 0, k.px(6), 0, 0);

        art = new HeroArtView(a, t, k.unit);
        right.addView(art, new FrameLayout.LayoutParams(-1, -1));

        footer = k.footer(col);
        moodName = k.overline(footer, t.label, 18);
        moodName.setLetterSpacing(.24f);
        k.sep(footer, 44);
        TextView sound = k.caption(footer, "소리 끔");
        CarIcon mute = new CarIcon(CarIcon.MUTE, t.ink3);
        mute.setBounds(0, 0, k.px(28), k.px(28));
        sound.setCompoundDrawables(mute, null, null, null);
        sound.setCompoundDrawablePadding(k.px(10));
        sound.setGravity(Gravity.CENTER_VERTICAL);
        k.flex(footer);
        change = k.secondary(footer, "바꾸기", this::secondaryAction);
        k.space(footer, 20);
        start = k.primary(footer, "3분 쉬기", a::startRest);
        start.setMinWidth(k.px(340));

        fly = new FrameLayout(a);
        flyLetters = new LinearLayout(a);
        flyLetters.setOrientation(LinearLayout.HORIZONTAL);
        String[] glyphs = {"차", "분", "."};
        for (int i = 0; i < glyphs.length; i++) {
            TextView g = k.text(flyLetters, glyphs[i], 34, i == glyphs.length - 1 ? t.accent : t.ink, CarKit.W700);
            g.setTypeface(CarKit.wordFace());
            g.setLetterSpacing(-0.02f);
            letters.add(g);
        }
        fly.addView(flyLetters, new FrameLayout.LayoutParams(-2, -2));
        flySingle = k.wordmark(fly, 34);
        fly.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        root.addView(fly, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        fly.setVisibility(View.GONE);

        skip = k.ghost(null, "건너뛰기", null, this::skipIntro);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        sp.setMargins(0, k.px(105), k.px(52), 0);
        root.addView(skip, sp);
        skip.setVisibility(View.GONE);
        root.setOnClickListener(null);
        return root;
    }

    private void secondaryAction() {
        if (a.session.state() == RestSession.State.PAUSED) a.endPausedRest();
        else a.showScreen(MainActivity.Screen.PREPARATION);
    }

    @Override void update() {
        boolean paused = a.session.state() == RestSession.State.PAUSED;
        phone.setText(CarCopy.phoneStatus(a));
        status.setText(BuildConfig.SIMULATED_VEHICLE ? "주차 중 · 데모" : "주차 중");
        k.liveState(status, !a.parkingVerified());
        if (!a.parkingVerified()) status.setText("주차 확인 전");
        boolean arrived = a.hasRemoteProposal() || a.sharedRestName != null;
        eyebrowIcon.setVisibility(arrived ? View.VISIBLE : View.GONE);
        eyebrow.setText(paused ? "멈춘 쉼이 있어요" : arrived
                ? "폰에서 고른 쉼이 도착했어요" + (a.proposalReceivedAtMs > 0 ? " · " + CarCopy.clock(a.proposalReceivedAtMs) : "")
                : "주차한 차 안, 3분의 고요");
        CarKit.margins(eyebrow, arrived ? k.px(12) : 0, 0, 0, 0);
        display.setText(paused ? "이어서\n쉬어갈까요?" : "잠깐\n쉬어갈까요?");
        line1.setText(paused ? "멈춘 동안 시간은 흐르지 않아요." : "3분 · " + CarCopy.mode(a.naturalRestMode) + " · 소리 끔");
        line2.setText(paused ? "이어서 쉬려면 직접 눌러 주세요." : a.naturalRestMode ? "박자 안내 없이 편안하게 쉬어가세요." : "안내는 참고만 해도 좋아요.");
        restName.setText(a.sharedRestName != null ? a.sharedRestName : CarCopy.restName(a.selectedPreset));
        String sub = CarCopy.restValues(a.selectedPreset);
        CabinActuator cabin = a.environment.cabin();
        if (cabin != null && !a.selectedPreset.isEmpty()) {
            // The plan the start will send, in the same words the result uses. While the last rest is still being
            // put back (or owes it), that comes first; once it is done the card shows the next start again.
            String plan = CabinControl.describe(a.restPlan());
            boolean puttingBack = a.cabinFinishNote != null && (cabin.owesRestore() || cabin.busy());
            sub = puttingBack ? a.cabinFinishNote.replace(".", "")
                    : plan.isEmpty() ? "시작해도 차 안은 그대로예요" : "시작하면 " + plan;
        } else if (BuildConfig.LIVE_REST && !a.selectedPreset.isEmpty()) {
            String plan = CabinControl.describe(a.restPlan());
            sub = plan.isEmpty() ? "차 안은 그대로 두고 쉬어요" : "손으로 맞출 값 · " + plan;
        } else if (!a.selectedPreset.isEmpty()) sub += " · 공조는 직접 맞춰 주세요";
        restSub.setText(sub);
        change.setText(paused ? "쉬기 마치기" : "바꾸기");
        start.setText(paused ? "계속하기" : "3분 쉬기");
    }

    @Override List<View> cascadeTargets() {
        return list(bar(header), eyebrow.getParent() instanceof View ? (View) eyebrow.getParent() : null, display, line1, line2, rule, card, bar(footer));
    }

    @Override void onShow(boolean animate) {
        if (introRunning) return;
        super.onShow(animate);
        art.setBreathing(!k.reducedMotion());
    }

    boolean introRunning() { return introRunning; }

    /**
     * Pleos intro (motion-car-intro, 2026-10-28): words only, no art. ‘차분’ rises letter by letter at 3.2×
     * in the middle (0.08s + 0.045s each, 0.8s), the olive dot pops at 0.66s; at 1.45s the mark shrinks and flies
     * to the header logo spot (0.82s, ease-ios) while the header, copy, art and buttons cascade in; it then hands
     * over to the real header logo, which has the same size and place.
     */
    void playIntro() { playIntro(-1); }

    private static final long FLY_AT = 1450, FLY_MS = 820, CASCADE_AT = 1560, END = FLY_AT + FLY_MS;
    private static final float START_SCALE = 3.2f;
    private android.animation.ValueAnimator introClock;
    private float startTx, startTy, endTx, endTy;
    private boolean geometryReady;

    /** {@code freezeAtMs >= 0} holds a single frame (review screenshots); tap ‘건너뛰기’ to continue. */
    void playIntro(long freezeAtMs) {
        view();
        introRunning = true;
        introCascaded = false;
        geometryReady = false;
        for (View v : cascadeTargets()) { v.animate().cancel(); v.setAlpha(1f); v.setTranslationY(0); }
        for (View v : list(bar(header), copy, bar(footer))) { v.animate().cancel(); v.setAlpha(0f); }
        art.finishIntro();
        art.setBreathing(false);
        art.animate().cancel();
        art.setAlpha(0f);
        headerWord.setAlpha(0f);
        fly.setVisibility(View.VISIBLE);
        fly.setAlpha(1f);
        skip.setVisibility(View.VISIBLE);
        skip.setAlpha(1f);
        if (introClock != null) introClock.cancel();
        applyIntro(0);
        // 위치 계산은 배치가 끝난 뒤에 한다(차가 막 켜졌을 때는 아직 크기가 0일 수 있다)
        rootView.post(() -> {
            if (!introRunning) return;
            if (freezeAtMs >= 0) { applyIntro(freezeAtMs); return; }
            runClock(0);
        });
    }

    private void runClock(long fromMs) {
        if (introClock != null) introClock.cancel();
        introClock = android.animation.ValueAnimator.ofFloat(fromMs, END);
        introClock.setDuration(Math.max(1, END - fromMs));
        introClock.setInterpolator(null);
        introClock.addUpdateListener(an -> applyIntro((long) (float) an.getAnimatedValue()));
        introClock.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator animation) { if (!cancelled) revealHome(); }
        });
        introClock.start();
    }

    private static float clamp(float v) { return Math.max(0, Math.min(1, v)); }

    /** 가운데(시작)와 머리말 로고 자리(끝)를 화면 기준으로 잰다. 상자는 가운데를 축으로 커졌다 작아진다. */
    private boolean measure() {
        if (geometryReady) return true;
        int w = fly.getWidth(), h = fly.getHeight();
        if (w == 0 || rootView.getWidth() == 0 || headerWord.getWidth() == 0) return false;
        int[] root = new int[2], word = new int[2];
        rootView.getLocationInWindow(root);
        headerWord.getLocationInWindow(word);
        fly.setPivotX(w / 2f);
        fly.setPivotY(h / 2f);
        startTx = rootView.getWidth() / 2f - w / 2f;
        startTy = rootView.getHeight() / 2f - h / 2f - k.px(20);
        endTx = word[0] - root[0];
        endTy = word[1] - root[1];
        geometryReady = true;
        return true;
    }

    private void applyIntro(long ms) {
        boolean placed = measure();
        for (int i = 0; i < letters.size(); i++) {
            View g = letters.get(i);
            if (i < letters.size() - 1) {
                float e = CarKit.EASE_IOS.getInterpolation(clamp((ms - 80 - 45L * i) / 800f));
                g.setAlpha(e);
                g.setTranslationY(k.px(14) * (1 - e));
            } else {
                float p = clamp((ms - 660) / 760f);
                float sc = p <= 0 ? 0 : CarKit.SPRING_POP.getInterpolation(p);
                g.setPivotX(g.getWidth() / 2f);
                g.setPivotY(g.getHeight() * .8f);
                g.setScaleX(sc);
                g.setScaleY(sc);
            }
        }
        // 글자 하나씩 → 한 덩어리 로고로 겹쳐 바꾼다(머리말 로고와 자간까지 같게 착지하려고)
        float swap = clamp((ms - 1300) / 150f);
        flyLetters.setAlpha(1 - swap);
        flySingle.setAlpha(swap);
        float f = CarKit.EASE_IOS.getInterpolation(clamp((ms - FLY_AT) / (float) FLY_MS));
        float scale = START_SCALE + (1 - START_SCALE) * f;
        fly.setScaleX(scale);
        fly.setScaleY(scale);
        fly.setAlpha(placed ? 1f : 0f);
        if (placed) {
            fly.setTranslationX(startTx + (endTx - startTx) * f);
            fly.setTranslationY(startTy + (endTy - startTy) * f);
        }
        skip.setAlpha(1 - clamp((ms - FLY_AT) / 200f));
        if (ms >= CASCADE_AT && !introCascaded) {
            introCascaded = true;
            for (View v : list(bar(header), copy, bar(footer))) v.setAlpha(1f);
            k.cascade(cascadeTargets(), 0);
            art.animate().alpha(1f).setStartDelay(160).setDuration(700).setInterpolator(CarKit.EASE_IOS).start();
        }
    }

    private void skipIntro() {
        if (!introRunning || introClock == null) return;
        long now = (long) (float) introClock.getAnimatedValue();
        if (now >= FLY_AT) return;
        // 남은 등장은 건너뛰고 바로 로고 자리로 날아간다
        runClock(FLY_AT - 1);
        for (View g : letters) { g.setAlpha(1f); g.setTranslationY(0); g.setScaleX(1f); g.setScaleY(1f); }
    }

    private void revealHome() {
        if (!introRunning) return;
        introRunning = false;
        if (!introCascaded) {
            introCascaded = true;
            for (View v : list(bar(header), copy, bar(footer))) v.setAlpha(1f);
            k.cascade(cascadeTargets(), 0);
        }
        art.animate().cancel();
        art.setAlpha(1f);
        headerWord.setAlpha(1f);
        fly.setVisibility(View.GONE);
        skip.setVisibility(View.GONE);
        art.setBreathing(!k.reducedMotion());
        shell.onIntroFinished();
    }

    void cancelIntro() {
        if (!introRunning) return;
        if (introClock != null) introClock.cancel();
        revealHome();
    }
}
