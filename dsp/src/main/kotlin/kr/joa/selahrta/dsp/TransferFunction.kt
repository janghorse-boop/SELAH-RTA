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
 *
 * ## DC(0Hz) 칸은 항상 무효다
 *
 * **DC 는 전달함수에서 뜻이 없는 주파수다.** 그런데 실측에서는 마이크
 * DC 바이어스나 저주파 험이 0Hz 칸에 크게 실릴 수 있다. 그 칸을
 * [peak] 후보에 넣으면 **실제 신호의 봉우리가 아니라 DC 에너지로 문턱이
 * 잡혀**, 멀쩡히 상관된 칸들까지 통째로 무효가 된다. 그래서 피크를 고를
 * 때도, 본 루프를 돌 때도 **DC(인덱스 0)는 건너뛴다** — `valid[0]` 은
 * 항상 `false` 로 남는다.
 *
 * **나이키스트 칸(`bins-1`)은 그대로 둔다.** 거기엔 위상이 없고 허수부도
 * 0 이라 DC 와 달리 값이 튈 여지가 적다 — 그래서 DC 만 뺐다.
 */
fun transferFunction(avg: SpectralAverager, refFloorDb: Double = -40.0): TransferResult {
    val bins = avg.bins
    val mag = DoubleArray(bins)
    val coh = DoubleArray(bins)
    val valid = BooleanArray(bins)

    if (avg.count == 0) return TransferResult(mag, coh, valid, 0)

    // DC(i=0)는 피크 후보에서 제외한다 — 마이크 바이어스·저주파 험이 실려
    // 문턱을 망칠 수 있다. 나이키스트(bins-1)는 위상이 없어 그대로 둔다.
    var peak = 0.0
    for (i in 1 until bins) if (avg.sxx[i] > peak) peak = avg.sxx[i]
    if (peak <= 0.0) return TransferResult(mag, coh, valid, avg.count)

    val floor = peak * Math.pow(10.0, refFloorDb / 10.0)

    // i = 1 부터 돈다 — DC(i=0)는 결과에서 언제나 무효(valid[0] == false)로 남는다.
    for (i in 1 until bins) {
        val sxx = avg.sxx[i]
        if (sxx < floor) continue          // 무효 — 그리지 않는다

        val re = avg.sxyRe[i]
        val im = avg.sxyIm[i]
        val crossMagSq = re * re + im * im

        valid[i] = true
        // 유효한 칸인데 측정이 그 주파수에서 완전 무음(crossMagSq == 0)이면
        // magnitudeDb 는 -Infinity 가 된다. 값 자체는 올바른 표현이지만,
        // 화면을 그리는 다음 작업은 유한하지 않은 값을 다뤄야 한다.
        mag[i] = 10.0 * log10(crossMagSq / (sxx * sxx))

        val denom = sxx * avg.syy[i]
        // coerceIn(0.0, 1.0) 은 부동소수 잡음 방어일 뿐이다 — 분자·분모가
        // 모두 제곱합이라 음수가 될 수 없고, 상한도 코시-슈바르츠 부등식
        // (|Sxy|² ≤ Sxx·Syy)으로 수학적으로 보장된다. 두 경계 모두
        // 「수학적으로 넘을 수 없는 값」이지 실제로 넘는 경우를 가리는 것이 아니다.
        coh[i] = if (denom > 0.0) (crossMagSq / denom).coerceIn(0.0, 1.0) else 0.0
    }

    return TransferResult(mag, coh, valid, avg.count)
}
