package kr.joa.selahrta.dsp

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 지연을 찾은 결과.
 *
 * @param samples 표본 단위 지연. [found] 가 false 면 뜻이 없다.
 * @param sharpness 가장 큰 봉우리 ÷ 그 다음 봉우리. **클수록 또렷하다.**
 * @param found 믿을 만한가.
 */
data class DelayResult(val samples: Int, val sharpness: Double, val found: Boolean)

/** PHAT 로 나눌 때 **0 으로 나누지 않기 위한 바닥.** 소리 없는 대역은 크기가 0 이다. */
private const val PHAT_FLOOR = 1e-12

/**
 * 기준과 측정의 시간차를 **상호상관**으로 찾는다.
 *
 * ```
 * R(τ) = Σ x[n] · y[n+τ]      가장 큰 τ 가 지연이다
 * ```
 *
 * ## 숫자만 내놓지 않는다
 *
 * 조용한 자리에서는 **잡음끼리도 어딘가에서 가장 큰 값**이 나온다. 그 값을
 * 지연이라고 적으면 사람은 그 숫자로 스피커를 정렬한다. 그래서 **또렷함**
 * (으뜸 봉우리 ÷ 버금 봉우리)을 함께 보고, 문턱 아래면 **못 찾았다고
 * 말한다.**
 *
 * **문턱값 [minSharpness] 는 실측으로 정한 값이 아니다.** 실제 공간에서
 * 재어 보고 정해야 한다. 그때까지 화면은 이 값을 함께 적는다.
 *
 * ## 분수 표본은 보지 않는다
 *
 * 48kHz 에서 한 표본이 0.021ms 다. 정렬에는 충분하다. 분수 표본은 위상을
 * 할 때 필요하고, 그건 이번 범위가 아니다.
 */
class DelayEstimator(
    private val analysisSize: Int = 32_768,
    private val maxLagSamples: Int = 24_000,
    private val minSharpness: Double = 2.0,
    /**
     * **PHAT 가중**을 쓸 것인가(기본: 쓴다).
     *
     * 상호 스펙트럼의 **크기를 1 로 고르고 위상만 남긴다.** 잔향이 긴
     * 공간에서 봉우리가 훨씬 뾰족해진다 — 그래야 「또렷함」으로 참·거짓을
     * 가를 수 있다. 잔향 속 지연 추정의 표준 방법이다.
     *
     * **끌 수 있게 둔 까닭**: 끈 것과 견주는 시험이 있어야 「정말 더
     * 뾰족한가」를 말이 아니라 숫자로 보일 수 있다.
     */
    private val phat: Boolean = true,
) {
    init {
        require(analysisSize > 0) { "analysisSize 는 1 이상: $analysisSize" }
        require(maxLagSamples in 1 until analysisSize) {
            "maxLagSamples 는 1..${analysisSize - 1} 이라야 한다: $maxLagSamples"
        }
    }

    /** **원형으로 감기지 않도록** 분석 길이의 두 배를 쓴다. */
    private val fftSize = Integer.highestOneBit(analysisSize * 2 - 1) * 2
    private val fft = Fft(fftSize)

    private val xRe = DoubleArray(fftSize)
    private val xIm = DoubleArray(fftSize)
    private val yRe = DoubleArray(fftSize)
    private val yIm = DoubleArray(fftSize)

    fun estimate(reference: DoubleArray, measurement: DoubleArray): DelayResult {
        val n = minOf(reference.size, measurement.size, analysisSize)
        load(reference, n, xRe, xIm)
        load(measurement, n, yRe, yIm)

        fft.transform(xRe, xIm)
        fft.transform(yRe, yIm)

        // conj(X) · Y — 그리고 **PHAT 가중**(크기를 고르게, 위상만 남김)
        for (i in 0 until fftSize) {
            val re = xRe[i] * yRe[i] + xIm[i] * yIm[i]
            val im = xRe[i] * yIm[i] - xIm[i] * yRe[i]
            if (phat) {
                val mag = sqrt(re * re + im * im)
                if (mag > PHAT_FLOOR) {
                    xRe[i] = re / mag
                    xIm[i] = im / mag
                } else {
                    xRe[i] = 0.0
                    xIm[i] = 0.0
                }
            } else {
                xRe[i] = re
                xIm[i] = im
            }
        }

        inverse(xRe, xIm)

        var best = -1
        var bestVal = 0.0
        for (t in 0..maxLagSamples) {
            val v = abs(xRe[t])
            if (v > bestVal) { bestVal = v; best = t }
        }
        if (best < 0 || bestVal <= 0.0) return DelayResult(0, 0.0, false)

        // **버금은 으뜸 둘레를 뺀 곳에서** 고른다 — 봉우리 바로 옆은
        // 같은 봉우리의 어깨다.
        val guard = 8
        var second = 0.0
        for (t in 0..maxLagSamples) {
            if (abs(t - best) <= guard) continue
            val v = abs(xRe[t])
            if (v > second) second = v
        }

        val sharp = if (second <= 0.0) Double.MAX_VALUE else bestVal / second
        return DelayResult(best, sharp, sharp >= minSharpness)
    }

    private fun load(src: DoubleArray, n: Int, re: DoubleArray, im: DoubleArray) {
        java.util.Arrays.fill(re, 0.0)
        java.util.Arrays.fill(im, 0.0)
        System.arraycopy(src, 0, re, 0, n)
    }

    /**
     * 역변환. **[Fft] 에는 정변환밖에 없다** — 실수와 허수를 바꿔 정변환을
     * 돌리고 크기로 나누면 역변환이 된다.
     */
    private fun inverse(re: DoubleArray, im: DoubleArray) {
        fft.transform(im, re)
        val scale = 1.0 / fftSize
        for (i in re.indices) {
            re[i] *= scale
            im[i] *= scale
        }
    }
}
