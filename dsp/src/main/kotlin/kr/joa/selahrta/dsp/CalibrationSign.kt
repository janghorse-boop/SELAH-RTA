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
    SignEvidence.LooksLikeCorrection ->
        "이 파일의 머리글이 「보정값(correction)」으로 읽힙니다. 앱은 둘째 열을 " +
            "**마이크의 응답**으로 보고 측정값에서 빼는데, 이미 뒤집힌 값이라면 " +
            "보정이 반대로 두 배 걸립니다. 제조사 설명을 확인하십시오."
}
