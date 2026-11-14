package com.recovery.companion

/** 운동 전 5분 웜업. 일반적인 준비 동작일 뿐 처방이 아니다(문구는 비의료). */
data class WarmupStep(val title: String, val detail: String, val seconds: Int)

object WarmupGuide {
    const val NOTE = "일반적인 준비 동작이에요. 아프거나 어지러우면 바로 멈추세요."

    private val common = listOf(
        WarmupStep("천천히 숨 고르기", "코로 들이쉬고 입으로 길게 내쉬어요.", 45),
        WarmupStep("목·어깨 돌리기", "작은 원에서 큰 원으로, 앞뒤로 천천히.", 60),
    )

    fun steps(type: String): List<WarmupStep> = common + when (type) {
        "running" -> listOf(
            WarmupStep("제자리 걷기", "팔을 함께 흔들며 가볍게.", 60),
            WarmupStep("다리 앞뒤 흔들기", "벽을 짚고 한쪽씩 가볍게.", 60),
            WarmupStep("가볍게 뛰기", "아주 느린 속도로 몸을 데워요.", 75),
        )
        "cycling" -> listOf(
            WarmupStep("엉덩이·허벅지 풀기", "무릎을 가슴 쪽으로 번갈아 당겨요.", 60),
            WarmupStep("발목 돌리기", "양쪽 발목을 천천히 돌려요.", 45),
            WarmupStep("가볍게 페달", "힘을 거의 주지 않고 돌려요.", 90),
        )
        "strength" -> listOf(
            WarmupStep("팔 벌려 흔들기", "가슴을 열고 팔을 앞뒤로.", 60),
            WarmupStep("맨몸 스쿼트", "편한 깊이까지만, 천천히.", 60),
            WarmupStep("가벼운 무게로 한 세트", "오늘 할 동작을 가볍게 먼저.", 75),
        )
        "walking" -> listOf(
            WarmupStep("발목·종아리 풀기", "발끝을 들었다 내렸다 해요.", 60),
            WarmupStep("천천히 걷기", "평소보다 느린 걸음으로 시작해요.", 120),
        )
        else -> listOf(
            WarmupStep("제자리 걷기", "팔을 함께 흔들며 가볍게.", 75),
            WarmupStep("몸통 돌리기", "허리를 좌우로 부드럽게.", 60),
            WarmupStep("가볍게 몸 털기", "팔다리를 털며 긴장을 풀어요.", 60),
        )
    }

    fun totalSeconds(type: String): Int = steps(type).sumOf { it.seconds }
    /** 화면에 보이는 분: 반올림(285초 → 5분). */
    fun minutes(type: String): Int = (totalSeconds(type) + 30) / 60
}
