package com.recovery.companion

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

/** Authoritative connected collection; shares host with configured relay while keeping separate auth/port. */
class SyncedPresetsActivity : Activity() {
    private val dialogs by lazy { SyncedPresetDialogs(this, { state }, { epoch }, ::writable, ::send) }
    private lateinit var client: PresetSyncClient
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var token: String? = null
    private var approved = false
    private var foreground = false
    private var online = false
    private var busy = false
    private var checking = false
    private var revocationRequestedAt = 0L
    private var revocationDeadline = 0L
    private var sessionExpiresAt = 0L
    private var lastServerConfirmedAt = 0L
    private val revocationPending get() = revocationRequestedAt > 0L
    private var demoExpanded = false
    private var technicalExpanded = false
    private var feelingsExpanded = false
    private var arrivalExpanded = false
    private var managementExpanded = false
    private var generation = 0L
    private var epoch: String? = null
    private var state: JSONObject? = null
    private var pending: JSONObject? = null
    private var pendingSuccess: (() -> Unit)? = null
    private var pendingConflict: (() -> Unit)? = null
    private var comparison = ""
    private var presetOrigin = "https://127.0.0.1:9447"
    private var note = "웹에서 폰용 연결 번호를 만든 뒤 입력해 주세요."
    private lateinit var column: LinearLayout
    private lateinit var scroll: ScrollView
    private val theme by lazy { PhoneTheme(this) }
    private val ink get() = theme.ink
    private val paper get() = theme.paper
    private val poll = object : Runnable {
        override fun run() { if (foreground) { refresh(); handler.postDelayed(this, 2000) } }
    }
    /** A rest from 취향 waiting to be sent to the car with its 차 안 설정 (‘차에 이 쉼 보내기’). */
    private var sendRest: JSONObject? = null

    companion object {
        const val EXTRA_SEND_REST = "sendRest"

        /** The propose_rest payload: the rest as this phone shows it, with its 차 안 설정. No workout data. */
        fun restProposal(p: Preset, cabin: CabinPrefs): JSONObject = JSONObject()
            .put("name", p.name).put("comfort", p.comfort)
            .put("vehicle", JSONObject().put("tempC", p.vehicle.tempC ?: JSONObject.NULL).put("fan", p.vehicle.fan).put("seat", p.vehicle.seat))
            .put("cabin", CabinStore.toJson(cabin))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val relayBase = intent?.getStringExtra("relayOrigin")
            ?: CompanionFormatters.loadRelayBase(this, getString(R.string.relay_default_url))
        presetOrigin = PresetSyncPolicy.fromRelayOrigin(relayBase)
        client = PresetSyncClient(presetOrigin)
        sendRest = intent?.getStringExtra(EXTRA_SEND_REST)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val prefs = getSharedPreferences("pitin_preset_sync_session", MODE_PRIVATE)
        if ( prefs.getString("origin", null) == presetOrigin) {
            token = prefs.getString("token", null)
            approved = prefs.getBoolean("approved", false)
            comparison = prefs.getString("comparison", "") ?: ""
            sessionExpiresAt = prefs.getLong("expiresAt", 0L)
            revocationRequestedAt = prefs.getLong("revocationRequestedAt", 0L)
            revocationDeadline = prefs.getLong("revocationDeadline", 0L)
            if (token != null) note = if (approved) "이전 프리셋 연결을 확인하고 있어요." else "웹의 비교 번호($comparison)와 같다면 웹에서 승인해 주세요."
        }
        if (revocationPending) {
            approved = false
            note = "공유 목록은 이 폰에서 비웠어요. 서버 연결 삭제는 아직 확인하지 못했어요."
            expireRevocation()
        }
        render()
    }
    override fun onStart() { super.onStart(); foreground = true; online = false; generation++; render(); handler.post(poll) }
    override fun onStop() { foreground = false; online = false; generation++; handler.removeCallbacks(poll); super.onStop() }
    override fun onDestroy() { state = null; pending = null; worker.shutdownNow(); super.onDestroy() }
    private fun saveSession() {
        val prefs = getSharedPreferences("pitin_preset_sync_session", MODE_PRIVATE).edit()
        if (token == null) prefs.clear().apply()
        else prefs.putString("origin", presetOrigin).putString("token", token).putBoolean("approved", approved)
            .putString("comparison", comparison).putLong("expiresAt", sessionExpiresAt)
            .putLong("revocationRequestedAt", revocationRequestedAt).putLong("revocationDeadline", revocationDeadline).apply()
    }
    private fun json(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { put(it.first, it.second ?: JSONObject.NULL) } }
    private fun revision() = state?.optLong("revision", -1) ?: -1
    private fun writable() = foreground && approved && online && !busy && pending == null && !revocationPending
    private fun refresh() {
        if (revocationPending) { expireRevocation(); return }
        val auth = token ?: return
        if (busy || checking) return
        val ticket = generation
        checking = true
        worker.execute {
            val result = runCatching {
                if (!approved) client.call("GET", "/pairings/status", auth) else client.call("GET", "/state", auth)
            }
            runOnUiThread {
                checking = false
                if (!foreground || ticket != generation || revocationPending) return@runOnUiThread
                result.fold({ reply ->
                    if (reply.code == 401) { clear("연결이 만료되거나 해제됐어요. 다시 연결해 주세요."); return@fold }
                    if (reply.code != 200) { online = false; note = "서버에 연결할 수 없어요. 마지막 관측값이며 변경은 잠겨 있어요."; render(); return@fold }
                    lastServerConfirmedAt = System.currentTimeMillis()
                    reply.body.optJSONObject("sync")?.optString("expiresAt")?.let { value ->
                        sessionExpiresAt = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(sessionExpiresAt)
                        saveSession()
                    }
                    if (!approved) {
                        if (reply.body.optString("status") == "approved") { approved = true; saveSession(); note = "승인됐어요. 서버 프리셋을 읽고 있어요."; render() }
                    } else { val changed = !online; online = true; if (changed && pending == null) note = "서버의 최신 프리셋을 확인했어요."; if (applyState(reply.body, ticket) || changed) render() }
                }, { online = false; note = "오프라인 · 마지막 관측값이에요. 연결을 확인한 후 변경할 수 있어요."; render() })
            }
        }
    }
    private fun applyState(body: JSONObject, ticket: Long): Boolean {
        val next = body.optJSONObject("state") ?: return false
        val nextEpoch = body.getJSONObject("sync").getString("epoch")
        if (!PresetSyncPolicy.accepts(ticket, generation, epoch, nextEpoch, revision(), next.getLong("revision"))) return false
        val changed = epoch != nextEpoch || revision() != next.getLong("revision")
        if (epoch != null && epoch != nextEpoch) note = "서버 세션이 새로 시작됐어요. 예약과 선호를 다시 확인해 주세요."
        epoch = nextEpoch; state = next
        return changed
    }
    private fun clear(message: String) {
        token = null; approved = false; online = false; state = null; epoch = null
        pending = null; pendingSuccess = null; pendingConflict = null; comparison = ""; generation++
        revocationRequestedAt = 0L; revocationDeadline = 0L; sessionExpiresAt = 0L; lastServerConfirmedAt = 0L
        saveSession(); note = message; render()
    }
    private fun claim(code: String) {
        if (revocationPending || token != null) return
        if (!code.matches(Regex("[0-9]{6}"))) { toast("6자리 번호를 입력해 주세요."); return }
        if (busy) return
        busy = true; val ticket = ++generation
        worker.execute {
            val result = runCatching { client.call("POST", "/pairings/claim", body = json("role" to "phone", "code" to code)) }
            runOnUiThread {
                busy = false
                if (isDestroyed || ticket != generation) return@runOnUiThread
                result.fold({ reply ->
                    if (reply.code == 200 || reply.code == 201) {
                        token = reply.body.getString("token"); comparison = reply.body.getString("comparisonCode")
                        sessionExpiresAt = Instant.parse(reply.body.getString("expiresAt")).toEpochMilli()
                        approved = false; saveSession()
                        note = "웹의 비교 번호($comparison)와 같다면 웹에서 이 폰을 승인해 주세요."
                    } else note = "연결 번호를 확인해 주세요. 만료되었다면 웹에서 새로 만드세요."
                }, { note = "연결하지 못했어요. HTTPS 서버($presetOrigin) 연결을 확인해 주세요." })
                render()
            }
        }
    }
    private fun expireRevocation(): Boolean {
        if (!revocationPending || PresetRevocationPolicy.canRetry(revocationRequestedAt, revocationDeadline, System.currentTimeMillis())) return false
        clear("삭제 재시도 보관 시간이 지나 이 폰의 연결 정보를 지웠어요. 서버 삭제 확인은 받지 못했어요.")
        return true
    }
    private fun disconnect() {
        val auth = token ?: return
        if (busy) return
        if (!revocationPending) {
            val now = System.currentTimeMillis()
            revocationRequestedAt = now
            revocationDeadline = PresetRevocationPolicy.deadline(now, sessionExpiresAt)
            approved = false; online = false; state = null; epoch = null; comparison = ""
            pending = null; pendingSuccess = null; pendingConflict = null
            saveSession()
        }
        if (expireRevocation()) return
        busy = true; val ticket = ++generation
        note = "이 폰의 공유 목록은 비웠어요. 서버 연결 삭제를 확인하고 있어요."
        render()
        worker.execute {
            val result = runCatching { client.call("DELETE", "/session", auth) }
            runOnUiThread {
                busy = false
                if (isDestroyed) return@runOnUiThread
                if (ticket != generation) {
                    if (foreground && revocationPending) render()
                    return@runOnUiThread
                }
                val reply = result.getOrNull()
                when (PresetRevocationPolicy.result(reply?.code, reply?.body?.opt("deleted"))) {
                    PresetRevocationPolicy.Result.DELETED -> clear("서버에서 연결 삭제를 확인했어요. 이 폰의 로컬 선호와 서버의 공유 선호 목록은 유지돼요.")
                    PresetRevocationPolicy.Result.SESSION_ENDED -> clear("서버 연결이 더 이상 유효하지 않아요. 이 폰의 연결 정보는 지웠지만 삭제 확인 응답은 받지 못했어요.")
                    PresetRevocationPolicy.Result.RETRY -> {
                        note = "서버 삭제를 아직 확인하지 못했어요. 새 연결과 변경은 잠시 멈추고, 삭제 요청만 다시 보낼 수 있어요."
                        render()
                    }
                }
            }
        }
    }
    private fun send(action: String, payload: JSONObject, atEpoch: String? = epoch, atRevision: Long = revision(), success: (() -> Unit)? = null, conflict: (() -> Unit)? = null) {
        if (!writable()) { toast("연결 확인 중이거나 결과 미확인 요청이 있어요."); return }
        pending = json("action" to action, "payload" to payload, "expectedEpoch" to atEpoch, "expectedRevision" to atRevision, "requestId" to UUID.randomUUID().toString())
        pendingSuccess = success; pendingConflict = conflict
        submitPending()
    }
    private fun submitPending() {
        val command = pending ?: return
        val auth = token ?: return
        if (busy || !foreground || revocationPending) return
        busy = true; online = false; val ticket = ++generation; render()
        worker.execute {
            val result = runCatching { client.call("POST", "/commands", auth, command) }
            runOnUiThread {
                busy = false
                if (isDestroyed || ticket != generation) return@runOnUiThread
                result.fold({ reply ->
                    if (reply.code == 401) { clear("연결이 만료되거나 해제됐어요. 다시 연결해 주세요."); return@fold }
                    if (reply.code >= 500) { note = "결과 미확인 · 같은 요청으로 재시도해 주세요."; render(); return@fold }
                    if (reply.code in 200..299 && reply.body.optString("acknowledgedRequestId") != command.getString("requestId")) {
                        note = "응답의 요청 번호를 확인하지 못했어요. 같은 요청으로 재시도해 주세요."; render(); return@fold
                    }
                    applyState(reply.body, ticket); online = reply.body.has("state")
                    val done = pendingSuccess; val conflicted = pendingConflict
                    pending = null; pendingSuccess = null; pendingConflict = null
                    if (reply.code in 200..299) { note = "서버에 저장했어요. 상대 앱의 실제 수신은 별도로 확인해 주세요."; done?.invoke() }
                    else if (reply.code == 409) { note = "다른 화면에서 변경됐어요. 최신 상태를 검토한 뒤 다시 제출해 주세요."; conflicted?.invoke() }
                    else { note = reply.body.optJSONObject("error")?.optString("message") ?: "변경을 처리하지 못했어요."; toast(note) }
                    render()
                }, { note = "결과 미확인 · 새 변경은 잠겼어요. 같은 요청으로 재시도해 주세요."; render() })
            }
        }
    }
    private fun render() {
        val y = if (::scroll.isInitialized) scroll.scrollY else 0
        column = theme.column().apply { setPadding(dp(22), dp(10), dp(22), dp(36)); setBackgroundColor(paper) }
        scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; setBackgroundColor(paper); addView(column) }
        PhoneTheme.installDetail(this, scroll)
        column.addView(theme.ui.backHeader("연결", null, { finish() }), LinearLayout.LayoutParams(-1, -2).apply { marginStart = -dp(10) })
        column.addView(theme.ui.display("취향 함께 쓰기", 30f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        column.addView(theme.ui.body("차와 웹이 같은 쉼 목록을 봐요. 이 폰에만 둔 쉼과는 따로 보관해요."), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val connection = theme.column(column, theme.card)
        theme.heading(connection, when {
            revocationPending -> "서버 삭제 확인 필요"
            token == null -> "취향 함께 쓰기 연결"
            online -> "이어져 있어요"
            approved -> "확인이 필요해요"
            else -> "웹의 승인을 기다려요"
        })
        theme.text(connection, note, 15f, color = if (revocationPending) theme.danger else theme.ink).apply {
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        if (revocationPending) {
            button(connection, if (busy) "서버 삭제 확인 중…" else "서버 삭제 다시 확인", !busy, primary = true) { disconnect() }
            theme.text(connection, "삭제 확인 전에는 새 연결·조회·변경을 하지 않아요. 이 폰의 로컬 선호는 그대로예요.", 14f, color = theme.muted)
        } else if (token == null) {
            val code = dialogs.input(connection, "웹에서 만든 폰용 연결 번호", "", true).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(android.text.InputFilter.LengthFilter(6))
                hint = "6자리 번호"
                setSingleLine()
            }
            button(connection, if (busy) "연결하고 있어요" else "연결하기", !busy, primary = true) { claim(code.text.toString().trim()) }
            theme.text(connection, "연결해도 이 폰의 쉼을 저절로 올리지 않아요.", 14f, color = theme.muted)
        } else {
            if (!approved) {
                theme.text(connection, "비교 번호  $comparison", 26f, true)
                theme.text(connection, "웹 화면의 숫자와 같다면 웹에서 이 폰을 승인해 주세요.", 15f)
                button(connection, "승인 상태 다시 확인", !busy && !checking) { refresh() }
            }
            if (lastServerConfirmedAt > 0) theme.text(connection, formatTime(lastServerConfirmedAt) + " 확인", 14f, color = theme.muted)
            theme.text(connection, "연결됐다고 해서 차나 웹이 지금 켜져 있다는 뜻은 아니에요.", 14f, color = theme.muted)
            val technical = theme.disclosure(connection, "연결 끊기", technicalExpanded) { technicalExpanded = it }
            if (debugBuild()) theme.text(technical, "개발용 · $presetOrigin · 목록 ${revision()}", 13f, color = theme.muted)
            button(technical, "취향 함께 쓰기 끊기", !busy, destructive = true) { disconnect() }
        }
        sendRest?.takeUnless { revocationPending }?.let { rest ->
            val card = theme.column(column, theme.card)
            theme.heading(card, "차에 보낼 쉼 · " + rest.optString("name"))
            val c = rest.getJSONObject("cabin")
            val prefs = CabinPrefs(
                SeatPref(c.getJSONObject("seatDriver").getString("position"), c.getJSONObject("seatDriver").getString("comfort"), c.getJSONObject("seatDriver").getInt("level")),
                SeatPref(c.getJSONObject("seatPassenger").getString("position"), c.getJSONObject("seatPassenger").getString("comfort"), c.getJSONObject("seatPassenger").getInt("level")),
                light = LightPref(c.getJSONObject("light").getString("mode"), c.getJSONObject("light").getString("color"), c.getJSONObject("light").getInt("bright")))
            val car = rest.getJSONObject("vehicle")
            val temp = if (car.isNull("tempC")) "온도 그대로" else String.format(java.util.Locale.US, "%.1f°", car.getDouble("tempC"))
            theme.text(card, "에어컨 $temp · 시트 ${CabinPrefs.seatText(prefs.seatDriver)} · 조명 ${CabinPrefs.lightText(prefs.light)}", 15f)
            theme.text(card, "운동 기록은 보내지 않아요. 차 화면에 이 쉼이 준비되고, 시작과 차 안 맞추기는 차에서 직접 해요.", 14f, color = theme.muted)
            button(card, if (token == null) "먼저 위에서 연결해 주세요" else "차에 보내기", writable(), primary = pending == null) {
                send("propose_rest", json("preset" to rest), success = {
                    sendRest = null
                    note = "차에 보냈어요. 차 화면에서 확인하고 직접 시작해요."
                })
            }
        }
        if (pending != null && !revocationPending) {
            button(column, if (busy) "앞선 변경 확인 중…" else "앞선 변경 결과 다시 확인", !busy, primary = true) { submitPending() }
            theme.text(column, "같은 요청을 다시 확인해요. 결과 확인 전에는 새 변경을 보내지 않아요.", 14f, color = theme.muted)
        }
        state?.takeUnless { revocationPending }?.let { s ->
            val selection = theme.column(column, theme.card)
            theme.heading(selection, "함께 쓰는 쉼")
            val presets = s.getJSONArray("presets")
            var selected: JSONObject? = null
            for (i in 0 until presets.length()) {
                val p = presets.getJSONObject(i)
                val id = p.getString("id")
                val active = id == s.getString("selectedId")
                if (active) selected = p
                val title = p.getString("name") + if (id == s.getString("defaultId")) " · 기본" else ""
                theme.choice(selection, title, active) { send("select_preset", json("id" to id)) }.isEnabled = writable()
            }
            selected?.let { p ->
                theme.text(selection, dialogs.values(p), 15f)
                theme.text(selection, "쉼 값만 함께 봐요. 차의 공조·시트와 집 기기는 바꾸지 않아요.", 14f, color = theme.muted)
                button(selection, "이 쉼을 차에 전하기", writable(), primary = pending == null) { send("propose_vehicle", json("id" to p.getString("id"))) }

                val feelings = theme.disclosure(selection, "오늘은 어떤가요?", feelingsExpanded) { feelingsExpanded = it }
                theme.text(feelings, "내가 직접 고르는 느낌이에요. 건강 상태를 진단하지 않아요.", 14f, color = theme.muted)
                listOf("usual" to "평소처럼", "warm" to "더워요", "quiet" to "조용히 쉬고 싶어요", "tired" to "조금 지쳤어요", "cold" to "쌀쌀해요", "skip" to "고르지 않음").forEach { (value, label) ->
                    theme.choice(feelings, label, s.optString("feeling") == value) { send("set_feeling", json("value" to value)) }.isEnabled = writable()
                }
                s.optJSONObject("recommendation")?.let { rec ->
                    theme.text(feelings, rec.optString("reason"), 15f)
                    button(feelings, "이걸로", writable()) { send("select_preset", json("id" to rec.getString("presetId"))) }
                }

                val manage = theme.disclosure(selection, "쉼 편집·기본 쉼", managementExpanded) { managementExpanded = it }
                button(manage, "새 쉼 만들기", writable()) { dialogs.edit(p, false) }
                button(manage, if (p.getString("kind") == "builtin") "기본 쉼 복사해 고치기" else "이 쉼 고치기", writable()) { dialogs.edit(p, p.getString("kind") == "custom") }
                if (p.getString("kind") == "custom") button(manage, "이 쉼 지우기", writable(), destructive = true) { dialogs.confirmDelete(p) }
                button(manage, "지금 고른 쉼을 기본으로", writable()) { send("set_default", json("id" to p.getString("id"), "autoDefaultOnRest" to s.getBoolean("autoDefaultOnRest"))) }
                val automatic = theme.check(manage, "쉬기 준비 때 기본 쉼 먼저 고르기", s.getBoolean("autoDefaultOnRest"))
                automatic.isEnabled = writable()
                automatic.setOnCheckedChangeListener { _, enabled -> send("set_default", json("id" to s.getString("defaultId"), "autoDefaultOnRest" to enabled)) }
                theme.text(manage, "쉬기를 저절로 시작하지는 않아요.", 14f, color = theme.muted)
                button(manage, "기본 쉼으로 쉬기 준비", writable()) { send("begin_rest", json("origin" to "phone")) }

                val arrival = theme.disclosure(column, "귀가 준비 · 체험", arrivalExpanded) { arrivalExpanded = it }
                theme.heading(arrival, "이번 귀가 한 번", 19f)
                theme.text(arrival, "예시 집 에어컨으로 예약 흐름을 확인해요. 실제 기기는 연결하지 않았어요.", 14f, color = theme.muted)
                val plan = s.optJSONObject("plan")
                if (plan != null) {
                    theme.text(arrival, planStatus(plan.optString("status")), 17f, true)
                    theme.text(arrival, plan.optString("presetName") + " · 준비 예정 " + formatInstant(plan.optString("executeAt")), 15f)
                    theme.text(arrival, plan.optString("message"), 14f, color = theme.muted)
                    if (plan.isNull("requestedAt") && plan.optString("status") in setOf("armed", "needs_review", "failed", "expired")) {
                        button(arrival, "이번 예약 시간 다시 검토", writable()) { dialogs.arrival(p, true) }
                    }
                    if (plan.optString("status") == "requested") button(arrival, "예시 기기 설정 상태 확인", writable()) { send("confirm_home", json()) }
                    button(arrival, "이번 예약 취소", writable(), destructive = true) { send("cancel_arrival", json()) }
                    theme.text(arrival, "취소는 기기 설정 원복이나 전원 끄기가 아니에요.", 14f, color = theme.muted)
                }
                if (plan == null || plan.optString("status") in setOf("cancelled", "confirmed_demo", "expired")) {
                    button(arrival, "새 귀가 예약 검토", writable()) { dialogs.arrival(p, false) }
                }
                val demo = theme.disclosure(arrival, "체험 시계 · 예시 기기", demoExpanded) { demoExpanded = it }
                if (plan != null) button(demo, "시연 시계를 준비 예정 시각으로", writable()) {
                    val minutes = (Instant.parse(plan.getString("executeAt")).toEpochMilli() - Instant.parse(s.getString("now")).toEpochMilli()) / 60000.0
                    if (minutes > 0 && minutes <= 180) send("advance_time", json("minutes" to maxOf(0.1, minutes))) else toast("예정 시각이 지났거나 이동 범위를 벗어났어요.")
                }
                button(demo, "시연 시계 +5분", writable()) { send("advance_time", json("minutes" to 5)) }
                val home = s.getJSONObject("devices").getJSONObject("home")
                theme.text(demo, "합성 집 관측: " + home.opt("tempC") + "°C · " + fanName(home.optString("fan")) + " · " + if (home.optString("power") == "on") "켜짐" else "꺼짐", 14f)
                button(demo, "다른 앱·리모컨의 수동 변경 시연", writable()) { send("set_home_manual", json("tempC" to if (home.optDouble("tempC") == 26.0) 25.0 else 26.0)) }
                button(demo, if (home.optBoolean("online")) "예시 집 기기를 오프라인으로" else "예시 집 기기를 온라인으로", writable()) { send("set_target_online", json("online" to !home.optBoolean("online"))) }
            }
        }
        scroll.post { scroll.scrollTo(0, y) }
    }
    private fun button(parent: LinearLayout, label: String, enabled: Boolean, primary: Boolean = false, destructive: Boolean = false, action: () -> Unit): Button =
        theme.button(parent, label, primary, action).also {
            it.isEnabled = enabled
            if (destructive) theme.styleButton(it, quiet = true, destructive = true)
        }
    private fun formatTime(value: Long) = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date(value))
    private fun formatInstant(value: String) = runCatching { formatTime(Instant.parse(value).toEpochMilli()) }.getOrDefault("확인 필요")
    private fun fanName(value: String) = mapOf("keep" to "현재대로", "auto" to "자동", "low" to "약하게", "medium" to "중간", "high" to "강하게")[value] ?: value
    private fun planStatus(value: String) = mapOf("armed" to "예약됨 · 대기 중", "requested" to "요청 접수 · 관측 확인 전", "confirmed_demo" to "예시 기기 관측 확인됨", "failed" to "실패 · 다시 검토해 주세요", "cancelled" to "예약 취소됨", "expired" to "예정 시간 지남", "needs_review" to "수동 변경 · 재확인 필요")[value] ?: "상태 확인 필요"
    private fun dp(value: Int) = theme.dp(value)
    private fun toast(message: String) { note = message; if (::column.isInitialized) render() }
    private fun debugBuild() = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") overridePendingTransition(R.anim.pit_pop_in, R.anim.pit_pop_out)
    }
}
