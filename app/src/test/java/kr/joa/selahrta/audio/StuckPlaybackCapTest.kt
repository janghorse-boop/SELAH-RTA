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
 * **끝나기를 기다리는 재생의 상한이 실제로 지켜지는가.**
 *
 * 독립 검증자가 `SpFollowupProbe.kt` 로 재현한 것을 그대로 옮겼다.
 *
 * 내가 남긴 구멍은 **「끝났다」의 뜻**이었다. `releaseOnce()` 는
 * `compareAndSet` 을 **먼저** 하고 `sink.release()` 를 **그 뒤에** 부른다.
 * 그래서 `isReleased == true` 는 「놓기를 **시작**했다」이지 「놓기가
 * **끝났다**」가 아니다. 그런데 상한을 세는 자리에서 그 값을 보고 목록에서
 * 빼 버려, **놓는 중인 것이 상한에 세어지지 않았다.**
 *
 * 검증자가 잰 값: `RELEASE_PENDING starts=4 unfinished=4 pendingCount=1 cap=2`
 * — 놓기가 안 끝난 것이 넷인데 보이는 수는 하나였다.
 */
class StuckPlaybackCapTest {

    /** `write` 도, `release` 도 시험이 풀어 줄 때까지 돌아오지 않는다. */
    private class SlowReleaseSink : SignalSink {
        val writing = CountDownLatch(1)
        val writeGate = CountDownLatch(1)
        val releaseEntered = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        val releaseFinished = CountDownLatch(1)
        val releaseCalls = AtomicInteger()

        override fun open(sampleRate: Int, frames: Int) = true

        override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
            writing.countDown()
            check(writeGate.await(10, TimeUnit.SECONDS))
            return frames
        }

        override fun stop() {
            writeGate.countDown()
        }

        override fun release() {
            releaseCalls.incrementAndGet()
            releaseEntered.countDown()
            check(releaseGate.await(10, TimeUnit.SECONDS))
            releaseFinished.countDown()
        }
    }

    /**
     * **놓기가 늦어져도 상한을 우회하지 못한다.**
     *
     * 검증자가 적은 회귀 그대로다 — 놓기 자체를 붙들고 상한보다 많이
     * 시작을 요청한다.
     */
    @Test
    fun `놓기가 늦어져도 상한을 넘겨 열지 않는다`() {
        val sinks = CopyOnWriteArrayList<SlowReleaseSink>()
        val p = SignalPlayer(openSink = { SlowReleaseSink().also { sinks.add(it) } }, warn = {})
        val cap = SignalPlayer.MAX_STUCK_PLAYBACKS

        var starts = 0
        try {
            repeat(cap + 3) {
                val id = p.start(TestSignal.Sine1k, SignalLevel.Low)
                if (id != SignalPlayer.NONE) {
                    starts++
                    check(sinks.last().writing.await(10, TimeUnit.SECONDS))
                    p.stop()
                    check(sinks.last().releaseEntered.await(10, TimeUnit.SECONDS))
                }
            }

            val unfinished = sinks.count { it.releaseFinished.count != 0L }
            println("[SP01] 시작 $starts · 놓기 미완 $unfinished · pendingCount ${p.pendingCount} · 상한 $cap")

            assertTrue("놓기가 안 끝난 것이 상한을 넘으면 안 된다 ($unfinished)", unfinished <= cap)
            assertEquals(
                "보이는 수가 실제와 같아야 한다 (보임 ${p.pendingCount} / 실제 $unfinished)",
                unfinished,
                p.pendingCount,
            )
            assertEquals("상한만큼만 열려야 한다", cap, starts)
        } finally {
            sinks.forEach { it.writeGate.countDown(); it.releaseGate.countDown() }
            sinks.forEach { check(it.releaseFinished.await(10, TimeUnit.SECONDS)) }
        }

        // 놓기가 끝나면 **다시 시작할 수 있어야** 한다.
        assertTrue("모두 정확히 한 번씩 놓여야 한다", sinks.all { it.releaseCalls.get() == 1 })
        val again = p.start(TestSignal.Sine1k, SignalLevel.Low)
        assertNotEquals("풀린 뒤에는 다시 열려야 한다", SignalPlayer.NONE, again)
        sinks.last().writeGate.countDown()
        sinks.last().releaseGate.countDown()
        p.stop()
    }

    /**
     * **쓰기가 늦은 것과 놓기가 늦은 것이 섞여도 합계가 상한을 지킨다.**
     *
     * 검증자가 요구한 세 번째 회귀다.
     */
    @Test
    fun `쓰기 지연과 놓기 지연이 섞여도 합계가 상한을 지킨다`() {
        val sinks = CopyOnWriteArrayList<SlowReleaseSink>()
        val p = SignalPlayer(openSink = { SlowReleaseSink().also { sinks.add(it) } }, warn = {})
        val cap = SignalPlayer.MAX_STUCK_PLAYBACKS

        var starts = 0
        try {
            repeat(cap + 3) { round ->
                val id = p.start(TestSignal.Sine1k, SignalLevel.Low)
                if (id == SignalPlayer.NONE) return@repeat
                starts++
                val s = sinks.last()
                check(s.writing.await(10, TimeUnit.SECONDS))
                if (round % 2 == 0) {
                    // 쓰기에서 붙들린 채로 둔다 — stop() 이 풀어 주지 못하게
                    // 미리 게이트를 닫아 둘 수는 없으므로, 멈춘 뒤 놓기에서
                    // 붙들린다. 두 경우 모두 「아직 안 끝난 것」이다.
                    p.stop()
                } else {
                    p.stop()
                    check(s.releaseEntered.await(10, TimeUnit.SECONDS))
                }
            }

            val unfinished = sinks.count { it.releaseFinished.count != 0L }
            println("[SP01 혼합] 시작 $starts · 미완 $unfinished · pendingCount ${p.pendingCount}")
            assertTrue("합계가 상한을 넘으면 안 된다 ($unfinished)", unfinished <= cap)
        } finally {
            sinks.forEach { it.writeGate.countDown(); it.releaseGate.countDown() }
            sinks.forEach { check(it.releaseFinished.await(10, TimeUnit.SECONDS)) }
        }
        assertTrue("모두 정확히 한 번씩 놓여야 한다", sinks.all { it.releaseCalls.get() == 1 })
    }
}
