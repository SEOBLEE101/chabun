package ai.pitin.vehicle;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** Brightness slider (10–100, step 5): 10px track, 48px thumb, ink fill. Accessible as a range. */
final class CarSlider extends View {
    interface Listener { void onValue(int value); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final CarTheme theme;
    private final float unit;
    private final int min, max, step;
    private int value;
    private Listener listener;

    CarSlider(Context context, CarTheme theme, float unit, int min, int max, int step) {
        super(context);
        this.theme = theme; this.unit = unit; this.min = min; this.max = max; this.step = step;
        this.value = min;
        setFocusable(true);
        setClickable(true);
        setSoundEffectsEnabled(false);
        setContentDescription("밝기");
    }

    void setListener(Listener l) { listener = l; }

    void setValue(int v) { if (v != value) { value = v; invalidate(); } }

    private float thumbR() { return 24 * unit; }

    @Override protected void onDraw(Canvas canvas) {
        float r = thumbR(), left = r, right = getWidth() - r, cy = getHeight() / 2f, h = 10 * unit;
        float f = (value - min) / (float) (max - min);
        float x = left + (right - left) * f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(theme.line2);
        canvas.drawRoundRect(left - h / 2, cy - h / 2, right + h / 2, cy + h / 2, h / 2, h / 2, paint);
        paint.setColor(theme.ink);
        canvas.drawRoundRect(left - h / 2, cy - h / 2, x, cy + h / 2, h / 2, h / 2, paint);
        paint.setColor(0xffffffff);
        paint.setShadowLayer(8 * unit, 0, 2 * unit, 0x38000000);
        canvas.drawCircle(x, cy, r, paint);
        paint.clearShadowLayer();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                float r = thumbR();
                float f = Math.max(0, Math.min(1, (e.getX() - r) / Math.max(1, getWidth() - 2 * r)));
                int v = min + Math.round(f * (max - min) / step) * step;
                if (v != value) { value = v; invalidate(); if (listener != null) listener.onValue(v); }
                return true;
            default:
                return true;
        }
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, min, max, value));
        if (value < max) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        if (value > min) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            int v = Math.max(min, Math.min(max, value + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? step : -step)));
            if (v != value) { value = v; invalidate(); if (listener != null) listener.onValue(v); }
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }
}
