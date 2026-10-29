package ai.pitin.vehicle;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Pearl / Umber rest scene (쉬는 화면 9장면, hybrid, 2026-10-28) and its finish (마침 화면 테마별, 2026-10-28). A
 * presentational layer that {@link CarShell} shows above the protected Graphite rest screen, only for those two
 * moods, while a rest is ACTIVE or PAUSED and on its finish (COMPLETE / ENDED).
 *
 * <p>It owns no rest logic. Every frame (and on every render) it copies the words, timer and button states from the
 * protected views that MainScreen / FinishLayer keep updating, the art's breath is read from
 * {@link RestSession#expansion} — the same single clock as the Graphite point — and its buttons and feeling chips
 * press the protected controls (일시정지 / 계속하기 / 마치기 / 준비 확인 / 다시 3분 쉬기 / 닫기 / 좋았어요 …).
 *
 * <p>The art is a muted Grok Imagine loop in res/raw (see tools/prepare_rest_scenes.py) whose four edges are feathered
 * into one flat colour, the theme's bg token; this view's ground is that same flat colour as this device decodes it
 * (no radial gradient, no grain), so the video rectangle never shows. Guided: scale 1.00→1.04 on 들이쉬기 and back on 내쉬기 (RestSession's quintic
 * ease). Natural: no breath, the loop plays. Paused: video paused on its frame, scale held, a soft static dim.
 * Reduced motion: expansion is fixed at 0.5 by the contract (scale 1.02) and the loop holds a still frame.
 *
 * <p>Finish: the loop eases to a stop over 2.4 s (playback speed ramp, then pause on that frame), the scale eases
 * back to 1.00 and, on COMPLETE only, a soft halo blooms once behind the art (in 1.2 s to a gentle peak, then settles
 * lower) — after 3 s nothing moves. ENDED: the same ease to a stop, no bloom. Reduced motion: straight to the settled
 * state. Without a live player (e.g. the app came back at the finish) a still of the same artwork
 * (res/drawable-nodpi/rest_*_still.webp) is shown with the settled halo. The player is released when the finish is
 * left and is never restarted by the finish itself.
 */
final class RestSceneView extends FrameLayout implements TextureView.SurfaceTextureListener {
    private static final String TAG = "PitInRestScene";
    /** Breath amplitude: scale = 1 + BREATH × expansion. */
    static final float BREATH = .04f;
    /** Must match tools/prepare_rest_scenes.py (crop size and flat colour = CarTheme bg). */
    private static final int PEARL_W = 768, PEARL_H = 544, UMBER_W = 768, UMBER_H = 544;
    private static final String PAUSE_ICON = "M8.5 5.5v13M15.5 5.5v13";

    // ── finish choreography (ms) ─────────────────────────────
    /** The loop slows to a stop and the scale returns to 1.00 over this time. */
    static final long STOP_MS = 2400;
    /** Halo: fade in to its peak, then settle lower; after FINISH_MS nothing moves. */
    static final long BLOOM_IN_MS = 1200, BLOOM_SETTLE_MS = 1800, FINISH_MS = BLOOM_IN_MS + BLOOM_SETTLE_MS;
    /** A paused rest that is ended lifts its soft dim over this time. */
    static final long DIM_OUT_MS = 600;
    /** Lowest playback speed before the final pause, and the minimum time between speed steps. */
    private static final float MIN_SPEED = .06f;
    private static final long SPEED_STEP_MS = 100;
    /** Halo colour and strength (fraction of the way from ground to the glow colour at the halo's core). */
    // Pearl: 밝은 바탕에서 흰빛은 보이지 않아, 은은한 샴페인빛으로 진주 유리 뒤에 감돌게 한다
    private static final int PEARL_GLOW = 0xffefdcc0, UMBER_GLOW = 0xffe2c39e;
    private static final float PEARL_PEAK = 1f, PEARL_SETTLE = .55f, UMBER_PEAK = .24f, UMBER_SETTLE = .14f;
    /** Halo radii as fractions of the art box (it reaches past the box onto the flat ground; zero at its edge). */
    private static final float HALO_RX = .60f, HALO_RY = .62f;

    private final MainActivity a;
    private final CarKit k;
    private final CarTheme t;
    /**
     * Scene ground = the flat colour the video edges were feathered into (CarTheme bg). Decoders can render that YUV
     * colour a level or two off (the Pleos emulator: +1/+1/+2), so once per file the first decoded frame's corners are
     * read back and the ground takes exactly the colour this device shows there (accepted only within ±10 of bg).
     */
    private static final SparseIntArray DECODED_GROUND = new SparseIntArray();
    private int ground;
    private boolean groundChecked;
    private final int rawId, stillId, videoW, videoH;
    private final int glow;
    private final float haloPeak, haloSettle;

    private final ArtBox art;
    private final TextureView video;
    private final ImageView still;
    private final TextView status, end, label, headline, support, timer, caption, secondary, primary;
    private final TextView finishStatus, question, note, sound, footLine;
    private final View rule, endSep;
    private final LinearLayout finishBlock, chipRow;
    private final List<TextView> chips = new ArrayList<>();
    private final CarIcon pauseIcon;

    private MediaPlayer player;
    private Surface surface;
    private boolean prepared, wanted, attached, windowVisible = true, framePosted;
    private int positionMs;
    private float shownScale = -1;
    /** Paused dim, 0..1 of its ~22 % hold. */
    private float dimLevel;

    // finish state
    private boolean finishMode, finishBloom, ramping, playerSlowed, stillShown, finishAnimCancelled;
    private ValueAnimator finishAnim;
    private float finishFromScale = 1f, finishFromDim, lastSpeed = 1f, halo;
    private long lastSpeedMs = -SPEED_STEP_MS;
    private int stillGround;

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            framePosted = false;
            if (!showing()) return;
            mirror();
            applyScale();
            if (a.session.state() == RestSession.State.ACTIVE) { framePosted = true; postOnAnimation(this); }
            else update(); // the session left ACTIVE between renders (e.g. reached 180 s): the finish takes over now
        }
    };
    private final Runnable releaseLater = this::releasePlayer;

    RestSceneView(CarShell shell) {
        super(shell.a);
        this.a = shell.a;
        this.k = shell.kit;
        this.t = shell.theme;
        boolean pearl = CarTheme.PEARL_ID.equals(t.id);
        rawId = pearl ? R.raw.rest_pearl : R.raw.rest_umber;
        stillId = pearl ? R.drawable.rest_pearl_still : R.drawable.rest_umber_still;
        videoW = pearl ? PEARL_W : UMBER_W;
        videoH = pearl ? PEARL_H : UMBER_H;
        glow = pearl ? PEARL_GLOW : UMBER_GLOW;
        haloPeak = pearl ? PEARL_PEAK : UMBER_PEAK;
        haloSettle = pearl ? PEARL_SETTLE : UMBER_SETTLE;
        ground = DECODED_GROUND.get(rawId, t.bg);
        groundChecked = DECODED_GROUND.indexOfKey(rawId) >= 0;
        setBackground(new ColorDrawable(ground));
        setClickable(true); // nothing under the scene receives touches while it is shown

        // Art first (drawn under the words). The box sits on the right of the main area; see ArtBox.
        art = new ArtBox();
        video = new TextureView(a);
        video.setOpaque(false);
        video.setSurfaceTextureListener(this);
        video.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        art.addView(video);
        still = new ImageView(a); // finish without a live player: a still of the same artwork, over the video
        still.setScaleType(ImageView.ScaleType.FIT_XY);
        still.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        still.setVisibility(INVISIBLE);
        art.addView(still);

        LinearLayout col = k.column(this);
        col.setPadding(k.px(76), k.px(CarKit.FRAME_TOP), k.px(76), k.px(60));
        col.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));

        // Header: 차분. │ [● 주차 중 · 데모 (finish)] ……… ● status │ 마치기
        LinearLayout header = k.header(col);
        k.wordmark(header, 34);
        k.sep(header, 40);
        finishStatus = k.live(header, "", 24, false);
        finishStatus.setVisibility(GONE);
        k.flex(header);
        status = k.live(header, "", 24, false);
        endSep = k.sep(header, 40);
        end = k.ghost(header, "마치기", null, () -> press(a.endButton));
        end.setTextColor(t.ink);

        FrameLayout main = new FrameLayout(a);
        col.addView(main, CarKit.lp(-1, 0, 1));
        main.addView(art, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout row = k.row(main);
        row.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        LinearLayout copy = k.column(row);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.setLayoutParams(CarKit.lp(0, -1, 47));
        k.flex(row).setLayoutParams(CarKit.lp(0, 1, 53));

        label = k.text(copy, "", 28, t.ink2, CarKit.W500);
        headline = k.display(copy, "", 96); // the protected Graphite title is 96 at this car scale
        headline.setMaxLines(2);
        CarKit.margins(headline, 0, k.px(30), 0, 0);
        support = k.body(copy, "");
        CarKit.margins(support, 0, k.px(28), 0, 0);
        rule = new View(a);
        rule.setBackgroundColor(t.line2);
        LinearLayout.LayoutParams rp = CarKit.lp(k.px(64), k.px(2));
        rp.setMargins(0, k.px(44), 0, k.px(26));
        copy.addView(rule, rp);
        timer = k.num(copy, "", 96, t.ink);
        timer.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        caption = k.caption(copy, "");
        CarKit.margins(caption, 0, k.px(10), 0, 0);

        // Finish block (FinishLayer's question · feeling chips · note), mirrored and forwarded.
        finishBlock = k.column(copy);
        finishBlock.setVisibility(GONE);
        question = k.text(finishBlock, "", 32, t.ink, CarKit.W500);
        chipRow = k.row(finishBlock);
        CarKit.margins(chipRow, 0, k.px(26), 0, 0);
        note = k.caption(finishBlock, "");
        CarKit.margins(note, 0, k.px(26), 0, 0);

        // Footer: PEARL │ 소리 끔 (rest) / 공조와 시트는 그대로예요. (finish) ……… [secondary] [primary]
        LinearLayout footer = k.footer(col);
        TextView mood = k.overline(footer, t.label, 18);
        mood.setLetterSpacing(.24f);
        k.sep(footer, 44);
        sound = k.caption(footer, "소리 끔");
        CarIcon mute = new CarIcon(CarIcon.MUTE, t.ink3);
        mute.setBounds(0, 0, k.px(28), k.px(28));
        sound.setCompoundDrawables(mute, null, null, null);
        sound.setCompoundDrawablePadding(k.px(10));
        sound.setGravity(Gravity.CENTER_VERTICAL);
        footLine = k.caption(footer, "");
        footLine.setVisibility(GONE);
        k.flex(footer);
        secondary = k.secondary(footer, "", () -> press(a.settingsButton));
        CarKit.margins(secondary, 0, 0, k.px(20), 0);
        primary = k.primary(footer, "", this::pressPrimary);
        primary.setMinWidth(k.px(340));
        pauseIcon = new CarIcon(PAUSE_ICON, t.onPrimary, 2.4f);
        pauseIcon.setBounds(0, 0, k.px(36), k.px(36));
        primary.setCompoundDrawablePadding(k.px(16));
        setVisibility(GONE);
    }

    // ── state from CarShell ─────────────────────────────────
    /** CarShell: the rest screen is on and the session is ACTIVE, PAUSED, COMPLETE or ENDED (Pearl / Umber only). */
    void setWanted(boolean on) {
        wanted = on;
        update();
    }

    /** Called on every render (state changes and the 1 s tick). */
    void update() {
        syncMode();
        if (showing()) {
            mirror();
            if (!finishMode) { applyScale(); if (halo != 0) setHalo(0); }
        }
        applyPlayback();
    }

    /** Theme rebuild or stage replacement: stop for good. */
    void release() {
        wanted = false;
        removeCallbacks(frame);
        framePosted = false;
        cancelFinishAnim();
        finishMode = false;
        releasePlayer();
    }

    private boolean showing() { return wanted && attached && windowVisible && getVisibility() == VISIBLE; }

    private static boolean finished(RestSession.State s) {
        return s == RestSession.State.COMPLETE || s == RestSession.State.ENDED;
    }

    // ── rest ⇄ finish ───────────────────────────────────────
    private void syncMode() {
        boolean fin = finished(a.session.state());
        if (!wanted) {
            // Leaving SESSION: the host fades out with the picture as it is; the player is released by applyPlayback.
            if (finishMode) { finishMode = false; cancelFinishAnim(); ramping = false; }
            return;
        }
        if (fin == finishMode) return;
        if (fin) enterFinish();
        else leaveFinishToRest();
    }

    /** COMPLETE / ENDED reached (or the scene appears on a finish): ease to a calm, still end. */
    private void enterFinish() {
        finishMode = true;
        finishBloom = a.session.state() == RestSession.State.COMPLETE;
        removeCallbacks(frame);
        framePosted = false;
        cancelFinishAnim();
        boolean live = player != null && prepared;
        boolean playing = live && isPlaying();
        if (!live) { releasePlayer(); showStill(true); } // never open a player for the finish
        finishFromScale = shownScale < 0 ? 1f : shownScale;
        finishFromDim = dimLevel;
        // No live frame (the finish appears on its own), reduced motion or off screen: straight to the settled state.
        boolean calm = !live || k.reducedMotion() || !showing();
        if (reviewLog) Log.i(TAG, String.format(java.util.Locale.ROOT, "finish enter %s %s live=%b playing=%b bloom=%b reduced=%b scale=%.4f",
                t.id, a.session.state(), live, playing, finishBloom, k.reducedMotion(), finishFromScale));
        if (calm) { settleFinish(); return; }
        ramping = playing;
        lastSpeed = 1f;
        lastSpeedMs = -SPEED_STEP_MS;
        finishAnimCancelled = false;
        ValueAnimator anim = ValueAnimator.ofFloat(0f, FINISH_MS);
        anim.setDuration(FINISH_MS);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(v -> finishFrame((float) v.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (finishAnim == animation) finishAnim = null;
                if (!finishAnimCancelled && finishMode) settleFinish();
            }
        });
        finishAnim = anim;
        anim.start();
    }

    /** One finish frame at {@code ms} after COMPLETE / ENDED. */
    private void finishFrame(float ms) {
        if (!finishMode) return;
        float stop = Math.min(1f, ms / STOP_MS);
        setScale(finishFromScale + (1f - finishFromScale) * CarKit.EASE_IN_OUT.getInterpolation(stop));
        if (ramping) rampSpeed(stop, ms);
        if (finishFromDim > 0) setDim(finishFromDim * (1f - CarKit.EASE.getInterpolation(Math.min(1f, ms / DIM_OUT_MS))));
        if (finishBloom) setHalo(bloom(ms));
        if (reviewLog) logFinish(ms);
    }

    /** Halo strength: in to the peak over 1.2 s, then down to the settled level over 1.8 s. */
    private float bloom(float ms) {
        if (ms <= BLOOM_IN_MS) return haloPeak * CarKit.EASE.getInterpolation(ms / BLOOM_IN_MS);
        float settle = Math.min(1f, (ms - BLOOM_IN_MS) / BLOOM_SETTLE_MS);
        return haloPeak + (haloSettle - haloPeak) * CarKit.EASE_IN_OUT.getInterpolation(settle);
    }

    /** The loop slows like a breath running out: speed (1 − p)^1.6, stepped every 100 ms, then paused on its frame. */
    private void rampSpeed(float p, float ms) {
        if (player == null || !prepared) { ramping = false; return; }
        if (p >= 1f) { stopLoop(); return; }
        float speed = (float) Math.max(MIN_SPEED, Math.pow(1 - p, 1.6));
        if (ms - lastSpeedMs < SPEED_STEP_MS || Math.abs(speed - lastSpeed) < .02f) return;
        try {
            player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed));
            playerSlowed = true;
            lastSpeed = speed;
            lastSpeedMs = (long) ms;
        } catch (RuntimeException e) {
            Log.w(TAG, "speed ramp unavailable; holding the frame", e);
            stopLoop();
        }
    }

    /** Pause on the frame shown now. */
    private void stopLoop() {
        ramping = false;
        pausePlayer();
        if (reviewLog) Log.i(TAG, t.id + " finish loop stopped held@" + positionMs);
    }

    /** The calm end: scale 1.00, loop paused, no dim, halo at its settled level (COMPLETE) or none (ENDED). */
    private void settleFinish() {
        cancelFinishAnim();
        setScale(1f);
        if (ramping || isPlaying()) stopLoop();
        setDim(0);
        setHalo(finishBloom ? haloSettle : 0);
        if (reviewLog) Log.i(TAG, String.format(java.util.Locale.ROOT, "finish settled %s %s scale=%.4f halo=%.3f still=%b player=%s",
                t.id, a.session.state(), shownScale, halo, stillShown, player == null ? "none" : "held@" + positionMs));
    }

    /** 다시 3분 쉬기: back to the rest scene with a fresh loop (speed 1) from the frame on screen. */
    private void leaveFinishToRest() {
        finishMode = false;
        cancelFinishAnim();
        ramping = false;
        setHalo(0);
        setDim(0);
        releasePlayer(); // the rest opens a new player; the still (if shown) stays until its first frame
        shownScale = -1;
        if (reviewLog) Log.i(TAG, t.id + " finish left for " + a.session.state());
    }

    private void cancelFinishAnim() {
        ValueAnimator anim = finishAnim;
        finishAnim = null;
        if (anim != null) { finishAnimCancelled = true; anim.cancel(); }
    }

    private boolean isPlaying() {
        try { return player != null && prepared && player.isPlaying(); }
        catch (IllegalStateException e) { return false; }
    }

    // ── mirror of the protected screen (read only) ─────────
    private void mirror() {
        copyText(label, a.guideLabel);
        copyText(headline, a.titleText);
        copyText(support, a.supportText);
        copyText(timer, a.timerText);
        if (a.timerText != null) timer.setContentDescription(a.timerText.getContentDescription());
        copyText(caption, a.timerCaption);
        copyText(status, a.modeText);
        RestSession.State s = a.session.state();
        if (status.getVisibility() == VISIBLE) k.liveState(status, s != RestSession.State.ACTIVE);
        mirrorFinishStatus();
        mirrorButton(end, a.endButton);
        visible(endSep, end.getVisibility() == VISIBLE);
        if (a.timerDash != null) visible(rule, a.timerDash.getVisibility() != GONE);
        mirrorFinishBlock();
        visible(sound, !finishMode);
        if (finishMode) copyText(footLine, a.footerThemeText);
        else visible(footLine, false);
        mirrorButton(secondary, a.settingsButton);
        Button source = source();
        if (source == null) primary.setVisibility(INVISIBLE);
        else {
            mirrorButton(primary, source);
            boolean pause = source == a.pauseButton;
            if ((primary.getCompoundDrawables()[0] != null) != pause)
                primary.setCompoundDrawables(pause ? pauseIcon : null, null, null, null);
        }
        if (!finishMode) setDim(s == RestSession.State.PAUSED ? 1f : 0f);
    }

    /** Header status of the finish (‘● 주차 중 · 데모’), from FinishLayer's status next to the protected brand. */
    private void mirrorFinishStatus() {
        LinearLayout src = a.finishStatus;
        TextView text = src == null ? null : firstText(src);
        boolean on = text != null && src.getVisibility() == VISIBLE && text.getVisibility() != GONE;
        if (on && !TextUtils.equals(finishStatus.getText(), text.getText())) finishStatus.setText(text.getText());
        visible(finishStatus, on);
    }

    /** Question, feeling chips (text · selected · enabled) and note of the protected finish panel. */
    private void mirrorFinishBlock() {
        LinearLayout panel = a.finishPanel;
        boolean on = panel != null && panel.getVisibility() == VISIBLE;
        visible(finishBlock, on);
        if (!on) return;
        TextView first = null, last = null;
        for (int i = 0; i < panel.getChildCount(); i++) {
            View c = panel.getChildAt(i);
            if (!(c instanceof TextView)) continue;
            if (first == null) first = (TextView) c; else last = (TextView) c;
        }
        copyText(question, first);
        if (last != null) copyText(note, last); else visible(note, false);
        LinearLayout feedback = a.feedbackPanel;
        boolean chipsOn = feedback != null && feedback.getVisibility() == VISIBLE;
        visible(chipRow, chipsOn);
        if (!chipsOn) return;
        int n = feedback.getChildCount();
        while (chips.size() < n) {
            final int index = chips.size();
            TextView chip = k.chip(chipRow, "", () -> pressChip(index));
            CarKit.margins(chip, 0, 0, k.px(14), 0);
            chips.add(chip);
        }
        for (int i = 0; i < chips.size(); i++) {
            TextView dst = chips.get(i);
            View c = i < n ? feedback.getChildAt(i) : null;
            if (!(c instanceof TextView)) { visible(dst, false); continue; }
            TextView src = (TextView) c;
            boolean changed = !TextUtils.equals(dst.getText(), src.getText());
            if (changed) dst.setText(src.getText());
            visible(dst, src.getVisibility() != GONE);
            boolean selected = src.isSelected();
            if (changed || dst.isSelected() != selected) {
                k.chipState(dst, selected);
                if (reviewLog && !changed) Log.i(TAG, t.id + " chip " + src.getText() + " selected=" + selected);
            }
            if (dst.isEnabled() != src.isEnabled()) CarKit.enable(dst, src.isEnabled());
        }
    }

    private void pressChip(int index) {
        LinearLayout feedback = a.feedbackPanel;
        if (feedback == null || feedback.getVisibility() != VISIBLE || index >= feedback.getChildCount()) return;
        press(feedback.getChildAt(index));
    }

    private static TextView firstText(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) if (group.getChildAt(i) instanceof TextView) return (TextView) group.getChildAt(i);
        return null;
    }

    private static void visible(View v, boolean on) {
        int visibility = on ? VISIBLE : GONE;
        if (v.getVisibility() != visibility) v.setVisibility(visibility);
    }

    private Button source() {
        if (a.pauseButton != null && a.pauseButton.getVisibility() == VISIBLE) return a.pauseButton;
        if (a.resumeButton != null && a.resumeButton.getVisibility() == VISIBLE) return a.resumeButton;
        if (finishMode && a.startButton != null && a.startButton.getVisibility() == VISIBLE) return a.startButton; // 닫기
        return null;
    }

    private static void copyText(TextView dst, TextView src) {
        if (src == null) return;
        CharSequence value = src.getText();
        if (!TextUtils.equals(dst.getText(), value)) dst.setText(value);
        int visibility = src.getVisibility() == GONE ? GONE : VISIBLE;
        if (dst.getVisibility() != visibility) dst.setVisibility(visibility);
    }

    private static void mirrorButton(TextView dst, Button src) {
        if (src == null) { dst.setVisibility(GONE); return; }
        copyText(dst, src);
        if (dst.isEnabled() != src.isEnabled()) CarKit.enable(dst, src.isEnabled());
    }

    /** Forward to the protected control so its own handler (and its own enablement) decides. */
    private static void press(View target) {
        if (target != null && target.isEnabled() && target.getVisibility() == VISIBLE) target.performClick();
    }

    private void pressPrimary() { press(source()); }

    // ── breath: the same clock as the protected point ──────
    private void applyScale() {
        if (finishMode) return; // the finish eases the scale itself
        long now = SystemClock.elapsedRealtime();
        double expansion = a.naturalRestMode
                ? (a.session.reducedMotion() ? 0.5 : 0.0) // 자연스럽게 쉬기: no breath, the contract's 0.5 under reduced motion
                : a.session.expansion(now);              // guided 0→1→0 per 8 s, 0 in the 4 s tail, frozen while paused
        float scale = (float) (1 + BREATH * expansion);
        if (reviewLog) logState(now, expansion, scale);
        setScale(scale);
    }

    private void setScale(float scale) {
        if (Math.abs(scale - shownScale) < 1e-5f) return;
        shownScale = scale;
        video.setScaleX(scale);
        video.setScaleY(scale);
    }

    private void setDim(float level) {
        if (Math.abs(level - dimLevel) < 1e-3f) return;
        dimLevel = level;
        art.dimChanged();
    }

    private void setHalo(float strength) {
        if (Math.abs(strength - halo) < 1e-4f) return;
        halo = strength;
        applyHalo();
    }

    private HaloEffect haloEffect;
    private boolean haloFailed;

    /**
     * Halo behind the art: a RuntimeShader effect on this whole scene (Android 13+; older systems show no halo), so
     * the glow can spread past the art box onto the same flat ground without an edge. Centred on the art box.
     */
    private void applyHalo() {
        if (Build.VERSION.SDK_INT < 33 || haloFailed) return;
        RectF box = art.box;
        if (halo <= 0 || box.isEmpty()) {
            if (haloEffect != null) setRenderEffect(null);
            return;
        }
        float dx = 0, dy = 0;
        for (View v = art; v != null && v != this; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            dx += v.getLeft();
            dy += v.getTop();
        }
        try {
            if (haloEffect == null) haloEffect = new HaloEffect();
            haloEffect.apply(this, box.centerX() + dx, box.centerY() + dy, box.width() * HALO_RX, box.height() * HALO_RY,
                    ground, glow, halo);
        } catch (RuntimeException e) {
            haloFailed = true;
            Log.w(TAG, "halo unavailable", e);
            setRenderEffect(null);
        }
    }

    /** Demo debug builds, `--ez reviewRestSceneLog true` only: a 4 Hz trace to check sync and freeze on the emulator. */
    static boolean reviewLog;
    private long lastLogMs;

    private void logState(long now, double expansion, float scale) {
        if (now - lastLogMs < 250) return;
        lastLogMs = now;
        int position = -1;
        boolean playing = false;
        try { if (player != null && prepared) { position = player.getCurrentPosition(); playing = player.isPlaying(); } }
        catch (IllegalStateException ignored) { }
        Log.i(TAG, String.format(java.util.Locale.ROOT,
                "trace %s %s active=%d phase=%d exp=%.3f scale=%.4f title=%s scene=%s timer=%s video=%s@%d",
                t.id, a.session.state(), a.session.elapsedActiveMs(now), a.session.elapsedActiveMs(now) % 8000,
                expansion, scale, oneLine(a.titleText.getText()), oneLine(headline.getText()), timer.getText(),
                playing ? "playing" : "held", position));
    }

    private void logFinish(float ms) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLogMs < 250) return;
        lastLogMs = now;
        int position = -1;
        boolean playing = false;
        try { if (player != null && prepared) { position = player.getCurrentPosition(); playing = player.isPlaying(); } }
        catch (IllegalStateException ignored) { }
        Log.i(TAG, String.format(java.util.Locale.ROOT,
                "finish %s %s t=%.0f scale=%.4f speed=%.2f halo=%.3f dim=%.2f title=%s video=%s@%d still=%b",
                t.id, a.session.state(), ms, shownScale, ramping ? lastSpeed : 0f, halo, dimLevel,
                oneLine(headline.getText()), playing ? "playing" : "held", position, stillShown));
    }

    private static String oneLine(CharSequence text) {
        return String.valueOf(text).replace('\n', ' ');
    }

    // ── video ───────────────────────────────────────────────
    private void applyPlayback() {
        if (!showing()) {
            removeCallbacks(frame);
            framePosted = false;
            if (finishMode) settleFinish(); // e.g. the window went away mid-bloom: nothing animates off screen
            pausePlayer();
            removeCallbacks(releaseLater);
            if (!attached || !windowVisible) releasePlayer();
            else postDelayed(releaseLater, 400); // let a crossfade finish on the last frame
            return;
        }
        removeCallbacks(releaseLater);
        RestSession.State s = a.session.state();
        if (finishMode) {
            // The finish owns the art: ramp / hold the frame it has, never (re)start a loop. No player: the still.
            if (player == null || !prepared) { if (player != null) releasePlayer(); showStill(true); }
            return;
        }
        if (playerSlowed) releasePlayer(); // a loop slowed by a finish never carries on into a rest
        if (s == RestSession.State.ACTIVE && !framePosted) { framePosted = true; postOnAnimation(frame); }
        if (player == null && surface != null) openPlayer();
        if (player == null || !prepared) return;
        boolean play = s == RestSession.State.ACTIVE && !a.session.reducedMotion();
        try {
            if (play && !player.isPlaying()) player.start();
            else if (!play && player.isPlaying()) { player.pause(); positionMs = player.getCurrentPosition(); }
        } catch (IllegalStateException e) {
            Log.w(TAG, "player state", e);
            releasePlayer();
        }
    }

    private void openPlayer() {
        MediaPlayer mp = new MediaPlayer();
        try (AssetFileDescriptor fd = getResources().openRawResourceFd(rawId)) {
            mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
        } catch (Exception e) {
            Log.w(TAG, "cannot open rest art", e);
            mp.release();
            return;
        }
        player = mp;
        prepared = false;
        mp.setSurface(surface);
        mp.setLooping(true);
        mp.setVolume(0f, 0f); // the files carry no audio track; never touch audio focus
        mp.setOnPreparedListener(p -> {
            if (p != player) return;
            prepared = true;
            // Show the held frame (paused / reduced motion) or continue the loop where it stopped.
            p.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST);
            applyPlayback();
        });
        mp.setOnErrorListener((p, what, extra) -> {
            Log.w(TAG, "rest art error " + what + "/" + extra);
            if (p == player) releasePlayer();
            return true;
        });
        mp.prepareAsync();
        if (reviewLog) Log.i(TAG, t.id + " player opened from " + positionMs);
    }

    private void pausePlayer() {
        if (player == null || !prepared) return;
        try {
            if (player.isPlaying()) player.pause();
            positionMs = player.getCurrentPosition();
        } catch (IllegalStateException ignored) { }
    }

    private void releasePlayer() {
        removeCallbacks(releaseLater);
        if (player == null) return;
        pausePlayer();
        MediaPlayer p = player;
        player = null;
        prepared = false;
        ramping = false;
        playerSlowed = false;
        p.setOnPreparedListener(null);
        p.setOnErrorListener(null);
        p.release();
        if (reviewLog) Log.i(TAG, t.id + " player released at " + positionMs + " (" + a.session.state() + ")");
    }

    /** The finish's still of the artwork (frame 0 of the loop), over the video, with its own decoded ground. */
    private void showStill(boolean on) {
        if (on == stillShown) return;
        if (on) {
            if (still.getDrawable() == null) {
                Bitmap bitmap = BitmapFactory.decodeResource(getResources(), stillId);
                if (bitmap == null) { Log.w(TAG, "cannot decode the finish still"); return; }
                still.setImageBitmap(bitmap);
                stillGround = cornerGround(bitmap, 0);
            }
            stillShown = true;
            positionMs = 0; // a later loop starts on this same frame
            still.setVisibility(VISIBLE);
            if (stillGround != 0) setGround(stillGround);
        } else {
            stillShown = false;
            still.setVisibility(INVISIBLE);
            setGround(DECODED_GROUND.get(rawId, t.bg));
        }
        if (reviewLog) Log.i(TAG, t.id + " still " + (on ? "shown" : "hidden"));
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        surface = new Surface(texture);
        applyPlayback();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        releasePlayer();
        if (surface != null) surface.release();
        surface = null;
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        if (stillShown && !finishMode && player != null) post(() -> { if (!finishMode && player != null) showStill(false); });
        if (groundChecked) return;
        groundChecked = true;
        post(this::sampleGround);
    }

    /** Reads the decoded colour of the video's four flat corners and makes it the scene ground. */
    private void sampleGround() {
        Bitmap frame = null;
        try {
            int w = 96, h = Math.max(8, Math.round(96f * videoH / videoW));
            frame = video.getBitmap(w, h);
            if (frame == null) return;
            int color = cornerGround(frame, 1);
            if (color == 0) { Log.w(TAG, "decoded ground out of range"); return; }
            DECODED_GROUND.put(rawId, color);
            Log.i(TAG, t.id + " ground #" + Integer.toHexString(t.bg & 0xffffff) + " decodes as #" + Integer.toHexString(color & 0xffffff));
            if (!stillShown) setGround(color);
        } catch (RuntimeException e) {
            Log.w(TAG, "ground sample", e);
        } finally {
            if (frame != null) frame.recycle();
        }
    }

    /** Median colour of a frame's four corners, or 0 when it is not within ±10 of the theme bg. */
    private int cornerGround(Bitmap frame, int inset) {
        int w = frame.getWidth(), h = frame.getHeight();
        int[][] corners = {{inset, inset}, {w - 1 - inset, inset}, {inset, h - 1 - inset}, {w - 1 - inset, h - 1 - inset}};
        int[] r = new int[4], g = new int[4], b = new int[4];
        for (int i = 0; i < 4; i++) {
            int c = frame.getPixel(corners[i][0], corners[i][1]);
            r[i] = (c >> 16) & 0xff; g[i] = (c >> 8) & 0xff; b[i] = c & 0xff;
        }
        int color = 0xff000000 | (median(r) << 16) | (median(g) << 8) | median(b);
        int dr = Math.abs(((color >> 16) & 0xff) - ((t.bg >> 16) & 0xff));
        int dg = Math.abs(((color >> 8) & 0xff) - ((t.bg >> 8) & 0xff));
        int db = Math.abs((color & 0xff) - (t.bg & 0xff));
        return Math.max(dr, Math.max(dg, db)) > 10 ? 0 : color;
    }

    private static int median(int[] v) {
        int[] s = v.clone();
        java.util.Arrays.sort(s);
        return (s[1] + s[2]) / 2;
    }

    private void setGround(int color) {
        if (color == ground) return;
        ground = color;
        setBackground(new ColorDrawable(color));
        art.groundChanged();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        update();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        applyPlayback();
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        windowVisible = visibility == VISIBLE;
        update(); // the Activity stopped / came back: never play behind another screen
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (attached) update();
    }

    /**
     * The art region: right of the main area, video aspect, vertically centred. Clips the breathing video, paints a
     * soft static dim while paused, and lays a thin ground-colour edge over the box so a decoder's ±1 colour step
     * at the video edge can never read as a rectangle.
     */
    private final class ArtBox extends FrameLayout {
        private final RectF box = new RectF();
        private final Paint dim = new Paint();
        private final Paint edge = new Paint();
        private Shader left, right, top, bottom;

        ArtBox() {
            super(RestSceneView.this.getContext());
            setWillNotDraw(false);
            setClipChildren(true);
            dimChanged();
        }

        void dimChanged() {
            dim.setColor(CarTheme.alpha(ground, Math.round(0x38 * dimLevel))); // ~22 %: a very soft, static hold
            invalidate();
        }

        void groundChanged() {
            dimChanged();
            shaders();
            RestSceneView.this.applyHalo();
            invalidate();
        }

        private void shaders() {
            if (box.isEmpty()) return;
            float g = Math.max(1, Math.min(box.width(), box.height()) * .05f);
            int[] c = {ground, CarTheme.alpha(ground, 0)};
            left = new LinearGradient(box.left, 0, box.left + g, 0, c, null, Shader.TileMode.CLAMP);
            right = new LinearGradient(box.right, 0, box.right - g, 0, c, null, Shader.TileMode.CLAMP);
            top = new LinearGradient(0, box.top, 0, box.top + g, c, null, Shader.TileMode.CLAMP);
            bottom = new LinearGradient(0, box.bottom, 0, box.bottom - g, c, null, Shader.TileMode.CLAMP);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
            setMeasuredDimension(w, h);
            float aspect = videoW / (float) videoH;
            float bw = Math.min(w * .64f, h * .94f * aspect), bh = bw / aspect;
            box.set(w - bw, (h - bh) / 2f, w, (h + bh) / 2f);
            int ws = MeasureSpec.makeMeasureSpec(Math.round(bw), MeasureSpec.EXACTLY);
            int hs = MeasureSpec.makeMeasureSpec(Math.round(bh), MeasureSpec.EXACTLY);
            video.measure(ws, hs);
            still.measure(ws, hs);
            shaders();
        }

        @Override protected void onLayout(boolean changed, int l, int tp, int r, int b) {
            int bl = Math.round(box.left), bt = Math.round(box.top), br = Math.round(box.right), bb = Math.round(box.bottom);
            video.layout(bl, bt, br, bb);
            still.layout(bl, bt, br, bb);
            video.setPivotX(box.width() / 2f);
            video.setPivotY(box.height() / 2f);
            if (changed && halo > 0) RestSceneView.this.applyHalo();
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            int save = canvas.save();
            canvas.clipRect(box);
            super.dispatchDraw(canvas);
            if (dimLevel > 0) canvas.drawRect(box, dim);
            for (Shader s : new Shader[]{left, right, top, bottom}) {
                if (s == null) continue;
                edge.setShader(s);
                canvas.drawRect(box, edge);
            }
            canvas.restoreToCount(save);
        }
    }

    /**
     * The finish halo as an AGSL effect on the scene (only loaded on Android 13+). Light is added only where the
     * picture shows the flat ground (|colour − ground| small), so it reads as a glow behind the object; the object's
     * own lines, glass and shadows — and the words and buttons — keep their values. Elliptical, centred on the art
     * box, zero (with zero slope) at its edge.
     */
    private static final class HaloEffect {
        private static final String AGSL =
                "uniform shader content;\n"
                + "uniform float2 center;\n"
                + "uniform float2 radius;\n"
                + "uniform float3 ground;\n"
                + "uniform float3 glow;\n"
                + "uniform float strength;\n"
                + "half4 main(float2 p) {\n"
                + "  half4 c = content.eval(p);\n"
                + "  if (c.a < 0.996) return c;\n"
                + "  float2 q = (p - center) / radius;\n"
                + "  float fall = 1.0 - smoothstep(0.22, 1.0, length(q));\n"
                + "  float3 rgb = float3(c.rgb);\n"
                + "  float3 d = abs(rgb - ground);\n"
                + "  float behind = 1.0 - smoothstep(0.016, 0.09, max(d.r, max(d.g, d.b)));\n"
                + "  rgb += (glow - ground) * (strength * fall * behind);\n"
                + "  return half4(half3(clamp(rgb, 0.0, 1.0)), 1.0);\n"
                + "}\n";
        private final android.graphics.RuntimeShader shader = new android.graphics.RuntimeShader(AGSL);

        void apply(View target, float cx, float cy, float rx, float ry, int ground, int glow, float strength) {
            shader.setFloatUniform("center", cx, cy);
            shader.setFloatUniform("radius", Math.max(1f, rx), Math.max(1f, ry));
            shader.setFloatUniform("ground", ch(ground, 16), ch(ground, 8), ch(ground, 0));
            shader.setFloatUniform("glow", ch(glow, 16), ch(glow, 8), ch(glow, 0));
            shader.setFloatUniform("strength", strength);
            target.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"));
        }

        private static float ch(int color, int shift) { return ((color >> shift) & 0xff) / 255f; }
    }
}
