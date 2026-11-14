package com.recovery.companion

import android.app.TimePickerDialog
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
    val plan = prefs.plan(now)
    val commute = prefs.commute
    val lastEnd = listOfNotNull(app.workout?.end, app.plannedWorkoutEnd).maxOrNull()?.let { LocalDateTime.ofInstant(it, ZoneId.systemDefault()) }
    val moment = runCatching { DayRhythmPolicy.momentAt(now, commute, plan, lastEnd) }.getOrDefault(DayMoment.NONE)
    if (app.heartNudgeShown) return ui.sunken(24f, 20f, 18f, 8f).apply {
        addView(ui.overline("지금"))
        addView(ui.rowTitle("잠깐 쉬어 갈까요?", 17f))
        addView(ui.body(HeartNudgePolicy.MESSAGE, 14f))
        addView(ui.hstack(8f).apply {
            addView(ui.secondary("쉴 준비", 44f, 14f) {
                val store = PresetStore(ctx); store.beginRest(); app.selectPreset("quiet_rest"); shell.push(ShareScreen(shell))
            })
            addView(ui.ghost("오늘은 괜찮아요", screen.t.ink3, 14f) { app.dismissHeartNudge() })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        addView(ui.caption(HeartNudgePolicy.NOTE, size = 12f))
    }
    if (moment == DayMoment.NONE || prefs.dismissed(moment, now.toLocalDate()) || prefs.dismissed(moment, now.toLocalDate().minusDays(1)) && now.toLocalTime().isBefore(java.time.LocalTime.of(2, 0))) return null
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
            addView(ui.ghost("오늘은 닫기", screen.t.ink3, 14f) { prefs.dismiss(moment, LocalDate.now()); screen.rebuild() })
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
        c.addView(ui.vstack(6f).apply {
            addView(ui.sectionHead("워치 신호", "선택"))
            addView(ui.list(ui.toggleRow("심박이 높게 이어질 때 쉼 제안", "앱을 열 때만 최근 심박을 읽어요(운동 중 심박은 빼요). 기록은 저장하지 않아요.", prefs.heartNudge) { on -> app.setHeartNudge(on) }))
            addView(ui.caption(HeartNudgePolicy.NOTE), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        })
        statusLine = ui.status().also { c.addView(it) }
        c.addView(ui.info("설정은 이 폰에만 저장해요. 서버와 차로 보내지 않아요."))
    }

    private fun placesSub(): String {
        val b = prefs.placeBook
        val fav = b.favorites().size
        return if (fav == 0) "즐겨찾는 산책로·카페를 모아 둬요" else "즐겨찾기 ${fav}곳" + if (b.countVisits) " · 자주 가는 곳 기억 중" else ""
    }

    private fun save(block: () -> Unit) {
        try { block(); LifeRestAlarm.reschedule(ctx); rebuild() } catch (e: IllegalArgumentException) { report(e.message ?: "저장하지 못했어요.") }
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
                rows += ui.toggleRow("퇴근 무렵 알림", "퇴근 15분 전쯤 한 번 알려요. 10분쯤 늦을 수 있어요.", prefs.eveningReminder && LifeRestAlarm.canNotify(ctx)) { on -> app.setEveningReminder(on) }
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
        val plan = prefs.plan(LocalDateTime.now())
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
                            val evening = prefs.commute.evening
                            val suggested = if (evening.hour >= 23) LocalTime.of(23, 0) else LocalTime.of(evening.plusMinutes(60).hour, 0)
                            val soon = LocalTime.now().plusMinutes(30).withSecond(0).withNano(0)
                            pickTime(if (suggested.isBefore(LocalTime.now()) && soon.isAfter(LocalTime.now())) soon else suggested) { t -> save { prefs.setPlan(WorkoutPlan(LocalDate.now(), type, t)) } }
                        })
                    }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
            }
            addView(ui.caption("오늘 하루만 기억하고, 날이 바뀌면 지워요. 운동 읽기를 허용했다면 앱을 열 때 운동이 끝났는지 확인해요."), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
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
            if (left <= 0) {
                index += 1
                if (index >= steps.size) { index = -2; rebuild(); return }
                left = steps[index].seconds; rebuild() // fill()이 show()를 한 번 부른다
            } else show()
            stepView?.postDelayed(this, 1000)
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
                stepView?.removeCallbacks(tick); if (index >= 0) { index = -1; rebuild() } else { index = 0; left = steps[0].seconds; rebuild(); stepView?.postDelayed(tick, 1000) }
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
    private var query = ""
    private var results: List<Place> = emptyList()
    private var searching = false

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
            addView(ui.h2("장소 찾기", 17f))
            if (!PlaceSync.connected(ctx)) {
                addView(ui.body("연결 › 취향 함께 쓰기를 이으면 동네 이름으로 장소를 찾고 차로 보낼 수 있어요.", 14f))
            } else {
                addView(field(query, "예: 성수 카페, 한강 산책로", "찾을 말") { query = it }, LinearLayout.LayoutParams(-1, ui.dp(56)))
                addView(ui.secondary(if (searching) "찾고 있어요" else "${kind.label}로 찾기", 48f, 15f) { search() })
                addView(ui.caption("적은 말과 종류만 서버로 보내요. 지금 위치는 읽지도 보내지도 않아요."))
                if (results.isNotEmpty()) addView(ui.list(*results.map { placeRow(it) }.toTypedArray()))
            }
        })
        c.addView(ui.vstack(10f).apply {
            addView(ui.h2("곳 추가", 17f))
            addView(field(draft, "예: 집 앞 산책로", "곳 이름") { draft = it }, LinearLayout.LayoutParams(-1, ui.dp(56)))
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

    private fun field(value: String, hintText: String, label: String, onChange: (String) -> Unit) = EditText(ctx).apply {
        setText(value); textSize = 17f; setTextColor(t.ink); setHintTextColor(t.ink3); hint = hintText
        background = ui.shape(t.surface, 20f, t.line2); setPadding(ui.dp(18), 0, ui.dp(18), 0); setSingleLine()
        filters = arrayOf(InputFilter.LengthFilter(PlaceBook.NAME_MAX)); contentDescription = label
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { onChange(e.toString()) }
        })
    }

    private fun search() {
        if (searching) return
        searching = true; rebuild()
        ctx.lifecycleScope.launch {
            try { results = PlaceSync.search(ctx, query, kind); if (results.isEmpty()) report("찾은 곳이 없어요. 다른 말로 찾아 주세요.") }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: IllegalArgumentException) { report(e.message ?: "찾지 못했어요.") }
            catch (_: Exception) { report("서버에 닿지 못했어요. 연결 상태를 확인해 주세요.") }
            searching = false; rebuild()
        }
    }

    private fun sendToCar(p: Place) {
        ctx.lifecycleScope.launch {
            try {
                PlaceSync.sendToCar(ctx, p)
                prefs.placeBook = prefs.placeBook.recordUse(p, LocalDate.now()); rebuild()
                report("‘${p.name}’을 차로 보냈어요. 길안내는 차 화면에서 직접 시작해요.")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: IllegalArgumentException) { report(e.message ?: "차로 보내지 못했어요.") }
            catch (_: Exception) { report("서버에 닿지 못했어요. 연결 상태를 확인해 주세요.") }
        }
    }

    private fun placeRow(p: Place): View {
        val sub = listOfNotNull(p.kind.label, p.address.takeIf { it.isNotBlank() }, if (p.visits > 0) "${p.visits}번" else null).joinToString(" · ")
        return ui.row(null, p.name, sub) { actions(p) }
    }

    private fun actions(p: Place) = shell.showSheet { ui, dismiss ->
        ui.vstack(12f).apply {
            addView(ui.h2(p.name))
            if (PlaceSync.connected(ctx)) addView(ui.secondary("차로 보내기", 52f, 15f) { dismiss(); sendToCar(p) })
            if (prefs.placeBook.countVisits) addView(ui.secondary("여기 왔어요", 52f, 15f) {
                dismiss(); prefs.placeBook = prefs.placeBook.recordUse(p, LocalDate.now()); rebuild(); report("‘${p.name}’에 온 것을 기억했어요.")
            })
            if (!p.favorite) addView(ui.secondary("즐겨찾기에 추가", 52f, 15f) {
                dismiss(); save { prefs.placeBook = prefs.placeBook.addFavorite(p) }
            }) else addView(ui.secondary("즐겨찾기에서 빼기", 52f, 15f) {
                dismiss(); prefs.placeBook = prefs.placeBook.unfavorite(p.id); rebuild()
            })
            if (prefs.placeBook.find(p.id) != null) addView(ui.ghost("지우기", t.warn, 14f) { dismiss(); prefs.placeBook = prefs.placeBook.remove(p.id); rebuild() },
                LinearLayout.LayoutParams(-2, ui.dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
    }

    private fun save(block: () -> Unit) {
        try { block(); rebuild() } catch (e: IllegalArgumentException) { report(e.message ?: "저장하지 못했어요.") }
    }
}
