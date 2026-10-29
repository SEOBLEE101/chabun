package com.recovery.companion

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat

/** 줄 위 1px 선 */
class TopLineDrawable(private val color: Int, private val px: Float) : Drawable() {
    private val paint = Paint().apply { this.color = this@TopLineDrawable.color }
    override fun draw(canvas: Canvas) { canvas.drawRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.top + px, paint) }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** 줄바꿈되는 칩 묶음 */
class FlowLayout(context: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i); if (c.visibility == GONE) continue
            measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), heightMeasureSpec)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            x += c.measuredWidth + hGap; rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(maxW, y + rowH)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l; var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i); if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + hGap; rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}

/** 분절 선택(seg / cab-tabs). 선택 바탕이 spring-soft로 옮겨 간다. */
class SegmentedView(context: Context, private val ui: PitUi, private val options: List<Triple<String, String, String?>>, selected: String,
                    private val heightDp: Int = 42, private val onPick: (String) -> Unit) : FrameLayout(context) {
    private val t = ui.t
    private val thumb = View(context)
    private val labels = mutableListOf<TextView>()
    private val icons = mutableListOf<ImageView?>()
    private val cells = mutableListOf<View>()
    var selected = selected; private set
    init {
        val pad = ui.dp(4)
        setPadding(pad, pad, pad, pad)
        background = ui.shape(t.bg2, 999f)
        thumb.background = ui.shape(t.surface, 999f, if (t.dark) t.line2 else t.line, 1f)
        addView(thumb, LayoutParams(0, ui.dp(heightDp)))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEach { (id, label, icon) ->
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                isClickable = true; isFocusable = true
                setOnClickListener { pick(id, true) }
            }
            if (icon != null) {
                val iv = ImageView(context).apply { setImageDrawable(PitIcons.drawable(icon, t.ink2)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                icons += iv
                cell.addView(iv, LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { marginEnd = ui.dp(8) })
            } else icons += null
            val tv = TextView(context).apply { text = label; textSize = 15f; includeFontPadding = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            cell.addView(tv, LinearLayout.LayoutParams(-2, -2))
            labels += tv; cells += cell
            row.addView(cell, LinearLayout.LayoutParams(0, ui.dp(heightDp), 1f))
        }
        addView(row, LayoutParams(-1, ui.dp(heightDp)))
        style()
    }
    private fun style() {
        options.forEachIndexed { i, (id, label, _) ->
            val on = id == selected
            labels[i].setTextColor(if (on) t.ink else t.ink2)
            labels[i].typeface = PitType.ko(if (on) 600 else 500)
            cells[i].contentDescription = label + if (on) ", 선택됨" else ""
            icons[i]?.setImageDrawable(PitIcons.drawable(options[i].third!!, if (on) t.ink else t.ink2))
        }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight) / options.size
        (thumb.layoutParams as LayoutParams).width = w
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val w = (r - l - paddingLeft - paddingRight) / options.size
        if (!positioned && w > 0) { thumb.translationX = (index() * w).toFloat(); positioned = true }
    }
    private var positioned = false
    private fun index() = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    fun pick(id: String, user: Boolean) {
        if (id == selected) return
        selected = id; style()
        val w = thumb.width
        val target = (index() * w).toFloat()
        if (PitMotion.reduced() || w == 0) thumb.translationX = target
        else thumb.animate().translationX(target).setDuration(PitMotion.springSoft.durationMs).setInterpolator(PitMotion.springSoft).start()
        if (user) { PitHaptics.tick(this); onPick(id) }
    }
}

/** 컴포넌트 공장. 모든 색은 Tokens 역할로만. */
class PitUi(val context: Context, val t: Tokens) {
    private companion object { const val MIN_TOUCH_DP = 48f }
    fun dp(v: Number) = context.dpi(v)
    fun shape(color: Int, radiusDp: Float, stroke: Int? = null, strokeDp: Float = 1f) = GradientDrawable().apply {
        setColor(color); cornerRadius = if (radiusDp >= 999f) 9999f else context.dpf(radiusDp)
        if (stroke != null) setStroke(context.dpi(strokeDp).coerceAtLeast(1), stroke)
    }

    // ── Type ─────────────────────────────────────────────
    fun text(s: CharSequence, size: Float, weight: Int = 400, color: Int = t.ink, lineMult: Float = 1.4f, letter: Float = -0.01f): TextView = TextView(context).apply {
        text = s; textSize = size; typeface = PitType.ko(weight); setTextColor(color)
        letterSpacing = letter; includeFontPadding = false
        setLineHeight((size * lineMult * resources.displayMetrics.scaledDensity).toInt())
    }
    fun display(s: CharSequence, size: Float = 34f) = text(s, size, 700, t.ink, 1.22f, -0.035f).also { ViewCompat.setAccessibilityHeading(it, true) }
    fun title(s: CharSequence, size: Float = 30f) = text(s, size, 700, t.ink, 1.25f, -0.03f).also { ViewCompat.setAccessibilityHeading(it, true) }
    fun h2(s: CharSequence, size: Float = 19f) = text(s, size, 600, t.ink, 1.4f, -0.02f).also { ViewCompat.setAccessibilityHeading(it, true) }
    fun body(s: CharSequence, size: Float = 15f, color: Int = t.ink2) = text(s, size, 400, color, 1.6f)
    fun bodyL(s: CharSequence) = text(s, 16f, 500, t.ink, 1.5f, -0.01f)
    fun caption(s: CharSequence, color: Int = t.ink3, size: Float = 13f) = text(s, size, 400, color, 1.5f)
    fun overline(s: CharSequence, color: Int = t.ink3) = text(s.toString().uppercase(), 11f, 600, color, 1.3f, 0.18f)
    fun eyebrow(s: CharSequence) = text(s, 14f, 500, t.ink2, 1.4f)
    fun rowTitle(s: CharSequence, size: Float = 16f, weight: Int = 600, color: Int = t.ink) = text(s, size, weight, color, 1.35f, -0.02f)
    fun rowSub(s: CharSequence, color: Int = t.ink2) = text(s, 13f, 400, color, 1.45f)
    fun num(s: CharSequence, size: Float, weight: Int = 300, color: Int = t.ink) = TextView(context).apply {
        text = s; textSize = size; typeface = PitType.num(weight); setTextColor(color); includeFontPadding = false; letterSpacing = -0.02f
        fontFeatureSettings = "tnum"
    }
    fun status(): TextView = caption("", t.ink).apply {
        visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    // ── Layout ───────────────────────────────────────────
    fun vstack(gap: Float = 0f) = GapLayout(context, true, gap)
    fun hstack(gap: Float = 0f) = GapLayout(context, false, gap).apply { gravity = Gravity.CENTER_VERTICAL }
    fun wrap() = LinearLayout.LayoutParams(-2, -2)
    fun fill() = LinearLayout.LayoutParams(-1, -2)
    fun weight(w: Float = 1f) = LinearLayout.LayoutParams(0, -2, w)
    fun space(): View = View(context)
    fun ViewGroup.add(v: View, lp: ViewGroup.LayoutParams = LinearLayout.LayoutParams(-1, -2)): View { addView(v, lp); return v }

    fun card(padH: Float = 22f, padV: Float = 22f, gap: Float = 14f, radius: Float = 28f) = vstack(gap).apply {
        background = shape(t.surface, radius, t.line); setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    }
    fun flatCard(padH: Float = 18f, padV: Float = 16f, gap: Float = 14f) = vstack(gap).apply {
        background = shape(Color.TRANSPARENT, 28f, t.line); setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    }
    fun sunken(radius: Float = 18f, padH: Float = 16f, padV: Float = 14f, gap: Float = 4f) = vstack(gap).apply {
        background = shape(t.bg2, radius); setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    }

    // ── Icons ────────────────────────────────────────────
    fun icon(name: String, color: Int = t.ink, size: Float = 22f, stroke: Float = 1.5f): ImageView = ImageView(context).apply {
        setImageDrawable(PitIcons.drawable(name, color, stroke)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }
    fun glass(name: String, size: Float = 46f, iconSize: Float = 22f): FrameLayout = FrameLayout(context).apply {
        background = GlassDrawable(t)
        addView(icon(name, t.ink, iconSize), FrameLayout.LayoutParams(dp(iconSize), dp(iconSize), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    fun glassButton(name: String, desc: String, size: Float = 46f, onClick: () -> Unit) = glass(name, size, if (size < 44) 18f else 22f).apply {
        isClickable = true; isFocusable = true; contentDescription = desc; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener { PitHaptics.tick(this); onClick() }; pressable()
    }

    // ── Buttons ──────────────────────────────────────────
    private fun ripple(content: Drawable, onDark: Boolean) = RippleDrawable(ColorStateList.valueOf(if (onDark) Color.argb(40, 255, 255, 255) else Color.argb(22, 0, 0, 0)), content, null)
    private fun baseButton(label: CharSequence, heightDp: Float, onClick: () -> Unit) = TextView(context).apply {
        text = label; gravity = Gravity.CENTER; textSize = 16f; typeface = PitType.ko(600); letterSpacing = -0.02f
        includeFontPadding = false; isClickable = true; isFocusable = true; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        minHeight = dp(heightDp); minWidth = dp(MIN_TOUCH_DP); setPadding(dp(26), 0, dp(26), 0)
        setOnClickListener { if (isEnabled) onClick() }
        pressable()
    }
    fun primary(label: CharSequence, onClick: () -> Unit) = baseButton(label, 56f, onClick).apply {
        background = ripple(shape(t.primary, 999f), !t.dark); setTextColor(t.onPrimary)
        layoutParams = LinearLayout.LayoutParams(-1, dp(56))
    }
    /** Touch targets are at least 48dp; a smaller pill is drawn inside that height (InsetDrawable), so it looks the same. */
    private fun touchHeight(visibleDp: Float) = maxOf(visibleDp, MIN_TOUCH_DP)
    private fun insetToVisible(d: Drawable, visibleDp: Float): Drawable {
        val inset = ((touchHeight(visibleDp) - visibleDp) / 2f).let { dp(it) }
        return if (inset == 0) d else android.graphics.drawable.InsetDrawable(d, 0, inset, 0, inset)
    }
    fun secondary(label: CharSequence, heightDp: Float = 56f, size: Float = 16f, onClick: () -> Unit) = baseButton(label, touchHeight(heightDp), onClick).apply {
        background = insetToVisible(ripple(shape(Color.TRANSPARENT, 999f, t.line2), t.dark), heightDp); setTextColor(t.ink); textSize = size
        if (heightDp < 50) setPadding(dp(18), 0, dp(18), 0)
        layoutParams = LinearLayout.LayoutParams(-2, dp(touchHeight(heightDp)))
    }
    fun ghost(label: CharSequence, color: Int = t.ink2, size: Float = 15f, weight: Int = 600, onClick: () -> Unit) = baseButton(label, MIN_TOUCH_DP, onClick).apply {
        background = insetToVisible(ripple(shape(Color.TRANSPARENT, 999f), t.dark), 44f); setTextColor(color); textSize = size; typeface = PitType.ko(weight)
        setPadding(dp(12), 0, dp(12), 0)
        layoutParams = LinearLayout.LayoutParams(-2, dp(MIN_TOUCH_DP))
    }
    fun setEnabledLook(v: View, enabled: Boolean) { v.isEnabled = enabled; v.alpha = if (enabled) 1f else 0.38f }
    fun chip(label: CharSequence, on: Boolean, small: Boolean = false, icon: String? = null, onClick: (() -> Unit)? = null) = TextView(context).apply {
        text = label; gravity = Gravity.CENTER; textSize = if (small) 13f else 14f; typeface = PitType.ko(500); includeFontPadding = false
        val visible = if (small) 36f else 40f
        val touch = if (onClick != null) touchHeight(visible) else visible
        setPadding(dp(16), 0, dp(16), 0); minHeight = dp(touch); height = dp(touch)
        if (onClick != null) minWidth = dp(MIN_TOUCH_DP)
        val pillShape = if (on) shape(t.ink, 999f) else shape(Color.TRANSPARENT, 999f, t.line2)
        background = if (onClick != null) insetToVisible(pillShape, visible) else pillShape
        setTextColor(if (on) t.bg else t.ink)
        if (icon != null) { val d = PitIcons.drawable(icon, if (on) t.bg else t.ink).apply { setBounds(0, 0, dp(16), dp(16)) }; setCompoundDrawablesRelative(null, null, d, null); compoundDrawablePadding = dp(6) }
        contentDescription = label.toString() + if (on) ", 선택됨" else ""
        if (onClick != null) { isClickable = true; isFocusable = true; setOnClickListener { PitHaptics.tick(this); onClick() }; pressable() }
    }
    fun pill(label: CharSequence, kind: String = "ok") = TextView(context).apply {
        text = label; gravity = Gravity.CENTER; textSize = 12f; typeface = PitType.ko(600); includeFontPadding = false
        setPadding(dp(12), 0, dp(12), 0); height = dp(28); minHeight = dp(28)
        when (kind) {
            "ok" -> { background = shape(t.accentSoft, 999f); setTextColor(t.ink) }
            "neutral" -> { background = shape(t.bg2, 999f); setTextColor(t.ink) }
            "muted" -> { background = shape(t.bg2, 999f); setTextColor(t.ink2) }
            "outline" -> { background = shape(Color.TRANSPARENT, 999f, t.line2); setTextColor(t.ink3) }
            "inverse" -> { background = shape(t.ink, 999f); setTextColor(t.bg) }
            "warn" -> { background = shape(t.bg2, 999f); setTextColor(t.warn) }
        }
    }
    fun live(label: CharSequence, idle: Boolean = false, size: Float = 13f) = hstack(1f).apply {
        addView(LiveDotView(context, t, idle), LinearLayout.LayoutParams(-2, -2).apply { marginStart = -dp(7) })
        addView(text(label, size, 500, t.ink2, 1.35f), LinearLayout.LayoutParams(0, -2, 1f))
    }

    // ── Rows ─────────────────────────────────────────────
    fun row(icon: String?, title: CharSequence, sub: CharSequence? = null, end: CharSequence? = null, chevron: Boolean = true,
            topLine: Boolean = true, subView: View? = null, endView: View? = null, titleWeight: Int = 600, minHeight: Float = 68f,
            onClick: (() -> Unit)? = null): LinearLayout = hstack(14f).apply {
        setPadding(0, dp(12), 0, dp(12)); this.minimumHeight = dp(minHeight)
        if (topLine) background = TopLineDrawable(t.line, context.dpf(1).coerceAtLeast(1f))
        if (icon != null) addView(glass(icon))
        val main = vstack(3f)
        main.addView(rowTitle(title, weight = titleWeight))
        if (subView != null) main.addView(subView) else if (sub != null) main.addView(rowSub(sub))
        addView(main, LinearLayout.LayoutParams(0, -2, 1f))
        val endBox = hstack(6f)
        if (endView != null) endBox.addView(endView)
        if (end != null) endBox.addView(text(end, 13f, 400, t.ink3))
        if (chevron && onClick != null) endBox.addView(icon("chevron", t.ink3, 18f))
        if (endBox.childCount > 0) addView(endBox, wrap())
        if (onClick != null) {
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            pressable(0.985f)
        }
    }
    fun list(vararg rows: View) = vstack().apply { rows.forEachIndexed { i, r -> if (i == 0) r.background = null; addView(r, fill()) } }

    // ── Headers ──────────────────────────────────────────
    fun backHeader(label: String?, title: String? = null, onBack: () -> Unit, end: View? = null) = hstack(4f).apply {
        val back = TextView(context).apply {
            val d = PitIcons.drawable("back", t.ink).apply { setBounds(0, 0, dp(22), dp(22)) }
            setCompoundDrawablesRelative(d, null, null, null); compoundDrawablePadding = dp(2)
            text = label ?: ""; textSize = 15f; typeface = PitType.ko(600); setTextColor(t.ink); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(10), 0); minHeight = dp(MIN_TOUCH_DP); height = dp(MIN_TOUCH_DP); minWidth = dp(MIN_TOUCH_DP)
            contentDescription = (label ?: title ?: "") + "로 돌아가기"
            isClickable = true; isFocusable = true; setOnClickListener { onBack() }; pressable()
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(22, 0, 0, 0)), null, shape(Color.WHITE, 999f))
        }
        addView(back, wrap())
        if (title != null) addView(bodyL(title), LinearLayout.LayoutParams(0, -2, 1f)) else addView(space(), LinearLayout.LayoutParams(0, 1, 1f))
        if (end != null) addView(end, wrap())
        (layoutParams as? ViewGroup.MarginLayoutParams)
    }
    fun info(textValue: CharSequence) = hstack(10f).apply {
        gravity = Gravity.TOP
        addView(icon("info", t.ink3, 18f).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(1) })
        addView(caption(textValue), LinearLayout.LayoutParams(0, -2, 1f))
        setPadding(dp(4), 0, dp(4), 0)
    }
    fun sectionHead(titleText: String, endText: String? = null, endClick: (() -> Unit)? = null, size: Float = 19f) = hstack(8f).apply {
        gravity = Gravity.BOTTOM
        addView(h2(titleText, size), LinearLayout.LayoutParams(0, -2, 1f))
        if (endText != null) addView(caption(endText).apply {
            if (endClick != null) {
                isClickable = true; isFocusable = true; setOnClickListener { endClick() }
                // 48dp touch height; the words stay on the heading's baseline.
                minHeight = dp(MIN_TOUCH_DP); gravity = Gravity.BOTTOM or Gravity.END; setPadding(dp(10), 0, 0, dp(2))
            }
        })
    }
    fun numbered(n: String, titleText: String) = hstack(10f).apply {
        gravity = Gravity.BOTTOM
        addView(num(n, 13f, 600, t.ink3))
        addView(h2(titleText, 17f))
    }
    fun step(state: Int, titleText: String, sub: CharSequence? = null, liveSub: Boolean = false, muted: Boolean = false) = hstack(12f).apply {
        gravity = Gravity.TOP
        addView(StepDotView(context, t, state, t.surface))
        val col = vstack(if (liveSub) 4f else 2f)
        col.addView(rowTitle(titleText, 15f, 600, if (muted) t.ink2 else t.ink))
        if (sub != null) col.addView(if (liveSub) live(sub, idle = true) else rowSub(sub))
        addView(col, LinearLayout.LayoutParams(0, -2, 1f))
    }
    fun checkRow(label: CharSequence, checked: Boolean, onToggle: (Boolean) -> Unit): LinearLayout {
        lateinit var mark: CheckMarkView
        return hstack(14f).apply {
            setPadding(0, dp(14), 0, dp(14)); minimumHeight = dp(60)
            background = TopLineDrawable(t.line, context.dpf(1).coerceAtLeast(1f))
            mark = CheckMarkView(context, t, checked)
            addView(mark)
            addView(rowTitle(label, 15f), LinearLayout.LayoutParams(0, -2, 1f))
            isClickable = true; isFocusable = true
            contentDescription = label
            ViewCompat.setStateDescription(this, if (checked) "선택됨" else "선택 안 됨")
            setOnClickListener {
                val next = !mark.on; mark.set(next, true); PitHaptics.tick(this)
                ViewCompat.setStateDescription(this, if (next) "선택됨" else "선택 안 됨")
                onToggle(next)
            }
        }
    }
    fun radioRow(titleText: String, sub: CharSequence?, on: Boolean, minH: Float = 64f, subView: View? = null, onPick: () -> Unit) = hstack(14f).apply {
        setPadding(0, dp(12), 0, dp(12)); minimumHeight = dp(minH)
        background = TopLineDrawable(t.line, context.dpf(1).coerceAtLeast(1f))
        addView(RadioView(context, t, on, t.bg))
        val col = vstack(3f); col.addView(rowTitle(titleText, 15f))
        if (subView != null) col.addView(subView) else if (sub != null) col.addView(rowSub(sub))
        addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        isClickable = true; isFocusable = true; contentDescription = titleText
        ViewCompat.setStateDescription(this, if (on) "선택됨" else "선택 안 됨")
        setOnClickListener { PitHaptics.tick(this); onPick() }
        pressable(0.985f)
    }
    fun toggleRow(titleText: String, sub: String?, on: Boolean, icon: String? = null, onChange: (Boolean) -> Unit): LinearLayout {
        val toggle = ToggleView(context, t, on, titleText, onChange)
        return row(icon, titleText, sub, chevron = false, endView = toggle, titleWeight = if (icon == null) 500 else 600).apply {
            isClickable = true; setOnClickListener { toggle.performClick() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }
    fun pills(vararg labels: String) = FlowLayout(context, dp(6), dp(6)).apply { labels.forEach { addView(pill(it, "neutral")) } }
    fun flow(hGap: Float = 8f, vGap: Float = 8f) = FlowLayout(context, dp(hGap), dp(vGap))

    /** 차 안 설정 선택지(opt): 3~4칸 */
    fun opt(options: List<Triple<String, String, String?>>, selected: String, tall: Boolean = false, onPick: (String) -> Unit) = hstack(6f).apply {
        options.forEach { (id, label, iconName) ->
            val on = id == selected
            val cell = vstack(4f).apply {
                gravity = Gravity.CENTER
                background = if (on) shape(t.ink, 16f) else shape(Color.TRANSPARENT, 16f, t.line2)
                if (iconName != null) addView(icon(iconName, if (on) t.bg else t.ink, 20f), LinearLayout.LayoutParams(dp(20), dp(20)))
                addView(text(label, 14f, if (on) 600 else 500, if (on) t.bg else t.ink, 1.2f).apply { gravity = Gravity.CENTER })
                isClickable = true; isFocusable = true; contentDescription = label + if (on) ", 선택됨" else ""
                setOnClickListener { PitHaptics.tick(this); onPick(id) }
                pressable(0.97f)
            }
            addView(cell, LinearLayout.LayoutParams(0, dp(if (tall) 68 else 48), 1f))
        }
    }
    fun lbl(bold: String, note: String? = null) = hstack(8f).apply {
        gravity = Gravity.BOTTOM
        addView(text(bold, 15f, 600, t.ink, 1.3f, -0.02f), LinearLayout.LayoutParams(0, -2, 1f))
        if (note != null) addView(text(note, 12f, 400, t.ink3, 1.3f))
    }
    fun blend(a: Int, b: Int, f: Float) = ColorUtils.blendARGB(a, b, f)
    fun lpMargins(lp: LinearLayout.LayoutParams, top: Float = 0f, bottom: Float = 0f, start: Float = 0f, end: Float = 0f) = lp.apply {
        topMargin = dp(top); bottomMargin = dp(bottom); marginStart = dp(start); marginEnd = dp(end)
    }
    fun px(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)
}
