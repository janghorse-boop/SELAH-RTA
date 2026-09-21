package kr.joa.selahrta.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **멈추면 소리가 반드시 멎는가.** 한 번이 아니라 여러 번 돌려 본다.
 *
 * 전체 빌드에서 `사람이 멈추면 알리지 않는다` 가 드물게 실패했다. 한 번
 * 돌려서는 보이지 않는 경합이라 재서 찾았다 — 내보내는 스레드가 먼저
 * 놓아 버리면 주 스레드의 `stopSink()` 가 건너뛴다. 이미 놓은 출력에
 * `stop()` 을 부르면 터지므로 그 건너뜀 자체는 옳다.
 *
 * **그래서 붙들어야 할 것은 「stop() 을 불렀는가」가 아니라 「소리가
 * 멎었는가」다.** 둘 중 하나는 반드시 일어나야 하고, 특히 `release()` 는
 * 빠지면 안 된다 — 빠지면 장치를 붙든 채로 남아 다음 소리를 못 낸다.
 */
class SignalPlayerStopContractTest {

    private class Sink : SignalSink {
        @Volatile var stopped = false
        @Volatile var released = false
        override fun open(sampleRate: Int, frames: Int): Boolean = true
        override fun write(buf: FloatArray, offset: Int, frames: Int): Int = frames
        override fun stop() { stopped = true }
        override fun release(): Boolean { released = true; return true }
    }

    @Test
    fun `멈추면 언제나 소리가 멎는다`() {
        val rounds = 200
        var stopSkipped = 0
        var notReleased = 0
        var neither = 0

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
            if (!sink.stopped && !sink.released) neither++
        }

        // 몇 번 건너뛰었는지는 기기·부하에 따라 달라지므로 수를 단언하지
        // 않는다. 다만 무엇을 봤는지는 남긴다.
        println("STOP_CONTRACT rounds=$rounds stopSkipped=$stopSkipped notReleased=$notReleased")

        assertEquals("놓지 않은 채로 끝난 적이 있다 — 장치를 붙든 채 남는다", 0, notReleased)
        assertEquals("멈추지도 놓지도 않은 적이 있다 — 소리가 계속 난다", 0, neither)
    }
}
