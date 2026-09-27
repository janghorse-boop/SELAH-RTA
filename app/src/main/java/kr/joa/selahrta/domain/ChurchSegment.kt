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

    // ── 사람이 더해 쓰는 칸 (담당자 지시 2026-09-28: 최대 5개) ──
    //
    // **이름을 바꾸지 않는다.** 설정과 기록이 이 enum 이름을 열쇠로
    // 쓴다(`segName|Extra1` 처럼). 바꾸면 저장해 둔 범위와 이름을
    // 통째로 잃는다.
    //
    // 화면에 적히는 글자는 `MeterSettings.nameFor` 가 정한다 — 사람이
    // 「기도」·「특송」처럼 바꿔 쓴다.
    Extra1("구간 3", "구간 3"),
    Extra2("구간 4", "구간 4"),
    Extra3("구간 5", "구간 5"),
    ;

    ;
}

/** 구간은 다섯 개까지(담당자 지시 2026-09-28). */
const val MAX_SEGMENTS = 5

/**
 * 앱을 처음 열었을 때 쓰는 구간 — **하나도 없다**(담당자 지시
 * 2026-09-28).
 *
 * 전에는 설교·찬양이 처음부터 들어 있었고 뺄 수도 없었다. 그런데
 * **권장 범위는 예배당마다 다른 참고값**이라, 앱이 먼저 둘을 깔아 두면
 * 쓰지도 않는 범위와 견주어 색이 뜬다.
 *
 * 이제는 **사람이 더해야 생긴다.** 하나도 없으면 권장 범위 상자를
 * 아예 그리지 않고, 큰 숫자는 견줄 것 없이 그대로 보여 준다.
 */
val DEFAULT_SEGMENTS: Set<ChurchSegment> = emptySet()

/**
 * 구간별 참고 범위(dBA).
 *
 * **보편적 표준이 아니다.** 명세 10장이 「사용자 수정 가능한 참고값」이라고
 * 못박았다. 예배당 크기·잔향·회중 수에 따라 알맞은 값이 달라진다.
 */
data class SegmentRange(
    val avgLowDb: Double,
    val avgHighDb: Double,
) {
    val avg: ClosedFloatingPointRange<Double> get() = avgLowDb..avgHighDb

    /** 값이 서로 어긋나지 않는가. 고친 값을 저장하기 전에 본다. */
    val isSane: Boolean
        get() = avgLowDb < avgHighDb &&
            avgLowDb in 30.0..120.0 &&
            avgHighDb in 30.0..140.0
}

/**
 * 컨셉 화면 4번과 명세 10장의 초기값.
 *
 * 고치기 전의 출발점일 뿐이다. 화면 어디서든 「참고값」이라고 적는다.
 */
object DefaultSegmentRanges {
    val sermon = SegmentRange(68.0, 75.0)
    val worship = SegmentRange(78.0, 85.0)

    /**
     * 더해 쓰는 칸의 출발점.
     *
     * **설교와 찬양의 사이를 준다.** 무엇에 쓸지 모르는 칸이라 어느
     * 한쪽으로 기울이지 않는다 — 어차피 사람이 고쳐 쓴다.
     */
    val extra = SegmentRange(70.0, 80.0)

    fun of(s: ChurchSegment): SegmentRange = when (s) {
        ChurchSegment.Sermon -> sermon
        ChurchSegment.Worship -> worship
        else -> extra
    }
}

/**
 * 구간별로 무엇을 눈여겨봐야 하는가(명세 10장).
 *
 * **지금은 어느 화면도 이것을 그리지 않는다** — 담당자 지시(2026-09-27)로
 * 구간 카드에서 뺐다. 글은 그대로 두었으니, 다시 띄울 자리가 생기면
 * 새로 쓰지 말고 이것을 쓴다.
 */
val ChurchSegment.focusKo: String
    get() = when (this) {
        ChurchSegment.Sermon ->
            "말이 또렷한지가 먼저입니다. 250Hz~4kHz 가 묻히지 않는지 보십시오."
        ChurchSegment.Worship ->
            "저역이 얼마나 많은지(C−A 차이)와 짧은 최대(MAX)를 함께 보십시오."
        // 더해 쓰는 칸은 무엇에 쓸지 앱이 모른다. **짐작해서 적지 않는다.**
        else -> ""
    }

/**
 * 컨셉 화면 4번의 주의사항.
 *
 * **공식 청력 안전기준이 아니다.** 명세 18장이 「앱 참고 범위를 공식
 * 청력 안전기준과 동일시하지 않는다」고 못박았다.
 */
val SEGMENT_CAUTIONS: List<String> = listOf(
    "85 dB(A) 이상이 장시간 지속되지 않도록 합니다.",
    "음량보다 명료도와 주파수 밸런스가 더 중요합니다.",
    "좌우 편차는 ±3~5 dB 이내로 맞추는 것이 좋습니다.",
)
