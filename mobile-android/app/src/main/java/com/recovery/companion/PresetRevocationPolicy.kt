package com.recovery.companion

/** The retained credential permits an explicit DELETE retry only; it never restores data access. */
object PresetRevocationPolicy {
    const val MAX_RETENTION_MS = 2 * 60 * 60 * 1000L
    enum class Result { DELETED, SESSION_ENDED, RETRY }

    fun deadline(now: Long, sessionExpiresAt: Long): Long =
        minOf(now + MAX_RETENTION_MS, sessionExpiresAt.takeIf { it > 0 } ?: (now + MAX_RETENTION_MS))

    fun canRetry(requestedAt: Long, deadline: Long, now: Long): Boolean =
        requestedAt > 0 && now >= requestedAt && now < deadline && deadline - requestedAt in 1..MAX_RETENTION_MS

    fun result(code: Int?, deleted: Any?): Result = when {
        code != null && code in 200..299 && deleted == true -> Result.DELETED
        code in setOf(401, 404, 410) -> Result.SESSION_ENDED
        else -> Result.RETRY
    }
}
