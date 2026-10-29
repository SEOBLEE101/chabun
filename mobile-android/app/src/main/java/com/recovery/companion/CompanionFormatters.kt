package com.recovery.companion

import android.content.Context
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PairingSession(
    val base: String,
    val id: String,
    val token: String,
    val confirmed: Boolean,
    val label: String,
    val expiresAt: String? = null,
    val revocationPending: Boolean = false,
    val revocationStartedAtMillis: Long = 0,
    val revocationDeadlineMillis: Long = 0
)

data class WorkoutRecord(
    val type: String,
    val minutes: Int,
    val end: Instant,
    val source: String,
    val recordId: String? = null,
    val providerPackage: String? = null
)

/** Local-only formatting and pairing/host persistence helpers. */
object CompanionFormatters {
    private val timeFormat = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREA)
        .withZone(ZoneId.systemDefault())

    fun workoutName(type: String): String = when (type) {
        "walking" -> "걷기"
        "running" -> "달리기"
        "cycling" -> "자전거"
        "strength" -> "근력 운동"
        else -> "운동"
    }

    fun comfortLabel(comfort: String): String = when (comfort) {
        "cool_down" -> "↘ 산뜻하게 식히기"
        "quiet_rest" -> "− 조용히 쉬기"
        "gentle_refresh" -> "↗ 부드럽게 기분 전환"
        else -> "− 조용히 쉬기"
    }

    fun providerBadge(source: String, pkg: String?): String = when {
        source == "demo" -> "DEMO · 합성 체험 기록"
        pkg == "com.sec.android.app.shealth" -> "삼성 헬스 (Health Connect)"
        pkg?.contains("fitness") == true -> "Google 피트니스 (Health Connect)"
        source == "health_connect" && !pkg.isNullOrBlank() -> "Health Connect ($pkg)"
        else -> "Health Connect"
    }

    fun relativeEnd(end: Instant, now: Instant = Instant.now()): String {
        val elapsedMin = Duration.between(end, now).toMinutes().coerceAtLeast(0)
        val clock = timeFormat.format(end)
        val rel = when {
            elapsedMin <= 1L -> "방금 종료"
            elapsedMin < 60L -> "${elapsedMin}분 전 종료"
            else -> "${elapsedMin / 60}시간 ${elapsedMin % 60}분 전 종료"
        }
        return "$rel ($clock)"
    }

    fun formatWorkoutPreview(w: WorkoutRecord, now: Instant = Instant.now()): String {
        val badge = providerBadge(w.source, w.providerPackage)
        val note = if (w.source == "demo") {
            "실제 건강정보를 읽거나 저장하지 않는 합성 기록이에요."
        } else {
            "전송 항목: 운동 종류·시간·종료 시각·쉼 선호 (원시 심박·GPS·식별자 제외)"
        }
        return "$badge\n${workoutName(w.type)} · ${w.minutes}분 · ${relativeEnd(w.end, now)}\n$note"
    }

    fun formatPreferenceSnapshot(preset: Preset): String {
        return "이번에 보낼 쉼의 분위기: ${comfortLabel(preset.comfort)}\n‘${preset.name}’에서 고른 분위기만 운동 요약과 함께 보내요. 온도·바람 등 세부 선호는 ‘공유 선호 관리’에서 따로 전달할 수 있어요."
    }

    fun formatShareReadiness(w: WorkoutRecord?, pairingConfirmed: Boolean, consentChecked: Boolean): String {
        val step1 = if (w != null) "✓ 1) 운동 요약 선택됨" else "○ 1) 운동 기록/데모 선택 필요"
        val step2 = if (pairingConfirmed) "✓ 2) 차량 연결 승인됨" else "○ 2) 차량 연결 승인 확인 필요"
        val step3 = if (consentChecked) "✓ 3) 공유 동의 체크됨" else "○ 3) 공유 동의 체크 필요"
        return "$step1\n$step2\n$step3"
    }

    fun saveRelayBase(context: Context, base: String) {
        context.getSharedPreferences("pitin_connection_prefs", Context.MODE_PRIVATE)
            .edit().putString("relay_base", base).apply()
    }

    fun loadRelayBase(context: Context, defaultUrl: String): String =
        context.getSharedPreferences("pitin_connection_prefs", Context.MODE_PRIVATE)
            .getString("relay_base", defaultUrl) ?: defaultUrl

    fun savePairing(context: Context, p: PairingSession?) {
        val prefs = context.getSharedPreferences("pitin_pairing_session", Context.MODE_PRIVATE).edit()
        if (p == null) prefs.clear().apply()
        else prefs.putString("base", p.base).putString("id", p.id).putString("token", p.token)
            .putBoolean("confirmed", p.confirmed).putString("label", p.label)
            .putString("expiresAt", p.expiresAt).putBoolean("revocationPending", p.revocationPending)
            .putLong("revocationStartedAtMillis", p.revocationStartedAtMillis)
            .putLong("revocationDeadlineMillis", p.revocationDeadlineMillis).apply()
    }

    fun loadPairing(context: Context): PairingSession? {
        val prefs = context.getSharedPreferences("pitin_pairing_session", Context.MODE_PRIVATE)
        val base = prefs.getString("base", null) ?: return null
        val id = prefs.getString("id", null) ?: return null
        val token = prefs.getString("token", null) ?: return null
        val confirmed = prefs.getBoolean("confirmed", false)
        val label = prefs.getString("label", "차량 연결 확인 필요") ?: "차량 연결 확인 필요"
        return PairingSession(base, id, token, confirmed, label,
            prefs.getString("expiresAt", null), prefs.getBoolean("revocationPending", false),
            prefs.getLong("revocationStartedAtMillis", 0), prefs.getLong("revocationDeadlineMillis", 0))
    }
}
