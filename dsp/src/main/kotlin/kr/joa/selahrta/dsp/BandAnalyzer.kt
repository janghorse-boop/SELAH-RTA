package kr.joa.selahrta.dsp

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
     * 각 밴드에 FFT 칸이 하나라도 걸리는가.
     *
     * 저역의 좁은 밴드는 칸 하나보다 좁아서 아무 칸도 온전히 담기지 않는다.
     * 그런 밴드의 값은 이웃에서 새어 온 것이라 **실제 그 대역의 에너지가
     * 아니다.** 화면이 그 사실을 알릴 수 있도록 내보낸다.
     */
    val bandResolved: BooleanArray = BooleanArray(ThirdOctave.BAND_COUNT) { band ->
        val width = ThirdOctave.upperEdge(band) - ThirdOctave.lowerEdge(band)
        // 밴드가 칸 폭보다 넓어야 그 밴드를 「분해했다」고 말할 수 있다.
        width >= binWidth && ThirdOctave.upperEdge(band) <= sampleRate / 2.0
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
