package ai.pitin.vehicle;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * Car-scale components from pitin.css `.car` rules: 104px pill buttons, 88px text buttons, 80px chips,
 * r36 cards, header/footer bars, glass icons and status pills. Sizes are artboard pixels (1680×1200),
 * mapped onto the real window by {@link #px(float)}.
 */
final class CarKit {
    static final TimeInterpolator EASE_IOS = new PathInterpolator(.32f, .72f, 0f, 1f);
    static final TimeInterpolator EASE_WAVE = new PathInterpolator(.65f, 0f, .35f, 1f);
    static final TimeInterpolator EASE = new PathInterpolator(.22f, .61f, .36f, 1f);
    static final TimeInterpolator EASE_IN_OUT = new PathInterpolator(.45f, 0f, .55f, 1f);
    /** spring-soft: ~3% overshoot, settles by the end. */
    static final TimeInterpolator SPRING_SOFT = t -> t >= 1 ? 1 : (float) (1 - Math.exp(-8 * t) * Math.cos(7 * t));
    /** spring-pop: ~8% overshoot for dots and checks. */
    static final TimeInterpolator SPRING_POP = t -> t >= 1 ? 1 : (float) (1 - Math.exp(-6.43 * t) * Math.cos(8 * t));

    static final int W300 = 300, W400 = 400, W500 = 500, W600 = 600, W700 = 700;
    /** `.car` frame top padding: the artboard's 52 less the 12 the 88px header row adds above its centre line. */
    static final float FRAME_TOP = 40;

    /** The main shell; null on screens in their own Activity (함께 쓰는 쉼). */
    final MainActivity a;
    final Context c;
    final CarTheme t;
    final float unit;
    private final boolean reducedSetting;

    CarKit(MainActivity activity, CarTheme theme, float unit) { this(activity, activity, theme, unit, false); }

    /** For a screen outside the main shell: the app's own 동작 줄이기 setting is passed in. */
    CarKit(Context context, CarTheme theme, float unit, boolean reducedMotion) { this(null, context, theme, unit, reducedMotion); }

    private CarKit(MainActivity activity, Context context, CarTheme theme, float unit, boolean reducedMotion) {
        this.a = activity; this.c = context; this.t = theme; this.unit = unit; this.reducedSetting = reducedMotion;
    }

    /** The artboard-to-window scale the shell uses (1680×1200 fitted, clamped). */
    static float unitFor(Context context) {
        android.content.res.Configuration cfg = context.getResources().getConfiguration();
        float fit = Math.min(cfg.screenWidthDp / 1680f, cfg.screenHeightDp / 1200f);
        return context.getResources().getDisplayMetrics().density * Math.max(.5f, Math.min(1.5f, fit));
    }

    int px(float v) { return Math.round(v * unit); }

    static Typeface face(int weight) { return Typeface.create(Typeface.DEFAULT, weight, false); }
    static Typeface numFace() { return Typeface.create("sans-serif-light", Typeface.NORMAL); }
    static Typeface wordFace() { return Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 700, false); }

    boolean reducedMotion() { return (a != null ? a.appReducedMotion : reducedSetting) || !ValueAnimator.areAnimatorsEnabled(); }

    // ── text ────────────────────────────────────────────────
    TextView text(ViewGroup parent, String value, float size, int color, int weight) {
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        v.setTextColor(color);
        v.setTypeface(face(weight));
        v.setLetterSpacing(-0.01f);
        v.setIncludeFontPadding(false);
        v.setLineSpacing(0, 1.18f);
        // Korean breaks between words (phrases), not inside them.
        if (android.os.Build.VERSION.SDK_INT >= 33)
            v.setLineBreakWordStyle(android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE);
        if (parent instanceof LinearLayout) parent.addView(v, new LinearLayout.LayoutParams(-2, -2));
        else if (parent != null) parent.addView(v);
        return v;
    }

    TextView display(ViewGroup parent, String value, float size) {
        TextView v = text(parent, value, size, t.ink, W700);
        v.setLetterSpacing(-0.045f);
        v.setLineHeight(px(size * 1.12f));
        return v;
    }

    TextView title(ViewGroup parent, String value, float size) {
        TextView v = text(parent, value, size, t.ink, W700);
        v.setLetterSpacing(-0.03f);
        return v;
    }

    TextView body(ViewGroup parent, String value) {
        TextView v = text(parent, value, 30, t.ink2, W400);
        v.setLineHeight(px(45));
        return v;
    }

    TextView caption(ViewGroup parent, String value) { return text(parent, value, 22, t.ink3, W400); }

    TextView overline(ViewGroup parent, String value, float size) {
        TextView v = text(parent, value, size, t.ink3, W600);
        v.setTypeface(Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 600, false));
        v.setLetterSpacing(.18f);
        v.setAllCaps(true);
        return v;
    }

    /** ‘차분.’ — 폰 머리말과 같은 로고. 끝의 점만 분위기의 강조색이다. */
    TextView wordmark(ViewGroup parent, float size) {
        android.text.SpannableString mark = new android.text.SpannableString("차분.");
        mark.setSpan(new android.text.style.ForegroundColorSpan(t.accent), mark.length() - 1, mark.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView v = text(parent, "", size, t.ink, W700);
        v.setText(mark);
        v.setTypeface(wordFace());
        v.setLetterSpacing(-0.02f);
        v.setContentDescription("차분");
        return v;
    }

    TextView num(ViewGroup parent, String value, float size, int color) {
        TextView v = text(parent, value, size, color, W300);
        v.setTypeface(numFace());
        v.setFontFeatureSettings("tnum");
        v.setLetterSpacing(-0.02f);
        return v;
    }

    // ── surfaces ────────────────────────────────────────────
    static GradientDrawable round(int fill, float radius, int stroke, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (strokeWidth > 0) d.setStroke(strokeWidth, stroke);
        return d;
    }

    LinearLayout card(ViewGroup parent) {
        LinearLayout v = column(parent);
        v.setBackground(round(t.surface, px(36), t.line, Math.max(1, px(1))));
        v.setPadding(px(36), px(36), px(36), px(36));
        return v;
    }

    LinearLayout column(ViewGroup parent) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        if (parent != null) parent.addView(v);
        return v;
    }

    LinearLayout row(ViewGroup parent) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        if (parent != null) parent.addView(v);
        return v;
    }

    View space(LinearLayout parent, float size) {
        View v = new View(c);
        boolean horizontal = parent.getOrientation() == LinearLayout.HORIZONTAL;
        parent.addView(v, new LinearLayout.LayoutParams(horizontal ? px(size) : 1, horizontal ? 1 : px(size)));
        return v;
    }

    View flex(LinearLayout parent) {
        View v = new View(c);
        parent.addView(v, new LinearLayout.LayoutParams(0, 1, 1));
        return v;
    }

    View rule(LinearLayout parent, int color) {
        View v = new View(c);
        v.setBackgroundColor(color);
        boolean horizontal = parent.getOrientation() == LinearLayout.HORIZONTAL;
        parent.addView(v, horizontal ? new LinearLayout.LayoutParams(Math.max(1, px(1)), px(40))
                : new LinearLayout.LayoutParams(-1, Math.max(1, px(1))));
        return v;
    }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) { return new LinearLayout.LayoutParams(w, h, weight); }

    static void margins(View v, int l, int top, int r, int b) {
        ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        p.setMargins(l, top, r, b);
        v.setLayoutParams(p);
    }

    // ── buttons ─────────────────────────────────────────────
    private TextView buttonBase(ViewGroup parent, String label, float height, float size, Runnable action) {
        TextView v = text(parent, label, size, t.ink, W600);
        v.setLetterSpacing(-0.02f);
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);
        v.setMinHeight(px(height));
        v.setClickable(true);
        v.setFocusable(true);
        v.setSoundEffectsEnabled(false);
        v.setHapticFeedbackEnabled(false);
        if (action != null) v.setOnClickListener(ignored -> action.run());
        press(v);
        return v;
    }

    TextView primary(ViewGroup parent, String label, Runnable action) {
        TextView v = buttonBase(parent, label, 104, 30, action);
        v.setTextColor(t.onPrimary);
        v.setBackground(round(t.primary, px(52), 0, 0));
        v.setPadding(px(60), 0, px(60), 0);
        return v;
    }

    TextView secondary(ViewGroup parent, String label, Runnable action) {
        TextView v = buttonBase(parent, label, 104, 30, action);
        v.setBackground(round(0, px(52), t.line2, Math.max(1, px(1))));
        v.setPadding(px(60), 0, px(60), 0);
        return v;
    }

    TextView ghost(ViewGroup parent, String label, String icon, Runnable action) {
        TextView v = buttonBase(parent, label, 88, 26, action);
        v.setTextColor(t.ink2);
        v.setPadding(px(24), 0, px(24), 0);
        if (icon != null) {
            CarIcon d = new CarIcon(icon, t.ink2);
            d.setBounds(0, 0, px(32), px(32));
            v.setCompoundDrawables(d, null, null, null);
            v.setCompoundDrawablePadding(label.isEmpty() ? 0 : px(12));
        }
        return v;
    }

    /**
     * 88×88 back button whose arrow sits 4px from the content edge (the artboard's −24 offset), drawn with uneven
     * padding instead of a negative margin so the whole 88px stays touchable.
     */
    TextView backButton(ViewGroup parent, Runnable action) {
        TextView v = ghost(parent, "", CarIcon.BACK, action);
        v.setContentDescription("시작으로 돌아가기");
        v.setMinWidth(px(88));
        v.setPadding(px(4), 0, px(52), 0);
        return v;
    }

    TextView chip(ViewGroup parent, String label, Runnable action) {
        TextView v = buttonBase(parent, label, 80, 26, action);
        v.setPadding(px(36), 0, px(36), 0);
        v.setTypeface(face(W500));
        chipState(v, false);
        return v;
    }

    void chipState(TextView v, boolean on) {
        v.setSelected(on);
        v.setBackground(round(on ? t.ink : 0, px(40), on ? t.ink : t.line2, Math.max(1, px(1))));
        v.setTextColor(on ? t.bg : t.ink);
        v.setTypeface(face(on ? W600 : W500));
        String label = v.getText().toString();
        v.setContentDescription(label + (on ? ", 선택됨" : ""));
    }

    static void enable(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : .38f);
    }

    /** `.opt` segmented choice grid (3–4 equal cells, 88px tall, r26). */
    LinearLayout optRow(ViewGroup parent, String[][] items, boolean tall, OptListener listener) {
        LinearLayout row = row(parent);
        for (int i = 0; i < items.length; i++) {
            final String id = items[i][0];
            TextView b = buttonBase(row, items[i][1], tall ? 112 : 88, 25, () -> listener.pick(id));
            b.setTypeface(face(W500));
            b.setTag(id);
            b.setSingleLine(false);
            b.setMaxLines(2);
            if (items[i].length > 2 && items[i][2] != null) {
                CarIcon icon = new CarIcon(items[i][2], t.ink);
                icon.setBounds(0, 0, px(32), px(32));
                b.setCompoundDrawables(null, icon, null, null);
                b.setCompoundDrawablePadding(px(6));
                b.setPadding(0, px(14), 0, px(10));
            }
            LinearLayout.LayoutParams p = lp(0, px(tall ? 112 : 88), 1);
            if (i > 0) p.leftMargin = px(12);
            b.setLayoutParams(p);
        }
        return row;
    }

    void optState(LinearLayout row, String selected) {
        for (int i = 0; i < row.getChildCount(); i++) {
            TextView b = (TextView) row.getChildAt(i);
            boolean on = String.valueOf(b.getTag()).equals(selected);
            b.setSelected(on);
            b.setBackground(round(on ? t.ink : 0, px(26), on ? t.ink : t.line2, Math.max(1, px(1))));
            b.setTextColor(on ? t.bg : t.ink);
            b.setTypeface(face(on ? W600 : W500));
            Drawable[] ds = b.getCompoundDrawables();
            if (ds[1] instanceof CarIcon) ((CarIcon) ds[1]).setColor(on ? t.bg : t.ink);
            b.setContentDescription(b.getText() + (on ? ", 선택됨" : ""));
        }
    }

    interface OptListener { void pick(String id); }

    /** `.seg` pill segmented control (80px). */
    LinearLayout seg(ViewGroup parent, String[][] items, OptListener listener) {
        LinearLayout row = row(parent);
        row.setBackground(round(t.bg2, px(46), 0, 0));
        row.setPadding(px(6), px(6), px(6), px(6));
        for (String[] item : items) {
            final String id = item[0];
            TextView b = buttonBase(row, item[1], 80, 26, () -> listener.pick(id));
            b.setTag(id);
            b.setTypeface(face(W500));
            b.setLayoutParams(lp(0, px(80), 1));
        }
        return row;
    }

    void segState(LinearLayout row, String selected) {
        for (int i = 0; i < row.getChildCount(); i++) {
            TextView b = (TextView) row.getChildAt(i);
            boolean on = String.valueOf(b.getTag()).equals(selected);
            b.setSelected(on);
            b.setBackground(on ? round(t.surface, px(40), t.line, Math.max(1, px(1))) : null);
            b.setTextColor(on ? t.ink : t.ink2);
            b.setTypeface(face(on ? W600 : W500));
            b.setContentDescription(b.getText() + (on ? ", 선택됨" : ""));
        }
    }

    // ── small pieces ────────────────────────────────────────
    FrameLayout glass(ViewGroup parent, String icon, float size) {
        FrameLayout g = new FrameLayout(c);
        g.setBackground(new GlassDrawable(t, px(1)));
        ImageView i = new ImageView(c);
        i.setImageDrawable(new CarIcon(icon, t.ink));
        int s = px(size * 32f / 72f);
        g.addView(i, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        if (parent != null) parent.addView(g, new LinearLayout.LayoutParams(px(size), px(size)));
        return g;
    }

    /** Status text with the olive "alive" dot (`.live`), or a grey idle dot. */
    TextView live(ViewGroup parent, String value, float size, boolean idle) {
        TextView v = text(parent, value, size, t.ink2, W500);
        ShapeDrawable dot = new ShapeDrawable(new OvalShape());
        dot.getPaint().setColor(idle ? t.line2 : t.accent);
        dot.setBounds(0, 0, px(12), px(12));
        v.setCompoundDrawables(dot, null, null, null);
        v.setCompoundDrawablePadding(px(12));
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    void liveState(TextView v, boolean idle) {
        Drawable d = v.getCompoundDrawables()[0];
        if (d instanceof ShapeDrawable) { ((ShapeDrawable) d).getPaint().setColor(idle ? t.line2 : t.accent); d.invalidateSelf(); }
    }

    TextView pill(ViewGroup parent, String value, int fill) {
        TextView v = text(parent, value, 20, t.ink, W600);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setSingleLine(true);
        v.setMinHeight(px(44));
        v.setPadding(px(20), 0, px(20), 0);
        v.setBackground(round(fill, px(22), 0, 0));
        return v;
    }

    LinearLayout.LayoutParams wrap() { return lp(-2, -2); }

    // ── bars ───────────────────────────────────────────────
    /**
     * `.car-head`: the artboard's 64px row + 28px gap + rule, laid out as an 88px row + 16px gap so its 88px text
     * buttons and 80px chips can be touched over their whole height (AAOS: 76dp+). Pages start 12px higher
     * ({@link #FRAME_TOP}), so every centre line and the rule stay where the artboard puts them. Returns the inner row.
     */
    LinearLayout header(LinearLayout parent) {
        LinearLayout bar = column(parent);
        LinearLayout inner = row(bar);
        inner.setLayoutParams(lp(-1, px(88)));
        bar.setClipChildren(false);
        inner.setClipChildren(false);
        space(bar, 16);
        rule(bar, t.line);
        bar.setLayoutParams(lp(-1, -2));
        return inner;
    }

    /** `.car-foot`: top rule, 28px padding, 104px row. Returns the inner row. */
    LinearLayout footer(LinearLayout parent) {
        LinearLayout bar = column(parent);
        rule(bar, t.line);
        space(bar, 28);
        LinearLayout inner = row(bar);
        inner.setLayoutParams(lp(-1, px(104)));
        bar.setLayoutParams(lp(-1, -2));
        return inner;
    }

    View sep(LinearLayout parent, float height) {
        View v = new View(c);
        v.setBackgroundColor(t.line2);
        LinearLayout.LayoutParams p = lp(Math.max(1, px(1)), px(height));
        p.setMargins(px(28), 0, px(28), 0);
        parent.addView(v, p);
        return v;
    }

    // ── motion ─────────────────────────────────────────────
    /** Press: 0.965 in 90ms, spring-soft back. Colour-only under reduced motion. No haptics in the car. */
    void press(View v) {
        v.setOnTouchListener((view, e) -> {
            if (!view.isEnabled()) return false;
            if (reducedMotion()) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) view.setAlpha(.82f);
                else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) view.setAlpha(1f);
                return false;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().cancel();
                    view.animate().scaleX(.965f).scaleY(.965f).setDuration(90).setInterpolator(EASE).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().cancel();
                    view.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(SPRING_SOFT).start();
                    break;
                default:
            }
            return false;
        });
    }

    /** List cascade: 50ms apart, 12px rise with fade, at most 8 items, first show only. */
    void cascade(List<View> views, long startDelay) {
        if (reducedMotion()) {
            for (View v : views) { v.setAlpha(1f); v.setTranslationY(0); }
            return;
        }
        int n = 0;
        for (View v : views) {
            if (v == null) continue;
            v.animate().cancel();
            v.setAlpha(0f);
            v.setTranslationY(px(12));
            v.animate().alpha(1f).translationY(0).setStartDelay(startDelay + Math.min(n, 7) * 50L)
                    .setDuration(620).setInterpolator(EASE_IOS).start();
            n++;
        }
    }

    static void ellipsize(TextView v) { v.setSingleLine(true); v.setEllipsize(TextUtils.TruncateAt.END); }

    /** Round glass icon background: light from the upper left plus a 1px inner ring. */
    static final class GlassDrawable extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final CarTheme theme;

        GlassDrawable(CarTheme theme, int stroke) {
            this.theme = theme;
            ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(Math.max(1, stroke)); ring.setColor(theme.dark ? theme.line2 : theme.line);
            highlight.setStyle(Paint.Style.STROKE); highlight.setStrokeWidth(Math.max(1, stroke));
            highlight.setColor(theme.dark ? 0x14ffffff : 0x8cffffff);
        }

        @Override protected void onBoundsChange(android.graphics.Rect b) {
            float r = Math.max(1, Math.max(b.width(), b.height()) * 1.2f);
            fill.setShader(new RadialGradient(b.left + b.width() * .3f, b.top + b.height() * .2f, r,
                    new int[]{theme.glassA, theme.glassB}, new float[]{0, 1}, Shader.TileMode.CLAMP));
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY(), r = Math.min(b.width(), b.height()) / 2f;
            canvas.drawCircle(cx, cy, r, fill);
            canvas.drawCircle(cx, cy, r - ring.getStrokeWidth() / 2, ring);
            canvas.drawArc(cx - r + 2, cy - r + 2, cx + r - 2, cy + r - 2, 200, 140, false, highlight);
        }

        @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { fill.setColorFilter(cf); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
