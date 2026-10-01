package kr.joa.selahrta.dsp

/**
 * 전달함수에 쓸 **자기·상호 스펙트럼을 블록마다 모은다.**
 *
 * ```
 * Sxx = <X* · X>      기준의 자기 스펙트럼
 * Syy = <Y* · Y>      측정의 자기 스펙트럼
 * Sxy = <X* · Y>      상호 스펙트럼 — **복소수로** 모은다
 * ```
 *
 * ## 왜 H 를 먼저 내지 않는가
 *
 * 블록마다 `Y/X` 를 내서 그 결과를 평균하면 **편향되고**, Coherence 를
 * 낼 재료가 사라진다. **스펙트럼을 먼저 모으고 그 뒤에 나눈다**(명세 8장).
 *
 * **상호 스펙트럼은 크기만 평균하면 안 된다** — 위상이 지워져 Coherence 가
 * 늘 1 에 가까워진다. 실수·허수를 따로 모은다.
 *
 * ## 창 보정 계수를 쓰지 않는다
 *
 * 기준과 측정에 **같은 Hann 창**이 걸리므로 `H1 = Sxy/Sxx` 에서 보정이
 * 서로 상쇄된다. 넣으면 곱했다 나누는 일을 하게 된다.
 */
class SpectralAverager(val fftSize: Int = 8192) {
    init {
        require(fftSize > 0 && Integer.bitCount(fftSize) == 1) {
            "fftSize 는 2의 거듭제곱이라야 한다: $fftSize"
        }
    }

    /** 0Hz 부터 나이키스트까지. */
    val bins: Int = fftSize / 2 + 1

    val sxx = DoubleArray(bins)
    val syy = DoubleArray(bins)
    val sxyRe = DoubleArray(bins)
    val sxyIm = DoubleArray(bins)

    /** 모은 블록 수. **Coherence 를 보일지 말지가 이 값에 달려 있다.** */
    var count: Int = 0
        private set

    private val fft = Fft(fftSize)
    private val window = HannWindow.create(fftSize)

    private val xRe = DoubleArray(fftSize)
    private val xIm = DoubleArray(fftSize)
    private val yRe = DoubleArray(fftSize)
    private val yIm = DoubleArray(fftSize)

    fun reset() {
        java.util.Arrays.fill(sxx, 0.0)
        java.util.Arrays.fill(syy, 0.0)
        java.util.Arrays.fill(sxyRe, 0.0)
        java.util.Arrays.fill(sxyIm, 0.0)
        count = 0
    }

    /**
     * 블록 한 쌍을 더한다. **이미 시간 정렬된 쌍**이라야 한다.
     *
     * @param refOffset [ref] 에서 읽기 시작할 자리.
     * @param measOffset [meas] 에서 읽기 시작할 자리.
     */
    fun addBlock(ref: DoubleArray, refOffset: Int, meas: DoubleArray, measOffset: Int) {
        require(refOffset >= 0 && refOffset + fftSize <= ref.size) {
            "ref 에서 $refOffset 부터 $fftSize 개를 읽을 수 없다 (크기 ${ref.size})"
        }
        require(measOffset >= 0 && measOffset + fftSize <= meas.size) {
            "meas 에서 $measOffset 부터 $fftSize 개를 읽을 수 없다 (크기 ${meas.size})"
        }

        for (i in 0 until fftSize) {
            xRe[i] = ref[refOffset + i] * window[i]
            xIm[i] = 0.0
            yRe[i] = meas[measOffset + i] * window[i]
            yIm[i] = 0.0
        }

        fft.transform(xRe, xIm)
        fft.transform(yRe, yIm)

        for (i in 0 until bins) {
            val xr = xRe[i]; val xi = xIm[i]
            val yr = yRe[i]; val yi = yIm[i]
            sxx[i] += xr * xr + xi * xi
            syy[i] += yr * yr + yi * yi
            // conj(X) · Y
            sxyRe[i] += xr * yr + xi * yi
            sxyIm[i] += xr * yi - xi * yr
        }
        count++
    }
}
