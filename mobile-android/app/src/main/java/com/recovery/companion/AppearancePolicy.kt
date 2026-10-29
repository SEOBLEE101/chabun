package com.recovery.companion

/** Theme reports describe the 차분 vehicle app only. They never authorize vehicle commands. */
object AppearancePolicy {
    const val REPORT_LIFETIME_MS = 120_000L
    /** Wire ids the vehicle app reports (contract v2). Pearl keeps its stored/server id "paper". */
    val THEME_IDS: Set<String> = setOf("graphite", "paper", "umber")
    fun supportedTheme(themeId: Any?): Boolean = themeId is String && themeId in THEME_IDS
    /** Follow mode: the last confirmed vehicle theme; missing or unknown ids fall back to Pearl ("paper"). */
    fun followedTheme(lastThemeId: String?): String = lastThemeId?.takeIf { supportedTheme(it) } ?: "paper"
    fun displayName(themeId: String): String = when (themeId) { "graphite" -> "Graphite"; "umber" -> "Umber"; else -> "Pearl" }
    fun mood(themeId: String): Mood = when (themeId) { "graphite" -> Mood.GRAPHITE; "umber" -> Mood.UMBER; else -> Mood.PEARL }

    /** Which vehicle report follow mode reads; reason is "ok", "multiple", "unbound" or "unavailable". */
    data class Binding(val deviceId: String?, val reason: String)
    /**
     * An explicit choice is kept even while that vehicle is silent. An automatic choice whose vehicle no
     * longer reports (a re-paired car gets a new device id) is made again the same way: one vehicle is
     * followed, several ask the person to choose.
     */
    fun bind(selected: String?, automatic: Boolean, deviceIds: List<String>): Binding {
        val kept = selected?.takeIf { !automatic || it in deviceIds }
        if (kept != null) return Binding(kept, if (kept in deviceIds) "ok" else "unavailable")
        if (!automatic) return Binding(null, "unbound")
        return when (deviceIds.size) {
            0 -> Binding(null, "unavailable")
            1 -> Binding(deviceIds.single(), "ok")
            else -> Binding(null, "multiple")
        }
    }
    data class Report(
        val deviceId: String, val themeId: String, val paletteVersion: Int,
        val revision: Long, val reportedAt: Long, val expiresAt: Long
    )
    data class Packet(
        val epoch: String, val serverTime: Long, val sessionExpiresAt: Long, val vehicles: List<Report>
    )

    fun accepts(previousEpoch: String?, previousDevice: String?, previousRevision: Long, epoch: String, report: Report): Boolean =
        previousEpoch != epoch || previousDevice != report.deviceId || report.revision >= previousRevision

    /** A conservative duration derived from server time; the phone wall clock does not decide freshness. */
    fun remaining(serverTime: Long, expiresAt: Long, requestStartedElapsed: Long, receivedElapsed: Long): Long {
        if (receivedElapsed < requestStartedElapsed) return 0
        val lifetime = (expiresAt - serverTime).coerceIn(0L, REPORT_LIFETIME_MS)
        return (lifetime - (receivedElapsed - requestStartedElapsed)).coerceAtLeast(0)
    }

    fun isFresh(receivedElapsed: Long, remaining: Long, nowElapsed: Long): Boolean =
        remaining > 0 && nowElapsed >= receivedElapsed && nowElapsed - receivedElapsed < remaining
}
