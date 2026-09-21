package kr.joa.selahrta.domain

/**
 * 예배의 구간(명세 10장).
 *
 * 구간마다 알맞은 음량이 다르다. 설교가 찬양만큼 크면 시끄럽고, 찬양이
 * 설교만큼 조용하면 밋밋하다.
 *
 * **설교와 찬양 둘뿐이다.** 예전에는 「기도」와 「자유 측정」도 있었는데,
 * 쓰는 자리가 없어 걷어냈다. 화면 위쪽 칩(설교·찬양)이 곧 구간이므로
 * **같은 것을 고르는 줄이 둘일 까닭이 없다.**
 *
 * 저장된 설정에 옛 이름이 남아 있으면 [ChurchSegment] 로 풀리지 않고
 * 설교로 돌아간다(`MeterSettings` 가 그렇게 읽는다).
 */
enum class ChurchSegment(val labelKo: String, val shortKo: String) {
    Sermon("설교 (말씀)", "설교"),
    Worship("찬양", "찬양"),
}

/**
 * 구간별 참고 범위(dBA).
 *
 * **보편적 표준이 아니다.** 명세 10장이 「사용자 수정 가능한 참고값」이라고
 * 못박았다. 예배당 크기·잔향·회중 수에 따라 알맞은 값이 달라진다.
 */
data class SegmentRange(
    val avgLowDb: Double,
    val avgHighDb: Double,
    /**
     * 짧은 최대값의 참고 범위. **화면의 MAX 와 견주는 값이다.**
     *
     * PEAK 타일이 아니다 — PEAK 는 가중 전 파형의 최대라 dBA 가 아니고,
     * 가중치를 바꿔도 숫자가 변하지 않는다(독립 검증 R10). 여기 적힌
     * dBA 기준과 견줄 수 있는 것은 시간가중 최대인 MAX 뿐이다.
     */
    val peakLowDb: Double,
    val peakHighDb: Double,
) {
    val avg: ClosedFloatingPointRange<Double> get() = avgLowDb..avgHighDb
    val peak: ClosedFloatingPointRange<Double> get() = peakLowDb..peakHighDb

    /** 값이 서로 어긋나지 않는가. 고친 값을 저장하기 전에 본다. */
    val isSane: Boolean
        get() = avgLowDb < avgHighDb &&
            peakLowDb < peakHighDb &&
            avgLowDb in 30.0..120.0 &&
            peakHighDb in 30.0..140.0 &&
            // 피크가 평균보다 낮으면 무언가 잘못 적은 것이다.
            peakHighDb >= avgHighDb
}

/**
 * 컨셉 화면 4번과 명세 10장의 초기값.
 *
 * 고치기 전의 출발점일 뿐이다. 화면 어디서든 「참고값」이라고 적는다.
 */
object DefaultSegmentRanges {
    val sermon = SegmentRange(68.0, 75.0, 78.0, 82.0)
    val worship = SegmentRange(78.0, 85.0, 88.0, 95.0)

    fun of(s: ChurchSegment): SegmentRange = when (s) {
        ChurchSegment.Sermon -> sermon
        ChurchSegment.Worship -> worship
    }
}

/** 구간별로 무엇을 눈여겨봐야 하는가(명세 10장). */
val ChurchSegment.focusKo: String
    get() = when (this) {
        ChurchSegment.Sermon ->
            "말이 또렷한지가 먼저입니다. 250Hz~4kHz 가 묻히지 않는지 보십시오."
        ChurchSegment.Worship ->
            "저역이 얼마나 많은지(C−A 차이)와 짧은 최대(MAX)를 함께 보십시오."
    }

/**
 * 컨셉 화면 4번의 주의사항.
 *
 * **공식 청력 안전기준이 아니다.** 명세 18장이 「앱 참고 범위를 공식
 * 청력 안전기준과 동일시하지 않는다」고 못박았다.
 */
val SEGMENT_CAUTIONS: List<String> = listOf(
    "85 dBA 이상이 장시간 지속되지 않도록 합니다.",
    "음량보다 명료도와 주파수 밸런스가 더 중요합니다.",
    "좌우 편차는 ±3~5 dB 이내로 맞추는 것이 좋습니다.",
)
