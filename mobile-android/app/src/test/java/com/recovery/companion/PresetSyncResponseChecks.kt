package com.recovery.companion

/** Malformed decoded JSON successes must be rejected before callers commit state or clear requests. */
object PresetSyncResponseChecks {
    @JvmStatic fun main(args: Array<String>) {
        val timestamp="2026-10-24T00:00:00Z"
        fun preset(id: String): Map<String,Any?> = mapOf("id" to id,"name" to id,"kind" to "builtin","comfort" to "quiet_rest","vehicle" to mapOf("tempC" to 24.0,"fan" to "low","seat" to "keep"),"home" to mapOf("tempC" to 25.0,"fan" to "auto","power" to "on"))
        val home=mapOf("id" to "home-ac","name" to "home","tempC" to 27.0,"fan" to "auto","power" to "off","revision" to 0,"online" to true,"updatedBy" to "fixture","observedAt" to timestamp)
        val devices=mapOf("home" to home,"vehicle" to mapOf("parked" to true,"proposal" to null,"confirmedPresetId" to null,"simulated" to true))
        val state=mapOf("demo" to true,"revision" to 4,"now" to timestamp,"presets" to listOf(preset("quiet_rest"),preset("cool_down"),preset("gentle_refresh")),"selectedId" to "quiet_rest","defaultId" to "quiet_rest","autoDefaultOnRest" to false,"feeling" to "skip","recommendation" to null,"plan" to null,"devices" to devices)
        val sync=mapOf("epoch" to "epoch-a","deviceId" to "phone-a","role" to "phone","expiresAt" to timestamp)
        val response=mapOf("state" to state,"sync" to sync)
        var count=0
        fun verify(condition: Boolean) { check(condition); count++ }
        fun reject(path: String, candidate: Any?, requestId: String?=null) { verify(runCatching { PresetSyncResponsePolicy.validate(path,200,candidate,requestId) }.isFailure) }
        PresetSyncResponsePolicy.validate("/state",200,response); count++
        reject("/state",emptyMap<String,Any?>())
        reject("/state",response + ("state" to null))
        reject("/state",response + ("sync" to emptyMap<String,Any?>()))
        reject("/state",response + ("sync" to (sync + ("role" to "vehicle"))))
        reject("/state",response + ("sync" to (sync + ("expiresAt" to "tomorrow"))))
        val badHome = home + ("online" to "yes")
        val badVehicle = mapOf("tempC" to 99.0,"fan" to "low","seat" to "keep")
        val badPresets = listOf(preset("quiet_rest") + ("vehicle" to badVehicle),preset("cool_down"),preset("gentle_refresh"))
        val badStates=listOf(
            state - "presets",
            state + ("revision" to "4"),
            state + ("revision" to -1),
            state + ("revision" to 4.5),
            state + ("selectedId" to "missing"),
            state + ("autoDefaultOnRest" to "false"),
            state + ("now" to "bad-date"),
            state + ("presets" to listOf(preset("quiet_rest"),preset("quiet_rest"),preset("quiet_rest"))),
            state + ("devices" to (devices + ("home" to badHome))),
            state + ("plan" to mapOf("status" to "armed")),
            state + ("recommendation" to mapOf("reason" to "missing id")),
            state + ("presets" to badPresets)
        )
        badStates.forEach { reject("/state",response+("state" to it)) }
        val claim=mapOf("pairingId" to "pair-a","token" to "a".repeat(43),"comparisonCode" to "0042","expiresAt" to timestamp)
        PresetSyncResponsePolicy.validate("/pairings/claim",200,claim); count++
        reject("/pairings/claim",claim-"token"); reject("/pairings/claim",claim+("token" to 42)); reject("/pairings/claim",claim+("comparisonCode" to 42)); reject("/pairings/claim",claim+("expiresAt" to false))
        reject("/pairings/status",mapOf("status" to "approved"))
        val command=response+("acknowledgedRequestId" to "request-a")
        PresetSyncResponsePolicy.validate("/commands",200,command,"request-a"); count++
        reject("/commands",command,"request-b"); reject("/commands",response,"request-a")
        println("PresetSyncResponseChecks: $count checks passed (invalid decoded success objects, nested field validation, request-ID mismatch)")
    }
}


