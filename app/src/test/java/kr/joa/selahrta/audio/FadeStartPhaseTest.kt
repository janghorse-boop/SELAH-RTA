package kr.joa.selahrta.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * **감쇠가 이어진 각에서 시작하는가**(독립 검토 SRL-01, 2026-09-29).
 *
 * ## 무엇을 놓쳤나
 *
 * 순음의 각은 1,024 프레임을 **만들 때** 모두 나아간다. 정지 요청이 오면
 * 쓰지 않은 나머지를 버리는데, 실제로 나간 만큼으로 되돌리는 것은
 * `sample` 뿐이었다. **각은 끝까지 간 자리에 남았다.**
 *
 * 그래서 크기는 30ms 에 걸쳐 곱게 내려가는데, **내려가기 시작하는 그
 * 자리에서 파형이 수직으로 튀었다.** 없애려던 벽이 램프 시작점으로
 * 옮겨 갔을 뿐이다. 진폭 0.4 짜리 1kHz 순음에서 이음매가 0.346 튀었다.
 *
 * 먼저 쓴 `SignalRampTest` 는 **다 받아 주는 출력**으로 크기와 길이만
 * 봤다. 다 받아 주면 버릴 프레임이 없으므로 이 결함이 아예 안 생긴다.
 *
 * ## 어떻게 재나
 *
 * 셋째 `write` 가 40ms 잠들었다 **스스로** 깨어나게 한다 — 실제
 * `AudioTrack` 이 버퍼가 비어 돌아오는 것과 같은 모양이다. 그 사이에
 * `stop()` 이 불려 감쇠가 시작된다.
 *
 * **이 시험은 실제 스피커에서 「딱」이 들리는지는 모른다.** 파형이
 * 이어졌는지만 본다.
 */
class FadeStartPhaseTest {

    private val amp = 0.4
    /**
     * **딱 떨어지지 않는 주파수를 쓴다.**
     *
     * 1kHz 는 48kHz 에서 한 주기가 정확히 48 표본이라, 재는 자리가
     * 하필 영교차에 떨어질 수 있다. 그러면 각이 틀어져도 값이 둘 다
     * 0 이라 **시험이 조용히 통과한다**(실제로 128칸 경우가 그랬다).
     */
    private val hz = 997.0

    /** 셋째 쓰기에서 [thirdAccept] 칸만 받아 주는 출력. */
    private class LateSink(private val thirdAccept: Int) : SignalSink {
        val entered = CountDownLatch(1)
        private val calls = AtomicInteger()
        private val taken = ArrayList<Float>()
        private val lock = Any()

        override fun open(sampleRate: Int, frames: Int, channels: Int) = true

        override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
            val call = calls.incrementAndGet()
            val take = if (call == 3) {
                entered.countDown()
                // **스스로 깨어난다.** `stop()` 이 풀어 주는 것이 아니다 —
                // 그러면 감쇠가 아예 일어나지 않아 잴 것이 없다.
                Thread.sleep(40)
                thirdAccept
            } else {
                frames
            }
            synchronized(lock) { for (i in 0 until take) taken.add(buf[offset + i]) }
            return take
        }

        override fun stop() = Unit
        override fun release() = true

        fun snapshot(): List<Float> = synchronized(lock) { ArrayList(taken) }
    }

    /**
     * 셋째 덩어리에서 [thirdAccept] 칸만 나갔을 때, **그 다음 표본**이
     * 이어진 각에 있는지 본다.
     */
    private fun assertContinuous(thirdAccept: Int) {
        val sink = LateSink(thirdAccept)
        val p = SignalPlayer(openSink = { sink }, warn = {})
        p.start(SignalRequest(TestSignal.Custom, amp, toneHz = hz))
        assertTrue("셋째 쓰기까지 못 갔다", sink.entered.await(5, TimeUnit.SECONDS))
        p.stop()
        Thread.sleep(400)

        val v = sink.snapshot()
        // 앞 두 덩어리(각 1,024 프레임)와 셋째에서 받은 만큼.
        val emitted = FRAMES * 2 + thirdAccept / CHANNELS
        val idx = emitted * CHANNELS
        assertTrue("감쇠가 아예 안 나갔다(${v.size}칸)", v.size > idx)

        // 그 자리의 소리는 같은 각을 이어야 한다. 크기는 램프가 줄여 놓았을
        // 수 있으므로 **부호와 비율**이 아니라 「원래 파형을 넘지 않는가」와
        // 「이웃과 이어지는가」를 본다.
        val expectedFull = amp * sin(2 * PI * hz * emitted / 48_000.0)
        val got = v[idx].toDouble()
        // 램프가 1.0 에서 내려오기 시작하므로 첫 감쇠 표본은 거의 제 크기다.
        assertTrue(
            "감쇠 첫 표본이 %+.6f 다 — 이어졌다면 %+.6f 언저리여야 한다(셋째 %d칸)"
                .format(got, expectedFull, thirdAccept),
            abs(got - expectedFull) < amp * 0.05,
        )
    }

    /** **버린 프레임이 가장 많은 경우.** 셋째 덩어리가 통째로 안 나갔다. */
    @Test
    fun `셋째 덩어리를 통째로 버려도 각이 이어진다`() {
        assertContinuous(0)
    }

    /** 일부만 나간 경우. 되감을 자리가 덩어리 한가운데다. */
    @Test
    fun `셋째 덩어리를 조금만 내보내도 각이 이어진다`() {
        assertContinuous(128)
    }

    @Test
    fun `절반쯤 내보내도 각이 이어진다`() {
        assertContinuous(1_024)
    }

    private companion object {
        const val FRAMES = 1024
        const val CHANNELS = 2
    }
}
