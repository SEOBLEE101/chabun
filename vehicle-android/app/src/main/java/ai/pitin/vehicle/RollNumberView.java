package ai.pitin.vehicle;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Temperature with rolling digits (spec 6-1 숫자 굴림): only the changed digits roll, settling with spring-soft. */
final class RollNumberView extends View {
    private final Paint digit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint degree = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] from = new float[3], to = new float[3], cur = new float[3];
    private final float cell, line, degreeSize;
    private ValueAnimator anim;
    private int value = -1;

    RollNumberView(Context context, CarTheme theme, float size, float degreeSize) {
        super(context);
        this.degreeSize = degreeSize;
        digit.setTypeface(CarKit.numFace());
        digit.setTextSize(size);
        digit.setColor(theme.ink);
        digit.setTextAlign(Paint.Align.CENTER);
        degree.setTypeface(CarKit.numFace());
        degree.setTextSize(degreeSize);
        degree.setColor(theme.ink);
        cell = digit.measureText("0");
        line = size * 1.15f;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void setValue(int tenths, boolean animate) {
        if (tenths == value) return;
        float[] next = {tenths / 100 % 10, tenths / 10 % 10, tenths % 10};
        boolean first = value < 0;
        value = tenths;
        System.arraycopy(cur, 0, from, 0, 3);
        System.arraycopy(next, 0, to, 0, 3);
        if (anim != null) anim.cancel();
        if (first || !animate) { System.arraycopy(next, 0, cur, 0, 3); invalidate(); return; }
        anim = ValueAnimator.ofFloat(0, 1);
        anim.setDuration(700);
        anim.setInterpolator(CarKit.SPRING_SOFT);
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            for (int i = 0; i < 3; i++) cur[i] = from[i] + (to[i] - from[i]) * f;
            invalidate();
        });
        anim.start();
    }

    @Override protected void onMeasure(int w, int h) {
        float dot = digit.measureText(".");
        int width = Math.round(cell * 3 + dot + degree.measureText("°") + 4);
        setMeasuredDimension(width, resolveSize(Math.round(line), h));
    }

    @Override protected void onDraw(Canvas canvas) {
        float dot = digit.measureText(".");
        float baseline = (getHeight() - line) / 2f + line * .82f;
        float top = (getHeight() - line) / 2f;
        float x = 0;
        for (int i = 0; i < 3; i++) {
            if (i == 2) {
                canvas.drawText(".", x + dot / 2, baseline, digit);
                x += dot;
            }
            int save = canvas.save();
            canvas.clipRect(x, top, x + cell, top + line);
            float v = cur[i];
            int base = (int) Math.floor(v);
            float frac = v - base;
            for (int k = -1; k <= 2; k++) {
                int d = base + k;
                if (d < 0 || d > 9) continue;
                canvas.drawText(String.valueOf(d), x + cell / 2, baseline + (k - frac) * line, digit);
            }
            canvas.restoreToCount(save);
            x += cell;
        }
        canvas.drawText("°", x + 2, top + degreeSize * .9f + line * .1f, degree);
    }
}
