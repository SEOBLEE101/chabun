package com.recovery.companion

import android.content.Context
import android.os.SystemClock

/** Local preference and last observed appearance. Network freshness is never restored from disk. */
class AppearancePreferences(context: Context) {
    /**
     * FOLLOW_VEHICLE는 차량 앱이 알린 현재 분위기(paper=Pearl · graphite · umber)를 따른다.
     * PAPER·GRAPHITE·UMBER는 이 폰에만 고정하는 선택지다. 폰은 서버에 분위기를 보내지 않는다.
     */
    enum class Mode { FOLLOW_VEHICLE, PAPER, GRAPHITE, UMBER }
    private val prefs = context.getSharedPreferences("pitin_appearance", Context.MODE_PRIVATE)
    val mode: Mode get() = runCatching { Mode.valueOf(prefs.getString("mode", Mode.FOLLOW_VEHICLE.name)!!) }.getOrDefault(Mode.FOLLOW_VEHICLE)
    val selectionVersion get() = prefs.getLong("selectionVersion", 0)
    val selectedVehicleId: String? get() = prefs.getString("selectedVehicleId", null)
    val lastConfirmedAt get() = prefs.getLong("lastConfirmedAt", 0)
    val effectiveThemeId: String get() = when (mode) {
        Mode.PAPER -> "paper"
        Mode.GRAPHITE -> "graphite"
        Mode.UMBER -> "umber"
        Mode.FOLLOW_VEHICLE -> AppearancePolicy.followedTheme(prefs.getString("lastThemeId", null))
    }
    /** A change here (mode or followed theme) runs the wave transition in MainActivity.updateAppearance. */
    fun signature() = mode.name + ":" + effectiveThemeId
    fun displayName() = AppearancePolicy.displayName(effectiveThemeId)
    fun mood(): Mood = AppearancePolicy.mood(effectiveThemeId)
    /** 차량 앱 보고가 지금 유효한지(프로세스 안의 증거만 인정). */
    fun followIsFresh(): Boolean {
        val proof = receipt
        return mode == Mode.FOLLOW_VEHICLE && proof != null && proof.deviceId == selectedVehicleId &&
            proof.epoch == prefs.getString("lastEpoch", null) && proof.revision == prefs.getLong("lastRevision", 0) &&
            AppearancePolicy.isFresh(proof.receivedElapsed, proof.remaining, SystemClock.elapsedRealtime())
    }
    val reportStatus: String get() = prefs.getString("reportStatus", "checking") ?: "checking"

    fun selectMode(value: Mode) {
        if (mode == value) return
        prefs.edit().putString("mode", value.name).putLong("selectionVersion", selectionVersion + 1).apply()
    }
    fun selectVehicle(deviceId: String?) {
        require(deviceId == null || deviceId.length in 1..80)
        prefs.edit().putString("selectedVehicleId", deviceId).putBoolean("autoSelectSingle", false)
            .putLong("selectionVersion", selectionVersion + 1).putString("reportStatus", if (deviceId == null) "unbound" else "checking").apply()
        receipt = null
    }
    fun markUnavailable(reason: String = "unavailable") {
        prefs.edit().putString("reportStatus", reason).apply()
        receipt = null
    }
    fun accept(packet: AppearancePolicy.Packet, requestStartedElapsed: Long, receivedElapsed: Long) {
        val binding = AppearancePolicy.bind(selectedVehicleId, prefs.getBoolean("autoSelectSingle", true), packet.vehicles.map { it.deviceId })
        val target = binding.deviceId ?: run { markUnavailable(binding.reason); return }
        if (target != selectedVehicleId) prefs.edit().putString("selectedVehicleId", target).apply()
        val report = packet.vehicles.find { it.deviceId == target } ?: run { markUnavailable("unavailable"); return }
        if (!AppearancePolicy.supportedTheme(report.themeId)) { markUnavailable("unavailable"); return }
        if (!AppearancePolicy.accepts(prefs.getString("lastEpoch", null), prefs.getString("lastDeviceId", null),
                prefs.getLong("lastRevision", 0), packet.epoch, report)) {
            markUnavailable("out_of_order"); return
        }
        val remaining = AppearancePolicy.remaining(packet.serverTime, minOf(report.expiresAt, packet.sessionExpiresAt), requestStartedElapsed, receivedElapsed)
        if (remaining == 0L) { markUnavailable("stale"); return }
        prefs.edit().putString("lastThemeId", report.themeId).putString("lastDeviceId", report.deviceId)
            .putString("lastEpoch", packet.epoch).putLong("lastRevision", report.revision)
            .putLong("lastConfirmedAt", System.currentTimeMillis()).putString("reportStatus", "fresh").apply()
        receipt = Receipt(report.deviceId, packet.epoch, report.revision, receivedElapsed, remaining)
    }
    /** 목록 줄에 쓰는 짧은 상태 */
    fun shortStatus(): String = when {
        mode != Mode.FOLLOW_VEHICLE -> "이 폰만 " + displayName()
        followIsFresh() -> "차량 앱을 따라요 · 지금 " + displayName()
        prefs.contains("lastThemeId") -> "차량 앱을 따라요 · 마지막 확인 " + displayName()
        else -> "차량 앱을 따라요 · 확인 전 · " + displayName()
    }
    fun statusText(): String {
        if (mode != Mode.FOLLOW_VEHICLE) return "이 폰만 " + displayName() + " · 차량 앱 화면은 그대로예요"
        val reason = reportStatus
        if (reason == "multiple") return "차량 앱이 여러 대예요 · 따를 차를 골라 주세요"
        if (reason == "unbound") return "따를 차를 고르지 않았어요 · 지금 " + displayName()
        if (followIsFresh()) return "차량 앱을 따라요 · 지금 " + displayName()
        val hasPrevious = prefs.contains("lastThemeId")
        val lead = if (hasPrevious) "마지막으로 확인한 " + displayName() else "차량 앱 확인 전 · Pearl"
        return lead + when (reason) {
            "needs_connection" -> " · 취향 함께 쓰기를 먼저 연결해 주세요"
            "deletion_pending" -> " · 연결 삭제를 확인한 뒤 다시 이어져요"
            "out_of_order" -> " · 순서가 지난 응답이라 바꾸지 않았어요"
            "stale" -> " · 차량 앱의 새 소식을 기다려요"
            "checking" -> " · 확인하고 있어요"
            else -> " · 다시 확인이 필요해요"
        }
    }
    private data class Receipt(val deviceId: String, val epoch: String, val revision: Long, val receivedElapsed: Long, val remaining: Long)
    companion object {
        // A process-local proof cannot accidentally survive an app restart or device reboot.
        private var receipt: Receipt? = null
    }
}
