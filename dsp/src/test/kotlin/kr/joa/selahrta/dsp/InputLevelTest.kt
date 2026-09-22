package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **조용한 방과 죽은 입력을 가른다**(USB 오디오 지시서 7·14장).
 *
 * > 신호가 없거나 지나치게 작으면 「마이크 연결, +48 V 팬텀전원, GAIN을
 * > 확인하세요」라고 안내한다.
 *
 * 이 안내가 **조용한 대목마다 뜨면** 아무도 안 읽는다. 예배당은 원래
 * 조용하다.
 */
class InputLevelTest {

    private fun amp(db: Double) = Math.pow(10.0, db / 20.0)
    private val ms = 1_000_000L

    @Test
    fun `dBFS 로 옮긴다`() {
        assertEquals(0.0, dbfs(1.0), 1e-9)
        assertEquals(-6.0206, dbfs(0.5), 1e-3)
        assertEquals(-20.0, dbfs(0.1), 1e-9)
        assertEquals("0 은 바닥으로 본다", SILENCE_FLOOR_DBFS, dbfs(0.0), 0.0)
    }

    @Test
    fun `소리가 들어오면 세지 않는다`() {
        val w = SilenceWatch(holdMs = 3_000)
        w.update(amp(-40.0), 0)
        w.update(amp(-40.0), 10_000 * ms)
        assertEquals(0L, w.quietMs)
        assertFalse(w.noSignal)
    }

    /** **조용한 방은 신호 없음이 아니다.** −60dBFS 는 예배당에서 흔하다. */
    @Test
    fun `조용한 방은 신호 없음이 아니다`() {
        val w = SilenceWatch(holdMs = 3_000)
        repeat(20) { i -> w.update(amp(-60.0), i.toLong() * 500 * ms) }
        assertEquals("10초가 지나도 0 이어야 한다", 0L, w.quietMs)
        assertFalse(w.noSignal)
    }

    @Test
    fun `죽은 입력은 버티는 시간이 지나야 말한다`() {
        val w = SilenceWatch(holdMs = 3_000)
        w.update(amp(-95.0), 0)
        assertFalse("첫 덩어리에 말하면 안 된다", w.noSignal)

        w.update(amp(-95.0), 2_900 * ms)
        assertFalse("아직 이르다", w.noSignal)

        w.update(amp(-95.0), 3_000 * ms)
        assertTrue("3초를 채웠으면 말한다", w.noSignal)
        assertEquals(3_000L, w.quietMs)
    }

    /** **한 번이라도 소리가 들어오면 처음부터 다시 센다.** */
    @Test
    fun `중간에 소리가 들어오면 다시 센다`() {
        val w = SilenceWatch(holdMs = 3_000)
        w.update(amp(-95.0), 0)
        w.update(amp(-95.0), 2_900 * ms)
        // 숨 한 번, 기침 한 번.
        w.update(amp(-30.0), 2_950 * ms)
        assertEquals(0L, w.quietMs)

        w.update(amp(-95.0), 3_000 * ms)
        assertFalse("다시 세기 시작했어야 한다", w.noSignal)
        w.update(amp(-95.0), 6_000 * ms)
        assertTrue(w.noSignal)
    }

    /** 완전한 무음(0.0)도 같은 길을 탄다. */
    @Test
    fun `완전한 무음도 같다`() {
        val w = SilenceWatch(holdMs = 1_000)
        w.update(0.0, 0)
        w.update(0.0, 1_000 * ms)
        assertTrue(w.noSignal)
    }

    /**
     * **시계가 뒤로 가도 터지거나 뒤집히지 않는다.**
     *
     * 단조 시계라 보통은 없지만, 음수 시간이 한 번 나오면 그 뒤 판단이
     * 통째로 뒤집힌다.
     */
    @Test
    fun `시계가 뒤로 가면 다시 센다`() {
        val w = SilenceWatch(holdMs = 1_000)
        w.update(0.0, 5_000 * ms)
        w.update(0.0, 1_000 * ms) // 뒤로 갔다
        assertEquals(0L, w.quietMs)
        assertFalse(w.noSignal)
    }

    @Test
    fun `초기화하면 처음으로 돌아간다`() {
        val w = SilenceWatch(holdMs = 1_000)
        w.update(0.0, 0)
        w.update(0.0, 2_000 * ms)
        assertTrue(w.noSignal)
        w.reset()
        assertFalse(w.noSignal)
        assertEquals(0L, w.quietMs)
    }
}
