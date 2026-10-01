package kr.joa.selahrta.dsp

/** 한 번 잰 결과. [transfer] 는 **지연을 찾았을 때만** 있다. */
data class TransferMeasurement(val delay: DelayResult, val transfer: TransferResult?)

/**
 * 기준과 측정을 모아 두었다가 **지연 → 시간 정렬 → 전달함수** 순으로 잰다.
 *
 * ## 잠금이 **하나**인 까닭
 *
 * 두 고리는 **서로 다른 스레드**가 채운다 — 기준은 재생 스레드, 측정은
 * 캡처 스레드다. 창을 따로따로 뜨면 그 사이에 한쪽만 더 들어와 **없던
 * 시간차가 생긴다.** 5ms 만 어긋나도 음속으로 1.7m 다 — 우리가 재려는
 * 값과 같은 자릿수다.
 *
 * ## 순서를 뒤집지 않는다
 *
 * **지연을 못 찾으면 전달함수를 내지 않는다.** 시간이 안 맞은 채로 곱한
 * 스펙트럼은 그럴듯한 그림을 내놓지만 **Coherence 가 통째로 무너진** 값이다
 * (명세 3장: 시간축이 맞지 않은 상태에서 그럴듯한 그래프를 만들지 않는다).
 */
class TransferEngine(
    private val sampleRate: Int = 48_000,
    private val fftSize: Int = 8192,
    /** 50% 겹침으로 모을 블록 수. 명세 9장의 기본 목표가 16 이다. */
    private val averages: Int = 16,
    maxLagSamples: Int = 24_000,
) {
    init {
        require(sampleRate > 0) { "sampleRate 는 1 이상: $sampleRate" }
        require(averages > 0) { "averages 는 1 이상: $averages" }
    }

    private val hop = fftSize / 2

    /** 평균 [averages] 회를 50% 겹침으로 모으는 데 필요한 표본 수. */
    private val span = fftSize + (averages - 1) * hop

    /** 지연 보정까지 담을 만큼 넉넉히. */
    private val capacity = Integer.highestOneBit(span + maxLagSamples) * 2

    private val lock = Any()
    private val refRing = SampleRing(capacity)
    private val measRing = SampleRing(capacity)

    private val estimator = DelayEstimator(
        analysisSize = minOf(span, 32_768),
        maxLagSamples = maxLagSamples,
    )
    private val averager = SpectralAverager(fftSize)

    private val refWindow = DoubleArray(span)
    private val measWindow = DoubleArray(span)
    private val refAligned = DoubleArray(span)

    val referenceCount: Long get() = synchronized(lock) { refRing.written }
    val measurementCount: Long get() = synchronized(lock) { measRing.written }

    fun offerReference(src: FloatArray, offset: Int, count: Int) =
        synchronized(lock) { refRing.write(src, offset, count) }

    fun offerMeasurement(src: FloatArray, offset: Int, count: Int) =
        synchronized(lock) { measRing.write(src, offset, count) }

    /**
     * 지금 쌓인 것으로 한 번 잰다.
     *
     * **둘 다 창을 가득 채우지 못했으면 재지 않는다** — 앞이 0 으로 채워진
     * 창끼리 견주면 그 0 구간이 상관에 끼어든다.
     */
    fun measure(): TransferMeasurement? {
        val needed = (span + estimatorLag()).toLong()
        synchronized(lock) {
            if (refRing.written < needed || measRing.written < span) return null
            refRing.snapshot(refWindow)
            measRing.snapshot(measWindow)
        }

        val delay = estimator.estimate(refWindow, measWindow)
        if (!delay.found) return TransferMeasurement(delay, null)

        // **기준을 지연만큼 거슬러 뜬다** — 측정은 그만큼 늦게 들어왔다.
        synchronized(lock) {
            if (refRing.snapshot(refAligned, lagBack = delay.samples) < span) {
                return TransferMeasurement(delay, null)
            }
        }

        averager.reset()
        for (k in 0 until averages) {
            val at = k * hop
            averager.addBlock(refAligned, at, measWindow, at)
        }
        return TransferMeasurement(delay, transferFunction(averager))
    }

    fun delayMs(samples: Int): Double = samples * 1000.0 / sampleRate

    fun reset() = synchronized(lock) { refRing.clear(); measRing.clear() }

    /** 기준은 지연만큼 더 쌓여 있어야 거슬러 뜰 수 있다. */
    private fun estimatorLag(): Int = 0
}
