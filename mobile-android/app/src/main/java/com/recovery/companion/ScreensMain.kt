package com.recovery.companion

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 화면에 쓰는 말(4절 용어)로만 바꾼다. */
object Fmt {
    private val clock = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREA).withZone(ZoneId.systemDefault())
    private val hm = DateTimeFormatter.ofPattern("H:mm", Locale.KOREA).withZone(ZoneId.systemDefault())
    fun time(i: Instant): String = clock.format(i)
    fun time(ms: Long): String = clock.format(Instant.ofEpochMilli(ms))
    fun hm(i: Instant): String = hm.format(i)
    fun temp(v: Double?) = v?.let { String.format(Locale.US, "%.1f°", it) } ?: "그대로"
    fun fan(v: String) = mapOf("keep" to "그대로", "auto" to "자동", "low" to "약하게", "medium" to "중간", "high" to "세게")[v] ?: v
    fun seat(v: String) = if (v == "manual_comfort") "직접 편하게" else "그대로"
    fun power(v: String) = if (v == "on") "켜기" else "그대로"
    fun presetSub(p: Preset) = "차 ${temp(p.vehicle.tempC)} · 바람 ${fan(p.vehicle.fan)} · 시트 ${seat(p.vehicle.seat)}"
    fun comfortIcon(c: String) = when (c) { "cool_down" -> "cool"; "gentle_refresh" -> "gentle"; else -> "quiet" }
    fun comfortName(c: String) = when (c) { "cool_down" -> "산뜻하게 식히기"; "gentle_refresh" -> "부드럽게 기분 전환"; else -> "조용히 쉬기" }
    fun workout(type: String) = when (type) { "walking" -> "걷기"; "running" -> "러닝"; "cycling" -> "자전거"; "strength" -> "근력 운동"; else -> "운동" }
    fun provider(w: WorkoutRecord) = when {
        w.source == "demo" -> "체험용 합성 기록"
        w.providerPackage == "com.sec.android.app.shealth" -> "삼성 헬스"
        w.providerPackage?.contains("fitness") == true -> "Google 피트니스"
        else -> "Health Connect"
    }
    fun restLine(context: Context) = "3분 · " + (if (PitPrefs(context).restStyle == "free") "자유롭게 쉬기" else "안내에 맞춰 쉬기") + " · 소리 끔"
    fun minutesSince(i: Instant) = java.time.Duration.between(i, Instant.now()).toMinutes().coerceAtLeast(0)
    /** 연결 상태(4절): 이어져 있어요 · 차의 승인을 기다려요 · 확인이 필요해요 · 연결 안 함 */
    fun connection(app: PitApp): Pair<String, Boolean> = when {
        app.pairing?.revocationPending == true -> "삭제를 다시 확인해 주세요" to true
        app.pairingConfirmed -> ("이어져 있어요 · " + checked(app.lastPairingCheckAt)) to false
        app.pairing != null && app.confirmationCode != null -> "차의 승인을 기다려요" to true
        app.pairing != null -> "확인이 필요해요" to true
        else -> "연결 안 함" to true
    }
    fun checked(ms: Long): String = if (ms == 0L || System.currentTimeMillis() - ms < 90_000) "방금 확인" else hm(Instant.ofEpochMilli(ms)) + " 확인"
    fun syncedState(context: Context): String {
        val s = context.getSharedPreferences("pitin_preset_sync_session", Context.MODE_PRIVATE)
        return when {
            s.getLong("revocationRequestedAt", 0) > 0 -> "확인 필요"
            s.getString("token", null) == null -> "연결 안 함"
            s.getBoolean("approved", false) -> "이어짐"
            else -> "승인 대기"
        }
    }
    /** 오늘 화면 머리말 로고의 표시. 인트로 로고가 이 자리로 날아와 내려앉는다. */
    const val WORDMARK_TAG = "pit-wordmark"
    fun wordmark(ui: PitUi, size: Float = 22f) = ui.text(SpannableStringBuilder("차분.").apply {
        setSpan(ForegroundColorSpan(ui.t.accent), length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }, size, 700, ui.t.ink, 1.2f, -0.02f).apply { typeface = PitType.num(700); contentDescription = "차분" }
}

fun roundClip(v: View, radiusPx: Float) {
    v.outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, radiusPx) } }
    v.clipToOutline = true
}

/** Pearl 진주빛 유리 막: 가장자리를 바탕으로 녹이고 8초 박자로 숨 쉰다. */
fun pearlGlass(ui: PitUi) = MaskedImageView(ui.context, R.drawable.pit_pearl_glass, radiusX = 0.62f, radiusY = 0.6f, solidStop = 0.7f, heightRatio = 582f / 900f, breathe = true)

/** 분위기에 따라 바뀌는 숨 쉬는 그래픽 (Pearl 막 · Graphite 경로와 점 · Umber 등고선) */
fun moodArt(ui: PitUi, height: Float, pearlWidth: Float, umberWidth: Float, graphiteScale: Float = 1f): FrameLayout {
    val ctx = ui.context
    return FrameLayout(ctx).apply {
        clipChildren = true; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        when (ui.t.mood) {
            Mood.PEARL -> addView(pearlGlass(ui), FrameLayout.LayoutParams(ui.dp(pearlWidth), -2, Gravity.CENTER))
            Mood.GRAPHITE -> addView(GraphiteArtView(ctx, ui.t), FrameLayout.LayoutParams(ui.dp(320 * graphiteScale), -2, Gravity.CENTER))
            Mood.UMBER -> addView(MaskedImageView(ctx, R.drawable.pit_umber_contour, solidStop = 0.72f, drift = true), FrameLayout.LayoutParams(ui.dp(umberWidth), ui.dp(height), Gravity.CENTER))
        }
        layoutParams = LinearLayout.LayoutParams(-1, ui.dp(height))
    }
}

class TodayScreen(shell: PhoneShell) : Screen(shell) {
    override val isTab = true
    override val gap = 26f
    override fun fill(c: GapLayout) {
        val store = PresetStore(ctx)
        val preset = store.selected()
        val (conn, idle) = Fmt.connection(app)
        c.addView(ui.hstack().apply {
            addView(Fmt.wordmark(ui).apply { tag = Fmt.WORDMARK_TAG }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.live(if (app.pairingConfirmed) "차량과 이어져 있어요" else conn, idle).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }, ui.wrap())
        })
        c.addView(ui.vstack(10f).apply {
            val w = app.workout
            val eyebrow = Fmt.time(Instant.now()) + (w?.let { " · 운동을 마친 지 ${Fmt.minutesSince(it.end)}분" } ?: "")
            addView(ui.eyebrow(eyebrow))
            addView(ui.display("잠깐 쉬어갈까요?"))
        })
        c.addView(moodArt(ui, 210f, 330f, 340f), LinearLayout.LayoutParams(-1, ui.dp(210)).apply { marginStart = -ui.dp(22); marginEnd = -ui.dp(22); topMargin = -ui.dp(6) })
        // 하루 쉼: 출퇴근·웜업·운동 뒤에만 '지금' 카드가 보인다(제안만).
        momentCardFor(this)?.let { c.addView(it) }

        val card = ui.card(22f, 22f, 18f)
        card.setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(20))
        card.addView(ui.hstack(12f).apply {
            gravity = Gravity.TOP
            addView(ui.vstack(6f).apply {
                addView(ui.overline("오늘의 쉼"))
                addView(ui.h2(preset.name, 22f))
                addView(ui.rowSub(Fmt.restLine(ctx)))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.ghost("바꾸기") { shell.showTab(Tab.COMFORT, true) }, LinearLayout.LayoutParams(-2, ui.dp(48)).apply { marginEnd = -ui.dp(10) })
        })
        card.addView(ui.hstack(8f).apply {
            fun cell(label: String, value: String, numeric: Boolean) = ui.sunken(16f, 12f, 12f, 2f).apply {
                setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(10))
                addView(ui.caption(label, size = 12f))
                addView(if (numeric) ui.num(value, 22f, 400) else ui.text(value, 16f, 600, t.ink, 1.7f))
            }
            addView(cell("차 온도", Fmt.temp(preset.vehicle.tempC), preset.vehicle.tempC != null), LinearLayout.LayoutParams(0, -1, 1f))
            addView(cell("바람", Fmt.fan(preset.vehicle.fan), false), LinearLayout.LayoutParams(0, -1, 1f))
            addView(cell("시트", Fmt.seat(preset.vehicle.seat), false), LinearLayout.LayoutParams(0, -1, 1f))
        })
        card.addView(ui.primary("차에서 쉴 준비") {
            store.beginRest()
            shell.push(ShareScreen(shell))
        })
        card.addView(ui.caption("고른 것만 차로 전해요. 쉬기 시작은 차에서 직접 눌러요.").apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = -ui.dp(6) })
        c.addView(card)

        val rows = mutableListOf<View>()
        app.workout?.let { w ->
            rows += ui.row("pulse", "${Fmt.workout(w.type)} ${w.minutes}분", "${Fmt.hm(w.end)}에 마침 · 함께 전할지 골라요", end = "함께 전하기") { shell.push(ShareScreen(shell)) }
        }
        rows += ui.row("car", "차량", if (app.pairingConfirmed) "차량 앱과 이어져 있어요 · " + Fmt.checked(app.lastPairingCheckAt) else conn) { shell.push(PairScreen(shell)) }
        c.addView(ui.list(*rows.toTypedArray()), LinearLayout.LayoutParams(-1, -2).apply { topMargin = -ui.dp(4) })

        c.addView(ui.vstack(12f).apply {
            // 폰은 아직 차에서 마친 쉼을 받지 않는다. 있는 것처럼 보이지 않게 준비 중이라고 밝힌다.
            addView(ui.sectionHead("지난 쉼"))
            addView(ui.flatCard(18f, 16f, 2f).apply {
                addView(ui.rowTitle("아직 지난 쉼이 없어요", 15f))
                addView(ui.rowSub("차에서 마친 쉼을 모아 보는 기능은 준비 중이에요"))
            })
        })
    }
}

class ComfortScreen(shell: PhoneShell) : Screen(shell) {
    override val isTab = true
    override val gap = 30f
    private var feeling: String? = null
    private val suggestions = mapOf(
        "warm" to "‘산뜻하게 식히기’ 어떠세요? 차 23.5° · 바람 중간",
        "quiet" to "‘조용히 쉬기’ 어떠세요? 움직임을 덜고 잔잔하게",
        "tired" to "오늘은 ‘조용히 쉬기’로 천천히 머물러요.",
        "cold" to "‘부드럽게 기분 전환’ 어떠세요? 차 25.0° · 바람 약하게")
    override fun fill(c: GapLayout) {
        val store = PresetStore(ctx)
        val selected = store.selected()
        val prefs = PitPrefs(ctx)
        c.addView(ui.vstack(8f).apply {
            addView(ui.title("취향에 맞는 쉼"))
            addView(ui.body("한 번 골라 두면 폰과 차가 같이 기억해요."))
        })
        // 지금 고른 쉼
        val hero = FrameLayout(ctx).apply {
            addView(ImageView(ctx).apply { setImageResource(R.drawable.pit_ribbon); scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, FrameLayout.LayoutParams(-1, -1))
            val ink = Color.parseColor("#141413")
            val overlay = ui.vstack(4f).apply {
                setPadding(ui.dp(22), ui.dp(18), ui.dp(18), ui.dp(18)); gravity = Gravity.BOTTOM
                addView(ui.overline("지금 고른 쉼", Color.parseColor("#57534D")))
                addView(ui.hstack().apply {
                    gravity = Gravity.BOTTOM
                    addView(ui.vstack(8f).apply {
                        addView(ui.text(selected.name, 24f, 600, ink, 1.25f, -0.03f))
                        addView(View(ctx).apply { setBackgroundColor(ink) }, LinearLayout.LayoutParams(ui.dp(22), ui.dp(1.5f).coerceAtLeast(2)))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(ui.chip("살펴보기", false, icon = "chevron") { shell.push(PresetEditScreen(shell, selected.id)) }.apply {
                        background = ui.shape(Color.argb(179, 255, 255, 255), 999f, Color.parseColor("#D5D0C8")); setTextColor(ink)
                        (compoundDrawablesRelative[2] as? IconDrawable)?.color = ink
                    })
                })
            }
            addView(overlay, FrameLayout.LayoutParams(-1, -1))
            post { roundClip(this, ui.dp(28).toFloat()) }
        }
        c.addView(hero, LinearLayout.LayoutParams(-1, ui.dp(176)).apply { topMargin = -ui.dp(6) })

        // 오늘은 어떤가요?
        c.addView(ui.vstack(14f).apply {
            addView(ui.h2("오늘은 어떤가요?"))
            addView(ui.flow().apply {
                listOf("usual" to "평소처럼", "warm" to "더워요", "quiet" to "조용히 쉬고 싶어요", "tired" to "조금 지쳤어요", "cold" to "쌀쌀해요").forEach { (id, label) ->
                    addView(ui.chip(label, feeling == id) { feeling = if (feeling == id) null else id; rebuild() })
                }
            })
            feeling?.let { f ->
                val rec = Preset.recommendation(f, store.defaultId).first
                val text = if (f == "usual") {
                    val d = store.all().firstOrNull { it.id == store.defaultId } ?: store.selected()
                    "평소처럼이라면 ‘${d.name}’ 쉼이 무난해요."
                } else suggestions.getValue(f)
                addView(ui.sunken(18f, 16f, 14f).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(ui.body(text, 14f, t.ink), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(12) })
                    val already = rec == store.selectedId
                    addView(ui.secondary(if (already) "골랐어요" else "이걸로", 40f, 14f) { rec?.let { app.selectPreset(it); rebuild() } }.also { ui.setEnabledLook(it, !already) })
                })
            }
            addView(ui.caption("직접 고르는 느낌이에요. 건강 상태를 판단하지 않아요."), LinearLayout.LayoutParams(-1, -2).apply { topMargin = -ui.dp(4) })
        })

        // 나의 쉼
        c.addView(ui.vstack(6f).apply {
            addView(ui.sectionHead("나의 쉼", "기본은 쉬기 준비 때 먼저 골라져요"))
            val rows = store.all().map { p ->
                val on = p.id == store.selectedId
                val titleView = ui.hstack(8f).apply {
                    addView(ui.rowTitle(p.name), LinearLayout.LayoutParams(-2, -2, 0f))
                    if (p.id == store.defaultId) addView(ui.pill("기본").apply { textSize = 11f; height = ui.dp(22); minHeight = ui.dp(22); setPadding(ui.dp(10), 0, ui.dp(10), 0) })
                }
                ui.row(Fmt.comfortIcon(p.comfort), "", null, chevron = false, endView = CheckMarkView(ctx, t, on).takeIf { on }, onClick = {
                    if (store.selectedId != p.id) { PitHaptics.tick(c); app.selectPreset(p.id); rebuild() }
                    else shell.push(PresetEditScreen(shell, p.id))
                }).apply {
                    val main = getChildAt(1) as LinearLayout
                    main.removeViewAt(0); main.addView(titleView, 0); main.addView(ui.rowSub(Fmt.presetSub(p)))
                    contentDescription = p.name + (if (on) ", 선택됨. 한 번 더 누르면 살펴봐요" else "")
                }
            }
            val create = ui.row("plus", "새 쉼 만들기", "기본 쉼을 복사해서 내 온도·바람으로") { shell.push(PresetEditScreen(shell, null)) }
            addView(ui.list(*(rows + create).toTypedArray()))
        })

        c.addView(ui.list(
            ui.row("seat", "차 안 설정", CabinStore(ctx).summary(selected.id)) { shell.push(CabinScreen(shell)) },
            ui.row("link", "차에 이 쉼 보내기", "운동 기록 없이 · 차 안 설정도 함께 · 시작은 차에서") { app.sendRestToCar(selected.id) },
            ui.row("link", "취향 함께 쓰기", "차와 웹이 같은 쉼 목록을 봐요", end = Fmt.syncedState(ctx)) { app.openSyncedPresets() }
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = -ui.dp(8) })

        // 분위기 (쉬는 동안 차 화면)
        c.addView(ui.vstack(16f).apply {
            addView(ui.h2("분위기"))
            addView(ui.hstack(8f).apply {
                listOf("pearl" to ("Pearl" to R.drawable.pit_sphere_pearl), "graphite" to ("Graphite" to R.drawable.pit_sphere_graphite), "umber" to ("Umber" to R.drawable.pit_sphere_umber)).forEach { (id, v) ->
                    val on = prefs.restMood == id
                    addView(ui.vstack(10f).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        val sphere = ImageView(ctx).apply { setImageResource(v.second); scaleType = ImageView.ScaleType.CENTER_CROP; post { roundClip(this, width / 2f) } }
                        val ring = FrameLayout(ctx).apply {
                            background = ui.shape(Color.TRANSPARENT, 999f, if (on) t.ink else Color.TRANSPARENT, 1.5f)
                            addView(sphere, FrameLayout.LayoutParams(ui.dp(72), ui.dp(72), Gravity.CENTER))
                        }
                        addView(ring, LinearLayout.LayoutParams(ui.dp(84), ui.dp(84)))
                        addView(ui.text(v.first, 14f, 500, t.ink).apply { gravity = Gravity.CENTER })
                        isClickable = true; isFocusable = true; contentDescription = v.first + if (on) ", 선택됨" else ""
                        setOnClickListener {
                            if (prefs.restMood == id) return@setOnClickListener
                            prefs.restMood = id; PitHaptics.tick(this); rebuild()
                        }
                        if (on && !PitMotion.reduced()) sphere.post { sphere.animate().scaleX(1.06f).scaleY(1.06f).setDuration(PitMotion.springPop.durationMs).setInterpolator(PitMotion.springPop).start() }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }
            })
            addView(ui.caption("쉬는 동안 차 화면의 분위기예요. 앱 화면 색은 ‘나’에서 따로 정해요.").apply { gravity = Gravity.CENTER })
        })

        // 쉬는 방식
        c.addView(ui.vstack(14f).apply {
            addView(ui.h2("쉬는 방식"))
            addView(SegmentedView(ctx, ui, listOf(Triple("guided", "안내에 맞춰", null), Triple("free", "자유롭게", null)), prefs.restStyle) { prefs.restStyle = it; shell.screenFor(Tab.TODAY).dirty = true })
            addView(ui.list(
                ui.toggleRow("동작 줄이기", "점과 막이 움직이지 않고 한자리에 머물러요", prefs.reduceMotion) { prefs.reduceMotion = it; shell.screenFor(Tab.TODAY).dirty = true },
                ui.row(null, "소리", "처음에는 꺼져 있어요", end = "끔", titleWeight = 500) { soundSheet() }
            ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        })
    }
    private fun soundSheet() = shell.showSheet { ui, dismiss ->
        ui.vstack(14f).apply {
            addView(ui.h2("소리"))
            addView(ui.list(
                ui.radioRow("소리 끔", "쉬는 동안 차에서 소리를 내지 않아요", true) { dismiss() },
                ui.row(null, "잔잔한 소리", "곧 고를 수 있어요", chevron = false, endView = ui.pill("곧", "muted"), titleWeight = 500)
            ))
            addView(ui.primary("닫기") { dismiss() })
        }
    }
}

class ConnectScreen(shell: PhoneShell) : Screen(shell) {
    override val isTab = true
    override fun decorate(root: FrameLayout) {
        // 타원 마스크가 뷰 아래 끝(centerY + radiusY ≤ 1) 안에서 다 사라져야 경계선이 안 보인다
        val ribbon = MaskedImageView(ctx, R.drawable.pit_ribbon, centerX = 0.7f, centerY = 0.36f, radiusX = 0.7f, radiusY = 0.62f, solidStop = 0.25f)
        ribbon.alpha = if (ui.t.mood == Mood.PEARL) 0.9f else 0.12f
        root.addView(ribbon, FrameLayout.LayoutParams(ui.dp(330), ui.dp(250) + shell.insetTop, Gravity.END or Gravity.TOP).apply { rightMargin = -ui.dp(40) })
    }
    override fun fill(c: GapLayout) {
        val (conn, idle) = Fmt.connection(app)
        val source = if (PitPrefs(ctx).healthSource == "samsung") "삼성 헬스" else "Health Connect"
        val health = when (app.healthState) {
            HealthState.GRANTED -> "허용됨"; HealthState.NEEDS_PERMISSION -> if (PitPrefs(ctx).healthPermissionAsked) "허용 안 됨" else "연결 안 함"
            HealthState.UPDATE_REQUIRED -> "설치 필요"; HealthState.UNAVAILABLE -> "쓸 수 없음"; else -> "확인 중"
        }
        c.addView(ui.vstack(12f).apply {
            setPadding(0, ui.dp(28), 0, 0)
            addView(ui.display("필요한 것만\n연결하세요"))
            addView(ui.body("연결하지 않아도 바로 쉴 수 있어요."))
        })
        val rows = mutableListOf<View>(
            ui.row("car", "차량", subView = ui.live(if (app.pairingConfirmed) "차량 · 이어져 있어요" else conn, idle)) { shell.push(PairScreen(shell)) },
            ui.row("pulse", "운동 기록", if (ChabunDayPrefs(ctx).readsExerciseOnOpen()) "$source · 하루 쉼을 켜 둬서 앱을 열 때도 운동 시간을 읽어요" else "$source · 누를 때만 최근 운동을 읽어요", end = health) { shell.push(HealthScreen(shell)) },
            ui.row("today", "하루 쉼", "출퇴근 · 운동 전후 · 쉬어 갈 곳", end = ChabunDayPrefs(ctx).let { d -> if (d.commute.enabled || d.readsExerciseOnOpen()) "켜짐" else "꺼짐" }) { shell.push(ChabunDayScreen(shell)) },
            ui.row("sliders", "취향 함께 쓰기", "차와 웹이 같은 쉼 목록을 봐요", end = Fmt.syncedState(ctx)) { app.openSyncedPresets() }
        )
        // 집 연결은 아직 합성 기기만 있는 체험이라 디버그 빌드에서만 보인다.
        if (app.debug) rows += ui.row("house", "집", "Apple Home · SmartThings · Google Home", end = "살펴보기") { shell.push(HomesScreen(shell)) }
        rows += ui.row("moon", "화면 분위기", AppearancePreferences(ctx).shortStatus()) { shell.push(AppearanceScreen(shell)) }
        c.addView(ui.list(*rows.toTypedArray()))
        c.addView(ui.sunken(28f, 20f, 18f, 10f).apply {
            addView(ui.rowTitle("연결과 보내기는 따로예요", 15f))
            addView(ui.body("운동 기록은 보낼 때마다 한 번씩 물어봐요. 연결을 끊으면 차에 보낸 요약도 함께 지워요.", 14f))
        })
        c.addView(ui.hstack(14f).apply {
            minimumHeight = ui.dp(52)
            addView(ui.icon("doc", t.ink2))
            addView(ui.text("연결할 때 읽는 항목 확인하기", 14f, 400, t.ink2), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.icon("chevron", t.ink3, 18f))
            isClickable = true; isFocusable = true; setOnClickListener { shell.push(PrivacyScreen(shell)) }; pressable(0.985f)
        })
    }
}

class MeScreen(shell: PhoneShell) : Screen(shell) {
    override val isTab = true
    override val gap = 30f
    private var taps = 0
    override fun fill(c: GapLayout) {
        val prefs = PitPrefs(ctx)
        c.addView(ui.vstack(8f).apply {
            addView(ui.title("나"))
            addView(ui.body("이 폰의 설정을 모아 뒀어요."))
        })
        c.addView(ui.vstack(6f).apply {
            addView(ui.h2("최근 쉼"))
            addView(ui.list(ui.row(null, "아직 지난 쉼이 없어요", "차에서 마친 쉼을 모아 보는 기능은 준비 중이에요", chevron = false, titleWeight = 500, endView = ui.pill("준비 중", "muted"))))
        })
        c.addView(ui.vstack(6f).apply {
            addView(ui.h2("이 폰"))
            addView(ui.list(
                ui.row("moon", "화면 분위기", AppearancePreferences(ctx).shortStatus()) { shell.push(AppearanceScreen(shell)) },
                ui.toggleRow("가벼운 진동", "고를 때 손끝에 짧게 알려줘요", prefs.haptics, icon = "vibe") { prefs.haptics = it },
                ui.row("shield", "개인정보와 데이터", "무엇을 읽고, 무엇을 보내고, 언제 지우는지") { shell.push(PrivacyScreen(shell)) }
            ))
        })
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
        c.addView(ui.vstack(6f).apply {
            addView(ui.h2("도움"))
            addView(ui.list(
                ui.row(null, "처음 쓰는 법", "폰에서 고르고, 차에서 시작해요", titleWeight = 500) { howToSheet() },
                ui.row(null, "앱 정보", "차분 · 운동 후, 편안한 선택", end = version, chevron = false, titleWeight = 500, onClick = {
                    if (!app.debug) return@row
                    taps++
                    if (taps >= 7) { taps = 0; shell.push(DeveloperScreen(shell)) }
                })
            ))
        })
    }
    private fun howToSheet() = shell.showSheet { ui, dismiss ->
        ui.vstack(16f).apply {
            addView(ui.h2("처음 쓰는 법"))
            addView(ui.step(1, "폰에서 오늘의 쉼을 골라요", "취향에서 온도·바람과 차 안 설정을 정해요"))
            addView(ui.step(0, "원할 때만 운동 기록을 함께 전해요", "보낼 때마다 한 번씩 물어봐요", muted = true))
            addView(ui.step(0, "차에서 ‘3분 쉬기’를 눌러 시작해요", "폰이 쉬기를 저절로 시작하지는 않아요", muted = true))
            addView(ui.primary("알겠어요") { dismiss() })
        }
    }
}
