package com.recovery.companion

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 워치 심박으로 쉼을 '제안'할지 정한다(사용자가 켠 경우만). 판정·진단·점수는 없다.
 * 기준값은 그 사람 자신의 최근 기록(운동 구간 제외)의 낮은 쪽 값이다. 원본 기록은 저장하지 않는다.
 */
data class HrSample(val time: Instant, val bpm: Int)
data class Span(val start: Instant, val end: Instant)
data class NudgeMemory(val lastShownAt: Instant? = null, val dismissedOn: LocalDate? = null)

enum class NudgeResult { SUGGEST, OFF, NO_BASELINE, DISMISSED_TODAY, COOLDOWN, EXERCISE, NOT_ENOUGH_DATA, NOT_ELEVATED }

object HeartNudgePolicy {
    val BASELINE_PERIOD: Duration = Duration.ofDays(7)
    const val BASELINE_MIN_SAMPLES = 200
    const val BASELINE_PERCENTILE = 20
    const val RISE_BPM = 25
    val WINDOW: Duration = Duration.ofMinutes(10)
    /** 창 안 첫 기록이 적어도 이만큼 전이어야 '이어졌다'고 본다. */
    val MIN_SPAN: Duration = Duration.ofMinutes(8)
    const val WINDOW_MIN_SAMPLES = 5
    const val ELEVATED_SHARE = 0.7
    /** 운동이 끝난 뒤 이 시간 동안의 심박은 운동 때문일 수 있어 쓰지 않는다. */
    val AFTER_EXERCISE: Duration = Duration.ofMinutes(60)
    val COOLDOWN: Duration = Duration.ofHours(3)
    val PLAUSIBLE = 30..220

    const val MESSAGE = "심박이 평소보다 높게 이어졌어요. 잠깐 쉬어 볼까요?"
    const val NOTE = "의료기기가 아니에요. 몸 상태를 판단하지 않고, 쉬어 갈 때를 알려 드릴 뿐이에요."

    private fun nearExercise(t: Instant, exercises: List<Span>): Boolean =
        exercises.any { !t.isBefore(it.start) && t.isBefore(it.end.plus(AFTER_EXERCISE)) }

    /** 최근 7일, 운동 중·운동 뒤 60분을 뺀 기록의 20번째 백분위(가까운 순위). 기록이 모자라면 null. */
    fun baseline(samples: List<HrSample>, exercises: List<Span>, now: Instant): Int? {
        val from = now.minus(BASELINE_PERIOD)
        val values = samples.asSequence()
            .filter { !it.time.isBefore(from) && it.time.isBefore(now) && it.bpm in PLAUSIBLE && !nearExercise(it.time, exercises) }
            .map { it.bpm }.sorted().toList()
        if (values.size < BASELINE_MIN_SAMPLES) return null
        val rank = Math.ceil(BASELINE_PERCENTILE / 100.0 * values.size).toInt().coerceIn(1, values.size)
        return values[rank - 1]
    }

    fun decide(
        enabled: Boolean, samples: List<HrSample>, exercises: List<Span>, baseline: Int?,
        memory: NudgeMemory, now: Instant, zone: ZoneId,
    ): NudgeResult {
        if (!enabled) return NudgeResult.OFF
        if (baseline == null) return NudgeResult.NO_BASELINE
        if (memory.dismissedOn == now.atZone(zone).toLocalDate()) return NudgeResult.DISMISSED_TODAY
        memory.lastShownAt?.let { if (now.isBefore(it.plus(COOLDOWN)) && !it.isAfter(now)) return NudgeResult.COOLDOWN }
        val from = now.minus(WINDOW)
        if (exercises.any { it.start.isBefore(now) && it.end.plus(AFTER_EXERCISE).isAfter(from) }) return NudgeResult.EXERCISE
        val window = samples.filter { !it.time.isBefore(from) && !it.time.isAfter(now) && it.bpm in PLAUSIBLE }.sortedBy { it.time }
        if (window.size < WINDOW_MIN_SAMPLES || window.first().time.isAfter(now.minus(MIN_SPAN))) return NudgeResult.NOT_ENOUGH_DATA
        val threshold = baseline + RISE_BPM
        val sorted = window.map { it.bpm }.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2].toDouble() else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        val share = window.count { it.bpm >= threshold }.toDouble() / window.size
        return if (median >= threshold && share >= ELEVATED_SHARE) NudgeResult.SUGGEST else NudgeResult.NOT_ELEVATED
    }
}
