package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * **덩어리 하나만의 값**과 **구간 하나만의 Leq**.
 *
 * 둘 다 같은 잘못에서 나왔다 — 시간축에 누적값을 적으면 **올라가기만 하는
 * 계단**이 되어 「언제 컸는지」가 사라지고, 겉장에 측정 전체의 요약을
 * 적으면 **기록하지 않은 구간**의 소리가 섞인다. 코드를 읽어서는 둘 다
 * 안 보였다 — 기기에서 파일을 뽑아 디코드하고서야 드러났다(2026-09-26).
 */
class BlockExtremesTest {

    private val fs = 48_000
    private val blockFrames = 2_880 // 60ms

    private fun tone(level: Double, at: Int) = FloatArray(blockFrames) {
        val t = (at * blockFrames + it).toDouble() / fs
        (level * sin(2 * PI * 1000.0 * t)).toFloat()
    }

    private fun engine() = SplEngine(fs, Weighting.A, TimeWeight.Fast)

    @Test
    fun `덩어리 최대는 누적이 아니다`() {
        val e = engine()
        // 1초 크게, 그 뒤 2초 아주 작게.
        repeat(17) { e.process(tone(0.5, it), blockFrames) }
        var last: SplFrame? = null
        repeat(34) { last = e.process(tone(0.005, 17 + it), blockFrames) }
        val f = requireNotNull(last)

        assertTrue(
            "누적 최대는 큰 소리를 기억해야 한다: ${f.maxDbfs.value}",
            f.maxDbfs.value > -12.0,
        )
        assertTrue(
            "덩어리 최대가 누적을 따라갔다: ${f.blockMaxDbfs.value}",
            f.blockMaxDbfs.value < f.maxDbfs.value - 20.0,
        )
        assertTrue(
            "덩어리 PEAK 가 누적을 따라갔다: ${f.blockPeakDbfs.value}",
            f.blockPeakDbfs.value < f.peakDbfs.value - 20.0,
        )
    }

    /** 잘림도 **그 덩어리의** 일이다. 한 번 닿았다고 계속 켜져 있으면 안 된다. */
    @Test
    fun `덩어리 잘림은 누적이 아니다`() {
        val e = engine()
        val clip = FloatArray(blockFrames) { 1.0f }
        val quiet = tone(0.01, 1)

        assertTrue("잘린 덩어리를 못 알아봤다", e.process(clip, blockFrames).blockClipped)
        val f = e.process(quiet, blockFrames)
        assertTrue("누적 깃발은 켜져 있어야 한다", f.peakClipped)
        assertTrue("덩어리 깃발이 누적을 따라갔다", !f.blockClipped)
    }

    /** 빈 덩어리에서는 **잰 것이 없다.** 조용한 값으로 꾸미지 않는다. */
    @Test
    fun `빈 덩어리는 최소가 없다`() {
        val e = engine()
        e.process(tone(0.2, 0), blockFrames)
        assertNull(e.process(FloatArray(0), 0).blockMinDbfs)
    }

    /**
     * **구간 Leq 는 누적을 두 번 떠 차를 낸 것이다.**
     *
     * 에너지 합의 뺄셈이므로 **근사가 아니다.** 여기서는 엔진이 스스로
     * 보고한 두 시점의 세션 Leq 와 프레임 수만으로 답을 따로 셈해, 그것과
     * 정확히 같은지 본다 — 필터 상태 같은 다른 변수가 끼지 않는다.
     */
    @Test
    fun `구간 Leq 가 에너지 뺄셈과 정확히 같다`() {
        val e = engine()
        // 시끄러운 앞 구간 — 기록 전의 측정에 해당한다.
        repeat(34) { e.process(tone(0.5, it), blockFrames) }
        val start = e.energySpan()
        val startLeq = requireNotNull(e.process(FloatArray(0), 0).leqSessionDbfs).value

        repeat(34) { i -> e.process(tone(0.02, 34 + i), blockFrames) }
        val end = e.energySpan()
        val endLeq = requireNotNull(e.process(FloatArray(0), 0).leqSessionDbfs).value

        val n1 = start.frames
        val n2 = end.frames - n1
        assertTrue("앞 구간이 안 잡혔다", n1 > 0 && n2 > 0)
        // dBFS 는 20log10(rms) 이므로 10^(dB/10) 이 곧 rms² 다.
        val sumAll = Math.pow(10.0, endLeq / 10.0) * (n1 + n2)
        val sumHead = Math.pow(10.0, startLeq / 10.0) * n1
        val expected = 10.0 * kotlin.math.log10((sumAll - sumHead) / n2)

        val span = requireNotNull(end.since(start)) { "구간 Leq 가 없다" }
        assertEquals("구간 Leq 가 에너지 뺄셈과 다르다", expected, span.value, 1e-9)
        assertTrue(
            "앞 구간이 섞였다 — 전체 $endLeq vs 구간 ${span.value}",
            endLeq > span.value + 10.0,
        )
    }

    /**
     * 그 구간만 새 엔진에 넣은 것과도 **거의** 같다.
     *
     * 딱 맞지는 않는다 — 가중 필터는 기억을 지니므로, 앞 구간의 큰 소리가
     * 필터를 타고 조금 넘어온다. 이어 달린 엔진 쪽이 맞고, 새 엔진 쪽이
     * 근사다. 실제로 잰 차이는 0.18dB 였다.
     */
    @Test
    fun `구간 Leq 가 그 구간만 잰 것과 거의 같다`() {
        val warm = engine()
        repeat(34) { warm.process(tone(0.5, it), blockFrames) }
        val start = warm.energySpan()

        val alone = engine()
        repeat(34) { i ->
            val b = tone(0.02, 34 + i)
            warm.process(b, blockFrames)
            alone.process(b, blockFrames)
        }

        val span = requireNotNull(warm.energySpan().since(start)) { "구간 Leq 가 없다" }
        val direct = requireNotNull(alone.process(FloatArray(0), 0).leqSessionDbfs)
        assertEquals("구간 Leq 가 그 구간만 잰 것과 너무 다르다", direct.value, span.value, 0.25)
    }

    @Test
    fun `아무것도 안 모인 구간은 Leq 가 없다`() {
        val e = engine()
        e.process(tone(0.2, 0), blockFrames)
        val s = e.energySpan()
        assertNull("빈 구간에서 값이 나왔다", e.energySpan().since(s))
    }
}
