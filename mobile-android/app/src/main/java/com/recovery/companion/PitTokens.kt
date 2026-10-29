package com.recovery.companion

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.graphics.PathParser
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** 화면 분위기(재질). 저장·서버 ID: Pearl = "paper", Graphite = "graphite", Umber = "umber". 차량 앱 따르기에서 셋 다 따른다. */
enum class Mood { PEARL, GRAPHITE, UMBER }

/** pitin.css의 역할 토큰. 색은 역할 이름으로만 쓴다. */
data class Tokens(
    val mood: Mood,
    val bg: Int, val bg2: Int, val surface: Int, val surface2: Int,
    val ink: Int, val ink2: Int, val ink3: Int,
    val line: Int, val line2: Int,
    val primary: Int, val onPrimary: Int,
    val accent: Int, val accentSoft: Int,
    val glassA: Int, val glassB: Int, val glassHighlight: Int,
    val warn: Int,
    val dark: Boolean
) {
    companion object {
        private fun hex(v: String) = Color.parseColor(v)
        val PEARL = Tokens(Mood.PEARL,
            hex("#F6F4F0"), hex("#EEEBE6"), hex("#FFFFFF"), hex("#FAF8F5"),
            hex("#141413"), hex("#57534D"), hex("#6E6A63"),
            hex("#E6E2DB"), hex("#D5D0C8"),
            hex("#161616"), hex("#FAF8F4"),
            hex("#59694E"), Color.argb(36, 89, 105, 78),
            hex("#FFFFFF"), hex("#ECE9E4"), Color.argb(140, 255, 255, 255),
            hex("#8A4B2A"), false)
        /** 차량 GraphitePalette.java(보호 원본)와 같은 값 */
        val GRAPHITE = Tokens(Mood.GRAPHITE,
            hex("#111112"), hex("#0B0B0C"), hex("#202327"), hex("#272A2F"),
            hex("#F4F1EC"), hex("#B6B6B8"), hex("#96979A"),
            hex("#2C2E31"), hex("#383B3E"),
            hex("#F4EFE7"), hex("#171615"),
            hex("#A7BA93"), Color.argb(41, 167, 186, 147),
            hex("#2E2E33"), hex("#19191C"), Color.argb(20, 255, 255, 255),
            hex("#E2A07A"), true)
        val UMBER = Tokens(Mood.UMBER,
            hex("#1C1611"), hex("#15100C"), hex("#251D17"), hex("#2D241D"),
            hex("#F4EFE8"), hex("#CDBCA8"), hex("#A8957F"),
            hex("#3A3129"), hex("#4B4036"),
            hex("#F7F1EA"), hex("#1C1712"),
            hex("#E2C39E"), Color.argb(41, 226, 195, 158),
            hex("#3A2E25"), hex("#221A14"), Color.argb(20, 255, 255, 255),
            hex("#F0B089"), true)
        fun of(mood: Mood) = when (mood) { Mood.PEARL -> PEARL; Mood.GRAPHITE -> GRAPHITE; Mood.UMBER -> UMBER }
        fun current(context: Context) = of(AppearancePreferences(context).mood())
    }
}

/** 시스템 sans(Noto Sans CJK)를 굵기만 바꿔 쓴다. 숫자는 시스템 sans 300/400. */
object PitType {
    private val cache = HashMap<Int, Typeface>()
    fun ko(weight: Int): Typeface = cache.getOrPut(weight) { Typeface.create(Typeface.DEFAULT, weight, false) }
    fun num(weight: Int): Typeface = cache.getOrPut(1000 + weight) { Typeface.create(Typeface.SANS_SERIF, weight, false) }
}

/** 곡선 네 개만 쓴다 (pitin-motion.css). */
object PitMotion {
    val easeIos: TimeInterpolator get() = PathInterpolator(0.32f, 0.72f, 0f, 1f)
    val easeWave: TimeInterpolator get() = PathInterpolator(0.65f, 0f, 0.35f, 1f)
    val easeBreath: TimeInterpolator get() = PathInterpolator(0.45f, 0f, 0.55f, 1f)
    val easeOut: TimeInterpolator get() = PathInterpolator(0f, 0f, 0.58f, 1f)
    val springSoft = Spring(0.74f, 220f)
    val springPop = Spring(0.61f, 320f)

    /** 감쇠 스프링을 정해진 길이의 보간기로 만든다. 끝에서 정확히 1에 멈춘다. */
    class Spring(private val damping: Float, stiffness: Float) : TimeInterpolator {
        private val omega = sqrt(stiffness.toDouble())
        private val dampedOmega = omega * sqrt(1.0 - damping * damping)
        val durationMs: Long = (6.9 / (damping * omega) * 1000).toLong()
        override fun getInterpolation(input: Float): Float {
            if (input >= 1f) return 1f
            val t = input * durationMs / 1000.0
            val envelope = exp(-damping * omega * t)
            return (1.0 - envelope * (cos(dampedOmega * t) + damping * omega / dampedOmega * sin(dampedOmega * t))).toFloat()
        }
    }

    /** 시스템 ‘애니메이션 삭제’(animator scale 0)이면 짧은 페이드로 대신한다. */
    fun reduced(): Boolean = !ValueAnimator.areAnimatorsEnabled()
    /** 숨 쉬는 장식은 앱의 ‘동작 줄이기’도 따른다. */
    fun decorStill(context: Context): Boolean = reduced() || PitPrefs(context).reduceMotion
}

/** 이 폰에만 남는 화면·쉼 취향. 건강 기록·토큰·위치는 넣지 않는다. */
class PitPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("pitin_redesign_prefs", Context.MODE_PRIVATE)
    var restMood: String
        get() = prefs.getString("restMood", "graphite") ?: "graphite"
        set(v) { prefs.edit().putString("restMood", v).apply() }
    var restStyle: String
        get() = prefs.getString("restStyle", "guided") ?: "guided"
        set(v) { prefs.edit().putString("restStyle", v).apply() }
    var reduceMotion: Boolean
        get() = prefs.getBoolean("reduceMotion", false)
        set(v) { prefs.edit().putBoolean("reduceMotion", v).apply() }
    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) { prefs.edit().putBoolean("haptics", v).apply() }
    var healthSource: String
        get() = prefs.getString("healthSource", "samsung") ?: "samsung"
        set(v) { prefs.edit().putString("healthSource", v).apply() }
    var healthPermissionAsked: Boolean
        get() = prefs.getBoolean("healthPermissionAsked", false)
        set(v) { prefs.edit().putBoolean("healthPermissionAsked", v).apply() }
    var introShownDay: String
        get() = prefs.getString("introShownDay", "") ?: ""
        set(v) { prefs.edit().putString("introShownDay", v).apply() }
}

object PitHaptics {
    fun tick(view: View) {
        if (PitPrefs(view.context).haptics) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
    fun confirm(view: View) {
        if (!PitPrefs(view.context).haptics) return
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK)
    }
}

/** 1.5px 선 아이콘 한 벌 (24 단위). 기호 문자 아이콘을 대신한다. */
object PitIcons {
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"
    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"
    val data: Map<String, List<String>> = mapOf(
        "today" to listOf("M4 11.5 12 5l8 6.5V19a1 1 0 0 1-1 1h-4.5v-5h-5v5H5a1 1 0 0 1-1-1z"),
        "sliders" to listOf("M4 7h10M18 7h2M4 17h4M12 17h8", circle(16f, 7f, 2f), circle(10f, 17f, 2f)),
        "link" to listOf("M9.5 14.5l5-5", "M8 11l-1.5 1.5a3.5 3.5 0 0 0 5 5L13 16", "M16 13l1.5-1.5a3.5 3.5 0 0 0-5-5L11 8"),
        "person" to listOf(circle(12f, 8.5f, 3.5f), "M5 20c1.2-3.6 4-5.5 7-5.5s5.8 1.9 7 5.5"),
        "pulse" to listOf("M3 12h4l2.5-6 5 12 2.5-6H21"),
        "car" to listOf("M5 16.5V11l2-4.5h10L19 11v5.5", "M3.5 16.5h17", circle(7.5f, 16.5f, 1.5f), circle(16.5f, 16.5f, 1.5f), "M5 11h14"),
        "chevron" to listOf("M9 6l6 6-6 6"),
        "back" to listOf("M15 6l-6 6 6 6"),
        "down" to listOf("M6 9l6 6 6-6"),
        "house" to listOf("M4 11.5 12 5l8 6.5V20H4z", "M10 20v-5h4v5"),
        "moon" to listOf("M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"),
        "doc" to listOf("M7 3.5h7l4 4V20H7z", "M10 11h5M10 14.5h5"),
        "cool" to listOf("M3 9h11a3 3 0 1 0-3-3", "M3 15h15a3 3 0 1 1-3 3"),
        "quiet" to listOf("M15 4.5a7.5 7.5 0 1 0 4.5 13.5A8 8 0 0 1 15 4.5z"),
        "gentle" to listOf("M4 17h16", "M7 17a5 5 0 0 1 10 0", "M12 7v2M6 10l1.4 1.4M18 10l-1.4 1.4"),
        "plus" to listOf("M12 5v14M5 12h14"),
        "plusSmall" to listOf("M12 6v12M6 12h12"),
        "minus" to listOf("M6 12h12"),
        "seat" to listOf("M7 4.5v9.5h9l2 5.5M7 14l-1 5.5M10 4.5H7"),
        "climate" to listOf("M12 3v18M4.2 7.5l15.6 9M4.2 16.5l15.6-9M9 4.5l3 2 3-2M9 19.5l3-2 3 2"),
        "light" to listOf("M9 17.5h6M10 20.5h4M12 3.5a5.5 5.5 0 0 1 3.5 9.7c-.6.5-1 1.2-1 2v.3h-5v-.3c0-.8-.4-1.5-1-2A5.5 5.5 0 0 1 12 3.5z"),
        // A phone with short buzz lines on both sides (not the speaker shape used for 소리).
        "vibe" to listOf(rect(8.5f, 4f, 7f, 16f, 1.8f), "M5.5 9v6M18.5 9v6", "M3 10.5v3M21 10.5v3"),
        "shield" to listOf("M12 3.5l7 3v5c0 4.3-2.9 7.7-7 9.5-4.1-1.8-7-5.2-7-9.5v-5z"),
        "check" to listOf("M5 12.5l4.5 4.5L19 7.5"),
        "info" to listOf(circle(12f, 12f, 8.5f), "M12 11v5M12 8h.01"),
        "alert" to listOf(circle(12f, 12f, 8.5f), "M12 7.5v5.5M12 16h.01"),
        "phone" to listOf(rect(7f, 3.5f, 10f, 17f, 2.5f), "M11 17.5h2"),
        "heart" to listOf("M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z", "M7.5 12h2l1.5-2.5 2 4 1.2-1.5h2.3"),
        "ac" to listOf(rect(3.5f, 6f, 17f, 7f, 2f), "M7 16.5c0 1.2-.8 2-2 2.5M12 16.5v3M17 16.5c0 1.2.8 2 2 2.5"),
        "purifier" to listOf(rect(6f, 3.5f, 12f, 17f, 3f), "M9 8h6M9 11h6", circle(12f, 16f, 1.5f)),
        "dirKeep" to listOf("M6 12h12"),
        "dirUp" to listOf("M12 19V7M7 11l5-5 5 5"),
        "dirDown" to listOf("M12 5v12M7 13l5 5 5-5"),
        "dirBoth" to listOf("M12 4v16M8 8l4-4 4 4M8 16l4 4 4-4"),
        "close" to listOf("M6 6l12 12M18 6 6 18"),
        "sound" to listOf("M4 9.5v5h3.5L12 18.5v-13L7.5 9.5z", "M16 9l4 6M20 9l-4 6"),
        "code" to listOf("M8 8l-4 4 4 4M16 8l4 4-4 4"),
        "spark" to listOf("M12 4v4M12 16v4M4 12h4M16 12h4")
    )
    private val parsed = HashMap<String, List<Path>>()
    fun paths(name: String): List<Path> = parsed.getOrPut(name) {
        (data[name] ?: data.getValue("info")).map { PathParser.createPathFromPathData(it) }
    }
    fun drawable(name: String, color: Int, stroke: Float = 1.5f) = IconDrawable(paths(name), color, stroke)
}

class IconDrawable(private val paths: List<Path>, color: Int, private val strokeUnits: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    var color: Int
        get() = paint.color
        set(v) { paint.color = v; invalidateSelf() }
    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val s = minOf(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f)
        canvas.scale(s, s)
        paint.strokeWidth = strokeUnits
        paths.forEach { canvas.drawPath(it, paint) }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
