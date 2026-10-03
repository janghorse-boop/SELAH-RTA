package kr.joa.selahrta.dsp

/**
 * 한 번 잰 결과. [transfer] 는 **지연을 찾았을 때만** 있다.
 *
 * @param windowEnd 두 창이 **함께 끝난 표본 번호**(그 [epoch] 안에서 0 부터 센
 *   개수). 장시간 기록은 이 번호로 「언제의 지연인가」를 적는다.
 * @param epoch [TransferEngine.reset] 마다 바뀐다. **epoch 가 다른 값끼리는
 *   표본 번호가 이어지지 않는다** — 한 직선으로 맞추면 안 된다.
 * @param timebase 이 측정의 **시간축이 검증됐는가**(6회차 R6-02). 엔진은 그것을
 *   스스로 알 수 없으므로 기본이 [TimebaseStatus.Unverified] 다. **소비자는
 *   [TimebaseStatus.Verified] 가 아닌 [transfer] 를 정상·검증된 측정 곡선으로 게시하지
 *   않는다** — 합성 ±20/−50 ppm 에서 지연은 찾았는데 고역이 −1.8/−12 dB 로 무너졌다.
 *   **진단용으로 보이려면** 같은 화면에 미검증·클럭 드리프트 미확인임을 밝히는 경고를
 *   결과와 함께 늘 둔다. 이렇게 보인 결과는 정상 측정도, 상대 비교의 정확도도 뜻하지
 *   않는다(TF 설계 8장 — 34회차 수용 문구. 처음엔 「정상 측정 곡선으로 게시하지 않는다」
 *   한 문장뿐이었다).
 */
data class TransferMeasurement(
    val delay: DelayResult,
    val transfer: TransferResult?,
    val windowEnd: Long,
    val epoch: Long,
    val timebase: TimebaseStatus,
)

/**
 * 기준과 측정의 **시간축이 검증됐는가**(6회차 R6-02).
 *
 * 두 스트림의 시계가 다르면 평균 창 안에서 지연이 밀려 Coherence 와 고역이
 * 무너지는데, 지연은 여전히 「찾았다」로 나온다. 엔진은 그것을 가리지 못한다.
 * 그래서 **모름을 모름으로 싣는다.** 검증·보정 방법은 아직 정하지 않았다 —
 * 이 타입은 그 결과를 실을 자리일 뿐이다.
 */
sealed interface TimebaseStatus {
    /** 기본. 검증되지 않았다 — 「드리프트 없음」이 아니다. */
    data object Unverified : TimebaseStatus

    /**
     * 부르는 쪽이 **근거를 적어** 검증됐다고 건 상태. 엔진이 확인한 것이 아니다.
     * @param evidence 무엇으로 확인했는가(기록 문서·측정 세션 등). 비울 수 없다.
     */
    data class Verified(val evidence: String) : TimebaseStatus {
        init { require(evidence.isNotBlank()) { "검증의 근거를 적어야 한다" } }
    }
}

/**
 * [TransferEngine.measureOutcome] 의 결과 — **안 나왔으면 왜 안 나왔는지**까지.
 *
 * 셋이 모두 `null` 이던 때는 「아직 시작 중」과 「운용 중에 한쪽이 멈춰
 * 자리를 잃었다」가 구별되지 않았다(7회차 §11 (c)). 장시간 기록에서
 * 뒤의 것을 앞의 것으로 읽으면 **보존 실패가 숨는다.**
 *
 * 까닭의 숫자는 **판단한 잠금 안에서** 함께 뜬 값이다 — 나중에
 * [TransferEngine.referenceCount] 따위를 따로 읽어 까닭을 짐작하지 않는다.
 */
sealed interface MeasureOutcome {
    /** 다른 측정이 아직 돈다. **고리를 뜨지 않았으므로** epoch 도 개수도 없다. */
    data object Busy : MeasureOutcome

    /** 첫 측정에 필요한 양([needed])이 **어느 한쪽이라도** 아직 안 쌓였다. */
    data class InsufficientData(
        val epoch: Long,
        val referenceCount: Long,
        val measurementCount: Long,
        val needed: Long,
    ) : MeasureOutcome

    /**
     * 한쪽이 **허용 여유보다 더 앞서** 같은 번호의 창이 이미 덮어써졌다.
     * 여유는 방향마다 다르다([referenceSlack]·[measurementSlack]).
     * **이것이 곧 표본 누락이라는 뜻은 아니다** — 한쪽이 멈췄거나 늦을 뿐일 수 있다.
     */
    data class RetentionExceeded(
        val epoch: Long,
        val referenceCount: Long,
        val measurementCount: Long,
        val referenceSlack: Long,
        val measurementSlack: Long,
    ) : MeasureOutcome

    data class Measured(val measurement: TransferMeasurement) : MeasureOutcome
}

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
 * ## 두 창은 **같은 표본 번호에서** 끝난다 (독립 검토 R6-01)
 *
 * 잠금 하나는 복사 도중의 쓰기를 막을 뿐, **두 고리의 꼬리가 같은 순간**
 * 이라는 보장은 아니다. 재생 콜백이 128표본 먼저 들어온 순간에 꼬리끼리
 * 견주면 실제 지연 300 이 **428** 로 나왔다(측정이 먼저면 172). 전달함수는
 * 같은 배열 안에서 맞추므로 0dB·Coherence 1 로 멀쩡해 보여, 그 숫자를
 * 의심할 길이 없었다. 장시간 기록에서는 **큐 진행차가 드리프트처럼 보인다.**
 *
 * 그래서 두 스트림을 **각자 0 부터 센 표본 번호**로 놓고, 덜 들어온 쪽의
 * 개수(`windowEnd`)에서 **둘 다** 끝나게 뜬다. 앞서간 쪽은 그만큼
 * 거슬러 뜬다.
 *
 * **이 번호가 기대는 전제**: 두 스트림의 표본 0 이 [reset] 뒤 **각자
 * 처음 들어온 표본**이고, 중간에 빠지거나 끼어든 표본이 없다는 것이다.
 * 따라서 [DelayResult.samples] 는 **표본 번호 좌표의 지연**이다 —
 * 두 스트림의 **시작 시각 차이가 섞여 있어** 그 자체로 음향 지연이 아니다.
 * 같은 epoch 안에서는 그 차이가 상수라 **시간에 따른 변화**는 볼 수 있다.
 * 누락·삽입은 이 클래스가 알아챌 수 없다 — 들여보내는 쪽이 세어야 한다.
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
 *
 * ## 지연 탐색 범위가 평균 창에 묶여 있다 — 떼어내야 한다
 *
 * 지금은 `analysisSize` 가 [span] 에서 나오므로 평균을 줄이면 탐색 범위가
 * 함께 줄어든다. 그래서 기본 `maxLagSamples` 로는 **평균 5회 미만을 쓸 수
 * 없다.** 명세 29장은 `None`·`Short` 를 요구하므로, **화면에 Averaging 을
 * 붙이기 전에 둘을 떼어놓아야 한다.**
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

    init {
        // span 은 지연 탐색 범위(analysisSize)의 바탕이다 — 그보다 긴
        // 지연은 애초에 찾을 길이 없으니, 잘못된 조합을 생성자에서 바로
        // 걸러낸다(평균을 줄이면 화면에서 바로 죽는 문제, 검토 ①).
        require(maxLagSamples < span) {
            "평균 ${averages}회 · FFT ${fftSize} 로는 분석 창이 ${span} 표본뿐이라 " +
                "최대 지연 ${maxLagSamples} 를 찾을 수 없다. " +
                "평균을 늘리거나(최소 ${(maxLagSamples - fftSize) / hop + 2}회) 최대 지연을 줄여야 한다."
        }
    }

    /**
     * 지연 보정까지 담을 만큼 넉넉히. **남는 몫이 한쪽이 앞서갈 수 있는
     * 여유**다 — 그보다 더 앞서면 같은 번호의 창이 이미 덮어써져 재지 않는다.
     *
     * **여유는 비대칭이다**(7회차 R7-01). 기준은 `span + maxLagSamples` 를
     * 들고 있어야 하므로 [referenceSlack] 만큼, 측정은 `span` 만 들면 되므로
     * [measurementSlack] 만큼 앞설 수 있다. 기본 설정(48kHz)에서 각각
     * 37,440(0.78초) · 61,440(1.28초)이다.
     */
    private val capacity = Integer.highestOneBit(span + maxLagSamples) * 2
    private val referenceSlack = (capacity - (span + maxLagSamples)).toLong()
    private val measurementSlack = (capacity - span).toLong()

    private val lock = Any()
    private val refRing = SampleRing(capacity)
    private val measRing = SampleRing(capacity)

    /** [measure] 재진입을 막는다 — 겹쳐 부르면 뒤의 것이 `null`([MeasureOutcome.Busy])을 받는다. */
    private val measuring = java.util.concurrent.atomic.AtomicBoolean(false)

    /** [reset] 마다 하나씩 는다. [lock] 안에서만 읽고 쓴다. */
    private var epoch = 0L

    /** 지금 epoch 의 시간축 상태. [lock] 안에서만. [reset] 하면 [TimebaseStatus.Unverified]. */
    private var timebase: TimebaseStatus = TimebaseStatus.Unverified

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
     * 창끼리 견주면 그 0 구간이 상관에 끼어든다. 두 창이 **같은 번호에서
     * 끝나므로** 기준·측정 **둘 다** [span] 뿐 아니라
     * **[refLong] 전체**(= `span + maxLagSamples`)만큼 쌓여 있어야 한다 —
     * 지연을 구한 뒤 그만큼 거슬러 가야 하는데, 그 자리를 고리에서 **다시
     * 읽지 않고** 이미 떠 놓은 [refLong] 안에서 자르기 때문이다(잠금을
     * 두 번 잡지 않으려는 것).
     *
     * **재진입 불가 — [measuring] 플래그가 코드로 막는다.** `refLong`·
     * `refWindow`·`measWindow`·[estimator]·[averager] 가 전부 인스턴스
     * 버퍼이고, 잠금([lock])은 고리([refRing]·[measRing]) 쓰기만 보호한다.
     * 두 스레드가 동시에 본문에 들어가면 이 버퍼들을 함께 덮어써 뒤섞인
     * 값을 낸다 — 그래서 **겹쳐 부르면 뒤의 것이 `null` 을 받는다.**
     * `null` 은 까닭을 가리지 않는다 — 가려야 하면 [measureOutcome] 을 쓴다.
     */
    fun measure(): TransferMeasurement? =
        (measureOutcome() as? MeasureOutcome.Measured)?.measurement

    /**
     * [measure] 와 같이 재되, **안 나왔으면 그 까닭**을 낸다. 장시간 기록은
     * 이쪽을 쓴다 — 「시작 중」과 「자리를 잃었다」를 갈라 적어야 한다.
     *
     * **reset 과 겹칠 때**(7회차 R7-02): 고리를 **reset 보다 먼저 뜬** 측정은
     * 옛 epoch 로 끝까지 나온다 — reset 은 진행 중인 계산을 취소하지 않는다.
     * 재진입 막이를 얻었어도 **아직 고리를 뜨기 전**이면 새 epoch 로 나온다.
     * 그러니 「reset 전에 부르기 시작했으면 옛 것」이라고 가정하지 말고,
     * 결과에 실린 [TransferMeasurement.epoch] 로 가른다.
     */
    fun measureOutcome(): MeasureOutcome {
        // **이미 재는 중이면 조용히 물러난다.** 예외를 던지지 않는 까닭은,
        // 화면이 주기적으로 부르는 자리라 한 번 늦는 것이 정상이기 때문이다.
        if (!measuring.compareAndSet(false, true)) return MeasureOutcome.Busy
        try {
            val end: Long
            val epochAt: Long
            val timebaseAt: TimebaseStatus
            synchronized(lock) {
                val refCount = refRing.written
                val measCount = measRing.written
                // 두 창이 함께 끝날 자리 — 덜 들어온 쪽의 개수.
                end = minOf(refCount, measCount)
                if (end < refLong.size) {
                    return MeasureOutcome.InsufficientData(epoch, refCount, measCount, refLong.size.toLong())
                }
                val refBack = refCount - end
                val measBack = measCount - end
                // 앞서간 쪽이 고리 여유를 넘으면 그 자리는 이미 덮어써졌다.
                if (refBack > referenceSlack || measBack > measurementSlack) {
                    return MeasureOutcome.RetentionExceeded(
                        epoch, refCount, measCount, referenceSlack, measurementSlack,
                    )
                }
                refRing.snapshot(refLong, refBack.toInt())
                measRing.snapshot(measWindow, measBack.toInt())
                epochAt = epoch
                timebaseAt = timebase
            }
            // 여기부터는 고리를 다시 읽지 않는다 — 이미 떠 놓은 배열 안에서만 자른다.

            // refLong 의 꼬리 span 개 = 지금 기준(lagBack = 0 에 해당).
            System.arraycopy(refLong, refLong.size - span, refWindow, 0, span)

            val delay = estimator.estimate(refWindow, measWindow)
            if (!delay.found) return MeasureOutcome.Measured(TransferMeasurement(delay, null, end, epochAt, timebaseAt))

            // **기준을 지연만큼 거슬러 뜬다** — 측정은 그만큼 늦게 들어왔다.
            val start = refLong.size - span - delay.samples
            // 지금은 도달하지 않는다 — estimator.estimate() 는 늘 0..maxLagSamples
            // 범위 안의 값만 내놓으므로(그 밖은 찾지 않는다), start 는 항상
            // 0 이상이다. refLong 을 그만큼 길게 뜬 것이 이 보장의 근거다.
            if (start < 0) return MeasureOutcome.Measured(TransferMeasurement(delay, null, end, epochAt, timebaseAt))

            averager.reset()
            for (k in 0 until averages) {
                val at = k * hop
                averager.addBlock(refLong, start + at, measWindow, at)
            }
            return MeasureOutcome.Measured(TransferMeasurement(delay, transferFunction(averager), end, epochAt, timebaseAt))
        } finally {
            measuring.set(false)
        }
    }

    fun delayMs(samples: Int): Double = samples * 1000.0 / sampleRate

    /**
     * 지금 epoch 의 시간축 상태를 건다. **그 epoch 에만** 걸린다 — [reset] 하면 다시
     * [TimebaseStatus.Unverified] 다. 이 상태는 고리를 뜰 때 함께 떠서 결과에 실린다.
     */
    fun setTimebase(status: TimebaseStatus) = synchronized(lock) { timebase = status }

    fun reset() = synchronized(lock) {
        refRing.clear(); measRing.clear(); epoch++
        timebase = TimebaseStatus.Unverified
    }
}
