package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **대역 잡음이 정말 그 대역만 내는가**(담당자 지시 2026-09-29).
 *
 * 그래픽 EQ 의 250Hz 를 만지며 들으려고 쓰는 신호다. 옆 대역이 섞여
 * 들리면 **무엇을 만지고 있는지 귀로 가를 수가 없어** 쓸모가 없다.
 *
 * 여기서는 순음을 넣어 **주파수마다 얼마나 통과하는지**를 직접 잰다.
 * 잡음을 넣고 눈으로 보는 것보다 숫자가 분명하다.
 */
class BandNoiseFilterTest {

    private val fs = 48_000

    /** [hz] 순음을 넣었을 때 나오는 크기(dB). 0dB 가 그대로 통과다. */
    private fun gainDb(centerHz: Double, hz: Double): Double {
        val f = BandNoiseFilter(centerHz, fs)
        // 필터가 자리를 잡을 때까지 흘려보낸다.
        val warm = fs / 2
        repeat(warm) { f.process(sin(2 * PI * hz * it / fs)) }
        var sum = 0.0
        val n = fs / 2
        for (i in 0 until n) {
            val v = f.process(sin(2 * PI * hz * (warm + i) / fs))
            sum += v * v
        }
        val rms = sqrt(sum / n)
        // 넣은 순음의 RMS 는 1/√2 다.
        return 20 * log10(rms / (1.0 / sqrt(2.0)))
    }

    // ── 중심은 통과, 옆은 막힌다 ──────────────────────────

    @Test
    fun `중심 주파수는 거의 그대로 통과한다`() {
        val g = gainDb(1000.0, 1000.0)
        assertEquals("중심이 깎이거나 부풀었다: %.2f dB".format(g), 0.0, g, 1.0)
    }

    /**
     * **1/3 옥타브 경계에서 −3dB 언저리여야 한다.**
     *
     * 경계는 중심의 2^(±1/6) 배다. 4차라 정확히 −3dB 는 아니고 조금 더
     * 깎인다 — 「엇비슷한 폭」이면 된다.
     */
    @Test
    fun `대역 경계에서 눈에 띄게 깎인다`() {
        val lo = gainDb(1000.0, 1000.0 * Math.pow(2.0, -1.0 / 6))
        val hi = gainDb(1000.0, 1000.0 * Math.pow(2.0, 1.0 / 6))
        assertTrue("아래 경계가 안 깎였다: %.2f dB".format(lo), lo in -9.0..-1.0)
        assertTrue("위 경계가 안 깎였다: %.2f dB".format(hi), hi in -9.0..-1.0)
    }

    /**
     * **한 옥타브 밖은 확실히 죽어야 한다.**
     *
     * 이것이 안 되면 250Hz 를 튼다면서 500Hz 도 함께 들린다 — EQ 를
     * 만지는 사람이 어느 슬라이더가 듣는 소리를 바꾸는지 모르게 된다.
     */
    @Test
    fun `한 옥타브 밖은 크게 죽는다`() {
        assertTrue("아래가 안 죽는다", gainDb(1000.0, 500.0) < -20.0)
        assertTrue("위가 안 죽는다", gainDb(1000.0, 2000.0) < -20.0)
    }

    @Test
    fun `두 옥타브 밖은 거의 안 들린다`() {
        assertTrue(gainDb(1000.0, 250.0) < -35.0)
        assertTrue(gainDb(1000.0, 4000.0) < -35.0)
    }

    // ── 중심을 EQ 눈금에 맞춘다 ───────────────────────────

    /**
     * **슬라이더로 고른 3147Hz 는 「3150Hz 대역」이어야 한다.**
     *
     * EQ 의 눈금과 같은 자리를 내주지 않으면, 만지는 슬라이더와 들리는
     * 대역이 어긋난다.
     */
    @Test
    fun `가장 가까운 1_3 옥타브 중심으로 맞춘다`() {
        assertEquals(3150.0, BandNoiseFilter(3147.0, fs).centerHz, 1e-9)
        assertEquals(1000.0, BandNoiseFilter(1010.0, fs).centerHz, 1e-9)
        assertEquals(250.0, BandNoiseFilter(240.0, fs).centerHz, 1e-9)
    }

    /** **로그 거리로 잰다.** 선형으로 재면 고역 쪽이 늘 이긴다. */
    @Test
    fun `저역에서도 가까운 쪽을 고른다`() {
        // 22Hz 는 20 과 25 사이인데, 선형이면 25 가 더 가깝다고 본다
        // (|22−20|=2, |22−25|=3 이므로 선형도 20 이지만) — 23 에서 갈린다.
        // 로그로는 20·25 의 기하평균이 22.36 이다.
        assertEquals(20.0, BandNoiseFilter(22.0, fs).centerHz, 1e-9)
        assertEquals(25.0, BandNoiseFilter(23.0, fs).centerHz, 1e-9)
    }

    @Test
    fun `범위 밖도 끝 대역으로 맞춘다`() {
        assertEquals(20.0, BandNoiseFilter(5.0, fs).centerHz, 1e-9)
        assertEquals(20000.0, BandNoiseFilter(30000.0, fs).centerHz, 1e-9)
    }

    // ── 여러 표본율 ───────────────────────────────────────

    @Test
    fun `44100Hz 에서도 중심이 통과한다`() {
        val f = BandNoiseFilter(1000.0, 44_100)
        val warm = 22_050
        repeat(warm) { f.process(sin(2 * PI * 1000.0 * it / 44_100)) }
        var sum = 0.0
        val n = 22_050
        for (i in 0 until n) {
            val v = f.process(sin(2 * PI * 1000.0 * (warm + i) / 44_100))
            sum += v * v
        }
        val g = 20 * log10(sqrt(sum / n) / (1.0 / sqrt(2.0)))
        assertEquals("44.1kHz 에서 중심이 틀어졌다: %.2f dB".format(g), 0.0, g, 1.0)
    }
}
