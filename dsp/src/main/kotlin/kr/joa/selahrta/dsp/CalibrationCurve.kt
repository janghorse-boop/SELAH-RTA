package kr.joa.selahrta.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/** 보정 곡선의 점 하나. */
data class CurvePoint(val hz: Double, val gainDb: Double)

/**
 * 마이크의 주파수 보정 곡선(명세 8장).
 *
 * 제조사가 주는 보정 파일은 「이 마이크는 이 주파수에서 이만큼 더/덜 잡는다」를
 * 적은 것이다. 측정값에 **그 반대**를 걸어야 평탄해진다.
 *
 * 파일마다 부호 규약이 다른 것이 실무의 함정인데, 널리 쓰이는 .cal/.frd 는
 * 「마이크의 응답」을 적고 프로그램이 빼는 쪽이다. 우리도 그렇게 한다 —
 * [gainDbAt] 이 돌려주는 값을 **빼면** 보정된 값이 된다.
 */
class CalibrationCurve private constructor(
    /** 주파수 오름차순으로 정렬된 점들. */
    val points: List<CurvePoint>,
) {
    init {
        require(points.size >= 2) { "보정 곡선에는 점이 둘 이상 있어야 한다" }
    }

    val lowestHz: Double get() = points.first().hz
    val highestHz: Double get() = points.last().hz

    /**
     * [hz] 에서 마이크의 응답(dB).
     *
     * **주파수는 로그로, dB 는 선형으로 보간한다.** 주파수를 선형으로 보간하면
     * 저역에서 크게 어긋난다 — 100Hz 와 1000Hz 사이의 「가운데」는 550Hz 가
     * 아니라 316Hz 이기 때문이다.
     *
     * 곡선 바깥은 **끝점 값을 그대로 유지한다.** 밖으로 이어서 추정하면
     * 20Hz 아래나 20kHz 위에서 터무니없는 값이 나오는데, 그 대역은 마이크를
     * 잰 적이 없는 구간이라 추정할 근거가 없다.
     */
    fun gainDbAt(hz: Double): Double {
        require(hz > 0.0) { "주파수는 양수여야 한다: $hz" }
        if (hz <= lowestHz) return points.first().gainDb
        if (hz >= highestHz) return points.last().gainDb

        // 이진 탐색으로 구간을 찾는다. 점이 수천 개인 파일도 있다.
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (points[mid].hz <= hz) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        if (b.hz == a.hz) return a.gainDb
        val t = (log10(hz) - log10(a.hz)) / (log10(b.hz) - log10(a.hz))
        return a.gainDb + t * (b.gainDb - a.gainDb)
    }

    /**
     * FFT 칸마다 **곱할** 보정 계수(선형 전력비).
     *
     * 이것이 실제 보정 경로다. 칸 전력에 이 값을 곱한 뒤 밴드로 합산하면
     * `Σ P[k]·10^(−g(f_k)/10)` 이 되어, 밴드 안 어디에 에너지가 있든
     * 그 주파수의 응답을 받는다.
     *
     * 칸 0 은 DC 라 주파수가 0 이다. 마이크 응답을 말할 수 있는 자리가
     * 아니므로 곡선의 첫 점 값을 쓴다([gainDbAt] 이 곡선 밖을 그렇게 다룬다).
     */
    fun binCorrectionLinear(fftSize: Int, sampleRate: Int): DoubleArray {
        require(fftSize > 0) { "FFT 길이가 0 이하다: $fftSize" }
        require(sampleRate > 0) { "샘플레이트가 0 이하다: $sampleRate" }
        val binWidth = sampleRate.toDouble() / fftSize
        return DoubleArray(fftSize / 2 + 1) { k ->
            val hz = if (k == 0) lowestHz else k * binWidth
            10.0.pow(-gainDbAt(hz) / 10.0)
        }
    }

    /**
     * 밴드 중심에서의 응답(dB). **화면에 곡선 모양을 그리기 위한 값이다.**
     *
     * 측정값 보정에 쓰지 않는다. 밴드 하나를 숫자 하나로 보정하려면 그 밴드
     * 안의 에너지가 어느 주파수에 있는지 알아야 하는데, 그건 신호마다 다르다.
     * 예전에는 아래끝·중심·위끝을 전력 평균해 뺐는데, 1kHz 밴드 응답이
     * −6/0/+6dB 인 곡선에서 1kHz 순음의 실제 보정량은 0dB 이지만 그 평균은
     * **+2.4157dB** 이라 없던 오차를 만들었다(독립 검증 R05).
     *
     * 실제 보정은 [binCorrectionLinear] 로 칸마다 한다.
     */
    fun bandGainsDb(): DoubleArray = DoubleArray(ThirdOctave.BAND_COUNT) { b ->
        gainDbAt(ThirdOctave.exactCenter(b))
    }

    /** 곡선이 실제로 덮는 밴드인가. 밖이면 끝점 값을 늘여 쓴 것이라 근거가 약하다. */
    fun bandCovered(): BooleanArray = BooleanArray(ThirdOctave.BAND_COUNT) { b ->
        ThirdOctave.exactCenter(b) in lowestHz..highestHz
    }

    /** 가장 큰 보정량. 너무 크면 파일이 이상한 것이다. */
    val maxAbsGainDb: Double get() = points.maxOf { abs(it.gainDb) }

    companion object {
        /**
         * 점들로 곡선을 만든다.
         *
         * 정렬·중복 제거를 여기서 한 번만 한다. 파일은 대개 정렬돼 있지만
         * 아닌 것도 있고, 정렬 안 된 채로 보간하면 조용히 틀린 값이 나온다.
         */
        fun of(raw: List<CurvePoint>): Result<CalibrationCurve> {
            val clean = raw
                .filter { it.hz > 0.0 && it.hz.isFinite() && it.gainDb.isFinite() }
                .sortedBy { it.hz }
                .distinctBy { it.hz }
            return when {
                clean.size < 2 -> Result.failure(
                    IllegalArgumentException("쓸 수 있는 점이 ${clean.size}개뿐입니다. 최소 둘이 필요합니다."),
                )
                else -> Result.success(CalibrationCurve(clean))
            }
        }
    }
}
