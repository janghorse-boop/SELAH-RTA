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
    /**
     * 주파수 오름차순으로 정렬된 점들. **언제나 「응답」 규약이다.**
     *
     * 파일이 보정값(correction)이었다면 [of] 에서 이미 뒤집어 두었다 —
     * 그래야 이 클래스의 나머지가 읽는 법을 다시 따지지 않는다. 파일에
     * 적혀 있던 그대로는 [rawPoints] 에 있다.
     */
    val points: List<CurvePoint>,
    /** 파일에 적혀 있던 값 그대로. 되짚을 때와 화면에 원본을 보일 때 쓴다. */
    val rawPoints: List<CurvePoint>,
    /** 둘째 열을 무엇으로 읽었는가(독립 검토 R04). 프로파일에 남는다. */
    val reading: CurveReading,
    /**
     * **실제로 잰 구간들.** null 이면 점 전체가 근거가 있다는 뜻이다.
     *
     * ## 왜 필요한가 (독립 검토 CA-02)
     *
     * 잰 프로파일에는 **못 믿는 자리**가 섞인다(SNR 부족, 안 잰 대역).
     * 예전에는 그런 점을 **빼고** 곡선을 만들었다. 그러면 남은 점 사이가
     * 그냥 이어져 버려, 버렸던 구간이 **다시 보정된다** — 화면에서 「이
     * 대역은 못 믿는다」고 적어 놓고 그 대역을 고치고 있었다.
     *
     * 검토자가 잰 값: 2kHz 를 무효로 버렸는데 실제로 +3.008dB 가 걸렸고,
     * 측정 범위 밖(8kHz·46.9Hz)에는 끝점 값 +6dB 가 늘어나 걸렸다.
     *
     * 그래서 **뺀 자리를 기억한다.** 구간 밖의 칸은 계수 1.0 — 원래 전력
     * 그대로 둔다. 「모르는 곳은 건드리지 않는다」가 유일하게 정직하다.
     *
     * 제조사 CAL 파일은 null 이다 — 그쪽은 파일 전체가 측정값이고, 범위
     * 밖에서 끝점을 유지하는 것이 오래 쓰던 규약이다. 둘은 다른 계약이라
     * 구별한다(검토자 권고).
     */
    val supportHz: List<ClosedFloatingPointRange<Double>>? = null,
) {
    init {
        require(points.size >= 2) { "보정 곡선에는 점이 둘 이상 있어야 한다" }
    }

    /**
     * 같은 파일을 **다른 읽는 법으로** 다시 본다.
     *
     * 사람이 화면에서 골랐을 때 쓴다. 파일을 다시 읽지 않으므로 점이
     * 달라질 일이 없다 — 달라지는 것은 부호뿐이다.
     */
    fun withReading(other: CurveReading): CalibrationCurve =
        if (other == reading) this else CalibrationCurve(
            points = rawPoints.map { CurvePoint(it.hz, it.gainDb * other.toResponseSign) },
            rawPoints = rawPoints,
            reading = other,
            supportHz = supportHz,
        )

    val lowestHz: Double get() = points.first().hz
    val highestHz: Double get() = points.last().hz

    /**
     * 이 파일이 **실제로 잰** 주파수 범위.
     *
     * [gainDbAt] 은 이 범위 밖에서도 끝점 값을 돌려주므로 **숫자가
     * 나온다는 것이 잰 적이 있다는 뜻이 아니다.** 보정에 쓸 자리를
     * 고를 때는 숫자가 아니라 이 범위를 봐야 한다(독립 검증 CP02:
     * CAL 을 200Hz~10kHz 로 두었는데 30Hz 의 보정이 valid=true 였다).
     */
    val rangeHz: ClosedFloatingPointRange<Double> get() = lowestHz..highestHz

    /**
     * [hz] 를 이 파일이 실제로 쟀는가. 밖이면 보정 근거가 없다.
     *
     * [supportHz] 가 있으면 **구멍까지 본다** — 점 사이가 이어져 있다고
     * 해서 그 사이를 잰 것은 아니다(독립 검토 CA-02).
     */
    fun covers(hz: Double): Boolean {
        val support = supportHz ?: return hz in lowestHz..highestHz
        return support.any { hz in it }
    }

    /**
     * 이 주파수에 **보정을 걸어도 되는가.** [covers] 와 다르다.
     *
     * [covers] 는 「이 파일이 쟀는가」이고, 이것은 「걸 것인가」다.
     * 제조사 CAL 파일([supportHz] 가 null)은 범위 밖에서도 끝점 값을
     * 늘여 걸어 왔다 — 오래된 규약이고, 바꾸면 이미 잰 보정이 달라진다.
     * 잰 프로파일만 구간 밖을 비운다(독립 검토 CA-02 의 권고).
     */
    private fun supported(hz: Double): Boolean {
        val support = supportHz ?: return true
        return support.any { hz in it }
    }

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
     *
     * ## [referenceHz] — **절대 레벨의 기준점**
     *
     * 주면 그 주파수의 보정량이 정확히 0dB 이 되도록 곡선 전체를 상수만큼
     * 민다. **모양은 바뀌지 않는다** — 곡선의 절대 높이는 원래 뜻이 없다.
     *
     * ### 왜 필요한가
     *
     * 음압 교정기는 1kHz 순음 하나로 **절대 레벨**을 잡아 준다. 그런데
     * 그렇게 잡은 오프셋은 **시간영역 음압계**(가중 필터만 거친다)에서
     * 재는데, RTA 밴드는 그 위에 보정 곡선까지 받는다. 곡선의 1kHz 값이
     * 0dB 이 아니면 **두 숫자가 그만큼 갈라진다** — 큰 숫자(dBA)는 94.0 인데
     * 1kHz 막대는 96.1 인 식이다. 곡선을 갈아 끼우면 그 차이도 따라 바뀐다.
     *
     * 지금 쓰는 보정 곡선은 300~3000Hz **평균**을 0dB 로 맞춘다
     * ([kr.joa.selahrta.dsp.normalizeToBand]). 평균이 0 이라고 1kHz 가 0 인
     * 것은 아니다.
     *
     * 1kHz 에 못을 박으면 그 갈라짐이 **구조적으로 0** 이 된다. 어느 곡선을
     * 걸어도, 곡선을 빼도, 1kHz 는 교정기가 말한 그 값이다.
     *
     * ### 곡선이 1kHz 를 덮지 않으면
     *
     * [gainDbAt] 이 끝점 값을 돌려주므로 **잰 적 없는 값에 못을 박게 된다.**
     * 그래도 안 박는 것보다는 낫다 — 안 박으면 어긋남의 크기를 아무도
     * 모른다. 1kHz 를 덮지 않는 보정 파일은 [covers] 로 걸러 화면이 알린다.
     */
    fun binCorrectionLinear(
        fftSize: Int,
        sampleRate: Int,
        referenceHz: Double? = null,
    ): DoubleArray {
        require(fftSize > 0) { "FFT 길이가 0 이하다: $fftSize" }
        require(sampleRate > 0) { "샘플레이트가 0 이하다: $sampleRate" }
        require(referenceHz == null || referenceHz > 0.0) { "기준 주파수가 0 이하다: $referenceHz" }
        val ref = referenceHz?.let { gainDbAt(it) } ?: 0.0
        val binWidth = sampleRate.toDouble() / fftSize
        return DoubleArray(fftSize / 2 + 1) { k ->
            val hz = if (k == 0) lowestHz else k * binWidth
            // **잰 적 없는 자리는 건드리지 않는다**(독립 검토 CA-02).
            // 1.0 은 「곱해도 그대로」다 — 보정하지 않는다는 뜻이다.
            //
            // [covers] 가 아니라 [supported] 를 쓴다. 둘은 다른 계약이다 —
            // 제조사 파일은 범위 밖에서 끝점을 늘여 쓰는 옛 규약을 그대로
            // 따르고([gainDbAt]), 잰 프로파일만 구간 밖을 비운다.
            if (!supported(hz)) 1.0 else 10.0.pow(-(gainDbAt(hz) - ref) / 10.0)
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
    fun bandCenterResponseDb(): DoubleArray = DoubleArray(ThirdOctave.BAND_COUNT) { b ->
        gainDbAt(ThirdOctave.exactCenter(b))
    }

    /**
     * 옛 이름. **쓰지 말 것** — 이름이 「밴드에 걸 이득」처럼 읽혀 다시
     * 보정 경로로 끌려 들어가기 쉽다(독립 검증 R05 가 바로 그 일이었다).
     *
     * 검증자의 재현 시험이 이 이름을 부르고 있어 남겨 둔다.
     */
    @Deprecated(
        "뜻이 분명한 이름을 쓴다. 이 값은 보정량이 아니라 중심 주파수의 응답이다.",
        ReplaceWith("bandCenterResponseDb()"),
    )
    fun bandGainsDb(): DoubleArray = bandCenterResponseDb()

    /** 곡선이 실제로 덮는 밴드인가. 밖이면 끝점 값을 늘여 쓴 것이라 근거가 약하다. */
    fun bandCovered(): BooleanArray = BooleanArray(ThirdOctave.BAND_COUNT) { b ->
        covers(ThirdOctave.exactCenter(b))
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
        fun of(
            raw: List<CurvePoint>,
            /**
             * 둘째 열을 무엇으로 읽을 것인가(독립 검토 R04).
             *
             * [CurveReading.Correction] 이면 **여기서 뒤집는다.** 그래야
             * 이 클래스의 나머지 전부가 손대지 않고 그대로 맞다 — 읽는
             * 법을 곳곳에서 다시 따지면 한 군데는 반드시 빠진다.
             */
            reading: CurveReading = CurveReading.Response,
            /**
             * 실제로 잰 구간들. 잰 프로파일에서 **무효 자리를 뺀 뒤**
             * 남은 구간을 넘긴다. 제조사 파일은 null 이다(독립 검토 CA-02).
             */
            supportHz: List<ClosedFloatingPointRange<Double>>? = null,
        ): Result<CalibrationCurve> {
            val clean = raw
                .filter { it.hz > 0.0 && it.hz.isFinite() && it.gainDb.isFinite() }
                .sortedBy { it.hz }
                .distinctBy { it.hz }
            return when {
                clean.size < 2 -> Result.failure(
                    IllegalArgumentException("쓸 수 있는 점이 ${clean.size}개뿐입니다. 최소 둘이 필요합니다."),
                )
                else -> Result.success(
                    CalibrationCurve(
                        points = clean.map { CurvePoint(it.hz, it.gainDb * reading.toResponseSign) },
                        rawPoints = clean,
                        reading = reading,
                        supportHz = supportHz,
                    ),
                )
            }
        }
    }
}
