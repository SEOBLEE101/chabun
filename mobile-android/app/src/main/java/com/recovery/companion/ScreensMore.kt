package com.recovery.companion

import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout

private fun back(shell: PhoneShell) = { shell.pop() }

class HealthScreen(shell: PhoneShell) : Screen(shell) {
    override val gap = 24f
    override fun fill(c: GapLayout) {
        val prefs = PitPrefs(ctx)
        val samsung = prefs.healthSource == "samsung"
        val provider = if (samsung) "삼성 헬스" else "Health Connect"
        val state = app.healthState
        val denied = state == HealthState.NEEDS_PERMISSION && prefs.healthPermissionAsked
        val first = state == HealthState.NEEDS_PERMISSION && !prefs.healthPermissionAsked
        c.addView(ui.backHeader("연결", null, back(shell)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = -ui.dp(8); marginStart = -ui.dp(10) })
        c.addView(ui.vstack(10f).apply {
            addView(ui.display("운동 기록", 30f))
            addView(ui.body("쉼을 고를 때, 방금 마친 운동 하나만 읽어요. 연결하지 않아도 바로 쉴 수 있어요."))
        })
        c.addView(ui.card(22f, 22f, 16f).apply {
            addView(ui.hstack(14f).apply {
                addView(ui.glass("heart"))
                addView(ui.vstack(3f).apply { addView(ui.rowTitle(provider, 18f)); addView(ui.rowSub("Android의 Health Connect를 통해 읽어요")) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.pill(when { state == HealthState.GRANTED -> "허용됨"; denied -> "허용 안 됨"; first -> "연결 안 함"; state == HealthState.CHECKING -> "확인 중"; else -> "쓸 수 없음" }, if (state == HealthState.GRANTED) "ok" else "neutral"))
            })
            fun note(title: String, sub: String) = ui.sunken(18f, 16f, 14f).apply { addView(ui.rowTitle(title, 15f)); addView(ui.rowSub(sub)) }
            val readLabel = if (app.busy) "읽고 있어요" else "최근 운동 다시 읽기"
            when {
                state == HealthState.GRANTED -> {
                    val w = app.workout
                    when {
                        w != null -> addView(ui.sunken(18f, 16f, 14f).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                            addView(ui.icon("pulse", t.ink2))
                            addView(ui.vstack(3f).apply { addView(ui.rowTitle("${Fmt.workout(w.type)} ${w.minutes}분", 15f)); addView(ui.rowSub("${Fmt.time(w.end)}에 마침 · ${if (System.currentTimeMillis() - app.lastReadAt < 120_000) "방금 읽었어요" else Fmt.provider(w)}")) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(12) })
                        })
                        app.lastReadEmpty -> addView(note("가져올 운동 기록이 아직 없어요", "최근 24시간 안에 마친 운동이 없어요. 기록이 없다고 건강 상태를 뜻하지는 않아요."))
                        else -> addView(note("아직 읽은 운동이 없어요", "아래 버튼을 누를 때만 최근 운동 하나를 읽어요."))
                    }
                    addView(ui.hstack(8f).apply {
                        addView(ui.secondary(readLabel, 48f, 14f) { read() }.also { ui.setEnabledLook(it, !app.busy) }, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
                        addView(ui.secondary("권한 설정", 48f, 14f) { app.act(CompanionAction.HEALTH_SETTINGS) }, LinearLayout.LayoutParams(-2, ui.dp(48)))
                    })
                }
                denied -> {
                    addView(note("운동 읽기를 허용하지 않았어요", "Health Connect 설정에서 차분의 ‘운동’ 읽기를 허용하면 돼요."))
                    addView(ui.secondary("Health Connect 설정 열기", 48f, 14f) { app.act(CompanionAction.HEALTH_SETTINGS) }.apply { layoutParams = LinearLayout.LayoutParams(-1, ui.dp(48)) })
                    addView(ui.ghost("다시 요청하기", t.ink2, 14f) { read() }, LinearLayout.LayoutParams(-2, ui.dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                }
                first -> addView(ui.vstack(10f).apply {
                    addView(ui.primary(if (app.busy) "확인하고 있어요" else "$provider 연결하기") { read() })
                    addView(ui.caption("다음 화면은 Android의 Health Connect 권한 창이에요. ‘운동’만 허용하면 돼요.").apply { gravity = Gravity.CENTER })
                })
                state == HealthState.UPDATE_REQUIRED -> addView(note("Health Connect 설치나 업데이트가 필요해요", "Play 스토어에서 Health Connect를 설치하거나 업데이트한 뒤 다시 와 주세요."))
                state == HealthState.UNAVAILABLE -> addView(note("이 기기에서는 Health Connect를 쓸 수 없어요", "운동 기록 없이도 똑같이 쉴 수 있어요."))
                else -> addView(note("연결 상태를 확인하고 있어요", "잠시 뒤 다시 보여 드려요."))
            }
            statusLine = ui.status().also { addView(it) }
        })
        c.addView(ui.vstack(10f).apply {
            addView(ui.h2("어디서 가져올까요?", 17f))
            addView(ui.list(
                ui.radioRow("삼성 헬스만", "갤럭시 워치·삼성 헬스로 기록한 운동", samsung) { prefs.healthSource = "samsung"; rebuild() },
                ui.radioRow("Health Connect의 모든 운동 앱", "Google 피트니스 등 Health Connect에 기록하는 앱", !samsung) { prefs.healthSource = "all"; rebuild() }
            ))
        })
        c.addView(ui.vstack(10f).apply {
            addView(ui.h2("읽는 것", 17f))
            addView(ui.pills("운동 종류", "운동 시간", "마친 시각"))
            addView(ui.caption("위치, 수면, 몸무게는 읽지 않아요. 심박은 하루 쉼 › 워치 신호를 켠 경우에만 앱을 열 때 읽고 저장하지 않아요. 차로 보낼지는 그때마다 따로 물어봐요."))
        })
        spacer(c)
        if (state == HealthState.GRANTED) c.addView(ui.caption("연결을 끊으려면 Health Connect 설정에서 차분의 권한을 꺼 주세요.").apply { gravity = Gravity.CENTER })
        if (first || denied) c.addView(ui.ghost("운동 기록 없이 쓰기", t.ink2, 14f) { shell.pop(); shell.showTab(Tab.COMFORT, true) }, LinearLayout.LayoutParams(-2, ui.dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }
    private fun read() = app.act(if (PitPrefs(ctx).healthSource == "samsung") CompanionAction.READ_SAMSUNG else CompanionAction.READ_ALL)
}

class HomesScreen(shell: PhoneShell) : Screen(shell) {
    override val gap = 24f
    override fun fill(c: GapLayout) {
        c.addView(ui.backHeader("연결", null, back(shell)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = -ui.dp(8); marginStart = -ui.dp(10) })
        c.addView(ui.vstack(10f).apply {
            addView(ui.display("집도 함께\n준비할까요?", 30f))
            addView(ui.body("귀가 준비를 켤 때만, 고른 기기 한 대에 한 번 요청해요."))
        })
        fun provider(name: String, status: String, action: String?, caps: List<Pair<String, String>>, onAction: (() -> Unit)?) = ui.hstack(14f).apply {
            gravity = Gravity.TOP; setPadding(0, ui.dp(16), 0, ui.dp(16))
            background = TopLineDrawable(t.line, ctx.dpf(1).coerceAtLeast(1f))
            addView(ui.glass("house"))
            addView(ui.vstack(6f).apply {
                addView(ui.hstack(8f).apply {
                    addView(ui.rowTitle(name), LinearLayout.LayoutParams(0, -2, 1f))
                    if (action != null && onAction != null) addView(ui.secondary(action, 34f, 13f) { onAction() }.apply { setPadding(ui.dp(14), 0, ui.dp(14), 0) })
                    else addView(ui.pill("곧 지원", "muted"))
                })
                addView(ui.rowSub(status))
                addView(ui.flow(6f, 6f).apply { caps.forEach { (l, k) -> addView(ui.pill(l, k)) } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(2) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        c.addView(ui.list(
            provider("SmartThings", "Samsung 계정으로 연결해요", "연결하기", listOf("기기 읽기" to "muted", "기기에 요청 · 준비 중" to "muted")) { smartThingsSheet() },
            provider("Google Home", "Google 계정 연결을 준비하고 있어요", null, listOf("아직 연결 안 됨" to "outline"), null),
            provider("Home Assistant", "이미 쓰고 있다면 곧 연결할 수 있어요", null, listOf("아직 연결 안 됨" to "outline"), null)
        ))
        c.addView(ui.caption("Apple Home은 iPhone에서만 연결할 수 있어요."), LinearLayout.LayoutParams(-1, -2).apply { topMargin = -ui.dp(10) })
        val home = LocalArrival.session.home
        c.addView(ui.card(22f, 22f, 12f).apply {
            addView(ui.overline("귀가 준비에 쓸 기기"))
            addView(ui.hstack(14f).apply {
                addView(ui.glass("ac"))
                addView(ui.vstack(3f).apply { addView(ui.rowTitle("거실 에어컨")); addView(ui.rowSub("체험용 예시 기기 · 지금 ${Fmt.temp(home.tempC)}")) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.ghost("살펴보기", t.ink2, 14f) { shell.push(HomeDetailScreen(shell)) }.apply { setPadding(ui.dp(6), 0, ui.dp(6), 0) })
            })
            addView(ui.caption("같은 기기가 여러 앱에 보여도(Matter 기기 포함) 한 곳으로만 요청해요."))
            addView(ui.secondary("귀가 준비", 48f, 15f) { shell.push(ArrivalScreen(shell)) }.apply { layoutParams = LinearLayout.LayoutParams(-1, ui.dp(48)) })
        })
        spacer(c)
        c.addView(ui.caption("집으로는 운동 기록이나 위치를 보내지 않아요.").apply { gravity = Gravity.CENTER })
    }
    private fun smartThingsSheet() = shell.showSheet { ui, dismiss ->
        ui.vstack(14f).apply {
            addView(ui.h2("SmartThings 연결은 준비 중이에요"))
            addView(ui.body("지금은 계정을 연결하거나 기기에 요청하지 않아요. 연결이 열리면 여기에서 바로 이어 드려요. 그 전에는 체험용 예시 기기로 귀가 준비를 살펴볼 수 있어요."))
            addView(ui.primary("귀가 준비 살펴보기") { dismiss(); shell.push(ArrivalScreen(shell)) }, LinearLayout.LayoutParams(-1, ui.dp(56)).apply { topMargin = ui.dp(8) })
            addView(ui.ghost("닫기") { dismiss() }, LinearLayout.LayoutParams(-2, ui.dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
    }
}

class HomeDetailScreen(shell: PhoneShell) : Screen(shell) {
    override val gap = 22f
    private var readAt = System.currentTimeMillis()
    override fun fill(c: GapLayout) {
        val home = LocalArrival.session.home
        c.addView(ui.backHeader("집", null, back(shell)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = -ui.dp(8); marginStart = -ui.dp(10) })
        c.addView(ui.vstack(10f).apply {
            addView(ui.display("예시 집", 30f))
            addView(ui.hstack(8f).apply {
                addView(ui.live("체험용 예시 · " + if (System.currentTimeMillis() - readAt < 90_000) "방금 읽었어요" else Fmt.time(readAt) + " 읽음", idle = !home.online), LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.chip("우리 집", false, small = true, icon = "down"))
            })
        })
        fun device(icon: String, name: String, sub: String, caps: List<Pair<String, String>>, end: View? = null) =
            ui.row(icon, name, subView = ui.vstack(6f).apply {
                addView(ui.rowSub(sub))
                addView(ui.flow(6f, 6f).apply { caps.forEach { (l, k) -> addView(ui.pill(l, k)) } })
            }, chevron = false, endView = end)
        c.addView(ui.vstack(6f).apply {
            addView(ui.overline("거실"))
            addView(ui.list(
                device("ac", "에어컨", "${Fmt.temp(home.tempC)} · ${if (home.power == "on") "켜짐" else "꺼짐"} · 바람 ${Fmt.fan(home.fan)}", listOf("온도 읽기" to "ok", "온도 요청 · 준비 중" to "muted"), ui.pill("귀가 준비", "inverse")),
                device("light", "천장 조명", "꺼짐", listOf("켜짐·꺼짐 읽기" to "ok", "요청 · 준비 중" to "muted"))
            ))
        })
        c.addView(ui.vstack(6f).apply {
            addView(ui.overline("침실"))
            addView(ui.list(device("purifier", "공기청정기", "상태를 읽을 수 없어요", listOf("이 앱에서 지원 안 함" to "outline"))))
        })
        c.addView(ui.vstack(12f).apply {
            addView(ui.sectionHead("장면", "실행은 준비 중이에요", size = 17f))
            addView(ui.flow().apply { listOf("귀가", "휴식", "외출", "취침").forEach { addView(ui.chip(it, false)) } })
        })
        c.addView(ui.sunken(18f, 18f, 16f, 6f).apply {
            addView(ui.rowTitle("읽는 것과 요청하는 것은 따로예요", 14f))
            addView(ui.caption("‘읽기’는 지금 상태를 보여주기만 해요. ‘요청’은 귀가 준비를 켤 때 한 번 보내고, 실제로 바뀌었는지 다시 확인해서 알려드려요."))
        })
        statusLine = ui.status().also { c.addView(it) }
        spacer(c)
        c.addView(ui.hstack(8f).apply {
            addView(ui.secondary("목록 다시 읽기") { readAt = System.currentTimeMillis(); rebuild(); report("다시 읽었어요. 예시 기기라 실제 집은 읽지 않았어요.") }, LinearLayout.LayoutParams(0, ui.dp(56), 1f))
            addView(ui.ghost("이 앱에서 지우기", t.warn, 14f) { resetSheet() })
        })
    }
    private fun resetSheet() = shell.showSheet { ui, dismiss ->
        ui.vstack(14f).apply {
            addView(ui.h2("예시 집을 처음으로 되돌릴까요?"))
            addView(ui.body("이 폰에만 있는 체험용 기기와 예약을 지워요. 실제 집 기기에는 아무것도 보내지 않아요."))
            addView(ui.primary("되돌리기") { dismiss(); LocalArrival.reset(); shell.markAllDirty(); rebuild(); report("예시 집을 처음 상태로 되돌렸어요.") }, LinearLayout.LayoutParams(-1, ui.dp(56)).apply { topMargin = ui.dp(8) })
            addView(ui.ghost("그대로 두기") { dismiss() }, LinearLayout.LayoutParams(-2, ui.dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
    }
}

class ArrivalScreen(shell: PhoneShell) : Screen(shell) {
    override val gap = 24f
    private var lead = 10
    private var eta = 30
    private var consent = false
    private var editing = false
    private val statusName = mapOf("armed" to "예약됨", "requested" to "요청했어요", "confirmed_demo" to "바뀐 것을 확인했어요", "failed" to "요청하지 못했어요",
        "cancelled" to "취소했어요", "expired" to "시간이 지나 보내지 않았어요", "needs_review" to "손으로 바꿔서 다시 확인이 필요해요")
    override fun fill(c: GapLayout) {
        val session = LocalArrival.session
        val plan = session.plan
        val preset = plan?.snapshot ?: PresetStore(ctx).selected()
        val active = plan != null && plan.status in setOf("armed", "requested", "needs_review", "failed", "expired") && !editing
        val canUpdate = plan != null && plan.status in setOf("armed", "needs_review", "failed", "expired") && plan.requestedAt == null
        val drafting = plan == null || editing || plan.status in setOf("cancelled", "confirmed_demo")
        val target = Fmt.temp(preset.home.tempC)
        c.addView(ui.backHeader(null, "귀가 준비", back(shell)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = -ui.dp(8); marginStart = -ui.dp(10) })
        c.addView(ui.vstack(10f).apply {
            addView(ui.display("집에서도,\n익숙하게.", 30f))
            addView(ui.body("이번 귀가 한 번만, 고른 기기에 미리 요청해요."))
        })
        val requestAt = session.clock + (eta - lead) * 60_000L
        c.addView(ui.card(22f, 22f, 16f).apply {
            addView(ui.hstack(14f).apply {
                addView(ui.glass("ac"))
                addView(ui.vstack(3f).apply { addView(ui.rowTitle("거실 에어컨")); addView(ui.rowSub("예시 기기 · 지금 ${Fmt.temp(session.home.tempC)}")) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.vstack(0f).apply { gravity = Gravity.END; addView(ui.caption("준비할 온도")); addView(ui.num(target, 26f, 400)) })
            })
            addView(View(ctx).apply { setBackgroundColor(t.line) }, LinearLayout.LayoutParams(-1, ctx.dpi(1).coerceAtLeast(1)))
            if (drafting) {
                fun chips(title: String, values: List<Int>, current: Int, set: (Int) -> Unit) = ui.vstack(10f).apply {
                    addView(ui.rowTitle(title, 15f, 500))
                    addView(ui.hstack(6f).apply { values.forEach { v -> addView(ui.chip("${v}분", v == current) { set(v); consent = false; rebuild() }.apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, ui.dp(40), 1f)) } })
                }
                addView(chips("도착까지 얼마나 남았나요?", listOf(20, 30, 45, 60), eta) { eta = it })
                addView(chips("도착 몇 분 전에 준비할까요?", listOf(5, 10, 15, 20), lead) { lead = it })
                addView(ui.caption(if (eta > lead) "도착까지 약 ${eta}분 · ${Fmt.time(requestAt)}에 한 번 요청해요" else "준비 시간은 도착까지 남은 시간보다 짧아야 해요"))
            } else if (plan != null) {
                addView(ui.hstack(8f).apply {
                    addView(ui.pill(statusName[plan.status] ?: plan.status, if (plan.status == "confirmed_demo") "ok" else if (plan.status in setOf("failed", "needs_review", "expired")) "warn" else "neutral"))
                })
                addView(ui.caption("${Fmt.time(plan.executeAt)}에 요청 · 도착 ${Fmt.time(plan.arrivalAt)} · 도착 ${plan.leadMinutes}분 전"))
            }
        })
        // 3단 타임라인
        val st = plan?.status
        val (s1, s2, s3) = when {
            drafting -> Triple(1, 0, 0)
            st == "armed" -> Triple(2, 1, 0)
            st == "requested" -> Triple(2, 2, 1)
            st == "confirmed_demo" -> Triple(2, 2, 2)
            else -> Triple(2, 1, 0)
        }
        val execText = if (drafting) Fmt.time(requestAt) else Fmt.time(plan!!.executeAt)
        val reqSub = when (st) {
            null -> "늦어지면 보내지 않고 알려드려요"
            "expired" -> "준비 시간을 지나 보내지 않았어요"
            "failed" -> "기기와 연결되지 않았어요. 저절로 다시 보내지 않아요"
            "needs_review" -> "손으로 바꾼 설정을 덮어쓰지 않아요"
            "requested", "confirmed_demo" -> "한 번 요청했어요"
            else -> if (drafting) "늦어지면 보내지 않고 알려드려요" else "그때 한 번만 요청해요"
        }
        c.addView(ui.vstack(16f).apply {
            setPadding(ui.dp(2), 0, ui.dp(2), 0)
            addView(ui.step(s1, if (drafting) "예약하면" else "예약했어요", "기기와 온도를 지금 값 그대로 기억해요. 나중에 쉼을 바꿔도 이 예약은 그대로예요."))
            addView(ui.step(s2, "$execText · 요청", reqSub, muted = s2 == 0))
            addView(ui.step(s3, if (st == "confirmed_demo") "바뀐 것을 확인했어요" else "실제로 바뀌었는지 확인", if (st == "confirmed_demo") "예시 기기에서 맞는 것을 확인했어요" else "요청과 확인을 따로 보여줘요", muted = s3 == 0))
        })
        if (drafting) c.addView(ui.checkRow("이번 귀가 한 번, 거실 에어컨을 ${target}로 요청할게요", consent) { consent = it; updatePrimary() })
        statusLine = ui.status().also { c.addView(it) }
        spacer(c)
        c.addView(ui.vstack(6f).apply {
            when {
                drafting -> {
                    primaryButton = ui.primary(if (editing && canUpdate) "이번 예약 시간 바꾸기" else "이번 한 번 예약하기") { arm(canUpdate && editing) }.also { addView(it) }
                    addView(ui.caption("시간이나 온도를 바꾸면 다시 확인해요.").apply { gravity = Gravity.CENTER })
                    if (editing) addView(ui.ghost("바꾸지 않기") { editing = false; rebuild() }, LinearLayout.LayoutParams(-2, ui.dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                    updatePrimary()
                }
                st == "requested" -> addView(ui.primary("실제로 바뀌었는지 확인") { runCatching { session.confirmObserved() }.onFailure { report(it.message ?: "확인하지 못했어요.") }; rebuild() })
                else -> {
                    if (canUpdate) addView(ui.secondary("시간 다시 정하기") { editing = true; consent = false; rebuild() }.apply { layoutParams = LinearLayout.LayoutParams(-1, ui.dp(56)) })
                }
            }
            if (active && st in setOf("armed", "requested", "needs_review")) addView(ui.ghost("이번 예약 취소", t.warn, 14f) { session.cancel(); report("예약을 취소했어요. 이미 바뀐 설정을 되돌리지는 않아요."); rebuild() }, LinearLayout.LayoutParams(-2, ui.dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            if (plan != null && !drafting) addView(ui.caption("취소는 이미 바뀐 설정을 되돌리거나 끄는 것이 아니에요.").apply { gravity = Gravity.CENTER })
        })
    }
    private var primaryButton: View? = null
    private fun updatePrimary() { primaryButton?.let { ui.setEnabledLook(it, consent && eta > lead) } }
    private fun arm(update: Boolean) {
        try {
            require(consent) { "이번 예약 동의를 직접 선택해 주세요." }
            val session = LocalArrival.session
            if (update) session.update(eta, lead) else session.arm(PresetStore(ctx).selected(), eta, lead)
            editing = false; consent = false
            PitHaptics.confirm(content)
            report("예약했어요. ${Fmt.time(session.plan!!.executeAt)}에 한 번 요청해요.")
            rebuild()
        } catch (e: Exception) { report(e.message ?: "시간을 확인해 주세요.") }
    }
}

class AppearanceScreen(shell: PhoneShell) : Screen(shell) {
    override val gap = 24f
    override fun fill(c: GapLayout) {
        val prefs = AppearancePreferences(ctx)
        c.addView(ui.backHeader(null, "화면 분위기", back(shell)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = -ui.dp(8); marginStart = -ui.dp(10) })
        c.addView(ui.vstack(10f).apply {
            addView(ui.display("어떤 화면이\n편한가요?", 30f))
            addView(ui.body("차와 같은 분위기로 이어가거나, 이 폰만 따로 정할 수 있어요."))
        })
        c.addView(ui.hstack(10f).apply {
            listOf(Tokens.PEARL, Tokens.GRAPHITE, Tokens.UMBER).forEach { k ->
                addView(ui.vstack(0f).apply {
                    background = ui.shape(k.bg, 22f, if (k.dark) k.line2 else k.line)
                    setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
                    addView(ui.text(android.text.SpannableStringBuilder("차분.").apply { setSpan(android.text.style.ForegroundColorSpan(k.accent), length - 1, length, 0) }, 13f, 700, k.ink, 1.2f).apply { typeface = PitType.num(700) })
                    addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
                    addView(View(ctx).apply { background = ui.shape(k.ink, 4f) }, LinearLayout.LayoutParams(-1, ui.dp(8)).apply { marginEnd = ui.dp(22) })
                    addView(View(ctx).apply { background = ui.shape(k.line2, 3f) }, LinearLayout.LayoutParams(-1, ui.dp(6)).apply { marginEnd = ui.dp(40); topMargin = ui.dp(6) })
                    addView(View(ctx).apply { background = ui.shape(k.primary, 11f) }, LinearLayout.LayoutParams(-1, ui.dp(22)).apply { topMargin = ui.dp(12) })
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(0, ui.dp(132), 1f))
            }
        })
        val fresh = prefs.followIsFresh()
        val m = prefs.mode
        c.addView(ui.list(
            ui.radioRow("차량 앱 따르기", null, m == AppearancePreferences.Mode.FOLLOW_VEHICLE, subView = ui.live(prefs.statusText(), idle = !fresh)) { choose(AppearancePreferences.Mode.FOLLOW_VEHICLE) }.apply {
                ((getChildAt(1) as LinearLayout).getChildAt(0) as android.widget.TextView).append(android.text.SpannableString("  기본").apply { setSpan(android.text.style.ForegroundColorSpan(t.ink3), 0, length, 0); setSpan(android.text.style.AbsoluteSizeSpan(13, true), 0, length, 0) })
            },
            ui.radioRow("이 폰만 Pearl", "밝고 부드럽게", m == AppearancePreferences.Mode.PAPER) { choose(AppearancePreferences.Mode.PAPER) },
            ui.radioRow("이 폰만 Graphite", "어둡고 차분하게", m == AppearancePreferences.Mode.GRAPHITE) { choose(AppearancePreferences.Mode.GRAPHITE) },
            ui.radioRow("이 폰만 Umber", "따뜻하고 깊게", m == AppearancePreferences.Mode.UMBER) { choose(AppearancePreferences.Mode.UMBER) }
        ))
        if (m == AppearancePreferences.Mode.FOLLOW_VEHICLE) {
            val sync = app.appearanceSync
            if (sync.vehicles.size > 1) c.addView(ui.vstack(6f).apply {
                addView(ui.h2("따를 차량 앱", 17f))
                addView(ui.list(*sync.vehicles.map { r ->
                    ui.radioRow("차량 앱 · 끝자리 " + r.deviceId.takeLast(6), if (sync.reportIsFresh(r)) "최근 소식이 있어요" else "소식이 지났어요 · 다시 확인이 필요해요", prefs.selectedVehicleId == r.deviceId) {
                        prefs.selectVehicle(r.deviceId); sync.refresh(); rebuild()
                    }
                }.toTypedArray()))
            })
            c.addView(ui.hstack(8f).apply {
                addView(ui.secondary("차량 앱 다시 확인", 44f, 14f) { sync.refresh() })
                if (!sync.connectionAvailable()) addView(ui.secondary("취향 함께 쓰기 연결", 44f, 14f) { app.openSyncedPresets() })
            })
        }
        c.addView(ui.info("폰 화면만 바뀌어요. 차 화면과 실내 조명은 그대로예요. 차와 연결이 끊기면 마지막 분위기를 그대로 둬요."))
    }
    override fun onStateChanged() { rebuild() }
    private fun choose(mode: AppearancePreferences.Mode) {
        val prefs = AppearancePreferences(ctx)
        if (prefs.mode == mode) return
        prefs.selectMode(mode)
        rebuild()
        app.appearanceChanged()
    }
}

class PrivacyScreen(shell: PhoneShell) : Screen(shell) {
    override val gap = 24f
    override fun fill(c: GapLayout) {
        val store = PresetStore(ctx)
        val customs = store.all().count { it.kind == "custom" }
        c.addView(ui.backHeader(null, "개인정보와 데이터", back(shell)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = -ui.dp(8); marginStart = -ui.dp(10) })
        c.addView(ui.vstack(10f).apply {
            addView(ui.display("내 기록은\n필요한 만큼만", 30f))
            addView(ui.body("무엇이 어디에 있는지, 언제 지워지는지 한눈에 보여드려요."))
        })
        val sent = app.lastSent
        val revoking = app.pairing?.revocationPending == true
        c.addView(ui.card(22f, 22f, 12f).apply {
            fun head(pill: String?, kind: String = "ok") = ui.hstack().apply {
                addView(ui.overline("차에 보낸 것"), LinearLayout.LayoutParams(0, -2, 1f))
                if (pill != null) addView(ui.pill(pill, kind))
            }
            val desc = sent?.let { "${Fmt.workout(it.workoutType)} ${it.minutes}분 · ${it.presetName}" }
            when {
                revoking -> {
                    addView(head("확인 필요", "warn")); addView(ui.rowTitle(desc ?: "차에 보낸 요약"))
                    addView(ui.rowSub("새 연결과 보내기는 확인될 때까지 잠시 멈춰요"))
                    addView(ui.sunken(14f, 14f, 12f).apply { addView(ui.rowSub("이 폰에서는 지웠어요. 서버에서 지워졌는지는 아직 확인하지 못했어요.", t.ink)) })
                    addView(ui.secondary(if (app.busy) "확인하고 있어요" else "삭제 다시 확인", 44f, 14f) { app.act(CompanionAction.REVOKE) })
                }
                app.pairing == null && app.deletionConfirmedAt > 0 -> {
                    addView(head("지웠어요")); addView(ui.rowTitle("차에 보낸 요약")); addView(ui.rowSub("서버에서 지운 것을 확인했어요"))
                }
                sent != null && app.pairing != null -> {
                    addView(head("${Fmt.time(sent.expiresAt)}까지", "ok")); addView(ui.rowTitle(desc!!)); addView(ui.rowSub("그 뒤에는 저절로 지워져요"))
                    addView(ui.secondary("지금 지우기", 44f, 14f) { deleteSummarySheet(shell) })
                }
                app.pairing != null -> {
                    addView(head(null)); addView(ui.rowTitle("이 폰에서 보낸 요약은 아직 없어요"))
                    addView(ui.rowSub("연결을 끊으면 차에 남은 요약도 함께 지워요."))
                    addView(ui.secondary("연결 끊고 지우기", 44f, 14f) { deleteSummarySheet(shell) })
                }
                else -> { addView(head(null)); addView(ui.rowTitle("아직 차에 보낸 것이 없어요")); addView(ui.rowSub("운동 기록을 보내면 여기에서 확인하고 지울 수 있어요.")) }
            }
            statusLine = ui.status().also { addView(it) }
        })
        c.addView(ui.list(
            ui.row("phone", "이 폰에 있는 것", "나의 쉼 ${store.all().size}개 · 화면 분위기 · 차 안 설정", chevron = false,
                endView = ui.ghost("지우기", t.ink2, 13f) { clearSheet(customs) }.apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) }),
            ui.row("pulse", "운동 기록 읽기", (if (PitPrefs(ctx).healthSource == "samsung") "삼성 헬스" else "Health Connect") + (if (ChabunDayPrefs(ctx).readsExerciseOnOpen()) " · 누를 때, 그리고 운동 계획·심박 제안을 켠 동안 앱을 열 때 운동 시간을 읽어요(저장 안 함)" else " · 누를 때만 최근 운동 하나를 읽어요"), chevron = false,
                endView = ui.ghost("권한 설정", t.ink2, 13f) { app.act(CompanionAction.HEALTH_SETTINGS) }.apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) }),
            ui.row("today", "하루 쉼", "출퇴근 시각 · 오늘 운동 · 쉬어 갈 곳 · 이 폰에만 있어요" + (if (ChabunDayPrefs(ctx).heartNudge) " · 심박은 앱을 열 때만 읽고 저장하지 않아요" else ""), chevron = false,
                endView = ui.ghost("지우기", t.ink2, 13f) { ChabunDayPrefs(ctx).clearAll(); ChabunDayAlarm.reschedule(ctx); app.setHeartNudge(false); report("하루 쉼 설정과 장소를 지웠어요.") }.apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) })
        ))
        c.addView(ui.vstack(12f).apply {
            addView(ui.h2("차에 보내는 건 이것뿐이에요", 17f))
            addView(ui.pills("운동 종류", "운동 시간", "마친 시각", "고른 쉼", "쉬어 갈 곳(보낼 때만)"))
            addView(ui.caption("운동 요약은 차에 30분까지만 남고, 운동을 마친 지 2시간이 지나면 지워져요. 쉬어 갈 곳은 '차로 보내기'를 누를 때 그 한 곳의 이름·주소·좌표만 보내고, 방문 횟수는 보내지 않아요. 지금 위치(GPS), 이름, 계정은 읽지도 보내지도 않아요. 심박은 제안을 켠 경우 앱을 열 때만 읽고 어디로도 보내지 않아요."))
        })
        c.addView(ui.list(
            ui.row(null, "지우기와 끊기의 차이", "어디까지 지워지는지 한 번에 보기", titleWeight = 500) { scopeSheet() },
            ui.row(null, "취향 함께 쓰기 연결 관리", "끊어도 이 폰의 쉼은 그대로예요", titleWeight = 500) { app.openSyncedPresets() }
        ))
        spacer(c)
        c.addView(ui.caption("차분은 건강 상태를 진단하지 않아요.").apply { gravity = Gravity.CENTER })
    }
    private fun clearSheet(customs: Int) = shell.showSheet { ui, dismiss ->
        ui.vstack(10f).apply {
            addView(ui.h2("이 폰에서 무엇을 지울까요?"))
            addView(ui.caption("고른 것만 바로 지워요. 차와 서버에 보낸 요약은 위의 ‘차에 보낸 것’에서 지워요."))
            val rows = mutableListOf<View>(ui.row("pulse", "운동 미리보기", if (app.workout != null) "지금 고른 운동 하나" else "지울 미리보기가 없어요") {
                dismiss(); app.act(CompanionAction.CLEAR); report("이 폰의 운동 미리보기를 지웠어요. 이미 보낸 요약은 그대로예요.")
            })
            if (customs > 0) rows += ui.row("sliders", "직접 만든 쉼 ${customs}개", "기본 쉼 3개는 남아요") {
                dismiss(); val store = PresetStore(ctx); store.all().filter { it.kind == "custom" }.forEach { store.delete(it.id) }
                shell.markAllDirty(); app.selectPreset(PresetStore(ctx).selectedId); report("직접 만든 쉼을 지웠어요.")
            }
            rows += ui.row("seat", "차 안 설정", "모든 쉼의 시트·에어컨·조명 선호") { dismiss(); CabinStore(ctx).clearAll(); shell.markAllDirty(); report("차 안 설정을 지웠어요.") }
            addView(ui.list(*rows.toTypedArray()))
        }
    }
    private fun scopeSheet() = shell.showSheet { ui, dismiss ->
        ui.vstack(12f).apply {
            addView(ui.h2("지우기와 끊기의 차이"))
            listOf(
                "운동 미리보기 지우기" to "이 폰에서만 비워요. 보낸 요약과 건강 앱의 원본은 그대로예요.",
                "차에 보낸 요약 지우기" to "서버에 삭제를 요청하고, 서버가 지웠다고 답해야 ‘지웠어요’로 보여요. 꺼져 있는 차 화면은 다음 연결 확인 때 비워져요.",
                "차량 연결 끊기" to "요약 삭제와 함께 연결도 끝나요. 쉬기 자체를 멈추지는 않아요.",
                "취향 함께 쓰기 끊기" to "함께 보던 쉼 목록 연결만 끝나요. 이 폰의 쉼은 남아요.",
                "Health Connect 권한 끄기" to "앞으로 운동을 읽지 않아요. 원본 기록은 지우지 않아요."
            ).forEach { (title, body) -> addView(ui.vstack(2f).apply { addView(ui.rowTitle(title, 15f)); addView(ui.rowSub(body)) }) }
            addView(ui.primary("닫기") { dismiss() }, LinearLayout.LayoutParams(-1, ui.dp(56)).apply { topMargin = ui.dp(6) })
        }
    }
}

/** 디버그 빌드에서만: 나 › 앱 정보를 7번 누르면 열린다. */
class DeveloperScreen(shell: PhoneShell) : Screen(shell) {
    override val gap = 22f
    private var relayField: EditText? = null
    // 주소를 입력하는 동안에는 주기적인 다시 그리기가 입력을 저장값으로 되돌리지 않게 한다
    override fun onStateChanged() { if (relayField?.hasFocus() != true) rebuild() }
    override fun fill(c: GapLayout) {
        val session = LocalArrival.session
        c.addView(ui.backHeader("나", "개발자 설정", back(shell)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = -ui.dp(8); marginStart = -ui.dp(10) })
        c.addView(ui.caption("디버그 빌드에서만 보여요. 실제 사용 화면에는 나오지 않아요."))
        c.addView(ui.card(20f, 20f, 10f).apply {
            addView(ui.rowTitle("운동 요약 중계 주소", 15f))
            val field = EditText(ctx).apply {
                setText(app.relayBase()); textSize = 15f; setTextColor(t.ink); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                background = ui.shape(t.surface, 20f, t.line2); setPadding(ui.dp(16), 0, ui.dp(16), 0); isEnabled = app.pairing == null
                contentDescription = "운동 요약 중계 주소"
            }
            relayField = field
            addView(field, LinearLayout.LayoutParams(-1, ui.dp(56)))
            addView(ui.caption(if (app.pairing == null) "취향 함께 쓰기는 같은 호스트의 별도 연결(9447)을 써요." else "차량 연결이 있는 동안에는 바꿀 수 없어요."))
            addView(ui.secondary("저장", 44f, 14f) { app.setRelayBase(field.text.toString().trim()); report("주소를 저장했어요.") }.also { ui.setEnabledLook(it, app.pairing == null) })
        })
        c.addView(ui.list(
            ui.row("spark", "체험용 합성 운동 고르기", "건강 기록을 읽지 않는 걷기 20분") { app.act(CompanionAction.DEMO); report("합성 운동을 골랐어요. 운동 기록 함께 전하기에서 확인할 수 있어요.") },
            ui.row("today", "인트로 다시 보기", "앱 시작 장면") { shell.pop(); shell.host.postDelayed({ shell.playIntro() }, 600) },
            ui.row("pulse", "합성 심박 넣기", "심박 쉼 제안 확인용 · Health Connect에 시험 기록을 써요") { app.debugWriteSyntheticHeart() }
        ))
        c.addView(ui.card(20f, 20f, 10f).apply {
            addView(ui.rowTitle("귀가 준비 시연 시계", 15f))
            val plan = session.plan
            addView(ui.caption("예시 기기 ${Fmt.temp(session.home.tempC)} · ${if (session.home.online) "온라인" else "오프라인"} · 예약 ${plan?.status ?: "없음"} · 시계 ${Fmt.time(session.clock)}"))
            addView(ui.flow(8f, 8f).apply {
                if (plan != null) {
                    addView(ui.chip("요청 시각으로", false, small = true) { runCatching { session.advanceToExecution() }; rebuild() })
                    addView(ui.chip("+5분", false, small = true) { runCatching { session.advance(300_000) }; rebuild() })
                }
                addView(ui.chip("손으로 바꾼 것처럼", false, small = true) { runCatching { session.manualOverride(if (session.home.tempC == 26.0) 25.0 else 26.0) }; rebuild() })
                addView(ui.chip(if (session.home.online) "오프라인으로" else "온라인으로", false, small = true) { session.setOnline(!session.home.online); rebuild() })
            })
        })
        statusLine = ui.status().also { c.addView(it) }
    }
}
