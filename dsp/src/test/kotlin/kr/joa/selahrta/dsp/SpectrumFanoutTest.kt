package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * **같은 스펙트럼을 둘 이상이 받는다.**
 *
 * 예전에는 받는 자리가 하나뿐이었다. 하울링 탐지기가 이미 거기 붙어
 * 있었으므로, 교정이 같은 스펙트럼을 받으려고 대입하면 **하울링 탐지가
 * 조용히 꺼진다** — 빌드도 시험도 통과하고 예배 중에만 드러난다.
 *
 * 그래서 「둘이 같이 받는가」와 「뗀 것이 정말 떨어지는가」를 못 박는다.
 */
class SpectrumFanoutTest {

    private fun tone(rate: Int, hz: Double, n: Int) = FloatArray(n) {
        (0.2 * sin(2 * PI * hz * it / rate)).toFloat()
    }

    @Test
    fun `둘이 같은 장을 받는다`() {
        val rta = RtaEngine(48_000, fftSize = 1024)
        var a = 0
        var b = 0
        rta.addSpectrumSink { a++ }
        rta.addSpectrumSink { b++ }

        val s = tone(48_000, 1_000.0, 48_000)
        rta.process(s, s.size)

        assertTrue("장이 나와야 한다", a > 0)
        assertEquals("둘이 같은 수를 받아야 한다", a, b)
    }

    @Test
    fun `떼면 더는 받지 않는다`() {
        val rta = RtaEngine(48_000, fftSize = 1024)
        var kept = 0
        var dropped = 0
        val keeper = SpectrumSink { kept++ }
        val leaver = SpectrumSink { dropped++ }
        rta.addSpectrumSink(keeper)
        rta.addSpectrumSink(leaver)

        val s = tone(48_000, 1_000.0, 24_000)
        rta.process(s, s.size)
        val atRemoval = dropped
        assertTrue("떼기 전에 받은 것이 있어야 한다", atRemoval > 0)

        rta.removeSpectrumSink(leaver)
        rta.process(s, s.size)

        assertEquals("뗀 뒤에도 받았다", atRemoval, dropped)
        assertTrue("남은 쪽은 계속 받아야 한다", kept > atRemoval)
        assertEquals(1, rta.spectrumSinkCount)
    }

    /** 같은 것을 두 번 붙이면 장 수가 두 배로 세어진다 — 막는다. */
    @Test
    fun `같은 것을 두 번 붙여도 한 번만 불린다`() {
        val rta = RtaEngine(48_000, fftSize = 1024)
        var n = 0
        val sink = SpectrumSink { n++ }
        rta.addSpectrumSink(sink)
        rta.addSpectrumSink(sink)
        assertEquals(1, rta.spectrumSinkCount)

        val s = tone(48_000, 1_000.0, 24_000)
        rta.process(s, s.size)

        val rta2 = RtaEngine(48_000, fftSize = 1024)
        var once = 0
        rta2.addSpectrumSink { once++ }
        rta2.process(s, s.size)
        assertEquals("두 번 붙인 쪽이 더 많이 받았다", once, n)
    }

    @Test
    fun `아무도 안 붙어도 그냥 돈다`() {
        val rta = RtaEngine(48_000, fftSize = 1024)
        val s = tone(48_000, 1_000.0, 24_000)
        rta.process(s, s.size)
        assertTrue(rta.frame() != null)
        assertEquals(0, rta.spectrumSinkCount)
    }

    /**
     * **보정 전 스펙트럼**을 준다. 하울링 탐지가 그 전제 위에 서 있고
     * (봉우리가 둘레보다 얼마나 솟았는가), 교정은 **칸마다 CAL 을 걸기
     * 위해** 보정 전 값이 필요하다.
     */
    @Test
    fun `곡선을 걸어도 받는 것은 보정 전이다`() {
        val s = tone(48_000, 1_000.0, 24_000)

        fun firstSpectrum(withCurve: Boolean): DoubleArray {
            val rta = RtaEngine(48_000, fftSize = 1024)
            var got: DoubleArray? = null
            rta.addSpectrumSink { p -> if (got == null) got = p.copyOf() }
            if (withCurve) {
                rta.setCurve(
                    CalibrationCurve.of(
                        listOf(CurvePoint(20.0, 12.0), CurvePoint(20_000.0, 12.0)),
                    ).getOrThrow(),
                )
            }
            rta.process(s, s.size)
            return got!!
        }

        val plain = firstSpectrum(withCurve = false)
        val curved = firstSpectrum(withCurve = true)
        assertNotSame(plain, curved)
        for (i in plain.indices) {
            assertEquals("칸 $i 에서 보정이 새어 들어왔다", plain[i], curved[i], 1e-12)
        }
    }
}
