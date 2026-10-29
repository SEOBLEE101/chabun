package com.recovery.companion

object AppearancePolicyChecks {
    fun run(): Int {
        var count = 0
        fun verify(value: Boolean) { check(value); count++ }
        val report = AppearancePolicy.Report("vehicle-a", "graphite", 1, 4, 1_000_000, 1_120_000)
        verify(AppearancePolicy.accepts(null, null, 0, "epoch-a", report))
        verify(AppearancePolicy.accepts("epoch-a", "vehicle-a", 4, "epoch-a", report))
        verify(!AppearancePolicy.accepts("epoch-a", "vehicle-a", 5, "epoch-a", report))
        verify(AppearancePolicy.accepts("epoch-b", "vehicle-a", 50, "epoch-a", report))
        verify(AppearancePolicy.accepts("epoch-a", "vehicle-b", 50, "epoch-a", report))
        verify(AppearancePolicy.remaining(1_000_000, 1_120_000, 5_000, 5_200) == 119_800L)
        verify(AppearancePolicy.remaining(1_119_900, 1_120_000, 5_000, 5_200) == 0L)
        verify(AppearancePolicy.remaining(1_120_000, 1_120_000, 5_000, 5_000) == 0L)
        verify(AppearancePolicy.remaining(1_120_100, 1_120_000, 5_000, 5_000) == 0L)
        verify(AppearancePolicy.remaining(1_000_000, 2_000_000, 5_000, 5_200) == 119_800L)
        verify(AppearancePolicy.remaining(1_000_000, 1_120_000, 5_000, 4_000) == 0L)
        verify(AppearancePolicy.isFresh(5_200, 119_800, 5_200))
        verify(AppearancePolicy.isFresh(5_200, 119_800, 124_999))
        verify(!AppearancePolicy.isFresh(5_200, 119_800, 125_000))
        verify(!AppearancePolicy.isFresh(5_200, 119_800, 5_199))
        verify(!AppearancePolicy.isFresh(5_200, 0, 5_200))

        val sync = mapOf("epoch" to "epoch-a", "deviceId" to "phone-a", "role" to "phone", "expiresAt" to "2026-10-28T02:00:00Z")
        val valid = mapOf("deviceId" to "vehicle-a", "themeId" to "graphite", "paletteVersion" to 1, "revision" to 1,
            "reportedAt" to "2026-10-28T00:00:00Z", "expiresAt" to "2026-10-28T00:02:00Z")
        fun body(reports: List<Any?> = listOf(valid), serverTime: String = "2026-10-28T00:00:01Z"): Map<String, Any?> =
            mapOf("appearance" to mapOf("schemaVersion" to 1, "serverTime" to serverTime, "vehicles" to reports), "sync" to sync)
        fun accepts(value: Any?) = runCatching { PresetSyncResponsePolicy.validate("/appearance", 200, value) }.isSuccess
        verify(accepts(body()))
        verify(accepts(body(serverTime = "2026-10-28T00:03:00Z"))) // Expired reports remain visible, never treated as fresh.
        verify(accepts(body(emptyList())))
        verify(!accepts(body(listOf(valid, valid))))
        // Contract v2: the vehicle reports its current theme, graphite | paper (Pearl) | umber.
        listOf("graphite", "paper", "umber").forEach { verify(accepts(body(listOf(valid + ("themeId" to it))))) }
        listOf("neon", "pearl", "Graphite", "UMBER", " paper", "", null, 1).forEach {
            verify(!accepts(body(listOf(valid + ("themeId" to it)))))
        }
        verify(!accepts(body(listOf(valid - "themeId"))))
        verify(AppearancePolicy.THEME_IDS == setOf("graphite", "paper", "umber"))
        verify(AppearancePolicy.supportedTheme("umber") && !AppearancePolicy.supportedTheme("neon") && !AppearancePolicy.supportedTheme(null))
        verify(AppearancePolicy.followedTheme("paper") == "paper")
        verify(AppearancePolicy.followedTheme("graphite") == "graphite")
        verify(AppearancePolicy.followedTheme("umber") == "umber")
        verify(AppearancePolicy.followedTheme("neon") == "paper")
        verify(AppearancePolicy.followedTheme(null) == "paper")
        verify(AppearancePolicy.mood("paper") == Mood.PEARL && AppearancePolicy.mood("graphite") == Mood.GRAPHITE
            && AppearancePolicy.mood("umber") == Mood.UMBER)
        verify(AppearancePolicy.displayName("paper") == "Pearl" && AppearancePolicy.displayName("graphite") == "Graphite"
            && AppearancePolicy.displayName("umber") == "Umber")
        // Vehicle binding: a re-paired car (new device id) is followed again when the old choice was automatic.
        verify(AppearancePolicy.bind(null, true, listOf("car-a")) == AppearancePolicy.Binding("car-a", "ok"))
        verify(AppearancePolicy.bind("car-old", true, listOf("car-new")) == AppearancePolicy.Binding("car-new", "ok"))
        verify(AppearancePolicy.bind("car-a", true, listOf("car-a", "car-b")) == AppearancePolicy.Binding("car-a", "ok"))
        verify(AppearancePolicy.bind("car-old", true, listOf("car-a", "car-b")) == AppearancePolicy.Binding(null, "multiple"))
        verify(AppearancePolicy.bind(null, true, listOf("car-a", "car-b")) == AppearancePolicy.Binding(null, "multiple"))
        verify(AppearancePolicy.bind("car-old", true, emptyList()) == AppearancePolicy.Binding(null, "unavailable"))
        verify(AppearancePolicy.bind("car-mine", false, listOf("car-new")) == AppearancePolicy.Binding("car-mine", "unavailable"))
        verify(AppearancePolicy.bind("car-mine", false, listOf("car-mine")) == AppearancePolicy.Binding("car-mine", "ok"))
        verify(AppearancePolicy.bind(null, false, listOf("car-a")) == AppearancePolicy.Binding(null, "unbound"))
        verify(!accepts(body(listOf(valid + ("paletteVersion" to 2)))))
        verify(!accepts(body(listOf(valid + ("revision" to "1")))))
        verify(!accepts(body(listOf(valid + ("revision" to 0)))))
        verify(!accepts(body(listOf(valid + ("expiresAt" to "2026-10-28T00:02:01Z")))))
        verify(!accepts(body(listOf(valid + ("reportedAt" to "2026-10-28T00:00:02Z")))))
        verify(!accepts(body() + ("sync" to (sync + ("role" to "vehicle")))))
        verify(!accepts(body() + ("appearance" to mapOf("schemaVersion" to 1, "vehicles" to listOf(valid)))))
        return count
    }
}
