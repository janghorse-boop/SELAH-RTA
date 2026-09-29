package kr.joa.selahrta.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **`stop()` 이 돌아왔다고 자원을 놓은 것은 아니다**(독립 검토 UIS7-01,
 * 2026-09-29 — 검토자가 제안한 probe 를 받아 들였다).
 *
 * ## 왜 필요한가
 *
 * `SignalPlayerStopContractTest` 는 `stop()` 200번을 돌리며 그때마다
 * **곧바로** `released` 를 읽었다. 그런데 `stop()` 은 `join` 이 시간을
 * 넘기면 자원을 억지로 놓지 않고 **기다리는 것으로 남긴다** — 아직
 * `write` 안에 있는 자원을 놓으면 그것이 바로 예전에 겪은 사고다.
 *
 * 그래서 그 시험은 **200번에 한 번쯤 거짓으로 실패했다.** 검토자가 실제로
 * 그것을 밟았고(849개 중 1개 실패, 코드 그대로 다시 돌리면 통과),
 * 「즉시 안 놓았다」를 「영영 안 놓는다」의 증거로 삼은 단언이 원인이라고
 * 짚었다.
 *
 * **그 시험을 없애거나 조건을 무르게 만들면 안 된다** — 자원 누수는 실제
 * 위험이다. 그쪽은 「제한 시간 안에 놓기 완료를 관측했는가」로 고쳤고,
 * 여기서는 **확률에 기대지 않고** 같은 계약을 못박는다.
 *
 * ## 어떻게 못박나
 *
 * 내보내는 스레드를 `write` 안에 걸어 두고 `stop()` 을 부른다. 그 순서는
 * 스케줄러가 아니라 빗장이 정한다 — 늘 같은 일이 일어난다.
 *
 * 1. `stop()` 이 돌아온 그때 **아직 안 놓았고**, 기다리는 것으로 세어진다.
 * 2. 빗장을 풀면 내보내는 쪽이 **제 손으로 놓는다.**
 * 3. **쓰는 도중에는 결코 놓지 않는다.**
 *
 * **가짜 출력을 통과했다는 것이 실제 스피커가 조용해졌다는 뜻은 아니다.**
 * 여기서 보는 것은 호출 규약뿐이다.
 */
class DeferredReleaseTest {

    @Test
    fun `stop 이 돌아와도 쓰는 중이면 나중에 놓는다`() {
        val enteredWrite = CountDownLatch(1)
        val allowWriteReturn = CountDownLatch(1)
        val released = CountDownLatch(1)
        val stopped = AtomicBoolean(false)
        val inWrite = AtomicBoolean(false)
        val releasedDuringWrite = AtomicBoolean(false)

        val sink = object : SignalSink {
            override fun open(sampleRate: Int, frames: Int, channels: Int) = true

            override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
                inWrite.set(true)
                enteredWrite.countDown()
                try {
                    check(allowWriteReturn.await(10, TimeUnit.SECONDS))
                    return frames
                } finally {
                    inWrite.set(false)
                }
            }

            override fun stop() {
                stopped.set(true)
            }

            override fun release(): Boolean {
                // **놓는 그 순간에 쓰는 중이었는가.** 실제 `AudioTrack`
                // 이면 여기서 터진다.
                releasedDuringWrite.set(inWrite.get())
                released.countDown()
                return true
            }
        }

        val player = SignalPlayer(openSink = { sink }, warn = {})
        try {
            assertNotEquals(
                SignalPlayer.NONE,
                player.start(SignalRequest(TestSignal.Sine1k, DEFAULT_AMPLITUDE)),
            )
            assertTrue("내보내는 쪽이 write 에 들어오지 않았다", enteredWrite.await(10, TimeUnit.SECONDS))

            player.stop()

            assertTrue("출력에 멈추라고 하지 않았다", stopped.get())
            // 여기가 요점이다 — **아직 안 놓았는데 그것이 정상이다.**
            assertEquals("쓰는 중인데 놓아 버렸다", 1L, released.count)
            assertEquals("놓기를 기다리는 것으로 세지 않았다", 1, player.pendingCount)
        } finally {
            allowWriteReturn.countDown()
        }

        assertTrue("깨어난 뒤에도 끝내 놓지 않았다", released.await(10, TimeUnit.SECONDS))
        assertFalse("쓰는 도중에 놓았다 — 실제 장치면 터진다", releasedDuringWrite.get())

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (player.pendingCount != 0 && System.nanoTime() < deadline) Thread.yield()
        assertEquals("놓고 나서도 기다리는 것으로 남아 있다", 0, player.pendingCount)
    }
}
