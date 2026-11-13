package com.recovery.companion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.time.Instant
import java.time.LocalDate

enum class CompanionAction { SYNC_PRESETS, CLAIM, CHECK_PAIRING, READ_ALL, READ_SAMSUNG, HEALTH_SETTINGS, DEMO, SEND, CLEAR, REVOKE }
enum class HealthState { CHECKING, GRANTED, NEEDS_PERMISSION, UPDATE_REQUIRED, UNAVAILABLE, ERROR }
data class SentSummary(val workoutType: String, val minutes: Int, val presetName: String, val sentAt: Instant, val expiresAt: Instant, val demo: Boolean)

/** 화면이 읽는 앱 상태와 행동. 계약 로직은 MainActivity에 그대로 있다. */
interface PitApp {
    val debug: Boolean
    /** This build has no connection server (submission builds): pairing and sending cannot work, so screens say so up front. */
    val serverless: Boolean
    val busy: Boolean
    val pairing: PairingSession?
    val pairingConfirmed: Boolean
    val confirmationCode: String?
    val lastPairingCheckAt: Long
    val workout: WorkoutRecord?
    var consent: Boolean
    var pairCode: String
    val healthState: HealthState
    val lastReadEmpty: Boolean
    val lastReadAt: Long
    val lastSent: SentSummary?
    val deletionConfirmedAt: Long
    val appearanceSync: AppearanceSync
    fun act(action: CompanionAction)
    fun relayBase(): String
    fun setRelayBase(value: String)
    fun openSyncedPresets()
    /** 차에 이 쉼 보내기: the rest with its 차 안 설정, through 취향 함께 쓰기, no workout data. The car starts it. */
    fun sendRestToCar(presetId: String)
    fun appearanceChanged()
    fun selectPreset(id: String)
    /** 워치 심박 쉼 제안(선택): 앱을 열 때만 읽어 정한다. true면 홈에 제안 카드가 보인다. */
    val heartNudgeShown: Boolean
    fun setHeartNudge(on: Boolean)
    fun dismissHeartNudge()
}

/** 이 프로세스 안에서만 사는 귀가 준비 체험(합성 기기). */
object LocalArrival {
    var session = PresetSession(); private set
    fun reset() { session = PresetSession() }
}

enum class Tab(val label: String, val icon: String) { TODAY("오늘", "today"), COMFORT("취향", "sliders"), CONNECT("연결", "link"), ME("나", "person") }

/** 한 화면. 상태는 Screen 객체에 두고, 뷰는 테마가 바뀌면 다시 만든다. */
abstract class Screen(val shell: PhoneShell) {
    lateinit var ui: PitUi
    val t get() = ui.t
    val app get() = shell.app
    val ctx get() = shell.activity
    open val isTab = false
    open val gap = 28f
    var root: FrameLayout? = null
    protected lateinit var scroll: ScrollView
    lateinit var content: GapLayout
    var statusLine: TextView? = null
    var message: String? = null
    var dirty = false
    abstract fun fill(c: GapLayout)
    open fun decorate(root: FrameLayout) {}
    fun create(ui: PitUi): View {
        this.ui = ui
        val r = FrameLayout(ctx).apply { setBackgroundColor(ui.t.bg); isClickable = true }
        decorate(r)
        content = GapLayout(ctx, true, gap)
        scroll = ScrollView(ctx).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS; addView(content, FrameLayout.LayoutParams(-1, -2)) }
        r.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        // Status-bar veil: invisible at the top so the art reaches the edge, fades in as content scrolls under it.
        topScrim = View(ctx).apply { alpha = 0f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        r.addView(topScrim, FrameLayout.LayoutParams(-1, shell.insetTop + ui.dp(SCRIM_FADE)))
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> topScrim?.alpha = (y / ctx.dpf(24)).coerceIn(0f, 1f) }
        applyInsets()
        fill(content)
        reapplyMessage()
        root = r; dirty = false
        return r
    }
    private var topScrim: View? = null
    private companion object { const val SCRIM_FADE = 14f }
    fun applyInsets() {
        if (!::content.isInitialized) return
        topScrim?.let { s ->
            val fade = ui.dp(SCRIM_FADE)
            s.layoutParams = (s.layoutParams as FrameLayout.LayoutParams).also { it.height = shell.insetTop + fade }
            val veil = ColorUtils.setAlphaComponent(t.bg, 245)
            s.background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.ColorDrawable(veil),
                android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(veil, ColorUtils.setAlphaComponent(t.bg, 0))))).apply {
                setLayerInset(0, 0, 0, 0, fade)
                setLayerInset(1, 0, shell.insetTop, 0, 0)
            }
        }
        val side = ui.dp(22)
        val bottom = if (isTab) shell.tabBarHeight() + ui.dp(28) else maxOf(shell.insetBottom, shell.imeBottom) + ui.dp(32)
        content.setPadding(side, shell.insetTop + ui.dp(18), side, bottom)
    }
    fun rebuild() {
        if (root == null) return
        val y = scroll.scrollY
        content.removeAllViews(); statusLine = null
        fill(content); dirty = false
        reapplyMessage()
        scroll.post { scroll.scrollTo(0, y) }
    }
    open fun onStateChanged() = rebuild()
    open fun onShown() {}
    open fun onHidden() {}
    open fun onBack(): Boolean = false
    fun report(text: String) { message = text; reapplyMessage() }
    fun clearMessage() { message = null; statusLine?.visibility = View.GONE }
    private fun reapplyMessage() { val m = message ?: return; statusLine?.let { it.text = m; it.visibility = View.VISIBLE } }
    fun spacer(c: LinearLayout) { c.addView(View(ctx), LinearLayout.LayoutParams(-1, 0, 1f)) }
    fun scrollToTop() { if (::scroll.isInitialized) scroll.smoothScrollTo(0, 0) }
}

class PhoneShell(val activity: MainActivity, val app: PitApp) {
    val host = FrameLayout(activity)
    var t: Tokens = Tokens.current(activity); private set
    var ui = PitUi(activity, t); private set
    private val stage = FrameLayout(activity)
    private val tabRoot = FrameLayout(activity)
    private val tabContent = FrameLayout(activity)
    private var tabBar: LinearLayout? = null
    private val tabScreens = LinkedHashMap<Tab, Screen>()
    var tab = Tab.TODAY; private set
    private val stack = mutableListOf<Screen>()
    private var sheetLayer: FrameLayout? = null
    private var sheetDismiss: (() -> Unit)? = null
    private var waveRunning = false
    private var pendingTheme = false
    private var intro: View? = null
    var insetTop = 0; private set
    var insetBottom = 0; private set
    var imeBottom = 0; private set
    var factory: (Tab) -> Screen = { error("no screens") }

    fun tabBarHeight() = ui.dp(64) + insetBottom

    fun install() {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        host.addView(stage, FrameLayout.LayoutParams(-1, -1))
        stage.addView(tabRoot, FrameLayout.LayoutParams(-1, -1))
        tabRoot.addView(tabContent, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(host) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val changed = bars.top != insetTop || bars.bottom != insetBottom || ime.bottom != imeBottom
            insetTop = bars.top; insetBottom = bars.bottom; imeBottom = ime.bottom
            if (changed) { allScreens().forEach { it.applyInsets() }; layoutTabBar() }
            if (ime.bottom == 0 && pendingTheme) { pendingTheme = false; host.post { applyTheme(true) } }
            insets
        }
        activity.setContentView(host)
        paintChrome()
        buildTabBar()
        showTab(Tab.TODAY, animate = false)
    }
    private fun allScreens() = tabScreens.values + stack

    private fun paintChrome() {
        host.setBackgroundColor(t.bg); stage.setBackgroundColor(t.bg)
        activity.window.decorView.setBackgroundColor(t.bg)
        @Suppress("DEPRECATION") run { activity.window.statusBarColor = Color.TRANSPARENT; activity.window.navigationBarColor = Color.TRANSPARENT }
        WindowCompat.getInsetsController(activity.window, host).apply {
            isAppearanceLightStatusBars = !t.dark; isAppearanceLightNavigationBars = !t.dark
        }
    }

    // ── Tab bar ─────────────────────────────────────────
    private val tabItems = mutableMapOf<Tab, Pair<TextView, View>>()
    private fun buildTabBar() {
        tabBar?.let { tabRoot.removeView(it) }
        tabItems.clear()
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.ColorDrawable(t.bg),
                TopLineDrawable(t.line, activity.dpf(1).coerceAtLeast(1f))))
            setPadding(ui.dp(18), ui.dp(8), ui.dp(18), 0)
            isClickable = true
            contentDescription = "주 메뉴"
        }
        Tab.entries.forEach { item ->
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true; isFocusable = true; contentDescription = item.label
                setOnClickListener { PitHaptics.tick(this); if (stack.isEmpty()) showTab(item, true) }
            }
            val icon = ui.icon(item.icon, t.ink3, 22f)
            val label = ui.text(item.label, 11f, 500, t.ink3, 1.3f).apply { gravity = Gravity.CENTER }
            val dot = View(activity).apply { background = ui.shape(t.ink, 999f) }
            cell.addView(icon, LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)))
            cell.addView(label, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(4) })
            cell.addView(dot, LinearLayout.LayoutParams(ui.dp(4), ui.dp(4)).apply { topMargin = ui.dp(5) })
            tabItems[item] = label to dot
            cell.tag = icon
            bar.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        tabBar = bar
        tabRoot.addView(bar, FrameLayout.LayoutParams(-1, tabBarHeight(), Gravity.BOTTOM))
        styleTabs()
    }
    private fun layoutTabBar() { tabBar?.let { it.layoutParams = (it.layoutParams as FrameLayout.LayoutParams).also { lp -> lp.height = tabBarHeight() } } }
    private fun styleTabs() {
        val bar = tabBar ?: return
        Tab.entries.forEachIndexed { i, item ->
            val on = item == tab
            val (label, dot) = tabItems.getValue(item)
            label.setTextColor(if (on) t.ink else t.ink3); label.typeface = PitType.ko(if (on) 600 else 500)
            dot.visibility = if (on) View.VISIBLE else View.INVISIBLE
            val cell = bar.getChildAt(i)
            ((cell.tag as View) as android.widget.ImageView).setImageDrawable(PitIcons.drawable(item.icon, if (on) t.ink else t.ink3))
            ViewCompat.setStateDescription(cell, if (on) "선택됨" else null)
            cell.isSelected = on
        }
    }

    fun screenFor(tab: Tab): Screen = tabScreens.getOrPut(tab) { factory(tab) }

    fun showTab(next: Tab, animate: Boolean) {
        val prev = tab
        val nextScreen = screenFor(next)
        val firstTime = nextScreen.root == null
        if (firstTime) tabContent.addView(nextScreen.create(ui), FrameLayout.LayoutParams(-1, -1))
        else if (nextScreen.dirty) nextScreen.rebuild()
        if (firstTime && animate) cascade(nextScreen.content, 60)
        tab = next; styleTabs()
        val nextView = nextScreen.root!!
        if (prev == next && nextView.visibility == View.VISIBLE) { nextScreen.scrollToTop(); return }
        tabScreens[prev]?.takeIf { it != nextScreen }?.let { old ->
            old.onHidden()
            val ov = old.root ?: return@let
            if (!animate || PitMotion.reduced()) ov.visibility = View.GONE
            else ov.animate().alpha(0f).setDuration(160).withEndAction { ov.visibility = View.GONE; ov.alpha = 1f }.start()
        }
        nextView.visibility = View.VISIBLE
        nextView.bringToFront()
        if (animate && !PitMotion.reduced()) {
            nextView.alpha = 0f; nextView.translationY = ui.dp(12).toFloat()
            nextView.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(PitMotion.easeIos).start()
        }
        tabBar?.bringToFront()
        nextScreen.onShown()
    }

    fun top(): Screen = stack.lastOrNull() ?: screenFor(tab)

    // ── Push / back ─────────────────────────────────────
    fun push(screen: Screen) {
        if (waveRunning) return
        val below = stack.lastOrNull()?.root ?: tabRoot
        val view = screen.create(ui)
        stack += screen
        stage.addView(view, FrameLayout.LayoutParams(-1, -1))
        val w = host.width.toFloat().takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels.toFloat()
        if (PitMotion.reduced()) {
            view.alpha = 0f; view.animate().alpha(1f).setDuration(200).withEndAction { below.visibility = View.INVISIBLE }.start()
        } else {
            view.translationX = w; view.elevation = ui.dp(6).toFloat()
            view.animate().translationX(0f).setDuration(560).setInterpolator(PitMotion.easeIos).withEndAction { view.elevation = 0f }.start()
            below.animate().translationX(-0.28f * w).alpha(0.55f).setDuration(560).setInterpolator(PitMotion.easeIos)
                .withEndAction { below.visibility = View.INVISIBLE; below.translationX = 0f; below.alpha = 1f }.start()
        }
        screen.onShown()
    }
    fun pop() {
        val top = stack.removeLastOrNull() ?: return
        top.onHidden()
        val view = top.root ?: return
        val belowScreen = stack.lastOrNull() ?: screenFor(tab)
        val below = stack.lastOrNull()?.root ?: tabRoot
        if (belowScreen.dirty) belowScreen.rebuild()
        below.visibility = View.VISIBLE
        val w = host.width.toFloat()
        hideKeyboard()
        if (PitMotion.reduced()) {
            view.animate().alpha(0f).setDuration(200).withEndAction { stage.removeView(view) }.start()
        } else {
            below.translationX = -0.28f * w; below.alpha = 0.55f
            below.animate().translationX(0f).alpha(1f).setDuration(560).setInterpolator(PitMotion.easeIos).start()
            view.elevation = ui.dp(6).toFloat()
            view.animate().translationX(w).setDuration(560).setInterpolator(PitMotion.easeIos).withEndAction { stage.removeView(view) }.start()
        }
        belowScreen.onShown()
    }
    /** 맨 위 화면을 새 화면으로 바꾼다(보낸 뒤 상태 등). */
    fun replaceTop(screen: Screen) {
        val old = stack.removeLastOrNull()
        old?.root?.let { v -> v.animate().alpha(0f).setDuration(220).withEndAction { stage.removeView(v) }.start() }
        val view = screen.create(ui)
        stack += screen
        stage.addView(view, FrameLayout.LayoutParams(-1, -1))
        if (!PitMotion.reduced()) { view.alpha = 0f; view.translationY = ui.dp(12).toFloat(); view.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(PitMotion.easeIos).start() }
        screen.onShown()
    }
    fun popToRoot(then: Tab? = null) {
        while (stack.size > 1) { val s = stack.removeLast(); s.root?.let { stage.removeView(it) } }
        if (stack.isNotEmpty()) pop()
        if (then != null) host.postDelayed({ showTab(then, true) }, if (PitMotion.reduced()) 0 else 300)
    }
    fun back(): Boolean {
        if (intro != null) { skipIntro(); return true }
        if (sheetLayer != null) { sheetDismiss?.invoke(); return true }
        if (top().onBack()) return true
        if (stack.isNotEmpty()) { pop(); return true }
        if (tab != Tab.TODAY) { showTab(Tab.TODAY, true); return true }
        return false
    }
    fun hideKeyboard() {
        val focus = activity.currentFocus ?: return
        WindowCompat.getInsetsController(activity.window, focus).hide(WindowInsetsCompat.Type.ime())
        focus.clearFocus()
    }

    // ── State ───────────────────────────────────────────
    fun notifyChanged() {
        if (waveRunning) return
        val visible = top()
        allScreens().forEach { if (it == visible) { if (it.root != null) it.onStateChanged() } else it.dirty = true }
    }
    fun report(message: String) { top().report(message) }
    fun markAllDirty() { allScreens().forEach { it.dirty = true } }

    // ── Bottom sheet ────────────────────────────────────
    fun showSheet(build: (PitUi, () -> Unit) -> View) {
        if (sheetLayer != null) sheetDismiss?.invoke()
        val layer = FrameLayout(activity)
        val scrim = View(activity).apply { setBackgroundColor(Color.argb(82, 10, 10, 10)); alpha = 0f; isClickable = true }
        layer.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        val sheet = GapLayout(activity, true, 0f).apply {
            background = ui.shape(t.surface, 34f)
            setPadding(ui.dp(22), ui.dp(10), ui.dp(22), ui.dp(22))
            isClickable = true
        }
        sheet.addView(View(activity).apply { background = ui.shape(t.line2, 3f) }, LinearLayout.LayoutParams(ui.dp(38), ui.dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ui.dp(18) })
        var closing = false
        val dismiss: () -> Unit = dismiss@{
            if (closing) return@dismiss
            closing = true
            hideKeyboard()
            val done = { host.removeView(layer); if (sheetLayer == layer) { sheetLayer = null; sheetDismiss = null } }
            if (PitMotion.reduced()) { done(); unshrinkStage(false) }
            else {
                scrim.animate().alpha(0f).setDuration(300).start()
                sheet.animate().translationY(sheet.height + ui.dp(24).toFloat()).setDuration(320).setInterpolator(PitMotion.easeIos).withEndAction { done() }.start()
                unshrinkStage(true)
            }
        }
        sheet.addView(build(ui, dismiss), LinearLayout.LayoutParams(-1, -2))
        layer.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(ui.dp(8), 0, ui.dp(8), ui.dp(8) + insetBottom) })
        scrim.setOnClickListener { dismiss() }
        host.addView(layer, FrameLayout.LayoutParams(-1, -1))
        sheetLayer = layer; sheetDismiss = dismiss
        if (PitMotion.reduced()) { scrim.alpha = 1f; return }
        sheet.visibility = View.INVISIBLE
        sheet.post {
            sheet.translationY = sheet.height + ui.dp(16).toFloat(); sheet.visibility = View.VISIBLE
            sheet.animate().translationY(0f).setDuration(PitMotion.springSoft.durationMs + 120).setInterpolator(PitMotion.springSoft).start()
            scrim.animate().alpha(1f).setDuration(420).setInterpolator(PitMotion.easeIos).start()
            shrinkStage()
        }
    }
    private var stageRadius = 0f
    private fun shrinkStage() {
        stage.pivotX = stage.width / 2f; stage.pivotY = 0f
        stage.outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, stageRadius) } }
        stage.clipToOutline = true
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600; interpolator = PitMotion.easeIos
            addUpdateListener { val f = it.animatedValue as Float; stage.scaleX = 1f - 0.06f * f; stage.scaleY = stage.scaleX; stage.translationY = ui.dp(14) * f + insetTop * 0.5f * f; stageRadius = ui.dp(28) * f; stage.invalidateOutline() }
            start()
        }
    }
    private fun unshrinkStage(animate: Boolean) {
        if (!animate) { stage.scaleX = 1f; stage.scaleY = 1f; stage.translationY = 0f; stage.clipToOutline = false; return }
        val fromS = stage.scaleX; val fromY = stage.translationY; val fromR = stageRadius
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 420; interpolator = PitMotion.easeIos
            addUpdateListener { val f = it.animatedValue as Float; stage.scaleX = 1f - (1f - fromS) * f; stage.scaleY = stage.scaleX; stage.translationY = fromY * f; stageRadius = fromR * f; stage.invalidateOutline() }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) { stage.clipToOutline = false } })
            start()
        }
    }
    val sheetOpen get() = sheetLayer != null

    // ── Theme: 파도 전환 ─────────────────────────────────
    fun applyTheme(animated: Boolean) {
        val mood = AppearancePreferences(activity).mood()
        if (mood == t.mood || waveRunning) return
        if (imeBottom > 0 && activity.currentFocus is EditText) { pendingTheme = true; return }
        val next = Tokens.of(mood)
        if (!animated || PitMotion.reduced()) { swap(next); return }
        if (sheetLayer != null) sheetDismiss?.invoke()
        waveRunning = true
        val overlay = WaveOverlayView(activity).apply {
            color = next.bg
            glint = when (mood) { Mood.PEARL -> Color.argb(217, 255, 255, 255); Mood.GRAPHITE -> Color.argb(56, 255, 255, 255); Mood.UMBER -> Color.argb(140, 226, 195, 158) }
        }
        host.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        val wave = PitMotion.easeWave; val ios = PitMotion.easeIos
        var swapped = false
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100 + 380
            interpolator = null
            addUpdateListener {
                val ms = it.currentPlayTime.toFloat()
                overlay.main = wave.getInterpolation((ms / 1100f).coerceIn(0f, 1f))
                overlay.foam = wave.getInterpolation(((ms + 110f) / 1100f).coerceIn(0f, 1f))
                overlay.phase = ms / 1400f
                if (ms >= 1100f && !swapped) {
                    swapped = true
                    PitHaptics.tick(host)
                    swap(next)
                    overlay.bringToFront()
                    cascade(top().content)
                }
                if (ms >= 1100f) overlay.alpha = 1f - ios.getInterpolation(((ms - 1100f) / 380f).coerceIn(0f, 1f))
                overlay.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!swapped) swap(next)
                    host.removeView(overlay); waveRunning = false
                }
            })
            start()
        }
    }
    private fun swap(next: Tokens) {
        t = next; ui = PitUi(activity, t)
        paintChrome()
        val visibleTab = tab
        tabScreens.values.forEach { s -> s.root?.let { tabContent.removeView(it) }; s.root = null }
        stack.forEach { s -> s.root?.let { stage.removeView(it) }; s.root = null }
        buildTabBar()
        val ts = screenFor(visibleTab)
        tabContent.addView(ts.create(ui), FrameLayout.LayoutParams(-1, -1))
        tabBar?.bringToFront()
        tabRoot.visibility = if (stack.isEmpty()) View.VISIBLE else View.INVISIBLE
        stack.forEachIndexed { i, s ->
            val v = s.create(ui)
            v.visibility = if (i == stack.lastIndex) View.VISIBLE else View.INVISIBLE
            stage.addView(v, FrameLayout.LayoutParams(-1, -1))
        }
    }

    /** 목록 차례로: 줄마다 0.05s, 12px 떠오름, 최대 8줄. */
    fun cascade(group: ViewGroup?, startDelay: Long = 0) {
        group ?: return
        if (PitMotion.reduced()) return
        var n = 0
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i)
            if (c.visibility != View.VISIBLE || n >= 8) continue
            c.alpha = 0f; c.translationY = ui.dp(12).toFloat()
            c.animate().alpha(1f).translationY(0f).setStartDelay(startDelay + n * 50L).setDuration(620).setInterpolator(PitMotion.easeIos).start()
            n++
        }
    }

    // ── Intro ───────────────────────────────────────────
    fun shouldPlayIntro(force: Boolean): Boolean {
        if (PitMotion.reduced()) return false
        return force || PitPrefs(activity).introShownDay != LocalDate.now().toString()
    }
    private var introScene: IntroView.Scene? = null
    fun playIntro() {
        PitPrefs(activity).introShownDay = LocalDate.now().toString()
        val scene = IntroView.build(this) { leaveIntro() }
        introScene = scene
        intro = scene.root
        host.addView(scene.root, FrameLayout.LayoutParams(-1, -1))
        screenFor(Tab.TODAY).root?.alpha = 0f
        host.postDelayed(introLeave, IntroView.HOLD_MS)
    }
    private val introLeave = Runnable { leaveIntro() }
    fun skipIntro() { leaveIntro() }
    /** 가운데의 로고가 머리말 로고 자리로 날아가고, 덮개가 걷히며 오늘 화면이 차례로 떠오른다. */
    private fun leaveIntro() {
        val v = intro ?: return
        val scene = introScene
        intro = null; introScene = null
        host.removeCallbacks(introLeave)
        val today = screenFor(Tab.TODAY)
        val target = today.root?.findViewWithTag<View>(Fmt.WORDMARK_TAG)
        today.root?.alpha = 1f
        target?.alpha = 0f
        // 내려앉을 자리는 화면 내용이 떠오르기(아래로 12dp 내려가 있는 상태) 전에 잰다
        val from = IntArray(2).also { v.getLocationInWindow(it) }
        val to = IntArray(2).also { target?.getLocationInWindow(it) }
        cascade(today.content, 100)
        // 덮개(인트로 바탕)만 걷는다. 로고 상자는 그대로 보이며 날아간다
        val cover = v.background?.mutate()
        android.animation.ValueAnimator.ofInt(255, 0).apply {
            duration = 620; interpolator = PitMotion.easeIos
            addUpdateListener { cover?.alpha = it.animatedValue as Int }
        }.start()
        scene?.skip?.animate()?.alpha(0f)?.setDuration(200)?.start()
        val fly = scene?.fly
        if (fly == null || target == null || fly.width == 0) {
            v.animate().alpha(0f).setDuration(300).withEndAction { host.removeView(v); target?.alpha = 1f }.start()
            return
        }
        // 건너뛰기로 일찍 날아가면 남은 글자 등장은 바로 끝낸다
        if (scene.reveal.isRunning) scene.reveal.end()
        fly.animate().translationX((to[0] - from[0]).toFloat()).translationY((to[1] - from[1]).toFloat())
            .scaleX(1f).scaleY(1f).setDuration(780).setInterpolator(PitMotion.easeIos)
            .withEndAction { target.alpha = 1f; host.removeView(v) }.start()
    }
}
