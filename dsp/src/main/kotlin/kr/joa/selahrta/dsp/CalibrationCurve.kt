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
     * 1/3 옥타브 31밴드에 걸 보정값(dB). **빼는 값이다.**
     *
     * 밴드 하나의 응답은 그 밴드 안에서도 변하므로, 중심 한 점만 보지 않고
     * 아래끝·중심·위끝을 에너지로 평균한다. 좁은 밴드에서는 차이가 없지만
     * 곡선이 가파른 구간에서는 1dB 넘게 갈린다.
     */
    fun bandGainsDb(): DoubleArray = DoubleArray(ThirdOctave.BAND_COUNT) { b ->
        val lo = ThirdOctave.lowerEdge(b)
        val c = ThirdOctave.exactCenter(b)
        val hi = ThirdOctave.upperEdge(b)
        // 세 점의 전력 평균. dB 평균이 아니다.
        val p = (10.0.pow(gainDbAt(lo) / 10.0) +
            10.0.pow(gainDbAt(c) / 10.0) +
            10.0.pow(gainDbAt(hi) / 10.0)) / 3.0
        10.0 * log10(p)
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
