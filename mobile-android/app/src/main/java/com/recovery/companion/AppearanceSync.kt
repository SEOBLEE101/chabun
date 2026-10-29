package com.recovery.companion

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.Executors

/** Foreground, read-only appearance polling through the existing preset connection. Callbacks run on the main thread. */
class AppearanceSync(context: Context) {
    private val app = context.applicationContext
    private val prefs = AppearancePreferences(app)
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var closed = false
    private var generation = 0L
    private var requestSequence = 0L
    private var reading = false
    private var includeVehiclesWhenOverridden = false
    private var updated: () -> Unit = {}
    var vehicles: List<AppearancePolicy.Report> = emptyList(); private set
    var serverTime: Long = 0; private set
    private var lastReceivedElapsed = 0L
    private var lastRequestStartedElapsed = 0L
    private val poll = object : Runnable {
        override fun run() {
            if (!active || closed) return
            read()
            handler.postDelayed(this, 5_000)
        }
    }
    private data class Connection(val origin: String, val token: String)
    private fun connection(): Connection? {
        val origin = runCatching { PresetSyncPolicy.fromRelayOrigin(CompanionFormatters.loadRelayBase(app, app.getString(R.string.relay_default_url))) }.getOrNull() ?: return null
        val session = app.getSharedPreferences("pitin_preset_sync_session", Context.MODE_PRIVATE)
        if (session.getLong("revocationRequestedAt", 0) > 0) return null
        if (session.getString("origin", null) != origin || !session.getBoolean("approved", false)) return null
        val token = session.getString("token", null)?.takeIf { it.isNotBlank() } ?: return null
        return Connection(origin, token)
    }
    fun connectionAvailable() = connection() != null

    fun start(onUpdate: () -> Unit, includeVehiclesWhenOverridden: Boolean = false) {
        if (closed) return
        handler.removeCallbacks(poll)
        updated = onUpdate
        this.includeVehiclesWhenOverridden = includeVehiclesWhenOverridden
        active = true; generation++
        prefs.markUnavailable("checking")
        updated()
        handler.post(poll)
    }
    fun stop() {
        active = false; generation++; requestSequence++
        handler.removeCallbacks(poll)
    }
    fun close() { stop(); closed = true; worker.shutdownNow() }
    fun refresh() {
        if (!active || closed) return
        generation++
        prefs.markUnavailable("checking")
        updated()
        read()
    }
    private fun read() {
        if (!active || closed || reading) return
        if (prefs.mode != AppearancePreferences.Mode.FOLLOW_VEHICLE && !includeVehiclesWhenOverridden) return
        val auth = connection()
        if (auth == null) {
            vehicles = emptyList()
            val deleting = app.getSharedPreferences("pitin_preset_sync_session", Context.MODE_PRIVATE).getLong("revocationRequestedAt", 0) > 0
            prefs.markUnavailable(if (deleting) "deletion_pending" else "needs_connection")
            updated()
            return
        }
        reading = true
        val ticket = generation
        val sequence = ++requestSequence
        val selectedAtRequest = prefs.selectionVersion
        val requestedAt = SystemClock.elapsedRealtime()
        worker.execute {
            val result = runCatching { PresetSyncClient(auth.origin).call("GET", "/appearance", auth.token) }
            handler.post {
                reading = false
                if (!active || closed) return@post
                if (ticket != generation || sequence != requestSequence || selectedAtRequest != prefs.selectionVersion || auth != connection()) {
                    read()
                    return@post
                }
                result.fold({ reply ->
                    if (reply.code == 200) {
                        runCatching {
                            val packet = decode(reply.body)
                            val receivedAt = SystemClock.elapsedRealtime()
                            prefs.accept(packet, requestedAt, receivedAt)
                            vehicles = packet.vehicles; serverTime = packet.serverTime
                            lastRequestStartedElapsed = requestedAt; lastReceivedElapsed = receivedAt
                        }.onFailure {
                            vehicles = emptyList()
                            prefs.markUnavailable("unavailable")
                        }
                    } else {
                        vehicles = emptyList()
                        prefs.markUnavailable(if (reply.code == 401 || reply.code == 403) "needs_connection" else "unavailable")
                    }
                }, { prefs.markUnavailable("unavailable") })
                updated()
            }
        }
    }
    fun reportIsFresh(report: AppearancePolicy.Report): Boolean {
        val remaining = AppearancePolicy.remaining(serverTime, report.expiresAt, lastRequestStartedElapsed, lastReceivedElapsed)
        return AppearancePolicy.isFresh(lastReceivedElapsed, remaining, SystemClock.elapsedRealtime())
    }
    private fun decode(body: JSONObject): AppearancePolicy.Packet {
        val appearance = body.getJSONObject("appearance")
        val sync = body.getJSONObject("sync")
        val list = appearance.getJSONArray("vehicles")
        val reports = (0 until list.length()).map { i ->
            val item = list.getJSONObject(i)
            AppearancePolicy.Report(item.getString("deviceId"), item.getString("themeId"), item.getInt("paletteVersion"),
                item.getLong("revision"), Instant.parse(item.getString("reportedAt")).toEpochMilli(),
                Instant.parse(item.getString("expiresAt")).toEpochMilli())
        }
        return AppearancePolicy.Packet(sync.getString("epoch"), Instant.parse(appearance.getString("serverTime")).toEpochMilli(),
            Instant.parse(sync.getString("expiresAt")).toEpochMilli(), reports)
    }
}
