package ai.pitin.vehicle;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Light colour swatch (84px in the car): soft glow, ring when chosen, spring-pop to 1.08. */
final class SwatchView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int color;
    private final CarTheme theme;
    private boolean selected;
    private float scale = 1f;
    private ValueAnimator anim;

    SwatchView(Context context, int color, CarTheme theme) {
        super(context);
        this.color = color;
        this.theme = theme;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setSelectedSwatch(boolean on, boolean animate) {
        if (on == selected) return;
        selected = on;
        if (anim != null) anim.cancel();
        float target = on ? 1.08f : 1f;
        if (!animate) { scale = target; invalidate(); return; }
        anim = ValueAnimator.ofFloat(scale, target);
        anim.setDuration(500);
        anim.setInterpolator(CarKit.SPRING_POP);
        anim.addUpdateListener(a -> { scale = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) * 84f / 124f / 2f * scale;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        float room = Math.min(getWidth(), getHeight()) / 2f - r;
        paint.setShadowLayer(Math.max(1f, room * .8f), 0, 0, CarTheme.alpha(color, 0x66));
        canvas.drawCircle(cx, cy, r, paint);
        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.STROKE);
        if (selected) {
            paint.setColor(theme.ink);
            paint.setStrokeWidth(r * .07f);
            canvas.drawCircle(cx, cy, r + r * .07f + r * .035f, paint);
        } else {
            paint.setColor(theme.line2);
            paint.setStrokeWidth(Math.max(1f, r * .036f));
            canvas.drawCircle(cx, cy, r, paint);
        }
    }
}
