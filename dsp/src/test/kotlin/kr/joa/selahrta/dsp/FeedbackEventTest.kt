package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 하울링 기록(명세 9장: frequency·level·prominence·duration·timestamp 를 기록한다).
 *
 * 화면의 후보는 소리가 그치면 사라진다. 그러면 예배가 끝난 뒤 「아까 그게
 * 몇 Hz 였지」를 답할 수 없는데, 담당자가 EQ 를 만질 때 필요한 것이 바로
 * 그 답이다.
 */
class FeedbackEventTest {

    private val fs = 48_000
    private val n = 4096
    private val hopMs = 43L

    private fun run(
        d: FeedbackDetector,
        durationMs: Long,
        startMs: Long = 0,
        sample: (Int) -> Double,
    ): Long {
        val ps = PowerSpectrum(n)
        val p = DoubleArray(n / 2 + 1)
        var t = startMs
        while (t - startMs < durationMs) {
            val base = (t / 1000.0 * fs).toInt()
            ps.compute(DoubleArray(n) { sample(base + it) }, 0, p)
            d.process(p, t)
            t += hopMs
        }
        return t
    }

    private fun tone(hz: Double, amp: Double): (Int) -> Double =
        { i -> amp * sin(2 * PI * hz * i / fs) }

    @Test
    fun `지속까지 간 소리를 기록한다`() {
        val d = FeedbackDetector(n, fs)
        run(d, 2_500, sample = tone(1000.0, 0.2))

        val e = d.events.firstOrNull()
        assertNotNull("기록이 남아야 한다", e)
        assertEquals("주파수", 1000.0, e!!.hz, 5.0)
        assertTrue("솟음을 남긴다", e.maxProminenceDb > 20)
        assertTrue("레벨을 남긴다", e.peakLevelDbfs > -40)
        assertTrue("이어진 시간을 남긴다 (${e.durationMs}ms)", e.durationMs >= 1_500)
        assertTrue("아직 울리는 중이다", e.ongoing)
        assertEquals("시작 시각은 처음 본 때다", 0L, e.startMs)
    }

    /** 「의심」에 그친 소리는 기록하지 않는다 — 목록이 잡음으로 덮인다. */
    @Test
    fun `의심에 그친 소리는 기록하지 않는다`() {
        val d = FeedbackDetector(n, fs)
        run(d, 500, sample = tone(1000.0, 0.2))
        assertEquals(FeedbackState.Suspect, d.state)
        assertTrue("기록은 없어야 한다", d.events.isEmpty())
    }

    /** 소리가 그치면 기록이 닫힌다. 화면의 후보는 사라져도 기록은 남는다. */
    @Test
    fun `소리가 그치면 기록이 닫히고 남는다`() {
        val d = FeedbackDetector(n, fs)
        val t = run(d, 2_500, sample = tone(1000.0, 0.2))
        run(d, 1_000, startMs = t) { 0.0 }

        assertTrue("후보는 사라진다", d.candidates.isEmpty())
        val e = d.events.single()
        assertFalse("기록은 닫힌다", e.ongoing)
        assertNotNull("끝난 시각이 있어야 한다", e.endMs)
        assertTrue("끝난 시각이 시작보다 뒤다", e.endMs!! > e.startMs)
        assertEquals("주파수", 1000.0, e.hz, 5.0)
    }

    /** 다시 울리면 새 기록이다. 같은 자리라도 별개의 사건이다. */
    @Test
    fun `그쳤다 다시 울리면 새 기록이 된다`() {
        val d = FeedbackDetector(n, fs)
        var t = run(d, 2_000, sample = tone(1000.0, 0.2))
        t = run(d, 1_000, startMs = t) { 0.0 }
        run(d, 2_000, startMs = t, sample = tone(1000.0, 0.2))

        assertEquals("기록이 둘이어야 한다", 2, d.events.size)
        assertTrue("새것이 앞에 온다", d.events[0].startMs > d.events[1].startMs)
    }

    /** 여러 주파수가 동시에 울리면 각각 기록한다. */
    @Test
    fun `동시에 울리는 소리를 따로 기록한다`() {
        val d = FeedbackDetector(n, fs)
        run(d, 2_500) { i ->
            val t = i.toDouble() / fs
            0.2 * sin(2 * PI * 1000.0 * t) + 0.2 * sin(2 * PI * 3150.0 * t)
        }
        val hz = d.events.map { it.hz }.sorted()
        assertEquals("둘을 따로 기록해야 한다 ($hz)", 2, hz.size)
        assertEquals(1000.0, hz[0], 10.0)
        assertEquals(3150.0, hz[1], 20.0)
    }

    /** reset 하면 기록도 지운다. 새 측정의 기록에 지난 것이 섞이면 안 된다. */
    @Test
    fun `reset 하면 기록도 지운다`() {
        val d = FeedbackDetector(n, fs)
        run(d, 2_000, sample = tone(1000.0, 0.2))
        assertTrue(d.events.isNotEmpty())
        d.reset()
        assertTrue(d.events.isEmpty())
    }
}
