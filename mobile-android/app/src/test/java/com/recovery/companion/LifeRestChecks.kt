package com.recovery.companion

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Portable checks for the life-wide rest policies (DayRhythm, Warmup, HeartNudge, PlaceBook); no Android device. */
object LifeRestChecks {
    @JvmStatic fun main(args: Array<String>) {
        var checks = 0
        fun verify(value: Boolean, what: String = "") { check(value) { "failed: $what" }; checks++ }
        fun rejects(action: () -> Unit) { var rejected = false; try { action() } catch (_: IllegalArgumentException) { rejected = true }; verify(rejected, "rejects") }

        // ---- DayRhythm
        val mon = LocalDate.of(2026, 11, 16) // Monday
        check(mon.dayOfWeek == DayOfWeek.MONDAY)
        val on = CommuteSettings(enabled = true)
        fun at(d: LocalDate, h: Int, m: Int) = LocalDateTime.of(d, LocalTime.of(h, m))
        val R = DayRhythmPolicy
        verify(R.momentAt(at(mon, 8, 0), CommuteSettings(), null, null) == DayMoment.NONE, "off by default")
        verify(R.momentAt(at(mon, 8, 0), on, null, null) == DayMoment.MORNING_COMMUTE, "morning start edge")
        verify(R.momentAt(at(mon, 7, 59), on, null, null) == DayMoment.NONE, "before morning window")
        verify(R.momentAt(at(mon, 8, 59), on, null, null) == DayMoment.MORNING_COMMUTE)
        verify(R.momentAt(at(mon, 9, 0), on, null, null) == DayMoment.NONE, "morning end exclusive")
        verify(R.momentAt(at(mon, 18, 15), on, null, null) == DayMoment.EVENING_COMMUTE, "evening start edge")
        verify(R.momentAt(at(mon, 18, 14), on, null, null) == DayMoment.NONE)
        verify(R.momentAt(at(mon, 19, 29), on, null, null) == DayMoment.EVENING_COMMUTE)
        verify(R.momentAt(at(mon, 19, 30), on, null, null) == DayMoment.NONE, "evening end exclusive")
        val sat = mon.plusDays(5)
        verify(R.momentAt(at(sat, 8, 30), on, null, null) == DayMoment.NONE, "weekend not chosen")
        // 자정 넘김: 금요일 23:30 퇴근 → 토요일 00:20은 금요일 창
        val late = on.copy(morning = LocalTime.of(9, 0), evening = LocalTime.of(23, 30))
        val fri = mon.plusDays(4)
        verify(R.momentAt(at(sat, 0, 20), late, null, null) == DayMoment.EVENING_COMMUTE, "crosses midnight from Friday")
        verify(R.momentAt(at(mon.plusDays(7).minusDays(1), 0, 20), late, null, null) == DayMoment.NONE, "Sunday after Saturday (not chosen)")
        verify(R.momentAt(at(fri, 23, 14), late, null, null) == DayMoment.NONE)
        // 자정 앞으로 넘김: 00:10 출근, 일요일 23:45는 월요일 창
        val early = on.copy(morning = LocalTime.of(0, 10), evening = LocalTime.of(17, 0))
        verify(R.momentAt(at(mon.minusDays(1), 23, 45), early, null, null) == DayMoment.MORNING_COMMUTE, "Sunday 23:45 is Monday's window")
        rejects { on.copy(days = emptySet()).validated() }
        rejects { on.copy(evening = LocalTime.of(9, 30)).validated() }
        rejects { on.copy(morning = LocalTime.of(0, 10), evening = LocalTime.of(23, 30)).validated() } // 40 min apart round the clock
        verify(on.copy(morning = LocalTime.of(1, 30), evening = LocalTime.of(23, 30)).validated().enabled, "2 h round the clock is fine")
        verify(CommuteSettings(enabled = false, days = emptySet()).validated().days.isEmpty(), "off may have no days")
        rejects { WorkoutPlan(mon, "swimming", LocalTime.of(19, 0)).validated() }

        val plan = WorkoutPlan(mon, "running", LocalTime.of(19, 0))
        verify(R.momentAt(at(mon, 18, 40), on, plan, null) == DayMoment.WARMUP, "warmup beats evening commute")
        verify(R.momentAt(at(mon, 18, 39), on, plan, null) == DayMoment.EVENING_COMMUTE)
        verify(R.momentAt(at(mon, 19, 9), on, plan, null) == DayMoment.WARMUP)
        verify(R.momentAt(at(mon, 19, 10), on, plan, null) == DayMoment.EVENING_COMMUTE, "warmup end exclusive, still evening window")
        verify(R.momentAt(at(mon, 18, 50), CommuteSettings(), plan, null) == DayMoment.WARMUP, "warmup works with commute off")
        val end = at(mon, 19, 45)
        verify(R.momentAt(at(mon, 19, 50), on, plan, end) == DayMoment.AFTER_WORKOUT)
        verify(R.momentAt(end.plusMinutes(89), on, plan, end) == DayMoment.AFTER_WORKOUT)
        verify(R.momentAt(end.plusMinutes(90), on, plan, end) == DayMoment.NONE, "after-workout ends at 90 min")
        verify(R.momentAt(at(mon, 19, 0), on, plan, at(mon, 18, 29)) != DayMoment.AFTER_WORKOUT, "workout before plan-30m is not this plan")
        verify(R.momentAt(at(mon, 19, 0), on, plan, at(mon, 18, 30)) == DayMoment.AFTER_WORKOUT, "early-slack edge counts")
        verify(R.momentAt(at(mon, 18, 50), on, plan, at(mon, 19, 30)) == DayMoment.WARMUP, "future end time ignored")
        verify(R.momentAt(at(mon, 19, 5), CommuteSettings(), plan, at(mon, 18, 45)) == DayMoment.AFTER_WORKOUT, "done early: no warmup")
        verify(R.activePlan(plan, at(mon, 8, 0)) == plan && R.activePlan(plan, at(mon, 23, 59)) == plan, "plan lives through its day")
        verify(R.activePlan(plan, at(mon.plusDays(1), 0, 0)) == null, "day plan expires at next midnight")
        verify(R.activePlan(plan, at(mon.minusDays(1), 23, 0)) == null, "not before its day")
        val latePlan = WorkoutPlan(mon, "walking", LocalTime.of(23, 40))
        verify(R.activePlan(latePlan, at(mon.plusDays(1), 2, 39)) == latePlan, "night plan lives 3 h")
        verify(R.activePlan(latePlan, at(mon.plusDays(1), 2, 40)) == null, "night plan expires at start + 3 h")
        verify(R.momentAt(at(mon.plusDays(1), 0, 20), CommuteSettings(), latePlan, at(mon.plusDays(1), 0, 10)) == DayMoment.AFTER_WORKOUT, "after-midnight finish still gets rest")
        verify(R.planExpiresAt(WorkoutPlan(mon, "running", LocalTime.of(20, 0))) == mon.plusDays(1).atStartOfDay(), "21h+ grace not needed")
        verify(R.presetAfterWorkout("running") == "cool_down" && R.presetAfterWorkout("strength") == "quiet_rest" && R.presetAfterWorkout("walking") == "gentle_refresh")
        val ids = Preset.builtins().map { it.id }.toSet()
        verify(listOf("running", "cycling", "walking", "strength", "other").all { R.presetAfterWorkout(it) in ids }, "presets exist")
        verify(on.morningPresetId in ids && on.eveningPresetId in ids)
        verify(R.card(DayMoment.EVENING_COMMUTE, on, null)!!.presetId == "quiet_rest")
        verify(R.card(DayMoment.WARMUP, on, plan)!!.action == "warmup" && R.card(DayMoment.WARMUP, on, null) == null)
        verify(R.card(DayMoment.NONE, on, plan) == null)
        val banned = listOf("진단", "질환", "치료", "위험", "스트레스 지수", "자동으로 시작")
        DayMoment.entries.forEach { m -> R.card(m, on, plan)?.let { c -> verify(banned.none { (c.title + c.body).contains(it) }, "card wording $m") } }

        // 퇴근 알림: 다음 켜진 요일의 18:15
        verify(R.nextReminder(at(mon, 12, 0), CommuteSettings()) == null, "reminder off with commute off")
        verify(R.nextReminder(at(mon, 12, 0), on) == at(mon, 18, 15), "today's window start")
        verify(R.nextReminder(at(mon, 18, 15), on) == at(mon.plusDays(1), 18, 15), "strictly after now")
        verify(R.nextReminder(at(fri, 19, 0), on) == at(mon.plusDays(7), 18, 15), "skips the weekend")
        verify(R.nextReminder(at(mon, 12, 0), on.copy(days = setOf(DayOfWeek.SUNDAY))) == at(mon.plusDays(6), 18, 15), "one day a week")
        verify(R.nextReminder(at(mon, 23, 50), late.copy(days = setOf(DayOfWeek.MONDAY))) == at(mon.plusDays(7), 23, 15), "late evening next week")
        val pastMidnight = on.copy(morning = LocalTime.of(14, 0), evening = LocalTime.of(0, 10), days = setOf(DayOfWeek.MONDAY))
        verify(R.nextReminder(at(mon.minusDays(1), 23, 56), pastMidnight) == at(mon.plusDays(6), 23, 55), "00:10 evening: next week's Sunday 23:55 is still found")
        verify(R.momentAt(at(mon.minusDays(1), 23, 56), pastMidnight, null, null) == DayMoment.EVENING_COMMUTE, "Sunday 23:56 belongs to Monday's window")

        // ---- Warmup
        WORKOUT_TYPES.forEach { t ->
            val total = WarmupGuide.totalSeconds(t)
            verify(total in 240..360, "warmup $t about 5 min ($total)")
            verify(WarmupGuide.steps(t).all { it.seconds in 30..150 && it.title.isNotBlank() })
            verify(WarmupGuide.steps(t).none { s -> banned.any { (s.title + s.detail).contains(it) } })
        }
        verify(WarmupGuide.NOTE.contains("멈추세요"))

        // ---- HeartNudge
        val H = HeartNudgePolicy
        val zone = ZoneId.of("Asia/Seoul")
        val now = Instant.parse("2026-11-16T09:00:00Z")
        fun series(from: Instant, count: Int, stepSec: Long, bpm: (Int) -> Int) = (0 until count).map { HrSample(from.plusSeconds(it * stepSec), bpm(it)) }
        val week = series(now.minus(Duration.ofDays(6)), 400, 1200) { 60 + it % 20 } // 60..79
        val base = H.baseline(week, emptyList(), now)!!
        verify(base == 63, "20th percentile nearest rank ($base)")
        verify(H.baseline(week.take(199), emptyList(), now) == null, "needs 200 samples")
        verify(H.baseline(week.take(200), emptyList(), now) != null)
        verify(H.baseline(week + HrSample(now.minusSeconds(60), 10), emptyList(), now) == base, "implausible ignored")
        val old = series(now.minus(Duration.ofDays(8)), 300, 60) { 40 }
        verify(H.baseline(old + week, emptyList(), now) == base, "older than 7 days ignored")
        val exAll = listOf(Span(now.minus(Duration.ofDays(7)), now))
        verify(H.baseline(week, exAll, now) == null, "exercise samples excluded")
        val high = series(now.minus(Duration.ofMinutes(9)), 10, 60) { 95 } // 95 >= 63+25
        val mem = NudgeMemory()
        verify(H.decide(true, high, emptyList(), base, mem, now, zone) == NudgeResult.SUGGEST)
        verify(H.decide(false, high, emptyList(), base, mem, now, zone) == NudgeResult.OFF)
        verify(H.decide(true, high, emptyList(), null, mem, now, zone) == NudgeResult.NO_BASELINE)
        val justBelow = series(now.minus(Duration.ofMinutes(9)), 10, 60) { 87 }
        verify(H.decide(true, justBelow, emptyList(), base, mem, now, zone) == NudgeResult.NOT_ELEVATED, "threshold 88")
        val atThreshold = series(now.minus(Duration.ofMinutes(9)), 10, 60) { 88 }
        verify(H.decide(true, atThreshold, emptyList(), base, mem, now, zone) == NudgeResult.SUGGEST, "threshold inclusive")
        val spike = series(now.minus(Duration.ofMinutes(9)), 10, 60) { if (it < 6) 70 else 120 }
        verify(H.decide(true, spike, emptyList(), base, mem, now, zone) == NudgeResult.NOT_ELEVATED, "short spike is not sustained")
        val earlyBurst = (0L until 5L).map { HrSample(now.minusSeconds(570 - it * 10), 95) }
        verify(H.decide(true, earlyBurst, emptyList(), base, mem, now, zone) == NudgeResult.NOT_ENOUGH_DATA, "40 s burst is not an 8 min run")
        val recovered = earlyBurst + listOf(HrSample(now.minusSeconds(300), 65), HrSample(now.minusSeconds(60), 64))
        verify(H.decide(true, recovered, emptyList(), base, mem, now, zone) != NudgeResult.SUGGEST, "recovered heart rate does not suggest")
        val watchOff = series(now.minus(Duration.ofMinutes(10)), 6, 60) { 100 } // last sample 5 min ago
        verify(H.decide(true, watchOff, emptyList(), base, mem, now, zone) == NudgeResult.NOT_ENOUGH_DATA, "no recent sample: watch off")
        val sixOfTen = series(now.minus(Duration.ofMinutes(9)), 10, 60) { if (it < 4) 70 else 95 }
        verify(H.decide(true, sixOfTen, emptyList(), base, mem, now, zone) == NudgeResult.NOT_ELEVATED, "median high but share 0.6")
        val short = series(now.minus(Duration.ofMinutes(7)), 8, 60) { 100 }
        verify(H.decide(true, short, emptyList(), base, mem, now, zone) == NudgeResult.NOT_ENOUGH_DATA, "needs 8 min span")
        verify(H.decide(true, high.take(4), emptyList(), base, mem, now, zone) == NudgeResult.NOT_ENOUGH_DATA, "needs 5 samples")
        val ex = listOf(Span(now.minus(Duration.ofMinutes(100)), now.minus(Duration.ofMinutes(69))))
        verify(H.decide(true, high, ex, base, mem, now, zone) == NudgeResult.EXERCISE, "within 60 min after exercise")
        val exOld = listOf(Span(now.minus(Duration.ofMinutes(100)), now.minus(Duration.ofMinutes(71))))
        verify(H.decide(true, high, exOld, base, mem, now, zone) == NudgeResult.SUGGEST, "exercise long enough ago")
        verify(H.decide(true, high, emptyList(), base, NudgeMemory(lastShownAt = now.minus(Duration.ofMinutes(179))), now, zone) == NudgeResult.COOLDOWN)
        verify(H.decide(true, high, emptyList(), base, NudgeMemory(lastShownAt = now.minus(Duration.ofHours(3))), now, zone) == NudgeResult.SUGGEST, "cooldown edge")
        val today = now.atZone(zone).toLocalDate()
        verify(H.decide(true, high, emptyList(), base, NudgeMemory(dismissedOn = today), now, zone) == NudgeResult.DISMISSED_TODAY)
        verify(H.decide(true, high, emptyList(), base, NudgeMemory(dismissedOn = today.minusDays(1)), now, zone) == NudgeResult.SUGGEST, "dismiss lasts one day")
        verify(banned.none { (H.MESSAGE + H.NOTE).contains(it) } && H.NOTE.contains("의료기기가 아니에요"))

        // ---- PlaceBook
        val d = LocalDate.of(2026, 11, 16)
        val cafe = Place("kakao:1", " 조용한 카페 ", "서울 성동구", 37.54, 127.05, PlaceKind.CAFE, source = "kakao")
        verify(cafe.validated().name == "조용한 카페", "trim")
        rejects { cafe.copy(name = " ").validated() }
        rejects { cafe.copy(name = "가".repeat(41)).validated() }
        rejects { cafe.copy(lat = 37.5, lon = null).validated() }
        rejects { cafe.copy(lat = 48.0, lon = 2.3).validated() }
        rejects { cafe.copy(id = "a\tb").validated() }
        rejects { cafe.copy(source = "google").validated() }
        var book = PlaceBook.empty()
        verify(!book.countVisits && book.recordUse(cafe, d) === book, "visits off: nothing recorded")
        book = book.addFavorite(cafe)
        verify(book.favorites().single().favorite && book.frequent(d).isEmpty())
        book = book.visitsOn().recordUse(cafe, d).recordUse(cafe, d.plusDays(1))
        verify(book.find("kakao:1")!!.visits == 2 && book.frequent(d.plusDays(1)).size == 1)
        val park = Place("manual:park", "동네 공원", kind = PlaceKind.PARK)
        book = book.recordUse(park, d)
        verify(book.find("manual:park")!!.let { !it.favorite && it.visits == 1 } && book.frequent(d).size == 1, "one visit is not frequent")
        book = book.recordUse(park, d.plusDays(2)).recordUse(park, d.plusDays(3))
        verify(book.frequent(d.plusDays(3)).first().id == "manual:park", "most visits first")
        verify(book.frequent(d.plusDays(3 + 91)).isEmpty(), "older than 90 days drops out")
        verify(book.pruned(d.plusDays(3 + 91)).places.map { it.id } == listOf("kakao:1"), "prune keeps favorites")
        verify(book.addFavorite(cafe.copy(name = "새 이름")).find("kakao:1")!!.let { it.name == "새 이름" && it.visits == 2 }, "re-add keeps visits")
        verify(book.unfavorite("kakao:1").find("kakao:1")!!.favorite.not(), "unfavorite keeps visited place")
        verify(PlaceBook.empty().addFavorite(park).unfavorite("manual:park").places.isEmpty(), "unfavorite unvisited removes")
        val off = book.visitsOff()
        verify(!off.countVisits && off.places.single().id == "kakao:1" && off.places.single().visits == 0 && off.places.single().lastUsed == null, "turning off erases visits")
        verify(book.rename("kakao:1", " 단골 ").find("kakao:1")!!.name == "단골")
        rejects { book.rename("kakao:1", "") }
        var full = PlaceBook.empty()
        (1..PlaceBook.MAX_PLACES).forEach { full = full.addFavorite(Place("p$it", "곳 $it")) }
        rejects { full.addFavorite(Place("p51", "넘침")) }
        verify(full.addFavorite(Place("p1", "고침")).places.size == PlaceBook.MAX_PLACES, "update when full is fine")
        verify(full.visitsOn().recordUse(Place("p51", "넘침"), d).places.size == PlaceBook.MAX_PLACES, "full book ignores new visit")
        rejects { PlaceBook((1..51).map { Place("q$it", "곳") }, false) }
        rejects { Place("manual:c", "줄\n바꿈").validated() }
        rejects { Place("manual:c", "탭", "주\t소").validated() }
        // 꽉 찬 책: 90일 넘게 안 쓴 비즐겨찾기 곳을 비우고 새 곳을 받는다
        var crowded = PlaceBook.empty().visitsOn()
        (1..PlaceBook.MAX_PLACES).forEach { crowded = crowded.recordUse(Place("o$it", "옛 곳 $it"), d.minusDays(200)) }
        verify(crowded.places.size == PlaceBook.MAX_PLACES)
        val fresh = crowded.recordUse(Place("new", "새 곳"), d)
        verify(fresh.find("new")?.visits == 1 && fresh.places.size == 1, "full book prunes stale places for a new one")
        // 저장·복원
        val tricky = Place("manual:x", "역\\슬래시 \"따옴표\"", "주소", null, null, PlaceKind.SAUNA, favorite = true)
        val enc = book.addFavorite(tricky).encode()
        val dec = PlaceBook.decode(enc)
        verify(dec.countVisits && dec.places == book.addFavorite(tricky).places, "round trip")
        verify(PlaceBook.decode(null).places.isEmpty() && PlaceBook.decode("garbage").places.isEmpty())
        verify(PlaceBook.decode(enc + "broken\tline\n").places.size == dec.places.size, "broken line skipped")
        verify(PlaceBook.decode(enc.replace("\n", "\r\n")).places == dec.places, "CRLF tolerated")
        verify(PlaceBook.decode(enc.replace("placebook/1\t1", "placebook/1\t0")).countVisits.not())
        verify(PlaceKind.of("nope") == PlaceKind.OTHER && PlaceKind.of("park") == PlaceKind.PARK)

        println("LifeRestChecks: $checks checks passed")
    }
}
