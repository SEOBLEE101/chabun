package ai.pitin.vehicle;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.EnumMap;

/**
 * Hosts the redesigned car screens next to the untouched rest screen. The legacy MainScreen root
 * (rest in progress · paused · finish) is shown only for {@link MainActivity.Screen#SESSION};
 * every other screen is a {@link CarPage} in one Activity, so slides, waves and the intro never
 * rebuild the whole window.
 */
final class CarShell {
    static final String PREFS = "pitin_vehicle_prefs";
    final MainActivity a;
    CarTheme theme;
    CarKit kit;
    float unit;
    private FrameLayout stage, shellRoot, layer;
    /** Shown for SESSION: the protected MainScreen root, with the Pearl/Umber rest scene (rest and finish) above it. */
    private FrameLayout sessionHost;
    private RestSceneView scene;
    private boolean sceneFadingIn;
    private View legacy;
    private WaveOverlay wave;
    private TextView homePill;
    private LinearLayout devSheet;
    private final EnumMap<MainActivity.Screen, CarPage> pages = new EnumMap<>(MainActivity.Screen.class);
    private MainActivity.Screen shown;
    private boolean legacyShown;
    private boolean themeBusy;

    // Shared, mood-independent UI state (survives a theme rebuild).
    String draftPreset = "quiet_rest";
    boolean draftNatural;
    String pairPreview;
    boolean pairCompareStep, pairWaitingHint;
    CabinSettings cabinSaved = new CabinSettings(), cabinDraft = new CabinSettings();
    boolean cabinJustSaved, cabinDriver = true;
    String cabinTab = "seat";
    long cabinRereadAt, cabinOpenedAt = System.currentTimeMillis();
    /** ‘지금 차’ as the car last reported it (step key → raw value), and when. Empty until read. */
    final java.util.Map<String, Integer> cabinNow = new java.util.HashMap<>();
    long cabinNowAt;
    /** ‘지금 차에 맞추기’: null when idle, the progress line while running, then the result line. */
    String cabinApplyNote;
    boolean cabinApplying;
    private String pillText;
    private MainActivity.Screen pillScreen;

    CarShell(MainActivity activity) {
        this.a = activity;
        SharedPreferences p = prefs();
        theme = CarTheme.of(p.getString("car_mood", CarTheme.GRAPHITE_ID));
        PresetLink.get(a).themeChanged(theme.id);
        cabinSaved = CabinSettings.decode(p.getString("cabin_saved", null));
        cabinDraft = cabinSaved.copy();
    }

    SharedPreferences prefs() { return a.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Re-parents the MainScreen root (already set as content view) under one stage with the shell. */
    void install() {
        ViewGroup content = a.findViewById(android.R.id.content);
        View newLegacy = content.getChildAt(0);
        content.removeView(newLegacy);
        android.content.res.Configuration cfg = a.getResources().getConfiguration();
        float density = a.getResources().getDisplayMetrics().density;
        float fit = Math.min(cfg.screenWidthDp / 1680f, cfg.screenHeightDp / 1200f);
        float newUnit = density * Math.max(.5f, Math.min(1.5f, fit));
        if (stage != null && Math.abs(newUnit - unit) < .02f) {
            // Same car-scale (e.g. the TaskView settling its bounds): swap only the rebuilt rest-screen root,
            // so an intro, a draft or a slide in progress is not thrown away.
            if (legacy != null) sessionHost.removeView(legacy);
            legacy = newLegacy;
            sessionHost.addView(legacy, 0, new FrameLayout.LayoutParams(-1, -1));
            FinishLayer.install(a, this);
            sessionHost.setVisibility(legacyShown ? View.VISIBLE : View.GONE);
            a.setContentView(stage);
            syncScene(true);
            return;
        }
        legacy = newLegacy;
        FinishLayer.install(a, this);
        unit = newUnit;
        if (scene != null) { scene.release(); scene = null; }
        stage = new FrameLayout(a);
        sessionHost = new FrameLayout(a);
        sessionHost.addView(legacy, new FrameLayout.LayoutParams(-1, -1));
        stage.addView(sessionHost, new FrameLayout.LayoutParams(-1, -1));
        shellRoot = new FrameLayout(a) {
            @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && pillText != null) dismissPillSoon();
                return false;
            }
        };
        stage.addView(shellRoot, new FrameLayout.LayoutParams(-1, -1));
        layer = new FrameLayout(a);
        shellRoot.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        homePill = new TextView(a);
        shellRoot.addView(homePill, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        wave = new WaveOverlay(a, unit);
        shellRoot.addView(wave, new FrameLayout.LayoutParams(-1, -1));
        applyTheme();
        a.setContentView(stage);
        sessionHost.setVisibility(View.GONE);
        legacyShown = false;
        // The window can settle its car bounds right after launch; an intro in progress restarts on the new scale.
        if (introActive) stage.post(() -> playIntro(introFreeze));
    }

    private boolean introActive;
    private long introFreeze = -1;

    private void applyTheme() {
        kit = new CarKit(a, theme, unit);
        shellRoot.setBackground(CarTheme.GRAPHITE_ID.equals(theme.id) ? new GraphiteBackground(a) : new MoodGround(a, theme));
        for (CarPage p : pages.values()) if (p.view != null && p.view.getParent() != null) ((ViewGroup) p.view.getParent()).removeView(p.view);
        pages.clear();
        layer.removeAllViews();
        shown = null;
        styleHomePill();
        if (devSheet != null) { shellRoot.removeView(devSheet); devSheet = null; }
        int bar = theme.bg;
        a.getWindow().setStatusBarColor(bar);
        a.getWindow().setNavigationBarColor(bar);
        // Rest scene: Pearl / Umber only. Graphite keeps the protected screen alone, exactly as before.
        if (scene != null) { scene.animate().cancel(); scene.release(); sessionHost.removeView(scene); scene = null; }
        sceneFadingIn = false;
        if (!CarTheme.GRAPHITE_ID.equals(theme.id)) {
            scene = new RestSceneView(this);
            sessionHost.addView(scene, new FrameLayout.LayoutParams(-1, -1));
        }
        syncScene(true);
    }

    /**
     * Shows the themed rest scene over the protected rest screen while a rest is ACTIVE or PAUSED on SESSION, and
     * on its finish (COMPLETE / ENDED, 마침 화면 테마별); Graphite stays the protected original. {@code immediate}:
     * no fade (the host itself is fading in, or the shell was rebuilt).
     */
    private void syncScene(boolean immediate) {
        if (legacy == null) return;
        if (scene == null) {
            if (legacy.getVisibility() != View.VISIBLE) legacy.setVisibility(View.VISIBLE);
            return;
        }
        final RestSceneView scene = this.scene;
        final View legacy = this.legacy;
        RestSession.State s = a.session.state();
        boolean want = legacyShown && (s == RestSession.State.ACTIVE || s == RestSession.State.PAUSED
                || s == RestSession.State.COMPLETE || s == RestSession.State.ENDED);
        if (!legacyShown) { scene.setWanted(false); return; } // leaving: the host fades out with the scene on top
        boolean instant = immediate || kit.reducedMotion() || !a.foreground;
        if (want) {
            if (scene.getVisibility() != View.VISIBLE) {
                scene.animate().cancel();
                sceneFadingIn = false;
                scene.setAlpha(instant ? 1f : 0f);
                scene.setVisibility(View.VISIBLE);
            }
            if (scene.getAlpha() < 1f && !instant) {
                if (!sceneFadingIn) {
                    sceneFadingIn = true;
                    scene.animate().alpha(1f).setDuration(220).setInterpolator(CarKit.EASE_IOS).withEndAction(() -> {
                        sceneFadingIn = false;
                        if (scene == this.scene && legacy == this.legacy && scene.getAlpha() >= 1f) legacy.setVisibility(View.INVISIBLE);
                    }).start();
                }
            } else {
                if (scene.getAlpha() < 1f) { scene.animate().cancel(); sceneFadingIn = false; scene.setAlpha(1f); }
                legacy.setVisibility(View.INVISIBLE); // covered; also keeps accessibility to one screen
            }
            scene.setWanted(true);
        } else {
            sceneFadingIn = false;
            legacy.setVisibility(View.VISIBLE); // idle (no rest yet): the protected screen as it is
            scene.setWanted(false);
            if (scene.getVisibility() == View.VISIBLE) {
                scene.animate().cancel();
                if (instant) { scene.setVisibility(View.GONE); scene.setAlpha(0f); }
                else scene.animate().alpha(0f).setDuration(220).setInterpolator(CarKit.EASE_IOS)
                        .withEndAction(() -> scene.setVisibility(View.GONE)).start();
            }
        }
    }

    private void styleHomePill() {
        homePill.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, kit.px(26));
        homePill.setTypeface(CarKit.face(CarKit.W600));
        homePill.setTextColor(theme.ink);
        homePill.setIncludeFontPadding(false);
        homePill.setGravity(Gravity.CENTER_VERTICAL);
        homePill.setMinHeight(kit.px(68));
        homePill.setPadding(kit.px(30), 0, kit.px(30), 0);
        homePill.setBackground(CarKit.round(theme.accentSoft, kit.px(34), 0, 0));
        CarIcon icon = new CarIcon(CarIcon.VOICE, theme.ink);
        icon.setBounds(0, 0, kit.px(28), kit.px(28));
        homePill.setCompoundDrawables(icon, null, null, null);
        homePill.setCompoundDrawablePadding(kit.px(14));
        homePill.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        ((FrameLayout.LayoutParams) homePill.getLayoutParams()).topMargin = kit.px(150);
        homePill.setVisibility(View.GONE);
    }

    CarPage page(MainActivity.Screen s) {
        CarPage p = pages.get(s);
        if (p == null) {
            switch (s) {
                case PREPARATION: p = new ChangePage(this); break;
                case BLOCKED: p = new BlockedPage(this); break;
                case CONNECTIONS: p = new PairPage(this); break;
                case FEATURES: p = new FeaturesPage(this); break;
                case CABIN: p = new CabinPage(this); break;
                default: p = new HomePage(this);
            }
            pages.put(s, p);
        }
        return p;
    }

    private static int depth(MainActivity.Screen s) {
        if (s == null || s == MainActivity.Screen.HOME) return 0;
        return s == MainActivity.Screen.CABIN ? 2 : 1;
    }

    /** Called after MainScreen.render on every state change and once a second. */
    void render() {
        if (stage == null) return;
        boolean session = a.screen == MainActivity.Screen.SESSION;
        boolean entering = false;
        if (session != legacyShown) {
            legacyShown = session;
            entering = session;
            crossfade(session ? sessionHost : shellRoot, session ? shellRoot : sessionHost);
        }
        syncScene(entering);
        if (session) return;
        MainActivity.Screen target = a.screen;
        if (target != shown) showPage(target);
        CarPage p = page(target);
        p.update();
        boolean homePillOn = pillText != null && pillScreen == MainActivity.Screen.HOME && target == MainActivity.Screen.HOME
                && !(p instanceof HomePage && ((HomePage) p).introRunning());
        if (homePillOn) homePill.setText(pillText);
        if ((homePill.getVisibility() == View.VISIBLE) != homePillOn) {
            homePill.setVisibility(homePillOn ? View.VISIBLE : View.GONE);
            if (homePillOn && !kit.reducedMotion()) {
                homePill.setAlpha(0f); homePill.setTranslationY(-kit.px(24));
                homePill.animate().alpha(1f).translationY(0).setDuration(560).setInterpolator(CarKit.EASE_IOS).start();
            }
        }
    }

    private void crossfade(View in, View out) {
        boolean reduced = kit.reducedMotion();
        in.animate().cancel(); out.animate().cancel();
        in.setVisibility(View.VISIBLE);
        if (reduced || !a.foreground) { in.setAlpha(1f); out.setAlpha(1f); out.setVisibility(View.GONE); return; }
        in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(220).setInterpolator(CarKit.EASE_IOS).start();
        out.animate().alpha(0f).setDuration(220).setInterpolator(CarKit.EASE_IOS).withEndAction(() -> { out.setVisibility(View.GONE); out.setAlpha(1f); }).start();
    }

    private void showPage(MainActivity.Screen target) {
        CarPage next = page(target);
        View nv = next.view();
        MainActivity.Screen prev = shown;
        View ov = prev == null ? null : page(prev).view;
        shown = target;
        if (target == MainActivity.Screen.CABIN && prev != MainActivity.Screen.CABIN) {
            cabinOpenedAt = System.currentTimeMillis();
            cabinApplyNote = null;
            readCabinNow();
        }
        if (nv.getParent() == null) layer.addView(nv, new FrameLayout.LayoutParams(-1, -1));
        nv.animate().cancel();
        nv.setVisibility(View.VISIBLE);
        next.update();
        boolean animate = ov != null && ov != nv && a.foreground && !kit.reducedMotion() && !themeBusy;
        if (ov == null || ov == nv) {
            nv.setTranslationX(0); nv.setAlpha(1f);
            next.onShow(!themeBusy || true);
            return;
        }
        float w = Math.max(1, layer.getWidth());
        if (!animate) {
            if (kit.reducedMotion() && a.foreground) {
                nv.setAlpha(0f); nv.setTranslationX(0);
                nv.animate().alpha(1f).setDuration(200).start();
                ov.animate().alpha(0f).setDuration(200).withEndAction(() -> detach(ov)).start();
            } else { nv.setTranslationX(0); nv.setAlpha(1f); detach(ov); }
            next.onShow(false);
            return;
        }
        boolean forward = depth(target) >= depth(prev);
        nv.bringToFront();
        if (!forward) ov.bringToFront();
        if (forward) {
            nv.setTranslationX(w); nv.setAlpha(1f);
            nv.animate().translationX(0).setDuration(560).setInterpolator(CarKit.EASE_IOS).start();
            ov.animate().translationX(-.28f * w).alpha(.55f).setDuration(560).setInterpolator(CarKit.EASE_IOS)
                    .withEndAction(() -> detach(ov)).start();
        } else {
            nv.setTranslationX(-.28f * w); nv.setAlpha(.55f);
            nv.animate().translationX(0).alpha(1f).setDuration(560).setInterpolator(CarKit.EASE_IOS).start();
            ov.animate().translationX(w).setDuration(560).setInterpolator(CarKit.EASE_IOS).withEndAction(() -> detach(ov)).start();
        }
        next.onShow(true);
    }

    private void detach(View v) {
        if (v == null || (shown != null && pages.get(shown) != null && pages.get(shown).view == v)) return;
        v.animate().cancel();
        v.setTranslationX(0); v.setAlpha(1f);
        if (v.getParent() == layer) layer.removeView(v);
    }

    // ── mood (화면 분위기) with the wave ─────────────────────
    void changeMood(String id) {
        CarTheme next = CarTheme.of(id);
        if (next == theme || themeBusy) return;
        prefs().edit().putString("car_mood", next.id).apply();
        if (kit.reducedMotion() || !a.foreground) {
            theme = next; applyTheme(); a.render();
            PresetLink.get(a).themeChanged(theme.id); // report the newly rendered mood promptly
            View v = page(a.screen).view;
            if (v != null && a.foreground) { v.setAlpha(0f); v.animate().alpha(1f).setDuration(200).start(); }
            return;
        }
        themeBusy = true;
        wave.bringToFront();
        wave.run(next.waveFill, next.waveGlint, () -> {
            theme = next;
            applyTheme();
            PresetLink.get(a).themeChanged(theme.id); // the wave has covered the old mood: report the new one
            a.render();
            CarPage p = page(a.screen);
            themeBusy = false;
            if (p.view != null) kit.cascade(p.cascadeTargets(), 0);
            wave.bringToFront();
        });
    }

    /** Debug/screenshot: show the wave frozen at {@code ms} toward {@code id}. */
    void freezeWave(String id, float ms) {
        CarTheme next = CarTheme.of(id);
        wave.bringToFront();
        wave.freezeAt(next.waveFill, next.waveGlint, ms);
    }

    // ── intro ───────────────────────────────────────────────
    void playIntro(long freezeAtMs) {
        introActive = true;
        introFreeze = freezeAtMs;
        a.screen = MainActivity.Screen.HOME;
        a.render();
        HomePage home = (HomePage) page(MainActivity.Screen.HOME);
        if (kit.reducedMotion()) { introActive = false; return; } // reduced motion: straight to 시작
        home.playIntro(freezeAtMs);
        a.render();
    }

    void onIntroFinished() { introActive = false; a.render(); }

    // ── Gleo pill ───────────────────────────────────────────
    void showPill(String text, MainActivity.Screen screen) {
        pillText = text;
        pillScreen = screen;
        a.render();
    }

    String pillFor(MainActivity.Screen screen) { return pillScreen == screen ? pillText : null; }

    private void dismissPillSoon() {
        shellRoot.post(() -> { pillText = null; pillScreen = null; a.render(); });
    }

    // ── cabin ───────────────────────────────────────────────
    void saveCabin() {
        cabinSaved = cabinDraft.copy();
        cabinJustSaved = true;
        prefs().edit().putString("cabin_saved", cabinSaved.encode()).apply();
        a.render();
    }

    /**
     * ‘지금 차에 맞추기’: sends the plan the screen shows now (the same plan a rest would use, from the draft), parked
     * only, and shows 보내는 중 → the result. A direct change is not owned by a rest and is not put back later.
     */
    void applyCabinNow() {
        CabinActuator cabin = a.environment.cabin();
        if (cabin == null || cabinApplying) return;
        java.util.List<CabinControl.Step> plan = CabinControl.plan(cabinDraft, a.selectedPreset, a.sharedClimate, a.sharedCabin);
        if (plan.isEmpty()) { cabinApplyNote = "바꿀 값이 없어요 · 이 쉼은 차 안을 바꾸지 않아요"; a.render(); return; }
        String blocked = a.cabinBlocked();
        if (blocked != null) { cabinApplyNote = blocked; a.render(); return; }
        cabinApplying = true;
        cabinApplyNote = "차에 보내는 중 · 0/" + plan.size();
        cabin.apply(plan, null, 0, new CabinActuator.Listener() {
            @Override public void onProgress(int finished, int total) {
                cabinApplyNote = "차에 보내는 중 · " + finished + "/" + total;
                a.render();
            }
            @Override public void onDone(CabinActuator.Result result) {
                cabinApplying = false;
                cabinApplyNote = result.brief() + " · " + CarCopy.clock(System.currentTimeMillis());
                readCabinNow();
                a.render();
            }
        });
        a.render();
    }

    /** Reads ‘지금 차’ (seat heat/vent, set temperature and fan, light on/brightness) from the car or the demo car. */
    void readCabinNow() {
        CabinActuator cabin = a.environment.cabin();
        if (cabin == null) return;
        CabinControl.Item[] items = {CabinControl.Item.SEAT_HEAT, CabinControl.Item.SEAT_VENT, CabinControl.Item.TEMPERATURE,
                CabinControl.Item.FAN, CabinControl.Item.CLIMATE_POWER};
        java.util.List<CabinControl.Step> probes = new java.util.ArrayList<>();
        for (boolean driver : new boolean[]{true, false})
            for (CabinControl.Item item : items) probes.add(new CabinControl.Step(item, driver, 0));
        probes.add(new CabinControl.Step(CabinControl.Item.LIGHT_SWITCH, true, 0));
        probes.add(new CabinControl.Step(CabinControl.Item.LIGHT_BRIGHTNESS, true, 0));
        for (CabinControl.Step p : probes) cabin.peek(p, value -> {
            if (value == null) cabinNow.remove(p.key()); else cabinNow.put(p.key(), value);
            cabinNowAt = System.currentTimeMillis();
            a.render();
        });
    }

    // ── demo developer sheet (long-press the wordmark; demo debug builds only) ──
    void openDevSheet() {
        if (!(BuildConfig.SIMULATED_VEHICLE && BuildConfig.DEBUG)) return;
        if (devSheet != null) { shellRoot.removeView(devSheet); devSheet = null; return; }
        CarKit k = kit;
        devSheet = k.card(null);
        devSheet.setElevation(k.px(24));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(k.px(1100), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = k.px(190);
        shellRoot.addView(devSheet, lp);
        LinearLayout top = k.row(devSheet);
        k.text(top, "개발자 설정 · 데모", 30, theme.ink, CarKit.W600);
        k.flex(top);
        k.ghost(top, "닫기", null, this::openDevSheet);
        k.caption(devSheet, "합성 신호예요. 실제 차의 주차·통화 확인이 아니에요.");
        LinearLayout chips = k.row(devSheet);
        CarKit.margins(chips, 0, k.px(20), 0, 0);
        String[][] items = {{"주차", "parked"}, {"주행", "driving"}, {"통화", "call"}, {"미확인", "unknown"}, {"화면 제한", "restricted"}};
        for (String[] item : items) {
            TextView c = k.chip(chips, item[0], () -> { a.environment.simulate(item[1]); a.render(); });
            CarKit.margins(c, 0, 0, k.px(12), 0);
        }
        TextView label = k.caption(devSheet, "중계 서버 (디버그)");
        CarKit.margins(label, 0, k.px(24), 0, 0);
        EditText field = new EditText(a);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setText(a.originInput.getText());
        field.setTextColor(theme.ink);
        field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, k.px(24));
        field.setBackground(CarKit.round(theme.bg2, k.px(20), theme.line2, 1));
        field.setPadding(k.px(20), k.px(12), k.px(20), k.px(12));
        devSheet.addView(field, CarKit.lp(-1, -2));
        TextView apply = k.secondary(devSheet, "주소 쓰기", () -> a.originInput.setText(field.getText()));
        CarKit.margins(apply, 0, k.px(16), 0, 0);
    }

    // ── debug extras for review screenshots (demo debug builds only; no vehicle or network effect) ──
    void handleDebugExtras(Intent intent) {
        if (intent == null || !(BuildConfig.SIMULATED_VEHICLE && BuildConfig.DEBUG)) return;
        String mood = intent.getStringExtra("reviewMood");
        if (mood != null && !CarTheme.of(mood).id.equals(theme.id)) {
            theme = CarTheme.of(mood);
            prefs().edit().putString("car_mood", theme.id).apply();
            applyTheme();
            PresetLink.get(a).themeChanged(theme.id);
        }
        // Isolated testing (adb reverse): point the relay, and preset sync on :9447, at a local host.
        String relay = PresetSyncPolicy.reviewRelayOrigin(intent.getStringExtra("reviewRelayOrigin"));
        if (relay != null) {
            if (a.originInput != null) a.originInput.setText(relay);
            PresetLink.get(a).useRelayOrigin(relay);
        }
        if (intent.hasExtra("reviewRestSceneLog")) RestSceneView.reviewLog = intent.getBooleanExtra("reviewRestSceneLog", false);
        String scenario = intent.getStringExtra("demoScenario");
        if (scenario != null) a.environment.simulate(scenario);
        String screen = intent.getStringExtra("reviewScreen");
        String pair = intent.getStringExtra("reviewPair");
        if (pair != null) pairPreview = "code".equals(pair) || "compare".equals(pair) ? pair : null;
        String tab = intent.getStringExtra("reviewCabinTab");
        if ("seat".equals(tab) || "climate".equals(tab) || "light".equals(tab)) cabinTab = tab;
        if (intent.getBooleanExtra("reviewCabinSample", false)) {
            CabinSettings s = new CabinSettings();
            s.driverSeat.comfort = "heat"; s.driverSeat.level = 2; s.passengerSeat.position = "manual";
            s.driverClimate.mode = "custom"; s.driverClimate.tempTenths = 235; s.driverClimate.fan = "low"; s.driverClimate.dir = "upper"; s.driverClimate.ac = "on";
            s.light.mode = "custom"; s.light.color = "sage"; s.light.bright = 45;
            cabinDraft = s;
        }
        if (screen != null) {
            try { a.showScreen(MainActivity.Screen.valueOf(screen)); } catch (IllegalArgumentException ignored) { }
        }
        String freeze = intent.getStringExtra("reviewWaveTo");
        if (freeze != null) freezeWave(freeze, intent.getFloatExtra("reviewWaveMs", 600f));
        if (intent.getBooleanExtra("reviewWaveHide", false)) wave.hide();
        a.render();
    }

    /** Pearl / Umber ground: the artboard radial gradient with the same faint grain as Graphite. */
    static final class MoodGround extends Drawable {
        private final Paint ground = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grain = new Paint();
        private final CarTheme theme;

        MoodGround(Context context, CarTheme theme) {
            this.theme = theme;
            Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.graphite_grain);
            if (bitmap != null) grain.setShader(new BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            grain.setAlpha(theme.dark ? 9 : 5);
        }

        @Override protected void onBoundsChange(Rect b) {
            ground.setShader(new RadialGradient(b.width() * theme.groundX, b.height() * theme.groundY,
                    Math.max(1, Math.max(b.width(), b.height()) * .85f), theme.ground, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
        }

        @Override public void draw(Canvas canvas) {
            canvas.drawRect(getBounds(), ground);
            if (grain.getShader() != null) canvas.drawRect(getBounds(), grain);
        }

        @Override public void setAlpha(int alpha) { ground.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter f) { ground.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
