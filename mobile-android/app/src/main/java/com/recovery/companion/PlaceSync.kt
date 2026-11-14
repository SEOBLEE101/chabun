package com.recovery.companion

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * 쉬어 갈 곳의 바깥 연결: '취향 함께 쓰기' 연결(승인된 폰 세션)으로
 * - 장소 찾기: 검색어와 종류만 서버로 보낸다(좌표 없음). 서버가 카카오 키를 갖고 대신 찾는다.
 * - 차로 보내기: 고른 한 곳의 이름·주소·좌표만 보낸다(방문 횟수·즐겨찾기 여부는 보내지 않는다).
 */
object PlaceSync {
    private fun session(ctx: Context): Pair<String, String>? {
        val p = ctx.getSharedPreferences("pitin_preset_sync_session", Context.MODE_PRIVATE)
        val token = p.getString("token", null) ?: return null
        if (!p.getBoolean("approved", false)) return null
        return (p.getString("origin", null) ?: return null) to token
    }

    fun connected(ctx: Context): Boolean = session(ctx) != null

    private fun failure(reply: PresetSyncClient.Reply, fallback: String): Nothing {
        val message = reply.body.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        throw IllegalArgumentException(when (reply.code) {
            401, 403 -> "취향 함께 쓰기 연결을 다시 확인해 주세요."
            else -> message ?: fallback
        })
    }

    suspend fun search(ctx: Context, query: String, kind: PlaceKind): List<Place> = withContext(Dispatchers.IO) {
        val (origin, token) = session(ctx) ?: throw IllegalArgumentException("먼저 연결 › 취향 함께 쓰기를 이어 주세요.")
        val q = query.trim()
        require(q.length in 1..40) { "찾을 말을 1~40자로 적어 주세요." }
        // 종류 칩의 말(공원·카페·서점·찜질방)이 검색어에 없으면 붙인다: '성수' + 카페 → '성수 카페'.
        require(q.none { it < ' ' || it == '\u007f' }) { "찾을 말에 줄바꿈이나 특수 문자는 넣을 수 없어요." }
        val text = if (kind.query.isNotBlank() && !q.contains(kind.query) && q.length + 1 + kind.query.length <= 40) "$q ${kind.query}" else q
        val reply = PresetSyncClient(origin).call("POST", "/places/search", token, JSONObject().put("query", text).put("kind", kind.id))
        if (reply.code !in 200..299) failure(reply, "장소를 찾지 못했어요.")
        val list = reply.body.getJSONArray("places")
        (0 until list.length()).mapNotNull { i ->
            val o = list.getJSONObject(i)
            runCatching {
                Place(o.getString("id"), o.getString("name"), o.optString("address", ""),
                    if (o.isNull("lat")) null else o.getDouble("lat"), if (o.isNull("lon")) null else o.getDouble("lon"),
                    kind, source = "kakao").validated()
            }.getOrNull()
        }
    }

    suspend fun sendToCar(ctx: Context, place: Place) = withContext(Dispatchers.IO) {
        val (origin, token) = session(ctx) ?: throw IllegalArgumentException("먼저 연결 › 취향 함께 쓰기를 이어 주세요.")
        val client = PresetSyncClient(origin)
        val state = client.call("GET", "/state", token)
        if (state.code !in 200..299) failure(state, "차와의 공유 상태를 읽지 못했어요.")
        val epoch = state.body.getJSONObject("sync").getString("epoch")
        val revision = state.body.getJSONObject("state").getLong("revision")
        val p = place.validated()
        val payload = JSONObject().put("place", JSONObject().put("name", p.name).put("address", p.address)
            .put("lat", p.lat ?: JSONObject.NULL).put("lon", p.lon ?: JSONObject.NULL))
        val requestId = UUID.randomUUID().toString()
        val reply = client.call("POST", "/commands", token, JSONObject().put("action", "propose_place").put("payload", payload)
            .put("expectedEpoch", epoch).put("expectedRevision", revision).put("requestId", requestId))
        if (reply.code == 409) throw IllegalArgumentException("다른 화면에서 막 바뀌었어요. 한 번 더 보내 주세요.")
        if (reply.code !in 200..299) failure(reply, "차로 보내지 못했어요.")
    }
}
