package kr.joa.selahrta.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * **열기에 실패한 출력도 정리 장부에 오른다**(32회차 R32-02, `docs/unverified.md` 3-5).
 *
 * 예전에는 열기가 `false` 면 `release()` 의 결과를 버렸고, 열다가 예외가 나면 놓지도 않았다. 그래서 자원이 남아도
 * `pendingCount == 0`·`failedReleaseCount == 0` 이었다 — 그 둘을 「정리 완료」로 쓰는 쪽(TF 시작의 정리 기다림)이
 * 남은 자원을 보지 못했다.
 *
 * 가짜 출력으로 보는 것은 **플레이어의 장부 계약**이다. 실제 `AudioTrack` 이 그런 식으로 실패한다는 뜻이 아니다.
 */
class SignalPlayerOpenFailureTest {

    private fun player(sink: FakeSink) = SignalPlayer(onEnded = { _, _ -> }, openSink = { sink }, warn = {})

    private val req = SignalRequest(TestSignal.Pink, DEFAULT_AMPLITUDE)

    @Test
    fun `열기가 실패하고 놓기도 실패하면 놓기 실패로 세어진다`() {
        val sink = FakeSink(openFails = true, releaseResult = false)
        val p = player(sink)
        assertEquals(SignalPlayer.NONE, p.start(req))
        assertEquals("놓기를 한 번 시도했다", 1, sink.releaseCount.get())
        assertTrue("가짜 자원이 남았다", sink.holding)
        assertEquals(1, p.failedReleaseCount)
        assertEquals(1, p.pendingCount)
    }

    @Test
    fun `열기가 실패해도 놓기에 성공하면 아무것도 남지 않는다`() {
        val sink = FakeSink(openFails = true)
        val p = player(sink)
        assertEquals(SignalPlayer.NONE, p.start(req))
        assertFalse(sink.holding)
        assertEquals(0, p.failedReleaseCount)
        assertEquals(0, p.pendingCount)
    }

    @Test
    fun `열다가 예외가 나도 놓고 예외는 그대로 올린다`() {
        val sink = FakeSink(openThrows = true)
        val p = player(sink)
        try {
            p.start(req)
            fail("열기 예외가 사라졌다")
        } catch (e: IllegalStateException) {
            // 부르는 쪽(명령 스레드)의 계약은 그대로다.
        }
        assertEquals("열기 예외에서도 놓았다", 1, sink.releaseCount.get())
        assertFalse(sink.holding)
        assertEquals(0, p.pendingCount)
    }

    @Test
    fun `열다가 예외가 나고 놓기도 실패하면 놓기 실패로 세어진다`() {
        val sink = FakeSink(openThrows = true, releaseResult = false)
        val p = player(sink)
        runCatching { p.start(req) }
        assertTrue(sink.holding)
        assertEquals(1, p.failedReleaseCount)
        assertEquals(1, p.pendingCount)
    }

    @Test
    fun `열다가 예외가 나고 놓다가도 예외가 나면 놓기 실패로 세어진다`() {
        val sink = FakeSink(openThrows = true, releaseThrows = true)
        val p = player(sink)
        val e = runCatching { p.start(req) }.exceptionOrNull()
        assertTrue("열기 예외가 올라온다(놓기 예외에 가려지지 않는다)", e?.message?.contains("열다가") == true)
        assertEquals(1, p.failedReleaseCount)
        assertEquals(1, p.pendingCount)
    }

    /** TF 의 기준 탭(`TappedSink`)을 덧씌워도 안쪽 출력의 놓기 결과가 그대로 장부에 오른다. */
    @Test
    fun `기준 탭을 덧씌운 출력도 열기 실패의 놓기 결과가 장부에 오른다`() {
        val inner = FakeSink(openFails = true, releaseResult = false)
        val p = SignalPlayer(
            onEnded = { _, _ -> },
            openSink = { kr.joa.selahrta.transfer.TappedSink(inner) { _, _, _ -> } },
            warn = {},
        )
        assertEquals(SignalPlayer.NONE, p.start(req))
        assertEquals(1, inner.releaseCount.get())
        assertEquals(1, p.failedReleaseCount)
    }

    /** 남은 실패도 상한에 센다 — 놓지 못한 것이 쌓이면 새로 열지 않는다(SP01 과 같은 규칙). */
    @Test
    fun `놓지 못한 열기 실패가 상한에 이르면 새로 열지 않는다`() {
        val sinks = ArrayDeque(
            listOf(
                FakeSink(openFails = true, releaseResult = false),
                FakeSink(openFails = true, releaseResult = false),
                FakeSink(),
            ),
        )
        var opened = 0
        val p = SignalPlayer(onEnded = { _, _ -> }, openSink = { opened++; sinks.removeFirst() }, warn = {})
        repeat(2) { assertEquals(SignalPlayer.NONE, p.start(req)) }
        assertEquals(SignalPlayer.MAX_STUCK_PLAYBACKS, p.failedReleaseCount)
        assertEquals(SignalPlayer.NONE, p.start(req))
        assertEquals("상한에서 막혀 세 번째 출력은 열지 않았다", 2, opened)
    }
}
