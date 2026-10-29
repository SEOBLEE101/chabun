package com.recovery.companion

/** Covers refusal, lost responses, expiry and process restoration without an Android runtime. */
object PresetRevocationChecks {
    fun run(): Int {
        var checks = 0
        fun verify(value: Boolean) { check(value); checks++ }
        val now = 1_800_000_000_000L
        val deadline = PresetRevocationPolicy.deadline(now, 0)
        verify(deadline == now + 7_200_000)
        verify(PresetRevocationPolicy.deadline(now, now + 300_000) == now + 300_000)
        verify(PresetRevocationPolicy.deadline(now, now + 30_000_000) == deadline)
        verify(PresetRevocationPolicy.canRetry(now, deadline, now))
        verify(PresetRevocationPolicy.canRetry(now, deadline, deadline - 1))
        verify(!PresetRevocationPolicy.canRetry(now, deadline, deadline))
        verify(!PresetRevocationPolicy.canRetry(now, deadline, now - 1))
        verify(!PresetRevocationPolicy.canRetry(now, deadline + 1, now))
        verify(!PresetRevocationPolicy.canRetry(0, deadline, now))
        verify(PresetRevocationPolicy.result(200, true) == PresetRevocationPolicy.Result.DELETED)
        listOf(null, false, "true", 1).forEach {
            verify(PresetRevocationPolicy.result(200, it) == PresetRevocationPolicy.Result.RETRY)
        }
        listOf(null, 403, 408, 429, 500, 503).forEach {
            verify(PresetRevocationPolicy.result(it, true) == PresetRevocationPolicy.Result.RETRY)
        }
        listOf(401, 404, 410).forEach {
            verify(PresetRevocationPolicy.result(it, false) == PresetRevocationPolicy.Result.SESSION_ENDED)
        }
        return checks
    }
}
