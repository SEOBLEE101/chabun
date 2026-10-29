package com.recovery.companion

import android.app.Activity
import android.app.AlertDialog
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
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 예전 화면 코드(취향 함께 쓰기 등)가 쓰던 함수 이름을 그대로 두고, 모양만 새 토큰(Pearl·Graphite·Umber)으로 바꾼다.
 * 새 화면은 PitUi를 쓴다.
 */
class PhoneTheme(private val context: Context, val t: Tokens = Tokens.current(context)) {
    val ui = PitUi(context, t)
    val paper get() = t.bg
    val card get() = t.surface
    val ink get() = t.ink
    val muted get() = t.ink2
    val olive get() = t.ink3
    val graphite get() = t.primary
    val line get() = t.line
    val selectedSurface get() = t.bg2
    val onPrimary get() = t.onPrimary
    val onHero get() = t.onPrimary
    val danger get() = t.warn

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun surface(color: Int, radius: Int = 28) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    fun column(parent: LinearLayout? = null, color: Int? = null): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        color?.let {
            background = GradientDrawable().apply { setColor(it); cornerRadius = dp(28).toFloat(); setStroke(dp(1).coerceAtLeast(1), t.line) }
            setPadding(dp(22), dp(20), dp(22), dp(20))
        }
        parent?.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }
    fun text(parent: LinearLayout, value: String, size: Float = 15f, bold: Boolean = false, color: Int = ink): TextView = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); typeface = PitType.ko(if (bold) 600 else 400)
        letterSpacing = if (size >= 24f) -0.03f else -0.01f
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, dp(4), 0, dp(6))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    fun heading(parent: LinearLayout, value: String, size: Float = 19f): TextView = text(parent, value, size, true).also {
        it.typeface = PitType.ko(if (size >= 24f) 700 else 600)
        ViewCompat.setAccessibilityHeading(it, true)
    }
    fun styleButton(button: Button, primary: Boolean = false, selected: Boolean = false, quiet: Boolean = false, destructive: Boolean = false) {
        fun shape(fill: Int, stroke: Int?) = GradientDrawable().apply { setColor(fill); cornerRadius = 9999f; if (stroke != null) setStroke(dp(1).coerceAtLeast(1), stroke) }
        val fill = when { primary -> t.primary; selected -> t.ink; else -> Color.TRANSPARENT }
        val stroke = if (primary || quiet || selected) null else t.line2
        val fg = when { primary -> t.onPrimary; selected -> t.bg; destructive -> t.warn; quiet -> t.ink2; else -> t.ink }
        button.apply {
            stateListAnimator = null; elevation = 0f; translationZ = 0f; backgroundTintList = null
            background = RippleDrawable(ColorStateList.valueOf(if (t.dark || primary) Color.argb(40, 255, 255, 255) else Color.argb(22, 0, 0, 0)),
                StateListDrawable().apply { addState(intArrayOf(), shape(fill, stroke)) }, shape(Color.WHITE, null))
            setTextColor(fg)
            isSelected = selected
            typeface = PitType.ko(600)
            alpha = if (isEnabled) 1f else 0.38f
            ViewCompat.setStateDescription(this, if (selected) "선택됨" else null)
        }
    }
    fun button(parent: LinearLayout, title: String, primary: Boolean = false, onClick: () -> Unit): Button = object : Button(context) {
        override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else 0.38f }
    }.apply {
        text = title; textSize = 15f; isAllCaps = false; letterSpacing = -0.02f
        minHeight = dp(52); minimumHeight = dp(52); minWidth = 0
        setPadding(dp(20), dp(12), dp(20), dp(12))
        styleButton(this, primary)
        setOnClickListener { onClick() }
        pressable()
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    fun quietButton(parent: LinearLayout, title: String, destructive: Boolean = false, onClick: () -> Unit) = button(parent, title, onClick = onClick).also {
        styleButton(it, quiet = true, destructive = destructive)
        it.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        it.setPadding(dp(4), dp(12), dp(4), dp(12))
    }
    /** 선택지: 기호(●○) 대신 그린 라디오 */
    fun choice(parent: LinearLayout, title: String, selected: Boolean, onClick: () -> Unit) = button(parent, title, onClick = onClick).also {
        it.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        it.contentDescription = title
        styleButton(it)
        val radio = RadioDrawable(t, selected).apply { setBounds(0, 0, dp(22), dp(22)) }
        it.setCompoundDrawablesRelative(radio, null, null, null); it.compoundDrawablePadding = dp(12)
        it.background = RippleDrawable(ColorStateList.valueOf(Color.argb(22, 0, 0, 0)), GradientDrawable().apply {
            setColor(if (selected) t.bg2 else Color.TRANSPARENT); cornerRadius = dp(18).toFloat(); if (!selected) setStroke(dp(1).coerceAtLeast(1), t.line)
        }, null)
        it.typeface = PitType.ko(if (selected) 600 else 500)
        ViewCompat.setStateDescription(it, if (selected) "선택됨" else "선택 안 됨")
    }
    fun disclosure(parent: LinearLayout, title: String, expanded: Boolean = false, changed: (Boolean) -> Unit = {}): LinearLayout {
        val group = column(parent)
        group.tag = "pitin-disclosure:$title"
        val toggle = quietButton(group, title) { }
        val body = column(group)
        fun update(open: Boolean) {
            val d = PitIcons.drawable(if (open) "down" else "chevron", t.ink3).apply { setBounds(0, 0, dp(18), dp(18)) }
            toggle.setCompoundDrawablesRelative(null, null, d, null)
            toggle.text = title; toggle.setTextColor(t.ink); toggle.contentDescription = title
            ViewCompat.setStateDescription(toggle, if (open) "펼쳐짐" else "접힘")
            body.visibility = if (open) View.VISIBLE else View.GONE
        }
        update(expanded)
        toggle.setOnClickListener { val open = body.visibility != View.VISIBLE; update(open); changed(open) }
        return body
    }
    fun check(parent: LinearLayout, title: String, checked: Boolean = false): CheckBox = CheckBox(context).apply {
        text = title; textSize = 15f; setTextColor(ink); typeface = PitType.ko(500)
        minHeight = dp(56); setPadding(dp(8), dp(8), dp(4), dp(8))
        buttonTintList = ColorStateList.valueOf(t.ink)
        isChecked = checked
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    fun input(parent: LinearLayout, label: String, value: String = "", numeric: Boolean = false): EditText {
        val caption = text(parent, label, 13f, false, t.ink3)
        return EditText(context).apply {
            id = View.generateViewId(); caption.labelFor = id
            setText(value); setTextColor(ink); setHintTextColor(t.ink3); textSize = 16f
            minHeight = dp(56); setPadding(dp(18), dp(10), dp(18), dp(10))
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply { setColor(t.surface); cornerRadius = dp(20).toFloat(); setStroke(dp(1).coerceAtLeast(1), t.line2) }
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }
    fun spinner(parent: LinearLayout, label: String, values: List<String>, selected: String, title: (String) -> String): Spinner {
        val caption = text(parent, label, 13f, false, t.ink3)
        return Spinner(context).apply {
            id = View.generateViewId(); caption.labelFor = id; tag = values
            contentDescription = label; minimumHeight = dp(52)
            background = GradientDrawable().apply { setColor(t.surface); cornerRadius = dp(20).toFloat(); setStroke(dp(1).coerceAtLeast(1), t.line2) }
            setPopupBackgroundDrawable(GradientDrawable().apply { setColor(t.surface); cornerRadius = dp(18).toFloat() })
            adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, values.map(title)) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = super.getView(position, convertView, parent).also {
                    (it as? TextView)?.apply { setTextColor(ink); textSize = 16f; minHeight = dp(52); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), 0, dp(18), 0) }
                }
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = super.getDropDownView(position, convertView, parent).also {
                    (it as? TextView)?.apply { setTextColor(ink); setBackgroundColor(t.surface); textSize = 16f; minHeight = dp(52) }
                }
            }
            setSelection(values.indexOf(selected).coerceAtLeast(0))
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }
    fun applySystemBars(activity: Activity, view: View) {
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = !t.dark; isAppearanceLightNavigationBars = !t.dark
        }
    }
    fun dialogTheme(): Int = if (t.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    fun styleDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply { setColor(t.surface); cornerRadius = dp(28).toFloat() })
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(ink)
        val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
        if (titleId != 0) dialog.findViewById<TextView>(titleId)?.apply { setTextColor(ink); typeface = PitType.ko(600) }
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE).forEach { which ->
            dialog.getButton(which)?.apply { minHeight = dp(48); setTextColor(if (which == AlertDialog.BUTTON_POSITIVE) t.ink else t.ink2); isAllCaps = false; typeface = PitType.ko(600) }
        }
    }
    fun space(parent: LinearLayout, height: Int) { parent.addView(View(context), LinearLayout.LayoutParams(1, dp(height))) }

    companion object {
        /** 연결 탭에서 들어오는 별도 Activity(취향 함께 쓰기)의 바탕: 가장자리까지 그리고, 시스템 막대만큼 비운다. */
        fun installDetail(activity: Activity, content: View) {
            val theme = PhoneTheme(activity)
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            val shell = FrameLayout(activity).apply { setBackgroundColor(theme.t.bg) }
            shell.addView(content, FrameLayout.LayoutParams(-1, -1))
            ViewCompat.setOnApplyWindowInsetsListener(shell) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                insets
            }
            activity.window.decorView.setBackgroundColor(theme.t.bg)
            activity.setContentView(shell)
            theme.applySystemBars(activity, shell)
        }
    }
}

class RadioDrawable(private val t: Tokens, private val on: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val c = bounds.exactCenterX(); val cy = bounds.exactCenterY(); val r = bounds.width() / 2f
        val d = bounds.width() / 22f
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * d; paint.color = if (on) t.ink else t.line2
        canvas.drawCircle(c, cy, r - 0.75f * d, paint)
        if (on) { paint.style = Paint.Style.FILL; paint.color = t.ink; canvas.drawCircle(c, cy, 5.5f * d, paint) }
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
