package kr.joa.selahrta.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 늦게 도착한 소식을 가려내는 규칙(독립 재검증 F02·F04).
 *
 * 이 규칙이 깨졌을 때 무슨 일이 났는지는 [CaptureGeneration] 주석에 적었다.
 * 여기서는 그 규칙 자체를 못박는다.
 */
class CaptureGenerationTest {

    @Test
    fun `시작하기 전에는 아무것도 받아들이지 않는다`() {
        val g = CaptureGeneration()
        assertFalse(g.running)
        assertFalse(g.accepts(1))
        assertFalse("0 은 「세대 없음」이라 그 자체로 거절된다", g.accepts(CaptureGeneration.NONE))
    }

    @Test
    fun `지금 세대의 소식만 받아들인다`() {
        val g = CaptureGeneration()
        val first = g.begin()
        assertTrue(g.accepts(first))
        assertFalse(g.accepts(first + 1))
    }

    /**
     * F04 — **멈추면 늦게 온 소식을 받지 않는다.**
     *
     * 예전에는 stop 이 세션 번호를 그대로 둬서, 이미 멈춘 뒤 도착한 경로
     * 확인이 검사를 통과해 방금 지운 보정을 다시 구독했다. 늦게 온 읽기
     * 오류가 정상 종료를 「다른 앱이 마이크를 가져갔습니다」로 뒤집기도 했다.
     */
    @Test
    fun `멈춘 뒤에 온 소식은 버린다`() {
        val g = CaptureGeneration()
        val s = g.begin()
        assertTrue(g.accepts(s))

        g.end()

        assertFalse("멈춘 뒤에는 그 세대도 받지 않는다", g.accepts(s))
        assertFalse(g.running)
    }

    /**
     * F02 — **다시 시작하면 이전 세대는 받지 않는다.**
     *
     * 종료가 늦어진 작업 스레드가 깨어나 덩어리를 흘려보내도, 그 덩어리는
     * 이전 세대의 것이라 새 측정에 닿지 못한다.
     */
    @Test
    fun `다시 시작하면 이전 세대는 버린다`() {
        val g = CaptureGeneration()
        val old = g.begin()
        g.end()
        val new = g.begin()

        assertFalse("옛 스레드의 덩어리는 새 측정에 닿지 못한다", g.accepts(old))
        assertTrue(g.accepts(new))
        assertTrue("번호는 다시 쓰지 않는다", new > old)
    }

    /** 멈추지 않고 곧바로 다시 시작해도 마찬가지다(분리 후 자동 재시작). */
    @Test
    fun `멈추지 않고 다시 시작해도 이전 세대는 버린다`() {
        val g = CaptureGeneration()
        val old = g.begin()
        val new = g.begin()
        assertFalse(g.accepts(old))
        assertTrue(g.accepts(new))
    }

    @Test
    fun `여러 번 멈춰도 탈나지 않는다`() {
        val g = CaptureGeneration()
        val s = g.begin()
        g.end()
        g.end()
        assertFalse(g.accepts(s))
        assertEquals(CaptureGeneration.NONE, g.current)
    }

    /** 시작·정지를 되풀이해도 번호는 늘 새것이다. */
    @Test
    fun `번호는 되풀이해도 다시 쓰이지 않는다`() {
        val g = CaptureGeneration()
        val seen = mutableSetOf<Long>()
        repeat(50) {
            val id = g.begin()
            assertTrue("$id 가 다시 나왔다", seen.add(id))
            assertTrue(g.accepts(id))
            g.end()
            assertFalse(g.accepts(id))
        }
    }
}
