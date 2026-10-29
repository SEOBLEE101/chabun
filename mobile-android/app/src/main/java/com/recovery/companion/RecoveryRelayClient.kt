package com.recovery.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.io.IOException

class RelayRequestException(val statusCode: Int?, message: String) : Exception(message)

/** Health summary transport only; preset sync has its own credentials and API. */
class RecoveryRelayClient {
    suspend fun request(relay: String, path: String, method: String, body: JSONObject? = null, token: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val connection = URI(relay + path).toURL().openConnection() as HttpURLConnection
        var responseCode: Int? = null
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            responseCode = code
            val ok = code in 200..299
            val stream = if (ok) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (result.length + count > 65_536) throw IOException("Oversized relay response")
                    result.append(buffer, 0, count)
                }
                result.toString()
            }.orEmpty()
            val answer = if (ok) { if (text.isBlank()) JSONObject() else JSONObject(text) }
                else runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (!ok) throw RelayRequestException(code, answer.optJSONObject("error")?.optString("message") ?: "서버 요청에 실패했어요 ($code).")
            answer
        } catch (e: CancellationException) { throw e }
          catch (e: RelayRequestException) { throw e }
          catch (_: Exception) { throw RelayRequestException(responseCode?.takeIf { it !in 200..299 }, "서버 응답을 확인하지 못했어요. 연결 상태를 다시 확인해 주세요.")
        } finally { connection.disconnect() }
    }
}
