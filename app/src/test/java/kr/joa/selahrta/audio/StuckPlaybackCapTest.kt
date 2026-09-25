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

        override fun open(sampleRate: Int, frames: Int, channels: Int) = true

        override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
            writing.countDown()
            check(writeGate.await(10, TimeUnit.SECONDS))
            return frames
        }

        override fun stop() {
            writeGate.countDown()
        }

        override fun release(): Boolean {
            releaseCalls.incrementAndGet()
            releaseEntered.countDown()
            check(releaseGate.await(10, TimeUnit.SECONDS))
            releaseFinished.countDown()
            return true
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
                val id = p.start(SignalRequest(TestSignal.Sine1k, DEFAULT_AMPLITUDE))
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
        val again = p.start(SignalRequest(TestSignal.Sine1k, DEFAULT_AMPLITUDE))
        assertNotEquals("풀린 뒤에는 다시 열려야 한다", SignalPlayer.NONE, again)
        sinks.last().writeGate.countDown()
        sinks.last().releaseGate.countDown()
        p.stop()
    }

    /** `stop()` 이 **쓰기를 풀어 주지 않는** 출력. write 안에서 붙들린다. */
    private class StuckWriteSink : SignalSink {
        val writing = CountDownLatch(1)
        val writeGate = CountDownLatch(1)
        val released = CountDownLatch(1)
        override fun open(sampleRate: Int, frames: Int, channels: Int) = true
        override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
            writing.countDown()
            check(writeGate.await(10, TimeUnit.SECONDS))
            return frames
        }
        override fun stop() = Unit // **풀어 주지 않는다**
        override fun release(): Boolean {
            released.countDown()
            return true
        }
    }

    /**
     * **쓰기에서 붙들린 것과 놓기에서 붙들린 것이 섞여도 합계가 상한을
     * 지킨다.**
     *
     * 앞서 쓴 시험은 **둘 다 놓기 지연**이었다 — 가짜 출력의 `stop()` 이
     * 언제나 쓰기를 풀어 주는데도 「쓰기 지연」이라고 이름 붙였고, 주석에도
     * 사실상 그렇게 바뀐다고 적어 놓고 그대로 뒀다(독립 검증 RC03).
     *
     * 이제 **서로 다른 출력 둘**을 쓴다 — A 는 `stop()` 이 쓰기를 풀어 주지
     * 않아 write 안에 남고, B 는 풀어 주되 놓기에서 멈춘다. 둘이 각각 그
     * 상태임을 latch 로 확인한 뒤에 합계를 본다.
     */
    @Test
    fun `쓰기에서 붙들린 것과 놓기에서 붙들린 것이 섞여도 상한을 지킨다`() {
        val a = StuckWriteSink()
        val b = SlowReleaseSink()
        val made = mutableListOf<SignalSink>()
        val p = SignalPlayer(
            openSink = {
                val s = if (made.isEmpty()) a else b
                made.add(s)
                s
            },
            warn = {},
        )

        try {
            // A — 쓰기 안에서 붙들린다.
            check(p.start(SignalRequest(TestSignal.Sine1k, DEFAULT_AMPLITUDE)) != SignalPlayer.NONE)
            check(a.writing.await(10, TimeUnit.SECONDS))
            p.stop()
            assertEquals("A 는 아직 write 안이라 놓이지 않았다", 1L, a.released.count)

            // B — 쓰기는 풀리지만 놓기에서 멈춘다.
            check(p.start(SignalRequest(TestSignal.Sine2k, DEFAULT_AMPLITUDE)) != SignalPlayer.NONE)
            check(b.writing.await(10, TimeUnit.SECONDS))
            p.stop()
            check(b.releaseEntered.await(10, TimeUnit.SECONDS))

            println("[RC03] pendingCount ${p.pendingCount} · 상한 ${SignalPlayer.MAX_STUCK_PLAYBACKS}")
            assertEquals("둘 다 자리를 차지한다", 2, p.pendingCount)

            // 상한에 닿았으니 더 열리면 안 된다.
            assertEquals(
                "상한에 닿으면 열지 않는다",
                SignalPlayer.NONE,
                p.start(SignalRequest(TestSignal.Sine4k, DEFAULT_AMPLITUDE)),
            )
            assertEquals("새 출력을 만들지도 않는다", 2, made.size)

            // 하나씩 풀면 하나씩 정리된다.
            b.releaseGate.countDown()
            check(b.releaseFinished.await(10, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertEquals("B 가 빠지면 하나만 남는다", 1, p.pendingCount)
        } finally {
            a.writeGate.countDown()
            b.writeGate.countDown()
            b.releaseGate.countDown()
        }

        assertTrue("A 도 깨어나면 놓는다", a.released.await(10, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals("모두 정리되면 0", 0, p.pendingCount)
        assertNotEquals(
            "다시 시작할 수 있어야 한다",
            SignalPlayer.NONE,
            p.start(SignalRequest(TestSignal.Sine1k, DEFAULT_AMPLITUDE)),
        )
        p.stop()
    }
}
