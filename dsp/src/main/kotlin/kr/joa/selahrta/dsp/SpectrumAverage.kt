package kr.joa.selahrta.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 여러 장의 스펙트럼을 **하나로 모은다**(S23 개별 교정 지시서 3.3~3.4).
 *
 * > 핑크 노이즈는 충분한 평균과 동일 분석 창/FFT 설정을 사용한다.
 * > SNR 이 낮거나 **불안정한 구간을 배제**한다.
 *
 * ## dB 를 그냥 평균하면 틀린다
 *
 * 이것이 이 파일의 요점이다. dB 는 로그라 **산술 평균이 에너지 평균이
 * 아니다.** 0dB 와 20dB 를 dB 로 평균하면 10dB 가 나오지만, 실제 두
 * 소리를 같은 시간만큼 들은 에너지 평균은 **17dB** 다.
 *
 * 핑크 노이즈는 순간마다 출렁이므로 그 차이가 그대로 쌓인다 — dB 평균은
 * 실제보다 **낮게** 나오고, 출렁임이 큰 대역일수록 더 낮아진다. 저역이
 * 특히 그렇다. 그러면 「저역이 모자란 마이크」로 보여 없던 보정이 걸린다.
 *
 * 그래서 **전력으로 바꿔 평균하고 다시 dB 로 돌린다.**
 */
class BandAccumulator(val bandCount: Int) {

    init {
        require(bandCount > 0) { "밴드 수가 0 이하다: $bandCount" }
    }

    /** 전력 합. dB 가 아니다. */
    private val sum = DoubleArray(bandCount)

    /** 제곱 합. 흔들림을 재려고 둔다(dB 영역). */
    private val sumDb = DoubleArray(bandCount)
    private val sumDb2 = DoubleArray(bandCount)

    private val minDb = DoubleArray(bandCount) { Double.MAX_VALUE }
    private val maxDb = DoubleArray(bandCount) { -Double.MAX_VALUE }

    var count: Int = 0
        private set

    /** 한 장을 더한다. */
    fun add(bandsDb: DoubleArray) {
        require(bandsDb.size == bandCount) {
            "밴드 수가 다르다: ${bandsDb.size} != $bandCount"
        }
        for (i in 0 until bandCount) {
            val db = bandsDb[i]
            // **전력으로 바꿔 더한다.** dB 로 더하면 에너지 평균이 아니다.
            sum[i] += 10.0.pow(db / 10.0)
            sumDb[i] += db
            sumDb2[i] += db * db
            if (db < minDb[i]) minDb[i] = db
            if (db > maxDb[i]) maxDb[i] = db
        }
        count++
    }

    /** 에너지 평균(dB). 한 장도 없으면 null. */
    fun meanDb(): DoubleArray? {
        if (count == 0) return null
        return DoubleArray(bandCount) { 10.0 * log10(sum[it] / count) }
    }

    /**
     * 대역마다 얼마나 출렁였는가(dB 표준편차).
     *
     * **평균과 함께 봐야 뜻이 있다.** 값이 크면 그 대역은 잴 때마다
     * 달라진다는 뜻이고, 보정을 걸 근거가 약하다.
     */
    fun stdDevDb(): DoubleArray? {
        if (count < 2) return null
        return DoubleArray(bandCount) {
            val m = sumDb[it] / count
            val v = (sumDb2[it] / count - m * m).coerceAtLeast(0.0)
            sqrt(v)
        }
    }

    /** 대역마다 최댓값 − 최솟값(dB). 한 번 튄 것까지 잡는다. */
    fun spreadDb(): DoubleArray? {
        if (count == 0) return null
        return DoubleArray(bandCount) { maxDb[it] - minDb[it] }
    }

    fun reset() {
        sum.fill(0.0); sumDb.fill(0.0); sumDb2.fill(0.0)
        minDb.fill(Double.MAX_VALUE); maxDb.fill(-Double.MAX_VALUE)
        count = 0
    }
}

/**
 * **튄 장을 걸러 낸다**(지시서 3.4: 「여러 회 측정하여 불안정 구간을
 * 배제한다」).
 *
 * 문 닫히는 소리, 기침, 지나가는 사람 — 한 장만 크게 튀어도 전력 평균은
 * 그쪽으로 끌려간다(전력 평균이라 더 그렇다).
 *
 * 걸러 내는 기준은 **광대역 레벨**이다. 대역마다 따로 걸러 내면 같은
 * 순간의 스펙트럼이 조각조각 다른 장에서 오게 되어 모양이 깨진다.
 *
 * @param frames 장마다의 밴드 dB 배열.
 * @param maxDeviationDb 중앙값에서 이만큼 넘게 벗어난 장은 버린다.
 * @return 남긴 장들의 번호.
 */
fun keepStableFrames(
    frames: List<DoubleArray>,
    maxDeviationDb: Double = 3.0,
): List<Int> {
    if (frames.isEmpty()) return emptyList()
    require(maxDeviationDb > 0.0) { "허용 편차가 0 이하다: $maxDeviationDb" }

    // 장마다 광대역 레벨(전력 합)을 낸다.
    val levels = frames.map { f ->
        10.0 * log10(f.sumOf { 10.0.pow(it / 10.0) }.coerceAtLeast(1e-30))
    }
    // **평균이 아니라 중앙값을 쓴다.** 평균은 튄 장에 끌려가므로,
    // 튄 장을 걸러 내려는 기준 자체가 오염된다.
    val median = levels.sorted().let {
        if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2.0
    }
    return frames.indices.filter { abs(levels[it] - median) <= maxDeviationDb }
}

/**
 * 밴드 dB 를 [CurvePoint] 목록으로 옮긴다 — 보정 계산에 넣을 형태다.
 *
 * **호칭 중심이 아니라 정확한 중심주파수를 쓴다.** 호칭 125Hz 의 실제
 * 중심은 125.89Hz 다. 0.7% 차이지만 두 곡선을 뺄 때는 그 어긋남이
 * 그대로 보정값이 된다.
 */
fun bandsToCurvePoints(bandsDb: DoubleArray): List<CurvePoint> {
    require(bandsDb.size == ThirdOctave.BAND_COUNT) {
        "밴드 수가 다르다: ${bandsDb.size} != ${ThirdOctave.BAND_COUNT}"
    }
    return bandsDb.mapIndexed { i, db -> CurvePoint(ThirdOctave.exactCenter(i), db) }
}
