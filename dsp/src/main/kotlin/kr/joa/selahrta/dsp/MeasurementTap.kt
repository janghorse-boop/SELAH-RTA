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
 * 섞이지 않게 **모으는 중에는 갈아탈 수 없다.** 섞이면 어느 장이 CAL 을
 * 지났는지 나중에 알 길이 없고, 값은 멀쩡해 보인다.
 *
 * ## 가득 차면 **멈춘다** (버리지 않는다)
 *
 * 오래된 장을 버리고 새 장을 받는 편이 흔한 선택이지만, 여기서는 틀렸다.
 * 잔여 DSP 검사([probeResidualDsp])가 보는 것이 **신호가 시작된 직후**의
 * 이득 변화라, 앞쪽을 버리면 AGC 가 자리잡는 구간이 통째로 사라진다 —
 * 그러고도 장 수는 넉넉하니 판정은 「깨끗함」으로 나온다.
 *
 * ## 스레드
 *
 * [onSpectrum] 은 **오디오 스레드**에서 불린다. 거기서는 배열 하나를
 * 만들어 큐에 넣는 일만 한다. 시작·멈춤·꺼내기는 주 스레드가 한다.
 */
class MeasurementTap(
    private val fftSize: Int,
    private val sampleRate: Int,
    /** 이만큼 모으면 멈춘다. 48kHz·FFT4096·50% 겹침이면 초당 23장쯤이다. */
    val maxFrames: Int = 1_200,
) : SpectrumSink {

    private val analyzer = BandAnalyzer(fftSize, sampleRate)
    private val bandPower = DoubleArray(ThirdOctave.BAND_COUNT)

    private val plain = ConcurrentLinkedQueue<DoubleArray>()
    private val calibrated = ConcurrentLinkedQueue<CalibratedReferenceSpectrum>()

    /** [ConcurrentLinkedQueue.size] 는 훑어 세므로 따로 센다. */
    private val counter = AtomicInteger()

    @Volatile
    private var calibrator: ReferenceCalibrator? = null

    @Volatile
    private var collecting = false

    /** 지금 모으는 중인가. */
    val running: Boolean get() = collecting

    /** 지금까지 모은 장 수. */
    val count: Int get() = counter.get()

    /** 더 못 받는 상태인가. 화면이 「다 모았습니다」로 쓴다. */
    val full: Boolean get() = counter.get() >= maxFrames

    /** 기준 장에 걸린 CAL 의 증거. 대상 측정 중이면 null. */
    val proof: ReferenceCalibrationProof? get() = calibrator?.proof

    /**
     * **대상** 마이크 측정을 시작한다. CAL 을 걸지 않는다.
     *
     * 앞서 모은 것은 버린다 — 이어 붙이면 다른 순간의 장들이 한 측정으로
     * 셈해진다.
     */
    fun startTarget() {
        check(!collecting) { "이미 모으는 중이다. 먼저 stop() 한다." }
        clear()
        calibrator = null
        collecting = true
    }

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
    ) {
        check(!collecting) { "이미 모으는 중이다. 먼저 stop() 한다." }
        clear()
        calibrator = ReferenceCalibrator(curve, analyzer, fftSize, sampleRate, calFileName, calSha256)
        collecting = true
    }

    /** 멈춘다. 모은 것은 [drainTarget]·[drainReference] 로 꺼낼 때까지 남는다. */
    fun stop() {
        collecting = false
    }

    /**
     * 대상 장들을 꺼내며 비운다.
     *
     * 기준으로 모으는 중이었다면 **비어 있다** — 섞이지 않는다.
     */
    fun drainTarget(): List<DoubleArray> = drain(plain)

    /** 기준 장들을 꺼내며 비운다. 증거가 장마다 달려 있다. */
    fun drainReference(): List<CalibratedReferenceSpectrum> = drain(calibrated)

    private fun <T> drain(q: ConcurrentLinkedQueue<T>): List<T> {
        val out = ArrayList<T>(q.size)
        while (true) out += q.poll() ?: break
        counter.set(plain.size + calibrated.size)
        return out
    }

    private fun clear() {
        plain.clear()
        calibrated.clear()
        counter.set(0)
    }

    /** **오디오 스레드**에서 불린다. */
    override fun onSpectrum(power: DoubleArray) {
        if (!collecting) return
        // 센 다음 넘치면 되돌린다. 재고 나서 멈추는 편이, 세기 전에 보고
        // 두 스레드가 같이 들어와 한 장 더 받는 것보다 낫다.
        if (counter.incrementAndGet() > maxFrames) {
            counter.decrementAndGet()
            collecting = false
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
