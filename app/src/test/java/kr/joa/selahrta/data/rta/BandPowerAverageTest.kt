package kr.joa.selahrta.data.rta

import kr.joa.selahrta.dsp.ThirdOctave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **dB 를 그냥 평균 내면 틀린다**(담당자 지시 2026-09-29 기준 3).
 *
 * 들어오는 값은 이미 dB 다. 산술평균을 쓰면 **큰 쪽이 묻혀** 곡선이
 * 실제보다 낮게 적히고, 그 곡선으로 EQ 를 필요 없는 만큼 올리게 된다.
 */
class BandPowerAverageTest {

    private val n = ThirdOctave.BAND_COUNT

    private fun flat(v: Double) = DoubleArray(n) { v }

    // ── 전력으로 모은다 ─────────────────────────────────

    /**
     * **60dB 와 80dB 의 평균은 70 이 아니라 77.0dB 다.**
     *
     * 에너지로는 100 배 차이인데 산술평균은 그 차이를 반으로 접는다.
     * 이 한 줄이 이 클래스가 있는 까닭 전부다.
     */
    @Test
    fun `60과 80의 평균은 77dB 다`() {
        val a = BandPowerAverage()
        a.add(flat(60.0))
        a.add(flat(80.0))

        val m = a.meanDb()!!
        assertEquals("산술평균(70)이 나오면 안 된다", 77.0, m[0], 0.05)
        assertNotEquals(70.0, m[0], 1.0)
    }

    /** 같은 값만 들어오면 그 값 그대로다. 이것도 안 맞으면 셈이 틀린 것이다. */
    @Test
    fun `같은 값만 모으면 그 값이다`() {
        val a = BandPowerAverage()
        repeat(50) { a.add(flat(73.5)) }
        assertEquals(73.5, a.meanDb()!![0], 1e-9)
    }

    /** 밴드마다 따로 모은다. 한 칸이 다른 칸에 새면 안 된다. */
    @Test
    fun `밴드마다 따로 모은다`() {
        val a = BandPowerAverage()
        a.add(DoubleArray(n) { 50.0 + it })
        a.add(DoubleArray(n) { 50.0 + it })
        val m = a.meanDb()!!
        for (i in 0 until n) assertEquals("밴드 $i", 50.0 + i, m[i], 1e-9)
    }

    // ── 보정 오프셋 ─────────────────────────────────────

    /**
     * **오프셋은 나중에 더해도 같다.** dB SPL 은 dBFS 에 상수를 더한
     * 값이므로, 장마다 더한 뒤 평균 내는 것과 결과가 같아야 한다.
     */
    @Test
    fun `오프셋은 나중에 더해도 같다`() {
        val off = 94.3
        val later = BandPowerAverage().apply {
            add(flat(-40.0)); add(flat(-20.0))
        }.meanDb(off)!!
        val earlier = BandPowerAverage().apply {
            add(flat(-40.0 + off)); add(flat(-20.0 + off))
        }.meanDb()!!
        assertEquals(earlier[0], later[0], 1e-9)
    }

    // ── 끊고 다시 센다 ──────────────────────────────────

    /**
     * **채널을 바꾸면 끊는다**(기준 2). 안 끊으면 앞 채널의 소리가 뒤
     * 채널의 곡선에 남아, 좌우가 실제보다 비슷해 보인다.
     */
    @Test
    fun `되돌리면 앞의 것이 안 섞인다`() {
        val a = BandPowerAverage()
        repeat(100) { a.add(flat(90.0)) }
        a.reset()
        a.add(flat(50.0))

        assertEquals(1, a.frames)
        assertEquals(50.0, a.meanDb()!![0], 1e-9)
    }

    // ── 없는 것은 없다고 한다 ───────────────────────────

    /**
     * **한 장도 없으면 null 이다.** 0 을 돌려주면 「아주 조용한 방」으로
     * 읽혀, 측정이 안 된 것을 측정 결과로 저장하게 된다.
     */
    @Test
    fun `한 장도 없으면 평균이 없다`() {
        assertNull(BandPowerAverage().meanDb())
        assertEquals(0, BandPowerAverage().frames)
    }

    /**
     * **NaN 이 든 장은 통째로 버린다.** 한 칸만 NaN 이어도 그 밴드의
     * 평균이 영영 NaN 이 되어 곡선에 구멍이 남는다.
     */
    @Test
    fun `유한하지 않은 장은 안 센다`() {
        val a = BandPowerAverage()
        a.add(flat(70.0))
        a.add(DoubleArray(n) { if (it == 5) Double.NaN else 70.0 })
        a.add(DoubleArray(n) { if (it == 7) Double.POSITIVE_INFINITY else 70.0 })

        assertEquals("성한 한 장만 세어야 한다", 1, a.frames)
        assertTrue(a.meanDb()!!.all { it.isFinite() })
    }

    /** 밴드 수가 다른 장은 받지 않는다 — 분석 설정이 바뀐 장이다. */
    @Test(expected = IllegalArgumentException::class)
    fun `밴드 수가 다르면 안 받는다`() {
        BandPowerAverage().add(DoubleArray(n - 1))
    }
}
