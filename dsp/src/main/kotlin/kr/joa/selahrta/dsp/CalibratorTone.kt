package kr.joa.selahrta.dsp

/**
 * **음압 교정기의 1kHz 순음이 실제로 들어오고 있는가.**
 *
 * ## 왜 확인해야 하는가
 *
 * 교정기로 맞춘 보정값은 그 뒤 모든 음압 숫자의 바탕이 된다. 그런데
 * 교정기를 마이크에 **제대로 끼우지 않은 채** 눌러도 화면에는 그럴듯한
 * 숫자가 뜬다 — 방 소리로 계산된 엉뚱한 보정값이다. 그렇게 저장되면
 * 그 뒤로 모든 측정이 조용히 틀린다.
 *
 * 다행히 이것은 **확인할 수 있다.** 교정기는 1kHz 순음을 아주 크게
 * 낸다. 들어오는 소리가 그 모양이 아니면 끼우지 않은 것이다.
 *
 * ## 무엇을 보는가
 *
 * 1. **1kHz 밴드가 가장 커야 한다.** 다른 밴드가 더 크면 순음이 아니다.
 * 2. **이웃보다 크게 솟아야 한다.** 순음은 한 밴드에 에너지가 몰린다.
 *    음악이나 방 소리는 넓게 퍼진다.
 *
 * **1kHz 를 쓰는 덕이 하나 더 있다**: A·C 가중이 1kHz 에서 정확히 0dB
 * 이라, 교정기로 맞춘 값은 가중치와 무관하다. 소음계와 맞출 때처럼
 * 「둘의 가중을 맞춰야 한다」는 걱정이 없다.
 */
data class CalibratorToneCheck(
    val ok: Boolean,
    /** 1kHz 밴드가 나머지 중 가장 큰 밴드보다 얼마나 솟았는가(dB). */
    val prominenceDb: Double,
    /** 가장 큰 밴드의 번호. 1kHz 가 아니면 그 자리가 어디인지 알려 준다. */
    val loudestBand: Int,
    val reasonKo: String?,
)

/**
 * 밴드 레벨로 교정기 순음인지 본다.
 *
 * @param bandsDb 1/3 옥타브 밴드 dBFS.
 * @param minProminenceDb 1kHz 가 나머지보다 이만큼은 솟아야 한다.
 *   **실측으로 정한 값이 아니다** — 1/3 옥타브에서 순음은 이웃 밴드보다
 *   보통 20dB 넘게 솟지만, 교정기를 끼운 상태에서도 방 소리가 새어
 *   들어오므로 여유를 두었다.
 */
fun checkCalibratorTone(
    bandsDb: DoubleArray,
    minProminenceDb: Double = 12.0,
): CalibratorToneCheck {
    require(bandsDb.size == ThirdOctave.BAND_COUNT) {
        "밴드 수가 다르다: ${bandsDb.size} != ${ThirdOctave.BAND_COUNT}"
    }
    val target = ThirdOctave.nearestBand(1_000.0)
    val loudest = bandsDb.indices.maxBy { bandsDb[it] }

    // 1kHz 를 뺀 나머지 중 가장 큰 것과 견준다. **이웃만 보지 않는다** —
    // 엉뚱한 자리에 큰 소리가 있으면 그것이 문제다.
    val restMax = bandsDb.indices.filter { it != target }.maxOf { bandsDb[it] }
    val prominence = bandsDb[target] - restMax

    val reason = when {
        loudest != target ->
            "가장 큰 소리가 1kHz 가 아니라 ${ThirdOctave.label(loudest)}Hz 에 있습니다. " +
                "교정기가 마이크에 제대로 끼워졌는지 보십시오."

        prominence < minProminenceDb ->
            "1kHz 가 다른 대역보다 ${"%.1f".format(prominence)}dB 밖에 솟지 않았습니다. " +
                "순음이라면 훨씬 크게 솟습니다 — 교정기가 헐겁거나 주변이 시끄럽습니다."

        else -> null
    }
    return CalibratorToneCheck(
        ok = reason == null,
        prominenceDb = prominence,
        loudestBand = loudest,
        reasonKo = reason,
    )
}
