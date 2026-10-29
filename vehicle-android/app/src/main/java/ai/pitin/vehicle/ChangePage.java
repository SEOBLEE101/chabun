package ai.pitin.vehicle;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * 시작 전에 바꾸기 (car-change): 오늘의 쉼 3개 · 쉬는 방식 · 화면 분위기 · 소리 · 동작 줄이기.
 * Rest and mode are a draft until ‘이 설정으로’; mood and reduced motion apply at once (display preferences).
 */
final class ChangePage extends CarPage {
    private final List<LinearLayout> tiles = new ArrayList<>();
    private final List<View> checks = new ArrayList<>();
    private final List<View> rings = new ArrayList<>();
    private LinearLayout modeSeg, header, restSection, settings, footer;
    private TextView modeCaption;
    private CarToggle reduce;
    private LinearLayout reduceRow;

    ChangePage(CarShell shell) { super(shell); }

    @Override View build() {
        FrameLayout root = new FrameLayout(a);
        LinearLayout col = frame(root);
        header = k.header(col);
        TextView back = k.backButton(header, () -> a.showScreen(MainActivity.Screen.HOME));
        // The title tucks over the back button's empty right edge (not touchable), where the artboard puts it.
        CarKit.margins(k.title(header, "시작 전에 바꾸기", 40), -k.px(24), 0, 0, 0);
        k.flex(header);
        k.caption(header, "바꾼 내용은 이번 쉼에만 써요");

        LinearLayout main = k.column(col);
        main.setLayoutParams(CarKit.lp(-1, 0, 1));
        main.setPadding(0, k.px(44), 0, 0);

        restSection = k.column(main);
        TextView h = k.text(restSection, "오늘의 쉼", 34, t.ink, CarKit.W600);
        h.setLetterSpacing(-0.02f);
        LinearLayout grid = k.row(restSection);
        CarKit.margins(grid, 0, k.px(20), 0, 0);
        for (int i = 0; i < CarCopy.REST_IDS.length; i++) {
            final String id = CarCopy.REST_IDS[i];
            LinearLayout tile = k.column(grid);
            tile.setPadding(k.px(32), k.px(30), k.px(32), k.px(30));
            tile.setMinimumHeight(k.px(230));
            LinearLayout.LayoutParams tp = CarKit.lp(0, -2, 1);
            if (i > 0) tp.leftMargin = k.px(20);
            tile.setLayoutParams(tp);
            tile.setClickable(true);
            tile.setSoundEffectsEnabled(false);
            tile.setOnClickListener(v -> { shell.draftPreset = id; update(); });
            k.press(tile);
            LinearLayout top = k.row(tile);
            top.setLayoutParams(CarKit.lp(-1, -2));
            k.glass(top, CarCopy.restIcon(id), 72);
            k.flex(top);
            FrameLayout check = new FrameLayout(a);
            check.setBackground(CarKit.round(t.primary, k.px(12), 0, 0));
            ImageView mark = new ImageView(a);
            mark.setImageDrawable(new CarIcon(CarIcon.CHECK, t.onPrimary, 2f));
            check.addView(mark, new FrameLayout.LayoutParams(k.px(24), k.px(24), Gravity.CENTER));
            top.addView(check, CarKit.lp(k.px(40), k.px(40)));
            checks.add(check);
            TextView name = k.text(tile, CarCopy.restName(id), 32, t.ink, CarKit.W600);
            name.setLetterSpacing(-0.03f);
            CarKit.margins(name, 0, k.px(26), 0, 0);
            TextView sub = k.text(tile, CarCopy.restValues(id), 22, t.ink2, CarKit.W400);
            CarKit.margins(sub, 0, k.px(8), 0, 0);
            tiles.add(tile);
        }

        settings = k.row(main);
        settings.setGravity(Gravity.TOP);
        CarKit.margins(settings, 0, k.px(44), 0, 0);
        LinearLayout modeCol = column3(settings, 0);
        k.text(modeCol, "쉬는 방식", 34, t.ink, CarKit.W600).setLetterSpacing(-0.02f);
        modeSeg = k.seg(modeCol, new String[][]{{"guided", "안내에 맞춰"}, {"natural", "자유롭게"}}, id -> {
            shell.draftNatural = "natural".equals(id); update();
        });
        CarKit.margins(modeSeg, 0, k.px(20), 0, 0);
        modeCaption = k.caption(modeCol, "");
        CarKit.margins(modeCaption, 0, k.px(20), 0, 0);

        LinearLayout moodCol = column3(settings, 44);
        k.text(moodCol, "화면 분위기", 34, t.ink, CarKit.W600).setLetterSpacing(-0.02f);
        LinearLayout spheres = k.row(moodCol);
        CarKit.margins(spheres, 0, k.px(20), 0, 0);
        int[] images = {R.drawable.pitin_sphere_pearl, R.drawable.pitin_sphere_graphite, R.drawable.pitin_sphere_umber};
        for (int i = 0; i < CarTheme.ALL.length; i++) {
            final CarTheme mood = CarTheme.ALL[i];
            LinearLayout item = k.column(spheres);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setClickable(true);
            item.setSoundEffectsEnabled(false);
            item.setContentDescription(mood.label);
            LinearLayout.LayoutParams ip = CarKit.lp(-2, -2);
            if (i > 0) ip.leftMargin = k.px(24);
            item.setLayoutParams(ip);
            FrameLayout ring = new FrameLayout(a);
            ring.setPadding(k.px(5), k.px(5), k.px(5), k.px(5));
            ImageView img = new ImageView(a);
            img.setImageResource(images[i]);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setClipToOutline(true);
            img.setBackground(CarKit.round(0, k.px(40), 0, 0));
            ring.addView(img, new FrameLayout.LayoutParams(k.px(80), k.px(80)));
            item.addView(ring, CarKit.lp(k.px(90), k.px(90)));
            rings.add(ring);
            TextView label = k.text(item, mood.label, 20, t.ink, CarKit.W400);
            CarKit.margins(label, 0, k.px(10), 0, 0);
            item.setOnClickListener(v -> shell.changeMood(mood.id));
            k.press(item);
        }

        LinearLayout soundCol = column3(settings, 44);
        k.text(soundCol, "소리", 34, t.ink, CarKit.W600).setLetterSpacing(-0.02f);
        LinearLayout soundSeg = k.seg(soundCol, new String[][]{{"off", "끔"}, {"soon", "잔잔한 소리"}}, id -> { });
        k.segState(soundSeg, "off");
        View soon = soundSeg.getChildAt(1);
        soon.setEnabled(false);
        soon.setAlpha(.45f);
        CarKit.margins(soundSeg, 0, k.px(20), 0, 0);
        TextView sc = k.caption(soundCol, "소리는 곧 고를 수 있어요");
        CarKit.margins(sc, 0, k.px(20), 0, 0);

        footer = k.footer(col);
        // Label and switch are one 88px touch row; screen readers hear one switch ("동작 줄이기, 꺼짐").
        reduceRow = k.row(footer);
        reduceRow.setMinimumHeight(k.px(88));
        reduceRow.setClickable(true);
        reduceRow.setFocusable(true);
        reduceRow.setOnClickListener(v -> toggleReduce());
        k.press(reduceRow);
        k.text(reduceRow, "동작 줄이기", 26, t.ink, CarKit.W400).setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        reduce = new CarToggle(a, t, k.unit);
        reduce.setClickable(false);
        reduce.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams rp = CarKit.lp(k.px(80), k.px(48));
        rp.leftMargin = k.px(28);
        reduceRow.addView(reduce, rp);
        k.flex(footer);
        TextView ok = k.primary(footer, "이 설정으로", this::commit);
        ok.setMinWidth(k.px(340));
        return root;
    }

    private LinearLayout column3(LinearLayout parent, float leftGap) {
        LinearLayout c = k.column(parent);
        LinearLayout.LayoutParams p = CarKit.lp(0, -2, 1);
        p.leftMargin = k.px(leftGap);
        c.setLayoutParams(p);
        return c;
    }

    private void toggleReduce() {
        a.appReducedMotion = !a.appReducedMotion;
        a.getSharedPreferences("pitin_vehicle_prefs", 0).edit().putBoolean("reduced_motion", a.appReducedMotion).apply();
        a.render();
    }

    private void commit() {
        a.commitRestChoice(shell.draftPreset, shell.draftNatural);
        a.showScreen(MainActivity.Screen.HOME);
    }

    @Override void onShow(boolean animate) {
        super.onShow(animate);
    }

    @Override void update() {
        for (int i = 0; i < tiles.size(); i++) {
            boolean on = CarCopy.REST_IDS[i].equals(shell.draftPreset);
            GradientDrawable bg = CarKit.round(on ? t.surface2 : t.surface, k.px(32), on ? t.ink : t.line, on ? k.px(2) : Math.max(1, k.px(1)));
            tiles.get(i).setBackground(bg);
            tiles.get(i).setSelected(on);
            tiles.get(i).setContentDescription(CarCopy.restName(CarCopy.REST_IDS[i]) + (on ? ", 선택됨" : ""));
            checks.get(i).setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        }
        k.segState(modeSeg, shell.draftNatural ? "natural" : "guided");
        modeCaption.setText(shell.draftNatural ? "박자 안내 없이 머물러요" : "들이쉬기 4초 · 내쉬기 4초");
        for (int i = 0; i < rings.size(); i++) {
            boolean on = CarTheme.ALL[i].id.equals(shell.theme.id);
            rings.get(i).setBackground(on ? CarKit.round(0, k.px(45), t.ink, k.px(2)) : null);
            ((View) rings.get(i).getParent()).setContentDescription(CarTheme.ALL[i].label + (on ? ", 선택됨" : ""));
        }
        reduce.setChecked(a.appReducedMotion);
        reduceRow.setContentDescription("동작 줄이기");
        reduceRow.setStateDescription(a.appReducedMotion ? "켜짐" : "꺼짐");
        reduceRow.setAccessibilityDelegate(new android.view.View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(android.view.View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Switch.class.getName());
                info.setCheckable(true);
                info.setChecked(a.appReducedMotion);
            }
        });
    }

    @Override List<View> cascadeTargets() { return list(bar(header), restSection, settings, bar(footer)); }
}
