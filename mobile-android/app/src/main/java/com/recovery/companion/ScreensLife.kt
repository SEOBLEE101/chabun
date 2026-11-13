package com.recovery.companion

import android.app.TimePickerDialog
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** 홈 '지금' 카드: 출퇴근·웜업·운동 뒤에만 보인다. 제안만 하고 시작은 사용자가 누른다. */
fun momentCardFor(screen: Screen): View? {
    val ctx = screen.ctx; val ui = screen.ui; val app = screen.app; val shell = screen.shell
    val prefs = LifeRestPrefs(ctx)
    val now = LocalDateTime.now()
    val plan = prefs.plan(now.toLocalDate())
    val commute = prefs.commute
    val lastEnd = app.workout?.end?.let { LocalDateTime.ofInstant(it, ZoneId.systemDefault()) }
    val moment = runCatching { DayRhythmPolicy.momentAt(now, commute, plan, lastEnd) }.getOrDefault(DayMoment.NONE)
    val card = DayRhythmPolicy.card(moment, commute, plan) ?: return null
    return ui.sunken(24f, 20f, 18f, 8f).apply {
        addView(ui.overline("지금"))
        addView(ui.rowTitle(card.title, 17f))
        addView(ui.body(card.body, 14f))
        addView(ui.hstack(8f).apply {
            if (card.action == "warmup" && plan != null) {
                addView(ui.secondary("웜업 보기", 44f, 14f) { shell.push(WarmupScreen(shell, plan.type)) })
            } else {
                val store = PresetStore(ctx)
                val preset = card.presetId?.let { id -> store.all().firstOrNull { it.id == id } }
                addView(ui.secondary(preset?.let { "‘${it.name}’로 준비" } ?: "쉴 준비", 44f, 14f) {
                    store.beginRest()
                    preset?.let { app.selectPreset(it.id) }
                    shell.push(ShareScreen(shell))
                })
            }
            addView(ui.ghost("설정", screen.t.ink3, 14f) { shell.push(LifeRestScreen(shell)) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
    }
}

private fun dayLabel(d: DayOfWeek) = "월화수목금토일"[d.value - 1].toString()
private fun hm(t: LocalTime) = "%d:%02d".format(t.hour, t.minute)

class LifeRestScreen(shell: PhoneShell) : Screen(shell) {
    override val gap = 24f
    private val prefs = LifeRestPrefs(ctx)

    override fun fill(c: GapLayout) {
        c.addView(ui.backHeader(null, "하루 쉼", { shell.pop() }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = -ui.dp(8); marginStart = -ui.dp(10) })
        c.addView(ui.vstack(10f).apply {
            addView(ui.display("하루의 틈마다\n쉬어 갈까요?", 30f))
            addView(ui.body("정한 때가 되면 홈에 쉼을 제안해요. 쉬기 시작과 차 움직임은 언제나 직접 눌러요."))
        })
        commuteSection(c)
        planSection(c)
        c.addView(ui.list(
            ui.row("house", "쉬어 갈 곳", placesSub()) { shell.push(PlacesScreen(shell)) },
        ))
        statusLine = ui.status().also { c.addView(it) }
        c.addView(ui.info("설정은 이 폰에만 저장해요. 서버와 차로 보내지 않아요."))
    }

    private fun placesSub(): String {
        val b = prefs.placeBook
        val fav = b.favorites().size
        return if (fav == 0) "즐겨찾는 산책로·카페를 모아 둬요" else "즐겨찾기 ${fav}곳" + if (b.countVisits) " · 자주 가는 곳 기억 중" else ""
    }

    private fun save(block: () -> Unit) {
        try { block(); rebuild() } catch (e: IllegalArgumentException) { report(e.message ?: "저장하지 못했어요.") }
    }

    private fun commuteSection(c: GapLayout) {
        val s = prefs.commute
        c.addView(ui.vstack(6f).apply {
            addView(ui.sectionHead("출퇴근"))
            val rows = mutableListOf<View>(ui.toggleRow("출퇴근 때 쉼 제안", "출근 전 30분, 퇴근 무렵 1시간 동안 홈에 보여요", s.enabled) { on ->
                save { prefs.commute = s.copy(enabled = on) }
            })
            if (s.enabled) {
                rows += ui.row("today", "출근", "앞뒤 30분 ‘${presetName(s.morningPresetId)}’", end = hm(s.morning)) {
                    pickTime(s.morning) { t -> save { prefs.commute = prefs.commute.copy(morning = t) } }
                }
                rows += ui.row("today", "퇴근", "15분 전부터 한 시간쯤 ‘${presetName(s.eveningPresetId)}’", end = hm(s.evening)) {
                    pickTime(s.evening) { t -> save { prefs.commute = prefs.commute.copy(evening = t) } }
                }
            }
            addView(ui.list(*rows.toTypedArray()))
            if (s.enabled) {
                addView(ui.caption("요일"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
                addView(ui.flow().apply {
                    DayOfWeek.values().forEach { d ->
                        val on = d in s.days
                        addView(ui.chip(dayLabel(d), on, small = true) {
                            save { prefs.commute = prefs.commute.copy(days = if (on) s.days - d else s.days + d) }
                        }.apply { contentDescription = dayLabel(d) + "요일" + if (on) ", 켜짐" else "" })
                    }
                })
                presetChoice("출근 때 쉼", s.morningPresetId) { id -> save { prefs.commute = prefs.commute.copy(morningPresetId = id) } }
                presetChoice("퇴근 때 쉼", s.eveningPresetId) { id -> save { prefs.commute = prefs.commute.copy(eveningPresetId = id) } }
            }
        })
    }

    private fun GapLayout.presetChoice(label: String, current: String, onPick: (String) -> Unit) {
        addView(ui.caption(label), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        addView(ui.flow().apply {
            PresetStore(ctx).all().forEach { p -> addView(ui.chip(p.name, p.id == current, small = true) { if (p.id != current) onPick(p.id) }) }
        })
    }

    private fun presetName(id: String) = PresetStore(ctx).all().firstOrNull { it.id == id }?.name ?: Fmt.comfortName(id)

    private fun planSection(c: GapLayout) {
        val today = LocalDate.now()
        val plan = prefs.plan(today)
        c.addView(ui.vstack(6f).apply {
            addView(ui.sectionHead("오늘 운동"))
            if (plan != null) {
                addView(ui.list(
                    ui.row("pulse", "${hm(plan.start)} ${DayRhythmPolicy.typeLabel(plan.type)}", "20분 전부터 웜업, 마치면 쉼을 제안해요", end = "지우기", chevron = false) {
                        prefs.setPlan(null); rebuild()
                    },
                    ui.row("vibe", "웜업 미리 보기", "${WarmupGuide.totalSeconds(plan.type) / 60}분 · 일반적인 준비 동작") { shell.push(WarmupScreen(shell, plan.type)) },
                ))
            } else {
                addView(ui.body("퇴근 후 할 운동을 골라 두면 웜업과 운동 뒤 쉼을 이어 줘요.", 14f))
                addView(ui.flow().apply {
                    WORKOUT_TYPES.forEach { type ->
                        addView(ui.chip(DayRhythmPolicy.typeLabel(type), false, small = true) {
                            val start = prefs.commute.evening.plusMinutes(60)
                            pickTime(LocalTime.of(start.hour, 0)) { t -> save { prefs.setPlan(WorkoutPlan(LocalDate.now(), type, t)) } }
                        })
                    }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
            }
            addView(ui.caption("오늘 하루만 기억하고, 날이 바뀌면 지워요."), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        })
    }

    private fun pickTime(initial: LocalTime, onPick: (LocalTime) -> Unit) {
        TimePickerDialog(ctx, { _, h, m -> onPick(LocalTime.of(h, m)) }, initial.hour, initial.minute, true).show()
    }
}

class WarmupScreen(shell: PhoneShell, private val type: String) : Screen(shell) {
    override val gap = 22f
    private var index = -1
    private var left = 0
    private var stepView: TextView? = null
    private var timeView: TextView? = null
    private val tick = object : Runnable {
        override fun run() {
            val steps = WarmupGuide.steps(type)
            if (index !in steps.indices || stepView?.isAttachedToWindow != true) return
            left -= 1
            if (left <= 0) { index += 1; if (index >= steps.size) { index = -2; rebuild(); return }; left = steps[index].seconds; rebuild() }
            show(); stepView?.postDelayed(this, 1000)
        }
    }

    override fun onStateChanged() {}

    override fun fill(c: GapLayout) {
        c.addView(ui.backHeader(null, "웜업", { shell.pop() }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = -ui.dp(8); marginStart = -ui.dp(10) })
        val steps = WarmupGuide.steps(type)
        c.addView(ui.vstack(10f).apply {
            addView(ui.display("${DayRhythmPolicy.typeLabel(type)} 전\n${WarmupGuide.totalSeconds(type) / 60}분 웜업", 30f))
            addView(ui.body(WarmupGuide.NOTE))
        })
        c.addView(ui.card(22f, 22f, 10f).apply {
            stepView = ui.h2(when (index) { -2 -> "다 했어요. 천천히 시작해요."; -1 -> "준비되면 시작을 눌러요"; else -> steps[index].title }, 22f)
            timeView = ui.num("", 34f, 400)
            addView(stepView); addView(timeView)
            addView(ui.primary(if (index >= 0) "그만하기" else "시작") {
                if (index >= 0) { index = -1; rebuild() } else { index = 0; left = steps[0].seconds; rebuild(); stepView?.postDelayed(tick, 1000) }
            })
        })
        if (index >= 0) show()
        c.addView(ui.list(*steps.mapIndexed { i, s ->
            ui.row(null, s.title, s.detail, end = "${s.seconds}초", chevron = false).apply { if (i == index) alpha = 1f else if (index >= 0) alpha = 0.55f }
        }.toTypedArray()))
    }

    private fun show() {
        val steps = WarmupGuide.steps(type)
        if (index !in steps.indices) return
        stepView?.text = steps[index].title
        timeView?.text = "%d:%02d".format(left / 60, left % 60)
        stepView?.announceForAccessibility(if (left == steps[index].seconds) steps[index].title else "")
    }
}

class PlacesScreen(shell: PhoneShell) : Screen(shell) {
    override val gap = 24f
    private val prefs = LifeRestPrefs(ctx)
    private var kind = PlaceKind.PARK
    private var draft = ""

    override fun onStateChanged() {}

    override fun fill(c: GapLayout) {
        val book = prefs.placeBook
        val today = LocalDate.now()
        c.addView(ui.backHeader(null, "쉬어 갈 곳", { shell.pop() }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = -ui.dp(8); marginStart = -ui.dp(10) })
        c.addView(ui.vstack(10f).apply {
            addView(ui.display("마음 놓이는\n곳을 모아요", 30f))
            addView(ui.body("산책로·공원·카페처럼 운동 말고도 숨 돌리는 곳이요."))
        })
        c.addView(ui.list(ui.toggleRow("자주 가는 곳 기억하기", "‘여기 왔어요’를 누른 횟수만 세요. 위치는 읽지 않아요.", book.countVisits) { on ->
            prefs.placeBook = if (on) prefs.placeBook.visitsOn() else prefs.placeBook.visitsOff(); rebuild()
        }))
        val frequent = book.frequent(today)
        if (frequent.isNotEmpty()) c.addView(ui.vstack(6f).apply {
            addView(ui.sectionHead("자주 가는 곳"))
            addView(ui.list(*frequent.map { placeRow(it) }.toTypedArray()))
        })
        c.addView(ui.vstack(6f).apply {
            addView(ui.sectionHead("즐겨찾기"))
            val fav = book.favorites()
            if (fav.isEmpty()) addView(ui.body("아직 없어요. 아래에서 이름을 적어 추가해요.", 14f))
            else addView(ui.list(*fav.map { placeRow(it) }.toTypedArray()))
        })
        c.addView(ui.vstack(10f).apply {
            addView(ui.h2("곳 추가", 17f))
            addView(EditText(ctx).apply {
                setText(draft); textSize = 17f; setTextColor(t.ink); setHintTextColor(t.ink3); hint = "예: 집 앞 산책로"
                background = ui.shape(t.surface, 20f, t.line2); setPadding(ui.dp(18), 0, ui.dp(18), 0); setSingleLine()
                filters = arrayOf(InputFilter.LengthFilter(PlaceBook.NAME_MAX)); contentDescription = "곳 이름"
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(e: android.text.Editable?) { draft = e.toString() }
                })
            }, LinearLayout.LayoutParams(-1, ui.dp(56)))
            addView(ui.flow().apply {
                PlaceKind.entries.forEach { k -> addView(ui.chip(k.label, k == kind, small = true) { kind = k; rebuild() }) }
            })
            addView(ui.secondary("즐겨찾기에 추가", 48f, 15f) {
                try {
                    prefs.placeBook = prefs.placeBook.addFavorite(Place("manual:" + UUID.randomUUID(), draft, kind = kind))
                    draft = ""; rebuild()
                } catch (e: IllegalArgumentException) { report(e.message ?: "추가하지 못했어요.") }
            })
        })
        statusLine = ui.status().also { c.addView(it) }
        c.addView(ui.info("이 폰에만 저장해요. ‘자주 가는 곳 기억하기’를 끄면 횟수 기록을 바로 지워요."))
    }

    private fun placeRow(p: Place): View {
        val sub = listOfNotNull(p.kind.label, p.address.takeIf { it.isNotBlank() }, if (p.visits > 0) "${p.visits}번" else null).joinToString(" · ")
        return ui.row(null, p.name, sub) { actions(p) }
    }

    private fun actions(p: Place) = shell.showSheet { ui, dismiss ->
        ui.vstack(12f).apply {
            addView(ui.h2(p.name))
            if (prefs.placeBook.countVisits) addView(ui.secondary("여기 왔어요", 52f, 15f) {
                dismiss(); prefs.placeBook = prefs.placeBook.recordUse(p, LocalDate.now()); rebuild(); report("‘${p.name}’에 온 것을 기억했어요.")
            })
            if (!p.favorite) addView(ui.secondary("즐겨찾기에 추가", 52f, 15f) {
                dismiss(); save { prefs.placeBook = prefs.placeBook.addFavorite(p) }
            }) else addView(ui.secondary("즐겨찾기에서 빼기", 52f, 15f) {
                dismiss(); prefs.placeBook = prefs.placeBook.unfavorite(p.id); rebuild()
            })
            addView(ui.ghost("지우기", t.warn, 14f) { dismiss(); prefs.placeBook = prefs.placeBook.remove(p.id); rebuild() },
                LinearLayout.LayoutParams(-2, ui.dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
    }

    private fun save(block: () -> Unit) {
        try { block(); rebuild() } catch (e: IllegalArgumentException) { report(e.message ?: "저장하지 못했어요.") }
    }
}
