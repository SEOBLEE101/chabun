package com.recovery.companion

import java.time.LocalDate

/**
 * 스트레스 푸는 곳: 즐겨찾기와 '자주 가는 곳'. 기기 안에만 둔다.
 * 자주 가는 곳은 위치 추적이 아니라 앱 안에서 일어난 일(목적지로 보냄, "여기 왔어요")만 센다.
 */
enum class PlaceKind(val id: String, val label: String, val query: String) {
    PARK("park", "공원·산책로", "공원"),
    CAFE("cafe", "카페", "카페"),
    BOOKSTORE("bookstore", "서점", "서점"),
    SAUNA("sauna", "사우나·찜질방", "찜질방"),
    OTHER("other", "기타", "");
    companion object { fun of(id: String): PlaceKind = entries.firstOrNull { it.id == id } ?: OTHER }
}

data class Place(
    val id: String,
    val name: String,
    val address: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val kind: PlaceKind = PlaceKind.OTHER,
    val favorite: Boolean = false,
    val visits: Int = 0,
    val lastUsed: LocalDate? = null,
    val source: String = "manual",
) {
    fun validated(): Place {
        require(id.isNotBlank() && id.length <= 64 && id.none { it == '\t' || it == '\n' }) { "장소 ID가 올바르지 않아요." }
        val n = name.trim()
        require(n.length in 1..PlaceBook.NAME_MAX) { "장소 이름은 1~${PlaceBook.NAME_MAX}자로 적어 주세요." }
        require(address.length <= PlaceBook.ADDRESS_MAX) { "주소가 너무 길어요." }
        require((lat == null) == (lon == null)) { "좌표는 위도와 경도가 함께 있어야 해요." }
        if (lat != null && lon != null) require(lat in 33.0..39.0 && lon in 124.0..132.0) { "국내 좌표만 저장해요." }
        require(visits >= 0 && source in setOf("manual", "kakao"))
        return copy(name = n)
    }
}

class PlaceBook(places: List<Place>, val countVisits: Boolean) {
    val places: List<Place> = places.map { it.validated() }.distinctBy { it.id }

    init { require(this.places.size <= MAX_PLACES) { "장소는 ${MAX_PLACES}곳까지 둘 수 있어요." } }

    fun find(id: String): Place? = places.firstOrNull { it.id == id }

    /** 즐겨찾기로 넣는다. 이미 있으면 즐겨찾기만 켜고 이름·주소는 새 값으로. */
    fun addFavorite(place: Place): PlaceBook {
        val p = place.validated().copy(favorite = true)
        val old = find(p.id)
        if (old == null) require(places.size < MAX_PLACES) { "장소는 ${MAX_PLACES}곳까지 둘 수 있어요. 안 쓰는 곳을 지워 주세요." }
        val merged = old?.let { p.copy(visits = it.visits, lastUsed = it.lastUsed) } ?: p
        return with(if (old == null) places + merged else places.map { if (it.id == p.id) merged else it })
    }

    fun rename(id: String, name: String): PlaceBook = with(places.map { if (it.id == id) it.copy(name = name).validated() else it })

    fun unfavorite(id: String): PlaceBook = with(places.mapNotNull {
        when {
            it.id != id -> it
            countVisits && it.visits > 0 -> it.copy(favorite = false)
            else -> null
        }
    })

    fun remove(id: String): PlaceBook = with(places.filterNot { it.id == id })

    /** 앱 안에서 그곳을 썼다(목적지로 보냄, "여기 왔어요"). 기록이 꺼져 있으면 아무것도 바꾸지 않는다. */
    fun recordUse(place: Place, on: LocalDate): PlaceBook {
        if (!countVisits) return this
        val old = find(place.id)
        if (old == null && places.size >= MAX_PLACES) return pruned(on).let { if (it.places.size < MAX_PLACES) it.recordUse(place, on) else this }
        val next = (old ?: place.validated().copy(favorite = false, visits = 0)).let { it.copy(visits = (it.visits + 1).coerceAtMost(VISITS_CAP), lastUsed = on) }
        return with(if (old == null) places + next else places.map { if (it.id == next.id) next else it })
    }

    fun favorites(): List<Place> = places.filter { it.favorite }.sortedWith(compareByDescending<Place> { it.lastUsed }.thenBy { it.name })

    /** 최근 90일 안에 두 번 넘게 쓴 곳, 많이 쓴 순·최근 순. 즐겨찾기도 포함한다. */
    fun frequent(today: LocalDate, top: Int = 5): List<Place> {
        if (!countVisits) return emptyList()
        val from = today.minusDays(RECENT_DAYS)
        return places.filter { it.visits >= FREQUENT_MIN && it.lastUsed != null && !it.lastUsed.isBefore(from) }
            .sortedWith(compareByDescending<Place> { it.visits }.thenByDescending { it.lastUsed }.thenBy { it.name })
            .take(top)
    }

    /** 즐겨찾기가 아니고 90일 넘게 안 쓴 곳은 지운다. */
    fun pruned(today: LocalDate): PlaceBook {
        val from = today.minusDays(RECENT_DAYS)
        return with(places.filter { it.favorite || (it.lastUsed != null && !it.lastUsed.isBefore(from)) })
    }

    /** 자주 가는 곳 기록을 끈다: 즐겨찾기는 남기되 횟수·날짜는 지우고, 즐겨찾기가 아닌 곳은 지운다. */
    fun visitsOff(): PlaceBook = PlaceBook(places.filter { it.favorite }.map { it.copy(visits = 0, lastUsed = null) }, false)

    fun visitsOn(): PlaceBook = PlaceBook(places, true)

    private fun with(next: List<Place>) = PlaceBook(next, countVisits)

    /** 기기 안 저장용 한 줄 한 곳(탭 구분, 역슬래시 이스케이프). */
    fun encode(): String = buildString {
        append(FORMAT).append(if (countVisits) "\t1" else "\t0").append('\n')
        places.forEach { p ->
            append(listOf(p.id, p.name, p.address, p.lat?.toString() ?: "", p.lon?.toString() ?: "", p.kind.id,
                if (p.favorite) "1" else "0", p.visits.toString(), p.lastUsed?.toString() ?: "", p.source).joinToString("\t") { esc(it) }).append('\n')
        }
    }

    companion object {
        const val MAX_PLACES = 50
        const val NAME_MAX = 40
        const val ADDRESS_MAX = 120
        const val RECENT_DAYS = 90L
        const val FREQUENT_MIN = 2
        const val VISITS_CAP = 9999
        private const val FORMAT = "placebook/1"

        fun empty() = PlaceBook(emptyList(), false)

        /** 깨진 줄은 건너뛰고, 머리줄이 다르면 빈 책으로 시작한다(저장된 데이터가 앱을 멈추지 않게). */
        fun decode(text: String?): PlaceBook {
            val lines = text?.split('\n')?.filter { it.isNotEmpty() } ?: return empty()
            val head = lines.firstOrNull()?.split('\t') ?: return empty()
            if (head.size != 2 || head[0] != FORMAT) return empty()
            val list = lines.drop(1).mapNotNull { line ->
                runCatching {
                    val f = splitEscaped(line)
                    require(f.size == 10)
                    Place(f[0], f[1], f[2], f[3].takeIf { it.isNotEmpty() }?.toDouble(), f[4].takeIf { it.isNotEmpty() }?.toDouble(),
                        PlaceKind.of(f[5]), f[6] == "1", f[7].toInt(), f[8].takeIf { it.isNotEmpty() }?.let(LocalDate::parse), f[9]).validated()
                }.getOrNull()
            }.distinctBy { it.id }.take(MAX_PLACES)
            return PlaceBook(list, head[1] == "1")
        }

        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

        private fun splitEscaped(line: String): List<String> {
            val out = mutableListOf<String>(); val cur = StringBuilder(); var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '\\' && i + 1 < line.length -> { cur.append(when (line[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> line[i + 1] }); i += 2; continue }
                    c == '\t' -> { out.add(cur.toString()); cur.clear() }
                    else -> cur.append(c)
                }
                i++
            }
            out.add(cur.toString())
            return out
        }
    }
}
