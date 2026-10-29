package ai.pitin.vehicle;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** On/off toggle (80×48 in the car). The knob crosses with spring-soft; colour-only under reduced motion. */
final class CarToggle extends View {
    private final CarTheme theme;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean checked;
    private float pos;
    private ValueAnimator anim;

    CarToggle(Context context, CarTheme theme, float unit) {
        super(context);
        this.theme = theme;
        setClickable(true);
        setFocusable(true);
        setSoundEffectsEnabled(false);
        setHapticFeedbackEnabled(false);
    }

    void setChecked(boolean value) {
        if (value == checked && anim == null) { pos = value ? 1 : 0; invalidate(); return; }
        if (value == checked) return;
        checked = value;
        if (anim != null) anim.cancel();
        if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) { pos = value ? 1 : 0; invalidate(); return; }
        anim = ValueAnimator.ofFloat(pos, value ? 1 : 0);
        anim.setDuration(520);
        anim.setInterpolator(CarKit.SPRING_SOFT);
        anim.addUpdateListener(a -> { pos = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight(), r = h / 2f;
        paint.setColor(CarTheme.lerp(theme.line2, theme.primary, Math.max(0, Math.min(1, pos))));
        canvas.drawRoundRect(0, 0, w, h, r, r, paint);
        float inset = h * 4f / 48f, knob = r - inset;
        float cx = inset + knob + (w - 2 * inset - 2 * knob) * pos;
        paint.setColor(CarTheme.lerp(0xffffffff, theme.onPrimary, Math.max(0, Math.min(1, pos))));
        paint.setShadowLayer(3, 0, 1, 0x2e000000);
        canvas.drawCircle(cx, r, knob, paint);
        paint.clearShadowLayer();
    }
}
