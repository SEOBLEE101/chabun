package com.recovery.companion

/** Local preference only. No Health Connect, location, network or vehicle writes. */
data class PresetVehicle(val tempC: Double?, val fan: String, val seat: String)
data class PresetHome(val tempC: Double?, val fan: String, val power: String)
data class Preset(val id: String, val name: String, val kind: String, val comfort: String, val vehicle: PresetVehicle, val home: PresetHome) {
    fun validated(): Preset {
        require(id.isNotBlank() && name.trim().length in 1..40 && kind in setOf("builtin", "custom")) { "이름은1~40자로 입력해 주세요." }
        require(comfort in setOf("cool_down", "quiet_rest", "gentle_refresh"))
        fun temperature(value: Double?, maximum: Double) = value == null || (value.isFinite() && value in 18.0..maximum && kotlin.math.abs(value * 2 - kotlin.math.round(value * 2)) < 0.000001)
        require(temperature(vehicle.tempC, 28.0) && temperature(home.tempC, 30.0)) { "온도 범위와0.5도 간격을 확인해 주세요." }
        require(vehicle.fan in setOf("keep", "low", "medium", "high") && vehicle.seat in setOf("keep", "manual_comfort"))
        require(home.fan in setOf("keep", "auto", "low", "medium", "high") && home.power in setOf("keep", "on"))
        return copy(name = name.trim())
    }
    companion object {
        fun builtins() = listOf(
            Preset("cool_down", "산뜻하게 식히기", "builtin", "cool_down", PresetVehicle(23.5,"medium","keep"), PresetHome(25.0,"medium","on")),
            Preset("quiet_rest", "조용히 쉬기", "builtin", "quiet_rest", PresetVehicle(24.0,"low","keep"), PresetHome(25.0,"low","on")),
            Preset("gentle_refresh", "부드럽게 기분 전환", "builtin", "gentle_refresh", PresetVehicle(25.0,"low","manual_comfort"), PresetHome(25.5,"auto","on"))
        )
        fun recommendation(feeling: String, defaultId: String="quiet_rest"): Pair<String?,String> = when(feeling) {
            "warm" -> "cool_down" to "덥다고 직접 선택해 산뜻하게 식히기를 제안해요. 적용·예약은 직접 확인해요."
            "tired" -> "quiet_rest" to "오늘은 조금 지쳤다고 직접 선택해 조용히 쉬기를 제안해요. 건강 진단이나 기록 분석은 아니에요."
            "quiet" -> "quiet_rest" to "조용히 쉬고 싶다는 선택에 맞춘 제안이에요."
            "cold" -> "gentle_refresh" to "춥다는 선택에 맞춰 부드러운 바람을 검토해 보세요. 건강 판단은 아니에요."
            "skip" -> null to "추천을 건너뛰었어요. 원하는 선호를 직접 골라 주세요."
            else -> defaultId to "평소처럼은 저장한 기본 선호를 제안해요. 적용과 예약은 별도로 확인해요."
        }
    }
}

data class PresetHomeState(val tempC: Double=25.0, val fan: String="auto", val power: String="off", val revision: Long=0, val online: Boolean=true, val observedAt: Long=0, val updatedBy: String="initial_demo")
data class PresetArrivalPlan(val id: String, val version: Int, val snapshot: Preset, val target: String="home-ac", val arrivalAt: Long, val executeAt: Long, val leadMinutes: Int, val targetRevision: Long, val enabled: Boolean=true, val consent: Boolean=true, val status: String="armed", val requestedAt: Long?=null)

class PresetSession(initialClock: Long=System.currentTimeMillis()) {
    var clock = initialClock; private set
    var home = PresetHomeState(observedAt=initialClock); private set
    var plan: PresetArrivalPlan? = null; private set
    private val executedIds = mutableSetOf<String>()
    fun arm(preset: Preset, eta: Int, lead: Int) {
        require(plan?.status !in setOf("armed","requested")) { "이미 켠 예약을 먼저 수정하거나 취소해 주세요." }
        require(eta in 1..180 && lead in 1..30 && eta > lead) { "도착은1~180분, 미리 준비는1~30분이며 도착까지 시간이 더 길어야 해요." }
        plan = PresetArrivalPlan(java.util.UUID.randomUUID().toString(), (plan?.version ?: 0)+1,
            preset.validated().copy(vehicle=preset.vehicle.copy(), home=preset.home.copy()), arrivalAt=clock+eta*60000L,
            executeAt=clock+(eta-lead)*60000L, leadMinutes=lead, targetRevision=home.revision)
    }
    fun update(eta: Int, lead: Int) {
        val old=plan ?: throw IllegalArgumentException("수정할 예약이 없어요.")
        require(old.status in setOf("armed","needs_review","failed","expired") && old.requestedAt==null) { "이미 요청한 예약은 수정하지 않아요. 새 예약을 확인해 주세요." }
        require(eta in 1..180 && lead in 1..30 && eta>lead) { "도착과 미리 준비 시간을 확인해 주세요." }
        plan=old.copy(version=old.version+1,arrivalAt=clock+eta*60000L,executeAt=clock+(eta-lead)*60000L,leadMinutes=lead,targetRevision=home.revision,enabled=true,consent=true,status="armed")
    }
    fun cancel() { plan=plan?.copy(enabled=false,status="cancelled") }
    fun manualOverride(tempC: Double) {
        require(tempC.isFinite() && tempC in 18.0..30.0 && tempC*2==kotlin.math.round(tempC*2))
        home=home.copy(tempC=tempC,revision=home.revision+1,observedAt=clock,updatedBy="manual_demo")
        plan?.takeIf { it.status in setOf("armed","requested") }?.let { plan=it.copy(enabled=false,status="needs_review") }
    }
    fun setOnline(online: Boolean) { home=home.copy(online=online) }
    fun advanceToExecution() { val p=plan ?: return; advance((p.executeAt-clock).coerceAtLeast(0)) }
    fun advance(milliseconds: Long) {
        require(milliseconds >= 0 && milliseconds <= 180*60000L)
        clock += milliseconds
        val p=plan ?: return
        if (!p.enabled || !p.consent || p.status!="armed" || clock < p.executeAt || "${p.id}:${p.version}" in executedIds) return
        if (clock-p.executeAt > 60000L) { plan=p.copy(enabled=false,status="expired"); return }
        if (home.revision != p.targetRevision) { plan=p.copy(enabled=false,status="needs_review"); return }
        if (!home.online) { plan=p.copy(enabled=false,status="failed"); return }
        executedIds.add("${p.id}:${p.version}")
        plan=p.copy(enabled=false,status="requested",requestedAt=clock)
    }
    fun confirmObserved() {
        val p=plan ?: return
        require(p.status=="requested") { "아직 상태를 확인할 요청이 없어요." }
        if (!home.online) { plan=p.copy(status="failed"); return }
        if (home.revision != p.targetRevision) { plan=p.copy(status="needs_review"); return }
        val h=p.snapshot.home
        home=home.copy(tempC=h.tempC ?: home.tempC,fan=if(h.fan=="keep") home.fan else h.fan,power=if(h.power=="keep") home.power else h.power,revision=home.revision+1,observedAt=clock,updatedBy="arrival_demo")
        plan=p.copy(enabled=false,status="confirmed_demo")
    }
}
