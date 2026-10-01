package kr.joa.selahrta.dsp

import kotlin.math.log10

/**
 * 전달함수 한 판.
 *
 * @param magnitudeDb 칸마다의 `|H1|` (dB). [valid] 가 false 인 칸은 뜻이 없다.
 * @param coherence 칸마다의 `γ²` (0~1). 무효인 칸은 0 이다.
 * @param valid **기준이 그 칸에 쓸 만큼 있었는가.** false 면 그리지 않는다.
 * @param averages 모은 블록 수. 상관을 보일지 말지가 여기 달려 있다.
 */
data class TransferResult(
    val magnitudeDb: DoubleArray,
    val coherence: DoubleArray,
    val valid: BooleanArray,
    val averages: Int,
)

/** 상관을 화면에 어떻게 둘 것인가(명세 9장). */
enum class CoherenceDisplay {
    /** 1~7 — 숨긴다. 「측정 중」으로 적는다. */
    Hidden,

    /** 8~15 — 보이되 **안정화 중**임을 함께 적는다. */
    Stabilizing,

    /** 16 이상 — 기본 표시. */
    Shown,
}

/**
 * **8 은 보일 수 있는 최소, 16 이 기본 목표다.**
 *
 * 이 숫자는 **초기 운영값이지 통계적 신뢰구간이 아니다.** 50% 겹친 블록은
 * 독립 표본이 아니므로 「8이면 충분」처럼 읽으면 안 된다. 실측으로 조정한다.
 */
fun coherenceDisplay(averages: Int): CoherenceDisplay = when {
    averages < 8 -> CoherenceDisplay.Hidden
    averages < 16 -> CoherenceDisplay.Stabilizing
    else -> CoherenceDisplay.Shown
}

/**
 * 모아 둔 스펙트럼에서 **H1 과 Coherence** 를 낸다.
 *
 * ```
 * H1(f) = Sxy / Sxx
 * γ²(f) = |Sxy|² / (Sxx · Syy)
 * ```
 *
 * ## 기준이 약한 칸을 통째로 막는다
 *
 * `H1 = Sxy/Sxx` 이므로 **기준 에너지가 거의 없는 칸에서는 `Sxx` 가 작아져
 * 결과가 크게 튄다.** 그 값은 시스템의 응답이 아니라 **0 에 가까운 수로
 * 나눈 자국**이다. 음악이나 프로그램 신호를 기준으로 쓰면 바로 걸린다.
 *
 * 그래서 **가장 센 칸에 견주어** [refFloorDb] 아래인 칸은 무효로 둔다.
 * 절대값이 아니라 **상대값**으로 보는 까닭은, 사람이 볼륨을 올리고 내려도
 * 판정이 따라 움직이지 않게 하려는 것이다.
 *
 * **[refFloorDb] 는 실측으로 정할 값이다.** −40dB 은 출발점일 뿐이다.
 */
fun transferFunction(avg: SpectralAverager, refFloorDb: Double = -40.0): TransferResult {
    val bins = avg.bins
    val mag = DoubleArray(bins)
    val coh = DoubleArray(bins)
    val valid = BooleanArray(bins)

    if (avg.count == 0) return TransferResult(mag, coh, valid, 0)

    var peak = 0.0
    for (i in 0 until bins) if (avg.sxx[i] > peak) peak = avg.sxx[i]
    if (peak <= 0.0) return TransferResult(mag, coh, valid, avg.count)

    val floor = peak * Math.pow(10.0, refFloorDb / 10.0)

    for (i in 0 until bins) {
        val sxx = avg.sxx[i]
        if (sxx < floor) continue          // 무효 — 그리지 않는다

        val re = avg.sxyRe[i]
        val im = avg.sxyIm[i]
        val crossMagSq = re * re + im * im

        valid[i] = true
        mag[i] = 10.0 * log10(crossMagSq / (sxx * sxx))

        val denom = sxx * avg.syy[i]
        coh[i] = if (denom > 0.0) (crossMagSq / denom).coerceIn(0.0, 1.0) else 0.0
    }

    return TransferResult(mag, coh, valid, avg.count)
}
