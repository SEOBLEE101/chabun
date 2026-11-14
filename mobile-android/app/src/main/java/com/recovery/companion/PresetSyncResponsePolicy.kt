package com.recovery.companion

import java.time.Instant

/** Validates decoded JSON before any UI state or pending-request mutation. No Android dependency. */
object PresetSyncResponsePolicy {
    private fun obj(value: Any?): Map<*, *> = value as? Map<*, *> ?: error("Expected object")
    private fun str(value: Any?, max: Int=512): String = (value as? String)?.also { require(it.isNotBlank() && it.length<=max) } ?: error("Expected string")
    private fun bool(value: Any?) { require(value is Boolean) }
    private fun integer(value: Any?, minimum: Long=0): Long {
        val n=(value as? Number)?.toDouble() ?: error("Expected number")
        require(n.isFinite() && n>=minimum && n<=9007199254740991.0 && n==kotlin.math.floor(n)); return n.toLong()
    }
    private fun time(value: Any?) { Instant.parse(str(value,80)) }
    private fun one(value: Any?, vararg allowed: String) { require(value is String && value in allowed) }
    private fun temp(map: Map<*, *>, max: Double) {
        require(map.containsKey("tempC")); val value=map["tempC"] ?: return
        val n=(value as? Number)?.toDouble() ?: error("Expected temperature")
        require(n.isFinite() && n in 18.0..max && n*2==kotlin.math.floor(n*2))
    }
    private fun preset(value: Any?): String {
        val p=obj(value); val id=str(p["id"],80); require(Regex("[a-zA-Z0-9_-]+").matches(id))
        str(p["name"],40); one(p["kind"],"builtin","custom"); one(p["comfort"],"cool_down","quiet_rest","gentle_refresh")
        val car=obj(p["vehicle"]); temp(car,28.0); one(car["fan"],"keep","low","medium","high"); one(car["seat"],"keep","manual_comfort")
        val home=obj(p["home"]); temp(home,30.0); one(home["fan"],"keep","auto","low","medium","high"); one(home["power"],"keep","on")
        return id
    }
    private fun sync(value: Any?) { val s=obj(value); str(s["epoch"],80); str(s["deviceId"],80); one(s["role"],"phone"); time(s["expiresAt"]) }
    private fun state(value: Any?) {
        val s=obj(value); require(s["demo"]==true); integer(s["revision"]); time(s["now"])
        val list=s["presets"] as? List<*> ?: error("Expected presets"); require(list.size in 3..53)
        val ids=list.map(::preset); require(ids.toSet().size==ids.size)
        require(str(s["selectedId"],80) in ids && str(s["defaultId"],80) in ids); bool(s["autoDefaultOnRest"])
        one(s["feeling"],"usual","warm","quiet","tired","cold","skip")
        require(s.containsKey("recommendation") && s.containsKey("plan"))
        s["recommendation"]?.let { val r=obj(it); require(str(r["presetId"],80) in ids); str(r["reason"]) }
        val devices=obj(s["devices"]); val home=obj(devices["home"])
        require(home["id"]=="home-ac"); str(home["name"]); temp(home,30.0); one(home["fan"],"keep","auto","low","medium","high"); one(home["power"],"keep","on","off")
        integer(home["revision"]); bool(home["online"]); str(home["updatedBy"]); time(home["observedAt"])
        val car=obj(devices["vehicle"]); bool(car["parked"]); require(car["simulated"]==true)
        car["proposal"]?.let(::preset); car["confirmedPresetId"]?.let { str(it,80) }
        car["place"]?.let { val p=obj(it); str(p["id"],64); str(p["name"],40) }
        s["plan"]?.let {
            val p=obj(it); str(p["id"],80); integer(p["version"],1); str(p["presetId"],80); str(p["presetName"],40)
            require(preset(p["presetSnapshot"])==p["presetId"]); require(p["targetId"]=="home-ac")
            time(p["createdAt"]); time(p["arrivalAt"]); time(p["executeAt"]); require(integer(p["leadMinutes"],1)<=30)
            one(p["status"],"armed","requested","confirmed_demo","failed","cancelled","expired","needs_review")
            bool(p["enabled"]); integer(p["targetRevision"]); str(p["message"])
            p["requestedAt"]?.let(::time); p["confirmedAt"]?.let(::time)
        }
    }
    private fun appearance(value: Any?) {
        val a = obj(value)
        require(integer(a["schemaVersion"], 1) == 1L)
        val serverTime = Instant.parse(str(a["serverTime"], 80)).toEpochMilli()
        val reports = a["vehicles"] as? List<*> ?: error("Expected vehicle reports")
        require(reports.size <= 128)
        val ids = reports.map { raw ->
            val report = obj(raw)
            val id = str(report["deviceId"], 80)
            one(report["themeId"], "graphite", "paper", "umber") // contract v2: Pearl keeps wire id "paper"
            require(integer(report["paletteVersion"], 1) == 1L)
            integer(report["revision"], 1)
            val reportedAt = Instant.parse(str(report["reportedAt"], 80)).toEpochMilli()
            val expiresAt = Instant.parse(str(report["expiresAt"], 80)).toEpochMilli()
            require(reportedAt <= serverTime && expiresAt >= reportedAt && expiresAt - reportedAt <= 120_000L)
            id
        }
        require(ids.toSet().size == ids.size)
    }
    fun validate(path: String, code: Int, value: Any?, expectedRequestId: String? = null) {
        val body=obj(value)
        if(code !in 200..299) {
            if(body.containsKey("state")) { state(body["state"]); sync(body["sync"]) }
            return
        }
        when(path) {
            "/pairings/claim" -> { str(body["pairingId"],80); val token=str(body["token"],256); require(token.length>=32); require(Regex("[0-9]{4}").matches(str(body["comparisonCode"],4))); time(body["expiresAt"]) }
            "/pairings/status" -> { one(body["status"],"pending","approved"); str(body["pairingId"],80); require(Regex("[0-9]{4}").matches(str(body["comparisonCode"],4))); sync(body["sync"]) }
            "/state", "/commands" -> { state(body["state"]); sync(body["sync"]); if(path=="/commands") require(expectedRequestId!=null && body["acknowledgedRequestId"]==expectedRequestId) }
            "/session" -> require(body["deleted"]==true)
            "/appearance" -> { appearance(body["appearance"]); sync(body["sync"]) }
            "/places/search" -> {
                val list=body["places"] as? List<*> ?: error("Expected places"); require(list.size<=15)
                list.forEach { val p=obj(it); str(p["id"],80); str(p["name"],40); require((p["address"] as? String)?.let { a -> a.length<=120 } ?: false) }
                sync(body["sync"])
            }
            else -> error("Unknown response")
        }
    }
}
