package com.recovery.companion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

fun Context.dpf(v: Number) = v.toFloat() * resources.displayMetrics.density
fun Context.dpi(v: Number) = (v.toFloat() * resources.displayMetrics.density).roundToInt()

/** 세로/가로 묶음. 자식 사이 간격을 투명 구분선으로 준다 (GONE 자식은 건너뛴다). */
class GapLayout(context: Context, vertical: Boolean = true, gapDp: Float = 0f) : LinearLayout(context) {
    init {
        orientation = if (vertical) VERTICAL else HORIZONTAL
        gap(gapDp)
    }
    fun gap(dp: Float) {
        if (dp <= 0f) { showDividers = SHOW_DIVIDER_NONE; return }
        val px = context.dpi(dp)
        dividerDrawable = GradientDrawable().apply { setSize(px, px); setColor(Color.TRANSPARENT) }
        showDividers = SHOW_DIVIDER_MIDDLE
    }
}

/** 누름: 0.09s에 0.965배, 놓으면 spring-soft로 복귀. 동작 줄이기에서는 색만 바뀐다. */
@SuppressLint("ClickableViewAccessibility")
fun View.pressable(scale: Float = 0.965f): View {
    setOnTouchListener { v, e ->
        if (!v.isEnabled) return@setOnTouchListener false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (PitMotion.reduced()) v.alpha = 0.72f
                else v.animate().scaleX(scale).scaleY(scale).setDuration(90).setInterpolator(PitMotion.easeOut).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (PitMotion.reduced()) v.alpha = 1f
                else v.animate().scaleX(1f).scaleY(1f).setDuration(PitMotion.springSoft.durationMs).setInterpolator(PitMotion.springSoft).start()
            }
        }
        false
    }
    return this
}

/** 시간 흐름이 필요한 그림의 공통 바탕. 붙어 있을 때만 돌고, 동작 줄이기면 멈춘다. */
abstract class LoopView(context: Context) : View(context) {
    private var animator: ValueAnimator? = null
    protected var seconds = 0.0
    protected open val looping: Boolean get() = true
    override fun onAttachedToWindow() { super.onAttachedToWindow(); syncLoop() }
    override fun onDetachedFromWindow() { stopLoop(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); if (isVisible) syncLoop() else stopLoop() }
    fun syncLoop() {
        val want = looping && isAttachedToWindow && !PitMotion.decorStill(context)
        if (!want) { stopLoop(); invalidate(); return }
        if (animator != null) return
        val started = System.nanoTime()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { seconds = (System.nanoTime() - started) / 1e9; invalidate() }
            start()
        }
    }
    private fun stopLoop() { animator?.cancel(); animator = null }
    protected val still get() = animator == null
}

class GlassDrawable(private val t: Tokens) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hi = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    override fun onBoundsChange(bounds: Rect) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        fill.shader = RadialGradient(bounds.left + w * 0.3f, bounds.top + h * 0.2f, max(w, h) * 1.2f,
            t.glassA, t.glassB, Shader.TileMode.CLAMP)
    }
    override fun draw(canvas: Canvas) {
        val b = bounds; val r = min(b.width(), b.height()) / 2f
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        canvas.drawCircle(cx, cy, r, fill)
        val d = b.width() / 46f
        ring.strokeWidth = d; ring.color = if (t.dark) t.line2 else t.line
        canvas.drawCircle(cx, cy, r - d / 2f, ring)
        hi.strokeWidth = d; hi.color = t.glassHighlight
        canvas.drawArc(cx - r + d * 1.5f, cy - r + d * 1.5f, cx + r - d * 1.5f, cy + r - d * 1.5f, 200f, 140f, false, hi)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** 켜기/끄기. 손잡이가 spring-soft로 건너가고, 누르고 있으면 26→32로 늘어난다. */
class ToggleView(context: Context, private val t: Tokens, initial: Boolean, private val label: String, private val changed: (Boolean) -> Unit) : View(context) {
    var on = initial; private set
    private var pos = if (initial) 1f else 0f
    private var stretch = 0f
    private var color = if (initial) t.primary else t.line2
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 0, 0, 0) }
    private var anim: ValueAnimator? = null
    init {
        isClickable = true; isFocusable = true
        contentDescription = label
        setOnClickListener { set(!on, true); PitHaptics.tick(this); changed(on) }
    }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dpi(52), context.dpi(32))
    fun set(value: Boolean, animate: Boolean) {
        on = value
        anim?.cancel()
        val fromPos = pos; val fromColor = color; val toColor = if (value) t.primary else t.line2
        if (!animate || PitMotion.reduced()) { pos = if (value) 1f else 0f; color = toColor; invalidate(); return }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PitMotion.springSoft.durationMs; interpolator = PitMotion.springSoft
            addUpdateListener {
                val f = it.animatedValue as Float
                pos = fromPos + ((if (value) 1f else 0f) - fromPos) * f
                color = ColorUtils.blendARGB(fromColor, toColor, min(1f, it.animatedFraction * 2f))
                invalidate()
            }
            start()
        }
        sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED)
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animateStretch(1f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animateStretch(0f)
        }
        return super.onTouchEvent(event)
    }
    private fun animateStretch(to: Float) {
        if (PitMotion.reduced()) return
        ValueAnimator.ofFloat(stretch, to).apply { duration = 200; interpolator = PitMotion.easeIos; addUpdateListener { stretch = it.animatedValue as Float; invalidate() }; start() }
    }
    override fun onDraw(canvas: Canvas) {
        val d = context.dpf(1)
        paint.color = color
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
        val knobW = 26 * d + 6 * d * stretch
        val travel = width - 6 * d - knobW
        val left = 3 * d + travel * pos
        paint.color = if (on) t.onPrimary else Color.WHITE
        canvas.drawRoundRect(left, 4 * d, left + knobW, 4 * d + 26 * d, 13 * d, 13 * d, shadow)
        canvas.drawRoundRect(left, 3 * d, left + knobW, 3 * d + 26 * d, 13 * d, 13 * d, paint)
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"; info.isCheckable = true; info.isChecked = on
    }
}

/** 체크: 원이 spring-pop으로 피어나고 0.18s 뒤 체크가 한 획으로 그려진다. */
class CheckMarkView(context: Context, private val t: Tokens, initial: Boolean, private val round: Boolean = false) : View(context) {
    var on = initial; private set
    private var bloom = if (initial) 1f else 0f
    private var draw = if (initial) 1f else 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val check = PitIcons.paths("check").first()
    private val measure = PathMeasure(check, false)
    private val partial = Path()
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dpi(24), context.dpi(24))
    fun set(value: Boolean, animate: Boolean) {
        on = value
        if (!animate || PitMotion.reduced()) { bloom = if (value) 1f else 0f; draw = bloom; invalidate(); return }
        if (value) {
            bloom = 0f; draw = 0f
            ValueAnimator.ofFloat(0f, 1f).apply { duration = PitMotion.springPop.durationMs; interpolator = PitMotion.springPop; addUpdateListener { bloom = it.animatedValue as Float; invalidate() }; start() }
            ValueAnimator.ofFloat(0f, 1f).apply { startDelay = 180; duration = 360; interpolator = PitMotion.easeIos; addUpdateListener { draw = it.animatedValue as Float; invalidate() }; start() }
        } else {
            ValueAnimator.ofFloat(bloom, 0f).apply { duration = 180; addUpdateListener { bloom = it.animatedValue as Float; draw = bloom; invalidate() }; start() }
        }
    }
    override fun onDraw(canvas: Canvas) {
        val d = context.dpf(1); val s = width.toFloat()
        val r = if (round) s / 2f else 8 * d
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * d; paint.color = t.line2
        canvas.drawRoundRect(0.75f * d, 0.75f * d, s - 0.75f * d, s - 0.75f * d, r, r, paint)
        if (bloom > 0f) {
            paint.style = Paint.Style.FILL; paint.color = t.primary
            canvas.save(); canvas.scale(bloom, bloom, s / 2f, s / 2f)
            canvas.drawRoundRect(0f, 0f, s, s, r, r, paint)
            canvas.restore()
        }
        if (draw > 0f) {
            partial.reset(); measure.getSegment(0f, measure.length * draw, partial, true)
            canvas.save(); val k = s * 0.66f / 24f; canvas.translate(s * 0.17f, s * 0.17f); canvas.scale(k, k)
            stroke.color = t.onPrimary; stroke.strokeWidth = 2.2f
            canvas.drawPath(partial, stroke); canvas.restore()
        }
    }
}

class RadioView(context: Context, private val t: Tokens, var on: Boolean, private val holeColor: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var inner = if (on) 1f else 0f
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dpi(22), context.dpi(22))
    fun set(value: Boolean) {
        on = value
        if (PitMotion.reduced()) { inner = if (value) 1f else 0f; invalidate(); return }
        ValueAnimator.ofFloat(inner, if (value) 1f else 0f).apply { duration = PitMotion.springPop.durationMs; interpolator = PitMotion.springPop; addUpdateListener { inner = it.animatedValue as Float; invalidate() }; start() }
    }
    override fun onDraw(canvas: Canvas) {
        val d = context.dpf(1); val c = width / 2f
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * d; paint.color = if (on) t.ink else t.line2
        canvas.drawCircle(c, c, c - 0.75f * d, paint)
        if (inner > 0f) { paint.style = Paint.Style.FILL; paint.color = t.ink; canvas.drawCircle(c, c, 5.5f * d * inner, paint) }
    }
}

/** 이어짐 점. 2.8초마다 아주 작게 퍼진다. idle이면 회색 점. */
class LiveDotView(context: Context, private val t: Tokens, private val idle: Boolean, private val sizeDp: Float = 7f) : LoopView(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override val looping get() = !idle
    override fun onMeasure(w: Int, h: Int) { val s = context.dpi(sizeDp + 14); setMeasuredDimension(s, s) }
    override fun onDraw(canvas: Canvas) {
        val c = width / 2f; val r = context.dpf(sizeDp) / 2f
        if (!idle && !still) {
            val p = ((seconds % 2.8) / 2.8).toFloat()
            if (p < 0.7f) {
                val f = p / 0.7f
                paint.color = ColorUtils.setAlphaComponent(t.accent, ((1f - f) * 70).toInt())
                canvas.drawCircle(c, c, r + context.dpf(7) * f, paint)
            }
        }
        paint.color = if (idle) t.line2 else t.accent
        canvas.drawCircle(c, c, r, paint)
    }
}

/** 단계 표시: 완료(채운 원+체크) · 지금(이중 원) · 다음(빈 원). */
class StepDotView(context: Context, private val t: Tokens, private val state: Int, private val hole: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = PitIcons.drawable("check", t.onPrimary, 2.2f)
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dpi(24), context.dpi(24))
    override fun onDraw(canvas: Canvas) {
        val d = context.dpf(1); val c = width / 2f
        when (state) {
            2 -> { paint.style = Paint.Style.FILL; paint.color = t.ink; canvas.drawCircle(c, c, c, paint)
                val s = (14 * d).toInt(); icon.setBounds((c - s / 2).toInt(), (c - s / 2).toInt(), (c + s / 2).toInt(), (c + s / 2).toInt()); icon.draw(canvas) }
            1 -> { paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * d; paint.color = t.ink; canvas.drawCircle(c, c, c - 0.75f * d, paint)
                paint.style = Paint.Style.FILL; canvas.drawCircle(c, c, 6.5f * d, paint) }
            else -> { paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * d; paint.color = t.line2; canvas.drawCircle(c, c, c - 0.75f * d, paint) }
        }
    }
}

/** 돌아가는 고리 (보내는 중). */
class SpinnerRing(context: Context, private val color: Int) : LoopView(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    override fun onDraw(canvas: Canvas) {
        val d = context.dpf(1); paint.strokeWidth = 2f * d; paint.color = color
        val a = ((seconds % 0.9) / 0.9 * 360).toFloat()
        val inset = 2 * d
        canvas.drawArc(inset, inset, width - inset, height - inset, a, 270f, false, paint)
    }
}

/** 바뀐 자릿수만 위아래로 굴러 새 값에 멈춘다 (spring-soft). */
class RollingNumberView(context: Context, private val color: Int, private val textSizeSp: Float, weight: Int = 300, private val suffix: String = "°", private val suffixScale: Float = 0.5f) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@RollingNumberView.color; typeface = PitType.num(weight)
        textSize = textSizeSp * context.resources.displayMetrics.scaledDensity
    }
    private val suffixPaint = Paint(paint).apply { textSize = paint.textSize * suffixScale }
    private var text = ""
    private var positions = FloatArray(0)
    private var anims = arrayOfNulls<ValueAnimator>(0)
    private val digitW = paint.measureText("0")
    private val lineH = paint.fontMetrics.let { it.descent - it.ascent } * 1.0f
    var placeholder: String? = null
    fun setValue(value: String, animate: Boolean = true) {
        val old = text
        text = value
        val newPos = FloatArray(value.length) { i -> value[i].digitToIntOrNull()?.toFloat() ?: -1f }
        val oldPos = positions
        positions = FloatArray(value.length) { i -> if (old.length == value.length && i < oldPos.size) oldPos[i] else newPos[i] }
        anims.forEach { it?.cancel() }
        anims = arrayOfNulls(value.length)
        for (i in value.indices) {
            if (newPos[i] < 0f || positions[i] == newPos[i]) { positions[i] = newPos[i]; continue }
            if (!animate || PitMotion.reduced()) { positions[i] = newPos[i]; continue }
            val from = positions[i]; val to = newPos[i]
            anims[i] = ValueAnimator.ofFloat(from, to).apply {
                duration = PitMotion.springSoft.durationMs; interpolator = PitMotion.springSoft
                addUpdateListener { positions[i] = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        contentDescription = value + suffix
        requestLayout(); invalidate()
    }
    private fun charW(c: Char) = if (c.isDigit()) digitW else paint.measureText(c.toString())
    override fun onMeasure(w: Int, h: Int) {
        val shown = if (text.isEmpty()) placeholder ?: "" else text
        val width = shown.sumOf { charW(it).toDouble() }.toFloat() + suffixPaint.measureText(suffix) + context.dpf(2)
        setMeasuredDimension(width.toInt() + 1, lineH.toInt() + 1)
    }
    override fun onDraw(canvas: Canvas) {
        val fm = paint.fontMetrics
        val base = -fm.ascent
        var x = 0f
        canvas.save(); canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        if (text.isEmpty() && placeholder != null) { canvas.drawText(placeholder!!, 0f, base, paint); canvas.restore(); return }
        text.forEachIndexed { i, c ->
            val p = positions.getOrElse(i) { -1f }
            if (!c.isDigit() || p < 0f) { canvas.drawText(c.toString(), x, base, paint); x += charW(c); return@forEachIndexed }
            val lo = floor(p).toInt(); val frac = p - lo
            for (k in -1..2) {
                val digit = lo + k
                if (digit < 0 || digit > 9) continue
                val y = base + (k - frac) * lineH
                canvas.drawText(digit.toString(), x, y, paint)
            }
            x += digitW
        }
        canvas.drawText(suffix, x + context.dpf(1), base - (paint.textSize - suffixPaint.textSize) * 0.55f, suffixPaint)
        canvas.restore()
    }
}

/** 에어컨 호 다이얼: 240° 호, 하늘색→살구색 그라디언트, 손잡이 spring-soft. */
class ArcDialView(context: Context, private val t: Tokens) : View(context) {
    private var progress = 0.5f
    private var anim: ValueAnimator? = null
    var enabledLook = true
        set(v) { field = v; invalidate() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val prog = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(31, 0, 0, 0) }
    fun setProgress(value: Float, animate: Boolean) {
        anim?.cancel()
        if (!animate || PitMotion.reduced()) { progress = value; invalidate(); return }
        anim = ValueAnimator.ofFloat(progress, value).apply {
            duration = PitMotion.springSoft.durationMs; interpolator = PitMotion.springSoft
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }; start()
        }
    }
    override fun onDraw(canvas: Canvas) {
        val k = width / 300f
        canvas.save(); canvas.scale(k, k)
        val rect = RectF(32f, 32f, 268f, 268f)
        track.strokeWidth = 10f; track.color = t.line2
        canvas.drawArc(rect, 150f, 240f, false, track)
        prog.strokeWidth = 10f
        prog.shader = LinearGradient(32f, 0f, 268f, 0f, intArrayOf(Color.parseColor("#7FA6BE"), Color.parseColor("#C9BFA8"), Color.parseColor("#D9A86C")), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        val sweep = 240f * progress.coerceIn(0f, 1f)
        if (sweep > 0.5f) canvas.drawArc(rect, 150f, sweep, false, prog)
        val a = Math.toRadians((150f + 240f * progress).toDouble())
        val x = 150f + 118f * Math.cos(a).toFloat(); val y = 150f + 118f * Math.sin(a).toFloat()
        knob.color = Color.WHITE
        canvas.drawCircle(x, y + 1.5f, 13f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 0, 0, 0) })
        canvas.drawCircle(x, y, 13f, knob); knobRing.strokeWidth = 1f; canvas.drawCircle(x, y, 13f, knobRing)
        canvas.restore()
    }
}

/** 밝기 범위 막대: 6dp 선, 채움은 ink, 손잡이 28dp. */
class RangeView(context: Context, private val t: Tokens, private val min: Int, private val max: Int, private val step: Int, initial: Int, private val label: String, private val changed: (Int) -> Unit) : View(context) {
    var value = initial; private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(56, 0, 0, 0) }
    init { isFocusable = true; contentDescription = label }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dpi(36))
    private fun fraction() = (value - min).toFloat() / (max - min)
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        val pad = context.dpf(14)
        val f = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        val v = (min + ((max - min) * f / step).roundToInt() * step).coerceIn(min, max)
        if (v != value) { value = v; invalidate(); changed(v); if (v % 10 == 0) PitHaptics.tick(this) }
        if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun set(v: Int) { value = v; invalidate() }
    override fun onDraw(canvas: Canvas) {
        val d = context.dpf(1); val pad = 14 * d; val cy = height / 2f
        val x = pad + (width - 2 * pad) * fraction()
        paint.color = t.line2; canvas.drawRoundRect(pad, cy - 3 * d, width - pad, cy + 3 * d, 3 * d, 3 * d, paint)
        paint.color = t.ink; canvas.drawRoundRect(pad, cy - 3 * d, x, cy + 3 * d, 3 * d, 3 * d, paint)
        canvas.drawCircle(x, cy + 2 * d, 14 * d, shadow)
        paint.color = Color.WHITE; canvas.drawCircle(x, cy, 14 * d, paint)
    }
}

/** 오늘의 Pearl 막: 8초(4초 들숨·4초 날숨) 박자로 .94↔1.02. */
class BreathingImageView(context: Context, resId: Int) : ImageView(context) {
    private var anim: ValueAnimator? = null
    init { setImageResource(resId); scaleType = ScaleType.FIT_CENTER; adjustViewBounds = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { pivotX = width / 2f; pivotY = height * 0.6f }
        if (PitMotion.decorStill(context)) { scaleX = 0.98f; scaleY = 0.98f; return }
        anim = ValueAnimator.ofFloat(0.94f, 1.02f).apply {
            duration = 4000; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; interpolator = PitMotion.easeBreath
            addUpdateListener { val s = it.animatedValue as Float; scaleX = s; scaleY = s }
            start()
        }
    }
    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }
}

/** Graphite 경로와 점: 점이 경로 17%↔83%를 8초에 오간다. intro=true면 선이 그려지고 점이 톡 나타난다. */
class GraphiteArtView(context: Context, private val t: Tokens, private val intro: Boolean = false) : LoopView(context) {
    private val path = PathParser("M0 129.2 L57.6 129.2 C121.6 129.2 198.4 40.8 262.4 40.8 L320 40.8")
    private val measure = PathMeasure(path, false)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pos = FloatArray(2)
    private val seg = Path()
    var lineProgress = if (intro) 0f else 1f
    var dotScale = if (intro) 0f else 1f
    override val looping get() = !intro
    private companion object { fun PathParser(d: String): Path = androidx.core.graphics.PathParser.createPathFromPathData(d) }
    override fun onMeasure(w: Int, h: Int) { val width = MeasureSpec.getSize(w); setMeasuredDimension(width, (width * 170f / 320f).toInt()) }
    override fun onDraw(canvas: Canvas) {
        val k = width / 320f
        canvas.save(); canvas.scale(k, k)
        stroke.strokeWidth = 1.5f; stroke.color = if (intro) Color.parseColor("#8F9190") else ColorUtils.setAlphaComponent(t.ink, 178)
        seg.reset(); measure.getSegment(0f, measure.length * lineProgress, seg, true)
        canvas.drawPath(seg, stroke)
        val f = if (intro || still) 0.17f else {
            val p = ((seconds % 8.0) / 8.0).toFloat()
            val half = if (p < 0.5f) p * 2f else (1f - p) * 2f
            0.17f + 0.66f * PitMotion.easeBreath.getInterpolation(half)
        }
        measure.getPosTan(measure.length * f, pos, null)
        if (dotScale > 0f) {
            dot.color = t.bg; canvas.drawCircle(pos[0], pos[1], (6.5f + 4f) * dotScale, dot)
            dot.color = t.ink; canvas.drawCircle(pos[0], pos[1], 6.5f * dotScale, dot)
        }
        canvas.restore()
    }
}

/** 둥근(또는 타원) 가장자리로 사라지는 그림. Umber 등고선·리본에 쓴다. drift=true면 16초에 4dp 떠오른다. */
class MaskedImageView(context: Context, resId: Int, private val centerX: Float = 0.5f, private val centerY: Float = 0.5f,
                      private val radiusX: Float = 0.5f, private val radiusY: Float = 0.5f, private val solidStop: Float = 0.72f,
                      private val drift: Boolean = false, private val heightRatio: Float = 0f, private val breathe: Boolean = false) : LoopView(context) {
    private val bitmap = BitmapFactory.decodeResource(resources, resId)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val src = Rect(); private val dst = RectF()
    override val looping get() = drift || breathe
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onMeasure(w: Int, h: Int) {
        if (heightRatio > 0f) { val width = MeasureSpec.getSize(w); setMeasuredDimension(width, (width * heightRatio).toInt()) }
        else super.onMeasure(w, h)
    }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val rx = w * radiusX; val ry = h * radiusY
        val g = RadialGradient(0f, 0f, 1f, intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT), floatArrayOf(0f, solidStop, 1f), Shader.TileMode.CLAMP)
        g.setLocalMatrix(Matrix().apply { setScale(rx, ry); postTranslate(w * centerX, h * centerY) })
        mask.shader = g
        // center-crop
        val scale = max(w / bitmap.width.toFloat(), h / bitmap.height.toFloat())
        val sw = (w / scale).toInt(); val sh = (h / scale).toInt()
        src.set((bitmap.width - sw) / 2, (bitmap.height - sh) / 2, (bitmap.width + sw) / 2, (bitmap.height + sh) / 2)
        dst.set(0f, 0f, w.toFloat(), h.toFloat())
    }
    override fun onDraw(canvas: Canvas) {
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.save()
        if (drift && !still) {
            val p = ((seconds % 16.0) / 16.0).toFloat()
            val half = if (p < 0.5f) p * 2f else (1f - p) * 2f
            val e = PitMotion.easeBreath.getInterpolation(half)
            canvas.translate(0f, -context.dpf(4) * e)
            val s = 1f + 0.015f * e; canvas.scale(s, s, width / 2f, height / 2f)
        }
        if (breathe) {
            val s = if (still) 0.98f else {
                val p = ((seconds % 8.0) / 8.0).toFloat()
                val half = if (p < 0.5f) p * 2f else (1f - p) * 2f
                0.94f + 0.08f * PitMotion.easeBreath.getInterpolation(half)
            }
            canvas.scale(s, s, width / 2f, height * 0.6f)
        }
        canvas.drawBitmap(bitmap, src, dst, paint)
        canvas.restore()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mask)
        canvas.restoreToCount(layer)
    }
}

/** 파도 덮기: 새 바탕이 아래에서 물결치며 차오른다. 앞쪽 거품 물결(42%, 0.11s 먼저)과 본 물결 두 겹. */
class WaveOverlayView(context: Context) : View(context) {
    var color = Color.BLACK
    var glint = Color.WHITE
    var main = 0f
    var foam = 0f
    var phase = 0f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val foamCrest = androidx.core.graphics.PathParser.createPathFromPathData("M0 14 C40 14 60 32 100 32 S160 14 200 14 S260 32 300 32 S360 14 400 14 S460 32 500 32 S560 14 600 14 S660 32 700 32 S760 14 800 14 L800 46 L0 46 Z")
    private val mainCrest = androidx.core.graphics.PathParser.createPathFromPathData("M0 28 C40 28 60 8 100 8 S160 28 200 28 S260 8 300 8 S360 28 400 28 S460 8 500 8 S560 28 600 28 S660 8 700 8 S760 28 800 28 L800 46 L0 46 Z")
    private val mainLine = androidx.core.graphics.PathParser.createPathFromPathData("M0 28 C40 28 60 8 100 8 S160 28 200 28 S260 8 300 8 S360 28 400 28 S460 8 500 8 S560 28 600 28 S660 8 700 8 S760 28 800 28")
    private val tmp = Path(); private val m = Matrix()
    init { isClickable = true }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val crestH = context.dpf(46)
        fun sheet(progress: Float, crest: Path, alpha: Int, drift: Float, line: Path?) {
            if (progress <= 0f) return
            val top = h - progress * h * 1.18f
            val layer = if (alpha < 255) canvas.saveLayerAlpha(0f, maxOf(0f, top - crestH), w, h, alpha) else -1
            fill.color = color
            canvas.drawRect(0f, top - 1f, w, h, fill)
            m.reset(); m.setScale(2f * w / 800f, crestH / 46f); m.postTranslate(-w * drift, top - crestH + 1f)
            tmp.reset(); crest.transform(m, tmp); canvas.drawPath(tmp, fill)
            if (layer >= 0) canvas.restoreToCount(layer)
            if (line != null) {
                tmp.reset(); line.transform(m, tmp)
                glintPaint.color = glint; glintPaint.strokeWidth = context.dpf(1.2f)
                canvas.drawPath(tmp, glintPaint)
            }
        }
        val foamDrift = 1f - ((phase * 1.4f / 1.1f) % 1f)
        sheet(foam, foamCrest, 107, (foamDrift % 1f) * 0.5f + 0f, null)
        sheet(main, mainCrest, 255, (phase % 1f) * 0.5f, mainLine)
    }
}

fun View.fadeOutAndRemove(duration: Long = 200, end: () -> Unit = {}) {
    animate().alpha(0f).setDuration(duration).setListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) { (parent as? ViewGroup)?.removeView(this@fadeOutAndRemove); end() }
    }).start()
}

fun absDiff(a: Float, b: Float) = abs(a - b)
