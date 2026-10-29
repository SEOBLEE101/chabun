package com.recovery.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Only user comfort preferences persist; no health, credentials, home address or ETA. */
class PresetStore(context: Context) {
    private val prefs=context.getSharedPreferences("pitin_comfort_v04",Context.MODE_PRIVATE)
    private val custom=mutableListOf<Preset>()
    var selectedId=prefs.getString("selectedId","quiet_rest") ?: "quiet_rest"; private set
    var defaultId=prefs.getString("defaultId","quiet_rest") ?: "quiet_rest"; private set
    var autoDefault=prefs.getBoolean("autoDefaultOnRest",false); private set
    init {
        try {
            val values=JSONArray(prefs.getString("custom","[]"))
            for(i in 0 until values.length()) {
                try { decode(values.getJSONObject(i)).takeIf { it.kind=="custom" && it.id !in Preset.builtins().map { p->p.id } && custom.none { p->p.id==it.id } }?.let { custom.add(it.validated()) } } catch(_:Exception) { }
            }
        } catch(_:Exception) { }
        if(all().none { it.id==selectedId }) selectedId="quiet_rest"
        if(all().none { it.id==defaultId }) defaultId="quiet_rest"
    }
    fun all(): List<Preset> = Preset.builtins()+custom.toList()
    fun selected(): Preset = all().first { it.id==selectedId }
    fun select(id: String) { require(all().any { it.id==id }); selectedId=id; persist() }
    fun setDefault(id: String, automatic: Boolean) { require(all().any { it.id==id }); defaultId=id; autoDefault=automatic; persist() }
    fun beginRest(): Preset { if(autoDefault) selectedId=defaultId; persist(); return selected() }
    fun save(value: Preset) {
        require(value.kind=="custom" && value.id !in Preset.builtins().map { it.id }) { "기본 프리셋은 복사해 저장해 주세요." }
        val checked=value.validated(); custom.removeAll { it.id==checked.id }; custom.add(checked); selectedId=checked.id; persist()
    }
    fun delete(id: String) {
        require(custom.any { it.id==id }) { "기본 프리셋은 삭제하지 않아요." }
        custom.removeAll { it.id==id }
        if(selectedId==id) selectedId="quiet_rest"
        if(defaultId==id) defaultId="quiet_rest"
        persist()
    }
    private fun persist() { prefs.edit().putString("custom",JSONArray(custom.map { encode(it) }).toString()).putString("selectedId",selectedId).putString("defaultId",defaultId).putBoolean("autoDefaultOnRest",autoDefault).apply() }
    private fun encode(p: Preset)=JSONObject().put("id",p.id).put("name",p.name).put("kind",p.kind).put("comfort",p.comfort)
        .put("vehicle",JSONObject().put("tempC",p.vehicle.tempC ?: JSONObject.NULL).put("fan",p.vehicle.fan).put("seat",p.vehicle.seat))
        .put("home",JSONObject().put("tempC",p.home.tempC ?: JSONObject.NULL).put("fan",p.home.fan).put("power",p.home.power))
    private fun decode(o: JSONObject): Preset {
        val v=o.getJSONObject("vehicle"); val h=o.getJSONObject("home")
        return Preset(o.getString("id"),o.getString("name"),o.getString("kind"),o.getString("comfort"),
            PresetVehicle(if(v.isNull("tempC")) null else v.getDouble("tempC"),v.getString("fan"),v.getString("seat")),
            PresetHome(if(h.isNull("tempC")) null else h.getDouble("tempC"),h.getString("fan"),h.getString("power")))
    }
}
