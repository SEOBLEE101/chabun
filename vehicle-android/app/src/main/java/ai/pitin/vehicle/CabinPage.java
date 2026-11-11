package ai.pitin.vehicle;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * 차 안 설정 (car-cabin, spec 6-2): left vertical tabs 시트 / 에어컨 / 조명, 운전석 / 동승석, a ‘지금 차’ read card,
 * moving illustrations and a draft that is kept with ‘저장’ (for every rest on this car) or dropped with ‘되돌리기’.
 * In a build with a cabin actuator, ‘지금 차에 맞추기’ sends the values on screen while parked and ‘지금 차’ shows what
 * the car reports; the start of a rest applies the saved values too (MainActivity).
 */
final class CabinPage extends CarPage {
    private LinearLayout header, tabs, footer, targets;
    private TextView voicePill, roomPill, savePill, summary, revert, save, note, applyNow;
    private final List<TextView> tabButtons = new ArrayList<>();
    private FrameLayout content;
    private View seatView, climateView, lightView;
    // seat
    private CabinArtView seatArt;
    private TextView seatTarget, seatHeadline, seatSub;
    private LinearLayout comfortOpt, levelOpt, posOpt, levelZone;
    // climate
    private CabinArtView climateArt;
    private RollNumberView tempNumber;
    private TextView tempCaption;
    private LinearLayout climZoneLeft, climZoneRight, modeOpt, fanOpt, dirOpt, acOpt;
    private View tempDown, tempUp;
    // light
    private CabinArtView lightArt;
    private TextView lightHeadline, brightValue;
    private LinearLayout lightModeOpt, lightZone;
    private final List<SwatchView> swatches = new ArrayList<>();
    private CarSlider slider;
    // now cards
    private final List<NowCard> nowCards = new ArrayList<>();

    CabinPage(CarShell shell) { super(shell); }

    private CabinSettings d() { return shell.cabinDraft; }

    private void edit(Runnable change) {
        change.run();
        shell.cabinJustSaved = false;
        a.render();
    }

    @Override View build() {
        FrameLayout root = new FrameLayout(a);
        LinearLayout col = frame(root);
        header = k.header(col);
        TextView back = k.backButton(header, () -> a.showScreen(MainActivity.Screen.HOME));
        // The title tucks over the back button's empty right edge (not touchable), where the artboard puts it.
        CarKit.margins(k.title(header, "차 안 설정", 40), -k.px(24), 0, 0, 0);
        voicePill = k.pill(header, "Gleo로 열었어요", t.accentSoft);
        CarIcon vi = new CarIcon(CarIcon.VOICE, t.ink);
        vi.setBounds(0, 0, k.px(26), k.px(26));
        voicePill.setCompoundDrawables(vi, null, null, null);
        voicePill.setCompoundDrawablePadding(k.px(10));
        voicePill.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        CarKit.margins(voicePill, k.px(28), 0, 0, 0);
        k.flex(header);
        targets = k.row(header);
        TextView driver = k.chip(targets, "운전석", () -> { shell.cabinDriver = true; a.render(); });
        TextView passenger = k.chip(targets, "동승석", () -> { shell.cabinDriver = false; a.render(); });
        driver.setTag("driver");
        passenger.setTag("passenger");
        CarKit.margins(passenger, k.px(12), 0, 0, 0);
        roomPill = k.pill(header, "실내 전체", t.bg2);
        roomPill.setMinHeight(k.px(48));
        roomPill.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, k.px(22));
        savePill = k.pill(header, "", t.bg2);
        savePill.setMinHeight(k.px(48));
        savePill.setPadding(k.px(22), 0, k.px(22), 0);
        savePill.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, k.px(22));
        savePill.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        CarKit.margins(savePill, k.px(28), 0, 0, 0);

        LinearLayout main = k.row(col);
        main.setGravity(Gravity.TOP);
        main.setLayoutParams(CarKit.lp(-1, 0, 1));
        main.setPadding(0, k.px(40), 0, 0);
        tabs = k.column(main);
        tabs.setBackground(CarKit.round(t.bg2, k.px(36), 0, 0));
        tabs.setPadding(k.px(8), k.px(8), k.px(8), k.px(8));
        tabs.setLayoutParams(CarKit.lp(k.px(300), -2));
        String[][] tabItems = {{"seat", "시트", CarIcon.SEAT}, {"climate", "에어컨", CarIcon.CLIMATE}, {"light", "조명", CarIcon.LIGHT}};
        for (int i = 0; i < tabItems.length; i++) {
            final String id = tabItems[i][0];
            TextView b = k.text(tabs, tabItems[i][1], 28, t.ink2, CarKit.W500);
            b.setGravity(Gravity.CENTER_VERTICAL);
            b.setPadding(k.px(30), 0, k.px(30), 0);
            b.setTag(id);
            b.setClickable(true);
            b.setSoundEffectsEnabled(false);
            CarIcon icon = new CarIcon(tabItems[i][2], t.ink2);
            icon.setBounds(0, 0, k.px(34), k.px(34));
            b.setCompoundDrawables(icon, null, null, null);
            b.setCompoundDrawablePadding(k.px(16));
            LinearLayout.LayoutParams p = CarKit.lp(-1, k.px(104));
            if (i > 0) p.topMargin = k.px(6);
            b.setLayoutParams(p);
            b.setOnClickListener(v -> { shell.cabinTab = id; a.render(); });
            k.press(b);
            tabButtons.add(b);
        }
        k.space(main, 56);
        content = new FrameLayout(a);
        main.addView(content, CarKit.lp(0, -1, 1));
        seatView = buildSeat();
        climateView = buildClimate();
        lightView = buildLight();
        content.addView(seatView, new FrameLayout.LayoutParams(-1, -2));
        content.addView(climateView, new FrameLayout.LayoutParams(-1, -2));
        content.addView(lightView, new FrameLayout.LayoutParams(-1, -2));

        footer = k.footer(col);
        LinearLayout notes = k.column(footer);
        notes.setLayoutParams(CarKit.lp(0, -2, 1));
        summary = k.text(notes, "", 22, t.ink, CarKit.W400);
        CarKit.ellipsize(summary);
        note = k.text(notes, "", 19, t.ink3, CarKit.W400);
        note.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        CarKit.margins(note, 0, k.px(6), 0, 0);
        if (a.environment.cabin() != null) {
            applyNow = k.secondary(footer, "지금 차에 맞추기", shell::applyCabinNow);
            CarKit.margins(applyNow, k.px(24), 0, 0, 0);
        }
        revert = k.secondary(footer, "되돌리기", () -> { shell.cabinDraft = shell.cabinSaved.copy(); shell.cabinJustSaved = false; a.render(); });
        CarKit.margins(revert, k.px(24), 0, 0, 0);
        k.space(footer, 20);
        save = k.primary(footer, "저장", shell::saveCabin);
        save.setMinWidth(k.px(330));
        return root;
    }

    private LinearLayout twoColumns() {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        return row;
    }

    private LinearLayout leftCard(LinearLayout row) {
        LinearLayout card = k.card(row);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setLayoutParams(CarKit.lp(0, -2, 1));
        return card;
    }

    private LinearLayout rightColumn(LinearLayout row) {
        LinearLayout c = k.column(row);
        LinearLayout.LayoutParams p = CarKit.lp(0, -2, 1);
        p.leftMargin = k.px(56);
        c.setLayoutParams(p);
        return c;
    }

    private LinearLayout label(LinearLayout parent, String bold, String hint, float topGap) {
        LinearLayout l = k.row(parent);
        l.setGravity(Gravity.BOTTOM);
        if (topGap > 0) CarKit.margins(l, 0, k.px(topGap), 0, 0);
        k.text(l, bold, 28, t.ink, CarKit.W600).setLetterSpacing(-0.02f);
        k.flex(l);
        if (hint != null) k.text(l, hint, 20, t.ink3, CarKit.W400);
        return l;
    }

    private LinearLayout opt(LinearLayout parent, String[][] items, boolean tall, float topGap, CarKit.OptListener listener) {
        LinearLayout o = k.optRow(parent, items, tall, listener);
        o.setLayoutParams(CarKit.lp(-1, -2));
        if (topGap > 0) CarKit.margins(o, 0, k.px(topGap), 0, 0);
        return o;
    }

    private View buildSeat() {
        LinearLayout row = twoColumns();
        LinearLayout card = leftCard(row);
        card.setPadding(k.px(36), k.px(36), k.px(36), k.px(40));
        seatArt = new CabinArtView(a, CabinArtView.Kind.SEAT, t);
        card.addView(seatArt, CarKit.lp(-1, k.px(320)));
        seatTarget = k.overline(card, "운전석", 18);
        CarKit.margins(seatTarget, 0, k.px(22), 0, 0);
        seatHeadline = k.title(card, "", 44);
        CarKit.margins(seatHeadline, 0, k.px(22), 0, 0);
        seatSub = k.caption(card, "");
        CarKit.margins(seatSub, 0, k.px(22), 0, 0);
        LinearLayout right = rightColumn(row);
        nowCards.add(new NowCard(right, "seat"));
        label(right, "열선·통풍", "둘 중 하나만", 40);
        comfortOpt = opt(right, new String[][]{{"rest", "쉼 따르기"}, {"keep", "그대로"}, {"off", "끄기"}, {"heat", "열선"}, {"vent", "통풍"}}, false, 18,
                id -> edit(() -> CabinSettings.setSeatComfort(d().seat(shell.cabinDriver), id)));
        levelZone = k.column(right);
        CarKit.margins(levelZone, 0, k.px(40), 0, 0);
        label(levelZone, "단계", null, 0);
        levelOpt = opt(levelZone, new String[][]{{"1", "1단계"}, {"2", "2단계"}, {"3", "3단계"}}, false, 18,
                id -> edit(() -> CabinSettings.setSeatLevel(d().seat(shell.cabinDriver), Integer.parseInt(id))));
        label(right, "자세", "등받이와 위치는 차의 버튼으로", 40);
        posOpt = opt(right, new String[][]{{"keep", "그대로"}, {"manual", "차에서 직접 조절"}}, false, 18,
                id -> edit(() -> CabinSettings.setSeatPosition(d().seat(shell.cabinDriver), id)));
        return row;
    }

    private View buildClimate() {
        LinearLayout row = twoColumns();
        LinearLayout card = leftCard(row);
        card.setPadding(k.px(36), k.px(28), k.px(36), k.px(36));
        climZoneLeft = k.column(card);
        climZoneLeft.setGravity(Gravity.CENTER_HORIZONTAL);
        climZoneLeft.setLayoutParams(CarKit.lp(-1, -2));
        climateArt = new CabinArtView(a, CabinArtView.Kind.CLIMATE, t);
        climZoneLeft.addView(climateArt, CarKit.lp(-1, k.px(310)));
        tempNumber = new RollNumberView(a, t, k.px(104), k.px(48));
        climZoneLeft.addView(tempNumber, CarKit.lp(-2, k.px(124)));
        tempCaption = k.caption(climZoneLeft, "");
        CarKit.margins(tempCaption, 0, k.px(4), 0, 0);
        LinearLayout steps = k.row(card);
        steps.setLayoutParams(CarKit.lp(-1, -2));
        CarKit.margins(steps, 0, k.px(10), 0, 0);
        tempDown = k.glass(steps, CarIcon.MINUS, 96);
        tempDown.setContentDescription("온도 내리기");
        k.flex(steps);
        k.caption(steps, "18° ~ 28°");
        k.flex(steps);
        tempUp = k.glass(steps, CarIcon.PLUS, 96);
        tempUp.setContentDescription("온도 올리기");
        for (View v : new View[]{tempDown, tempUp}) {
            v.setClickable(true);
            v.setSoundEffectsEnabled(false);
            k.press(v);
        }
        tempDown.setOnClickListener(v -> edit(() -> CabinSettings.stepTemp(d().climate(shell.cabinDriver), -1)));
        tempUp.setOnClickListener(v -> edit(() -> CabinSettings.stepTemp(d().climate(shell.cabinDriver), 1)));

        LinearLayout right = rightColumn(row);
        nowCards.add(new NowCard(right, "climate"));
        modeOpt = opt(right, new String[][]{{"rest", "쉼 따르기"}, {"keep", "그대로"}, {"custom", "직접 정하기"}}, false, 30,
                id -> edit(() -> CabinSettings.setClimateMode(d().climate(shell.cabinDriver), id)));
        climZoneRight = k.column(right);
        CarKit.margins(climZoneRight, 0, k.px(30), 0, 0);
        label(climZoneRight, "바람 세기", null, 0);
        fanOpt = opt(climZoneRight, new String[][]{{"auto", "자동"}, {"low", "약하게"}, {"medium", "중간"}, {"high", "세게"}}, false, 16,
                id -> edit(() -> CabinSettings.setFan(d().climate(shell.cabinDriver), id)));
        label(climZoneRight, "바람 방향", null, 34);
        dirOpt = opt(climZoneRight, new String[][]{{"keep", "그대로", CarIcon.DIR_KEEP}, {"upper", "위로", CarIcon.DIR_UP},
                {"lower", "아래로", CarIcon.DIR_DOWN}, {"both", "위아래", CarIcon.DIR_BOTH}}, true, 16,
                id -> edit(() -> CabinSettings.setDirection(d().climate(shell.cabinDriver), id)));
        label(climZoneRight, "냉방", "A/C", 34);
        acOpt = opt(climZoneRight, new String[][]{{"keep", "그대로"}, {"on", "켜기"}, {"off", "끄기"}}, false, 16,
                id -> edit(() -> CabinSettings.setAc(d().climate(shell.cabinDriver), id)));
        return row;
    }

    private View buildLight() {
        LinearLayout row = twoColumns();
        LinearLayout card = leftCard(row);
        card.setPadding(k.px(24), k.px(24), k.px(24), k.px(30));
        lightArt = new CabinArtView(a, CabinArtView.Kind.LIGHT, t);
        card.addView(lightArt, CarKit.lp(-1, -2));
        LinearLayout cap = k.row(card);
        cap.setGravity(Gravity.BOTTOM);
        cap.setLayoutParams(CarKit.lp(-1, -2));
        cap.setPadding(k.px(10), k.px(20), k.px(10), 0);
        lightHeadline = k.text(cap, "", 30, t.ink, CarKit.W600);
        k.flex(cap);
        k.caption(cap, "화면 속 예시 색이에요");

        LinearLayout right = rightColumn(row);
        nowCards.add(new NowCard(right, "light"));
        lightModeOpt = opt(right, new String[][]{{"rest", "쉼 따르기"}, {"keep", "그대로"}, {"off", "끄기"}, {"custom", "직접"}}, false, 40,
                id -> edit(() -> CabinSettings.setLightMode(d().light, id)));
        lightZone = k.column(right);
        CarKit.margins(lightZone, 0, k.px(40), 0, 0);
        label(lightZone, "빛의 색", "차의 실제 색과 다를 수 있어요", 0);
        LinearLayout sw = k.row(lightZone);
        sw.setLayoutParams(CarKit.lp(-1, -2));
        CarKit.margins(sw, 0, k.px(22), 0, 0);
        int[] colors = {0xffe8cfa6, 0xffe8e6de, 0xffa5b198, 0xffa4bbc9};
        for (int i = 0; i < CabinSettings.COLORS.length; i++) {
            final String id = CabinSettings.COLORS[i];
            LinearLayout item = k.column(sw);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setLayoutParams(CarKit.lp(0, -2, 1));
            item.setClickable(true);
            item.setSoundEffectsEnabled(false);
            SwatchView s = new SwatchView(a, colors[i], t);
            item.addView(s, CarKit.lp(k.px(124), k.px(124)));
            TextView l = k.text(item, CabinSettings.colorLabel(id), 22, t.ink, CarKit.W500);
            CarKit.margins(l, 0, k.px(4), 0, 0);
            item.setOnClickListener(v -> edit(() -> CabinSettings.setColor(d().light, id)));
            k.press(item);
            s.setTag(id);
            swatches.add(s);
        }
        LinearLayout bl = label(lightZone, "밝기", null, 44);
        brightValue = k.num(bl, "", 28, t.ink);
        slider = new CarSlider(a, t, k.unit, 10, 100, 5);
        slider.setListener(v -> edit(() -> CabinSettings.setBright(d().light, v)));
        LinearLayout.LayoutParams sp = CarKit.lp(-1, k.px(56));
        sp.topMargin = k.px(14);
        lightZone.addView(slider, sp);
        LinearLayout ends = k.row(lightZone);
        ends.setLayoutParams(CarKit.lp(-1, -2));
        CarKit.margins(ends, 0, k.px(8), 0, 0);
        k.text(ends, "은은하게", 20, t.ink3, CarKit.W400);
        k.flex(ends);
        k.text(ends, "밝게", 20, t.ink3, CarKit.W400);
        return row;
    }

    private static void zone(View v, boolean on) {
        v.setAlpha(on ? 1f : .34f);
        setEnabledDeep(v, on);
    }

    private static void setEnabledDeep(View v, boolean on) {
        v.setEnabled(on);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) setEnabledDeep(g.getChildAt(i), on);
        }
    }

    @Override void update() {
        CabinSettings dr = d();
        String tab = shell.cabinTab;
        boolean driver = shell.cabinDriver;
        for (TextView b : tabButtons) {
            boolean on = tab.equals(b.getTag());
            b.setSelected(on);
            b.setBackground(on ? CarKit.round(t.surface, k.px(30), t.line, Math.max(1, k.px(1))) : null);
            b.setElevation(on ? k.px(1) : 0);
            b.setTextColor(on ? t.ink : t.ink2);
            b.setTypeface(CarKit.face(on ? CarKit.W600 : CarKit.W500));
            ((CarIcon) b.getCompoundDrawables()[0]).setColor(on ? t.ink : t.ink2);
            b.setContentDescription(b.getText() + " 탭" + (on ? ", 선택됨" : ""));
        }
        seatView.setVisibility("seat".equals(tab) ? View.VISIBLE : View.GONE);
        climateView.setVisibility("climate".equals(tab) ? View.VISIBLE : View.GONE);
        lightView.setVisibility("light".equals(tab) ? View.VISIBLE : View.GONE);
        boolean light = "light".equals(tab);
        targets.setVisibility(light ? View.GONE : View.VISIBLE);
        roomPill.setVisibility(light ? View.VISIBLE : View.GONE);
        for (int i = 0; i < targets.getChildCount(); i++) {
            TextView c = (TextView) targets.getChildAt(i);
            k.chipState(c, ("driver".equals(c.getTag())) == driver);
        }
        voicePill.setVisibility(shell.pillFor(MainActivity.Screen.CABIN) != null ? View.VISIBLE : View.GONE);
        if (voicePill.getVisibility() == View.VISIBLE) voicePill.setText(shell.pillFor(MainActivity.Screen.CABIN));

        int changed = dr.changedCount(shell.cabinSaved);
        if (changed > 0) {
            savePill.setText("저장 전 · " + changed + "개");
            savePill.setBackground(CarKit.round(t.bg2, k.px(24), 0, 0));
            savePill.setTextColor(t.ink2);
            android.graphics.drawable.ShapeDrawable dot = new android.graphics.drawable.ShapeDrawable(new android.graphics.drawable.shapes.OvalShape());
            dot.getPaint().setColor(t.warn);
            dot.setBounds(0, 0, k.px(10), k.px(10));
            savePill.setCompoundDrawables(dot, null, null, null);
            savePill.setCompoundDrawablePadding(k.px(10));
        } else {
            savePill.setText(shell.cabinJustSaved ? "저장했어요" : "저장됨");
            savePill.setBackground(CarKit.round(t.accentSoft, k.px(24), 0, 0));
            savePill.setTextColor(t.ink);
            savePill.setCompoundDrawables(null, null, null, null);
        }
        CarKit.enable(revert, changed > 0);
        save.setText(changed > 0 ? "저장" : "저장됨");
        CarKit.enable(save, changed > 0);
        summary.setText(dr.summary());
        boolean actuator = a.environment.cabin() != null;
        String blocked = actuator ? a.cabinBlocked() : null;
        if (shell.cabinApplyNote != null) note.setText(shell.cabinApplyNote);
        else if (!actuator) note.setText("차에서 맞출 값을 기억해 둬요. 이 빌드는 차를 바꾸지 않아요.");
        else if (blocked != null) note.setText(blocked + " · 쉬기를 시작할 때 이 값으로 맞춰요.");
        else note.setText(BuildConfig.SIMULATED_VEHICLE ? "데모 · 실제 차 대신 화면 속 차에 맞춰 봐요."
                : "쉬기를 시작하면 이 값으로 맞추고, 마치면 쉬기 전으로 돌려요.");
        note.setTextColor(shell.cabinApplyNote != null && !shell.cabinApplying ? t.ink2 : t.ink3);
        if (applyNow != null) {
            applyNow.setText(shell.cabinApplying ? "맞추는 중" : "지금 차에 맞추기");
            CarKit.enable(applyNow, !shell.cabinApplying && blocked == null);
        }
        boolean reduced = k.reducedMotion();

        CabinSettings.Seat seat = dr.seat(driver);
        seatArt.setReduced(reduced);
        seatArt.setSeat(seat);
        seatTarget.setText(driver ? "운전석" : "동승석");
        boolean heat = "heat".equals(seat.comfort), vent = "vent".equals(seat.comfort);
        boolean seatRest = "rest".equals(seat.comfort);
        String seatFollow = follow("seat", driver);
        seatHeadline.setText(heat ? "열선 " + seat.level + "단계" : vent ? "통풍 " + seat.level + "단계"
                : "off".equals(seat.comfort) ? "열선·통풍 끄기" : seatRest ? "쉼 따르기" : "열선·통풍 그대로");
        seatSub.setText(seatRest ? (seatFollow.isEmpty() ? "이 쉼은 시트를 바꾸지 않아요" : seatFollow.replaceFirst("^(동승석|시트) ", ""))
                : "manual".equals(seat.position) ? "자세는 차에서 직접 맞춰요" : "자세는 그대로 둬요");
        k.optState(comfortOpt, seat.comfort);
        k.optState(levelOpt, String.valueOf(seat.level));
        k.optState(posOpt, seat.position);
        zone(levelZone, heat || vent);

        CabinSettings.Climate clim = dr.climate(driver);
        climateArt.setReduced(reduced);
        climateArt.setClimate(clim);
        tempNumber.setValue(clim.tempTenths, !reduced);
        tempNumber.setContentDescription((driver ? "운전석" : "동승석") + " 바라는 온도 " + CabinSettings.temp(clim.tempTenths).replace("°", "도"));
        boolean custom = "custom".equals(clim.mode);
        String pill = shell.pillFor(MainActivity.Screen.CABIN);
        if ("rest".equals(clim.mode)) {
            String f = follow("climate", driver);
            tempCaption.setText("쉼 따르기 · " + (f.isEmpty() ? "이 쉼은 에어컨을 바꾸지 않아요" : f.replaceFirst("^에어컨 ", "")));
        } else tempCaption.setText((driver ? "운전석" : "동승석") + ("keep".equals(clim.mode) ? " · 그대로 둬요" : " · 바라는 온도"));
        k.optState(modeOpt, clim.mode);
        k.optState(fanOpt, clim.fan);
        k.optState(dirOpt, clim.dir);
        k.optState(acOpt, clim.ac);
        zone(climZoneLeft, custom);
        zone(tempDown, custom);
        zone(tempUp, custom);
        zone(climZoneRight, custom);

        CabinSettings.Light l = dr.light;
        lightArt.setReduced(reduced);
        lightArt.setLight(l);
        boolean lon = "custom".equals(l.mode);
        String lightFollow = follow("light", true);
        lightHeadline.setText(lon ? CabinSettings.colorLabel(l.color) + " · 밝기 " + l.bright + "%" : "off".equals(l.mode) ? "조명 끄기"
                : "rest".equals(l.mode) ? (lightFollow.isEmpty() ? "쉼 따르기 · 조명은 그대로" : "쉼 따르기 · " + lightFollow.replaceFirst("^조명 ", ""))
                : "조명은 그대로 둬요");
        k.optState(lightModeOpt, l.mode);
        for (SwatchView s : swatches) s.setSelectedSwatch(l.color.equals(s.getTag()), !reduced);
        brightValue.setText(l.bright + "%");
        slider.setValue(l.bright);
        zone(lightZone, lon);
        for (NowCard n : nowCards) n.update();
        if (pill == null) tempCaption.setTypeface(CarKit.face(CarKit.W400));
    }

    /** What ‘쉼 따르기’ brings for this group from the chosen rest (and the phone's values for it). */
    private String follow(String group, boolean driver) {
        return CabinControl.followText(group, driver, a.selectedPreset, a.sharedClimate, a.sharedCabin);
    }

    @Override List<View> cascadeTargets() { return list(bar(header), tabs, content, bar(footer)); }

    /** ‘지금 차’: what the car reports now, read time and ‘다시 읽기’ (reads only; never sends a value). */
    private final class NowCard {
        final String kind;
        final TextView title, time;
        final TextView[] values = new TextView[2], labels = new TextView[2];

        NowCard(LinearLayout parent, String kind) {
            this.kind = kind;
            LinearLayout card = k.column(parent);
            card.setBackground(CarKit.round(t.bg2, k.px(28), 0, 0));
            card.setPadding(k.px(28), k.px(14), k.px(16), k.px(20));
            card.setLayoutParams(CarKit.lp(-1, -2));
            card.setContentDescription("차의 지금 값");
            LinearLayout top = k.row(card);
            top.setLayoutParams(CarKit.lp(-1, -2));
            top.setMinimumHeight(k.px(52));
            title = k.overline(top, "", 18);
            k.flex(top);
            time = k.text(top, "", 20, t.ink3, CarKit.W400);
            FrameLayout re = new FrameLayout(a);
            ImageView ri = new ImageView(a);
            ri.setImageDrawable(new CarIcon(CarIcon.REREAD, t.ink2));
            re.addView(ri, new FrameLayout.LayoutParams(k.px(26), k.px(26), Gravity.CENTER));
            re.setClickable(true);
            re.setSoundEffectsEnabled(false);
            re.setContentDescription("차의 지금 값 다시 읽기");
            re.setOnClickListener(v -> { shell.cabinRereadAt = System.currentTimeMillis(); shell.readCabinNow(); a.render(); });
            k.press(re);
            // 76px touch area (AAOS; the row grows to fit); the icon keeps its place at the card's right edge.
            LinearLayout.LayoutParams rp = CarKit.lp(k.px(76), k.px(76));
            rp.leftMargin = k.px(4);
            top.addView(re, rp);
            LinearLayout stats = k.row(card);
            stats.setGravity(Gravity.BOTTOM);
            CarKit.margins(stats, 0, k.px(2), 0, 0);
            for (int i = 0; i < 2; i++) {
                values[i] = k.text(stats, "", 36, t.ink, CarKit.W500);
                values[i].setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.create("sans-serif", 0), 500, false));
                values[i].setLetterSpacing(-0.02f);
                if (i > 0) CarKit.margins(values[i], k.px(40), 0, 0, 0);
                labels[i] = k.text(stats, "", 20, t.ink3, CarKit.W400);
                CarKit.margins(labels[i], k.px(10), 0, 0, 0);
            }
        }

        void update() {
            boolean driver = shell.cabinDriver;
            boolean demo = BuildConfig.SIMULATED_VEHICLE;
            title.setText("지금 차 · " + ("light".equals(kind) ? "실내" : driver ? "운전석" : "동승석"));
            if (a.environment.cabin() != null && !shell.cabinNow.isEmpty()) { reported(driver, demo); return; }
            String[][] stats;
            if ("seat".equals(kind)) stats = driver ? new String[][]{{"1단계", "열선"}, {"꺼짐", "통풍"}} : new String[][]{{"꺼짐", "열선"}, {"꺼짐", "통풍"}};
            else if ("climate".equals(kind)) stats = driver ? new String[][]{{"24.0°", "설정 온도"}, {"26.5°", "실내 온도"}} : new String[][]{{"24.0°", "설정 온도"}, {"26.0°", "실내 온도"}};
            else stats = new String[][]{{"켜짐", "앰비언트"}, {"60%", "밝기"}};
            long readAt = shell.cabinRereadAt > 0 ? shell.cabinRereadAt : shell.cabinOpenedAt;
            boolean justNow = shell.cabinRereadAt > 0 && System.currentTimeMillis() - shell.cabinRereadAt < 60_000;
            if (demo) time.setText("데모 값 · " + (justNow ? "방금 읽음" : CarCopy.clock(readAt) + " 읽음"));
            else time.setText(justNow ? "방금 확인 · 읽을 수 없어요" : "이 차에서 아직 읽을 수 없어요");
            for (int i = 0; i < 2; i++) {
                values[i].setText(demo ? stats[i][0] : "확인 못 함");
                values[i].setTextColor(demo ? t.ink : t.ink3);
                values[i].setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, k.px(demo ? 36 : 26));
                labels[i].setText(stats[i][1]);
            }
        }

        /** What the car (or the demo car) reported last, in its own numbers. */
        private void reported(boolean driver, boolean demo) {
            String side = driver ? ":driver" : ":passenger";
            String[][] stats;
            if ("seat".equals(kind)) stats = new String[][]{{level(shell.cabinNow.get("SEAT_HEAT" + side)), "열선"},
                    {level(shell.cabinNow.get("SEAT_VENT" + side)), "통풍"}};
            else if ("climate".equals(kind)) {
                Integer power = shell.cabinNow.get("CLIMATE_POWER" + side), temp = shell.cabinNow.get("TEMPERATURE" + side),
                        fan = shell.cabinNow.get("FAN" + side);
                stats = new String[][]{{temp == null ? null : CabinSettings.temp(temp), "설정 온도"},
                        {Integer.valueOf(0).equals(power) ? "꺼짐" : fan == null ? null : fan + "단", "바람"}};
            } else {
                Integer on = shell.cabinNow.get("LIGHT_SWITCH"), bright = shell.cabinNow.get("LIGHT_BRIGHTNESS");
                stats = new String[][]{{on == null ? null : on == 1 ? "켜짐" : "꺼짐", "앰비언트"},
                        {bright == null ? null : bright + "%", "밝기"}};
            }
            boolean justNow = System.currentTimeMillis() - shell.cabinNowAt < 60_000;
            time.setText((demo ? "데모 차 · " : "") + (justNow ? "방금 읽음" : CarCopy.clock(shell.cabinNowAt) + " 읽음"));
            for (int i = 0; i < 2; i++) {
                boolean known = stats[i][0] != null;
                values[i].setText(known ? stats[i][0] : "확인 못 함");
                values[i].setTextColor(known ? t.ink : t.ink3);
                values[i].setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, k.px(known ? 36 : 26));
                labels[i].setText(stats[i][1]);
            }
        }

        private String level(Integer v) { return v == null ? null : v == 0 ? "꺼짐" : v + "단계"; }
    }
}
