package kr.joa.selahrta.dsp

import kotlin.math.abs

/**
 * **잔여 DSP 를 신호로 검사한다**(지시서 3장 · DoD 5항).
 *
 * > API 설정값만 신뢰하지 말고 일정 레벨 신호의 시간별 이득 변화,
 * > 스펙트럼 변형, 반복 측정으로 잔여 DSP를 검사한다.
 *
 * ## 이 검사가 증명할 수 있는 것과 없는 것
 *
 * **「처리가 없다」를 증명하지 못한다.** 증명할 수 없다 — 시간에 대해
 * 변하지 않는 처리(HAL 에 박힌 고정 EQ 같은 것)는 **마이크 자신의 응답과
 * 구별되지 않는다.** 밖에서 소리를 넣어 보는 한 둘은 같은 것으로 보인다.
 *
 * 다행히 그건 문제가 아니다. 고정 응답은 교정이 재서 걷어내는 바로 그
 * 대상이다. 교정을 **망가뜨리는** 것은 시간에 따라 변하는 처리다:
 *
 * - AGC 는 이득을 시간에 따라 바꾼다. 기준 장과 대상 장이 서로 다른
 *   이득으로 재지므로, 그 차이가 마이크 응답 차이로 둔갑한다.
 * - NS 는 레벨이 낮은 대역을 더 깎는다. 깎이는 양이 신호에 따라 변하므로,
 *   같은 마이크를 두 번 재도 모양이 달라진다.
 *
 * 그래서 이 검사는 **「시간에 따라 변하는 처리가 있는가」**를 본다. 통과해도
 * 「처리 없음」이 아니라 **「변하는 처리는 못 찾았다」**이고, 문구도 그렇게
 * 적는다.
 *
 * ## 무엇을 재는가
 *
 * 일정한 소리(핑크 노이즈)를 틀어 두고 **신호가 시작되는 순간부터** 장을
 * 모은다. 시작을 포함하는 것이 중요하다 — AGC 는 대개 시작 직후에
 * 자리를 잡고, 자리잡은 뒤부터 재면 아무 일도 없어 보인다.
 *
 * 1. **광대역 이득 변화**: 앞창과 뒤창의 광대역 레벨 차이. AGC 의 자취다.
 * 2. **스펙트럼 모양 변화**: 광대역 변화를 **빼고 난 뒤** 남는 대역별
 *    변화의 최댓값. NS 의 자취다. 광대역을 빼지 않으면 1번과 같은 것을
 *    두 번 세게 된다.
 * 3. **반복 사이 차이**: 같은 짓을 두 번 해서 광대역 레벨이 얼마나
 *    달라지는가. 부르는 쪽이 여러 번 돌려 넣는다.
 *
 * ## 문턱값의 근거 (아직 없다)
 *
 * 아래 기본값은 **실측으로 정한 값이 아니다.** 갤럭시 S23 에서 AGC 를
 * 켠 채와 끈 채로 재어 보고 정해야 한다(DoD 5항). 지금은 「이 정도면
 * 사람이 알아챌 만하다」는 짐작이고, 그 사실을 여기 적어 둔다 —
 * 숫자만 남으면 다음 사람이 근거가 있는 줄 안다.
 */
data class DspProbePolicy(
    /** 앞창·뒤창의 광대역 차이가 이보다 크면 이득이 변한 것으로 본다. */
    val maxBroadbandDriftDb: Double = 1.0,
    /**
     * 광대역을 뺀 뒤 대역별로 남는 변화의 한도.
     *
     * **실측으로 올렸다**(2026-09-23, 조용한 사무실, S23 하단 마이크,
     * PC 스피커로 핑크 노이즈). 처리가 없는데도 정렬된 두 실행에서
     * 2.2dB · 3.2dB 이 나왔다 — 둘 다 가장 낮은 대역(40Hz)이었다.
     * 그 대역은 스피커가 내는 것이 거의 없어 방 울림이 그대로 흔들린다.
     * 2.0 으로 두면 멀쩡한 측정이 「NS 가 있다」로 뜬다.
     */
    val maxBandShapeDriftDb: Double = 4.0,
    /** 반복 사이 광대역 레벨의 벌어짐 한도. */
    val maxRepeatSpreadDb: Double = 1.0,
    /** 앞창·뒤창에 각각 쓸 장 수. 둘을 합친 것보다 장이 적으면 판정하지 않는다. */
    val windowFrames: Int = 8,
    /**
     * 이 레벨(광대역 중앙값 기준 상대 dB)보다 낮은 대역은 **모양 변화에서
     * 뺀다.**
     *
     * 묻혀 있는 대역은 잡음이 그대로 흔들림으로 보여, 아무 처리가 없어도
     * 모양이 크게 변한 것처럼 나온다.
     *
     * **이것만으로는 「신호가 너무 작다」를 알 수 없다.** 상대값이라, 온
     * 대역이 똑같이 조용해도 서로 견주면 멀쩡해 보인다. 절대적으로 작은
     * 것은 [probeResidualDsp] 의 `noiseFloorDb` 로만 알 수 있다.
     */
    val bandFloorBelowBroadbandDb: Double = 45.0,

    /** 잡음 바닥을 받았을 때, 대역을 볼 만하다고 치는 최소 SNR. */
    val minBandSnrDb: Double = 6.0,

    /**
     * 이만큼은 봐야 판정한다(실기기에서 드러난 자리).
     *
     * 처음에는 「한 대역이라도 있으면 판정한다」였다. 그런데 사무실에서
     * 돌려 보니 31개 중 **7개**만 넘겼는데도 「의심」이 당당히 나왔다 —
     * 이득 −5.6dB, 모양 13.2dB. 그 숫자는 남은 일곱 대역의 잡음 변동일
     * 수도 있고, 화면은 그 사실을 **경고하지 않았다.** 숫자로만 적혀
     * 있으면 사람은 판정을 믿는다.
     *
     * **실측으로 올렸다**(같은 날 같은 자리). 창이 신호의 시작을 걸치면
     * 2개·14개처럼 적게 나오는데, 14개짜리 실행은 이득 −23.5dB 이라는
     * 믿을 수 없는 값을 내놓고도 12 를 넘어 판정까지 갔다. 제대로 정렬된
     * 실행은 31개 중 28개가 나왔다 — 그 언저리를 요구하는 편이 맞다.
     */
    val minBandsConsidered: Int = 20,
)

enum class DspVerdict {
    /** 변하는 처리를 찾지 못했다. **「처리 없음」이 아니다.** */
    NoTimeVaryingFound,

    /** 변하는 처리의 자취가 있다. 교정을 자동 적용하면 안 된다. */
    Suspect,

    /** 판정할 만큼 재지 못했다. **통과도 실패도 아니다.** */
    NotEnoughData,
}

/**
 * 검사 결과.
 *
 * 수치를 **전부 들고 나간다.** 「의심됨」만 돌려주면 사람이 무엇을 고쳐야
 * 할지 모르고, 문턱값을 나중에 실측으로 정할 때 쓸 값도 남지 않는다.
 */
data class DspProbeResult(
    val verdict: DspVerdict,
    /** 뒤창 − 앞창 (광대역 dB). 부호를 살린다 — 올라갔는지 내려갔는지가 단서다. */
    val broadbandDriftDb: Double?,
    /** 광대역 변화를 뺀 뒤 대역별로 남은 변화의 최댓값(절댓값). */
    val bandShapeDriftDb: Double?,
    /** 그 최댓값이 나온 대역. 화면에 어느 대역인지 적으려고 남긴다. */
    val worstBand: Int?,
    /** 반복 사이 광대역 레벨의 벌어짐. 한 번만 쟀으면 null. */
    val repeatSpreadDb: Double?,
    /** 모양 변화를 볼 때 실제로 쓴 대역 수. 0 이면 볼 것이 없었다는 뜻이다. */
    val bandsConsidered: Int,
    val framesUsed: Int,
    val reasonsKo: List<String>,
) {
    /**
     * 이 결과로 `dspVerifiedBySignal` 을 켜도 되는가.
     *
     * **[DspVerdict.NotEnoughData] 는 false 다.** 재지 못한 것과 재서
     * 괜찮았던 것을 같게 다루면, 신호를 안 틀고 넘어간 측정이 「확인함」
     * 으로 저장된다.
     */
    val verifiedBySignal: Boolean get() = verdict == DspVerdict.NoTimeVaryingFound
}

/**
 * 한 번 돌린 장들을 본다.
 *
 * @param frames 신호가 나오는 동안 모은 밴드 dB 장들. **시간 순서대로**,
 *   신호가 시작된 시점부터.
 * @param repeatBroadbandDb 앞서 돌린 회차들의 광대역 레벨. 비어 있으면
 *   반복 항목은 판정하지 않는다.
 * @param noiseFloorDb 신호를 끄고 잰 대역별 잡음(dB). 주면 SNR 이 모자란
 *   대역을 뺀다. **주지 않으면 「신호가 너무 작다」를 알 수 없다** —
 *   상대 문턱만으로는 온 대역이 똑같이 조용한 경우가 걸러지지 않는다.
 */
fun probeResidualDsp(
    frames: List<DoubleArray>,
    repeatBroadbandDb: List<Double> = emptyList(),
    noiseFloorDb: DoubleArray? = null,
    policy: DspProbePolicy = DspProbePolicy(),
): DspProbeResult {
    require(policy.windowFrames > 0) { "창 크기가 0 이하다: ${policy.windowFrames}" }

    val w = policy.windowFrames
    if (frames.size < w * 2) {
        return DspProbeResult(
            verdict = DspVerdict.NotEnoughData,
            broadbandDriftDb = null,
            bandShapeDriftDb = null,
            worstBand = null,
            repeatSpreadDb = null,
            bandsConsidered = 0,
            framesUsed = frames.size,
            reasonsKo = listOf(
                "판정할 만큼 재지 못했습니다. 장이 ${frames.size}개인데 ${w * 2}개가 필요합니다.",
            ),
        )
    }
    val bandCount = frames.first().size
    require(frames.all { it.size == bandCount }) { "장마다 밴드 수가 다르다" }

    val head = frames.take(w)
    val tail = frames.takeLast(w)

    // **중앙값으로 모은다.** 문 닫히는 소리 한 장이 평균을 끌고 가면
    // 그 자체가 「이득이 변했다」로 읽힌다([keepStableFrames] 와 같은 까닭).
    val headBroadband = medianOf(head.map { broadbandDb(it) })
    val tailBroadband = medianOf(tail.map { broadbandDb(it) })
    val broadbandDrift = tailBroadband - headBroadband

    // 어느 대역을 볼 것인가 — 묻힌 대역은 뺀다.
    val floor = headBroadband - policy.bandFloorBelowBroadbandDb
    val headBands = DoubleArray(bandCount) { b -> medianOf(head.map { it[b] }) }
    val tailBands = DoubleArray(bandCount) { b -> medianOf(tail.map { it[b] }) }
    require(noiseFloorDb == null || noiseFloorDb.size == bandCount) {
        "잡음 바닥의 밴드 수가 다르다: ${noiseFloorDb?.size} != $bandCount"
    }
    val considered = (0 until bandCount).filter { b ->
        headBands[b] >= floor &&
            (noiseFloorDb == null || headBands[b] - noiseFloorDb[b] >= policy.minBandSnrDb)
    }

    var worst = 0.0
    var worstBand: Int? = null
    for (b in considered) {
        // **광대역 변화를 뺀다.** 빼지 않으면 이득 변화가 모든 대역에
        // 똑같이 나타나 모양 변화로도 세어진다.
        val shape = (tailBands[b] - headBands[b]) - broadbandDrift
        if (abs(shape) > worst) {
            worst = abs(shape)
            worstBand = b
        }
    }

    val allBroadband = repeatBroadbandDb + listOf(medianOf(frames.map { broadbandDb(it) }))
    val repeatSpread = if (allBroadband.size >= 2) {
        allBroadband.max() - allBroadband.min()
    } else {
        null
    }

    val reasons = mutableListOf<String>()
    if (abs(broadbandDrift) > policy.maxBroadbandDriftDb) {
        reasons += "같은 소리를 트는 동안 레벨이 ${fmt(broadbandDrift)}dB 변했습니다. " +
            "자동 이득(AGC)이 남아 있을 수 있습니다."
    }
    if (worstBand != null && worst > policy.maxBandShapeDriftDb) {
        reasons += "${ThirdOctave.label(worstBand)} 대역의 모양이 ${fmt(worst)}dB 변했습니다. " +
            "잡음 억제(NS)가 남아 있을 수 있습니다."
    }
    if (repeatSpread != null && repeatSpread > policy.maxRepeatSpreadDb) {
        reasons += "같은 측정을 되풀이했는데 레벨이 ${fmt(repeatSpread)}dB 벌어졌습니다."
    }
    // **볼 대역이 모자라면 판정하지 않는다.** 일곱 대역으로 「스펙트럼
    // 모양이 변했다」고 말할 수는 없다(사무실 실측에서 드러난 자리).
    if (considered.size < policy.minBandsConsidered) {
        return DspProbeResult(
            verdict = DspVerdict.NotEnoughData,
            broadbandDriftDb = broadbandDrift,
            bandShapeDriftDb = null,
            worstBand = null,
            repeatSpreadDb = repeatSpread,
            bandsConsidered = considered.size,
            framesUsed = frames.size,
            reasonsKo = listOf(tooQuietKo(considered.size, bandCount)),
        )
    }

    return DspProbeResult(
        verdict = if (reasons.isEmpty()) DspVerdict.NoTimeVaryingFound else DspVerdict.Suspect,
        broadbandDriftDb = broadbandDrift,
        bandShapeDriftDb = worst,
        worstBand = worstBand,
        repeatSpreadDb = repeatSpread,
        bandsConsidered = considered.size,
        framesUsed = frames.size,
        reasonsKo = if (reasons.isEmpty()) listOf(NO_TIME_VARYING_FOUND_KO) else reasons,
    )
}

/**
 * 통과했을 때 적는 말.
 *
 * **「처리가 없습니다」라고 쓰지 않는다.** 고정 처리는 이 검사로 보이지
 * 않는다(위 KDoc 참고).
 */
fun tooQuietKo(considered: Int, total: Int): String =
    "믿고 볼 수 있는 대역이 $total 개 중 $considered 개뿐입니다. 이만큼으로는 " +
        "이득이나 스펙트럼이 변했는지 말할 수 없습니다 — 남은 대역의 잡음 " +
        "흔들림일 수도 있습니다. 더 크게 틀거나 주변을 조용히 한 뒤 다시 하십시오."

const val NO_TIME_VARYING_FOUND_KO: String =
    "소리를 트는 동안 이득도 스펙트럼 모양도 눈에 띄게 변하지 않았습니다. " +
        "시간에 따라 변하는 처리(AGC·NS)는 찾지 못했습니다 — 고정된 처리는 " +
        "이 방법으로 알 수 없고, 그것은 교정이 함께 걷어냅니다."

private fun medianOf(xs: List<Double>): Double {
    val s = xs.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
}

private fun fmt(x: Double): String = String.format("%.1f", x)
