package com.recovery.companion

import android.content.Context
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 생활 전반 쉼 설정. 이 폰 안에만 저장하고 서버·차로 보내지 않는다. 깨진 값은 기본값으로 돌아간다. */
class ChabunDayPrefs(context: Context) {
    private val p = context.getSharedPreferences("chabun_day", Context.MODE_PRIVATE)

    var commute: CommuteSettings
        get() = runCatching {
            CommuteSettings(
                enabled = p.getBoolean("commuteOn", false),
                morning = LocalTime.parse(p.getString("morning", "08:30")),
                evening = LocalTime.parse(p.getString("evening", "18:30")),
                days = (p.getString("days", "1,2,3,4,5") ?: "").split(',').filter { it.isNotBlank() }.map { DayOfWeek.of(it.toInt()) }.toSet(),
                morningPresetId = p.getString("morningPreset", "gentle_refresh") ?: "gentle_refresh",
                eveningPresetId = p.getString("eveningPreset", "quiet_rest") ?: "quiet_rest",
            ).validated()
        }.getOrElse { CommuteSettings() }
        set(v) {
            val c = v.validated()
            p.edit().putBoolean("commuteOn", c.enabled).putString("morning", c.morning.toString()).putString("evening", c.evening.toString())
                .putString("days", c.days.map { it.value }.sorted().joinToString(","))
                .putString("morningPreset", c.morningPresetId).putString("eveningPreset", c.eveningPresetId).apply()
        }

    /** 아직 살아 있는 계획만 돌려준다(다음 날 0시와 계획 + 3시간 중 늦은 때까지). 지난 계획은 읽을 때 지운다. */
    fun plan(now: LocalDateTime): WorkoutPlan? {
        val raw = p.getString("plan", null) ?: return null
        val parsed = runCatching {
            val f = raw.split('|'); require(f.size == 3)
            WorkoutPlan(LocalDate.parse(f[0]), f[1], LocalTime.parse(f[2])).validated()
        }.getOrNull()
        val active = DayRhythmPolicy.activePlan(parsed, now)
        if (active == null) p.edit().remove("plan").apply()
        return active
    }

    fun setPlan(plan: WorkoutPlan?) {
        if (plan == null) p.edit().remove("plan").apply()
        else plan.validated().let { p.edit().putString("plan", "${it.date}|${it.type}|${it.start}").apply() }
    }

    var placeBook: PlaceBook
        get() = PlaceBook.decode(p.getString("places", null)).pruned(LocalDate.now())
        set(v) { p.edit().putString("places", v.encode()).apply() }

    /** 운동 계획이 있거나 심박 제안을 켰으면 앱을 열 때 운동 시간을 읽는다(개인정보 문구에 쓴다). */
    fun readsExerciseOnOpen(): Boolean = heartNudge || plan(LocalDateTime.now()) != null

    var eveningReminder: Boolean
        get() = p.getBoolean("eveningReminder", false)
        set(v) { p.edit().putBoolean("eveningReminder", v).apply() }

    var heartNudge: Boolean
        get() = p.getBoolean("heartNudge", false)
        set(v) {
            val e = p.edit().putBoolean("heartNudge", v)
            if (!v) e.remove("nudgeShownAt").remove("nudgeDismissedOn").remove("heartBaseline")
            e.apply()
        }

    var nudgeMemory: NudgeMemory
        get() = NudgeMemory(
            p.getLong("nudgeShownAt", 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli),
            p.getString("nudgeDismissedOn", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        )
        set(v) {
            p.edit().apply {
                if (v.lastShownAt == null) remove("nudgeShownAt") else putLong("nudgeShownAt", v.lastShownAt.toEpochMilli())
                if (v.dismissedOn == null) remove("nudgeDismissedOn") else putString("nudgeDismissedOn", v.dismissedOn.toString())
            }.apply()
        }

    /** '오늘은 닫기': 그날 그 때(출근·퇴근·웜업·운동 뒤)의 카드는 다시 보이지 않는다. 날이 바뀌면 풀린다. */
    fun dismissed(moment: DayMoment, today: LocalDate): Boolean =
        (p.getString("dismissed", "") ?: "").split(',').contains("$today:${moment.name}")
    fun dismiss(moment: DayMoment, today: LocalDate) {
        val kept = (p.getString("dismissed", "") ?: "").split(',').filter { it.startsWith("$today:") }
        p.edit().putString("dismissed", (kept + "$today:${moment.name}").distinct().joinToString(",")).apply()
    }

    /** 생활 전반 쉼 데이터를 모두 지운다(개인정보 화면에서). */
    fun clearAll() { p.edit().clear().apply() }
}
