package kr.joa.selahrta.dsp

/**
 * 한 덩어리를 처리한 결과. 전부 dBFS 이며 **음압이 아니다** —
 * 보정을 거쳐야 dB SPL 이 된다.
 */
data class SplFrame(
    /** 시간가중을 거친 지금 레벨. 화면의 큰 숫자가 이것이다. */
    val currentDbfs: Dbfs,
    /** 짧은 구르는 Leq(기본 10초). */
    val leqShortDbfs: Dbfs?,
    /** 긴 구르는 Leq(기본 1분). */
    val leqLongDbfs: Dbfs?,
    /** 측정을 시작한 뒤 전체의 Leq. */
    val leqSessionDbfs: Dbfs?,
    /** 시간가중 레벨의 최대값(MAX). */
    val maxDbfs: Dbfs,
    /**
     * 시간가중 레벨의 **최소값**(MIN). 아직 자리를 안 잡았으면 null.
     *
     * **자리를 잡은 뒤부터 센다.** 시작 직후 τ 동안은 0 에서 올라오는
     * 중이라, 그 구간을 넣으면 MIN 이 늘 그 시작 구간으로 굳어 이후
     * 아무리 조용해도 바뀌지 않는다([BlockWeighting.min]).
     */
    val minDbfs: Dbfs?,
    /** 파형 절대값의 최대(PEAK). MAX 와 다른 지표다(명세 6장). */
    val peakDbfs: Dbfs,
    /** 그 피크가 풀스케일에 닿았는가. 닿았으면 그 값은 하한일 뿐이다. */
    val peakClipped: Boolean,
    /** 긴 Leq 의 창이 가득 찼는가. 차기 전 값은 이름보다 짧은 구간의 평균이다. */
    val leqLongFull: Boolean,
    /**
     * 시간가중이 자리를 잡았는가.
     *
     * 시작 직후 첫 τ 동안은 0 에서 올라오는 중이라 실제보다 낮다.
     * 그 값을 측정값이라 부르면 안 된다.
     */
    val settled: Boolean,
)

/**
 * 음압 측정 엔진(명세 6장).
 *
 * 파이프라인 순서가 중요하다:
 *
 *     PCM → 가중 필터 → 제곱 → 시간가중 / Leq / MAX / Peak
 *
 * **가중을 먼저 걸고 그 다음에 에너지를 센다.** 순서를 바꾸면 A 가중이
 * 저역을 깎기 전의 에너지가 섞여, 저음이 큰 찬양에서 실제보다 훨씬 높은
 * dBA 가 나온다.
 *
 * Peak 만은 예외로 **가중 전** 파형에서 잰다. 클리핑은 가중과 무관하게
 * 입력단에서 일어나는 일이라, 가중 뒤 파형을 보면 이미 깎인 뒤라 놓친다.
 */
class SplEngine(
    private val sampleRate: Int,
    weighting: Weighting,
    timeWeight: TimeWeight,
    leqShortMs: Long = 10_000,
    leqLongMs: Long = 60_000,
) {
    init {
        require(sampleRate > 0) { "샘플레이트가 0 이하다: $sampleRate" }
    }

    private val filter = weightingFilter(weighting, sampleRate)
    private val timeWeighting = ExponentialTimeWeighting(timeWeight, sampleRate)
    private val leqShort = RollingLeq(leqShortMs, sampleRate)
    private val leqLong = RollingLeq(leqLongMs, sampleRate)
    private val leqSession = EnergyAverage()

    private var maxMeanSquare = 0.0
    private var minMeanSquare = Double.POSITIVE_INFINITY
    private var peakAbs = 0.0
    private var peakClipped = false
    private var anyInput = false

    /** 가중된 신호를 담는 작업 버퍼. 덩어리마다 새로 만들지 않는다. */
    private var work = DoubleArray(0)

    /**
     * 덩어리 하나를 처리한다.
     *
     * [samples] 는 **가중 전** 원본이며 이 함수가 바꾸지 않는다 —
     * 녹음 쪽이 같은 버퍼를 쓰기 때문이다(명세 추가분 10장).
     */
    fun process(samples: FloatArray, frames: Int, offset: Int = 0): SplFrame {
        require(offset >= 0) { "offset=$offset 이 음수다" }
        require(frames >= 0 && offset + frames <= samples.size) {
            "offset=$offset 에서 ${frames} 개를 읽을 수 없다 (크기 ${samples.size})"
        }

        // Peak 는 가중 전에 잰다. 클리핑은 입력단의 사건이다.
        for (i in 0 until frames) {
            val a = kotlin.math.abs(samples[offset + i].toDouble())
            if (a > peakAbs) {
                peakAbs = a
                peakClipped = a >= CLIP_THRESHOLD
            }
        }

        if (work.size < frames) work = DoubleArray(frames)
        for (i in 0 until frames) work[i] = samples[offset + i].toDouble()

        filter.processInPlace(work, frames)

        if (frames > 0) anyInput = true
        // 덩어리 안의 **최대**를 본다. 마지막 값만 보면 덩어리 경계에 따라
        // MAX 가 달라진다 — 같은 PCM 을 1샘플씩 넣을 때와 1024개씩 넣을 때
        // 0.74dB 이 갈렸다(독립 검증 R06).
        val bw = timeWeighting.pushBlock(work, frames)
        if (frames > 0 && bw.max > maxMeanSquare) maxMeanSquare = bw.max
        if (bw.min.isFinite() && bw.min < minMeanSquare) minMeanSquare = bw.min

        leqShort.addBlock(work, frames)
        leqLong.addBlock(work, frames)
        // 세션 Leq 는 덩어리의 RMS 로 모은다 — 같은 에너지 평균이다.
        if (frames > 0) leqSession.add(rms(work, 0, frames), frames)

        return SplFrame(
            currentDbfs = timeWeighting.levelDbfs() ?: Dbfs(SILENCE_DBFS),
            leqShortDbfs = leqShort.leqDbfs(),
            leqLongDbfs = leqLong.leqDbfs(),
            leqSessionDbfs = leqSession.dbfs(),
            maxDbfs = amplitudeToDbfs(kotlin.math.sqrt(maxMeanSquare)),
            minDbfs = if (minMeanSquare.isFinite()) {
                amplitudeToDbfs(kotlin.math.sqrt(minMeanSquare))
            } else {
                null
            },
            peakDbfs = amplitudeToDbfs(peakAbs),
            peakClipped = peakClipped,
            leqLongFull = leqLong.isFull,
            settled = timeWeighting.settled,
        )
    }

    /** 아직 한 덩어리도 안 들어왔는가. 화면이 「값 없음」을 그리는 근거다. */
    val hasInput: Boolean get() = anyInput

    /** MAX·PEAK 만 다시 센다. 새 구간을 재기 시작할 때 쓴다. */
    fun resetPeaks() {
        minMeanSquare = Double.POSITIVE_INFINITY
        maxMeanSquare = 0.0
        peakAbs = 0.0
        peakClipped = false
    }

    fun reset() {
        filter.reset()
        timeWeighting.reset()
        leqShort.reset()
        leqLong.reset()
        leqSession.reset()
        resetPeaks()
        anyInput = false
    }
}
