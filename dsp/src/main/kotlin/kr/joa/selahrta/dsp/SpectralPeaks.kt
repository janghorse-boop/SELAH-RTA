package kr.joa.selahrta.dsp

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * 스펙트럼에서 찾아낸 봉우리 하나.
 *
 * **이것만으로 하울링이라고 하지 않는다**(명세 9장). 여기까지는 「튀는
 * 자리가 있다」는 관찰이고, 그것이 하울링인지는 [FeedbackDetector] 가
 * 시간을 두고 본다.
 */
data class SpectralPeak(
    /** 봉우리의 FFT 칸 번호. */
    val bin: Int,
    /**
     * 봉우리의 주파수(Hz). **칸 번호 × 칸 폭이 아니다.**
     *
     * 순음이 칸 사이에 놓이면 이웃 칸으로 나뉘는데, 세 칸의 무게중심을
     * 보면 칸 사이 위치를 되찾을 수 있다. 하울링은 같은 주파수에 머무는
     * 것이 특징이라 이 정밀도가 판정에 바로 쓰인다 — 칸 단위로만 보면
     * 996Hz 와 1002Hz 가 같은 칸이 되어 「머물렀다」로 읽힌다.
     */
    val hz: Double,
    /** 봉우리의 전력(선형). */
    val power: Double,
    /**
     * 둘레보다 얼마나 솟았는가(dB).
     *
     * 둘레의 **중앙값**과 견준다. 평균이 아니라 중앙값인 까닭은, 옆에 다른
     * 봉우리가 하나 있어도 흔들리지 않기 때문이다 — 화음이 울릴 때 평균을
     * 쓰면 둘레가 통째로 올라가 봉우리가 낮아 보인다.
     */
    val prominenceDb: Double,
    /**
     * 봉우리의 폭(칸 수). −3dB 아래로 내려갈 때까지 센다.
     *
     * 하울링은 순음이라 Hann 창의 주엽만큼만 넓다(4096점에서 2~3칸).
     * 말소리의 포먼트나 잡음의 언덕은 훨씬 넓다.
     */
    val widthBins: Int,
)

/**
 * 스펙트럼에서 봉우리를 찾는다(명세 9장).
 *
 * **가장 큰 칸 하나를 집는 것이 아니다.** 명세가 못박은 대로, 튀는 칸
 * 하나를 하울링으로 단정하면 큰 소리가 날 때마다 오탐이 난다. 여기서는
 * 「둘레보다 얼마나 솟았고, 얼마나 좁은가」를 재기만 한다.
 */
class SpectralPeakFinder(
    private val fftSize: Int,
    private val sampleRate: Int,
    /** 이 주파수 아래는 보지 않는다. FFT 가 제대로 분해하지 못하는 대역이다. */
    private val minHz: Double = 120.0,
    /** 이 주파수 위는 보지 않는다. 하울링이 생기는 대역을 넉넉히 덮는다. */
    private val maxHz: Double = 10_000.0,
) {
    init {
        require(fftSize > 0) { "FFT 길이가 0 이하다" }
        require(sampleRate > 0) { "샘플레이트가 0 이하다" }
        require(minHz > 0 && maxHz > minHz) { "주파수 범위가 뒤집혔다" }
    }

    val binHz: Double = sampleRate.toDouble() / fftSize
    private val binCount = fftSize / 2 + 1

    private val lowBin = max(1, (minHz / binHz).toInt())
    private val highBin = min(binCount - 2, (maxHz / binHz).toInt())

    /**
     * 둘레를 재는 구간(칸). 봉우리 자신의 치마는 빼고 그 바깥만 본다.
     *
     * 안쪽 경계가 주엽보다 넓어야 한다 — 주엽 안을 「둘레」로 세면 봉우리
     * 자신과 견주는 꼴이라 솟은 정도가 늘 0 에 가까워진다.
     */
    private val skirtBins = 4
    private val neighbourhoodBins = 48

    /** 둘레 중앙값을 낼 때 쓰는 작업 배열. 프레임마다 새로 만들지 않는다. */
    private val scratch = DoubleArray(2 * neighbourhoodBins)

    /**
     * 봉우리를 찾는다. 솟은 정도가 [minProminenceDb] 이상인 것만 돌려준다.
     *
     * [power] 는 [PowerSpectrum.compute] 가 낸 칸별 전력이다.
     */
    fun find(
        power: DoubleArray,
        minProminenceDb: Double = 6.0,
        limit: Int = 8,
    ): List<SpectralPeak> {
        require(power.size == binCount) { "power 길이가 ${binCount} 가 아니다: ${power.size}" }
        if (highBin <= lowBin) return emptyList()

        val found = ArrayList<SpectralPeak>()
        for (k in lowBin..highBin) {
            val p = power[k]
            if (p <= 0.0) continue
            // 지역 최대인가. 같은 값이 이어질 때 두 번 잡지 않도록 한쪽만 등호를 쓴다.
            if (p <= power[k - 1] || p < power[k + 1]) continue

            val floor = neighbourMedian(power, k)
            if (floor <= 0.0) continue
            val prominence = 10.0 * log10(p / floor)
            if (prominence < minProminenceDb) continue

            found.add(
                SpectralPeak(
                    bin = k,
                    hz = refinedHz(power, k),
                    power = p,
                    prominenceDb = prominence,
                    widthBins = widthAtHalfPower(power, k),
                ),
            )
        }

        // 솟은 순서로 자른다. 많이 들고 있어 봐야 뒤쪽은 판정에 쓰이지 않는다.
        return found.sortedByDescending { it.prominenceDb }.take(limit)
    }

    /**
     * 봉우리 둘레의 중앙값 전력.
     *
     * 봉우리 자신의 치마([skirtBins])는 빼고, 그 바깥
     * [neighbourhoodBins] 칸까지를 본다.
     */
    private fun neighbourMedian(power: DoubleArray, k: Int): Double {
        var n = 0
        for (d in (skirtBins + 1)..neighbourhoodBins) {
            val a = k - d
            val b = k + d
            if (a >= 0) scratch[n++] = power[a]
            if (b < power.size) scratch[n++] = power[b]
        }
        if (n == 0) return 0.0
        // 부분 정렬로 충분하지만, n 이 100 남짓이라 그냥 정렬한다.
        java.util.Arrays.sort(scratch, 0, n)
        return if (n % 2 == 1) scratch[n / 2] else (scratch[n / 2 - 1] + scratch[n / 2]) / 2.0
    }

    /**
     * 세 칸으로 칸 사이 위치를 되찾는다.
     *
     * 봉우리와 양옆 칸에 포물선을 맞춰 꼭짓점을 찾는다. **dB 로 바꾼 뒤에
     * 맞춘다** — 전력 그대로 맞추면 한쪽으로 치우친다. Hann 창의 주엽은
     * 로그 눈금에서 포물선에 가깝기 때문이다.
     *
     * 1kHz 순음(칸 85.33)에서 전력으로 맞추면 998.7Hz(2.3cent 어긋남),
     * dB 로 맞추면 1000.0Hz 가 나온다. 이 정밀도가 판정에 바로 쓰인다 —
     * 흔들림을 cent 로 재기 때문이다.
     */
    private fun refinedHz(power: DoubleArray, k: Int): Double {
        val a = power[k - 1]
        val b = power[k]
        val c = power[k + 1]
        if (a <= 0.0 || b <= 0.0 || c <= 0.0) return k * binHz
        val la = log10(a)
        val lb = log10(b)
        val lc = log10(c)
        val denom = la - 2 * lb + lc
        // 세 점이 일직선이면 꼭짓점을 잡을 수 없다. 칸 한가운데로 둔다.
        val delta = if (denom == 0.0) 0.0 else (0.5 * (la - lc) / denom).coerceIn(-0.5, 0.5)
        return (k + delta) * binHz
    }

    /** 봉우리에서 −3dB 아래로 내려갈 때까지의 폭(칸). */
    private fun widthAtHalfPower(power: DoubleArray, k: Int): Int {
        val half = power[k] / 2.0
        var lo = k
        while (lo > 0 && power[lo - 1] >= half) lo--
        var hi = k
        while (hi < power.size - 1 && power[hi + 1] >= half) hi++
        return hi - lo + 1
    }
}
