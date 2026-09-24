package kr.joa.selahrta.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 독립 검증자(Codex)가 `ReleaseCompletionProbe.kt` 와
 * `CopyOnWriteRemoveAllProbe.kt` 로 재현한 것을 옮긴 회귀 시험.
 *
 * - **RC01(High)** — `CopyOnWriteArrayList` 위의 Kotlin `removeAll(predicate)`
 *   는 원자적이지 않다. 낱개 연산이 안전한 것과 **복합 연산이 안전한 것은
 *   다른 이야기**인데 내가 그것을 섞어 생각했다. 주 스레드의 `start()` 에서
 *   터지므로 **앱이 죽는다.**
 * - **RC02(Medium)** — `release()` 가 실패해도 성공과 똑같이 세어, 상한이
 *   막으려던 자원 누적을 다시 허용한다. 「다시 소리를 낼 수 있게 한다」는
 *   이유는 **자원이 없어졌다는 근거가 아니다.**
 */
class ReleaseCompletionTest {

    /**
     * **RC01 — 그 조합이 실제로 터지는지 먼저 확인한다.**
     *
     * 검증자의 최소 재현을 그대로 옮겼다. 이것이 터진다는 것은 곧
     * `SignalPlayer` 가 같은 조합을 쓰면 안 된다는 뜻이다.
     */
    @Test
    fun `CopyOnWriteArrayList 의 predicate removeAll 은 원자적이지 않다`() {
        val pending = CopyOnWriteArrayList(listOf(1, 2))
        val inPredicate = CountDownLatch(1)
        val removed = CountDownLatch(1)
        val worker = Thread {
            check(inPredicate.await(5, TimeUnit.SECONDS))
            pending.remove(1)
            pending.remove(2)
            removed.countDown()
        }.apply { start() }

        var failure: Throwable? = null
        try {
            pending.removeAll {
                inPredicate.countDown()
                check(removed.await(5, TimeUnit.SECONDS))
                true
            }
        } catch (t: Throwable) {
            failure = t
        }
        worker.join(5_000)

        println("[RC01] 최소 재현: ${failure?.javaClass?.simpleName}: ${failure?.message}")
        assertTrue(
            "이 조합은 터진다 — 그러니 production 에서 쓰지 않는다 (실제: $failure)",
            failure is IndexOutOfBoundsException,
        )
    }

    /** 자원을 놓기도 전에 `release()` 가 터지는 출력. */
    private class FailingReleaseSink : SignalSink {
        val writing = CountDownLatch(1)
        val writeGate = CountDownLatch(1)
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
            throw IllegalStateException("놓지 못했다")
        }
    }

    /**
     * **RC02 — 놓기가 실패한 것을 성공처럼 세지 않는다.**
     *
     * 검증자가 잰 값: `RELEASE_FAILURE starts=4 unreleased=4 pending=0
     * warnings=4`. 네 번 다 실패했는데 **계속 새로 열렸고 pending 은 0**
     * 이었다.
     */
    @Test
    fun `놓기가 실패하면 자리를 비켜 주지 않는다`() {
        val sinks = CopyOnWriteArrayList<FailingReleaseSink>()
        val p = SignalPlayer(openSink = { FailingReleaseSink().also { sinks.add(it) } }, warn = {})
        val cap = SignalPlayer.MAX_STUCK_PLAYBACKS

        var starts = 0
        repeat(cap + 3) {
            val id = p.start(SignalRequest(TestSignal.Sine1k, DEFAULT_AMPLITUDE))
            if (id == SignalPlayer.NONE) return@repeat
            starts++
            check(sinks.last().writing.await(10, TimeUnit.SECONDS))
            p.stop()
            // 내보내는 스레드가 깨어나 release 를 시도할 틈을 준다.
            Thread.sleep(50)
        }

        println("[RC02] 시작 $starts · 놓기 실패 ${sinks.count { it.releaseCalls.get() > 0 }} · pendingCount ${p.pendingCount}")

        assertTrue("상한을 넘겨 열면 안 된다 ($starts)", starts <= cap)
        assertEquals("실패한 것도 자리를 차지해야 한다", starts, p.pendingCount)
        assertTrue(
            "같은 출력에 놓기를 되풀이하지 않는다",
            sinks.all { it.releaseCalls.get() <= 1 },
        )
    }
}
