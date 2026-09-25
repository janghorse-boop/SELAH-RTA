package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.log2

/**
 * 연속 스펙트럼 화면(검토안 3장의 Spectrum)이 무엇을 보여야 하는가.
 *
 * RTA 는 밴드로 묶어 「어느 대역이 큰가」를 보고, Spectrum 은 칸 그대로
 * 「그 안에서 정확히 몇 Hz 인가」를 본다. 그래서 여기서 지켜야 할 것은
 * 둘이다 — **봉우리를 뭉개지 않을 것**, 그리고 **RTA 와 같은 숫자를
 * 말할 것**.
 */
class SpectrumDisplayTest {

    private val fs = 48_000
    private val n = 4096
    private val binHz = fs.toDouble() / n

    private fun spectrumOf(hz: Double, amp: Double = 0.5): DoubleArray {
        val d = SignalGenerator.sine(hz, fs, n, amp)
        val p = DoubleArray(n / 2 + 1)
        PowerSpectrum(n).compute(d, 0, p)
        return p
    }

    private fun axis(columns: Int = 256) = SpectrumAxis(n / 2 + 1, fs, columns)

    @Test
    fun `칸이 20Hz 에서 20kHz 를 로그 등간격으로 덮는다`() {
        val a = axis()
        assertEquals("첫 칸", 20.0, a.hz.first(), 0.01)
        assertEquals("끝 칸", 20_000.0, a.hz.last(), 0.5)
        // 로그 등간격이면 이웃끼리의 **비**가 일정하다.
        val step = a.hz[1] / a.hz[0]
        for (i in 1 until a.hz.size - 1) {
            assertEquals("칸 ${i} 의 간격", step, a.hz[i + 1] / a.hz[i], 1e-9)
        }
    }

    @Test
    fun `순음이 제 칸에서 가장 크다`() {
        val a = axis()
        val out = DoubleArray(a.columns)
        for (hz in listOf(125.0, 1_000.0, 4_000.0, 12_500.0)) {
            a.reduce(spectrumOf(hz), out)
            val loudest = out.indices.maxByOrNull { out[it] }!!
            // 그 칸의 가운데 주파수가 순음과 같은 칸 폭 안에 있어야 한다.
            val ratio = abs(log2(a.hz[loudest] / hz))
            assertTrue(
                "${hz}Hz → ${"%.0f".format(a.hz[loudest])}Hz 로 갔다",
                ratio <= a.octavesPerColumn,
            )
        }
    }

    /**
     * **칸을 평균내지 않는다.**
     *
     * 고역에서는 화면 칸 하나에 FFT 칸이 스무 개 넘게 들어간다. 평균을
     * 내면 좁은 봉우리가 둘레에 묻혀 20dB 넘게 낮아진다 — 이 화면을 만드는
     * 까닭이 「그 안에서 정확히 어디가 솟았나」이므로, 그러면 화면이 제
     * 일을 못한다.
     */
    @Test
    fun `칸 값은 그 구간에서 가장 큰 FFT 칸이다`() {
        val a = axis()
        val out = DoubleArray(a.columns)
        val p = spectrumOf(12_500.0)
        a.reduce(p, out)

        val col = out.indices.maxByOrNull { out[it] }!!
        // 그 칸이 덮는 FFT 칸들 중 최대와 **정확히** 같아야 한다.
        var expected = 0.0
        for (b in p.indices) {
            val f = b * binHz
            if (f >= a.lowEdge(col) && f < a.highEdge(col) && p[b] > expected) expected = p[b]
        }
        assertTrue("그 칸에 FFT 칸이 있다", expected > 0.0)
        assertEquals(expected, out[col], expected * 1e-12)

        // 평균이었다면 이만큼 묻혔을 것이다 — 그 차이가 이 시험의 요점이다.
        var sum = 0.0
        var cnt = 0
        for (b in p.indices) {
            val f = b * binHz
            if (f >= a.lowEdge(col) && f < a.highEdge(col)) { sum += p[b]; cnt++ }
        }
        val buriedDb = 10.0 * log10(expected / (sum / cnt))
        assertTrue("평균이면 ${"%.1f".format(buriedDb)}dB 묻힌다", buriedDb > 10.0)
    }

    @Test
    fun `가장 큰 봉우리의 주파수를 칸 사이까지 되찾는다`() {
        // 검토안 4장의 예시 주파수. 칸(11.72Hz) 한가운데가 아니다 —
        // 231.5번 칸이라, 칸 번호만 쓰면 2,707Hz 로 7Hz 어긋난다.
        for (hz in listOf(1_000.0, 2_713.0, 97.0)) {
            val top = topSpectrumPeak(spectrumOf(hz), fs, n)
            assertNotNull("${hz}Hz 에서 봉우리를 못 찾았다", top)
            assertEquals("${hz}Hz", hz, top!!.hz, 1.0)
        }
    }

    @Test
    fun `아무 소리도 없으면 봉우리가 없다`() {
        assertNull(topSpectrumPeak(DoubleArray(n / 2 + 1), fs, n))
    }

    @Test
    fun `분해능을 칸 폭으로 알려준다`() {
        assertEquals(binHz, axis().binHz, 1e-9)
    }

    /**
     * 화면 가로 자리를 주파수에서 바로 낸다. 피드백 표식·최고 봉우리처럼
     * **칸에 맞아떨어지지 않는** 주파수를 찍는 데 쓴다.
     */
    @Test
    fun `주파수를 화면 가로 자리로 옮긴다`() {
        val a = axis()
        assertEquals(0.0, a.position(20.0), 1e-9)
        assertEquals(1.0, a.position(20_000.0), 1e-9)
        // 로그축이므로 20Hz 와 20kHz 의 기하평균이 한가운데다.
        assertEquals(0.5, a.position(632.455), 1e-4)
        // 밖으로 나간 것은 밖으로 둔다 — 잘라 버리면 끝 칸에 몰려 붙는다.
        assertTrue(a.position(10.0) < 0.0)
        assertTrue(a.position(30_000.0) > 1.0)
    }
}
