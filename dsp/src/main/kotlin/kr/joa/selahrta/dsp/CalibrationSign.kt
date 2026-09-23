package kr.joa.selahrta.dsp

/**
 * 보정 파일의 **둘째 열이 무엇인가** — 응답인가 보정값인가.
 *
 * ## 왜 이것이 중요한가
 *
 * [CalibrationCurve] 는 둘째 열을 **마이크의 응답**으로 읽고 측정값에서
 * **뺀다.** 널리 쓰이는 `.cal`/`.frd` 가 그 규약이기 때문이다.
 *
 * 그런데 어떤 제조사는 **이미 뒤집힌 값**(correction)을 준다. 그 파일을
 * 응답으로 읽으면 **보정이 정확히 두 배로, 반대 방향으로 걸린다** —
 * +2dB 여야 할 자리가 −2dB 가 되어 4dB 가 틀어진다.
 *
 * ## 이 파일이 하는 일과 하지 않는 일
 *
 * **부호를 정하지 않는다.** 실제 Dayton EMM-6 파일을 보기 전에는 정할
 * 수 없고, 추측해서 고치면 측정값이 통째로 틀어진다(USB 오디오 지시서
 * 9.4: *「불명확하면 임의 부호로 측정값을 변경하지 않는다」*).
 *
 * 하는 일은 **증거를 모아 사람에게 보이는 것**뿐이다. 파일 머리글에
 * 열 이름이 적혀 있는 경우가 많고, 거기에 우리 가정과 어긋나는 말이
 * 있으면 알린다.
 *
 * 기존 경고(보정량 ±30dB 초과)로는 못 잡는다 — 실제 측정 마이크 파일은
 * 대개 ±5dB 안쪽이라 **부호가 반대여도 조용히 통과한다.**
 */
enum class SignEvidence {
    /** 머리글이 「응답」 쪽을 가리킨다. 지금 가정과 같다. */
    LooksLikeResponse,

    /** 머리글이 「보정값」 쪽을 가리킨다. **지금 가정과 반대다.** */
    LooksLikeCorrection,

    /** 단서가 없다. 대부분의 파일이 여기다. */
    Unknown,
}

/** 우리 가정(응답)과 같은 쪽을 가리키는 말. */
private val RESPONSE_WORDS = listOf("spl", "response", "magnitude", "measured", "응답")

/** 우리 가정과 **반대**를 가리키는 말. */
private val CORRECTION_WORDS = listOf("correction", "compensation", "보정값", "보정 값")

/**
 * 머리글 줄들에서 단서를 찾는다.
 *
 * **둘 다 나오면 모름이다.** 「Correction (from measured response)」 같은
 * 문장이 실제로 있고, 그런 파일은 사람이 봐야 한다.
 */
fun signEvidenceOf(headerLines: List<String>): SignEvidence {
    val text = headerLines.joinToString(" ").lowercase()
    val response = RESPONSE_WORDS.any { text.contains(it) }
    val correction = CORRECTION_WORDS.any { text.contains(it) }
    return when {
        response && correction -> SignEvidence.Unknown
        correction -> SignEvidence.LooksLikeCorrection
        response -> SignEvidence.LooksLikeResponse
        else -> SignEvidence.Unknown
    }
}

/**
 * 사람에게 할 말. 없으면 null.
 *
 * **막지 않는다.** 단서일 뿐이고, 틀릴 수 있다. 판단은 사람이 한다 —
 * 다만 모르고 지나가지는 않게 한다.
 */
fun signNoticeKo(evidence: SignEvidence): String? = when (evidence) {
    SignEvidence.LooksLikeResponse, SignEvidence.Unknown -> null
    // **화면에 그대로 나가는 문장이다.** 마크다운 강조(`**`)를 쓰지 않는다 —
    // 이 자리는 서식 없는 Text 라 별표가 글자 그대로 보인다. 기기에서
    // 확인하기 전에는 몰랐다(2026-09-22 실기기).
    SignEvidence.LooksLikeCorrection ->
        "이 파일의 머리글이 「보정값(correction)」으로 읽힙니다. 앱은 둘째 열을 " +
            "「마이크의 응답」으로 보고 측정값에서 빼는데, 이미 뒤집힌 값이라면 " +
            "보정이 반대로 두 배 걸립니다. 제조사 설명을 확인하십시오."
}

/**
 * 둘째 열을 **무엇으로 읽을 것인가.**
 *
 * 증거([SignEvidence])는 단서일 뿐이고, 이것은 **정해진 값**이다.
 * 어느 쪽으로 읽었는지 프로파일에 남아야 나중에 되짚을 수 있다
 * (독립 검토 R04: 「response/correction 을 명시적으로 선택·기록하고」).
 */
enum class CurveReading(val labelKo: String, val explainKo: String) {
    /** 마이크의 **응답**. 측정값에서 뺀다. `.cal`·`.frd` 의 일반 규약이다. */
    Response(
        "마이크 응답",
        "둘째 열이 마이크가 실제로 낸 값입니다. 측정값에서 뺍니다. " +
            ".cal·.frd 파일은 대개 이쪽입니다.",
    ),

    /** 이미 뒤집힌 **보정값**. 측정값에 더한다. */
    Correction(
        "보정값",
        "둘째 열이 이미 뒤집힌 값입니다. 측정값에 더합니다. " +
            "이쪽을 응답으로 읽으면 보정이 반대로 두 배 걸립니다.",
    ),
    ;

    /** 응답 규약으로 옮길 때 곱할 값. */
    val toResponseSign: Double get() = if (this == Response) 1.0 else -1.0
}

/**
 * 이 곡선을 **무엇에 쓰는가.** 같은 모호함이라도 걸린 것이 다르다.
 */
enum class ReadingStakes {
    /**
     * RTA 막대에 거는 표시용 곡선.
     *
     * 틀리면 화면의 막대가 어긋나고, 보고 있으면 알아챌 여지가 있다.
     */
    DisplayCurve,

    /**
     * **교정의 기준**이 되는 EMM-6 CAL.
     *
     * 틀리면 이 기준으로 만든 **모든 프로파일이 그만큼 틀어진 채로
     * 굳는다.** 그러고도 곡선은 멀쩡해 보인다. 그래서 여기서는 모르는
     * 것을 관례로 때우지 않는다.
     */
    ReferenceForCalibration,
}

/** 읽는 법을 정했는가, 사람에게 물어야 하는가. */
sealed interface ReadingDecision {
    val reading: CurveReading
    val whyKo: String

    /** 정해졌다. 그대로 걸어도 된다. */
    data class Settled(
        override val reading: CurveReading,
        override val whyKo: String,
    ) : ReadingDecision

    /**
     * **사람이 정해야 한다.** [reading] 은 제안일 뿐이고, 확인 전에는
     * 자동으로 걸지 않는다.
     */
    data class NeedsPerson(
        override val reading: CurveReading,
        override val whyKo: String,
    ) : ReadingDecision

    val settled: Boolean get() = this is Settled
}

/**
 * 증거와 용도로 읽는 법을 정한다(독립 검토 R04).
 *
 * ## 왜 용도에 따라 다른가
 *
 * 단서가 없는 파일이 대부분이다. 그것을 전부 막으면 아무 파일도 못
 * 쓰고, 전부 통과시키면 기준이 뒤집힌 채로 굳는다. 그래서 **걸린 것의
 * 크기로 가른다.**
 *
 * - 표시용 곡선은 관례(`.cal`·`.frd` = 응답)를 따르고 지나간다. 틀려도
 *   화면에서 드러날 여지가 있고, 되돌리기 쉽다.
 * - **교정의 기준**은 그렇지 않다. 그 파일로 만든 프로파일이 전부 같은
 *   방향으로 틀어지고, 나중에 봐도 알 수 없다. 그래서 모르면 묻는다.
 *
 * 머리글이 **우리 가정과 반대**를 가리키면 용도와 상관없이 묻는다 —
 * 그때는 「모르는」 것이 아니라 「어긋나는」 것이다.
 */
fun decideReading(evidence: SignEvidence, stakes: ReadingStakes): ReadingDecision = when (evidence) {
    SignEvidence.LooksLikeResponse -> ReadingDecision.Settled(
        CurveReading.Response,
        "파일 머리글이 「응답」으로 읽힙니다.",
    )

    SignEvidence.LooksLikeCorrection -> ReadingDecision.NeedsPerson(
        CurveReading.Correction,
        "파일 머리글이 「보정값」으로 읽힙니다. 앱의 기본 가정과 반대라 " +
            "확인이 필요합니다. 잘못 읽으면 보정이 반대로 두 배 걸립니다.",
    )

    SignEvidence.Unknown -> when (stakes) {
        ReadingStakes.DisplayCurve -> ReadingDecision.Settled(
            CurveReading.Response,
            "머리글에 단서가 없어 관례대로 「응답」으로 읽습니다(.cal·.frd).",
        )

        ReadingStakes.ReferenceForCalibration -> ReadingDecision.NeedsPerson(
            CurveReading.Response,
            "머리글에 단서가 없습니다. 이 파일은 교정의 기준이 되므로, " +
                "잘못 읽으면 이 기준으로 만든 프로파일이 전부 같은 방향으로 " +
                "틀어집니다. 제조사 설명을 보고 정해 주십시오.",
        )
    }
}

/**
 * 곡선의 **모양**에서 오는 단서(독립 검토 R04 보강).
 *
 * 머리글에 단서가 없는 파일이 대부분이라 [signEvidenceOf] 만으로는
 * 거의 언제나 「모름」이 된다. 그런데 값 자체가 말해 주는 것이 있다.
 *
 * 작은 측정용 캡슐은 대체로 **저역이 조금 모자라고 고역이 솟는다**
 * (다이어프램 앞의 압력 상승과 캡슐 공진 때문이다). 그 마이크의 **응답**을
 * 적은 파일은 그 모양 그대로이고, 이미 뒤집은 **보정값** 파일은 거울상
 * 이다 — 저역이 솟고 고역이 내려간다.
 *
 * **이것으로 정하지 않는다.** 마이크마다 다르고, 정규화 자리도 다르다.
 * 사람이 고를 때 곁에 놓는 단서일 뿐이다.
 */
data class CurveShape(
    val lowDb: Double,
    val midDb: Double,
    val highDb: Double,
) {
    /** 고역이 저역보다 높은가. 측정 캡슐 응답의 흔한 모양이다. */
    val risesToHigh: Boolean get() = highDb > lowDb

    /** 1kHz 언저리를 0 으로 맞춰 둔 파일인가. */
    val normalizedAtMid: Boolean get() = kotlin.math.abs(midDb) < 0.5
}

/** 곡선에서 저·중·고 세 점을 뽑는다. */
fun shapeOf(curve: CalibrationCurve): CurveShape = CurveShape(
    lowDb = curve.gainDbAt(curve.lowestHz.coerceAtLeast(20.0)),
    midDb = curve.gainDbAt(1_000.0),
    highDb = curve.gainDbAt(curve.highestHz.coerceAtMost(20_000.0)),
)

/**
 * 모양을 사람 말로. **판단하지 않고 보여만 준다.**
 *
 * 숫자를 먼저 적고, 그 모양이 흔히 무엇을 뜻하는지를 뒤에 덧붙인다 —
 * 순서를 바꾸면 앱이 정해 준 것처럼 읽힌다.
 */
fun describeShapeKo(shape: CurveShape): String {
    val head = "이 파일은 저역 %.1fdB · 1kHz %.1fdB · 고역 %.1fdB 입니다."
        .format(shape.lowDb, shape.midDb, shape.highDb)
    val tail = if (shape.risesToHigh) {
        "저역이 낮고 고역이 솟는 모양인데, 작은 측정용 캡슐의 응답이 대체로 " +
            "이렇습니다. 보정값 파일이라면 대개 그 거울상(저역이 솟고 고역이 " +
            "내려감)입니다."
    } else {
        "고역이 낮고 저역이 솟는 모양입니다. 측정용 캡슐의 응답은 대체로 그 " +
            "반대라, 이미 뒤집힌 보정값일 수 있습니다."
    }
    return "$head $tail 어느 쪽인지는 제조사 설명으로 확인하십시오."
}
