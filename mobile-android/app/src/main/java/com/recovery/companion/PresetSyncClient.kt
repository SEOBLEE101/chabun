package com.recovery.companion

import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Uses platform trust/hostname checks, including the opt-in localTlsDebug public CA. */
class PresetSyncClient(origin: String = "https://127.0.0.1:9447") {
    private val base = PresetSyncPolicy.origin(origin) + "/api/preset-sync/v1"
    data class Reply(val code: Int, val body: JSONObject)
    private fun plain(value: Any?, depth: Int = 0): Any? {
        require(depth <= 24)
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> value.keys().asSequence().associateWith { plain(value.get(it), depth + 1) }
            is org.json.JSONArray -> (0 until value.length()).map { plain(value.get(it), depth + 1) }
            else -> value
        }
    }
    fun call(method: String, path: String, token: String? = null, body: JSONObject? = null): Reply {
        require(path in setOf("/pairings/claim", "/pairings/status", "/state", "/commands", "/session", "/appearance", "/places/search"))
        val connection = URL(base + path).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= 16384)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            require(code !in 300..399) { "Redirect rejected" }
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 262144); output.write(buffer, 0, count) }
                output.toByteArray()
            } ?: byteArrayOf()
            require(bytes.size <= 262144)
            val text = String(bytes, Charsets.UTF_8)
            if (code in setOf(401, 403, 404, 410)) {
                val decoded = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("error", "unauthorized") }
                return Reply(code, decoded)
            }
            val decoded = JSONObject(text)
            PresetSyncResponsePolicy.validate(path, code, plain(decoded), body?.optString("requestId"))
            return Reply(code, decoded)
        } finally { connection.disconnect() }
    }
}
