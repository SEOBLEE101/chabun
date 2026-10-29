package com.recovery.companion

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 아래에서 올라오는 시트. 별도 Activity(취향 함께 쓰기)에서 시스템 대화상자 대신 쓴다.
 * 앱 안 시트(PhoneShell.showSheet)와 같은 모양·움직임이고, 키보드가 올라오면 시트도 함께 올라간다.
 * 버튼을 누르면 [onPositive]가 불리며, 닫을지는 부른 쪽이 정한다(초안 유지·충돌 검토를 위해).
 */
internal class PitSheet(
    private val activity: Activity,
    title: String?,
    message: CharSequence? = null,
    body: View? = null
) {
    private val t = Tokens.current(activity)
    private val ui = PitUi(activity, t)
    private val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
    private val layer = FrameLayout(activity)
    private val scrim = View(activity).apply { setBackgroundColor(Color.argb(82, 10, 10, 10)); alpha = 0f }
    private val sheet = GapLayout(activity, true, 0f).apply {
        background = ui.shape(t.surface, 34f)
        setPadding(ui.dp(22), ui.dp(10), ui.dp(22), ui.dp(22))
        isClickable = true
    }
    private val buttonRow = ui.hstack(10f)
    lateinit var positive: TextView; private set
    var negative: TextView? = null; private set
    private var closing = false

    init {
        sheet.addView(View(activity).apply { background = ui.shape(t.line2, 3f) },
            LinearLayout.LayoutParams(ui.dp(38), ui.dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ui.dp(18) })
        val inner = ui.vstack(14f)
        title?.let { inner.addView(ui.title(it, 22f).apply { ViewCompat.setAccessibilityHeading(this, true) }) }
        message?.let { inner.addView(ui.body(it, 15f)) }
        body?.let { (it.parent as? ViewGroup)?.removeView(it); inner.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        val maxBody = (activity.resources.displayMetrics.heightPixels * 0.62f).toInt()
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxBody, View.MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = false; addView(inner) }
        sheet.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        sheet.addView(buttonRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        layer.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        layer.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(ui.dp(8), 0, ui.dp(8), ui.dp(8)) })
        scrim.setOnClickListener { dismiss() }
        dialog.setContentView(layer)
        dialog.setOnCancelListener { dismiss() }
        dialog.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            @Suppress("DEPRECATION") w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            w.setWindowAnimations(0)
            // The scrim draws behind the system bars too, so they stay see-through like the page below.
            // The translucent base theme is pre-Material: it must be told to draw (transparent) bar backgrounds.
            @Suppress("DEPRECATION") run {
                w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                w.statusBarColor = Color.TRANSPARENT; w.navigationBarColor = Color.TRANSPARENT
            }
            if (android.os.Build.VERSION.SDK_INT >= 30) w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) { w.isStatusBarContrastEnforced = false; w.isNavigationBarContrastEnforced = false }
            WindowCompat.getInsetsController(w, layer).apply { isAppearanceLightStatusBars = !t.dark; isAppearanceLightNavigationBars = !t.dark }
        }
        ViewCompat.setOnApplyWindowInsetsListener(layer) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            (sheet.layoutParams as FrameLayout.LayoutParams).bottomMargin = ui.dp(8) + maxOf(bars.bottom, ime.bottom)
            sheet.requestLayout()
            insets
        }
    }

    /** Buttons: a quiet one that closes by default, and the main one. [danger] colours the main one as a deletion. */
    fun buttons(negativeLabel: String?, positiveLabel: String, danger: Boolean = false,
                onNegative: (() -> Unit)? = null, onPositive: () -> Unit): PitSheet {
        buttonRow.removeAllViews()
        negativeLabel?.let { label ->
            negative = ui.secondary(label) { onNegative?.invoke() ?: dismiss() }.also {
                buttonRow.addView(it, LinearLayout.LayoutParams(0, ui.dp(56), 1f))
            }
        }
        positive = ui.primary(positiveLabel) { onPositive() }.also {
            if (danger) { it.background = ui.shape(t.warn, 999f); it.setTextColor(if (t.dark) t.bg else Color.WHITE) }
            buttonRow.addView(it, LinearLayout.LayoutParams(0, ui.dp(56), 1f))
        }
        return this
    }

    fun show(): PitSheet {
        dialog.show()
        if (PitMotion.reduced()) { scrim.alpha = 1f; return this }
        sheet.visibility = View.INVISIBLE
        sheet.post {
            sheet.translationY = sheet.height + ui.dp(16).toFloat(); sheet.visibility = View.VISIBLE
            sheet.animate().translationY(0f).setDuration(PitMotion.springSoft.durationMs + 120).setInterpolator(PitMotion.springSoft).start()
            scrim.animate().alpha(1f).setDuration(420).setInterpolator(PitMotion.easeIos).start()
        }
        return this
    }

    fun dismiss() {
        if (closing) return
        closing = true
        sheet.findFocus()?.let { f -> WindowCompat.getInsetsController(dialog.window ?: return@let, f).hide(WindowInsetsCompat.Type.ime()) }
        val done = { runCatching { dialog.dismiss() }; Unit }
        if (PitMotion.reduced() || !dialog.isShowing) { done(); return }
        scrim.animate().alpha(0f).setDuration(300).start()
        sheet.animate().translationY(sheet.height + ui.dp(24).toFloat()).setDuration(320).setInterpolator(PitMotion.easeIos).withEndAction { done() }.start()
    }
}
