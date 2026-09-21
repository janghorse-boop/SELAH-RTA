package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * 주파수 보정이 **칸마다** 걸리는지 본다(독립 검증 R05).
 *
 * 기준답을 구현에서 끌어오지 않는다. 「마이크가 이 주파수에서 이만큼
 * 더/덜 잡는다」는 전달함수를 정해 놓고, 그 마이크가 냈을 신호를 직접
 * 만든 뒤, 보정을 걸어 **원음의 에너지를 되찾는지** 본다. 원음 에너지는
 * 진폭에서 바로 나오는 값이라(A²/2) 구현과 무관하다.
 */
class CurveCorrectionTest {

    private val fs = 48000
    private val n = 4096
    private val binHz = fs.toDouble() / n

    private fun curve(vararg p: Pair<Double, Double>): CalibrationCurve =
        CalibrationCurve.of(p.map { CurvePoint(it.first, it.second) }).getOrThrow()

    /**
     * 순음 하나를 만든다. [gainDb] 는 마이크가 그 주파수에서 더 잡는 양이다.
     *
     * 주파수는 FFT 칸 한가운데에 놓는다. 칸 사이에 걸치면 Hann 창의 새어 나감이
     * 이웃 밴드로 번져, 보정 오차와 분해능 오차가 섞여 버린다.
     */
    private fun toneAtBin(bin: Int, amplitude: Double, gainDb: Double): DoubleArray {
        val hz = bin * binHz
        val scale = amplitude * 10.0.pow(gainDb / 20.0)
        return DoubleArray(n) { scale * sin(2 * PI * hz * it / fs) }
    }

    /** 보정을 걸어 밴드 전력을 낸다. */
    private fun bandPower(signal: DoubleArray, c: CalibrationCurve?): DoubleArray {
        val p = DoubleArray(n / 2 + 1)
        PowerSpectrum(n).compute(signal, 0, p)
        val out = DoubleArray(ThirdOctave.BAND_COUNT)
        BandAnalyzer(n, fs).toBandPower(p, out, c?.binCorrectionLinear(n, fs))
        return out
    }

    private fun bandOf(hz: Double): Int =
        (0 until ThirdOctave.BAND_COUNT).first {
            hz >= ThirdOctave.lowerEdge(it) && hz < ThirdOctave.upperEdge(it)
        }

    /**
     * 밴드마다 다른 응답을 가진 마이크의 신호에서 원음 에너지를 되찾는다.
     *
     * 250Hz 를 5dB 더 잡고 4kHz 를 8dB 덜 잡는 마이크다. 보정이 제대로
     * 걸리면 세 주파수 모두 진폭 0.5 의 에너지(0.125)로 돌아와야 한다.
     */
    @Test
    fun `마이크 응답을 되돌려 원음 에너지를 되찾는다`() {
        val mic = curve(20.0 to 0.0, 250.0 to 5.0, 1000.0 to 0.0, 4000.0 to -8.0, 20000.0 to 0.0)
        val expectedDb = 10 * log10(0.5 * 0.5 / 2)

        for (bin in listOf(22, 85, 341)) {
            val hz = bin * binHz
            val g = mic.gainDbAt(hz)
            val b = bandOf(hz)
            val got = 10 * log10(bandPower(toneAtBin(bin, 0.5, g), mic)[b])
            assertEquals("%.1fHz(응답 %+.2fdB) 보정 후".format(hz, g), expectedDb, got, 0.1)
        }
    }

    /**
     * 같은 밴드 안이라도 주파수마다 제 보정값을 받는다.
     *
     * 이것이 R05 의 핵심이다. 1kHz 밴드의 아래끝/위끝 응답이 −6/+6dB 인
     * 곡선에서, 밴드를 숫자 하나로 보정하면 밴드 안 어디에 있는 순음이든
     * 같은 값을 뺀다 — 아래끝 순음은 12dB 만큼 틀린다.
     */
    @Test
    fun `같은 밴드 안에서도 주파수마다 제 보정값을 받는다`() {
        val b = 17
        val mic = curve(
            ThirdOctave.lowerEdge(b) to -6.0,
            1000.0 to 0.0,
            ThirdOctave.upperEdge(b) to 6.0,
        )
        val expectedDb = 10 * log10(0.5 * 0.5 / 2)

        // 밴드 17 은 891~1122Hz. 칸 78(914Hz)·85(996Hz)·92(1078Hz) 가 그 안이다.
        for (bin in listOf(78, 85, 92)) {
            val hz = bin * binHz
            val got = 10 * log10(bandPower(toneAtBin(bin, 0.5, mic.gainDbAt(hz)), mic)[b])
            assertEquals("%.1fHz 순음".format(hz), expectedDb, got, 0.1)
        }
    }

    /**
     * 한 밴드에 순음이 둘이면 각자의 보정을 받은 에너지의 합이다.
     *
     * 밴드를 숫자 하나로 보정하면 둘 다에 같은 값을 걸어, 둘 중 어느 쪽도
     * 맞지 않는 값이 나온다.
     */
    @Test
    fun `한 밴드의 두 순음이 각자 보정을 받는다`() {
        val b = 17
        val mic = curve(
            ThirdOctave.lowerEdge(b) to -6.0,
            ThirdOctave.upperEdge(b) to 6.0,
        )
        val a1 = 0.5
        val a2 = 0.2
        val hz1 = 78 * binHz
        val hz2 = 92 * binHz
        val sig = DoubleArray(n)
        toneAtBin(78, a1, mic.gainDbAt(hz1)).forEachIndexed { i, v -> sig[i] += v }
        toneAtBin(92, a2, mic.gainDbAt(hz2)).forEachIndexed { i, v -> sig[i] += v }

        val expected = 10 * log10(a1 * a1 / 2 + a2 * a2 / 2)
        assertEquals(expected, 10 * log10(bandPower(sig, mic)[b]), 0.1)
    }

    /** 평탄한 곡선은 모든 밴드를 같은 양만큼 옮긴다. */
    @Test
    fun `평탄한 곡선은 모든 밴드를 같은 양만큼 옮긴다`() {
        val mic = curve(20.0 to -2.5, 20000.0 to -2.5)
        val sig = toneAtBin(85, 0.5, 0.0)
        val plain = bandPower(sig, null)
        val fixed = bandPower(sig, mic)
        for (b in 0 until ThirdOctave.BAND_COUNT) {
            if (plain[b] <= 0.0) continue
            assertEquals(2.5, 10 * log10(fixed[b]) - 10 * log10(plain[b]), 1e-9)
        }
    }

    /**
     * 두 순음 사이의 notch 는 두 순음을 건드리지 않는다.
     *
     * 예전 구현은 아래끝·중심·위끝 세 점을 평균했으므로, 중심의 깊은 notch
     * 하나가 밴드 전체를 들어 올렸다. 응답이 −20dB 인 자리에 에너지가
     * 없는데도 그렇다.
     */
    @Test
    fun `밴드 한가운데의 notch 가 좌우 순음을 건드리지 않는다`() {
        val b = 17
        val flat = curve(20.0 to 0.0, 20000.0 to 0.0)
        val notched = curve(
            20.0 to 0.0,
            960.0 to 0.0,
            996.09375 to -20.0,
            1030.0 to 0.0,
            20000.0 to 0.0,
        )
        // 칸 78(914Hz)·92(1078Hz) — notch 바깥이다.
        val sig = DoubleArray(n)
        toneAtBin(78, 0.5, 0.0).forEachIndexed { i, v -> sig[i] += v }
        toneAtBin(92, 0.5, 0.0).forEachIndexed { i, v -> sig[i] += v }

        val withFlat = 10 * log10(bandPower(sig, flat)[b])
        val withNotch = 10 * log10(bandPower(sig, notched)[b])
        assertEquals("notch 자리에 에너지가 없으므로 값이 같아야 한다", withFlat, withNotch, 0.02)
    }

    /** 칸 보정 계수는 그 칸 주파수의 응답을 되돌리는 값이다. */
    @Test
    fun `칸 보정 계수는 그 칸 주파수의 응답을 되돌린다`() {
        val mic = curve(20.0 to 3.0, 1000.0 to -4.0, 20000.0 to 6.0)
        val f = mic.binCorrectionLinear(n, fs)
        assertEquals(n / 2 + 1, f.size)
        for (k in listOf(1, 85, 341, 2048)) {
            val hz = k * binHz
            assertEquals(10.0.pow(-mic.gainDbAt(hz) / 10.0), f[k], 1e-12)
        }
        // 칸 0 은 DC 라 마이크 응답을 말할 자리가 아니다. 곡선의 첫 점을 쓴다.
        assertEquals(10.0.pow(-3.0 / 10.0), f[0], 1e-12)
    }

    /**
     * 밴드 보정값은 이제 **표시용**이다. 중심 주파수의 응답을 그대로 준다.
     *
     * 예전에는 이 값을 밴드 dB 에서 빼는 것이 보정 경로였고, 그래서
     * 1kHz 순음이 +2.4157dB 만큼 틀렸다(R05).
     */
    @Test
    fun `밴드 표시값은 중심 주파수의 응답이다`() {
        val steep = curve(891.0 to 0.0, 1000.0 to 0.0, 1123.0 to 12.0)
        assertEquals(steep.gainDbAt(ThirdOctave.exactCenter(17)), steep.bandCenterResponseDb()[17], 1e-12)
        assertTrue("중심이 0dB 이면 표시값도 0dB 이다", steep.bandCenterResponseDb()[17] < 0.01)
    }
}
