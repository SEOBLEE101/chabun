package ai.pitin.vehicle;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * 이 차에서 쓸 기능 (car-features, L01): 지금 값 / 이번 쉼 값 / 상태 for climate, fan, seat, light, sound.
 * A build without a cabin actuator stays read-only (‘직접 맞춰 주세요 · 지원 확인 전’); with one (demo pretend car,
 * pleos) the rows show the rest's real plan and what the car reported.
 */
final class FeaturesPage extends CarPage {
    private static final String[] KEYS = {"temp", "fan", "seat", "light", "sound"};
    private static final String[] NAMES = {"공조 온도", "바람", "시트 통풍·열선", "실내 조명", "소리"};
    private static final String[] ICONS = {CarIcon.TEMP, CarIcon.FAN, CarIcon.SEAT, CarIcon.LIGHT, CarIcon.SOUND_OFF};
    private final TextView[] now = new TextView[5], want = new TextView[5], state = new TextView[5], note = new TextView[5];
    private LinearLayout header, heads, card, footer;
    private TextView headNote;

    FeaturesPage(CarShell shell) { super(shell); }

    @Override View build() {
        FrameLayout root = new FrameLayout(a);
        LinearLayout col = frame(root);
        header = k.header(col);
        TextView back = k.backButton(header, () -> a.showScreen(MainActivity.Screen.HOME));
        // The title tucks over the back button's empty right edge (not touchable), where the artboard puts it.
        CarKit.margins(k.title(header, "이 차에서 쓸 기능", 40), -k.px(24), 0, 0, 0);
        k.flex(header);
        headNote = k.caption(header, "");

        LinearLayout main = k.column(col);
        main.setLayoutParams(CarKit.lp(-1, 0, 1));
        main.setPadding(0, k.px(40), 0, 0);
        heads = k.row(main);
        heads.setPadding(k.px(40), 0, k.px(40), 0);
        String[] hs = {"기능", "지금", "이번 쉼", "상태"};
        float[] weights = {1.3f, 1f, 1f, 1.4f};
        for (int i = 0; i < 4; i++) {
            TextView h = k.overline(heads, hs[i], 18);
            LinearLayout.LayoutParams p = CarKit.lp(0, -2, weights[i]);
            if (i > 0) p.leftMargin = k.px(24);
            h.setLayoutParams(p);
        }
        heads.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        card = k.card(main);
        card.setPadding(k.px(40), k.px(8), k.px(40), k.px(8));
        CarKit.margins(card, 0, k.px(28), 0, 0);
        for (int r = 0; r < KEYS.length; r++) {
            if (r > 0) k.rule(card, t.line);
            LinearLayout row = k.row(card);
            row.setMinimumHeight(k.px(116));
            row.setLayoutParams(CarKit.lp(-1, -2));
            LinearLayout name = k.row(row);
            name.setLayoutParams(CarKit.lp(0, -2, weights[0]));
            k.glass(name, ICONS[r], 72);
            TextView nt = k.text(name, NAMES[r], 28, t.ink, CarKit.W600);
            CarKit.margins(nt, k.px(22), 0, 0, 0);
            now[r] = k.text(row, "", 28, t.ink, CarKit.W400);
            want[r] = k.text(row, "", 28, t.ink, CarKit.W400);
            LinearLayout st = k.column(row);
            state[r] = k.pill(st, "", t.bg2);
            st.setLayoutParams(CarKit.lp(0, -2, weights[3]));
            ((LinearLayout.LayoutParams) state[r].getLayoutParams()).width = -2;
            note[r] = k.text(st, "", 20, t.ink3, CarKit.W400);
            CarKit.margins(note[r], 0, k.px(6), 0, 0);
            LinearLayout.LayoutParams np = CarKit.lp(0, -2, weights[1]); np.leftMargin = k.px(24); now[r].setLayoutParams(np);
            LinearLayout.LayoutParams wp = CarKit.lp(0, -2, weights[2]); wp.leftMargin = k.px(24); want[r].setLayoutParams(wp);
            ((LinearLayout.LayoutParams) st.getLayoutParams()).leftMargin = k.px(24);
        }

        footer = k.footer(col);
        TextView cap = k.caption(footer, "요청한 것과 실제로 바뀐 것을 따로 보여줘요. 손으로 바꾼 값은 다시 덮어쓰지 않아요.");
        cap.setLayoutParams(CarKit.lp(0, -2, 1));
        k.secondary(footer, "차 안 설정", () -> a.showScreen(MainActivity.Screen.CABIN));
        k.space(footer, 20);
        TextView ok = k.primary(footer, "확인", () -> a.showScreen(MainActivity.Screen.HOME));
        ok.setMinWidth(k.px(300));
        return root;
    }

    @Override void update() {
        if (a.environment.cabin() != null) { updateControl(); return; }
        boolean demo = BuildConfig.SIMULATED_VEHICLE;
        headNote.setText(demo ? "지금은 차 상태를 읽기만 해요 · 데모 값" : "지금은 차 상태를 읽기만 해요");
        CabinSettings saved = shell.cabinSaved;
        // 데모 값은 차 안 설정의 '지금 차' 카드(CabinPage)와 같게 둔다. 설정 온도와 실내 온도는 섞지 않는다
        String[] nows = demo ? new String[]{"설정 24.0° · 실내 26.5°", "읽을 수 없어요", "열선 1단계", "켜짐 · 밝기 60%", "끔"}
                : new String[]{"읽을 수 없어요", "읽을 수 없어요", "읽을 수 없어요", "읽을 수 없어요", "끔"};
        boolean customClimate = "custom".equals(saved.driverClimate.mode);
        String[] wants = {
                customClimate ? CabinSettings.temp(saved.driverClimate.tempTenths) : CarCopy.restTemp(a.selectedPreset),
                customClimate ? CabinSettings.fanLabel(saved.driverClimate.fan) : CarCopy.restFan(a.selectedPreset),
                CabinSettings.seatText(saved.driverSeat),
                CabinSettings.lightText(saved.light),
                "끔"};
        String[] states = {"직접 맞춰 주세요", "직접 맞춰 주세요", "지원 확인 전", "지원 확인 전", "앱 소리만"};
        String[] notes = {"지금은 읽기만 해요", "지원 확인 전", "이 차의 옵션을 확인해야 해요", "차 설정에서 직접", "음악·내비 소리는 건드리지 않아요"};
        for (int r = 0; r < KEYS.length; r++) {
            now[r].setText(nows[r]);
            now[r].setTextColor("읽을 수 없어요".equals(nows[r]) ? t.ink3 : t.ink);
            want[r].setText(wants[r]);
            state[r].setText(states[r]);
            note[r].setText(notes[r]);
        }
    }

    /** A build that changes the cabin: ‘이번 쉼’ is the rest's real plan, ‘지금’ what the car reported. */
    private void updateControl() {
        boolean demo = BuildConfig.SIMULATED_VEHICLE;
        String blocked = a.cabinBlocked();
        headNote.setText(demo ? "데모 · 화면 속 차에만 맞춰요" : "주차 중에만 맞추고, 쉼을 마치면 돌려요");
        List<CabinControl.Step> plan = a.restPlan();
        java.util.Map<String, Integer> car = shell.cabinNow;
        Integer temp = car.get("TEMPERATURE:driver"), fan = car.get("FAN:driver"), power = car.get("CLIMATE_POWER:driver");
        Integer heat = car.get("SEAT_HEAT:driver"), vent = car.get("SEAT_VENT:driver");
        Integer light = car.get("LIGHT_SWITCH"), bright = car.get("LIGHT_BRIGHTNESS");
        String[] nows = {
                temp != null ? CabinSettings.temp(temp) : "읽을 수 없어요",
                Integer.valueOf(0).equals(power) ? "꺼짐" : fan != null ? fan + "단" : "읽을 수 없어요",
                heat != null && heat > 0 ? "열선 " + heat + "단계" : vent != null && vent > 0 ? "통풍 " + vent + "단계"
                        : heat != null || vent != null ? "꺼짐" : "읽을 수 없어요",
                light == null ? "읽을 수 없어요" : light == 0 ? "꺼짐" : "켜짐" + (bright == null ? "" : " · 밝기 " + bright + "%"),
                "끔"};
        String[] wants = {groupWords(plan, "temp"), groupWords(plan, "fan"), groupWords(plan, "seat"), groupWords(plan, "light"), "끔"};
        String[] notes = {"쉼을 마치면 돌려요", "쉼을 마치면 돌려요", "이 차가 지원하는 단계 안에서", "앰비언트 조명만", "음악·내비 소리는 건드리지 않아요"};
        for (int r = 0; r < KEYS.length; r++) {
            boolean changes = r < 4 && !"그대로".equals(wants[r]);
            now[r].setText(nows[r]);
            now[r].setTextColor("읽을 수 없어요".equals(nows[r]) ? t.ink3 : t.ink);
            want[r].setText(wants[r]);
            state[r].setText(r == 4 ? "앱 소리만" : !changes ? "그대로 둬요" : blocked != null ? "주차하면 맞춰요" : "시작하면 맞춰요");
            note[r].setText(notes[r]);
        }
    }

    /** One row of the plan in on-screen words: ‘24.0°’, ‘약하게’, ‘통풍 2단계’, ‘올리브 · 40%’, or ‘그대로’. */
    private static String groupWords(List<CabinControl.Step> plan, String row) {
        java.util.List<CabinControl.Step> mine = new java.util.ArrayList<>();
        for (CabinControl.Step s : plan) {
            boolean match;
            switch (row) {
                case "temp": match = s.item == CabinControl.Item.TEMPERATURE && s.driver; break;
                case "fan": match = (s.item == CabinControl.Item.FAN || s.item == CabinControl.Item.CLIMATE_AUTO && s.value == 1) && s.driver; break;
                case "seat": match = (s.item == CabinControl.Item.SEAT_HEAT || s.item == CabinControl.Item.SEAT_VENT) && s.driver; break;
                default: match = s.item == CabinControl.Item.LIGHT_SWITCH || s.item == CabinControl.Item.LIGHT_BRIGHTNESS
                        || s.item == CabinControl.Item.LIGHT_COLOR;
            }
            if (match) mine.add(s);
        }
        if (mine.isEmpty()) return "그대로";
        return CabinControl.describe(mine).replaceFirst("^(에어컨|시트|조명) ", "").replace("바람 ", "");
    }

    @Override List<View> cascadeTargets() { return list(bar(header), heads, card, bar(footer)); }
}
