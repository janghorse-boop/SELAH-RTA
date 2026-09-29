package kr.joa.selahrta.data.rta

import kr.joa.selahrta.audio.SignalChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **세 걸음의 차례**(지시서 §7 후속 — 안내형 측정 순서).
 *
 * 좌우를 견주려면 세 번을 **같은 자리에서, 같은 조건으로** 재야 한다.
 * 손으로 하면 그 사이에 채널을 잘못 고르거나 이름을 다르게 적게 되고,
 * 그러면 잰 차이가 **좌우의 차이가 아니게 된다.**
 */
class RtaSequenceTest {

    private fun seq(name: String = "본당 중앙") = RtaSequence(name, "set-1")

    @Test
    fun `왼쪽 오른쪽 양쪽 차례로 간다`() {
        val s = seq()
        assertEquals(SignalChannels.Left, s.currentChannel())
        s.advance()
        assertEquals(SignalChannels.Right, s.currentChannel())
        s.advance()
        assertEquals(SignalChannels.Both, s.currentChannel())
    }

    /**
     * **양쪽이 마지막이다.** 좌우를 각각 잰 다음에 합친 것을 재야, 합친
     * 것이 둘의 어디쯤인지 바로 견줄 수 있다.
     */
    @Test
    fun `양쪽은 맨 뒤다`() {
        assertEquals(SignalChannels.Both, RtaSequence.DEFAULT_STEPS.last())
    }

    @Test
    fun `다 돌면 끝난다`() {
        val s = seq()
        assertFalse(s.done)
        repeat(3) { s.advance() }
        assertTrue(s.done)
        assertNull(s.currentChannel())
    }

    /** 끝난 뒤에 더 밀어도 넘어가지 않는다. */
    @Test
    fun `끝난 뒤에는 더 안 나아간다`() {
        val s = seq()
        repeat(10) { s.advance() }
        assertEquals(3, s.index)
    }

    // ── 이름 ────────────────────────────────────────────

    /**
     * **채널을 이름에 넣는다.** 세 개가 같은 이름이면 목록에서도 차이
     * 요약에서도 어느 쪽이 어느 쪽인지 갈리지 않는다.
     */
    @Test
    fun `이름에 채널이 붙는다`() {
        val s = seq("본당 중앙")
        assertEquals("본당 중앙 · 왼쪽만", s.currentNameKo())
        s.advance()
        assertEquals("본당 중앙 · 오른쪽만", s.currentNameKo())
        s.advance()
        assertEquals("본당 중앙 · 양쪽", s.currentNameKo())
    }

    @Test
    fun `세 이름이 서로 다르다`() {
        val s = seq()
        val names = buildList {
            while (!s.done) { add(s.currentNameKo()); s.advance() }
        }
        assertEquals(3, names.toSet().size)
    }

    // ── 진행 표시 ───────────────────────────────────────

    /** 몇 번째인지와 무엇을 재는지 함께 적는다. */
    @Test
    fun `진행을 사람 말로 적는다`() {
        val s = seq()
        assertEquals("1/3 · 왼쪽만", s.progressKo())
        s.advance()
        assertEquals("2/3 · 오른쪽만", s.progressKo())
        s.advance()
        s.advance()
        assertEquals("끝", s.progressKo())
    }

    // ── 한 묶음 ─────────────────────────────────────────

    /** **세 걸음이 한 묶음으로 들어간다.** 그래야 나중에 함께 겹쳐 본다. */
    @Test
    fun `세 걸음이 같은 묶음이다`() {
        val s = RtaSequence("본당 중앙", "set-9")
        repeat(3) {
            assertEquals("set-9", s.setId)
            s.advance()
        }
    }

    /** 걸음을 줄여도 셈이 맞는다. 담당자가 둘만 재고 싶을 수 있다. */
    @Test
    fun `걸음이 둘이어도 셈이 맞는다`() {
        val s = RtaSequence(
            "본당 중앙",
            "set-1",
            listOf(SignalChannels.Left, SignalChannels.Right),
        )
        assertEquals("1/2 · 왼쪽만", s.progressKo())
        s.advance()
        assertEquals("2/2 · 오른쪽만", s.progressKo())
        s.advance()
        assertTrue(s.done)
    }
}
