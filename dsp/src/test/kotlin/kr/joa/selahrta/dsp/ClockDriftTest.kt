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

    // 위 세 시험은 출력 쪽만 깨뜨리고 입력 쪽은 pair(48_000.0) 로 멀쩡히 둔다.
    // estimateDrift 는 출력 쪽을 먼저 평가해 거기서 Unavailable 을 돌려주므로,
    // 입력 쪽 rateOrNull 이 null 을 돌려주는 분기는 저 시험만으로는 한 번도
    // 실행되지 않는다. 아래 세 시험은 **출력은 멀쩡하고 입력만 못 재는** 대칭
    // 경우를 추가해 그 분기를 실제로 지나가게 한다.

    @Test
    fun `입력 쪽 시간이 안 흘렀으면 못 쟀다고 말한다`() {
        val (o1, o2) = pair(48_000.0)
        val s = ClockSample(0L, 0L)
        assertEquals(DriftResult.Unavailable, estimateDrift(o1, o2, s, s))
    }

    @Test
    fun `입력 쪽 프레임이 안 늘었으면 못 쟀다고 말한다`() {
        val (o1, o2) = pair(48_000.0)
        val i1 = ClockSample(0L, 0L)
        val i2 = ClockSample(0L, 60 * sec)       // 시간은 흘렀는데 프레임이 그대로
        assertEquals(DriftResult.Unavailable, estimateDrift(o1, o2, i1, i2))
    }

    @Test
    fun `입력 쪽 시간이 거꾸로 가면 못 쟀다고 말한다`() {
        val (o1, o2) = pair(48_000.0)
        val i1 = ClockSample(0L, 60 * sec)
        val i2 = ClockSample(48_000L * 60, 0L)
        assertEquals(DriftResult.Unavailable, estimateDrift(o1, o2, i1, i2))
    }
}
