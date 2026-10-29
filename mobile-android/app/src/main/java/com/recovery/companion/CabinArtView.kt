package com.recovery.companion

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import kotlin.math.roundToInt

/** cabin-art: 시트가 눕고, 바람이 흐르고, 빛이 번진다. 고른 값이 바로 그림에 반영된다. 동작 줄이기면 멈춘 그림. */
class CabinArtView(context: Context, private val t: Tokens, private val kind: String) : LoopView(context) {
    private fun p(d: String): Path = PathParser.createPathFromPathData(d)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    // 시트
    private val legs = p("M128 172 H236 M146 160 L140 172 M218 160 L224 172")
    private val cushion = p("M114 142 C114 130 122 124 134 124 L224 124 C238 124 246 132 246 144 C246 154 238 160 226 160 L130 160 C120 160 114 152 114 142 Z")
    private val back = p("M100 46 C98 34 108 28 118 32 C124 34 128 40 129 48 L140 132 C141 140 134 146 126 146 L120 146 C112 146 108 141 107 134 Z")
    private val head = p("M96 16 C96 8 106 4 114 8 C118 10 120 15 119 21 L118 26 C117 31 111 33 106 31 C100 29 97 24 96 16 Z")
    private val reclineArc = p("M74 40 A96 96 0 0 0 52 86")
    private val reclineArrow = p("M46 78 L52 87 L59 79")
    private val heatWave = p("M0 118 c-4 -5 4 -9 0 -14 c-4 -5 4 -9 0 -14 c-4 -5 4 -9 0 -14")
    // 에어컨
    private val wall = p("M268 30 L276 150 M276 150 L300 152")
    private val person = listOf(p("M236 74 C240 96 240 114 238 130"), p("M236 88 C226 100 214 106 204 110"), p("M238 130 L196 134 L192 172 L178 174"))
    // 조명
    private val roofLine = p("M40 24 C110 12 210 12 280 24")
    private val dashLine = p("M14 122 C30 84 90 72 160 72 C230 72 290 84 306 122")
    private val strips = listOf(p("M44 98 C96 84 224 84 276 98") to 3f, p("M10 134 C22 152 30 172 34 196") to 2.6f, p("M310 134 C298 152 290 172 286 196") to 2.6f)

    var seat = SeatPref(); private set
    var climate = ClimatePref(); private set
    var light = LightPref(); private set
    private var slatDeg = FloatArray(4)
    private var streamColor = Color.parseColor("#C9BFA8")
    private var lightColor = Color.parseColor("#6B6C70")
    private var stripOpacity = 0.55f
    private var footOpacity = 0f
    private var glow = 0f
    private val anims = mutableListOf<ValueAnimator>()

    override val looping: Boolean get() = when (kind) {
        "seat" -> seat.position == "manual" || seat.comfort == "heat" || seat.comfort == "vent"
        "climate" -> climate.mode == "custom"
        else -> light.mode == "custom"
    }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; contentDescription = when (kind) { "seat" -> "시트 그림"; "climate" -> "에어컨 바람 그림"; else -> "실내 조명 그림" } }
    override fun onMeasure(w: Int, h: Int) { val width = MeasureSpec.getSize(w); setMeasuredDimension(width, (width * 200f / 320f).roundToInt()) }

    private fun tween(from: Float, to: Float, ms: Long, interp: android.animation.TimeInterpolator = PitMotion.easeIos, set: (Float) -> Unit) {
        if (PitMotion.reduced() || !isAttachedToWindow) { set(to); invalidate(); return }
        anims += ValueAnimator.ofFloat(from, to).apply { duration = ms; interpolator = interp; addUpdateListener { set(it.animatedValue as Float); invalidate() }; start() }
    }
    private fun tweenColor(from: Int, to: Int, ms: Long, set: (Int) -> Unit) = tween(0f, 1f, ms) { set(ColorUtils.blendARGB(from, to, it)) }

    fun setSeat(v: SeatPref) { seat = v; syncLoop(); invalidate() }
    fun setClimate(v: ClimatePref) {
        climate = v
        val live = v.mode == "custom"
        val target = when (if (live) v.dir else "keep") { "upper" -> floatArrayOf(-16f, -16f, -16f, -16f); "lower" -> floatArrayOf(16f, 16f, 16f, 16f); "both" -> floatArrayOf(-16f, 16f, -16f, 16f); else -> FloatArray(4) }
        for (i in 0..3) { val from = slatDeg[i]; val idx = i; tween(from, target[i], PitMotion.springSoft.durationMs, PitMotion.springSoft) { slatDeg[idx] = it } }
        val tt = ((v.temp - 18) / 10).toFloat().coerceIn(0f, 1f)
        val c = ColorUtils.blendARGB(Color.parseColor("#7FA6BE"), Color.parseColor("#D9A86C"), tt)
        val fromC = streamColor; tweenColor(fromC, c, 700) { streamColor = it }
        syncLoop(); invalidate()
    }
    fun setLight(v: LightPref) {
        light = v
        val on = v.mode == "custom"
        val c = if (on) Color.parseColor(CabinPrefs.COLORS.getValue(v.color).second) else if (v.mode == "off") Color.parseColor("#2E2F33") else Color.parseColor("#6B6C70")
        val fromC = lightColor; tweenColor(fromC, c, 800) { lightColor = it }
        val so = if (on) 0.35f + 0.65f * v.bright / 100f else if (v.mode == "off") 0.9f else 0.55f
        val fo = if (on) 0.06f + 0.3f * v.bright / 100f else 0f
        val gl = if (on) 2f + v.bright / 8f else 0f
        val s0 = stripOpacity; val f0 = footOpacity; val g0 = glow
        tween(0f, 1f, 800) { stripOpacity = s0 + (so - s0) * it; footOpacity = f0 + (fo - f0) * it; glow = g0 + (gl - g0) * it }
        syncLoop(); invalidate()
    }
    fun snap(seat: SeatPref, climate: ClimatePref, light: LightPref) {
        anims.forEach { it.cancel() }; anims.clear()
        this.seat = seat; this.climate = climate; this.light = light
        val live = climate.mode == "custom"
        slatDeg = when (if (live) climate.dir else "keep") { "upper" -> floatArrayOf(-16f, -16f, -16f, -16f); "lower" -> floatArrayOf(16f, 16f, 16f, 16f); "both" -> floatArrayOf(-16f, 16f, -16f, 16f); else -> FloatArray(4) }
        streamColor = ColorUtils.blendARGB(Color.parseColor("#7FA6BE"), Color.parseColor("#D9A86C"), ((climate.temp - 18) / 10).toFloat().coerceIn(0f, 1f))
        val on = light.mode == "custom"
        lightColor = if (on) Color.parseColor(CabinPrefs.COLORS.getValue(light.color).second) else if (light.mode == "off") Color.parseColor("#2E2F33") else Color.parseColor("#6B6C70")
        stripOpacity = if (on) 0.35f + 0.65f * light.bright / 100f else if (light.mode == "off") 0.9f else 0.55f
        footOpacity = if (on) 0.06f + 0.3f * light.bright / 100f else 0f
        glow = if (on) 2f + light.bright / 8f else 0f
        syncLoop(); invalidate()
    }
    override fun onDetachedFromWindow() { anims.forEach { it.cancel() }; anims.clear(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val k = width / 320f
        canvas.save(); canvas.scale(k, k)
        when (kind) { "seat" -> drawSeat(canvas); "climate" -> drawClimate(canvas); else -> drawLight(canvas, k) }
        canvas.restore()
    }
    private fun line(c: Canvas, path: Path, color: Int, w: Float) { stroke.color = color; stroke.strokeWidth = w; stroke.pathEffect = null; c.drawPath(path, stroke) }

    private fun drawSeat(c: Canvas) {
        stroke.pathEffect = null
        stroke.color = t.line2; stroke.strokeWidth = 1.5f; c.drawLine(30f, 186f, 290f, 186f, stroke)
        line(c, legs, t.ink, 1.8f)
        val heat = seat.comfort == "heat"; val vent = seat.comfort == "vent"
        val overlay = if (heat) Color.parseColor("#D08C5B") else Color.parseColor("#7FA6BE")
        val overlayAlpha = if (heat || vent) ((0.08f + seat.level * 0.07f) * 255).toInt() else 0
        fun body(path: Path) {
            fill.color = t.surface; c.drawPath(path, fill)
            if (overlayAlpha > 0) { fill.color = ColorUtils.setAlphaComponent(overlay, overlayAlpha); c.drawPath(path, fill) }
            line(c, path, t.ink, 1.8f)
        }
        body(cushion)
        if (vent) { fill.color = Color.parseColor("#7FA6BE"); var x = 146f; while (x <= 222f) { c.drawCircle(x, 134f, 1.6f, fill); c.drawCircle(x + 6f, 146f, 1.6f, fill); x += 12f } }
        val manual = seat.position == "manual"
        var angle = 0f
        if (manual && !still) {
            val p = ((seconds % 7.0) / 7.0).toFloat()
            angle = when {
                p < 0.12f -> 0f
                p < 0.42f -> -15f * PitMotion.easeBreath.getInterpolation((p - 0.12f) / 0.30f)
                p < 0.68f -> -15f
                else -> -15f * (1f - PitMotion.easeBreath.getInterpolation((p - 0.68f) / 0.32f))
            }
        }
        c.save(); c.rotate(angle, 124f, 142f); body(back); body(head); c.restore()
        if (manual) {
            stroke.color = t.ink3; stroke.strokeWidth = 1.4f; stroke.pathEffect = DashPathEffect(floatArrayOf(3f, 5f), 0f)
            c.drawPath(reclineArc, stroke); stroke.pathEffect = null; c.drawPath(reclineArrow, stroke)
        }
        if (heat || vent) for (i in 0 until seat.level) {
            val x = 160f + i * 22f
            if (heat) {
                var ty = 0f; var a = 0.85f
                if (!still) {
                    val period = 2.4; val pp = (((seconds - i * 0.5) % period + period) % period / period).toFloat()
                    val e = PitMotion.easeBreath.getInterpolation(pp)
                    ty = 8f - 20f * e
                    a = if (pp < 0.35f) 0.95f * pp / 0.35f else 0.95f * (1f - (pp - 0.35f) / 0.65f)
                }
                c.save(); c.translate(x, ty)
                stroke.color = ColorUtils.setAlphaComponent(Color.parseColor("#D08C5B"), (a * 255).toInt()); stroke.strokeWidth = 2.2f
                c.drawPath(heatWave, stroke); c.restore()
            } else {
                val dur = floatArrayOf(1.4f, 1.7f, 2f)[i]
                val phase = if (still) 0f else (28f * ((seconds % dur) / dur).toFloat())
                stroke.color = Color.parseColor("#7FA6BE"); stroke.strokeWidth = 2.2f
                stroke.pathEffect = DashPathEffect(floatArrayOf(5f, 9f), phase)
                c.drawLine(x, 120f, x, 80f, stroke); stroke.pathEffect = null
            }
        }
    }

    private fun drawClimate(c: Canvas) {
        line(c, wall, t.line2, 3f)
        fill.color = t.surface; c.drawRoundRect(20f, 72f, 78f, 128f, 14f, 14f, fill)
        stroke.color = t.ink; stroke.strokeWidth = 1.8f; c.drawRoundRect(20f, 72f, 78f, 128f, 14f, 14f, stroke)
        floatArrayOf(84f, 94f, 104f, 114f).forEachIndexed { i, y -> c.save(); c.rotate(slatDeg[i], 49f, y); c.drawLine(31f, y, 67f, y, stroke); c.restore() }
        val live = climate.mode == "custom"
        if (live && climate.ac == "on") {
            c.save(); c.rotate(if (still) 0f else (60f * ((seconds % 6.0) / 6.0)).toFloat(), 49f, 57f)
            stroke.color = Color.parseColor("#7FA6BE"); stroke.strokeWidth = 1.6f
            c.drawLine(49f, 50f, 49f, 64f, stroke); c.drawLine(43f, 53.5f, 55f, 60.5f, stroke); c.drawLine(43f, 60.5f, 55f, 53.5f, stroke)
            c.restore()
        }
        val n = mapOf("auto" to 2, "low" to 2, "medium" to 3, "high" to 4)[climate.fan] ?: 2
        val spd = mapOf("auto" to 1.9, "low" to 2.6, "medium" to 1.5, "high" to 1.0)[climate.fan] ?: 1.6
        fun spread(a: Float, b: Float, count: Int, i: Int) = if (count == 1) (a + b) / 2 else a + (b - a) * i / (count - 1)
        val sets = when (climate.dir) { "lower" -> listOf(146f to 170f); "both" -> listOf(70f to 96f, 148f to 170f); else -> listOf(70f to 98f) }
        stroke.strokeWidth = 2.2f
        sets.forEach { (ty1, ty2) ->
            for (i in 0 until n) {
                val y0 = spread(90f, 110f, n, i); val yt = spread(ty1, ty2, n, i)
                val path = Path().apply { moveTo(84f, y0); cubicTo(130f, y0, 160f, yt, 206f, yt) }
                val phase = if (!live || still) 0f else (-60.0 * (((seconds + i * 0.37) % spd) / spd)).toFloat()
                stroke.color = ColorUtils.setAlphaComponent(streamColor, if (live) 255 else 71)
                stroke.pathEffect = DashPathEffect(floatArrayOf(3f, 12f), phase)
                c.drawPath(path, stroke)
            }
        }
        stroke.pathEffect = null
        stroke.color = t.ink; stroke.strokeWidth = 2f
        c.drawCircle(232f, 60f, 13f, stroke); person.forEach { c.drawPath(it, stroke) }
    }

    private fun drawLight(c: Canvas, k: Float) {
        fill.color = Color.parseColor("#141416"); fill.maskFilter = null
        c.drawRoundRect(0f, 0f, 320f, 200f, 22f, 22f, fill)
        if (footOpacity > 0.01f) {
            c.save(); c.clipRect(0f, 0f, 320f, 200f)
            fill.color = ColorUtils.setAlphaComponent(lightColor, (footOpacity * 255).toInt()); fill.maskFilter = BlurMaskFilter(12f * k, BlurMaskFilter.Blur.NORMAL)
            c.drawOval(40f, 180f, 280f, 212f, fill); fill.maskFilter = null; c.restore()
        }
        line(c, roofLine, Color.parseColor("#2E2F33"), 1.4f)
        line(c, dashLine, Color.parseColor("#4A4B4F"), 1.4f)
        val breathing = if (light.mode == "custom" && !still) {
            val p = ((seconds % 6.0) / 6.0).toFloat(); val half = if (p < 0.5f) p * 2 else (1 - p) * 2
            0.8f + 0.2f * PitMotion.easeBreath.getInterpolation(half)
        } else 1f
        val alpha = (stripOpacity * breathing * 255).toInt().coerceIn(0, 255)
        strips.forEach { (path, w) ->
            if (glow > 0.5f) {
                glowPaint.color = ColorUtils.setAlphaComponent(lightColor, (alpha * 0.9f).toInt()); glowPaint.strokeWidth = w + 2f
                glowPaint.maskFilter = BlurMaskFilter(glow * k, BlurMaskFilter.Blur.NORMAL)
                c.drawPath(path, glowPaint)
            }
            stroke.color = ColorUtils.setAlphaComponent(lightColor, alpha); stroke.strokeWidth = w; c.drawPath(path, stroke)
        }
        stroke.color = Color.parseColor("#4A4B4F"); stroke.strokeWidth = 1.4f
        c.drawCircle(104f, 152f, 34f, stroke); c.drawCircle(104f, 152f, 8f, stroke)
        c.drawRoundRect(146f, 114f, 186f, 186f, 8f, 8f, stroke)
    }
}
