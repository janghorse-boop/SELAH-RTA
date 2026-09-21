package kr.joa.selahrta.dsp

import kotlin.math.pow

/**
 * FFT 칸을 1/3 옥타브 31밴드로 묶는다(명세 7장).
 *
 * **칸의 dB 를 평균 내지 않는다.** 밴드 경계 안의 칸 **전력을 더한 뒤**
 * 한 번만 dB 로 옮긴다. dB 평균은 큰 성분이 섞인 밴드를 실제보다 낮게
 * 만든다 — 하울링처럼 한 칸이 튀는 경우에 특히 크게 틀린다.
 *
 * 경계에 걸친 칸은 **겹치는 폭만큼만** 나눠 넣는다. 칸 하나를 통째로
 * 어느 한쪽에 주면, 저역에서 칸 하나가 밴드 폭보다 넓기 때문에 밴드마다
 * 몇 dB 씩 들쭉날쭉해진다.
 */
/**
 * 얼마나 새는 것까지 「분해됐다」고 볼 것인가.
 *
 * 1dB 은 귀로는 겨우 알아챌까 말까 한 차이지만, 저역 균형을 보고 EQ 를
 * 만지는 자리에서는 방향을 바꿀 만한 양이다. 규격에서 온 값이 아니라
 * 우리가 정한 선이므로, 넘는 밴드는 화면이 그 사실을 적는다.
 */
const val LEAKAGE_TOLERANCE_DB = 1.0

/**
 * 밴드 안 어디에 순음을 놓고 재는가(로그 눈금 0~1, 0.5 가 중심).
 *
 * **밴드 가운데 절반만 쓴다.** 예전에는 「경계 근처 손실은 소리가 두 밴드에
 * 걸쳐 있어서」라고 적었는데 그것은 틀린 설명이었다 — 순음은 한 밴드에
 * 속하고, 갈라지는 것은 창 누설과 칸 분배 때문인 분석 오차다(독립 재검증
 * F05). 다만 그 오차는 **모든 밴드에 똑같이** 생기므로(1kHz 밴드도 경계에서
 * 2.36dB) 판정 기준으로 쓰면 31개가 전부 미분해가 된다. 그래서 가운데
 * 구간의 표본으로 판정하고, 표본이라는 사실을 화면이 적는다.
 */
private val TEST_POSITIONS = doubleArrayOf(0.25, 0.5, 0.75)

class BandAnalyzer(
    private val fftSize: Int,
    private val sampleRate: Int,
) {
    init {
        require(fftSize > 0) { "FFT 길이가 0 이하다" }
        require(sampleRate > 0) { "샘플레이트가 0 이하다" }
    }

    private val binWidth = sampleRate.toDouble() / fftSize
    private val binCount = fftSize / 2 + 1

    /**
     * 밴드마다 [칸 번호 → 무게] 표를 미리 만든다.
     *
     * 프레임마다 경계를 다시 계산하면 초당 수십 번 같은 나눗셈을 되풀이한다.
     * 무게는 「그 칸이 이 밴드에 얼마나 걸쳐 있는가」(0~1)다.
     */
    private val weights: Array<DoubleArray> = Array(ThirdOctave.BAND_COUNT) { band ->
        val lo = ThirdOctave.lowerEdge(band)
        val hi = ThirdOctave.upperEdge(band)
        DoubleArray(binCount) { bin ->
            // 칸 [bin] 이 덮는 주파수 구간
            val binLo = (bin - 0.5) * binWidth
            val binHi = (bin + 0.5) * binWidth
            val overlap = minOf(hi, binHi) - maxOf(lo, binLo)
            if (overlap <= 0.0) 0.0 else overlap / (binHi - binLo)
        }
    }

    /**
     * 밴드마다 순음의 에너지가 **얼마나 새어 나가는가**(dB).
     *
     * Hann 창을 쓴 FFT 는 순음을 한 칸에 담지 못하고 이웃으로 번지게 한다.
     * 밴드가 좁으면 번진 부분이 밴드 밖으로 나가 에너지가 실제보다 **낮게**
     * 잡힌다. 4096점·48kHz 에서 63Hz 중심 순음은 2.19dB, 80Hz 는 1.12dB 이
     * 빠진다(독립 검증 R08).
     *
     * 예전에는 「밴드가 칸 폭보다 넓은가」로 분해 여부를 판정했는데, 그것은
     * 창의 주파수 응답을 보지 않은 기준이라 위 두 밴드를 「충분히 분해됨」
     * 으로 표시했다. 이제 실제로 재서 판정한다.
     *
     * **이 값은 상한이 아니라 표본이다.** 밴드 가운데 절반 구간(로그 눈금
     * 0.25·0.5·0.75)에 순음을 놓고 잰 것 중 가장 나쁜 값이다. 경계에 바싹
     * 붙은 순음은 이보다 더 샌다 — 1kHz 밴드조차 t=0.01 에서 2.36dB 이
     * 빠진다(독립 재검증 F05).
     *
     * 왜 경계를 빼는가. 경계 누설은 **저역만의 현상이 아니라 전 대역에서
     * 생길 수 있다** — 유한한 창을 쓰는 한 주엽이 경계를 넘기 때문이다.
     * 실제로 1kHz 밴드도 t=0.01 에서 2.36dB 이 빠진다. (한 사례이며 31개
     * 밴드가 모두 같은 양이라는 뜻은 아니다.) 그런 자리까지 1dB 기준에
     * 넣으면 쓸 만한 밴드가 거의 남지 않아 화면이 아무것도 구별해 주지
     * 못한다. 그래서 「이 밴드가 제 대역을 담는가」를 가리는 데는 가운데
     * 구간을 쓰고, **그 사실을 화면에 적는다.**
     */
    val bandLossDb: DoubleArray = leakageFor(fftSize, sampleRate)

    /**
     * 그 밴드의 값을 그 대역의 에너지라고 말해도 되는가.
     *
     * [LEAKAGE_TOLERANCE_DB] 를 넘게 새면 아니다. 화면이 그 사실을 알린다.
     */
    val bandResolved: BooleanArray = BooleanArray(ThirdOctave.BAND_COUNT) { band ->
        bandLossDb[band] <= LEAKAGE_TOLERANCE_DB
    }

    /**
     * 밴드마다 순음을 넣어 실제로 얼마나 빠지는지 잰다.
     *
     * 식으로 어림하지 않고 재는 까닭은, 창·칸 나누기·경계 처리가 모두 섞인
     * 결과라 어림하면 어디서 틀렸는지 알 수 없기 때문이다.
     */
    private fun measureLeakage(): DoubleArray {
        val spectrum = PowerSpectrum(fftSize)
        val x = DoubleArray(fftSize)
        val p = DoubleArray(binCount)
        val band = DoubleArray(ThirdOctave.BAND_COUNT)
        val nyquist = sampleRate / 2.0

        return DoubleArray(ThirdOctave.BAND_COUNT) { b ->
            val lo = ThirdOctave.lowerEdge(b)
            val hi = ThirdOctave.upperEdge(b)
            if (hi > nyquist) {
                // 나이퀴스트를 넘는 밴드는 잴 수 있는 대상이 아니다.
                Double.POSITIVE_INFINITY
            } else {
                var worst = 0.0
                for (t in TEST_POSITIONS) {
                    // 로그 눈금으로 밴드 안을 나눈 자리. 1/3 옥타브는 로그 간격이다.
                    val f = lo * (hi / lo).pow(t)
                    for (i in x.indices) x[i] = kotlin.math.sin(2 * Math.PI * f * i / sampleRate)
                    spectrum.compute(x, 0, p)
                    toBandPower(p, band)
                    // 진폭 1 사인파의 평균 제곱은 1/2 이다.
                    val loss = if (band[b] <= 0.0) {
                        Double.POSITIVE_INFINITY
                    } else {
                        -10.0 * kotlin.math.log10(band[b] / 0.5)
                    }
                    if (loss > worst) worst = loss
                }
                worst
            }
        }
    }

    /** 이 설정에서 제대로 분해되는 가장 낮은 밴드 번호. */
    val lowestResolvedBand: Int = bandResolved.indexOfFirst { it }.coerceAtLeast(0)

    /**
     * 전력 스펙트럼을 밴드 전력으로 묶는다.
     *
     * [power] 는 [PowerSpectrum.compute] 가 낸 칸별 전력,
     * [out] 은 길이 31 의 밴드 전력 배열이다.
     *
     * [binCorrection] 은 마이크 보정 곡선을 칸마다 적용하는 선형 계수다
     * ([CalibrationCurve.binCorrectionLinear]). **밴드로 묶기 전에 곱한다** —
     * 묶은 뒤에 밴드 하나를 숫자 하나로 보정하면, 밴드 안에서 응답이 변하는
     * 구간에서 실제와 다른 값을 뺀다(독립 검증 R05).
     */
    fun toBandPower(power: DoubleArray, out: DoubleArray, binCorrection: DoubleArray? = null) {
        require(power.size == binCount) { "power 길이가 ${binCount} 가 아니다: ${power.size}" }
        require(out.size == ThirdOctave.BAND_COUNT) {
            "out 길이가 ${ThirdOctave.BAND_COUNT} 가 아니다: ${out.size}"
        }
        require(binCorrection == null || binCorrection.size == binCount) {
            "binCorrection 길이가 ${binCount} 가 아니다: ${binCorrection?.size}"
        }
        for (b in out.indices) {
            var sum = 0.0
            val w = weights[b]
            for (i in power.indices) {
                val wi = w[i]
                if (wi > 0.0) {
                    sum += power[i] * wi * (binCorrection?.get(i) ?: 1.0)
                }
            }
            out[b] = sum
        }
    }

    companion object {
        /**
         * (FFT 길이, 샘플레이트)마다 한 번만 재고 기억한다.
         *
         * 재는 데 FFT 를 93번 돌려 60ms 쯤 걸린다. 측정을 시작할 때마다
         * 그만큼 멈추면 「시작 버튼이 굼뜨다」로 나타난다. 조합은 기기마다
         * 한둘뿐이라 기억해 두면 두 번째부터는 공짜다.
         */
        private val leakageCache = java.util.concurrent.ConcurrentHashMap<Long, DoubleArray>()

        private fun BandAnalyzer.leakageFor(fftSize: Int, sampleRate: Int): DoubleArray =
            leakageCache.computeIfAbsent(fftSize.toLong() shl 32 or sampleRate.toLong()) {
                measureLeakage()
            }
    }

    /** 밴드 전력을 dBFS 로 옮긴다. 전력이므로 10·log10 이다. */
    fun toBandDbfs(bandPower: DoubleArray, out: DoubleArray) {
        require(bandPower.size == out.size) { "길이가 맞지 않는다" }
        for (i in bandPower.indices) {
            out[i] = if (bandPower[i] <= 0.0) {
                SILENCE_DBFS
            } else {
                (10.0 * kotlin.math.log10(bandPower[i])).coerceAtLeast(SILENCE_DBFS)
            }
        }
    }
}

/**
 * 밴드별 최대값을 붙들어 둔다(Peak Hold, 명세 14장).
 *
 * 값이 올라갈 때는 곧바로 따라가고, 내려올 때는 천천히 내린다.
 * 잠깐 스친 봉우리를 눈으로 잡으려고 쓰는 기능이라 **붙드는 것이 목적**이고,
 * 영원히 붙들면 화면이 굳으므로 천천히 풀어 준다.
 */
class PeakHold(
    bandCount: Int = ThirdOctave.BAND_COUNT,
    /** 한 프레임에 내려올 수 있는 최대치(dB). */
    private val fallPerFrameDb: Double = 0.4,
) {
    private val held = DoubleArray(bandCount) { SILENCE_DBFS }

    fun update(bandsDb: DoubleArray): DoubleArray {
        require(bandsDb.size == held.size) { "길이가 맞지 않는다" }
        for (i in held.indices) {
            held[i] = if (bandsDb[i] > held[i]) {
                bandsDb[i]
            } else {
                (held[i] - fallPerFrameDb).coerceAtLeast(bandsDb[i])
            }
        }
        return held
    }

    fun reset() = held.fill(SILENCE_DBFS)

    fun snapshot(): DoubleArray = held.copyOf()
}

/**
 * 밴드 레벨을 시간으로 고르게 한다(RTA smoothing, 명세 7장).
 *
 * **SPL 의 Fast/Slow 와는 별개 설정이다**(명세 7장이 못박았다). 막대가
 * 너무 떨면 어느 대역이 큰지 읽을 수 없고, 너무 느리면 하울링이 시작되는
 * 순간을 놓친다.
 *
 * 에너지 영역에서 고른다. dB 를 고르면 짧고 큰 봉우리가 뭉개진다.
 */
class BandSmoothing(
    bandCount: Int = ThirdOctave.BAND_COUNT,
    /** 0 이면 고르지 않음, 1 에 가까울수록 느리게 따라간다. */
    private val factor: Double = 0.5,
) {
    init {
        require(factor in 0.0..0.99) { "평활 계수가 범위를 벗어난다: $factor" }
    }

    private val state = DoubleArray(bandCount)
    private var started = false

    fun update(bandPower: DoubleArray): DoubleArray {
        require(bandPower.size == state.size) { "길이가 맞지 않는다" }
        if (!started) {
            bandPower.copyInto(state)
            started = true
        } else {
            for (i in state.indices) {
                state[i] = state[i] * factor + bandPower[i] * (1.0 - factor)
            }
        }
        return state
    }

    fun reset() {
        state.fill(0.0)
        started = false
    }
}
