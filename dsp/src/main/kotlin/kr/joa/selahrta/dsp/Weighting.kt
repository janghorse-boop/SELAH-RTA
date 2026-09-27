package kr.joa.selahrta.dsp

/**
 * 주파수 가중(명세 6장, IEC 61672-1).
 *
 * **표를 보고 dB 를 더하는 방식이 아니다.** 가중은 주파수마다 다르게 작용하는
 * 필터라, 신호 전체에 숫자 하나를 더하는 것으로는 흉내 낼 수 없다.
 * 같은 85dB 이라도 저음이 많은 찬양과 말소리는 A 가중 뒤에 전혀 다른 값이
 * 된다 — 그 차이를 보자고 만드는 도구에서 offset 으로 때우면 아무 뜻이 없다.
 */
enum class Weighting(val labelKo: String, val unitSuffix: String) {
    /** 사람 귀의 감도를 흉내 낸다. 음압 규제와 청력 기준이 쓰는 가중이다. */
    A("A-weighting", "dB(A)"),

    /** 저역을 덜 깎는다. 큰 소리와 피크를 볼 때 쓴다. */
    C("C-weighting", "dB(C)"),

    /**
     * 깎지도 올리지도 않는다(평탄).
     *
     * **이름은 「Z-weighting」 하나다.** 「무가중」·「가중 없음」·「Flat」을
     * 이름 자리에 쓰지 않는다 — 같은 것을 네 가지로 부르던 탓에 「고정된
     * Z 와 무가중이 다른 것인가」라는 물음이 실제로 나왔다(2026-09-27).
     * 평탄하다는 사실은 고를 때 나오는 설명 줄에서 말한다.
     */
    Z("Z-weighting", "dB(Z)"),
}

/**
 * `LAeq 1분` 처럼 L 기호 꼴로 적는다(지시서 §10).
 *
 * **화면이 글자를 손으로 적지 않게 하려고 여기 둔다.** 손으로 적으면
 * 가중을 바꾼 뒤에도 옛 글자가 남아, 표기와 계산이 어긋난다.
 */
fun Weighting.leqLabel(windowKo: String): String = "L${name}eq $windowKo"

/** `LZpeak` 처럼 적는다. PEAK 은 창이 없어 뒤에 붙는 말이 없다. */
fun Weighting.peakLabel(): String = "L${name}peak"

/**
 * 가중의 **칸별 전력 이득**. RTA·Spectrum 이 칸마다 곱한다.
 *
 * ## 왜 칸마다인가
 *
 * 1/3 옥타브 대역은 ±11.6% 폭이다. 저역에서 A-weighting 의 기울기는
 * 옥타브당 12dB 에 가까워 **한 대역 안에서 4dB 가 달라진다.** 대역
 * 중심값 하나를 대역 전체에 걸면 대역 안의 소리 모양에 따라 1dB 가까이
 * 어긋난다. 칸마다 걸고 나서 묶으면 근사가 아니다.
 *
 * ## 음압과 같은 필터를 쓴다
 *
 * [weightingFilter] 의 응답을 그대로 읽는다. 규격의 아날로그 식으로
 * 따로 셈하면 더 정확하겠지만, **그러면 같은 소리를 두 화면이 다르게
 * 말한다** — 음압은 이 필터를 시간축으로 통과시켜 얻은 값이기 때문이다.
 * 정확함보다 두 값이 맞아떨어지는 쪽이 낫다.
 *
 * ## 전력 이득이다
 *
 * 돌려주는 값은 **전력에 곱하는 것**이라 진폭비의 제곱이다. 진폭비를
 * 그대로 쓰면 dB 가 절반으로 나온다 — 100Hz 에서 -19.1dB 이어야 할
 * A-weighting 이 -9.6dB 로 보인다.
 *
 * @return Z 이면 null — **곱할 것이 없다**는 뜻이다. 1 로 채운 배열을
 *   돌려주면 아무 일도 안 하는 곱셈을 칸마다 하게 된다.
 */
fun weightBinGain(w: Weighting, fftSize: Int, sampleRate: Int): DoubleArray? {
    require(fftSize > 0) { "FFT 길이가 0 이하다: $fftSize" }
    require(sampleRate > 0) { "샘플레이트가 0 이하다: $sampleRate" }
    if (w == Weighting.Z) return null

    val chain = weightingFilter(w, sampleRate)
    val binWidth = sampleRate.toDouble() / fftSize
    return DoubleArray(fftSize / 2 + 1) { bin ->
        // 진폭비를 제곱해 전력비로 만든다.
        val m = chain.magnitudeAt(bin * binWidth, sampleRate)
        // **성하지 않은 값은 0 으로 둔다.** 0Hz 근처에서 셈이 무너지면
        // NaN 이 곱셈으로 번져 그 대역이 통째로 빈다.
        if (m.isFinite() && m > 0.0) m * m else 0.0
    }
}

/**
 * IEC 61672-1 이 정한 극점 주파수(Hz).
 *
 * 규격이 아날로그 전달함수로 정의한 값이라 소수점까지 그대로 옮긴다.
 * 반올림하면 저역에서 수십분의 dB 씩 어긋난다.
 */
private const val F1 = 20.598997
private const val F2 = 107.65265
private const val F3 = 737.86223
private const val F4 = 12194.217

/** 가중을 1kHz 에서 0dB 로 맞춘다. 규격이 정한 기준점이다. */
private const val REFERENCE_HZ = 1000.0

/**
 * A 가중 필터를 만든다.
 *
 * 아날로그 전달함수(IEC 61672-1):
 *
 *     H(s) = K · s⁴ / ((s+ω1)² (s+ω2) (s+ω3) (s+ω4)²)
 *
 * 2차 구간 셋으로 나눈다:
 *   s²/(s+ω1)²  ·  s²/(s+ω4)²  ·  1/((s+ω2)(s+ω3))
 *
 * K 는 1kHz 에서 크기가 1 이 되도록 마지막에 맞춘다.
 */
fun aWeighting(sampleRate: Int): BiquadChain {
    require(sampleRate > 0) { "샘플레이트가 0 이하다: $sampleRate" }
    val w1 = 2.0 * Math.PI * F1
    val w2 = 2.0 * Math.PI * F2
    val w3 = 2.0 * Math.PI * F3
    val w4 = 2.0 * Math.PI * F4

    val chain = BiquadChain(
        listOf(
            // s² / (s+ω1)²
            Biquad.fromAnalog(1.0, 0.0, 0.0, 1.0, 2.0 * w1, w1 * w1, sampleRate),
            // s² / (s+ω4)²
            Biquad.fromAnalog(1.0, 0.0, 0.0, 1.0, 2.0 * w4, w4 * w4, sampleRate),
            // 1 / ((s+ω2)(s+ω3))
            Biquad.fromAnalog(0.0, 0.0, 1.0, 1.0, w2 + w3, w2 * w3, sampleRate),
        ),
    )
    return chain.normalisedAt(REFERENCE_HZ, sampleRate)
}

/**
 * C 가중 필터를 만든다.
 *
 *     H(s) = K · s² / ((s+ω1)² (s+ω4)²)
 *
 * A 가중과 달리 중역·저역을 거의 그대로 둔다. 200Hz~1.25kHz 에서 0dB 이다.
 */
fun cWeighting(sampleRate: Int): BiquadChain {
    require(sampleRate > 0) { "샘플레이트가 0 이하다: $sampleRate" }
    val w1 = 2.0 * Math.PI * F1
    val w4 = 2.0 * Math.PI * F4

    val chain = BiquadChain(
        listOf(
            // s² / (s+ω1)²
            Biquad.fromAnalog(1.0, 0.0, 0.0, 1.0, 2.0 * w1, w1 * w1, sampleRate),
            // 1 / (s+ω4)²
            Biquad.fromAnalog(0.0, 0.0, 1.0, 1.0, 2.0 * w4, w4 * w4, sampleRate),
        ),
    )
    return chain.normalisedAt(REFERENCE_HZ, sampleRate)
}

/** Z 가중은 아무것도 하지 않는다. 구간이 없는 사슬이 곧 통과다. */
fun zWeighting(): BiquadChain = BiquadChain(emptyList())

/** 고른 가중에 맞는 필터. */
fun weightingFilter(w: Weighting, sampleRate: Int): BiquadChain = when (w) {
    Weighting.A -> aWeighting(sampleRate)
    Weighting.C -> cWeighting(sampleRate)
    Weighting.Z -> zWeighting()
}

/**
 * IEC 61672-1 이 표로 정한 A·C 가중의 이론값(dB).
 *
 * 필터가 맞는지 견주는 기준이다. **구현에서 뽑은 값을 적어 두면 안 된다** —
 * 그러면 틀린 구현이 자기 자신과 맞는지 확인하는 시험이 된다.
 */
object WeightingReference {
    /** 1/3 옥타브 중심주파수별 A 가중(dB). */
    val A: Map<Double, Double> = mapOf(
        10.0 to -70.4, 12.5 to -63.4, 16.0 to -56.7, 20.0 to -50.5,
        25.0 to -44.7, 31.5 to -39.4, 40.0 to -34.6, 50.0 to -30.2,
        63.0 to -26.2, 80.0 to -22.5, 100.0 to -19.1, 125.0 to -16.1,
        160.0 to -13.4, 200.0 to -10.9, 250.0 to -8.6, 315.0 to -6.6,
        400.0 to -4.8, 500.0 to -3.2, 630.0 to -1.9, 800.0 to -0.8,
        1000.0 to 0.0, 1250.0 to 0.6, 1600.0 to 1.0, 2000.0 to 1.2,
        2500.0 to 1.3, 3150.0 to 1.2, 4000.0 to 1.0, 5000.0 to 0.5,
        6300.0 to -0.1, 8000.0 to -1.1, 10000.0 to -2.5, 12500.0 to -4.3,
        16000.0 to -6.6, 20000.0 to -9.3,
    )

    /** 1/3 옥타브 중심주파수별 C 가중(dB). */
    val C: Map<Double, Double> = mapOf(
        10.0 to -14.3, 12.5 to -11.2, 16.0 to -8.5, 20.0 to -6.2,
        25.0 to -4.4, 31.5 to -3.0, 40.0 to -2.0, 50.0 to -1.3,
        63.0 to -0.8, 80.0 to -0.5, 100.0 to -0.3, 125.0 to -0.2,
        160.0 to -0.1, 200.0 to 0.0, 250.0 to 0.0, 315.0 to 0.0,
        400.0 to 0.0, 500.0 to 0.0, 630.0 to 0.0, 800.0 to 0.0,
        1000.0 to 0.0, 1250.0 to 0.0, 1600.0 to -0.1, 2000.0 to -0.2,
        2500.0 to -0.3, 3150.0 to -0.5, 4000.0 to -0.8, 5000.0 to -1.3,
        6300.0 to -2.0, 8000.0 to -3.0, 10000.0 to -4.4, 12500.0 to -6.2,
        16000.0 to -8.5, 20000.0 to -11.2,
    )
}
