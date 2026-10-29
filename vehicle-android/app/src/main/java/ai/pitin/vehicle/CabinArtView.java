package ai.pitin.vehicle;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Moving illustrations from cabin-art (spec 6-1): seat side view (heat waves / vent lines, slow recline
 * when "직접"), climate vent + airflow streams by fan and temperature, interior light strips that breathe.
 * Drawn on a 320×200 grid. Reduced motion shows a still frame.
 */
final class CabinArtView extends View {
    enum Kind { SEAT, CLIMATE, LIGHT }

    private static final int HEAT = 0xffd08c5b, COOL = 0xff7fa6be, WARM_AIR = 0xffd9a86c;
    private static final Path CUSHION = SvgPath.parse("M114 142 C114 130 122 124 134 124 L224 124 C238 124 246 132 246 144 C246 154 238 160 226 160 L130 160 C120 160 114 152 114 142 Z");
    private static final Path LEGS = SvgPath.parse("M128 172 H236 M146 160 L140 172 M218 160 L224 172");
    private static final Path BACK = SvgPath.parse("M100 46 C98 34 108 28 118 32 C124 34 128 40 129 48 L140 132 C141 140 134 146 126 146 L120 146 C112 146 108 141 107 134 Z");
    private static final Path HEAD = SvgPath.parse("M96 16 C96 8 106 4 114 8 C118 10 120 15 119 21 L118 26 C117 31 111 33 106 31 C100 29 97 24 96 16 Z");
    private static final Path ARC = SvgPath.parse("M74 40 A96 96 0 0 0 52 86");
    private static final Path ARROW = SvgPath.parse("M46 78 L52 87 L59 79");
    private static final Path DOOR = SvgPath.parse("M268 30 L276 150 M276 150 L300 152");
    private static final Path PERSON = SvgPath.parse("M245 60 a13 13 0 1 0 -26 0 a13 13 0 1 0 26 0 M236 74 C240 96 240 114 238 130 M236 88 C226 100 214 106 204 110 M238 130 L196 134 L192 172 L178 174");
    private static final Path ROOF = SvgPath.parse("M40 24 C110 12 210 12 280 24");
    private static final Path DASH = SvgPath.parse("M14 122 C30 84 90 72 160 72 C230 72 290 84 306 122");
    private static final Path[] STRIPS = {SvgPath.parse("M44 98 C96 84 224 84 276 98"), SvgPath.parse("M10 134 C22 152 30 172 34 196"), SvgPath.parse("M310 134 C298 152 290 172 286 196")};

    private final Kind kind;
    private final CarTheme theme;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tmp = new Path();
    private final Matrix m = new Matrix();
    private ValueAnimator ticker;
    private boolean reduced;
    private float scale = 1, ox, oy;

    // seat
    private String position = "keep", comfort = "keep";
    private int level = 1;
    // climate
    private String mode = "keep", fan = "auto", dir = "keep", ac = "keep";
    private float temp = 24f;
    private final float[] slat = new float[4], slatFrom = new float[4], slatTo = new float[4];
    private long slatStart = -1;
    // light
    private String lightMode = "keep", color = "cream";
    private int bright = 60;

    CabinArtView(Context context, Kind kind, CarTheme theme) {
        super(context);
        this.kind = kind;
        this.theme = theme;
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        if (kind == Kind.LIGHT) setLayerType(LAYER_TYPE_SOFTWARE, null); // blur glow
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription(kind == Kind.SEAT ? "시트 그림" : kind == Kind.CLIMATE ? "에어컨 바람 그림" : "실내 조명 그림");
    }

    void setReduced(boolean value) { reduced = value; updateTicker(); invalidate(); }

    void setSeat(CabinSettings.Seat s) { position = s.position; comfort = s.comfort; level = s.level; updateTicker(); invalidate(); }

    void setClimate(CabinSettings.Climate c) {
        boolean wasLive = "custom".equals(mode);
        mode = c.mode; fan = c.fan; dir = c.dir; ac = c.ac; temp = c.tempTenths / 10f;
        float[] target = slatTarget();
        boolean changed = false;
        for (int i = 0; i < 4; i++) if (target[i] != slatTo[i]) changed = true;
        if (changed || wasLive != "custom".equals(mode)) {
            System.arraycopy(slat, 0, slatFrom, 0, 4);
            System.arraycopy(target, 0, slatTo, 0, 4);
            slatStart = reduced ? -1 : SystemClock.uptimeMillis();
            if (reduced) System.arraycopy(target, 0, slat, 0, 4);
        }
        updateTicker(); invalidate();
    }

    void setLight(CabinSettings.Light l) { lightMode = l.mode; color = l.color; bright = l.bright; updateTicker(); invalidate(); }

    private float[] slatTarget() {
        if (!"custom".equals(mode)) return new float[]{0, 0, 0, 0};
        switch (dir) {
            case "upper": return new float[]{-16, -16, -16, -16};
            case "lower": return new float[]{16, 16, 16, 16};
            case "both": return new float[]{-16, 16, -16, 16};
            default: return new float[]{0, 0, 0, 0};
        }
    }

    private boolean animated() {
        if (reduced) return false;
        switch (kind) {
            case SEAT: return "heat".equals(comfort) || "vent".equals(comfort) || "manual".equals(position);
            case CLIMATE: return true; // streams flow when custom; slats settle
            default: return "custom".equals(lightMode);
        }
    }

    private void updateTicker() {
        boolean want = animated() && isAttachedToWindow();
        if (want && ticker == null) {
            ticker = ValueAnimator.ofFloat(0, 1);
            ticker.setDuration(1000);
            ticker.setRepeatCount(ValueAnimator.INFINITE);
            ticker.addUpdateListener(a -> invalidate());
            ticker.start();
        } else if (!want && ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); updateTicker(); }
    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); if (ticker != null) { ticker.cancel(); ticker = null; } }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = Math.round(w * 200f / 320f);
        if (MeasureSpec.getMode(hSpec) == MeasureSpec.EXACTLY) h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        scale = Math.min(w / 320f, h / 200f);
        ox = (w - 320 * scale) / 2f;
        oy = (h - 200 * scale) / 2f;
    }

    private float now() { return reduced ? 0 : SystemClock.uptimeMillis() / 1000f; }

    @Override protected void onDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.translate(ox, oy);
        canvas.scale(scale, scale);
        switch (kind) {
            case SEAT: drawSeat(canvas); break;
            case CLIMATE: drawClimate(canvas); break;
            default: drawLight(canvas);
        }
        canvas.restoreToCount(save);
    }

    private void stroke(int color, float width) {
        p.setShader(null); p.setPathEffect(null); p.setMaskFilter(null);
        p.setStyle(Paint.Style.STROKE); p.setColor(color); p.setStrokeWidth(width); p.setAlpha(Color.alpha(color));
    }

    private void fill(int color) {
        p.setShader(null); p.setPathEffect(null); p.setMaskFilter(null);
        p.setStyle(Paint.Style.FILL); p.setColor(color);
    }

    // ── seat ───────────────────────────────────────────────
    private void drawSeat(Canvas c) {
        boolean heat = "heat".equals(comfort), vent = "vent".equals(comfort), manual = "manual".equals(position);
        float t = now();
        stroke(theme.line2, 1.5f);
        c.drawLine(30, 186, 290, 186, p);
        stroke(theme.ink, 1.8f);
        c.drawPath(LEGS, p);
        int tint = heat ? HEAT : vent ? COOL : 0;
        int tintAlpha = (heat || vent) ? Math.round(255 * (0.08f + level * 0.07f)) : 0;
        drawPart(c, CUSHION, tint, tintAlpha);
        if (vent) {
            fill(COOL);
            for (int x = 146; x <= 222; x += 12) { c.drawCircle(x, 134, 1.6f, p); c.drawCircle(x + 6, 146, 1.6f, p); }
        }
        int s = c.save();
        if (manual) c.rotate(recline(t), 124, 142);
        drawPart(c, BACK, tint, tintAlpha);
        drawPart(c, HEAD, 0, 0);
        c.restoreToCount(s);
        if (manual) {
            stroke(theme.ink3, 1.4f);
            p.setPathEffect(new DashPathEffect(new float[]{3, 5}, 0));
            c.drawPath(ARC, p);
            p.setPathEffect(null);
            c.drawPath(ARROW, p);
        }
        if (heat || vent) {
            for (int i = 0; i < level; i++) {
                float x = 160 + i * 22;
                if (heat) {
                    float cycle = reduced ? .4f : (((t - i * .5f) % 2.4f) + 2.4f) % 2.4f / 2.4f;
                    float dy = 8 - 20 * cycle;
                    float alpha = reduced ? .85f : cycle < .35f ? cycle / .35f * .95f : .95f * (1 - (cycle - .35f) / .65f);
                    tmp.reset();
                    tmp.moveTo(x, 118 + dy);
                    for (int k = 0; k < 3; k++) {
                        float y0 = 118 + dy - k * 14;
                        tmp.cubicTo(x - 4, y0 - 5, x + 4, y0 - 9, x, y0 - 14);
                    }
                    stroke(HEAT, 2.2f);
                    p.setAlpha(Math.round(255 * alpha));
                    c.drawPath(tmp, p);
                } else {
                    float dur = i == 0 ? 1.4f : i == 1 ? 1.7f : 2f;
                    float phase = reduced ? 0 : -28f * ((t % dur) / dur);
                    stroke(COOL, 2.2f);
                    p.setPathEffect(new DashPathEffect(new float[]{5, 9}, phase));
                    c.drawLine(x, 120, x, 80, p);
                }
            }
        }
    }

    /** 7s cycle: 0–12% upright, 42–68% at -15°, back to 0 (ease-in-out between). */
    private float recline(float t) {
        if (reduced) return 0;
        float f = (t % 7f) / 7f;
        if (f < .12f) return 0;
        if (f < .42f) return -15 * CarKit.EASE_IN_OUT.getInterpolation((f - .12f) / .30f);
        if (f < .68f) return -15;
        return -15 * (1 - CarKit.EASE_IN_OUT.getInterpolation((f - .68f) / .32f));
    }

    private void drawPart(Canvas c, Path path, int tint, int tintAlpha) {
        fill(theme.surface);
        c.drawPath(path, p);
        if (tintAlpha > 0) { fill(tint); p.setAlpha(tintAlpha); c.drawPath(path, p); }
        stroke(theme.ink, 1.8f);
        c.drawPath(path, p);
    }

    // ── climate ────────────────────────────────────────────
    private void drawClimate(Canvas c) {
        boolean live = "custom".equals(mode);
        float t = now();
        stroke(theme.line2, 3f);
        c.drawPath(DOOR, p);
        fill(theme.surface);
        c.drawRoundRect(20, 72, 78, 128, 14, 14, p);
        stroke(theme.ink, 1.8f);
        c.drawRoundRect(20, 72, 78, 128, 14, 14, p);
        float k = 1;
        if (slatStart >= 0) {
            float f = Math.min(1, (SystemClock.uptimeMillis() - slatStart) / 700f);
            k = CarKit.SPRING_SOFT.getInterpolation(f);
            if (f >= 1) slatStart = -1;
        }
        int[] ys = {84, 94, 104, 114};
        for (int i = 0; i < 4; i++) {
            slat[i] = slatStart >= 0 ? slatFrom[i] + (slatTo[i] - slatFrom[i]) * k : slatTo[i];
            int s = c.save();
            c.rotate(slat[i], 49, ys[i]);
            c.drawLine(31, ys[i], 67, ys[i], p);
            c.restoreToCount(s);
        }
        if (live && "on".equals(ac)) {
            int s = c.save();
            c.rotate(reduced ? 0 : (t % 6f) / 6f * 60f, 49, 57);
            stroke(COOL, 1.6f);
            c.drawLine(49, 50, 49, 64, p);
            c.drawLine(43, 53.5f, 55, 60.5f, p);
            c.drawLine(43, 60.5f, 55, 53.5f, p);
            c.restoreToCount(s);
        }
        int n = "medium".equals(fan) ? 3 : "high".equals(fan) ? 4 : 2;
        float spd = "medium".equals(fan) ? 1.9f : "high".equals(fan) ? 1.0f : 2.6f;
        int color = CarTheme.lerp(COOL, WARM_AIR, (temp - 18f) / 10f);
        float[][] targets = "lower".equals(dir) && live ? new float[][]{{146, 170}}
                : "both".equals(dir) && live ? new float[][]{{70, 96}, {148, 170}} : new float[][]{{70, 98}};
        for (float[] band : targets) {
            for (int i = 0; i < n; i++) {
                float y0 = spread(90, 110, n, i), yt = spread(band[0], band[1], n, i);
                tmp.reset();
                tmp.moveTo(84, y0);
                tmp.cubicTo(130, y0, 160, yt, 206, yt);
                stroke(color, 2.2f);
                float phase = live && !reduced ? -60f * ((((t + i * .37f) % spd) + spd) % spd / spd) : 0;
                p.setPathEffect(new DashPathEffect(new float[]{3, 12}, phase));
                p.setAlpha(live ? 255 : Math.round(255 * .28f));
                c.drawPath(tmp, p);
            }
        }
        stroke(theme.ink, 2f);
        c.drawPath(PERSON, p);
    }

    private static float spread(float a, float b, int n, int i) { return n == 1 ? (a + b) / 2 : a + (b - a) * i / (n - 1); }

    // ── light ──────────────────────────────────────────────
    private int lightColor() {
        if ("custom".equals(lightMode)) {
            switch (color) { case "neutral": return 0xffe8e6de; case "sage": return 0xffa5b198; case "mist": return 0xffa4bbc9; default: return 0xffe8cfa6; }
        }
        return "off".equals(lightMode) ? 0xff2e2f33 : 0xff6b6c70;
    }

    private void drawLight(Canvas c) {
        boolean on = "custom".equals(lightMode);
        float t = now();
        int lc = lightColor();
        fill(0xff141416);
        c.drawRoundRect(0, 0, 320, 200, 22, 22, p);
        if (on) {
            float foot = .06f + .3f * bright / 100f;
            RadialGradient g = new RadialGradient(160, 196, 132, new int[]{CarTheme.alpha(lc, Math.round(255 * foot)), CarTheme.alpha(lc, 0)}, new float[]{.3f, 1}, Shader.TileMode.CLAMP);
            m.setScale(1f, 16f / 120f * 1.6f, 160, 196);
            g.setLocalMatrix(m);
            p.setStyle(Paint.Style.FILL); p.setPathEffect(null); p.setColor(0xffffffff); p.setShader(g);
            c.save();
            c.clipRect(0, 0, 320, 200);
            c.drawRect(0, 150, 320, 200, p);
            c.restore();
            p.setShader(null);
        }
        stroke(0xff2e2f33, 1.4f);
        c.drawPath(ROOF, p);
        stroke(0xff4a4b4f, 1.4f);
        c.drawPath(DASH, p);
        float breathe = on && !reduced ? .8f + .2f * (float) (0.5 - 0.5 * Math.cos((t % 6f) / 6f * Math.PI * 2)) : 1f;
        float strip = on ? .35f + .65f * bright / 100f : "off".equals(lightMode) ? .9f : .55f;
        float glowPx = on ? 2 + bright / 8f : 0;
        for (int i = 0; i < STRIPS.length; i++) {
            float w = i == 0 ? 3f : 2.6f;
            if (glowPx > 0) {
                glow.setColor(lc);
                glow.setAlpha(Math.round(255 * strip * breathe * .85f));
                glow.setStrokeWidth(w + glowPx * .6f);
                glow.setMaskFilter(new BlurMaskFilter(Math.max(1, glowPx * scale * .6f) / scale, BlurMaskFilter.Blur.NORMAL));
                c.drawPath(STRIPS[i], glow);
            }
            stroke(lc, w);
            p.setAlpha(Math.round(255 * strip * breathe));
            c.drawPath(STRIPS[i], p);
        }
        stroke(0xff4a4b4f, 1.4f);
        c.drawCircle(104, 152, 34, p);
        c.drawCircle(104, 152, 8, p);
        c.drawRoundRect(146, 114, 186, 186, 8, 8, p);
    }

    private static final class Color { static int alpha(int c) { return c >>> 24; } }
}
