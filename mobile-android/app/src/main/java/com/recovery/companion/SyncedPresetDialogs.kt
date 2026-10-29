package com.recovery.companion

import android.app.Activity
import android.graphics.Color
import android.text.InputType
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import org.json.JSONObject
import java.time.Instant

/** Sheet drafts (PitSheet) retain their opening epoch/revision; transport stays in the Activity. */
internal class SyncedPresetDialogs(
    private val activity: Activity,
    private val readState: () -> JSONObject?,
    private val readEpoch: () -> String?,
    private val writable: () -> Boolean,
    private val submit: (String, JSONObject, String?, Long, (() -> Unit)?, (() -> Unit)?) -> Unit
) {
    private val state get() = readState()
    private val epoch get() = readEpoch()
    private val theme by lazy { PhoneTheme(activity) }
    private val ink get() = theme.ink
    private fun revision() = state?.optLong("revision", -1) ?: -1
    private fun json(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { put(it.first,it.second ?: JSONObject.NULL) } }
    private fun send(action: String, payload: JSONObject, atEpoch: String?, atRevision: Long, success: (() -> Unit)? = null, conflict: (() -> Unit)? = null) {
        submit(action, payload, atEpoch, atRevision, success, conflict)
    }
    fun values(p: JSONObject): String {
        val car=p.getJSONObject("vehicle"); val home=p.getJSONObject("home")
        fun temp(o: JSONObject)=if(o.isNull("tempC")) "현재대로" else "${o.opt("tempC")}°C"
        fun fan(value: String)=mapOf("keep" to "유지","low" to "약","medium" to "중","high" to "강","auto" to "자동")[value] ?: value
        return "차 ${temp(car)} · 바람 ${fan(car.getString("fan"))} · 시트 ${if(car.getString("seat")=="keep") "유지" else "직접 조절"}\n집 ${temp(home)} · 바람 ${fan(home.getString("fan"))} · 전원 ${if(home.getString("power")=="on") "켜기 선호" else "유지"}"
    }
    fun confirmDelete(p: JSONObject) {
        val openingEpoch=epoch; val openingRevision=revision()
        val sheet = PitSheet(activity, "이 쉼을 지울까요?", "${p.getString("name")}을 지워요. 이미 켜 둔 예약의 복사본은 그대로 남아요.")
        sheet.buttons("취소", "지우기", danger = true) { sheet.dismiss(); send("delete_preset",json("id" to p.getString("id")),openingEpoch,openingRevision) }.show()
    }
    fun edit(p: JSONObject, existing: Boolean) {
        val form = form()
        val name=input(form,"이름",p.getString("name")+if(existing) "" else " 내 설정")
        val comfort=choice(form,"분위기",listOf("cool_down","quiet_rest","gentle_refresh"),p.getString("comfort"))
        val car=p.getJSONObject("vehicle"); val home=p.getJSONObject("home")
        val carTemp=input(form,"차 온도 · 18~28°, 0.5° 단위 · 비우면 그대로",if(car.isNull("tempC")) "" else car.getString("tempC"),true)
        val carFan=choice(form,"차 바람",listOf("keep","low","medium","high"),car.getString("fan"))
        val seat=choice(form,"시트 직접 조절 안내",listOf("keep","manual_comfort"),car.getString("seat"))
        val homeTemp=input(form,"집 온도 · 18~30°, 0.5° 단위 · 비우면 그대로",if(home.isNull("tempC")) "" else home.getString("tempC"),true)
        val homeFan=choice(form,"집 바람",listOf("keep","auto","low","medium","high"),home.getString("fan"))
        val power=choice(form,"집 전원",listOf("keep","on"),home.getString("power"))
        draftDialog(if (existing) "이 쉼 고치기" else "새 쉼 만들기",form,"save_preset") {
            fun temp(field: EditText, maximum: Double): Any {
                val raw=field.text.toString().trim(); if(raw.isEmpty()) return JSONObject.NULL
                val n=raw.toDouble(); require(n.isFinite() && n in 18.0..maximum && n*2 == kotlin.math.floor(n*2)) { "온도 범위와 0.5도 단위를 확인해 주세요." }; return n
            }
            val title=name.text.toString().trim(); require(title.isNotEmpty() && title.length<=40) { "이름은 1~40자로 입력해 주세요." }
            json("name" to title,"comfort" to selectedValue(comfort),"vehicle" to json("tempC" to temp(carTemp,28.0),"fan" to selectedValue(carFan),"seat" to selectedValue(seat)),"home" to json("tempC" to temp(homeTemp,30.0),"fan" to selectedValue(homeFan),"power" to selectedValue(power))).apply { if(existing) put("id",p.getString("id")) }
        }
    }
    fun arrival(p: JSONObject, update: Boolean) {
        val original = if (update) state?.optJSONObject("plan") else null
        val initialEta = original?.let {
            runCatching { kotlin.math.ceil((Instant.parse(it.getString("arrivalAt")).toEpochMilli() - Instant.parse(state!!.getString("now")).toEpochMilli()) / 60000.0).toInt().coerceIn(2, 180) }.getOrDefault(20)
        } ?: 20
        val form=form(); val eta=input(form,"도착까지 남은 시간 · 2~180분",initialEta.toString(),true); val lead=input(form,"도착 전에 미리 켤 시간 · 1~30분",(original?.optInt("leadMinutes", 5) ?: 5).toString(),true)
        val consent=theme.check(form,"이번 한 번, 예시 집 에어컨 한 대의 합성 예약에 동의해요.")
        val snapshot=if(update) state?.optJSONObject("plan")?.optJSONObject("presetSnapshot") ?: p else p
        fun snapshotDescription(value: JSONObject) = "예약 복사본 · ${value.getString("name")}\n${values(value)}\n요청 접수와 관측 확인은 별개예요. 취소해도 기기 원복이 아니에요."
        val snapshotLabel = theme.text(form,snapshotDescription(snapshot),15f)
        theme.text(form,"시간이나 최신 선호를 다시 검토하면 동의를 새로 선택해 주세요.",14f,color=theme.muted)
        listOf(eta, lead).forEach { field -> field.doAfterTextChanged { consent.isChecked = false } }
        fun refreshSnapshot() {
            val current = if (update) state?.optJSONObject("plan")?.optJSONObject("presetSnapshot") else {
                val presets = state?.optJSONArray("presets")
                (0 until (presets?.length() ?: 0)).mapNotNull { presets?.optJSONObject(it) }.find { it.optString("id") == p.optString("id") }
            }
            snapshotLabel.text = current?.let(::snapshotDescription) ?: "선택한 선호가 목록에서 사라졌어요. 창을 닫고 선호를 다시 선택해 주세요."
        }
        draftDialog(if(update) "원래 예약 시간 수정" else "이번 귀가 예약",form,if(update) "update_arrival" else "arm_arrival",reviewConsent=consent,onReviewed=::refreshSnapshot) {
            require(consent.isChecked) { "이번 예약 동의를 직접 선택해 주세요." }
            val e=eta.text.toString().toInt(); val l=lead.text.toString().toInt()
            require(e in 1..180 && l in 1..30 && e>l) { "도착 시간은 준비 시간보다 커야 해요." }
            json("etaMinutes" to e,"leadMinutes" to l,"consent" to true).apply { if(!update) { put("presetId",p.getString("id")); put("target","home-ac") } }
        }
    }
    private fun draftDialog(title: String, form: LinearLayout, action: String, reviewConsent: CheckBox? = null, onReviewed: (() -> Unit)? = null, payload: () -> JSONObject) {
        var baseEpoch=epoch; var baseRevision=revision(); var needsReview=false
        val openingPlanId=state?.optJSONObject("plan")?.optString("id")
        val openingPlanVersion=state?.optJSONObject("plan")?.optInt("version")
        val error = theme.text(form, "", 14f, color = theme.danger).apply {
            visibility = android.view.View.GONE
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        fun problem(message: String) { error.text = message; error.visibility = android.view.View.VISIBLE }
        val sheet = PitSheet(activity, title, body = form)
        sheet.buttons("닫기", "확인하고 저장") save@{
            if (!writable()) { problem("연결 또는 앞선 요청 결과를 먼저 확인해 주세요. 입력한 내용은 유지돼요."); return@save }
            error.visibility = android.view.View.GONE
            if(action == "update_arrival" && (state?.optJSONObject("plan")?.optString("id") != openingPlanId || state?.optJSONObject("plan")?.optInt("version") != openingPlanVersion)) { problem("원래 예약이 변경됐어요. 이 초안을 닫고 현재 예약을 새로 검토해 주세요."); return@save }
            if(needsReview) {
                if(!writable()) { problem("먼저 연결 상태와 미확인 요청을 확인해 주세요."); return@save }
                val reviewedEpoch=epoch; val reviewedRevision=revision()
                val review = PitSheet(activity, "최신 상태 다시 검토", "${reviewState()}\n다른 화면의 변경을 확인했나요? 입력한 내용은 유지돼요. 다시 제출하려면 확인 후 제출을 한 번 더 눌러 주세요.")
                review.buttons("계속 검토", "최신 상태 확인") { review.dismiss(); baseEpoch=reviewedEpoch; baseRevision=reviewedRevision; needsReview=false; reviewConsent?.isChecked=false; onReviewed?.invoke(); sheet.positive.text="확인하고 저장" }.show()
            } else try {
                send(action,payload(),baseEpoch,baseRevision,success={ sheet.dismiss() },conflict={ needsReview=true; reviewConsent?.isChecked=false; sheet.positive.text="충돌 · 최신 상태 다시 검토"; problem("다른 화면에서 변경됐어요. 초안은 유지했어요.") })
            } catch (e: Exception) {
                problem(if (e is NumberFormatException) "숫자와 입력 범위를 확인해 주세요." else e.message ?: "입력값과 동의를 확인해 주세요.")
            }
        }.show()
    }
    private fun reviewState(): String {
        val s=state ?: return "서버 상태를 먼저 읽어 주세요."
        val presets=s.getJSONArray("presets")
        return buildString {
            append("현재 revision ${revision()} · 선택 ${s.optString("selectedId")}\n")
            for(i in 0 until presets.length()) { val p=presets.getJSONObject(i); append("${p.getString("name")}\n${values(p)}\n") }
            s.optJSONObject("plan")?.let { append("예약 v${it.optInt("version")} ${it.optString("status")} · ${it.optString("presetName")}\n") }
        }
    }
    /** The sheet already draws the surface; the form adds no box of its own. */
    private fun form() = theme.column().apply { setPadding(0, 0, 0, 0) }
    fun input(parent: LinearLayout, title: String, value: String, numeric: Boolean = false): EditText =
        theme.input(parent, title, value, numeric)
    private fun choice(parent: LinearLayout, title: String, values: List<String>, selected: String): Spinner =
        theme.spinner(parent, title, values, selected, ::optionName)
    @Suppress("UNCHECKED_CAST")
    private fun selectedValue(spinner: Spinner): String = (spinner.tag as List<String>)[spinner.selectedItemPosition]
    private fun optionName(value: String) = mapOf(
        "cool_down" to "산뜻하게 식히기", "quiet_rest" to "조용히 쉬기", "gentle_refresh" to "부드럽게 기분 전환",
        "keep" to "현재 설정 유지", "low" to "약하게", "medium" to "중간", "high" to "강하게", "auto" to "자동",
        "manual_comfort" to "편한 자세로 직접 조절", "on" to "켜기 선호"
    )[value] ?: value
    private fun dp(value: Int)=(value*activity.resources.displayMetrics.density).toInt()
    private fun toast(message: String) { Toast.makeText(activity,message,Toast.LENGTH_LONG).show() }
}
