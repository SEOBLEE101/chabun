package com.recovery.companion

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import java.util.Locale

/**
 * 차 안 설정: 시트 / 에어컨 / 조명. 바꾼 값은 저장 전까지 초안이고, 되돌리기는 마지막 저장으로 돌아간다.
 * 선호 저장만 하며 차에 값을 쓰지 않는다.
 */
class CabinScreen(shell: PhoneShell) : Screen(shell) {
    override val gap = 22f
    private val store = CabinStore(ctx)
    private val presetId = PresetStore(ctx).selectedId
    private var saved = store.saved(presetId)
    private var draft = saved
    private var justSaved = false
    private var tab = "seat"
    private var target = "driver"
    private var art: CabinArtView? = null
    private var dial: ArcDialView? = null
    private var roll: RollingNumberView? = null
    private var controls: GapLayout? = null
    private var summary: GapLayout? = null
    private var footer: GapLayout? = null
    private var pill: TextView? = null
    private var headline: GapLayout? = null
    private var zone: View? = null

    override fun onStateChanged() {}
    override fun fill(c: GapLayout) {
        dial = null; roll = null; zone = null; headline = null; modeBox = null; climateOpts = null
        pill = TextView(ctx)
        c.addView(ui.backHeader("취향", null, { shell.pop() }, pill), LinearLayout.LayoutParams(-1, -2).apply { topMargin = -ui.dp(8); marginStart = -ui.dp(10) })
        c.addView(ui.vstack(8f).apply {
            addView(ui.display("나에게 편한 차 안", 30f))
            addView(ui.body("바꾸고 싶은 것만 고르세요. 나머지는 그대로 둬요."))
        })
        c.addView(SegmentedView(ctx, ui, listOf(Triple("seat", "시트", "seat"), Triple("climate", "에어컨", "climate"), Triple("light", "조명", "light")), tab, 48) { tab = it; content.postDelayed({ rebuild(); shell.cascade(content) }, if (PitMotion.reduced()) 0 else 200) }.apply {
            contentDescription = "차 안 설정"
        })
        if (tab != "light") {
            c.addView(ui.hstack().apply {
                addView(ui.caption("설정할 자리"), LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.hstack(6f).apply {
                    listOf("driver" to "운전석", "passenger" to "동승석").forEach { (id, label) -> addView(ui.chip(label, id == target, small = true) { if (target != id) { target = id; rebuild() } }) }
                })
            })
        } else {
            c.addView(ui.hstack().apply {
                addView(ui.caption("설정할 곳"), LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.pill("실내 전체", "neutral"))
            })
        }
        val artView = CabinArtView(ctx, t, tab).also { it.snap(draft.seat(target), draft.climate(target), draft.light); art = it }
        when (tab) {
            "seat" -> {
                c.addView(ui.card(18f, 14f, 4f).apply {
                    setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(18))
                    addView(artView, LinearLayout.LayoutParams(-1, -2))
                    headline = ui.vstack(4f).also { addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) }) }
                })
                controls = ui.vstack(22f).also { c.addView(it) }
            }
            "climate" -> {
                c.addView(ui.card(14f, 12f, 0f).apply { addView(artView, LinearLayout.LayoutParams(-1, -2)) })
                controls = ui.vstack(18f).also { c.addView(it) }
            }
            else -> {
                c.addView(ui.card(12f, 12f, 10f).apply {
                    setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(16))
                    addView(artView, LinearLayout.LayoutParams(-1, -2).apply { })
                    headline = ui.vstack().also { addView(it) }
                })
                controls = ui.vstack(22f).also { c.addView(it) }
            }
        }
        summary = ui.card(22f, 22f, 12f).also { c.addView(it) }
        footer = ui.vstack(6f).also { c.addView(it) }
        statusLine = ui.status().also { c.addView(it) }
        renderAll(animate = false)
    }

    private fun edit(change: (CabinPrefs) -> CabinPrefs) {
        draft = change(draft); justSaved = false
        renderAll(animate = true)
    }

    private fun renderAll(animate: Boolean) {
        val s = draft.seat(target); val cl = draft.climate(target); val l = draft.light
        if (animate) when (tab) { "seat" -> art?.setSeat(s); "climate" -> art?.setClimate(cl); else -> art?.setLight(l) }
        headline?.let { h ->
            h.removeAllViews()
            if (tab == "seat") {
                h.addView(ui.overline(if (target == "driver") "운전석" else "동승석"))
                h.addView(ui.h2(when (s.comfort) { "heat" -> "열선 ${s.level}단계"; "vent" -> "통풍 ${s.level}단계"; "off" -> "열선·통풍 끄기"; else -> "열선·통풍 그대로" }, 20f))
                h.addView(ui.caption(if (s.position == "manual") "자세는 차에서 직접 맞춰요" else "자세는 그대로 둬요"))
            } else if (tab == "light") {
                h.addView(ui.hstack().apply {
                    setPadding(ui.dp(6), 0, ui.dp(6), 0); gravity = Gravity.BOTTOM
                    addView(ui.text(when (l.mode) { "custom" -> CabinPrefs.COLORS.getValue(l.color).first + " · 밝기 ${l.bright}%"; "off" -> "조명 끄기"; else -> "조명은 그대로 둬요" }, 15f, 600), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(ui.caption("화면 속 예시 색이에요", size = 12f))
                })
            }
        }
        controls?.let { c ->
            if (tab == "climate" && dial != null && c.childCount > 0) updateClimate(c, cl, animate) else { c.removeAllViews(); when (tab) { "seat" -> seatControls(c, s); "climate" -> climateControls(c, cl); else -> lightControls(c, l) } }
        }
        renderSummary()
    }

    private fun seatControls(c: GapLayout, s: SeatPref) {
        c.addView(ui.vstack(10f).apply {
            addView(ui.lbl("열선·통풍", "둘 중 하나만 골라요"))
            addView(ui.opt(listOf(Triple("keep", "그대로", null), Triple("off", "끄기", null), Triple("heat", "열선", null), Triple("vent", "통풍", null)), s.comfort) { v -> edit { it.withSeat(target, it.seat(target).copy(comfort = v)) } })
        })
        val on = s.comfort == "heat" || s.comfort == "vent"
        c.addView(ui.vstack(10f).apply {
            addView(ui.lbl("단계", "열선이나 통풍을 고르면 정할 수 있어요"))
            addView(ui.opt(listOf(Triple("1", "1단계", null), Triple("2", "2단계", null), Triple("3", "3단계", null)), s.level.toString()) { v -> edit { it.withSeat(target, it.seat(target).copy(level = v.toInt())) } })
            zoneLook(this, on)
        })
        c.addView(ui.vstack(10f).apply {
            addView(ui.lbl("자세", "등받이와 위치는 차에서 직접"))
            addView(ui.opt(listOf(Triple("keep", "그대로", null), Triple("manual", "차에서 직접 조절", null)), s.position) { v -> edit { it.withSeat(target, it.seat(target).copy(position = v)) } })
        })
    }
    private fun zoneLook(v: View, on: Boolean) {
        v.alpha = if (on) 1f else 0.34f
        fun walk(x: View) { x.isEnabled = on; if (x is android.view.ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(v)
        v.importantForAccessibility = if (on) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private var modeBox: GapLayout? = null
    private var climateOpts: GapLayout? = null
    private fun climateControls(c: GapLayout, cl: ClimatePref) {
        modeBox = ui.vstack().also { c.addView(it) }
        val zoneBox = ui.vstack(18f)
        zone = zoneBox
        val dialCard = ui.card(18f, 12f, 0f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(18))
            val box = FrameLayout(ctx)
            val d = ArcDialView(ctx, t).also { dial = it }
            box.addView(d, FrameLayout.LayoutParams(ui.dp(300), ui.dp(230)))
            val center = ui.vstack(4f).apply { gravity = Gravity.CENTER_HORIZONTAL }
            val r = RollingNumberView(ctx, t.ink, 60f, 300, "°", 0.5f).also { roll = it }
            center.addView(r, LinearLayout.LayoutParams(-2, -2))
            center.addView(ui.caption("${if (target == "driver") "운전석" else "동승석"} · 바라는 온도"), ui.wrap())
            box.addView(center, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = ui.dp(66) })
            addView(box, LinearLayout.LayoutParams(ui.dp(300), ui.dp(210)))
            addView(ui.hstack().apply {
                addView(ui.glassButton("minus", "온도 내리기") { edit { x -> x.withClimate(target, x.climate(target).let { it.copy(temp = (it.temp - 0.5).coerceAtLeast(18.0)) }) } })
                addView(ui.caption("18° ~ 28°").apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.glassButton("plusSmall", "온도 올리기") { edit { x -> x.withClimate(target, x.climate(target).let { it.copy(temp = (it.temp + 0.5).coerceAtMost(28.0)) }) } })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = -ui.dp(26) })
        }
        zoneBox.addView(dialCard)
        climateOpts = ui.vstack(18f).also { zoneBox.addView(it) }
        c.addView(zoneBox)
        c.addView(ui.caption("실내 온도가 아니라, 내가 바라는 설정 온도예요. 차에서는 지금 실내 온도와 나란히 보여요."))
        updateClimate(c, cl, false)
    }
    private fun updateClimate(c: GapLayout, cl: ClimatePref, animate: Boolean) {
        modeBox?.let { m -> m.removeAllViews(); m.addView(ui.opt(listOf(Triple("keep", "그대로", null), Triple("custom", "직접 정하기", null)), cl.mode) { v -> edit { it.withClimate(target, it.climate(target).copy(mode = v)) } }) }
        dial?.setProgress(((cl.temp - 18) / 10).toFloat(), animate)
        roll?.setValue(String.format(Locale.US, "%.1f", cl.temp), animate)
        roll?.contentDescription = "${if (target == "driver") "운전석" else "동승석"} 바라는 온도 ${String.format(Locale.US, "%.1f", cl.temp)}도"
        climateOpts?.let { o ->
            o.removeAllViews()
            o.addView(ui.vstack(10f).apply {
                addView(ui.lbl("바람 세기"))
                addView(ui.opt(listOf(Triple("auto", "자동", null), Triple("low", "약하게", null), Triple("medium", "중간", null), Triple("high", "세게", null)), cl.fan) { v -> edit { it.withClimate(target, it.climate(target).copy(fan = v)) } })
            })
            o.addView(ui.vstack(10f).apply {
                addView(ui.lbl("바람 방향"))
                addView(ui.opt(listOf(Triple("keep", "그대로", "dirKeep"), Triple("upper", "위로", "dirUp"), Triple("lower", "아래로", "dirDown"), Triple("both", "위아래", "dirBoth")), cl.dir, tall = true) { v -> edit { it.withClimate(target, it.climate(target).copy(dir = v)) } })
            })
            o.addView(ui.vstack(10f).apply {
                addView(ui.lbl("냉방", "A/C"))
                addView(ui.opt(listOf(Triple("keep", "그대로", null), Triple("on", "켜기", null), Triple("off", "끄기", null)), cl.ac) { v -> edit { it.withClimate(target, it.climate(target).copy(ac = v)) } })
            })
        }
        zone?.let { z ->
            val on = cl.mode == "custom"
            if (animate && !PitMotion.reduced()) z.animate().alpha(if (on) 1f else 0.34f).setDuration(350).setInterpolator(PitMotion.easeIos).start()
            val a = z.alpha; zoneLook(z, on); if (animate && !PitMotion.reduced()) z.alpha = a
        }
    }

    private fun lightControls(c: GapLayout, l: LightPref) {
        c.addView(ui.opt(listOf(Triple("keep", "그대로", null), Triple("off", "끄기", null), Triple("custom", "직접 정하기", null)), l.mode) { v -> edit { it.copy(light = it.light.copy(mode = v)) } })
        val zoneBox = ui.vstack(22f)
        zoneBox.addView(ui.vstack(14f).apply {
            addView(ui.lbl("빛의 색", "차의 실제 색과 다를 수 있어요"))
            addView(ui.hstack().apply {
                CabinPrefs.COLORS.forEach { (id, v) ->
                    val on = id == l.color
                    val color = Color.parseColor(v.second)
                    addView(ui.vstack(8f).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        val sw = View(ctx).apply {
                            background = android.graphics.drawable.LayerDrawable(arrayOf(
                                ui.shape(ColorUtils.setAlphaComponent(color, 60), 999f),
                                ui.shape(color, 999f, if (on) t.ink else t.line2, if (on) 2f else 1f)
                            )).apply { setLayerInset(1, ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) }
                            if (on) { scaleX = 1.08f; scaleY = 1.08f }
                        }
                        addView(sw, LinearLayout.LayoutParams(ui.dp(54), ui.dp(54)))
                        addView(ui.text(v.first, 13f, 500, t.ink, 1.2f).apply { gravity = Gravity.CENTER }, ui.wrap())
                        isClickable = true; isFocusable = true; contentDescription = v.first + if (on) ", 선택됨" else ""
                        setOnClickListener { PitHaptics.tick(this); edit { it.copy(light = it.light.copy(color = id)) } }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }
            })
        })
        val brightLabel = ui.num("${l.bright}%", 15f, 400)
        zoneBox.addView(ui.vstack(14f).apply {
            addView(ui.hstack().apply { addView(ui.text("밝기", 15f, 600, t.ink, 1.3f, -0.02f), LinearLayout.LayoutParams(0, -2, 1f)); addView(brightLabel) })
            addView(RangeView(ctx, t, 10, 100, 5, l.bright, "밝기") { v ->
                draft = draft.copy(light = draft.light.copy(bright = v)); justSaved = false
                brightLabel.text = "$v%"; art?.setLight(draft.light); renderSummary()
                headline?.let { h -> ((h.getChildAt(0) as? LinearLayout)?.getChildAt(0) as? TextView)?.text = CabinPrefs.COLORS.getValue(draft.light.color).first + " · 밝기 $v%" }
            }, LinearLayout.LayoutParams(-1, ui.dp(36)))
            addView(ui.hstack().apply { addView(ui.caption("은은하게", size = 12f), LinearLayout.LayoutParams(0, -2, 1f)); addView(ui.caption("밝게", size = 12f)) })
        })
        zoneLook(zoneBox, l.mode == "custom")
        c.addView(zoneBox)
        c.addView(ui.caption("화면 분위기와는 따로예요. 동승자와 함께 정해 주세요."))
    }

    private fun renderSummary() {
        val changed = draft.changedFrom(saved)
        pill?.apply {
            text = if (changed > 0) "저장 전" else if (justSaved) "저장했어요" else "저장됨"
            textSize = 12f; typeface = PitType.ko(600); includeFontPadding = false; gravity = Gravity.CENTER
            setPadding(ui.dp(12), 0, ui.dp(12), 0); height = ui.dp(28); minHeight = ui.dp(28)
            background = ui.shape(if (changed > 0) t.bg2 else t.accentSoft, 999f)
            setTextColor(if (changed > 0) t.ink2 else t.ink)
            val dot = if (changed > 0) ui.shape(t.warn, 999f).apply { setSize(ui.dp(6), ui.dp(6)) } else null
            setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null); compoundDrawablePadding = ui.dp(6)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        summary?.let { s ->
            s.removeAllViews()
            s.addView(ui.hstack().apply {
                gravity = Gravity.BOTTOM
                addView(ui.h2("이번 쉼의 차 안", 17f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.caption(if (changed > 0) "바뀐 항목 ${changed}개" else "저장한 그대로예요"))
            })
            fun line(title: String, body: String, top: Boolean) = ui.hstack().apply {
                gravity = Gravity.TOP; setPadding(0, ui.dp(8), 0, ui.dp(8)); minimumHeight = ui.dp(48)
                if (top) background = TopLineDrawable(t.line, ctx.dpf(1).coerceAtLeast(1f))
                addView(ui.rowTitle(title, 14f), LinearLayout.LayoutParams(ui.dp(54), -2))
                addView(ui.rowSub(body), LinearLayout.LayoutParams(0, -2, 1f))
            }
            s.addView(ui.vstack().apply {
                addView(line("시트", "운전석 " + CabinPrefs.seatText(draft.seatDriver) + "\n동승석 " + CabinPrefs.seatText(draft.seatPassenger), false))
                addView(line("에어컨", "운전석 " + CabinPrefs.climateText(draft.climDriver) + "\n동승석 " + CabinPrefs.climateText(draft.climPassenger), true))
                addView(line("조명", "실내 전체 " + CabinPrefs.lightText(draft.light), true))
            })
        }
        footer?.let { f ->
            f.removeAllViews()
            f.addView(ui.primary(if (changed > 0) "이번 쉼에 저장" else "저장됨") { save() }.also { ui.setEnabledLook(it, changed > 0) })
            f.addView(ui.ghost("저장 전으로 되돌리기", t.ink2, 14f) { draft = saved; rebuild() }.also { ui.setEnabledLook(it, changed > 0) }, LinearLayout.LayoutParams(-2, ui.dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            f.addView(ui.caption("‘차에 이 쉼 보내기’로 함께 가요. 차를 연결한 앱에서 주차 중 쉼을 시작하면 이 값으로 맞추고, 마치면 돌려요.").apply { gravity = Gravity.CENTER })
        }
    }

    private fun save() {
        runCatching { store.save(presetId, draft) }.onFailure { report("저장하지 못했어요. 값을 다시 확인해 주세요."); return }
        saved = draft; justSaved = true
        pill?.let { PitHaptics.confirm(it) }
        shell.markAllDirty()
        renderSummary()
    }
}
