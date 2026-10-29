package ai.pitin.vehicle;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * The mood art on the right of 시작/마침-style screens. Graphite: the path and its point at 17%
 * (static, as on the artboard). Pearl: the pearl glass membrane. Umber: the contour object under a
 * radial mask. It can play the Pleos intro (curve draws ~1.3s, point pops with spring-pop).
 */
final class HeroArtView extends View {
    private static final String CURVE = "M0 129.2 L57.6 129.2 C121.6 129.2 198.4 40.8 262.4 40.8 L320 40.8";
    private final CarTheme theme;
    private final float unit;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint maskV = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path source = SvgPath.parse(CURVE);
    private final Path curve = new Path();
    private final Path partial = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final float[] dot = new float[2];
    private final RectF art = new RectF();
    private Bitmap bitmap;
    private float draw = 1f, dotScale = 1f, artAlpha = 1f, artScale = 1f;
    private ValueAnimator intro;
    private ObjectAnimator breathe;
    private boolean breathing;

    HeroArtView(Context context, CarTheme theme, float unit) {
        super(context);
        this.theme = theme;
        this.unit = unit;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setColor(GraphitePalette.PATH); // only the Graphite mood draws the curve
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        maskV.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        if (CarTheme.PEARL_ID.equals(theme.id)) bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.pitin_pearl_glass);
        if (CarTheme.UMBER_ID.equals(theme.id)) bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.pitin_umber_contour);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private boolean graphite() { return bitmap == null; }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (graphite()) {
            float s = 2.35f * unit;
            float aw = 320 * s, ah = 170 * s;
            art.set((w - aw) / 2f, (h - ah) / 2f, (w + aw) / 2f, (h + ah) / 2f);
            Matrix m = new Matrix();
            m.setScale(s, s);
            m.postTranslate(art.left, art.top);
            source.transform(m, curve);
            measure.setPath(curve, false);
            stroke.setStrokeWidth(Math.max(1.4f, .9f * s));
        } else if (CarTheme.PEARL_ID.equals(theme.id)) {
            float aw = 820 * unit, ah = aw * bitmap.getHeight() / bitmap.getWidth();
            art.set((w - aw) / 2f, (h - ah) / 2f, (w + aw) / 2f, (h + ah) / 2f);
            // Soft 8% edge so the membrane's faint matte never reads as a box on the ground.
            int[] c = {Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT};
            float[] st = {0, .08f, .92f, 1};
            mask.setShader(new LinearGradient(art.left, 0, art.right, 0, c, st, Shader.TileMode.CLAMP));
            maskV.setShader(new LinearGradient(0, art.top, 0, art.bottom, c, st, Shader.TileMode.CLAMP));
        } else {
            float aw = 860 * unit, ah = 590 * unit;
            art.set((w - aw) / 2f, (h - ah) / 2f, (w + aw) / 2f, (h + ah) / 2f);
            float r = aw / 2f;
            RadialGradient g = new RadialGradient(art.centerX(), art.centerY(), r,
                    new int[]{Color.BLACK, Color.BLACK, Color.TRANSPARENT}, new float[]{0, .7f, 1}, Shader.TileMode.CLAMP);
            Matrix lm = new Matrix();
            lm.setScale(1f, ah / aw, art.centerX(), art.centerY());
            g.setLocalMatrix(lm);
            mask.setShader(g);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        if (art.isEmpty()) return;
        if (graphite()) {
            float len = measure.getLength();
            if (draw >= 1f) canvas.drawPath(curve, stroke);
            else if (draw > 0f) {
                partial.reset();
                measure.getSegment(0, len * draw, partial, true);
                canvas.drawPath(partial, stroke);
            }
            if (dotScale > 0.001f && measure.getPosTan(len * .17f, dot, null)) {
                float r = 4.5f * 2.35f * unit * dotScale, ring = 2f * 2.35f * unit * dotScale;
                fill.setColor(theme.bg);
                canvas.drawCircle(dot[0], dot[1], r + ring, fill);
                fill.setColor(theme.ink);
                canvas.drawCircle(dot[0], dot[1], r, fill);
            }
            return;
        }
        int save = canvas.save();
        float s = (CarTheme.PEARL_ID.equals(theme.id) ? .94f : 1f) * artScale;
        canvas.scale(s, s, art.centerX(), art.centerY());
        image.setAlpha(Math.round(255 * artAlpha));
        if (CarTheme.UMBER_ID.equals(theme.id)) {
            int layer = canvas.saveLayer(art, null);
            drawCover(canvas);
            canvas.drawRect(art, mask);
            canvas.restoreToCount(layer);
        } else {
            int layer = canvas.saveLayer(art, null);
            canvas.drawBitmap(bitmap, null, art, image);
            canvas.drawRect(art, mask);
            canvas.drawRect(art, maskV);
            canvas.restoreToCount(layer);
        }
        canvas.restoreToCount(save);
    }

    /** object-fit: cover into the art box. */
    private void drawCover(Canvas canvas) {
        float bw = bitmap.getWidth(), bh = bitmap.getHeight();
        float scale = Math.max(art.width() / bw, art.height() / bh);
        float dw = bw * scale, dh = bh * scale;
        RectF dst = new RectF(art.centerX() - dw / 2, art.centerY() - dh / 2, art.centerX() + dw / 2, art.centerY() + dh / 2);
        canvas.drawBitmap(bitmap, null, dst, image);
    }

    /** Pleos intro frame at {@code ms}: curve draws from 120ms over 1.3s, the point pops at 1.25s (spring-pop 0.8s). */
    void setIntroTime(long ms) {
        if (intro != null) intro.cancel();
        float d = clamp((ms - 120) / 1300f);
        draw = CarKit.EASE_IOS.getInterpolation(d);
        float p = clamp((ms - 1250) / 800f);
        dotScale = p <= 0 ? 0 : CarKit.SPRING_POP.getInterpolation(p);
        float e = CarKit.EASE_IOS.getInterpolation(clamp(ms / 1100f));
        artAlpha = e;
        artScale = .84f + .16f * e;
        invalidate();
    }

    void finishIntro() {
        if (intro != null) intro.cancel();
        draw = 1; dotScale = 1; artAlpha = 1; artScale = 1;
        invalidate();
    }

    /** Pearl breathes (8s, .94↔1.02 ⇒ scale on the view), Umber drifts (16s); Graphite stays still. */
    void setBreathing(boolean on) {
        breathing = on;
        if (breathe != null) { breathe.cancel(); breathe = null; }
        setScaleX(1); setScaleY(1); setTranslationY(0);
        if (!on || graphite()) return;
        if (CarTheme.PEARL_ID.equals(theme.id)) {
            breathe = ObjectAnimator.ofFloat(this, "breath", 0f, 1f);
            breathe.setDuration(8000);
        } else {
            breathe = ObjectAnimator.ofFloat(this, "breath", 0f, 1f);
            breathe.setDuration(16000);
        }
        breathe.setRepeatCount(ValueAnimator.INFINITE);
        breathe.setInterpolator(null);
        breathe.start();
    }

    /** Used by the breathing animator: transform only (scale / translation of this view). */
    @SuppressWarnings("unused")
    void setBreath(float phase) {
        float wave = (float) (0.5 - 0.5 * Math.cos(phase * Math.PI * 2)); // 0 → 1 → 0
        float eased = CarKit.EASE_IN_OUT.getInterpolation(wave);
        if (CarTheme.PEARL_ID.equals(theme.id)) {
            float s = (.94f + .08f * eased) / .94f; // drawn at .94 already
            setPivotY(getHeight() * .6f);
            setScaleX(s); setScaleY(s);
        } else {
            setTranslationY(-4 * unit * eased);
            float s = 1 + .015f * eased;
            setScaleX(s); setScaleY(s);
        }
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (intro != null) intro.cancel();
        if (breathe != null) { breathe.cancel(); breathe = null; }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (breathing && breathe == null) setBreathing(true);
    }

    private static float clamp(float v) { return Math.max(0, Math.min(1, v)); }
}
