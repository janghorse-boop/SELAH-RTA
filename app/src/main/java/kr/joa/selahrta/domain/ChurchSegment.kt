package kr.joa.selahrta.domain

/**
 * 예배의 구간(명세 10장).
 *
 * 구간마다 알맞은 음량이 다르다. 설교가 찬양만큼 크면 시끄럽고, 찬양이
 * 설교만큼 조용하면 밋밋하다.
 */
enum class ChurchSegment(val labelKo: String, val shortKo: String) {
    Sermon("설교 (말씀)", "설교"),
    Worship("찬양", "찬양"),
    Prayer("기도 / 성경봉독", "기도"),

    /**
     * 자유 측정. **판정하지 않는다.**
     *
     * 예배가 아닌 상황(음향 점검, 장비 비교)에서는 「적정」이라는 말 자체가
     * 뜻이 없다. 계측값만 본다.
     */
    Free("자유 측정", "자유"),
    ;

    val judges: Boolean get() = this != Free
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
    val prayer = SegmentRange(65.0, 72.0, 75.0, 80.0)

    fun of(s: ChurchSegment): SegmentRange? = when (s) {
        ChurchSegment.Sermon -> sermon
        ChurchSegment.Worship -> worship
        ChurchSegment.Prayer -> prayer
        ChurchSegment.Free -> null
    }
}

/** 구간별로 무엇을 눈여겨봐야 하는가(명세 10장). */
val ChurchSegment.focusKo: String
    get() = when (this) {
        ChurchSegment.Sermon ->
            "말이 또렷한지가 먼저입니다. 250Hz~4kHz 가 묻히지 않는지 보십시오."
        ChurchSegment.Worship ->
            "저역이 얼마나 많은지(C−A 차이)와 순간 피크를 함께 보십시오."
        ChurchSegment.Prayer ->
            "차분하고 명료한 수준입니다. 너무 작으면 뒷자리에서 안 들립니다."
        ChurchSegment.Free ->
            "판정하지 않습니다. 계측값만 봅니다."
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
