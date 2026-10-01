package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockDriftTest {

    private val sec = 1_000_000_000L

    /** 60초 동안 [rate] Hz 로 흐른 것처럼 짝을 만든다. */
    private fun pair(rate: Double, seconds: Long = 60): Pair<ClockSample, ClockSample> =
        ClockSample(0L, 0L) to
            ClockSample((rate * seconds).toLong(), seconds * sec)

    @Test
    fun `두 시계가 같으면 0 ppm 이다`() {
        val (o1, o2) = pair(48_000.0)
        val (i1, i2) = pair(48_000.0)
        val r = estimateDrift(o1, o2, i1, i2)
        assertTrue(r is DriftResult.Ppm)
        assertEquals(0.0, (r as DriftResult.Ppm).value, 0.5)
    }

    @Test
    fun `출력이 빠르면 양수 ppm 이다`() {
        val (o1, o2) = pair(48_000.0 * (1 + 20e-6))   // +20ppm
        val (i1, i2) = pair(48_000.0)
        val r = estimateDrift(o1, o2, i1, i2) as DriftResult.Ppm
        assertEquals(20.0, r.value, 1.0)
    }

    @Test
    fun `출력이 느리면 음수 ppm 이다`() {
        val (o1, o2) = pair(48_000.0 * (1 - 50e-6))   // -50ppm
        val (i1, i2) = pair(48_000.0)
        val r = estimateDrift(o1, o2, i1, i2) as DriftResult.Ppm
        assertEquals(-50.0, r.value, 1.0)
    }

    /** **못 재는 경우를 0 으로 돌려주지 않는다.** */
    @Test
    fun `시간이 안 흘렀으면 못 쟀다고 말한다`() {
        val s = ClockSample(0L, 0L)
        assertEquals(DriftResult.Unavailable, estimateDrift(s, s, s, s))
    }

    @Test
    fun `프레임이 안 늘었으면 못 쟀다고 말한다`() {
        val o1 = ClockSample(0L, 0L)
        val o2 = ClockSample(0L, 60 * sec)       // 시간은 흘렀는데 프레임이 그대로
        val (i1, i2) = pair(48_000.0)
        assertEquals(DriftResult.Unavailable, estimateDrift(o1, o2, i1, i2))
    }

    @Test
    fun `시간이 거꾸로 가면 못 쟀다고 말한다`() {
        val o1 = ClockSample(0L, 60 * sec)
        val o2 = ClockSample(48_000L * 60, 0L)
        val (i1, i2) = pair(48_000.0)
        assertEquals(DriftResult.Unavailable, estimateDrift(o1, o2, i1, i2))
    }
}
