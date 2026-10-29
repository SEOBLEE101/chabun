package com.recovery.companion

import java.net.URI

/** Pure policy shared by transport and foreground state handling. */
object PresetSyncPolicy {
    fun origin(value: String): String {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.host in setOf("127.0.0.1", "localhost", "10.0.2.2") && uri.port == 9447)
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in listOf("", "/"))
        return "https://${uri.host}:9447"
    }
    fun fromRelayOrigin(relayOrigin: String): String = runCatching {
        val uri = URI(relayOrigin.trim().replace(Regex("/+$"), ""))
        val host = if (uri.host in setOf("127.0.0.1", "localhost", "10.0.2.2")) uri.host else "127.0.0.1"
        origin("https://$host:9447")
    }.getOrDefault("https://127.0.0.1:9447")
    fun accepts(requestGeneration: Long, generation: Long, oldEpoch: String?, newEpoch: String, oldRevision: Long, newRevision: Long): Boolean =
        requestGeneration == generation && (oldEpoch == null || oldEpoch != newEpoch || newRevision >= oldRevision)
}
