package kr.joa.selahrta.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 독립 검증자(Codex)가 만든 `SignalPlayerProbe.kt` 를 그대로 옮긴 회귀 시험.
 *
 * **재는 방법을 손대지 않았다.** 검증자는 `check(...)` 로 **결함이
 * 재현되는 것**을 확인했으므로, 여기서는 그 단언을 **고쳐진 뒤의 모습**으로
 * 뒤집어 적었다.
 *
 * 세 가지 다 내가 놓친 것이다:
 *
 * - **P1** — 시간 초과로 두고 온 재생이 **쌓인다.** 나는 요청서에 「그 하나가
 *   남는다」고 적었는데, 실제로는 멈출 때마다 하나씩 늘어난다(스레드까지).
 * - **P2** — 내보내는 스레드의 `release()` 가 주 스레드의 `stop()` **안에서**
 *   겹친다. 내가 놓는 자리를 스레드로 옮기면서 만든 것이다.
 * - **P3** — `write` 가 **요청보다 적게** 쓰면 그만큼을 버리고 다음 칸으로
 *   건너뛴다. 파형이 끊긴다. 이것은 처음부터 있던 결함이다.
 */
class SignalPlayerProbeTest {

    /** 풀어 줄 때까지 `write` 에서 붙들린다. `stop()` 은 풀어 주지 않는다. */
    private class StuckSink : SignalSink {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val released = CountDownLatch(1)
        override fun open(sampleRate: Int, frames: Int) = true
        override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
            entered.countDown()
            check(unblock.await(5, TimeUnit.SECONDS))
            return frames
        }
        override fun stop() = Unit
        override fun release() {
            released.countDown()
        }
    }

    /** `stop()` 이 도는 **동안** `release()` 가 불리는지 본다. */
    private class StopRaceSink : SignalSink {
        val entered = CountDownLatch(1)
        val writeGate = CountDownLatch(1)
        val stopEntered = CountDownLatch(1)
        val finishStop = CountDownLatch(1)
        val released = CountDownLatch(1)

        @Volatile
        var insideStop = false

        @Volatile
        var releasedDuringStop = false

        override fun open(sampleRate: Int, frames: Int) = true
        override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
            entered.countDown()
            check(writeGate.await(5, TimeUnit.SECONDS))
            return frames
        }

        override fun stop() {
            insideStop = true
            stopEntered.countDown()
            check(finishStop.await(5, TimeUnit.SECONDS))
            insideStop = false
        }

        override fun release() {
            releasedDuringStop = insideStop
            released.countDown()
        }
    }

    /** 첫 `write` 가 요청보다 **적게** 쓴다. 실제 `AudioTrack` 이 그럴 수 있다. */
    private class PartialSink : SignalSink {
        val calls = AtomicInteger()
        val secondEntered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val released = CountDownLatch(1)

        @Volatile
        var secondStart = 0f

        @Volatile
        var secondOffset = -1

        override fun open(sampleRate: Int, frames: Int) = true
        override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
            if (calls.incrementAndGet() == 1) return 128
            secondOffset = offset
            secondStart = buf[offset]
            secondEntered.countDown()
            check(gate.await(5, TimeUnit.SECONDS))
            return frames
        }

        override fun stop() {
            gate.countDown()
        }

        override fun release() {
            released.countDown()
        }
    }

    /**
     * **P1 — 시간 초과로 두고 온 재생이 쌓이지 않는다.**
     *
     * 검증자가 잰 값: `TIMEOUT_RETAINED=3 after 3 sequential start/stop cycles`.
     * 멈출 때마다 출력 하나와 스레드 하나가 늘어난다.
     */
    @Test
    fun `시간 초과로 두고 온 재생이 끝없이 쌓이지 않는다`() {
        val sinks = CopyOnWriteArrayList<StuckSink>()
        val p = SignalPlayer(openSink = { StuckSink().also { sinks.add(it) } }, warn = {})

        var startedCycles = 0
        repeat(6) {
            if (p.start(TestSignal.Sine1k, SignalLevel.Low) != SignalPlayer.NONE) {
                startedCycles++
                check(sinks.last().entered.await(5, TimeUnit.SECONDS))
            }
            p.stop()
        }

        val retained = sinks.count { it.released.count != 0L }
        println("[P1] 시작 $startedCycles 회 · 남은 출력 $retained · playing=${p.playing}")
        assertNull("내보내는 중이 아니어야 한다", p.playing)
        assertTrue(
            "두고 온 것이 상한을 넘으면 안 된다 (남은 $retained)",
            retained <= SignalPlayer.MAX_STUCK_PLAYBACKS,
        )

        // 풀어 주면 모두 제 손으로 놓는다.
        sinks.forEach { it.unblock.countDown() }
        sinks.forEach { assertTrue("깨어나면 놓아야 한다", it.released.await(5, TimeUnit.SECONDS)) }
    }

    /**
     * **P2 — `stop()` 이 도는 동안 `release()` 가 겹치지 않는다.**
     *
     * 검증자가 잰 값: `RELEASE_DURING_STOP=true`. `AudioTrack` 은 `stop()`
     * 과 `release()` 를 다른 스레드에서 겹쳐 부르는 것을 보장하지 않는다.
     *
     * **내가 만든 것이다** — 놓는 자리를 내보내는 스레드로 옮기면서 생겼다.
     */
    @Test
    fun `멈추는 동안 놓기가 겹치지 않는다`() {
        val race = StopRaceSink()
        val q = SignalPlayer(openSink = { race }, warn = {})
        q.start(TestSignal.Sine1k, SignalLevel.Low)
        check(race.entered.await(5, TimeUnit.SECONDS))

        val stopper = Thread { q.stop() }.apply { start() }
        check(race.stopEntered.await(5, TimeUnit.SECONDS))
        // stop() 이 도는 한가운데서 내보내는 스레드를 풀어 준다.
        race.writeGate.countDown()

        // **검증자의 순서를 그대로 쓴다** — stop() 을 아직 붙들어 둔 채로
        // release 가 일어나는지 본다. 처음에 나는 여기서 stop 을 먼저
        // 끝내 버려, 아무것도 보지 않는 시험을 만들었다.
        val releasedWhileStopping = race.released.await(2, TimeUnit.SECONDS)

        race.finishStop.countDown()
        stopper.join(5_000)
        assertFalse("멈추는 스레드가 끝나야 한다", stopper.isAlive)
        assertTrue("결국 놓여야 한다", race.released.await(5, TimeUnit.SECONDS))

        println("[P2] stop() 안에서 놓였는가=${race.releasedDuringStop} (stop 중 release=$releasedWhileStopping)")
        assertFalse("stop() 이 도는 동안 release() 가 불리면 안 된다", race.releasedDuringStop)
    }

    /**
     * **P3 — 적게 쓰였으면 그만큼만 나아간다.**
     *
     * 검증자가 잰 값: 첫 `write` 가 1024 중 128 만 받았는데, 다음 덩어리는
     * 1024 표본 뒤에서 시작했다 — **896 표본이 사라졌다.** 파형이 끊겨
     * 「틱」 소리가 나고, 스윕이면 시간축이 어긋난다.
     *
     * 이것은 내 수정이 만든 것이 아니라 **처음부터 있던 결함**이다.
     */
    @Test
    fun `적게 쓰이면 그만큼만 나아간다`() {
        val partial = PartialSink()
        val r = SignalPlayer(openSink = { partial }, warn = {})
        r.start(TestSignal.Sine1k, SignalLevel.Low)
        check(partial.secondEntered.await(5, TimeUnit.SECONDS))

        // 128 표본만 나갔으니, 다음에 나갈 첫 표본은 128번째여야 한다.
        val expected = (SignalLevel.Low.amplitude * sin(2 * PI * 1000 * 128 / 48000)).toFloat()
        println(
            "[P3] 다음 표본 ${partial.secondStart} · 128 뒤 기대값 $expected · offset ${partial.secondOffset}",
        )
        assertEquals(
            "128 만 나갔으면 다음은 128번째 표본이어야 한다",
            expected,
            partial.secondStart,
            1e-4f,
        )

        r.stop()
        assertTrue(partial.released.await(5, TimeUnit.SECONDS))
    }
}
