package com.recovery.companion

import android.content.Context
import org.json.JSONObject

/**
 * 차 안 설정(시트·에어컨·조명) — 쉼마다 이 폰에 저장하는 값이다. 폰은 차에 직접 쓰지 않는다.
 * ‘차에 이 쉼 보내기’로 쉼과 함께 차에 전해지고, 차 앱이 주차 중 사용자가 시작할 때 적용한다.
 * ‘그대로’는 지금 값이 아니라 ‘이 쉼은 이 항목을 바꾸지 않음’이다.
 */
data class SeatPref(val position: String = "keep", val comfort: String = "keep", val level: Int = 1) {
    fun valid() = position in setOf("keep", "manual") && comfort in setOf("keep", "off", "heat", "vent") && level in 1..3
}
data class ClimatePref(val mode: String = "keep", val temp: Double = 24.0, val fan: String = "auto", val dir: String = "keep", val ac: String = "keep") {
    fun valid() = mode in setOf("keep", "custom") && temp in 18.0..28.0 && (temp * 2) % 1.0 == 0.0 &&
        fan in setOf("auto", "low", "medium", "high") && dir in setOf("keep", "upper", "lower", "both") && ac in setOf("keep", "on", "off")
}
data class LightPref(val mode: String = "keep", val color: String = "cream", val bright: Int = 60) {
    fun valid() = mode in setOf("keep", "off", "custom") && color in setOf("cream", "neutral", "sage", "mist") && bright in 10..100
}
data class CabinPrefs(
    val seatDriver: SeatPref = SeatPref(), val seatPassenger: SeatPref = SeatPref(),
    val climDriver: ClimatePref = ClimatePref(), val climPassenger: ClimatePref = ClimatePref(),
    val light: LightPref = LightPref()
) {
    fun seat(target: String) = if (target == "driver") seatDriver else seatPassenger
    fun climate(target: String) = if (target == "driver") climDriver else climPassenger
    fun withSeat(target: String, v: SeatPref) = if (target == "driver") copy(seatDriver = v) else copy(seatPassenger = v)
    fun withClimate(target: String, v: ClimatePref) = if (target == "driver") copy(climDriver = v) else copy(climPassenger = v)
    fun changedFrom(o: CabinPrefs) = listOf(seatDriver != o.seatDriver, seatPassenger != o.seatPassenger, climDriver != o.climDriver, climPassenger != o.climPassenger, light != o.light).count { it }

    companion object {
        val FAN = mapOf("auto" to "자동", "low" to "약하게", "medium" to "중간", "high" to "세게")
        val DIR = mapOf("keep" to "방향 그대로", "upper" to "위로", "lower" to "아래로", "both" to "위아래")
        /** 이름은 시안 그대로, 색은 ‘화면 속 예시 색’ */
        val COLORS = linkedMapOf("cream" to ("따뜻한 빛" to "#E8CFA6"), "neutral" to ("차분한 빛" to "#E8E6DE"), "sage" to ("올리브" to "#A5B198"), "mist" to ("옅은 블루" to "#A4BBC9"))
        fun seatText(x: SeatPref): String {
            val p = mutableListOf<String>()
            when (x.comfort) { "off" -> p += "열선·통풍 끄기"; "heat" -> p += "열선 ${x.level}단계"; "vent" -> p += "통풍 ${x.level}단계" }
            if (x.position == "manual") p += "자세는 차에서 직접"
            return if (p.isEmpty()) "그대로" else p.joinToString(" · ")
        }
        fun climateText(x: ClimatePref): String {
            if (x.mode == "keep") return "그대로"
            val p = mutableListOf(String.format(java.util.Locale.US, "%.1f°", x.temp), "바람 " + FAN.getValue(x.fan))
            if (x.dir != "keep") p += DIR.getValue(x.dir)
            if (x.ac != "keep") p += if (x.ac == "on") "냉방 켜기" else "냉방 끄기"
            return p.joinToString(" · ")
        }
        fun lightText(x: LightPref) = when (x.mode) { "keep" -> "그대로"; "off" -> "끄기"; else -> COLORS.getValue(x.color).first + " · 밝기 ${x.bright}%" }
    }
}

class CabinStore(context: Context) {
    private val prefs = context.getSharedPreferences("pitin_cabin_prefs", Context.MODE_PRIVATE)
    fun saved(presetId: String): CabinPrefs = runCatching { decode(JSONObject(prefs.getString("rest:$presetId", null) ?: return CabinPrefs())) }.getOrDefault(CabinPrefs())
    fun save(presetId: String, value: CabinPrefs) {
        require(value.seatDriver.valid() && value.seatPassenger.valid() && value.climDriver.valid() && value.climPassenger.valid() && value.light.valid())
        prefs.edit().putString("rest:$presetId", encode(value).toString()).apply()
    }
    fun clearAll() = prefs.edit().clear().apply()
    /** 취향 탭 ‘차 안 설정’ 줄 아래 요약 */
    fun summary(presetId: String): String {
        val c = saved(presetId)
        val highlights = mutableListOf<String>()
        if (c.seatDriver.comfort in setOf("heat", "vent")) highlights += "운전석 " + (if (c.seatDriver.comfort == "heat") "열선 " else "통풍 ") + "${c.seatDriver.level}단계"
        if (c.climDriver.mode == "custom") highlights += String.format(java.util.Locale.US, "%.1f°", c.climDriver.temp)
        if (c.light.mode == "custom") highlights += CabinPrefs.COLORS.getValue(c.light.color).first
        return "시트 · 에어컨 · 조명 · " + if (highlights.isEmpty()) "모두 그대로" else highlights.take(2).joinToString(" · ")
    }
    private fun encode(c: CabinPrefs) = toJson(c)

    companion object {
        /** Also the cabin contract v1 sent with a rest to the car (propose_rest); "keep" = this rest leaves it alone. */
        fun toJson(c: CabinPrefs): JSONObject = JSONObject().put("seatDriver", seatJ(c.seatDriver)).put("seatPassenger", seatJ(c.seatPassenger))
            .put("climDriver", climJ(c.climDriver)).put("climPassenger", climJ(c.climPassenger))
            .put("light", JSONObject().put("mode", c.light.mode).put("color", c.light.color).put("bright", c.light.bright))
        private fun seatJ(s: SeatPref) = JSONObject().put("position", s.position).put("comfort", s.comfort).put("level", s.level)
        private fun climJ(c: ClimatePref) = JSONObject().put("mode", c.mode).put("temp", c.temp).put("fan", c.fan).put("dir", c.dir).put("ac", c.ac)
    }
    private fun seat(o: JSONObject) = SeatPref(o.getString("position"), o.getString("comfort"), o.getInt("level")).takeIf { it.valid() } ?: SeatPref()
    private fun clim(o: JSONObject) = ClimatePref(o.getString("mode"), o.getDouble("temp"), o.getString("fan"), o.getString("dir"), o.getString("ac")).takeIf { it.valid() } ?: ClimatePref()
    private fun decode(o: JSONObject) = CabinPrefs(seat(o.getJSONObject("seatDriver")), seat(o.getJSONObject("seatPassenger")),
        clim(o.getJSONObject("climDriver")), clim(o.getJSONObject("climPassenger")),
        o.getJSONObject("light").let { LightPref(it.getString("mode"), it.getString("color"), it.getInt("bright")).takeIf { l -> l.valid() } ?: LightPref() })
}
