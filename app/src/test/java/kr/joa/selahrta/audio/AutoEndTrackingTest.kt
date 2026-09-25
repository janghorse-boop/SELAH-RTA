package kr.joa.selahrta.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * **사람이 멈추지 않고 스스로 오류로 끝난 재생**도 같은 규칙으로 센다.
 *
 * 내가 RC02 를 고칠 때 **수동 `stop()` 경로만** 손댔다. 자동 오류 종료는
 * `current` 를 먼저 지우므로 그 재생이 목록에도 없고 현재도 아니어서,
 * **놓기에 실패해도 아무 데도 세어지지 않았다.**
 *
 * 검증자 측정:
 *
 * | 경로 | 해제 | 시작 | failedReleaseCount | pendingCount |
 * |---|---|---:|---:|---:|
 * | 수동 stop | false | 2 | 2 | 2 |
 * | **자동 오류 종료** | false | **4** | **0** | **0** |
 *
 * 그래서 등록·해제·정리를 `settle()` 한 곳으로 모았고, **놓기를 시도하기
 * 전에 먼저 등록한다** — 놓기가 늦어지는 동안에도 세어져야 한다.
 */
class AutoEndTrackingTest {

    /**
     * 첫 `write` 가 오류로 끝나고, `release` 는 시험이 정한 대로 구는 출력.
     *
     * [releaseGate] 가 있으면 놓기가 그때까지 돌아오지 않는다.
     */
    private class AutoFailSink(
        private val failCode: Int,
        private val releaseResult: Boolean = false,
        private val releaseThrows: Boolean = false,
        private val releaseGate: CountDownLatch? = null,
    ) : SignalSink {
        val releaseCalls = AtomicInteger()
        val releaseEntered = CountDownLatch(1)

        override fun open(sampleRate: Int, frames: Int, channels: Int) = true

        override fun write(buf: FloatArray, offset: Int, frames: Int): Int = failCode

        override fun stop() = Unit

        override fun release(): Boolean {
            releaseCalls.incrementAndGet()
            releaseEntered.countDown()
            releaseGate?.let { check(it.await(10, TimeUnit.SECONDS)) }
            if (releaseThrows) throw IllegalStateException("놓지 못했다")
            return releaseResult
        }
    }

    /** 자동 오류 종료를 [times] 번 겪되, 매번 알림이 올 때까지 기다린다. */
    private fun runAutoFailures(
        times: Int,
        make: () -> AutoFailSink,
    ): Triple<Int, SignalPlayer, List<AutoFailSink>> {
        val sinks = CopyOnWriteArrayList<AutoFailSink>()
        var ended = CountDownLatch(1)
        val p = SignalPlayer(
            onEnded = { _, _ -> ended.countDown() },
            openSink = { make().also { sinks.add(it) } },
            warn = {},
        )
        var starts = 0
        repeat(times) {
            ended = CountDownLatch(1)
            if (p.start(SignalRequest(TestSignal.Sine1k, DEFAULT_AMPLITUDE)) == SignalPlayer.NONE) return@repeat
            starts++
            // **알림을 기다린다** — 동시 실행을 가정하지 않는다.
            check(ended.await(5, TimeUnit.SECONDS)) { "끝났다는 알림이 오지 않았다" }
            // 놓기까지 끝날 틈을 준다.
            Thread.sleep(30)
        }
        return Triple(starts, p, sinks)
    }

    /** **장치가 끊기고 놓기도 실패하면** 두 번째 뒤로는 열지 않는다. */
    @Test
    fun `자동 오류 종료에서 놓기가 실패하면 상한에 센다`() {
        val cap = SignalPlayer.MAX_STUCK_PLAYBACKS
        val (starts, p, sinks) = runAutoFailures(cap + 3) {
            AutoFailSink(SignalSink.ERROR_DEAD_OBJECT, releaseResult = false)
        }

        println("[RC02 자동] 시작 $starts · failed ${p.failedReleaseCount} · pending ${p.pendingCount}")
        assertEquals("상한만큼만 열려야 한다", cap, starts)
        assertEquals("실패한 것이 자리를 차지해야 한다", cap, p.pendingCount)
        assertEquals("실패 수도 보여야 한다", cap, p.failedReleaseCount)
        assertTrue("같은 출력에 놓기를 되풀이하지 않는다", sinks.all { it.releaseCalls.get() == 1 })
    }

    /** 예외로 실패해도 같다. */
    @Test
    fun `자동 오류 종료에서 놓기가 예외로 실패해도 상한에 센다`() {
        val cap = SignalPlayer.MAX_STUCK_PLAYBACKS
        val (starts, p, _) = runAutoFailures(cap + 3) {
            AutoFailSink(SignalSink.ERROR_DEAD_OBJECT, releaseThrows = true)
        }
        assertEquals(cap, starts)
        assertEquals(cap, p.failedReleaseCount)
    }

    /** 장치 끊김이 아닌 **그냥 쓰기 오류**도 같은 규칙을 쓴다. */
    @Test
    fun `보통 쓰기 오류로 끝나도 같은 규칙을 쓴다`() {
        val cap = SignalPlayer.MAX_STUCK_PLAYBACKS
        val (starts, p, _) = runAutoFailures(cap + 3) {
            AutoFailSink(failCode = -1, releaseResult = false)
        }
        assertEquals(cap, starts)
        assertEquals(cap, p.pendingCount)
    }

    /** **연속 0 으로 끝나는 경로**도 같은 규칙을 쓴다. */
    @Test
    fun `연속 0 으로 끝나도 같은 규칙을 쓴다`() {
        val cap = SignalPlayer.MAX_STUCK_PLAYBACKS
        val (starts, p, _) = runAutoFailures(cap + 3) {
            AutoFailSink(failCode = 0, releaseResult = false)
        }
        assertEquals("상한만큼만 열려야 한다", cap, starts)
        assertEquals(cap, p.pendingCount)
    }

    /**
     * **놓기가 늦어지는 동안에도 센다.**
     *
     * 검증자가 특히 짚은 자리다 — 「실패가 반환된 뒤에만 등록하지 말고
     * release 진행 중도 추적해야 한다」.
     */
    @Test
    fun `자동 오류 뒤 놓기가 늦어지는 동안에도 상한에 센다`() {
        val gate = CountDownLatch(1)
        val sinks = CopyOnWriteArrayList<AutoFailSink>()
        val ended = CountDownLatch(1)
        val p = SignalPlayer(
            onEnded = { _, _ -> ended.countDown() },
            openSink = {
                AutoFailSink(SignalSink.ERROR_DEAD_OBJECT, releaseGate = gate).also { sinks.add(it) }
            },
            warn = {},
        )

        try {
            assertNotEquals(SignalPlayer.NONE, p.start(SignalRequest(TestSignal.Sine1k, DEFAULT_AMPLITUDE)))
            // 놓기 안에 들어갔지만 아직 돌아오지 않았다.
            assertTrue(sinks.last().releaseEntered.await(5, TimeUnit.SECONDS))

            println("[RC02 지연] pending ${p.pendingCount}")
            assertEquals("놓는 중인 것도 세어야 한다", 1, p.pendingCount)
        } finally {
            gate.countDown()
        }
        assertTrue("끝났다고 알려야 한다", ended.await(5, TimeUnit.SECONDS))
    }

    /** **성공한 자동 종료**는 자리를 돌려주고 다시 시작할 수 있다. */
    @Test
    fun `자동 종료가 성공하면 자리를 돌려준다`() {
        val sinks = CopyOnWriteArrayList<AutoFailSink>()
        var ended = CountDownLatch(1)
        val p = SignalPlayer(
            onEnded = { _, _ -> ended.countDown() },
            openSink = {
                AutoFailSink(SignalSink.ERROR_DEAD_OBJECT, releaseResult = true).also { sinks.add(it) }
            },
            warn = {},
        )

        repeat(4) {
            ended = CountDownLatch(1)
            assertNotEquals("늘 다시 열려야 한다", SignalPlayer.NONE, p.start(SignalRequest(TestSignal.Sine1k, DEFAULT_AMPLITUDE)))
            assertTrue(ended.await(5, TimeUnit.SECONDS))
            Thread.sleep(30)
        }

        assertEquals("자리가 남아 있으면 안 된다", 0, p.pendingCount)
        assertEquals("실패도 없어야 한다", 0, p.failedReleaseCount)
        assertTrue("놓기는 한 번씩만", sinks.all { it.releaseCalls.get() == 1 })
        assertEquals("네 번 다 열렸다", 4, sinks.size)
    }
}
