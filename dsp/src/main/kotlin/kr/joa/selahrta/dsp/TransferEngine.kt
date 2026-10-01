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
 * **그래서 기준은 잠금 한 번에, 지연 보정까지 담을 만큼(`span + maxLagSamples`)
 * 길게 한 번만 뜬다.** 지연을 구한 뒤에는 고리를 **다시 읽지 않고**, 이미
 * 떠 놓은 배열 안에서 자리만 골라 쓴다 — 잠금을 두 번 잡으면(1차로 두 창을
 * 뜨고, 지연을 구한 뒤 2차로 기준만 다시 뜨면) 그 사이에 재생 스레드가
 * 써 넣은 만큼 기준과 측정이 서로 다른 순간을 가리키게 된다. 시험이 전부
 * 단일 스레드라 이 길은 시험으로 걸리지 않고, 고리 용량을 넉넉히 잡아 둔
 * 탓에 측정 실패로도 안 걸린다 — **조용히 부정확한 값**으로 새어 나간다.
 *
 * **그 대가로 첫 측정까지 `maxLagSamples` 만큼(기본값에서 0.5초) 더
 * 기다린다.** 지연이 얼마로 나올지는 미리 모르므로, 최악(`maxLagSamples`)을
 * 미리 치르고 레이스를 아예 없애는 쪽을 골랐다 — 빨리 틀린 값을 내는 것보다
 * 늦게 맞는 값을 내는 쪽이 낫다.
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

    /**
     * 기준을 **잠금 한 번에, 지연 보정까지 담을 만큼 길게** 뜬다. 끝
     * (`refLong.size - 1`)이 「지금」이다. 지연 `D` 를 구한 뒤에는 이 배열의
     * 끝에서 `span + D` 만큼 들어간 자리(`start`, [measure] 참고)부터
     * `span` 개를 그대로 잘라 쓴다 — 고리를 다시 읽지 않는다.
     */
    private val refLong = DoubleArray(span + maxLagSamples)
    private val refWindow = DoubleArray(span)
    private val measWindow = DoubleArray(span)

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
     * 창끼리 견주면 그 0 구간이 상관에 끼어든다. 기준은 [span] 뿐 아니라
     * **[refLong] 전체**(= `span + maxLagSamples`)만큼 쌓여 있어야 한다 —
     * 지연을 구한 뒤 그만큼 거슬러 가야 하는데, 그 자리를 고리에서 **다시
     * 읽지 않고** 이미 떠 놓은 [refLong] 안에서 자르기 때문이다(잠금을
     * 두 번 잡지 않으려는 것).
     */
    fun measure(): TransferMeasurement? {
        synchronized(lock) {
            if (refRing.written < refLong.size || measRing.written < span) return null
            refRing.snapshot(refLong)
            measRing.snapshot(measWindow)
        }
        // 여기부터는 고리를 다시 읽지 않는다 — 이미 떠 놓은 배열 안에서만 자른다.

        // refLong 의 꼬리 span 개 = 지금 기준(lagBack = 0 에 해당).
        System.arraycopy(refLong, refLong.size - span, refWindow, 0, span)

        val delay = estimator.estimate(refWindow, measWindow)
        if (!delay.found) return TransferMeasurement(delay, null)

        // **기준을 지연만큼 거슬러 뜬다** — 측정은 그만큼 늦게 들어왔다.
        val start = refLong.size - span - delay.samples
        // 지금은 도달하지 않는다 — estimator.estimate() 는 늘 0..maxLagSamples
        // 범위 안의 값만 내놓으므로(그 밖은 찾지 않는다), start 는 항상
        // 0 이상이다. refLong 을 그만큼 길게 뜬 것이 이 보장의 근거다.
        if (start < 0) return TransferMeasurement(delay, null)

        averager.reset()
        for (k in 0 until averages) {
            val at = k * hop
            averager.addBlock(refLong, start + at, measWindow, at)
        }
        return TransferMeasurement(delay, transferFunction(averager))
    }

    fun delayMs(samples: Int): Double = samples * 1000.0 / sampleRate

    fun reset() = synchronized(lock) { refRing.clear(); measRing.clear() }
}
