package com.recovery.companion

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 하루의 '틈'(출근·퇴근·운동 전·운동 뒤)에 맞춘 쉼 제안. 순수 Kotlin(java.time만), 안드로이드 없이 검사한다.
 * 제안만 한다: 카드를 보여 줄 뿐, 쉼 시작·차 움직임은 언제나 사용자가 누른다.
 */
enum class DayMoment { AFTER_WORKOUT, WARMUP, EVENING_COMMUTE, MORNING_COMMUTE, NONE }

val WORKOUT_TYPES = listOf("walking", "running", "cycling", "strength", "other")

data class CommuteSettings(
    val enabled: Boolean = false,
    val morning: LocalTime = LocalTime.of(8, 30),
    val evening: LocalTime = LocalTime.of(18, 30),
    val days: Set<DayOfWeek> = WEEKDAYS,
    val morningPresetId: String = "gentle_refresh",
    val eveningPresetId: String = "quiet_rest",
) {
    fun validated(): CommuteSettings {
        require(!enabled || days.isNotEmpty()) { "요일을 하루 이상 골라 주세요." }
        require(Duration.between(morning, evening).abs() >= Duration.ofHours(2)) { "출근과 퇴근 시각은 두 시간 넘게 떨어져야 해요." }
        require(morningPresetId.isNotBlank() && eveningPresetId.isNotBlank())
        return this
    }
    companion object {
        val WEEKDAYS: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    }
}

/** 그날 하루만 쓰는 운동 계획. 기기 안에만 두고 날짜가 지나면 버린다. */
data class WorkoutPlan(val date: LocalDate, val type: String, val start: LocalTime) {
    fun validated(): WorkoutPlan { require(type in WORKOUT_TYPES) { "알 수 없는 운동 종류예요." }; return this }
    fun startAt(): LocalDateTime = date.atTime(start)
}

data class MomentCard(val moment: DayMoment, val title: String, val body: String, val presetId: String?, val action: String)

object DayRhythmPolicy {
    val MORNING_BEFORE: Duration = Duration.ofMinutes(30)
    val MORNING_AFTER: Duration = Duration.ofMinutes(30)
    val EVENING_BEFORE: Duration = Duration.ofMinutes(15)
    val EVENING_AFTER: Duration = Duration.ofMinutes(60)
    val WARMUP_BEFORE: Duration = Duration.ofMinutes(20)
    val WARMUP_AFTER: Duration = Duration.ofMinutes(10)
    /** 계획 시각보다 이만큼 일찍 끝난 운동까지 그 계획의 운동으로 본다. */
    val WORKOUT_EARLY_SLACK: Duration = Duration.ofMinutes(30)
    val AFTER_WORKOUT_FOR: Duration = Duration.ofMinutes(90)

    /** 계획은 '다음 날 0시'와 '계획 시각 + 3시간' 중 늦은 때까지 산다(밤 운동이 자정을 넘겨 끝나도 운동 뒤 쉼을 받도록). */
    val PLAN_GRACE: Duration = Duration.ofHours(3)
    fun planExpiresAt(plan: WorkoutPlan): LocalDateTime = maxOf(plan.date.plusDays(1).atStartOfDay(), plan.startAt().plus(PLAN_GRACE))
    fun activePlan(plan: WorkoutPlan?, now: LocalDateTime): WorkoutPlan? =
        plan?.takeIf { !now.toLocalDate().isBefore(it.date) && now.isBefore(planExpiresAt(it)) }

    /**
     * 지금이 어떤 때인지. 우선순위: 운동 뒤 > 웜업 > 퇴근 > 출근.
     * [lastWorkoutEnd]는 Health Connect에서 앱이 읽은 가장 최근 운동의 끝 시각(없으면 null).
     */
    fun momentAt(now: LocalDateTime, commute: CommuteSettings, plan: WorkoutPlan?, lastWorkoutEnd: LocalDateTime?): DayMoment {
        val p = plan?.validated()
        if (p != null && lastWorkoutEnd != null && !lastWorkoutEnd.isAfter(now)) {
            val counts = !lastWorkoutEnd.isBefore(p.startAt().minus(WORKOUT_EARLY_SLACK))
            if (counts && now.isBefore(lastWorkoutEnd.plus(AFTER_WORKOUT_FOR))) return DayMoment.AFTER_WORKOUT
        }
        if (p != null && inWindow(now, p.startAt(), WARMUP_BEFORE, WARMUP_AFTER)) {
            val doneAlready = lastWorkoutEnd != null && !lastWorkoutEnd.isBefore(p.startAt().minus(WORKOUT_EARLY_SLACK)) && !lastWorkoutEnd.isAfter(now)
            if (!doneAlready) return DayMoment.WARMUP
        }
        if (!commute.enabled) return DayMoment.NONE
        val c = commute.validated()
        if (commuteHit(now, c, c.evening, EVENING_BEFORE, EVENING_AFTER)) return DayMoment.EVENING_COMMUTE
        if (commuteHit(now, c, c.morning, MORNING_BEFORE, MORNING_AFTER)) return DayMoment.MORNING_COMMUTE
        return DayMoment.NONE
    }

    /** 창은 그 시각이 속한 날의 요일로 판단한다(23:30 퇴근의 00:20은 전날 요일). */
    private fun commuteHit(now: LocalDateTime, c: CommuteSettings, time: LocalTime, before: Duration, after: Duration): Boolean =
        (-1L..1L).any { offset ->
            val anchorDay = now.toLocalDate().plusDays(offset)
            anchorDay.dayOfWeek in c.days && inWindow(now, anchorDay.atTime(time), before, after)
        }

    private fun inWindow(now: LocalDateTime, at: LocalDateTime, before: Duration, after: Duration): Boolean =
        !now.isBefore(at.minus(before)) && now.isBefore(at.plus(after))

    /** 퇴근 알림: 다음 켜진 요일의 퇴근 창 시작(퇴근 15분 전). 지금보다 뒤인 첫 시각, 없으면 null. */
    fun nextReminder(now: LocalDateTime, c: CommuteSettings): LocalDateTime? {
        if (!c.enabled || c.days.isEmpty()) return null
        return (0L..7L).asSequence().map { now.toLocalDate().plusDays(it) }
            .filter { it.dayOfWeek in c.days }
            .map { it.atTime(c.evening).minus(EVENING_BEFORE) }
            .firstOrNull { it.isAfter(now) }
    }

    fun presetAfterWorkout(type: String): String = when (type) {
        "running", "cycling" -> "cool_down"
        "walking" -> "gentle_refresh"
        else -> "quiet_rest"
    }

    fun card(moment: DayMoment, commute: CommuteSettings, plan: WorkoutPlan?): MomentCard? = when (moment) {
        DayMoment.MORNING_COMMUTE -> MomentCard(moment, "출근 전이에요", "출발 전에 3분, 숨을 고르고 갈까요?", commute.morningPresetId, "rest")
        DayMoment.EVENING_COMMUTE -> MomentCard(moment, "퇴근길이에요", "운전하기 전에 3분 쉬고 출발할까요?", commute.eveningPresetId, "rest")
        DayMoment.WARMUP -> plan?.let {
            MomentCard(moment, "곧 ${typeLabel(it.type)} 시간이에요", "몸을 천천히 깨우는 5분 웜업을 해 볼까요?", null, "warmup")
        }
        DayMoment.AFTER_WORKOUT -> plan?.let {
            MomentCard(moment, "운동 마쳤네요", "차에서 3분 쉬어 갈까요? 시작은 직접 눌러요.", presetAfterWorkout(it.type), "rest")
        }
        DayMoment.NONE -> null
    }

    fun typeLabel(type: String): String = when (type) {
        "walking" -> "걷기"; "running" -> "달리기"; "cycling" -> "자전거"; "strength" -> "근력 운동"; else -> "운동"
    }
}
