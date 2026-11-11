package com.recovery.companion

import android.animation.ValueAnimator
import android.graphics.Color
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.time.Instant

/** ‘지금 지우기’: 요약 삭제는 연결 해제와 함께 간다(서버 계약). 결과는 서버 응답으로만 ‘지웠어요’. */
fun deleteSummarySheet(shell: PhoneShell) = shell.showSheet { ui, dismiss ->
    ui.vstack(14f).apply {
        addView(ui.h2("차에 보낸 요약을 지울까요?"))
        addView(ui.body("요약을 지우면 차량 연결도 함께 끊겨요. 다시 보내려면 차 화면의 번호로 새로 연결해요."))
        addView(ui.primary("지우고 연결 끊기") { dismiss(); shell.app.act(CompanionAction.REVOKE) }, LinearLayout.LayoutParams(-1, ui.dp(56)).apply { topMargin = ui.dp(8) })
        addView(ui.ghost("그대로 두기") { dismiss() }, LinearLayout.LayoutParams(-2, ui.dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }
}

class PairScreen(shell: PhoneShell) : Screen(shell) {
    override val gap = 26f
    private var renderedStep = ""
    private var boxes = listOf<TextView>()
    private var next: TextView? = null
    private fun step() = when {
        app.pairing?.revocationPending == true -> "revoking"
        app.pairingConfirmed -> "done"
        app.pairing != null -> "compare"
        else -> "code"
    }
    override fun onStateChanged() { if (step() != renderedStep || step() != "code") rebuild() else updateCode() }
    override fun onShown() {
        if (step() == "code" && !app.serverless) content.postDelayed({ (content.findViewWithTag<View>("code-field") as? EditText)?.let { f -> f.requestFocus(); WindowCompat.getInsetsController(ctx.window, f).show(WindowInsetsCompat.Type.ime()) } }, 600)
    }
    override fun fill(c: GapLayout) {
        val s = step(); renderedStep = s
        c.addView(ui.backHeader(null, "차량", { shell.pop() }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = -ui.dp(8); marginStart = -ui.dp(10) })
        c.addView(ui.hstack(6f).apply {
            val done = when (s) { "code" -> 1; "compare" -> 2; "done" -> 3; else -> 1 }
            for (i in 1..3) addView(View(ctx).apply { background = ui.shape(if (i <= done) t.ink else t.line2, 2f) }, LinearLayout.LayoutParams(0, ui.dp(3), 1f))
        })
        when (s) {
            "code" -> code(c)
            "compare" -> compare(c)
            "done" -> done(c)
            else -> revoking(c)
        }
    }
    private fun code(c: GapLayout) {
        if (app.serverless) {
            boxes = emptyList(); next = null
            c.addView(ui.vstack(12f).apply {
                addView(ui.display("이 빌드에서는\n차와 연결할 수 없어요", 30f))
                addView(ui.body("연결 서버 없이 동작하는 빌드예요. 연결하지 않아도 폰에서 취향을 고르고, 차에서 바로 쉴 수 있어요."))
            })
            statusLine = ui.status().also { c.addView(it) }
            return
        }
        c.addView(ui.vstack(12f).apply {
            addView(ui.display("차 화면의 번호를\n입력해 주세요", 30f))
            addView(ui.body("차에서 차분을 열고 ‘폰 연결’을 누르면 6자리 번호가 보여요."))
        })
        val frame = FrameLayout(ctx)
        val row = ui.hstack(8f)
        boxes = (0 until 6).map { ui.num("", 30f, 400).apply { gravity = Gravity.CENTER } }
        boxes.forEach { row.addView(it, LinearLayout.LayoutParams(0, ui.dp(64), 1f)) }
        frame.addView(row, FrameLayout.LayoutParams(-1, ui.dp(64)))
        val field = EditText(ctx).apply {
            tag = "code-field"
            inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(InputFilter.LengthFilter(6))
            setTextColor(Color.TRANSPARENT); background = null; isCursorVisible = false; setText(app.pairCode)
            imeOptions = EditorInfo.IME_ACTION_DONE; contentDescription = "차 화면의 6자리 번호"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) { app.pairCode = e.toString().filter { it.isDigit() }; updateCode(); if (app.pairCode.length == 6) PitHaptics.tick(this@apply) }
            })
            setOnEditorActionListener { _, _, _ -> if (app.pairCode.length == 6) app.act(CompanionAction.CLAIM); true }
        }
        frame.addView(field, FrameLayout.LayoutParams(-1, ui.dp(64)))
        c.addView(frame, LinearLayout.LayoutParams(-1, ui.dp(64)).apply { topMargin = ui.dp(6) })
        c.addView(ui.caption("번호는 5분 동안만 쓸 수 있어요."))
        statusLine = ui.status().also { c.addView(it) }
        spacer(c)
        next = ui.primary("다음") { shell.hideKeyboard(); app.act(CompanionAction.CLAIM) }.also { c.addView(it) }
        updateCode()
    }
    private fun updateCode() {
        val v = app.pairCode
        boxes.forEachIndexed { i, b ->
            b.text = v.getOrNull(i)?.toString() ?: ""
            b.background = if (i == v.length.coerceAtMost(5)) ui.shape(t.surface, 16f, t.ink, 1.5f) else ui.shape(t.surface, 16f, t.line2)
        }
        next?.let { ui.setEnabledLook(it, v.length == 6 && !app.busy) }
        next?.text = if (app.busy) "연결하고 있어요" else "다음"
    }
    private fun compare(c: GapLayout) {
        c.addView(ui.vstack(12f).apply {
            addView(ui.display("두 화면의 숫자가\n같은가요?", 30f))
            addView(ui.body("같으면 차 화면에서 ‘승인’을 눌러 주세요. 다르면 연결하지 마세요."))
        })
        val code = app.confirmationCode
        c.addView(ui.card(22f, 34f, 12f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(22), ui.dp(34), ui.dp(22), ui.dp(28))
            addView(ui.overline("확인 숫자"), ui.wrap())
            addView(ui.num(code?.toCharArray()?.joinToString(" ") ?: "· · · ·", 64f, 300).apply { letterSpacing = 0.18f }, ui.wrap())
            addView(ui.live(if (code != null) "차의 승인을 기다리는 중" else "연결 상태를 확인하고 있어요", idle = true).apply { (getChildAt(1).layoutParams as LinearLayout.LayoutParams).apply { width = -2; weight = 0f } }, ui.wrap())
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        c.addView(ui.ghost(if (app.busy) "확인하고 있어요" else "차에서 승인했다면 확인하기", t.ink, 14f) { app.act(CompanionAction.CHECK_PAIRING) }.also { ui.setEnabledLook(it, !app.busy) },
            LinearLayout.LayoutParams(-2, ui.dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = -ui.dp(10) })
        statusLine = ui.status().also { c.addView(it) }
        spacer(c)
        c.addView(ui.secondary("숫자가 달라요") {
            shell.showSheet { ui, dismiss ->
                ui.vstack(14f).apply {
                    addView(ui.h2("이 연결을 멈출까요?"))
                    addView(ui.body("숫자가 다르면 다른 차일 수 있어요. 이 요청을 지우고 처음부터 다시 연결해요."))
                    addView(ui.primary("연결하지 않을게요") { dismiss(); app.act(CompanionAction.REVOKE) }, LinearLayout.LayoutParams(-1, ui.dp(56)).apply { topMargin = ui.dp(8) })
                    addView(ui.ghost("다시 볼게요") { dismiss() }, LinearLayout.LayoutParams(-2, ui.dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                }
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(-1, ui.dp(56)) })
    }
    private fun done(c: GapLayout) {
        c.addView(ui.vstack(12f).apply {
            addView(ui.display("차량과\n이어졌어요", 30f))
            addView(ui.body("이제 고른 쉼을 차로 전할 수 있어요. 쉬기는 언제나 차에서 직접 시작해요."))
        })
        val left = app.pairing?.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }?.let { java.time.Duration.between(Instant.now(), it).toMinutes() }
        val keep = when { left == null -> "시간이 지나면 자동으로 끊겨요"; left >= 60 -> "${(left + 30) / 60}시간 뒤 자동으로 끊겨요"; left > 0 -> "${left}분 뒤 자동으로 끊겨요"; else -> "곧 자동으로 끊겨요" }
        val ap = AppearancePreferences(ctx)
        c.addView(ui.card(20f, 4f, 0f).apply {
            addView(ui.row(null, "연결 유지", keep, chevron = false, topLine = false, endView = ui.live("이어짐"), titleWeight = 600).apply { (getChildAt(0) as LinearLayout).getChildAt(0).let { (it as TextView).textSize = 15f } })
            addView(ui.row(null, "화면 분위기", ap.statusText(), chevron = false).apply { (getChildAt(0) as LinearLayout).getChildAt(0).let { (it as TextView).textSize = 15f } })
        })
        statusLine = ui.status().also { c.addView(it) }
        spacer(c)
        c.addView(ui.primary("오늘로 돌아가기") { shell.popToRoot(Tab.TODAY) })
        c.addView(ui.ghost("연결 끊기", t.warn) {
            shell.showSheet { ui, dismiss ->
                ui.vstack(14f).apply {
                    addView(ui.h2("차량 연결을 끊을까요?"))
                    addView(ui.body("연결을 끊으면 차에 보낸 요약도 함께 지워요. 이 폰의 쉼과 취향은 그대로예요."))
                    addView(ui.primary("연결 끊기") { dismiss(); app.act(CompanionAction.REVOKE) }, LinearLayout.LayoutParams(-1, ui.dp(56)).apply { topMargin = ui.dp(8) })
                    addView(ui.ghost("그대로 두기") { dismiss() }, LinearLayout.LayoutParams(-2, ui.dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                }
            }
        }, LinearLayout.LayoutParams(-2, ui.dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = -ui.dp(14) })
    }
    private fun revoking(c: GapLayout) {
        c.addView(ui.vstack(12f).apply {
            addView(ui.display("삭제를 아직\n확인하지 못했어요", 30f))
            addView(ui.body("이 폰의 미리보기는 지웠어요. 서버가 지웠다고 답할 때까지 새 연결과 보내기는 잠시 멈춰요."))
        })
        statusLine = ui.status().also { c.addView(it) }
        spacer(c)
        c.addView(ui.primary(if (app.busy) "확인하고 있어요" else "삭제 다시 확인") { app.act(CompanionAction.REVOKE) }.also { ui.setEnabledLook(it, !app.busy) })
    }
}

/** ‘차로 보내기’ 버튼이 원으로 줄며 고리 → 체크 → ‘보냈어요’로 펼쳐진다. */
class SendButton(private val ui: PitUi, private val onSend: () -> Unit) : FrameLayout(ui.context) {
    private val label = ui.text("차로 보내기", 16f, 600, ui.t.onPrimary, 1.2f).apply { gravity = Gravity.CENTER }
    private val ring = SpinnerRing(ui.context, ui.t.onPrimary).apply { visibility = View.GONE }
    private val check = ui.icon("check", ui.t.onPrimary, 22f, 2.2f).apply { visibility = View.GONE }
    private var fullWidth = 0
    var sending = false; private set
    init {
        background = ui.shape(ui.t.primary, 999f)
        addView(label, LayoutParams(-1, -1)); addView(ring, LayoutParams(ui.dp(26), ui.dp(26), Gravity.CENTER)); addView(check, LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER))
        isClickable = true; isFocusable = true; contentDescription = "차로 보내기"
        setOnClickListener { if (isEnabled && !sending) onSend() }
        pressable()
    }
    fun ready(enabled: Boolean) { isEnabled = enabled; alpha = if (enabled) 1f else 0.38f }
    private fun widthTo(target: Int, end: () -> Unit = {}) {
        val lp = layoutParams as LinearLayout.LayoutParams
        if (PitMotion.reduced()) { lp.width = target; layoutParams = lp; end(); return }
        val from = if (lp.width > 0) lp.width else width
        ValueAnimator.ofInt(from, target).apply {
            duration = 560; interpolator = PitMotion.easeIos
            addUpdateListener { lp.width = it.animatedValue as Int; layoutParams = lp }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { end() } })
            start()
        }
    }
    fun startSending() {
        sending = true; fullWidth = width
        label.visibility = View.INVISIBLE
        widthTo(ui.dp(56)) { if (sending) { ring.visibility = View.VISIBLE; ring.syncLoop() } }
        contentDescription = "보내는 중"
    }
    fun succeed() {
        ring.visibility = View.GONE
        check.visibility = View.VISIBLE; check.scaleX = 0f; check.scaleY = 0f
        check.animate().scaleX(1f).scaleY(1f).setDuration(PitMotion.springPop.durationMs).setInterpolator(PitMotion.springPop).start()
        postDelayed({
            check.visibility = View.GONE; label.text = "보냈어요"; label.visibility = View.VISIBLE
            widthTo(fullWidth.takeIf { it > 0 } ?: LinearLayout.LayoutParams.MATCH_PARENT)
        }, 520)
        PitHaptics.confirm(this); contentDescription = "보냈어요"
    }
    fun fail() {
        sending = false; ring.visibility = View.GONE
        widthTo(fullWidth.takeIf { it > 0 } ?: LinearLayout.LayoutParams.MATCH_PARENT) {
            (layoutParams as LinearLayout.LayoutParams).width = LinearLayout.LayoutParams.MATCH_PARENT; requestLayout()
            label.text = "차로 보내기"; label.visibility = View.VISIBLE
        }
        contentDescription = "차로 보내기"
    }
}

class ShareScreen(shell: PhoneShell) : Screen(shell) {
    override val gap = 24f
    private var sendingSince = 0L
    private var send: SendButton? = null
    override fun onStateChanged() {
        val button = send
        if (sendingSince > 0 && button != null) {
            if (app.busy) return
            val ok = (app.lastSent?.sentAt?.toEpochMilli() ?: 0) >= sendingSince
            sendingSince = 0
            if (ok) { button.succeed(); content.postDelayed({ shell.replaceTop(SentScreen(shell)) }, 1400) }
            else { button.fail(); content.postDelayed({ rebuild() }, 600) }
            return
        }
        rebuild()
    }
    override fun fill(c: GapLayout) {
        val store = PresetStore(ctx)
        c.addView(ui.hstack().apply {
            addView(ui.glassButton("back", "닫기", 44f) { shell.pop() }.apply { background = null })
            addView(ui.space(), LinearLayout.LayoutParams(0, 1, 1f))
            addView(ui.ghost("운동 기록 없이 계속", t.ink2, 14f) { shell.pop(); shell.showTab(Tab.COMFORT, true) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = -ui.dp(8); marginStart = -ui.dp(10); marginEnd = -ui.dp(10) })
        c.addView(ui.vstack(10f).apply {
            addView(ui.display("이번 운동을\n차에 함께 전할까요?", 30f))
            addView(ui.body("고른 것만, 이번 한 번만 보내요."))
        })
        // 01
        c.addView(ui.vstack(10f).apply {
            addView(ui.numbered("01", "이번 운동"))
            val w = app.workout
            addView(ui.card(18f, 16f, 0f).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(ui.glass("pulse"))
                addView(ui.vstack(3f).apply {
                    if (w != null) {
                        addView(ui.rowTitle("${Fmt.workout(w.type)} · ${w.minutes}분"))
                        addView(ui.rowSub("${Fmt.time(w.end)}에 마침 · ${Fmt.provider(w)}"))
                    } else {
                        addView(ui.rowTitle(if (app.lastReadEmpty) "가져올 운동이 없어요" else "아직 고른 운동이 없어요"))
                        addView(ui.rowSub(if (app.lastReadEmpty) "최근 24시간 안에 마친 운동이 없어요" else "방금 마친 운동 하나만 읽어요"))
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(14); marginEnd = ui.dp(6) })
                if (w != null) addView(ui.ghost("바꾸기", t.ink2, 13f) { workoutSheet() }.apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) })
                else addView(ui.secondary(if (app.busy) "읽는 중" else "읽기", 40f, 14f) { readWorkout() }.also { ui.setEnabledLook(it, !app.busy) })
            })
            if (w == null) addView(ui.ghost("다른 곳에서 읽기", t.ink3, 13f) { workoutSheet() }, LinearLayout.LayoutParams(-2, ui.dp(36)).apply { gravity = Gravity.END })
        })
        // 02
        c.addView(ui.vstack(6f).apply {
            addView(ui.numbered("02", "함께 전할 쉼"))
            val descriptors = mapOf("cool_down" to "가볍고 시원하게", "quiet_rest" to "움직임을 덜고 잔잔하게", "gentle_refresh" to "따뜻하고 차분하게")
            val rows = store.all().map { p ->
                ui.radioRow(p.name, if (p.kind == "builtin") descriptors[p.id] else Fmt.presetSub(p), p.id == store.selectedId, 60f) {
                    if (p.id != store.selectedId) app.selectPreset(p.id)
                }
            }
            addView(ui.list(*rows.toTypedArray()))
        })
        // 03
        c.addView(ui.vstack(12f).apply {
            addView(ui.numbered("03", "보내는 내용"))
            addView(ui.pills("운동 종류", "운동 시간", "마친 시각", "고른 쉼"))
            addView(ui.caption("심박, 위치, 이름 같은 기록은 보내지 않아요."))
            addView(ui.checkRow("이번 한 번만 연결된 차에 보낼게요", app.consent) { app.consent = it; updateSend() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        })
        val revoking = app.pairing?.revocationPending == true
        if (!app.pairingConfirmed || revoking) {
            c.addView(ui.sunken(18f, 16f, 14f, 10f).apply {
                val noServer = app.serverless && app.pairing == null
                addView(ui.rowTitle(if (revoking) "삭제를 먼저 확인해 주세요" else if (app.pairing != null) "차량 연결을 다시 확인해 주세요" else if (noServer) "이 빌드에서는 차로 보낼 수 없어요" else "먼저 차와 연결해 주세요", 14f))
                addView(ui.caption(if (revoking) "삭제가 확인될 때까지 보내기를 잠시 멈춰요." else if (noServer) "연결 서버 없이 동작하는 빌드예요. 연결하지 않아도 쉴 수 있어요." else "연결은 차 화면의 6자리 번호로 해요. 연결하지 않아도 쉴 수 있어요."))
                if (!noServer) addView(ui.secondary("차량 연결로", 40f, 14f) { shell.push(PairScreen(shell)) })
            })
        }
        statusLine = ui.status().also { c.addView(it) }
        spacer(c)
        c.addView(ui.vstack(10f).apply {
            val button = SendButton(ui) {
                sendingSince = System.currentTimeMillis(); send?.startSending(); clearMessage()
                app.act(CompanionAction.SEND)
            }
            send = button
            addView(button, LinearLayout.LayoutParams(-1, ui.dp(56)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(ui.caption("차에는 30분까지만 남고, 운동을 마친 지 2시간이 지나면 지워져요.").apply { gravity = Gravity.CENTER })
        })
        updateSend()
    }
    private fun updateSend() {
        val ready = app.workout != null && app.pairingConfirmed && app.consent && !app.busy && app.pairing?.revocationPending != true
        send?.ready(ready)
    }
    private fun readWorkout() = app.act(if (PitPrefs(ctx).healthSource == "samsung") CompanionAction.READ_SAMSUNG else CompanionAction.READ_ALL)
    private fun workoutSheet() = shell.showSheet { ui, dismiss ->
        ui.vstack(10f).apply {
            addView(ui.h2("어떤 운동을 전할까요?"))
            addView(ui.caption("최근 24시간 안에 마친 운동 하나만 읽어요. 읽기만 하고 아직 보내지 않아요."))
            val rows = mutableListOf<View>(
                ui.row("heart", "삼성 헬스에서 다시 읽기", "갤럭시 워치·삼성 헬스로 기록한 운동") { dismiss(); PitPrefs(ctx).healthSource = "samsung"; app.act(CompanionAction.READ_SAMSUNG) },
                ui.row("pulse", "모든 운동 앱에서 읽기", "Health Connect에 기록하는 앱 전체") { dismiss(); PitPrefs(ctx).healthSource = "all"; app.act(CompanionAction.READ_ALL) })
            if (app.debug) rows += ui.row("spark", "체험용 합성 걷기 20분", "건강 기록을 읽지 않는 개발 체험") { dismiss(); app.act(CompanionAction.DEMO) }
            if (app.workout != null) rows += ui.row("close", "이 폰의 미리보기 지우기", "이미 보낸 요약은 그대로예요") { dismiss(); app.act(CompanionAction.CLEAR) }
            addView(ui.list(*rows.toTypedArray()))
        }
    }
}

class SentScreen(shell: PhoneShell) : Screen(shell) {
    override val gap = 26f
    override fun fill(c: GapLayout) {
        val sent = app.lastSent
        val revoking = app.pairing?.revocationPending == true
        // 지운 뒤에는 ‘기다리고 있어요’ 같은 보낸 상태를 남기지 않는다
        val gone = !revoking && (sent == null || app.pairing == null)
        val deleted = app.deletionConfirmedAt > 0
        c.addView(moodArt(ui, 150f, 230f, 260f, 0.78f), LinearLayout.LayoutParams(-1, ui.dp(150)).apply { marginStart = -ui.dp(22); marginEnd = -ui.dp(22); topMargin = ui.dp(6); bottomMargin = -ui.dp(10) })
        c.addView(ui.vstack(10f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ui.display(if (!gone) "차에서 이어가세요" else if (deleted) "지웠어요" else "차에 남은 요약이 없어요", 30f).apply { gravity = Gravity.CENTER })
            addView(ui.body(if (!gone) "차에서 차분을 열면\n고른 쉼이 기다리고 있어요."
                else if (deleted) "차에 보낸 요약과 연결을\n서버에서 지운 것을 확인했어요."
                else "다시 보내려면\n차 화면의 번호로 새로 연결해요.").apply { gravity = Gravity.CENTER })
        })
        if (!gone) c.addView(ui.card(22f, 22f, 18f).apply {
            setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(20))
            val detail = sent?.let { "${Fmt.time(it.sentAt)} · ${it.presetName} · ${Fmt.workout(it.workoutType)} ${it.minutes}분" + if (it.demo) " · 체험" else "" }
            addView(ui.step(2, "보냈어요", detail))
            // 서버는 차가 받았는지 알려 주지 않는다. 기다리는 척하지 않고, 차에서 확인하는 법을 알려 준다
            addView(ui.step(1, "받았는지는 차에서 확인해요", "차 화면에 ‘폰에서 고른 쉼이 도착했어요’가 보이면 받은 거예요"))
            addView(ui.step(0, "차에서 ‘3분 쉬기’를 눌러 시작", "받기만 하고 저절로 시작하지는 않아요", muted = true))
        })
        if (!gone) c.addView(ui.hstack(8f).apply {
            setPadding(ui.dp(4), 0, ui.dp(4), 0)
            addView(ui.vstack(2f).apply {
                when {
                    revoking -> { addView(ui.rowTitle("삭제를 아직 확인하지 못했어요", 14f)); addView(ui.caption("새 보내기는 확인될 때까지 잠시 멈춰요")) }
                    sent == null || app.pairing == null -> { addView(ui.rowTitle(if (app.deletionConfirmedAt > 0) "지웠어요" else "차에 남은 요약이 없어요", 14f)); addView(ui.caption(if (app.deletionConfirmedAt > 0) "서버에서 지운 것을 확인했어요" else "연결을 다시 하면 보낼 수 있어요")) }
                    else -> { addView(ui.rowTitle("${Fmt.time(sent.expiresAt)}까지 차에 남아요", 14f)); addView(ui.caption("그 뒤에는 저절로 지워져요")) }
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (revoking) addView(ui.ghost("삭제 다시 확인", t.warn, 14f) { app.act(CompanionAction.REVOKE) })
            else if (sent != null && app.pairing != null) addView(ui.ghost("지금 지우기", t.warn, 14f) { deleteSummarySheet(shell) })
        })
        statusLine = ui.status().also { c.addView(it) }
        spacer(c)
        c.addView(ui.primary("오늘로 돌아가기") { shell.popToRoot(Tab.TODAY) })
    }
}

/** 쉼 편집 전체 화면: 차에서/집에서 분절, 온도 ±0.5 스테퍼(숫자 굴림), 바람·시트·전원 칩. */
class PresetEditScreen(shell: PhoneShell, presetId: String?) : Screen(shell) {
    override val gap = 24f
    private val store = PresetStore(ctx)
    private val source: Preset = presetId?.let { id -> store.all().firstOrNull { it.id == id } } ?: store.selected()
    private val isNew = presetId == null || source.kind == "builtin"
    private var name = if (isNew) source.name + " 내 쉼" else source.name
    private var comfort = source.comfort
    private var vehicle = source.vehicle
    private var home = source.home
    private var place = "car"
    private var makeDefault = !isNew && store.defaultId == source.id && store.autoDefault
    private var cardBox: GapLayout? = null
    private var noteBox: LinearLayout? = null
    override fun onStateChanged() {}
    override fun fill(c: GapLayout) {
        c.addView(ui.hstack().apply {
            addView(ui.ghost("취소", t.ink2, 15f) { shell.pop() })
            addView(ui.text(if (isNew) "내 쉼 만들기" else "쉼 편집", 16f, 600, t.ink, 1.3f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.ghost("저장", t.ink, 15f) { save() })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = -ui.dp(8); marginStart = -ui.dp(10); marginEnd = -ui.dp(10) })
        c.addView(ui.vstack(8f).apply {
            addView(ui.caption("이름"))
            addView(EditText(ctx).apply {
                setText(name); textSize = 20f; typeface = PitType.ko(600); letterSpacing = -0.02f; setTextColor(t.ink); setHintTextColor(t.ink3)
                background = ui.shape(t.surface, 20f, t.line2); setPadding(ui.dp(18), 0, ui.dp(18), 0); setSingleLine(); hint = "쉼 이름"
                filters = arrayOf(InputFilter.LengthFilter(40)); contentDescription = "이름"
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(e: Editable?) { name = e.toString() }
                })
            }, LinearLayout.LayoutParams(-1, ui.dp(60)))
            if (isNew) addView(ui.caption("‘${source.name}’에서 복사했어요"))
        })
        c.addView(ui.vstack(14f).apply {
            // 집 기기는 합성 기기 체험이라 디버그 빌드에서만 보인다.
            if (app.debug) addView(SegmentedView(ctx, ui, listOf(Triple("car", "차에서", null), Triple("home", "집에서", null)), place) { place = it; fillCard() })
            cardBox = ui.card(20f, 6f, 0f).also { addView(it) }
            noteBox = ui.hstack().also { addView(it) }
        })
        fillCard()
        c.addView(ui.list(ui.toggleRow("기본 쉼으로", "쉬기 준비를 누르면 이 쉼이 먼저 골라져요", makeDefault) { makeDefault = it }))
        statusLine = ui.status().also { c.addView(it) }
        spacer(c)
        if (!isNew) c.addView(ui.ghost("이 쉼 지우기", t.warn, 14f) { confirmDelete() }, LinearLayout.LayoutParams(-2, ui.dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }
    private fun fillCard() {
        val card = cardBox ?: return
        card.removeAllViews()
        val car = place == "car"
        val temp = if (car) vehicle.tempC else home.tempC
        val lo = 18.0; val hi = if (car) 28.0 else 30.0
        val roll = RollingNumberView(ctx, t.ink, 34f).apply { placeholder = "그대로"; setValue(temp?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "", false) }
        fun setTemp(v: Double?) {
            if (car) vehicle = vehicle.copy(tempC = v) else home = home.copy(tempC = v)
            roll.setValue(v?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "")
        }
        card.addView(ui.hstack(6f).apply {
            minimumHeight = ui.dp(92); setPadding(0, ui.dp(12), 0, ui.dp(12))
            addView(ui.vstack(3f).apply { addView(ui.rowTitle("온도", weight = 500)); addView(ui.rowSub("0.5도씩 · ${lo.toInt()}~${hi.toInt()}°")) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.glassButton("minus", "온도 내리기", 40f) { val cur = (if (car) vehicle.tempC else home.tempC) ?: (if (car) 24.0 else 25.0); setTemp((cur - 0.5).coerceIn(lo, hi)) })
            addView(FrameLayout(ctx).apply { addView(roll, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) }, LinearLayout.LayoutParams(ui.dp(100), ui.dp(48)))
            addView(ui.glassButton("plusSmall", "온도 올리기", 40f) { val cur = (if (car) vehicle.tempC else home.tempC) ?: (if (car) 23.5 else 24.5); setTemp((cur + 0.5).coerceIn(lo, hi)) })
        })
        fun chipsRow(title: String, options: List<Pair<String, String>>, current: String, set: (String) -> Unit) = ui.vstack(12f).apply {
            setPadding(0, ui.dp(16), 0, ui.dp(16)); background = TopLineDrawable(t.line, ctx.dpf(1).coerceAtLeast(1f))
            addView(ui.rowTitle(title, weight = 500))
            addView(ui.flow(6f, 6f).apply { options.forEach { (id, label) -> addView(ui.chip(label, id == current, small = true) { set(id); fillCard() }) } })
        }
        if (car) {
            card.addView(chipsRow("바람", listOf("keep" to "그대로", "low" to "약하게", "medium" to "중간", "high" to "세게"), vehicle.fan) { vehicle = vehicle.copy(fan = it) })
            card.addView(chipsRow("시트", listOf("keep" to "그대로", "manual_comfort" to "직접 편하게"), vehicle.seat) { vehicle = vehicle.copy(seat = it) })
            card.addView(chipsRow("함께 전하는 쉼의 결", listOf("cool_down" to "산뜻하게", "quiet_rest" to "조용히", "gentle_refresh" to "부드럽게"), comfort) { comfort = it })
        } else {
            card.addView(chipsRow("바람", listOf("keep" to "그대로", "auto" to "자동", "low" to "약하게", "medium" to "중간", "high" to "세게"), home.fan) { home = home.copy(fan = it) })
            card.addView(chipsRow("전원", listOf("keep" to "그대로", "on" to "켜기"), home.power) { home = home.copy(power = it) })
        }
        noteBox?.let { n -> n.removeAllViews(); n.addView(ui.info(if (car) "차에서 쉼을 시작하면 이 값으로 차 안을 맞추고, 마치면 쉬기 전으로 돌려요. 차 제어를 쓸 수 없는 차에서는 이 값으로 직접 맞춰 주세요." else "집 기기는 귀가 준비를 켤 때만, 고른 한 대에 한 번 요청해요."), LinearLayout.LayoutParams(-1, -2)) }
    }
    private fun save() {
        try {
            val id = if (isNew) java.util.UUID.randomUUID().toString() else source.id
            val value = Preset(id, name, "custom", comfort, vehicle, home)
            store.save(value)
            if (makeDefault) store.setDefault(id, true)
            else if (store.defaultId == id) store.setDefault(id, false)
            shell.hideKeyboard()
            shell.markAllDirty()
            app.selectPreset(id)
            shell.pop()
        } catch (e: Exception) {
            report(e.message?.takeIf { it.isNotBlank() } ?: "이름과 온도를 확인해 주세요.")
        }
    }
    private fun confirmDelete() = shell.showSheet { ui, dismiss ->
        ui.vstack(14f).apply {
            addView(ui.h2("이 쉼을 지울까요?"))
            addView(ui.body(if (app.debug) "‘$name’을 이 폰에서 지워요. 이미 만든 귀가 준비 예약은 그대로예요." else "‘$name’을 이 폰에서 지워요."))
            addView(ui.primary("지우기") {
                dismiss(); store.delete(source.id); shell.markAllDirty(); app.selectPreset(PresetStore(ctx).selectedId); shell.pop()
            }, LinearLayout.LayoutParams(-1, ui.dp(56)).apply { topMargin = ui.dp(8) })
            addView(ui.ghost("그대로 두기") { dismiss() }, LinearLayout.LayoutParams(-2, ui.dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
    }
}
