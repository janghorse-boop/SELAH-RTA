package kr.joa.selahrta.dsp

/**
 * 31밴드 RTA 한 프레임의 결과.
 *
 * 값은 전부 dBFS 이며 **음압이 아니다** — 보정을 거쳐야 dB SPL 이 된다.
 */
class RtaFrame(
    /** 밴드별 레벨(dBFS). 길이 31. */
    val bandsDbfs: DoubleArray,
    /** Peak Hold 로 붙들어 둔 값(dBFS). 길이 31. */
    val holdDbfs: DoubleArray,
    /** 밴드마다 FFT 로 실제 분해되는가. 안 되는 밴드의 값은 이웃으로 샌다. */
    val resolved: BooleanArray,
    /**
     * 밴드마다 순음 에너지가 얼마나 새어 나가는가(dB).
     *
     * 「분해 안 됨」을 참·거짓으로만 말하면 얼마나 못 믿을지를 알 수 없다.
     * 2dB 과 7dB 은 다른 이야기다(독립 검증 R08).
     */
    val lossDb: DoubleArray,
    /**
     * 이 프레임을 계산할 때 걸려 있던 보정 곡선의 세대.
     *
     * 화면이 자기 상태의 세대와 견줘, 다르면 「보정 적용됨」이라고 적지
     * 않는다. 숫자와 이름표가 어긋나는 구간을 없앤다(독립 재검증 F06).
     */
    val curveGeneration: Long,
)

/**
 * 실시간 주파수 분석기(명세 7장).
 *
 * 들어오는 PCM 을 **겹쳐 가며** FFT 한다. 겹치지 않으면 창이 깎아 버린
 * 경계 근처의 소리를 놓쳐, 짧게 스치는 소리가 프레임 사이로 사라진다.
 *
 * 화면 갱신(10~20 FPS)보다 자주 FFT 를 돌리고 그 결과를 평활한다 —
 * FFT 자체를 화면 속도로 늦추면 하울링이 시작되는 순간을 놓친다.
 */
/**
 * FFT 한 장이 나올 때마다 부른다.
 *
 * 하울링 탐지는 **같은 스펙트럼**을 본다. 따로 FFT 를 돌리면 같은 일을
 * 두 번 하는 셈이고, 두 결과의 시각이 어긋나 「RTA 에는 보이는데 후보에는
 * 없는」 상태가 생긴다.
 */
fun interface SpectrumSink {
    /** [power] 는 **빌려주는 배열**이다. 붙들어 두려면 복사해야 한다. */
    fun onSpectrum(power: DoubleArray)
}

class RtaEngine(
    /** 실제로 도는 샘플레이트. 교정 통로가 같은 값으로 묶여야 한다. */
    val sampleRateHz: Int,
    /** 명세 7장의 출발점은 4096. 길수록 저역이 잘 보이고 반응은 느려진다. */
    val fftSize: Int = 4096,
    /** 겹치는 비율. 0.5 면 절반씩 겹친다. */
    overlap: Double = 0.5,
    smoothingFactor: Double = 0.5,
    peakHoldFallDb: Double = 0.4,
) {
    init {
        require(sampleRateHz > 0) { "샘플레이트가 0 이하다" }
        require(overlap in 0.0..0.9) { "겹침 비율이 범위를 벗어난다: $overlap" }
    }

    private val hop = (fftSize * (1.0 - overlap)).toInt().coerceAtLeast(1)
    private val spectrum = PowerSpectrum(fftSize)
    private val bands = BandAnalyzer(fftSize, sampleRateHz)
    private val smoothing = BandSmoothing(ThirdOctave.BAND_COUNT, smoothingFactor)
    private val peakHold = PeakHold(ThirdOctave.BAND_COUNT, peakHoldFallDb)

    /** 들어온 샘플을 모아 두는 원형 버퍼. */
    private val ring = DoubleArray(fftSize)
    private var writePos = 0
    private var sinceLastFft = 0
    private var filled = 0

    /** 작업용 버퍼들. 프레임마다 새로 만들지 않는다. */
    private val linear = DoubleArray(fftSize)
    private val power = DoubleArray(spectrum.binCount)
    private val bandPower = DoubleArray(ThirdOctave.BAND_COUNT)
    private val bandDb = DoubleArray(ThirdOctave.BAND_COUNT)

    private var latest: RtaFrame? = null

    /**
     * FFT 한 장마다 받아 갈 곳들. 하울링 탐지기와 교정 측정이 여기 붙는다.
     *
     * ## 왜 하나가 아닌가
     *
     * 예전에는 `var spectrumSink` 하나였고 하울링 탐지기가 이미 차지하고
     * 있었다. 교정이 **같은 스펙트럼**을 받으려고 그 자리에 대입하면
     * **하울링 탐지가 조용히 꺼진다** — 빌드도 시험도 통과하고, 화면에서
     * 후보가 안 뜨는 것으로만 드러난다. 그것도 예배 중에.
     *
     * 그래서 자리를 여럿으로 열고, **대입이 아니라 더하기**로 바꿨다.
     *
     * ## 오디오 스레드에서 읽는다
     *
     * 붙이고 떼는 것은 주 스레드, 부르는 것은 오디오 스레드다. 목록을
     * 고칠 때마다 **새 배열을 만들어 갈아 끼운다** — 오디오 스레드는 참조
     * 하나만 읽으므로 잠금도, 한창 고쳐지는 중인 목록을 보는 일도 없다.
     */
    @Volatile
    private var sinks: Array<SpectrumSink> = emptyArray()

    private val sinkLock = Any()

    /** 같은 것을 두 번 붙이지 않는다 — 두 번 불리면 장 수가 두 배로 세어진다. */
    fun addSpectrumSink(sink: SpectrumSink) = synchronized(sinkLock) {
        if (sinks.none { it === sink }) sinks = sinks + sink
    }

    fun removeSpectrumSink(sink: SpectrumSink) = synchronized(sinkLock) {
        sinks = sinks.filter { it !== sink }.toTypedArray()
    }

    /** 붙어 있는 수. 시험이 「정말 떨어졌는가」를 보는 데 쓴다. */
    val spectrumSinkCount: Int get() = sinks.size

    /**
     * 마이크 보정 곡선을 칸마다 걸 계수. 없으면 보정하지 않는다.
     *
     * 주 스레드가 갈아 끼우고 오디오 스레드가 읽는다. 다 만든 배열의
     * **참조만** 바꾸므로 `@Volatile` 로 그 참조가 보이게만 하면 된다 —
     * 반쯤 채워진 배열을 읽는 일은 없다.
     */
    @Volatile
    private var binCorrection: DoubleArray? = null

    /**
     * 지금 결과가 **몇 번째 곡선으로** 계산된 것인가.
     *
     * 화면은 「보정이 걸렸다」를 자기 상태에서 읽는데, 엔진이 아직 새 곡선으로
     * 한 번도 계산하지 않았으면 그 말이 숫자와 맞지 않는다. 프레임에 세대를
     * 실어 보내 화면이 견줄 수 있게 한다(독립 재검증 F06).
     */
    @Volatile
    private var curveGeneration = 0L

    /**
     * 마이크 보정 곡선을 건다. null 이면 보정 없이 돌린다.
     *
     * 곡선이 바뀌면 평활·Peak Hold 와 **마지막 결과까지** 비운다. 마지막
     * 결과를 남겨 두면 다음 FFT 가 돌 때까지 옛 곡선으로 계산한 프레임을
     * 새 곡선의 이름표와 함께 내보낸다 — 화면이 「+10dB 보정 적용됨」이라고
     * 적는 동안 숫자는 보정 전 값이었다(독립 재검증 F06).
     */
    fun setCurve(curve: CalibrationCurve?) {
        binCorrection = curve?.binCorrectionLinear(fftSize, sampleRateHz)
        curveGeneration++
        smoothing.reset()
        peakHold.reset()
        latest = null
    }

    /**
     * 덩어리를 넣는다. FFT 를 돌릴 만큼 쌓이면 결과가 갱신된다.
     *
     * [samples] 는 **가중 전 원본**이다. RTA 는 주파수 균형을 보는 것이라
     * A 가중을 걸면 저역이 깎인 그림이 되어 「어느 대역이 큰지」를 잘못 읽게
     * 된다. 음압(dBA)과 RTA 는 다른 질문에 답한다.
     */
    fun process(samples: FloatArray, frames: Int) {
        require(frames in 0..samples.size) { "frames=$frames 이 범위를 벗어난다" }
        for (i in 0 until frames) {
            ring[writePos] = samples[i].toDouble()
            writePos = (writePos + 1) % fftSize
            if (filled < fftSize) filled++
            sinceLastFft++
            if (filled >= fftSize && sinceLastFft >= hop) {
                sinceLastFft = 0
                runFft()
            }
        }
    }

    private fun runFft() {
        // 원형 버퍼를 시간 순서대로 펴서 옮긴다. 순서가 틀리면 파형이
        // 가운데서 끊긴 것처럼 되어 없던 고역이 잔뜩 생긴다.
        for (i in 0 until fftSize) {
            linear[i] = ring[(writePos + i) % fftSize]
        }
        spectrum.compute(linear, 0, power)

        // 하울링 탐지는 **보정 전 스펙트럼**을 본다. 봉우리가 둘레보다
        // 얼마나 솟았는지를 보는 것이라, 마이크 응답을 되돌리는 보정은
        // 솟은 정도를 거의 바꾸지 않으면서 계산만 늘린다.
        // 배열 참조를 **한 번만** 읽는다. 읽는 사이에 갈아 끼워져도 이 장은
        // 일관된 목록으로 끝난다.
        val current = sinks
        for (i in current.indices) current[i].onSpectrum(power)

        // 보정은 **밴드로 묶기 전에** 칸마다 건다(독립 검증 R05).
        bands.toBandPower(power, bandPower, binCorrection)
        val smoothed = smoothing.update(bandPower)
        bands.toBandDbfs(smoothed, bandDb)
        val held = peakHold.update(bandDb)
        latest = RtaFrame(
            bandDb.copyOf(),
            held.copyOf(),
            bands.bandResolved,
            bands.bandLossDb,
            curveGeneration,
        )
    }

    /** 가장 최근 결과. 아직 FFT 를 한 번도 못 돌렸으면 null. */
    fun frame(): RtaFrame? = latest

    /** 지금 걸려 있는 곡선의 세대. 화면이 프레임의 것과 견준다. */
    val currentCurveGeneration: Long get() = curveGeneration

    /** 분해되지 않는 가장 낮은 밴드 위의 첫 밴드. 화면이 그 아래를 흐리게 그린다. */
    val lowestResolvedBand: Int get() = bands.lowestResolvedBand

    fun resetHold() = peakHold.reset()

    fun reset() {
        ring.fill(0.0)
        writePos = 0
        sinceLastFft = 0
        filled = 0
        smoothing.reset()
        peakHold.reset()
        latest = null
    }
}
