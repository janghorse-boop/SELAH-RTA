package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **믿을 수 없는 자리에서 선을 끊는다.**
 *
 * 이어 그리면 잰 적 없는 대역에 선이 지나가 잰 것처럼 보인다. 그 자리가
 * 곧 「보정이 걸리지 않는 곳」인데 그래프는 반대로 말하게 된다 —
 * 그리고 그건 화면만 봐서는 못 알아챈다.
 */
class ValidSegmentsTest {

    private fun seg(vararg b: Boolean) = validSegments(b)

    @Test
    fun `전부 믿을 수 있으면 토막 하나다`() {
        assertEquals(listOf(0..3), seg(true, true, true, true))
    }

    @Test
    fun `전부 못 믿으면 토막이 없다`() {
        assertEquals(emptyList<IntRange>(), seg(false, false, false))
        assertEquals(emptyList<IntRange>(), validSegments(BooleanArray(0)))
    }

    /** **가운데가 비면 둘로 갈린다.** 이어 그리면 안 되는 자리다. */
    @Test
    fun `가운데가 비면 갈린다`() {
        assertEquals(listOf(0..1, 4..5), seg(true, true, false, false, true, true))
    }

    @Test
    fun `양끝이 비어도 센다`() {
        assertEquals(listOf(1..2), seg(false, true, true, false))
    }

    @Test
    fun `끝에서 끝나도 닫는다`() {
        assertEquals(listOf(2..3), seg(false, false, true, true))
    }

    /** 점 하나짜리 토막도 남긴다 — 선은 못 그어도 점은 찍는다. */
    @Test
    fun `점 하나도 토막이다`() {
        assertEquals(listOf(0..0, 2..2, 4..4), seg(true, false, true, false, true))
    }

    /** 보정 곡선에서 실제로 나오는 모양 — 저역·고역이 범위 밖인 경우. */
    @Test
    fun `가운데만 믿을 수 있는 흔한 모양`() {
        val axis = logAxis(20.0, 20000.0, 12)
        val c = interpolateToAxis(
            listOf(CurvePoint(100.0, 0.0), CurvePoint(10000.0, 0.0)),
            axis,
        )
        val segs = validSegments(c.valid)
        assertEquals("가운데 한 토막이어야 한다", 1, segs.size)
        val s = segs.single()
        assertEquals("100Hz 아래는 빠진다", true, c.hz[s.first] >= 100.0)
        assertEquals("10kHz 위는 빠진다", true, c.hz[s.last] <= 10000.0)
    }
}
