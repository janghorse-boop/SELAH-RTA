package kr.joa.selahrta.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **멈추면 소리가 반드시 멎는가.**
 *
 * 전체 빌드에서 `사람이 멈추면 알리지 않는다` 가 드물게 실패했다. 한 번
 * 돌려서는 보이지 않는 경합이라 재서 찾았다 — 내보내는 스레드가 먼저
 * 놓아 버리면 주 스레드의 `stopSink()` 가 건너뛴다. 이미 놓은 출력에
 * `stop()` 을 부르면 터지므로 그 건너뜀 자체는 옳다.
 *
 * **그래서 붙들어야 할 것은 「stop() 을 불렀는가」가 아니라 「소리가
 * 멎었는가」다.** 특히 `release()` 는 빠지면 안 된다 — 빠지면 장치를
 * 붙든 채로 남아 다음 소리를 못 낸다.
 *
 * ## 무엇을 단언하고 무엇을 못 하는가
 *
 * | 단언 | 성격 |
 * |---|---|
 * | 놓기가 빠진 적이 없다(`notReleased == 0`) | **불변식** |
 * | 놓은 뒤에 멈추라고 한 적이 없다(`stoppedAfterRelease == 0`) | **불변식** |
 * | 스스로 끝난 뒤의 `stop()` 은 sink 를 건드리지 않는다 | **결정적** |
 * | 건너뛴 횟수 | 세기만 한다 — 기계와 부하에 좌우된다 |
 *
 * **`releaseStarted` 가드를 못박는 단언은 없다.** 되돌려서 확인했다:
 * `stopSink()` 의 `if (!releaseStarted.get())` 를 지워도 **어느 시험도
 * 실패하지 않는다.** 200회 시험의 `stopSkipped` 만 6 → 0 으로 바뀐다 —
 * 보고는 있으나 단언이 아니다. 그 수를 단언하면 **부하에 좌우되는
 * 시험**이 되므로(이 저장소에서 이미 한 번 데였다) 그러지 않았다.
 *
 * 아래 `stoppedAfterRelease` 는 그 대신 **위반이 일어나면 잡는다.** 경합이
 * 그 순서로 벌어진 판에서만 걸리므로 변이를 늘 잡지는 못하지만, 거짓으로
 * 실패하지도 않는다.
 *
 * **가짜 sink 를 통과했다는 것이 실제 스피커가 조용해졌다는 뜻은 아니다.**
 * 여기서 보는 것은 호출 규약뿐이다.
 */
class SignalPlayerStopContractTest {

    private class Sink : SignalSink {
        @Volatile var stopped = false
        @Volatile var released = false

        /** **놓은 뒤에 멈추라고 했는가.** 실제 `AudioTrack` 이면 터진다. */
        @Volatile var stoppedAfterRelease = false

        override fun open(sampleRate: Int, frames: Int): Boolean = true
        override fun write(buf: FloatArray, offset: Int, frames: Int): Int = frames
        override fun stop() {
            if (released) stoppedAfterRelease = true
            stopped = true
        }

        override fun release(): Boolean { released = true; return true }
    }

    @Test
    fun `멈추면 언제나 소리가 멎는다`() {
        val rounds = 200
        var stopSkipped = 0
        var notReleased = 0
        var stoppedAfterRelease = 0

        repeat(rounds) {
            val sink = Sink()
            val player = SignalPlayer(onEnded = { _, _ -> }, openSink = { sink }, warn = {})
            player.start(TestSignal.Sine1k, SignalLevel.Low)
            // 스레드가 실제로 돌기 시작할 틈만 준다. 길게 자면 늘 같은
            // 순서로만 끝나 경합이 드러나지 않는다.
            Thread.sleep(2)

            player.stop()

            if (!sink.stopped) stopSkipped++
            if (!sink.released) notReleased++
            if (sink.stoppedAfterRelease) stoppedAfterRelease++
        }

        // 건너뛴 수는 기기·부하에 따라 달라지므로 **단언하지 않는다.**
        // 다만 무엇을 봤는지는 남긴다.
        println(
            "STOP_CONTRACT rounds=$rounds stopSkipped=$stopSkipped " +
                "notReleased=$notReleased stoppedAfterRelease=$stoppedAfterRelease",
        )

        // **「멈추지도 놓지도 않았다」 는 따로 세지 않는다.** 아래가 0 이면
        // 늘 놓은 것이므로 그 수는 언제나 0 이다 — 논리적으로 중복이고,
        // 두 줄이 서로를 확인해 주는 것처럼 보여 오히려 해롭다(검증자 지적).
        assertEquals("놓지 않은 채로 끝난 적이 있다 — 장치를 붙든 채 남는다", 0, notReleased)
        assertEquals(
            "이미 놓은 출력에 stop 을 불렀다 — 실제 장치면 터진다",
            0,
            stoppedAfterRelease,
        )
    }

    /**
     * **놓기가 먼저인 순서를 못박는다.**
     *
     * `write` 가 계속 0 을 돌려주게 해 재생이 **스스로** 끝나게 한다. 그
     * 경로는 `current` 를 먼저 지우고 `release()` 를 부르므로, 뒤늦은
     * `stop()` 은 건드릴 것이 없다. latch 로 놓기가 끝난 것을 확인한 **뒤에**
     * 멈추라고 한다 — 타이밍에 기대지 않는다.
     *
     * **무엇을 못박는지 정확히**: 여기서 `sink.stop()` 이 불리지 않는
     * 까닭은 `releaseStarted` 가드가 아니라 **`current` 가 이미 비어
     * `stop()` 이 곧바로 돌아오기** 때문이다. 가드 쪽은 사람이 멈추는
     * 경로에서만 닿고, 그 순서는 밖에서 고정할 수 없다(클래스 KDoc 참고).
     */
    @Test
    fun `스스로 끝난 뒤에 멈추라고 해도 sink 를 건드리지 않는다`() {
        val releasedLatch = CountDownLatch(1)

        class IdleSink : SignalSink {
            @Volatile var stopped = false
            @Volatile var released = false
            override fun open(sampleRate: Int, frames: Int): Boolean = true

            /** 늘 0 — 맴돌이 상한에 걸려 재생이 스스로 끝난다. */
            override fun write(buf: FloatArray, offset: Int, frames: Int): Int = 0
            override fun stop() { stopped = true }
            override fun release(): Boolean {
                released = true
                releasedLatch.countDown()
                return true
            }
        }

        val sink = IdleSink()
        val player = SignalPlayer(onEnded = { _, _ -> }, openSink = { sink }, warn = {})
        player.start(TestSignal.Sine1k, SignalLevel.Low)

        assertTrue("재생이 스스로 끝나야 한다", releasedLatch.await(10, TimeUnit.SECONDS))
        assertTrue("끝났으면 놓았어야 한다", sink.released)

        // 놓기가 **끝난 뒤** 멈추라고 한다.
        player.stop()

        assertFalse("이미 놓은 출력에 stop 을 부르면 안 된다", sink.stopped)
        assertTrue(sink.released)
    }
}
