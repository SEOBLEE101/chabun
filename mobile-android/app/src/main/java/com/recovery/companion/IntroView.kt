package com.recovery.companion

import android.animation.ValueAnimator
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 앱 인트로 (2026-10-28 개정): 선·그림 없이 글자만.
 * 가운데에서 ‘차분’이 한 글자씩 떠오르고 올리브 점이 톡 → 1.35s에 머리말 로고 자리로 날아가(PhoneShell.leaveIntro)
 * 같은 크기·위치의 진짜 머리말 로고로 바뀐다. 세 분위기 모두 같은 움직임이고 색만 분위기를 따른다.
 */
object IntroView {
    const val START_SCALE = 2.4f
    const val HOLD_MS = 1350L

    /**
     * 한 글자의 떠오름. 글자를 따로 떼지 않고 머리말 로고와 같은 TextView 안에서 그려서,
     * 날아가 내려앉을 때 자간까지 똑같다(바꿔치기 잔상이 없다).
     */
    class RiseSpan(private val lift: Float, private val color: Int?) : CharacterStyle(), UpdateAppearance {
        var progress = 0f
        override fun updateDrawState(tp: TextPaint) {
            if (color != null) tp.color = color
            tp.alpha = (tp.alpha * progress.coerceIn(0f, 1f)).toInt()
            tp.baselineShift += (lift * (1 - progress)).toInt()
        }
    }

    class Scene(val root: FrameLayout, val fly: FrameLayout, val word: TextView, val reveal: ValueAnimator, val skip: View)

    fun build(shell: PhoneShell, skip: () -> Unit): Scene {
        val ctx = shell.activity; val ui = shell.ui; val t = ui.t
        val root = FrameLayout(ctx).apply { setBackgroundColor(t.bg); isClickable = true; contentDescription = "차분 시작 화면" }
        val fly = FrameLayout(ctx).apply { visibility = View.INVISIBLE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        val lift = ui.dp(9).toFloat()
        val text = SpannableString("차분.")
        val spans = (0 until text.length).map { i -> RiseSpan(lift, if (i == text.length - 1) t.accent else null) }
        spans.forEachIndexed { i, s -> text.setSpan(s, i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val word = Fmt.wordmark(ui).apply { setText(text, TextView.BufferType.SPANNABLE) }
        fly.addView(word, FrameLayout.LayoutParams(-2, -2))
        root.addView(fly, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        // 글자는 0.08s부터 0.04s 간격으로 0.76s 동안 떠오르고, 점은 0.62s에 톡(spring-pop)
        val reveal = ValueAnimator.ofFloat(0f, 1400f).apply {
            duration = 1400; interpolator = null
            addUpdateListener { a ->
                val ms = a.animatedValue as Float
                spans.forEachIndexed { i, s ->
                    s.progress = if (i < spans.size - 1) PitMotion.easeIos.getInterpolation(((ms - 80 - 40 * i) / 760f).coerceIn(0f, 1f))
                    else PitMotion.springPop.getInterpolation(((ms - 620) / 720f).coerceIn(0f, 1f))
                }
                word.invalidate()
            }
        }
        // 배치가 끝나면 상자를 화면 가운데에 두고 키운다(가운데를 축으로 커졌다 작아진다)
        root.post {
            fly.pivotX = fly.width / 2f; fly.pivotY = fly.height / 2f
            fly.translationX = root.width / 2f - fly.width / 2f
            fly.translationY = root.height / 2f - fly.height / 2f - ui.dp(16)
            fly.scaleX = START_SCALE; fly.scaleY = START_SCALE
            fly.visibility = View.VISIBLE
            reveal.start()
        }
        val skipButton = ui.ghost("건너뛰기", t.ink2, 14f) { skip() }
        root.addView(skipButton, FrameLayout.LayoutParams(-2, ui.dp(44), Gravity.TOP or Gravity.END).apply { topMargin = shell.insetTop + ui.dp(8); rightMargin = ui.dp(14) })
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).top
            skipButton.layoutParams = (skipButton.layoutParams as FrameLayout.LayoutParams).also { it.topMargin = top + ui.dp(8) }
            insets
        }
        return Scene(root, fly, word, reveal, skipButton)
    }
}
