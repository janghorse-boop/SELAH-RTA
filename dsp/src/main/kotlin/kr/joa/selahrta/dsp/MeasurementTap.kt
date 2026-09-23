package kr.joa.selahrta.dsp

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 교정 측정이 **살아 있는 스펙트럼을 받아 가는 자리**.
 *
 * [RtaEngine] 에 붙여 FFT 한 장마다 밴드 레벨을 모은다. RTA 와 하울링
 * 탐지가 보는 바로 그 스펙트럼이라 FFT 를 두 번 돌리지 않고, 시각도
 * 어긋나지 않는다.
 *
 * ## 기준과 대상은 다른 것을 모은다
 *
 * - **기준**(EMM-6)은 CAL 을 걸어야 하고, 건 증거를 달고 나가야 한다
 *   ([CalibratedReferenceSpectrum]). [startReference] 로 시작한다.
 * - **대상**(내장 마이크)은 걸 CAL 이 없다. 그걸 재려고 하는 참이다.
 *   [startTarget] 으로 시작한다.
 *
 * ## 한 회차가 통째로 하나다 (독립 검토 R05)
 *
 * 처음에는 `collecting`·`calibrator`·큐를 **따로** 두고 각각 volatile 로
 * 막았다. 검토자가 짚은 대로 **그것으로는 모자랐다.** 필드 하나하나가
 * 안전해도 회차를 바꾸는 일 전체가 원자적이지는 않다:
 *
 * > 오디오 콜백이 `collecting=true` 를 확인 → 주 스레드가 멈추고 꺼내고
 * > 새 회차를 시작 → 늦게 이어진 콜백이 **새 calibrator 를 읽는다.**
 *
 * 그러면 대상 마이크로 받은 장이 **기준 회차에 CAL 증거를 달고** 들어간다.
 * 값은 멀쩡해 보이고, 증거를 꾸밀 수 없게 만들어 둔 설계가 그 자리에서
 * 무너진다.
 *
 * 그래서 회차를 [Round] 라는 **바뀌지 않는 덩어리**로 묶었다. 오디오
 * 스레드는 그 참조를 **딱 한 번** 읽고 끝까지 그것에만 쓴다. 늦게 이어진
 * 콜백은 이미 꺼내 간 옛 회차에 쓰게 되므로 — **마지막 한 장을 잃을 수는
 * 있어도 새 회차에 섞이지는 않는다.** 둘 중 훨씬 나쁜 쪽을 없앤다.
 *
 * 한 장도 잃지 않으려면 시작·종료를 **오디오 스레드에서** 치러야 한다
 * (앱은 `postToCapture` 로 그렇게 한다). 여기서는 그것 없이도 섞이지만은
 * 않도록 해 둔다.
 *
 * ## 가득 차면 **멈춘다** (버리지 않는다)
 *
 * 오래된 장을 버리고 새 장을 받는 편이 흔한 선택이지만, 여기서는 틀렸다.
 * 잔여 DSP 검사([probeResidualDsp])가 보는 것이 **신호가 시작된 직후**의
 * 이득 변화라, 앞쪽을 버리면 AGC 가 자리잡는 구간이 통째로 사라진다 —
 * 그러고도 장 수는 넉넉하니 판정은 「깨끗함」으로 나온다.
 */
class MeasurementTap(
    private val fftSize: Int,
    private val sampleRate: Int,
    /** 이만큼 모으면 멈춘다. 48kHz·FFT4096·50% 겹침이면 초당 23장쯤이다. */
    val maxFrames: Int = 1_200,
) : SpectrumSink {

    /**
     * 한 회차. **만든 뒤로는 바뀌지 않는다** — 걸 CAL 도, 담는 큐도.
     *
     * 오디오 스레드가 이 덩어리 하나만 붙들면 회차가 섞일 길이 없다.
     */
    internal class Round(
        val generation: Long,
        val calibrator: ReferenceCalibrator?,
        private val maxFrames: Int,
        private val analyzer: BandAnalyzer,
    ) {
        /** 이 회차 전용 작업 버퍼. 회차마다 따로라 서로 밟지 않는다. */
        private val bandPower = DoubleArray(ThirdOctave.BAND_COUNT)

        val plain = ConcurrentLinkedQueue<DoubleArray>()
        val calibrated = ConcurrentLinkedQueue<CalibratedReferenceSpectrum>()

        /** [ConcurrentLinkedQueue.size] 는 훑어 세므로 따로 센다. */
        val counter = AtomicInteger()

        @Volatile
        var open: Boolean = true
            private set

        fun close() {
            open = false
        }

        val full: Boolean get() = counter.get() >= maxFrames

        /** **오디오 스레드**에서 불린다. */
        fun accept(power: DoubleArray) {
            if (!open) return
            // 센 다음 넘치면 되돌린다. 재고 나서 멈추는 편이, 세기 전에
            // 보고 두 스레드가 같이 들어와 한 장 더 받는 것보다 낫다.
            if (counter.incrementAndGet() > maxFrames) {
                counter.decrementAndGet()
                close()
                return
            }
            val cal = calibrator
            if (cal != null) {
                calibrated += cal.spectrum(power)
            } else {
                analyzer.toBandPower(power, bandPower, null)
                val db = DoubleArray(ThirdOctave.BAND_COUNT)
                analyzer.toBandDbfs(bandPower, db)
                plain += db
            }
        }
    }

    private val analyzer = BandAnalyzer(fftSize, sampleRate)

    /** 지금 받고 있는 회차. 오디오 스레드가 **한 번만** 읽는다. */
    @Volatile
    private var current: Round? = null

    /** 멈춘 회차. 꺼내기는 여기서 한다. */
    @Volatile
    private var stopped: Round? = null

    private var generation = 0L

    /** 꺼낼 것이 있는 회차. 멈췄으면 그것, 아니면 받는 중인 것. */
    private val readable: Round? get() = stopped ?: current

    /** 지금 받는 중인가. 가득 차서 스스로 닫혔으면 거짓이다. */
    val running: Boolean get() = current?.open == true

    /** 지금까지 모은 장 수. */
    val count: Int get() = readable?.counter?.get() ?: 0

    /** 더 못 받는 상태인가. 화면이 「다 모았습니다」로 쓴다. */
    val full: Boolean get() = readable?.full == true

    /** 기준 장에 걸린 CAL 의 증거. 대상 측정 중이면 null. */
    val proof: ReferenceCalibrationProof? get() = readable?.calibrator?.proof

    /** 지금 회차 번호. 화면·진단이 「몇 번째 측정인가」를 볼 때 쓴다. */
    val currentGeneration: Long get() = readable?.generation ?: 0L

    /**
     * **대상** 마이크 측정을 시작한다. CAL 을 걸지 않는다.
     *
     * 앞서 모은 것은 버린다 — 이어 붙이면 다른 순간의 장들이 한 측정으로
     * 셈해진다.
     */
    fun startTarget() = begin(null)

    /**
     * **기준** 마이크 측정을 시작한다. 장마다 CAL 이 칸 단위로 걸린다.
     *
     * @param curve EMM-6 개별 CAL.
     * @param calSha256 파일 내용의 해시. 이름이 같아도 내용이 다르면 다른 기준이다.
     */
    fun startReference(
        curve: CalibrationCurve,
        calFileName: String? = null,
        calSha256: String? = null,
    ) = begin(ReferenceCalibrator(curve, analyzer, fftSize, sampleRate, calFileName, calSha256))

    private fun begin(cal: ReferenceCalibrator?) {
        check(!running) { "이미 모으는 중이다. 먼저 stop() 한다." }
        // 옛 회차를 닫는다. 안 꺼낸 장이 있었다면 여기서 사라진다 —
        // 「다시 시작하면 앞서 모은 것을 버린다」가 그 뜻이다.
        current?.close()
        stopped = null
        generation++
        current = Round(generation, cal, maxFrames, analyzer)
    }

    /** 멈춘다. 모은 것은 [drainTarget]·[drainReference] 로 꺼낼 때까지 남는다. */
    fun stop() {
        val c = current ?: return
        c.close()
        stopped = c
        current = null
    }

    /**
     * 대상 장들을 꺼내며 비운다.
     *
     * 기준으로 모으는 중이었다면 **비어 있다** — 섞이지 않는다.
     */
    fun drainTarget(): List<DoubleArray> {
        val r = readable ?: return emptyList()
        return drain(r.plain, r)
    }

    /** 기준 장들을 꺼내며 비운다. 증거가 장마다 달려 있다. */
    fun drainReference(): List<CalibratedReferenceSpectrum> {
        val r = readable ?: return emptyList()
        return drain(r.calibrated, r)
    }

    private fun <T> drain(q: ConcurrentLinkedQueue<T>, round: Round): List<T> {
        val out = ArrayList<T>(q.size)
        while (true) out += q.poll() ?: break
        round.counter.set(round.plain.size + round.calibrated.size)
        return out
    }

    /** **오디오 스레드**에서 불린다. */
    override fun onSpectrum(power: DoubleArray) {
        // **딱 한 번 읽는다.** 이 뒤에 주 스레드가 회차를 바꿔도 이 장은
        // 처음 잡은 회차에서 끝난다.
        val round = current ?: return
        round.accept(power)
    }

    /**
     * 시험 전용: **회차를 잡는 순간과 쓰는 순간을 갈라** 준다.
     *
     * 경쟁은 그 사이에서 난다. 스레드를 돌려 흔드는 시험은 대개 「어쩌다
     * 통과」하므로, 여기서는 그 순서를 **강제로** 만들어 본다(독립 검토
     * R05 의 검증 제안).
     */
    internal fun beginFrameForTest(): Round? = current
}
