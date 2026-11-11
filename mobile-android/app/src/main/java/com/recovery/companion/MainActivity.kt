package com.recovery.companion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Phone only. Health Connect never runs in the vehicle app. No background collection.
 * 한 Activity 안에서 화면을 바꾼다(오늘·취향·연결·나). 연결·공유·삭제 계약 로직은 그대로 둔다.
 */
class MainActivity : ComponentActivity(), PitApp {
    private lateinit var shell: PhoneShell
    override lateinit var appearanceSync: AppearanceSync
    private var appearanceSignature = ""
    private val relay = RecoveryRelayClient()
    override var busy = false; private set
    override var pairing: PairingSession? = null; private set
    override var pairingConfirmed = false; private set
    override var confirmationCode: String? = null; private set
    override var lastPairingCheckAt = 0L; private set
    private var pairingLabel = "아직 차량과 연결하지 않았어요"
    override var workout: WorkoutRecord? = null; private set
    override var healthState = HealthState.CHECKING; private set
    override var lastReadEmpty = false; private set
    override var lastReadAt = 0L; private set
    override var lastSent: SentSummary? = null; private set
    override var deletionConfirmedAt = 0L; private set
    override var pairCode = ""
    override var consent = false
        set(value) { field = value }
    private var reviewedPreset: Preset? = null
    private var requestedSamsungOnly = false
    private var validationOnResumePending = false
    private var foreground = false
    private var foregroundEpoch = 0L
    override val debug get() = isDebugBuild()
    override val serverless get() = !isDebugBuild() && !relayBase().trim().startsWith("https://")
    private val exercisePermission = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    private val permissions = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        clearSummary()
        if (exercisePermission in granted) task { readWorkout(requestedSamsungOnly) }
        else { shell.report("운동 읽기를 허용하지 않았어요. 운동 기록 없이도 쉼을 고를 수 있어요."); refreshHealthAccess(); refreshHome() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedSamsungOnly = savedInstanceState?.getBoolean("requestedSamsungOnly") ?: false
        CompanionFormatters.loadPairing(this)?.let {
            pairing = it
            // A persisted approval is only a cached display value, never current server authority.
            pairingConfirmed = false
            pairingLabel = if (it.revocationPending) it.label else "연결 상태 재확인 필요 · 아직 공유할 수 없어요"
            validationOnResumePending = !it.revocationPending
        }
        appearanceSync = AppearanceSync(this)
        appearanceSignature = AppearancePreferences(this).signature()
        shell = PhoneShell(this, this)
        shell.factory = { tab -> when (tab) { Tab.TODAY -> TodayScreen(shell); Tab.COMFORT -> ComfortScreen(shell); Tab.CONNECT -> ConnectScreen(shell); Tab.ME -> MeScreen(shell) } }
        shell.install()
        savedInstanceState?.getString("tab")?.let { name -> Tab.entries.find { it.name == name }?.let { shell.showTab(it, false) } }
        val opened = showRequestedPage(intent)
        onBackPressedDispatcher.addCallback(this) { if (!shell.back()) finish() }
        refreshHome()
        val forceIntro = isDebugBuild() && intent?.getBooleanExtra("pitin_intro", false) == true
        if (savedInstanceState == null && !opened && shell.shouldPlayIntro(forceIntro)) shell.playIntro()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showRequestedPage(intent)
        refreshHome()
    }

    /** 다른 화면(Health Connect 안내 등)에서 들어올 때만 쓴다. */
    private fun showRequestedPage(intent: Intent?): Boolean {
        val page = intent?.getStringExtra("pitin_page") ?: return false
        intent.removeExtra("pitin_page")
        when (page) {
            "privacy" -> shell.push(PrivacyScreen(shell))
            "CONNECTION", "connect" -> shell.showTab(Tab.CONNECT, false)
            "comfort" -> shell.showTab(Tab.COMFORT, false)
            "me", "MORE" -> shell.showTab(Tab.ME, false)
            "cabin" -> shell.push(CabinScreen(shell))
            "pair" -> shell.push(PairScreen(shell))
            "share", "WORKOUT" -> shell.push(ShareScreen(shell))
            "health" -> shell.push(HealthScreen(shell))
            // 집 연결은 합성 기기 체험이라 디버그 빌드에서만 연다.
            "homes" -> if (isDebugBuild()) shell.push(HomesScreen(shell))
            "arrival" -> if (isDebugBuild()) shell.push(ArrivalScreen(shell))
            "appearance" -> shell.push(AppearanceScreen(shell))
            "preset" -> shell.push(PresetEditScreen(shell, null))
            "sent" -> shell.push(SentScreen(shell))
            "homedetail" -> if (isDebugBuild()) shell.push(HomeDetailScreen(shell))
            "developer" -> if (isDebugBuild()) shell.push(DeveloperScreen(shell))
            else -> return false
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        refreshHealthAccess()
        foreground = true
        appearanceSync.start(::updateAppearance)
        expireRevocationIfNeeded()
        if (pairing?.revocationPending == true) {
            pairingConfirmed = false
            validationOnResumePending = false
            refreshHome()
        } else if (pairing != null) {
            pairingConfirmed = false
            pairingLabel = "연결 상태 재확인 필요 · 아직 공유할 수 없어요"
            pairing = pairing?.copy(confirmed = false, label = pairingLabel)
                ?.also { CompanionFormatters.savePairing(this, it) }
            refreshHome()
            validationOnResumePending = true
            checkPairingAutomaticallyWhenIdle()
        } else refreshHome()
    }

    override fun onPause() {
        foreground = false
        foregroundEpoch++
        appearanceSync.stop()
        rememberSplash()
        if (pairing != null && pairing?.revocationPending != true) {
            pairingConfirmed = false
            pairingLabel = "연결 상태 재확인 필요 · 아직 공유할 수 없어요"
            pairing = pairing?.copy(confirmed = false, label = pairingLabel)
                ?.also { CompanionFormatters.savePairing(this, it) }
            refreshHome()
        }
        super.onPause()
    }

    override fun onDestroy() {
        appearanceSync.close()
        super.onDestroy()
    }

    /** Android 13+: the next system start window uses the mood the app shows now, so Graphite/Umber do not flash Pearl. */
    private fun rememberSplash() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val style = when (AppearancePreferences(this).mood()) {
            Mood.GRAPHITE -> R.style.Theme_PitIn_Splash_Graphite
            Mood.UMBER -> R.style.Theme_PitIn_Splash_Umber
            else -> R.style.Theme_PitIn
        }
        runCatching { splashScreen.setSplashScreenTheme(style) }
    }

    /** 차량 앱 따르기로 테마가 바뀌어 들어오면 파도로 바꾼다. 진행 중인 작업과 고른 운동은 그대로다. */
    private fun updateAppearance() {
        if (!::shell.isInitialized || !foreground) return
        val preferences = AppearancePreferences(this)
        if (!busy && preferences.signature() != appearanceSignature) {
            appearanceSignature = preferences.signature()
            shell.applyTheme(true)
        }
        shell.notifyChanged()
    }
    override fun appearanceChanged() {
        appearanceSignature = AppearancePreferences(this).signature()
        shell.applyTheme(true)
        appearanceSync.refresh()
    }

    private fun checkPairingAutomaticallyWhenIdle() {
        if (busy || pairing == null || pairing?.revocationPending == true) return
        validationOnResumePending = false
        task { checkPairing() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("requestedSamsungOnly", requestedSamsungOnly); outState.putString("tab", shell.tab.name)
        super.onSaveInstanceState(outState)
    }

    override fun selectPreset(id: String) {
        val store = PresetStore(this)
        (store.all().firstOrNull { it.id == id } ?: store.all().firstOrNull { it.comfort == id })?.let { store.select(it.id) }
        refreshHome()
    }
    override fun relayBase(): String = CompanionFormatters.loadRelayBase(this, getString(R.string.relay_default_url))
    override fun setRelayBase(value: String) { if (pairing == null) CompanionFormatters.saveRelayBase(this, value) }
    override fun openSyncedPresets() {
        startActivity(Intent(this, SyncedPresetsActivity::class.java).putExtra("relayOrigin", relayBase()))
        @Suppress("DEPRECATION") overridePendingTransition(R.anim.pit_push_in, R.anim.pit_push_out)
    }

    override fun sendRestToCar(presetId: String) {
        val preset = PresetStore(this).all().firstOrNull { it.id == presetId } ?: return
        val rest = SyncedPresetsActivity.restProposal(preset, CabinStore(this).saved(presetId))
        startActivity(Intent(this, SyncedPresetsActivity::class.java).putExtra("relayOrigin", relayBase())
            .putExtra(SyncedPresetsActivity.EXTRA_SEND_REST, rest.toString()))
        @Suppress("DEPRECATION") overridePendingTransition(R.anim.pit_push_in, R.anim.pit_push_out)
    }

    override fun act(action: CompanionAction) {
        when (action) {
            CompanionAction.HEALTH_SETTINGS -> {
                runCatching { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
                    .onFailure { shell.report("Health Connect 설정을 열 수 없어요. Android 설정에서 건강 기록 권한을 확인해 주세요.") }
                return
            }
            CompanionAction.SYNC_PRESETS -> { openSyncedPresets(); return }
            else -> {}
        }
        task {
            when (action) {
                CompanionAction.CLAIM -> claimPairing()
                CompanionAction.CHECK_PAIRING -> checkPairing()
                CompanionAction.READ_ALL -> connectOrRead(false)
                CompanionAction.READ_SAMSUNG -> connectOrRead(true)
                CompanionAction.DEMO -> {
                    check(isDebugBuild()); clearSummary()
                    workout = WorkoutRecord("walking", 20, Instant.now().minusSeconds(60), "demo", providerPackage = "demo")
                    lastReadEmpty = false; lastReadAt = System.currentTimeMillis()
                }
                CompanionAction.SEND -> sendSummary()
                CompanionAction.CLEAR -> clearSummary()
                CompanionAction.REVOKE -> revokePairing()
                else -> {}
            }
        }
    }

    private suspend fun claimPairing() {
        check(pairing == null) { "이미 요청한 연결이 있어요. 승인 상태를 확인하거나 연결을 끊어 주세요." }
        val origin = validateBase(relayBase()).also { CompanionFormatters.saveRelayBase(this, it) }
        val answer = relay.request(origin, "/api/pairings/claim", "POST", JSONObject().put("code", pairCode.trim()))
        val id = answer.optString("pairingId"); val token = answer.optString("phoneToken")
        val confirmation = answer.optString("confirmation"); val expiresAt = answer.optString("expiresAt")
        SharePolicy.validateClaimResponse(id, token, confirmation, expiresAt)
        pairingConfirmed = false; pairingLabel = "차량 승인 대기 · 비교 확인 번호 $confirmation"
        confirmationCode = confirmation
        pairing = PairingSession(origin, id, token, false, pairingLabel, expiresAt).also { CompanionFormatters.savePairing(this, it) }
        pairCode = ""
    }

    private suspend fun checkPairing() {
        val p = requirePairing()
        check(!p.revocationPending) { "삭제를 확인한 뒤 새로 연결해 주세요." }
        pairingConfirmed = false
        pairingLabel = "연결 상태 재확인 필요 · 아직 공유할 수 없어요"
        pairing = p.copy(confirmed = false, label = pairingLabel)
            .also { CompanionFormatters.savePairing(this, it) }
        refreshHome()
        val requestEpoch = foregroundEpoch
        val answer = relay.request(p.base, "/api/pairings/${p.id}", "GET", token = p.token)
        if (pairing?.id != p.id || pairing?.token != p.token || pairing?.revocationPending == true) return
        if (!foreground || requestEpoch != foregroundEpoch) {
            pairingConfirmed = false
            pairingLabel = "연결 상태 재확인 필요 · 아직 공유할 수 없어요"
            pairing = p.copy(confirmed = false, label = pairingLabel).also { CompanionFormatters.savePairing(this, it) }
            validationOnResumePending = true
            return
        }
        val status = answer.optString("status"); val confirmation = answer.optString("confirmation", "")
        pairingConfirmed = SharePolicy.validateStatusResponse(p.id, answer.optString("pairingId"), status, confirmation, answer.optString("expiresAt"))
        if (confirmation.isNotEmpty()) confirmationCode = confirmation
        lastPairingCheckAt = System.currentTimeMillis()
        pairingLabel = when (status) {
            "paired" -> "이어져 있어요"
            "claimed" -> "차량 승인 대기 · 확인 번호 ${confirmation.ifEmpty { "—" }}"
            else -> "차량의 연결 승인을 기다리고 있어요"
        }
        pairing = p.copy(confirmed = pairingConfirmed, label = pairingLabel, expiresAt = answer.optString("expiresAt")).also { CompanionFormatters.savePairing(this, it) }
        if (status == "paired") PitHaptics.confirm(shell.host)
        else shell.report("아직 차의 승인을 기다리고 있어요.")
    }

    private suspend fun revokePairing() {
        val p = requirePairing()
        val now = System.currentTimeMillis()
        val pending = if (p.revocationPending) p else p.copy(
            confirmed = false, label = "공유 중단 · 서버 삭제 확인 대기",
            revocationPending = true, revocationStartedAtMillis = now,
            revocationDeadlineMillis = SharePolicy.revocationDeadline(p.expiresAt, now))
        pairing = pending
        pairingConfirmed = false
        pairingLabel = pending.label
        validationOnResumePending = false
        CompanionFormatters.savePairing(this, pending)
        clearSummary()
        refreshHome()
        if (expireRevocationIfNeeded()) return
        // The retained credential may only DELETE; it cannot restore approval or share data.
        val answer = relay.request(pending.base, "/api/pairings/${pending.id}", "DELETE", token = pending.token)
        SharePolicy.validateDeleteResponse(answer.opt("deleted"))
        pairing = null
        confirmationCode = null
        CompanionFormatters.savePairing(this, null)
        pairingLabel = "서버 요약 삭제 및 연결 해제 확인됨"
        lastSent = null
        deletionConfirmedAt = System.currentTimeMillis()
        shell.report("지웠어요. 서버에서 요약 삭제와 연결 끊기를 확인했어요. 꺼져 있는 차 화면은 다음 확인 때 비워져요.")
    }

    private fun expireRevocationIfNeeded(): Boolean {
        val p = pairing ?: return false
        if (!p.revocationPending || SharePolicy.canRetryRevocation(p.revocationStartedAtMillis,
                p.revocationDeadlineMillis, System.currentTimeMillis())) return false
        pairing = null
        pairingConfirmed = false
        validationOnResumePending = false
        CompanionFormatters.savePairing(this, null)
        pairingLabel = "삭제 재시도 기간 종료 · 서버 삭제 확인 없음"
        lastSent = null
        if (::shell.isInitialized) {
            clearSummary()
            shell.report("다시 확인할 수 있는 시간이 지나 이 폰의 연결 정보를 지웠어요. 서버 삭제 응답은 받지 못했어요. 요약은 원래 시각을 넘겨 쓰이지 않아요.")
        }
        return true
    }

    private suspend fun sendSummary() {
        check(consent) { "보내는 내용을 확인하고 ‘이번 한 번만 보낼게요’를 골라 주세요." }
        val p = requirePairing().also { check(pairingConfirmed && !it.revocationPending) { "차량 연결을 먼저 확인해 주세요." } }
        val w = workout ?: error("먼저 운동 기록을 골라 주세요.")
        val selected = PresetStore(this).selected()
        val requestEpoch = foregroundEpoch
        SharePolicy.requireShare(p.base, isDebugBuild(), w.source, consent, w.end, Instant.now())
        if (w.source == "health_connect") verifySourceWorkout(w)
        check(foreground && foregroundEpoch == requestEpoch && pairingConfirmed
            && pairing?.id == p.id && pairing?.token == p.token && pairing?.revocationPending != true) {
            "화면이나 연결 상태가 바뀌었어요. 승인 상태와 보내는 내용을 다시 확인해 주세요."
        }
        check(PresetStore(this).selected() == selected && consent) {
            consent = false
            "고른 쉼이 바뀌었어요. 보내는 내용을 확인하고 다시 골라 주세요."
        }
        SharePolicy.requireFresh(w.end, Instant.now())
        val body = JSONObject().put("schemaVersion", 1).put("source", w.source)
            .put("workout", JSONObject().put("type", w.type).put("durationMinutes", w.minutes).put("endedAt", w.end.toString()))
            .put("comfort", selected.comfort).put("consent", JSONObject().put("shareWorkoutSummary", true))
        // One explicit consent authorizes one attempt, including an uncertain network outcome.
        consent = false
        val answer = relay.request(p.base, "/api/pairings/${p.id}/summary", "POST", body, p.token)
        SharePolicy.validateSummaryResponse(answer.opt("accepted"), answer.optString("summaryId"), answer.optString("receivedAt"), answer.optString("expiresAt"))
        val expires = Instant.parse(answer.optString("expiresAt"))
        check(!expires.isAfter(w.end.plus(Duration.ofMinutes(120)))) {
            "운동 기록의 유효 범위를 넘는 서버 응답이에요. 보냈다고 표시하지 않았어요."
        }
        consent = false
        // 서버 접수만 확인했다. ‘차가 받았어요’는 차에서만 확인할 수 있다.
        lastSent = SentSummary(w.type, w.minutes, selected.name, Instant.now(), expires, w.source == "demo")
        deletionConfirmedAt = 0L
    }

    private suspend fun verifySourceWorkout(w: WorkoutRecord) {
        val client = healthClient()
        if (exercisePermission !in client.permissionController.getGrantedPermissions()) {
            clearSummary(); error("운동 읽기 권한이 꺼졌어요. 이 폰의 미리보기를 비웠고 보내지 않았어요.")
        }
        val id = w.recordId
        if (id.isNullOrBlank()) {
            clearSummary(); error("고른 기록을 다시 확인하지 못했어요. 운동을 다시 읽어 주세요.")
        }
        val current = try { client.readRecord(ExerciseSessionRecord::class, id).record }
            catch (e: CancellationException) { throw e }
            catch (e: SecurityException) { clearSummary(); throw e }
            catch (_: Exception) {
                clearSummary(); error("원본 운동을 다시 확인하지 못했어요. 기록 상태를 확인하고 다시 읽어 주세요. 보내지 않았어요.")
            }
        val minutes = (Duration.between(current.startTime, current.endTime).seconds / 60.0).roundToInt()
        val unchanged = current.metadata.id == id && current.metadata.dataOrigin.packageName == w.providerPackage
            && activityName(current.exerciseType) == w.type && minutes == w.minutes && current.endTime == w.end
        if (!unchanged) {
            clearSummary(); error("고른 원본 운동이 바뀌었어요. 최신 기록을 다시 읽고 다시 골라 주세요.")
        }
    }

    private fun refreshHome() {
        val preset = PresetStore(this).selected()
        val preferenceChanged = reviewedPreset != null && reviewedPreset != preset
        reviewedPreset = preset
        if (preferenceChanged) consent = false
        if (::shell.isInitialized) shell.notifyChanged()
    }

    private fun refreshHealthAccess() = lifecycleScope.launch {
        healthState = try {
            when (HealthConnectClient.getSdkStatus(this@MainActivity)) {
                HealthConnectClient.SDK_AVAILABLE -> {
                    val granted = HealthConnectClient.getOrCreate(this@MainActivity).permissionController.getGrantedPermissions()
                    if (exercisePermission in granted) HealthState.GRANTED
                    else { if (workout?.source == "health_connect") clearSummary(); HealthState.NEEDS_PERMISSION }
                }
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                    if (workout?.source == "health_connect") clearSummary(); HealthState.UPDATE_REQUIRED
                }
                else -> { if (workout?.source == "health_connect") clearSummary(); HealthState.UNAVAILABLE }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { HealthState.ERROR }
        refreshHome()
    }

    private suspend fun connectOrRead(samsungOnly: Boolean) {
        clearSummary(); requestedSamsungOnly = samsungOnly
        val client = healthClient(); val required = setOf(exercisePermission)
        if (!client.permissionController.getGrantedPermissions().containsAll(required)) {
            PitPrefs(this).healthPermissionAsked = true
            permissions.launch(required)
        } else readWorkout(samsungOnly)
    }

    private fun healthClient(): HealthConnectClient = when (HealthConnectClient.getSdkStatus(this)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectClient.getOrCreate(this)
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> error("Health Connect 설치나 업데이트가 필요해요.")
        else -> error("이 기기에서는 Health Connect를 쓸 수 없어요. 건강 기록을 읽지 않았어요.")
    }

    private suspend fun readWorkout(samsungOnly: Boolean) {
        clearSummary(); val client = healthClient()
        check(exercisePermission in client.permissionController.getGrantedPermissions()) { "운동 읽기 권한이 없어요. 건강 기록을 읽지 않았어요." }
        healthState = HealthState.GRANTED
        val now = Instant.now(); var token: String? = null; var latest: ExerciseSessionRecord? = null
        do {
            val req = ReadRecordsRequest(ExerciseSessionRecord::class, timeRangeFilter = TimeRangeFilter.between(now.minus(Duration.ofHours(24)), now), dataOriginFilter = if (samsungOnly) setOf(DataOrigin("com.sec.android.app.shealth")) else emptySet(), pageToken = token)
            val response = client.readRecords(req)
            response.records.filter { !it.endTime.isAfter(now) }.forEach { if (latest == null || it.endTime.isAfter(latest!!.endTime)) latest = it }
            token = response.pageToken
        } while (!token.isNullOrEmpty())
        lastReadAt = System.currentTimeMillis()
        val record = latest ?: run {
            lastReadEmpty = true
            shell.report(if (samsungOnly) "최근 24시간에 삼성 헬스가 전한 운동이 없어요. 기록이 없다고 건강 상태를 뜻하지는 않아요." else "최근 24시간에 마친 운동이 없어요. 기록이 없다고 건강 상태를 뜻하지는 않아요.")
            return
        }
        lastReadEmpty = false
        val minutes = (Duration.between(record.startTime, record.endTime).seconds / 60.0).roundToInt()
        check(minutes in 1..240) { "최근 운동이 지원 범위(1~240분)를 벗어났어요." }
        val selected = WorkoutRecord(activityName(record.exerciseType), minutes, record.endTime, "health_connect", record.metadata.id, record.metadata.dataOrigin.packageName)
        SharePolicy.requireFresh(selected.end, now); workout = selected
    }

    private fun clearSummary() { workout = null; consent = false }
    private fun requirePairing(): PairingSession = pairing ?: error("차량 연결부터 해 주세요. 연결 › 차량에서 할 수 있어요.")
    private fun activityName(type: Int) = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walking"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "running"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "cycling"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "strength"
        else -> "other"
    }
    private fun isDebugBuild() = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    private fun validateBase(raw: String): String {
        // Submission builds carry no HTTPS relay address (no server, no local CA).
        if (!isDebugBuild() && !raw.trim().startsWith("https://")) throw IllegalArgumentException(RELEASE_NO_SERVER)
        return SharePolicy.validateBase(raw, isDebugBuild())
    }

    private fun task(block: suspend () -> Unit) {
        if (busy) return
        lifecycleScope.launch {
            busy = true; shell.notifyChanged()
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: RelayRequestException) {
                pairingConfirmed = false
                if (pairing?.revocationPending == true) {
                    validationOnResumePending = false
                    if (e.statusCode in setOf(401, 404, 410)) {
                        pairing = null; confirmationCode = null; lastSent = null
                        CompanionFormatters.savePairing(this@MainActivity, null)
                        pairingLabel = "연결 종료 · 서버 삭제 확인 없음"
                        shell.report("이전 연결로 더 요청할 수 없어 다시 확인할 정보를 지웠어요. 서버의 삭제 완료 응답은 받지 못했어요.")
                    } else {
                        pairingLabel = "공유 중단 · 서버 삭제를 다시 확인해 주세요"
                        pairing = pairing?.copy(confirmed = false, label = pairingLabel)
                            ?.also { CompanionFormatters.savePairing(this@MainActivity, it) }
                        shell.report("삭제를 아직 확인하지 못했어요. 보내기는 멈췄고 이 폰의 미리보기는 지웠어요. ‘삭제 다시 확인’을 눌러 주세요.")
                    }
                } else if (e.statusCode in setOf(401, 404, 410)) {
                    pairing = null; confirmationCode = null; lastSent = null
                    CompanionFormatters.savePairing(this@MainActivity, null)
                    clearSummary()
                    pairingLabel = "연결을 다시 해주세요 · 이전 연결을 사용할 수 없어요"
                    shell.report("이전 연결을 더 쓸 수 없어요. 이 폰의 연결과 미리보기를 비웠어요. 서버에서 지웠다는 뜻은 아니에요.")
                } else {
                    pairing = pairing?.copy(confirmed = false, label = "연결 상태 재확인 필요 · 아직 공유할 수 없어요")
                        ?.also { CompanionFormatters.savePairing(this@MainActivity, it) }
                    if (pairing != null) pairingLabel = "연결 상태 재확인 필요 · 아직 공유할 수 없어요"
                    shell.report(e.message ?: "연결 상태를 다시 확인해 주세요.")
                }
            }
            catch (_: SecurityException) { clearSummary(); shell.report("건강 권한이 꺼졌거나 쓸 수 없어요. 이 폰의 미리보기를 비웠어요.") }
            catch (e: Exception) {
                if (pairing != null && !pairingConfirmed) {
                    pairing = pairing?.copy(confirmed = false)?.also { CompanionFormatters.savePairing(this@MainActivity, it) }
                }
                shell.report(e.message ?: "마치지 못했어요. 저절로 다시 시도하지 않아요.")
            }
            finally {
                busy = false; refreshHome(); updateAppearance()
                if (foreground && validationOnResumePending && pairing != null && pairing?.revocationPending != true) checkPairingAutomaticallyWhenIdle()
            }
        }
    }
}

/** Shown when a submission build is asked to pair: it has no relay server address. */
internal const val RELEASE_NO_SERVER = "이 빌드는 연결 서버 없이 동작해서 차와 연결할 수 없어요."
