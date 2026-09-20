package kr.joa.selahrta.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 실수 신호용 FFT(radix-2, 제자리 계산).
 *
 * 외부 라이브러리를 쓰지 않는다. 이 앱에서 FFT 는 측정의 핵심이라
 * 스케일링과 창 보정이 어떻게 되는지 바깥에서 볼 수 있어야 한다 —
 * 라이브러리마다 규약이 달라서, 어느 쪽인지 모른 채 쓰면 몇 dB 가
 * 조용히 어긋난다.
 *
 * 회전인자는 미리 계산해 둔다. 초당 수십 번 도는 자리에서 sin/cos 를
 * 매번 부르면 그것만으로 예산을 먹는다.
 */
class Fft(val size: Int) {

    init {
        require(size > 0 && (size and (size - 1)) == 0) {
            "FFT 길이는 2의 거듭제곱이어야 한다: $size"
        }
    }

    private val levels = Integer.numberOfTrailingZeros(size)
    private val cosTable = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(2.0 * PI * it / size) }

    /**
     * 제자리 복소 FFT. [re] 와 [im] 의 길이는 [size] 여야 한다.
     */
    fun transform(re: DoubleArray, im: DoubleArray) {
        require(re.size == size && im.size == size) { "배열 길이가 ${size} 가 아니다" }

        // 비트 역순 정렬
        for (i in 0 until size) {
            val j = Integer.reverse(i) ushr (32 - levels)
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var halfSize = 1
        while (halfSize < size) {
            val step = size / (halfSize * 2)
            var i = 0
            while (i < size) {
                var k = 0
                for (j in i until i + halfSize) {
                    val l = j + halfSize
                    val c = cosTable[k]
                    val s = sinTable[k]
                    val tre = re[l] * c + im[l] * s
                    val tim = -re[l] * s + im[l] * c
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    k += step
                }
                i += halfSize * 2
            }
            halfSize *= 2
        }
    }
}

/**
 * 창 함수(window).
 *
 * 창 없이 FFT 를 걸면 토막 낸 자리의 불연속이 넓은 대역으로 퍼진다
 * (스펙트럼 누설). Hann 창은 그 누설을 크게 줄이는 대신 신호의 에너지를
 * 깎는데, **그 깎인 만큼을 되돌려 놓지 않으면 레벨이 통째로 낮게 나온다.**
 */
object HannWindow {

    /** 길이 [n] 의 Hann 창. */
    fun create(n: Int): DoubleArray {
        require(n > 0) { "창 길이가 0 이하다: $n" }
        if (n == 1) return doubleArrayOf(1.0)
        return DoubleArray(n) { 0.5 * (1.0 - cos(2.0 * PI * it / (n - 1))) }
    }

    /**
     * 진폭 보정 계수. 창의 평균값의 역수다.
     *
     * 순음 하나의 **봉우리 높이**를 되돌릴 때 쓴다. Hann 창의 평균은 0.5 라
     * 계수는 약 2 다.
     */
    fun amplitudeCorrection(window: DoubleArray): Double {
        val mean = window.average()
        require(mean > 0.0) { "창의 평균이 0 이다" }
        return 1.0 / mean
    }

    /**
     * 에너지 보정 계수. 제곱평균의 역수의 제곱근이다.
     *
     * **밴드에 담긴 에너지를 합칠 때는 이쪽을 쓴다.** 진폭 보정을 쓰면
     * 잡음처럼 여러 칸에 퍼진 신호에서 레벨이 1.76dB 높게 나온다.
     * 순음에는 진폭 보정, 대역 에너지에는 에너지 보정 — 이 둘을 섞으면
     * 그 차이만큼 조용히 틀린다.
     */
    fun energyCorrection(window: DoubleArray): Double {
        val meanSquare = window.sumOf { it * it } / window.size
        require(meanSquare > 0.0) { "창의 제곱평균이 0 이다" }
        return 1.0 / kotlin.math.sqrt(meanSquare)
    }
}

/**
 * 실수 신호의 전력 스펙트럼을 낸다.
 *
 * 돌려주는 값은 **칸(bin)마다의 전력**이며, 합치면 원신호의 평균 제곱이
 * 된다(파스발 정리). 그래야 1/3 옥타브로 묶을 때 에너지가 보존된다.
 */
class PowerSpectrum(val fftSize: Int) {

    private val fft = Fft(fftSize)
    private val window = HannWindow.create(fftSize)
    private val energyScale = HannWindow.energyCorrection(window).let { it * it }
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)

    /** 칸 개수(0Hz ~ 나이퀴스트). */
    val binCount: Int get() = fftSize / 2 + 1

    /** 칸 하나의 폭(Hz). */
    fun binWidth(sampleRate: Int): Double = sampleRate.toDouble() / fftSize

    /** 칸 [i] 의 중심 주파수(Hz). */
    fun binFrequency(i: Int, sampleRate: Int): Double = i * binWidth(sampleRate)

    /**
     * [input] 의 앞 [fftSize] 개를 써서 전력 스펙트럼을 [out] 에 담는다.
     *
     * [out] 의 길이는 [binCount] 여야 한다. 덩어리마다 배열을 새로 만들지
     * 않으려고 호출한 쪽이 들고 있게 한다.
     */
    fun compute(input: DoubleArray, offset: Int, out: DoubleArray) {
        require(offset >= 0 && offset + fftSize <= input.size) {
            "offset=$offset 에서 ${fftSize} 개를 읽을 수 없다 (크기 ${input.size})"
        }
        require(out.size == binCount) { "out 길이가 ${binCount} 가 아니다: ${out.size}" }

        for (i in 0 until fftSize) {
            re[i] = input[offset + i] * window[i]
            im[i] = 0.0
        }
        fft.transform(re, im)

        // 한쪽 스펙트럼으로 접는다. 0Hz 와 나이퀴스트를 뺀 칸은 두 배로
        // 세야 양쪽 대칭분을 합친 것이 된다 — 안 그러면 전체 전력이 절반이 된다.
        val n = fftSize.toDouble()
        for (i in 0 until binCount) {
            val p = (re[i] * re[i] + im[i] * im[i]) / (n * n)
            val folded = if (i == 0 || (i == binCount - 1 && fftSize % 2 == 0)) p else 2.0 * p
            // 창이 깎은 에너지를 되돌린다.
            out[i] = folded * energyScale
        }
    }
}
