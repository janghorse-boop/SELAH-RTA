package kr.joa.selahrta.dsp

import kotlin.math.exp

/**
 * 시간가중(IEC 61672-1, 명세 6장).
 *
 * 소음계의 바늘이 얼마나 빨리 따라가는가다. 같은 소리라도 Fast 는 짧은
 * 봉우리를 그대로 보여 주고 Slow 는 뭉개서 보여 준다.
 */
enum class TimeWeight(val labelKo: String, val tauSeconds: Double) {
    Fast("Fast (125ms)", 0.125),
    Slow("Slow (1s)", 1.0),
}

/**
 * 지수 시간가중 적분기.
 *
 * **에너지 영역에서 돈다.** 규격이 정의한 것은 「제곱한 신호를 1차 저역통과에
 * 통과시킨 것」이지 「dB 값을 평활한 것」이 아니다. dB 를 평활하면 짧고 큰
 * 소리가 실제보다 훨씬 작게 잡힌다 — 박수나 드럼처럼 순간적인 소리에서
 * 그 차이가 크게 벌어진다.
 *
 *     y[n] = y[n-1] + α·(x[n]² - y[n-1]),   α = 1 - exp(-T/τ)
 */
class ExponentialTimeWeighting(
    private val timeWeight: TimeWeight,
    sampleRate: Int,
) {
    init {
        require(sampleRate > 0) { "샘플레이트가 0 이하다: $sampleRate" }
    }

    private val alpha: Double = 1.0 - exp(-1.0 / (sampleRate * timeWeight.tauSeconds))

    /** 지금까지 쌓인 평균 제곱(에너지). */
    private var meanSquare = 0.0
    private var started = false

    /** 샘플 하나를 넣고 지금의 평균 제곱을 돌려준다. */
    fun push(sample: Double): Double {
        val sq = sample * sample
        if (!started) {
            // 0 에서 출발하면 첫 τ 동안 실제보다 낮게 나온다. 첫 샘플로
            // 자리를 잡아 두면 시작 직후의 값도 쓸 수 있다.
            meanSquare = sq
            started = true
        } else {
            meanSquare += alpha * (sq - meanSquare)
        }
        return meanSquare
    }

    /** 덩어리 하나를 넣고 마지막 값을 돌려준다. */
    fun pushBlock(buf: DoubleArray, frames: Int): Double {
        require(frames in 0..buf.size) { "frames=$frames 이 범위를 벗어난다" }
        for (i in 0 until frames) push(buf[i])
        return meanSquare
    }

    /** 지금 레벨(dBFS). 아직 아무것도 안 넣었으면 null. */
    fun levelDbfs(): Dbfs? = if (!started) null else amplitudeToDbfs(kotlin.math.sqrt(meanSquare))

    fun reset() {
        meanSquare = 0.0
        started = false
    }

    /** 시험과 진단용. */
    val label: String get() = timeWeight.labelKo
}

/**
 * 구르는 등가소음도(Leq).
 *
 * 창 안의 **에너지 평균**을 dB 로 옮긴 값이다(명세 6장). 10초·1분처럼
 * 정해진 길이로 굴린다.
 *
 * 샘플마다 창을 다시 더하지 않는다 — 1분 창이면 288만 샘플이라 초당 수십 번
 * 그러면 폰이 못 따라간다. 짧은 칸(bucket)마다 에너지를 모아 두고 칸 단위로
 * 굴린다. 칸 길이만큼의 시간 분해능을 잃지만, 1분 Leq 에서 100ms 는 문제가 아니다.
 */
class RollingLeq(
    windowMs: Long,
    private val sampleRate: Int,
    private val bucketMs: Long = 100L,
) {
    init {
        require(windowMs > 0) { "창 길이가 0 이하다: $windowMs" }
        require(bucketMs > 0 && bucketMs <= windowMs) { "칸 길이가 창보다 크거나 0 이하다" }
        require(sampleRate > 0) { "샘플레이트가 0 이하다" }
    }

    private val bucketFrames = (sampleRate * bucketMs / 1000).toInt().coerceAtLeast(1)
    private val bucketCount = (windowMs / bucketMs).toInt().coerceAtLeast(1)

    /** 칸마다의 제곱합과 프레임 수. 원형 버퍼로 굴린다. */
    private val sums = DoubleArray(bucketCount)
    private val counts = IntArray(bucketCount)
    private var head = 0

    private var currentSum = 0.0
    private var currentCount = 0

    /** 창이 한 번이라도 가득 찼는가. 차기 전의 값은 짧은 구간의 평균이다. */
    private var filledBuckets = 0

    fun add(sample: Double) {
        currentSum += sample * sample
        currentCount++
        if (currentCount >= bucketFrames) closeBucket()
    }

    fun addBlock(buf: DoubleArray, frames: Int) {
        require(frames in 0..buf.size) { "frames=$frames 이 범위를 벗어난다" }
        for (i in 0 until frames) add(buf[i])
    }

    private fun closeBucket() {
        sums[head] = currentSum
        counts[head] = currentCount
        head = (head + 1) % bucketCount
        if (filledBuckets < bucketCount) filledBuckets++
        currentSum = 0.0
        currentCount = 0
    }

    /**
     * 지금 창의 Leq(dBFS). 아직 아무것도 없으면 null.
     *
     * 아직 채워지지 않은 칸은 세지 않는다. 0 으로 세면 시작 직후에
     * 「아주 조용함」으로 보인다.
     */
    fun leqDbfs(): Dbfs? {
        var s = currentSum
        var n = currentCount.toLong()
        for (i in 0 until bucketCount) {
            s += sums[i]
            n += counts[i]
        }
        if (n == 0L) return null
        return amplitudeToDbfs(kotlin.math.sqrt(s / n))
    }

    /** 창이 가득 찼는가. 차기 전의 Leq 는 그 이름이 뜻하는 구간보다 짧다. */
    val isFull: Boolean get() = filledBuckets >= bucketCount

    fun reset() {
        sums.fill(0.0)
        counts.fill(0)
        head = 0
        currentSum = 0.0
        currentCount = 0
        filledBuckets = 0
    }
}
