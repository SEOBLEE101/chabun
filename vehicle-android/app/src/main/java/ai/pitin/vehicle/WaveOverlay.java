package ai.pitin.vehicle;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

/**
 * 파도 테마 전환 (motion-car-theme): the new mood's ground rises from the bottom in two layers
 * (a 42% foam wave 0.11s ahead, then the main wave with a glinting crest), drifting sideways.
 * Covered by ~1.0s, the theme swaps underneath, then the cover fades in 0.38s.
 */
final class WaveOverlay extends View {
    private static final Path MAIN = SvgPath.parse("M0 28 C40 28 60 8 100 8 S160 28 200 28 S260 8 300 8 S360 28 400 28 S460 8 500 8 S560 28 600 28 S660 8 700 8 S760 28 800 28 L800 46 L0 46 Z");
    private static final Path GLINT = SvgPath.parse("M0 28 C40 28 60 8 100 8 S160 28 200 28 S260 8 300 8 S360 28 400 28 S460 8 500 8 S560 28 600 28 S660 8 700 8 S760 28 800 28");
    private static final Path FOAM = SvgPath.parse("M0 14 C40 14 60 32 100 32 S160 14 200 14 S260 32 300 32 S360 14 400 14 S460 32 500 32 S560 14 600 14 S660 32 700 32 S760 14 800 14 L800 46 L0 46 Z");
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tmp = new Path();
    private final Matrix m = new Matrix();
    private final float crest;
    private int color, glintColor;
    private float ms;
    private long started;
    private ValueAnimator anim;

    WaveOverlay(Context context, float unit) {
        super(context);
        crest = 110 * unit;
        glint.setStyle(Paint.Style.STROKE);
        setVisibility(GONE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Runs the cover; {@code onCovered} fires at 1.0s, then the overlay fades and hides itself. */
    void run(int fillColor, int glintColor, Runnable onCovered) {
        if (anim != null) anim.cancel();
        this.color = fillColor;
        this.glintColor = glintColor;
        setAlpha(1f);
        setVisibility(VISIBLE);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        started = SystemClock.uptimeMillis();
        anim = ValueAnimator.ofFloat(0, 1100);
        anim.setDuration(1100);
        anim.setInterpolator(null);
        final boolean[] fired = {false};
        anim.addUpdateListener(a -> {
            ms = (float) a.getAnimatedValue();
            if (!fired[0] && ms >= 1000) { fired[0] = true; onCovered.run(); fadeOut(); }
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (!fired[0]) { fired[0] = true; onCovered.run(); fadeOut(); }
            }
        });
        anim.start();
    }

    private void fadeOut() {
        animate().alpha(0f).setDuration(380).setInterpolator(CarKit.EASE_IOS).withEndAction(() -> {
            setVisibility(GONE);
            setLayerType(LAYER_TYPE_NONE, null);
        }).start();
    }

    /** Debug/screenshot: freeze at a time in ms. */
    void freezeAt(int fillColor, int glintColor, float atMs) {
        if (anim != null) anim.cancel();
        animate().cancel();
        color = fillColor; this.glintColor = glintColor; ms = atMs; started = SystemClock.uptimeMillis();
        setAlpha(1f); setVisibility(VISIBLE); invalidate();
    }

    void hide() { if (anim != null) anim.cancel(); animate().cancel(); setVisibility(GONE); }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float t = (SystemClock.uptimeMillis() - started) / 1000f;
        drawSheet(canvas, FOAM, Math.min(1, (ms + 110) / 1100f), w, h, .42f, -w * (1 - (t % 1.1f) / 1.1f), false);
        drawSheet(canvas, MAIN, Math.min(1, ms / 1100f), w, h, 1f, -w * ((t % 1.4f) / 1.4f), true);
    }

    private void drawSheet(Canvas c, Path crestPath, float progress, float w, float h, float alpha, float drift, boolean withGlint) {
        float e = CarKit.EASE_WAVE.getInterpolation(Math.max(0, progress));
        float top = h - (h * 1.18f + crest) * e;
        if (top >= h + crest) return;
        fill.setColor(color);
        fill.setAlpha(Math.round(255 * alpha));
        c.drawRect(0, Math.max(0, top), w, h, fill);
        m.setScale(2 * w / 800f, crest / 46f);
        m.postTranslate(drift, top - crest + 1);
        crestPath.transform(m, tmp);
        c.drawPath(tmp, fill);
        if (withGlint) {
            GLINT.transform(m, tmp);
            glint.setColor(glintColor);
            glint.setStrokeWidth(Math.max(1f, 1.2f * crest / 46f));
            c.drawPath(tmp, glint);
        }
    }
}
