package kr.joa.selahrta.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

/**
 * **적게 쓰이는 경우**를 모아서 본다(독립 검증 SP02).
 *
 * 검증자가 필요하다고 적은 회귀를 그대로 만든다:
 *
 * > 128/256/나머지처럼 부분 반환하는 sink 로 **수락된 전체 샘플열을
 * > 원본과 비교**한다. 0 반복, 부분 반환 뒤 음수, 부분 반환 중 stop 도
 * > 검사한다.
 *
 * 「몇 번 불렀나」가 아니라 **「실제로 나간 소리가 원래 파형과 같은가」**
 * 를 본다. 앞선 시험은 첫 표본 하나만 봤는데, 그것만으로는 중간에서
 * 어긋나는 것을 못 잡는다.
 */
class PartialWriteTest {

    private companion object {
        const val FRAMES = 1024
        const val SAMPLE_RATE = 48_000
    }

    /**
     * 받은 것을 **모두 이어 붙여 두는** 출력.
     *
     * [accept] 가 이번 호출에서 몇 개를 받을지 정한다. 0 이나 음수를
     * 돌려주면 그대로 전한다.
     */
    private class RecordingSink(
        private val accept: (call: Int, offered: Int) -> Int,
    ) : SignalSink {
        val calls = AtomicInteger()
        val taken = ArrayList<Float>()
        val released = CountDownLatch(1)

        @Volatile
        var stopped = false

        /** 시험이 「그만 받자」고 할 때까지 돈다. */
        val enough = CountDownLatch(1)

        override fun open(sampleRate: Int, frames: Int) = true

        @Synchronized
        override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
            val n = accept(calls.incrementAndGet(), frames)
            if (n > 0) {
                for (i in 0 until n) taken.add(buf[offset + i])
                if (taken.size >= FRAMES * 2) enough.countDown()
            }
            return n
        }

        override fun stop() {
            stopped = true
        }

        override fun release() {
            released.countDown()
        }

        @Synchronized
        fun snapshot(): FloatArray = taken.toFloatArray()
    }

    /** 1kHz 순음의 [n] 번째 표본. 재생이 내야 할 원래 파형이다. */
    private fun expected(n: Int): FloatArray = FloatArray(n) { i ->
        (SignalLevel.Low.amplitude * sin(2 * PI * 1000 * i / SAMPLE_RATE)).toFloat()
    }

    /**
     * **128 → 256 → 나머지로 쪼개 받아도 파형이 이어진다.**
     *
     * 고치기 전에는 첫 호출이 128 만 받아도 다음 덩어리를 1024 뒤에서
     * 만들어, 표본 896개가 사라졌다.
     */
    @Test
    fun `쪼개서 받아도 나간 파형이 원본과 같다`() {
        val sink = RecordingSink { call, offered ->
            when (call) {
                1 -> 128
                2 -> 256
                else -> offered
            }
        }
        val p = SignalPlayer(openSink = { sink }, warn = {})
        p.start(TestSignal.Sine1k, SignalLevel.Low)
        assertTrue("두 덩어리는 나가야 한다", sink.enough.await(5, TimeUnit.SECONDS))
        p.stop()

        val got = sink.snapshot()
        assertTrue("충분히 받았어야 한다 (${got.size})", got.size >= FRAMES * 2)
        assertArrayEquals(
            "나간 파형이 원본과 같아야 한다",
            expected(FRAMES * 2),
            got.copyOf(FRAMES * 2),
            1e-6f,
        )
    }

    /**
     * **0 만 계속 돌려주면 맴돌지 않고 끝낸다.**
     *
     * 고치기 전에는 0 을 돌려줘도 「음수가 아니니 성공」으로 보고 다음
     * 덩어리로 넘어갔다. 지금은 몇 번 기다려 보고 끝낸다 — 중요한 것은
     * **CPU 를 태우며 영영 도는 일이 없다**는 것이다.
     */
    @Test
    fun `0 만 돌려주면 맴돌지 않고 끝낸다`() {
        val sink = RecordingSink { _, _ -> 0 }
        val ended = CountDownLatch(1)
        val p = SignalPlayer(
            onEnded = { _, _ -> ended.countDown() },
            openSink = { sink },
            warn = {},
        )
        p.start(TestSignal.Sine1k, SignalLevel.Low)

        assertTrue("끝났다고 알려야 한다", ended.await(5, TimeUnit.SECONDS))
        assertNull("내보내는 중이 아니어야 한다", p.playing)
        assertTrue("자원을 놓아야 한다", sink.released.await(5, TimeUnit.SECONDS))
        p.stop()
    }

    /**
     * **적게 받은 뒤 오류가 오면 그 오류로 끝난다.**
     *
     * Android 문서가 적은 순서다 — 일부가 전송된 경우 `DEAD_OBJECT` 는
     * **다음 write 에서** 돌아올 수 있다.
     */
    @Test
    fun `적게 받은 뒤 온 오류로 끝난다`() {
        val sink = RecordingSink { call, _ ->
            if (call == 1) 128 else SignalSink.ERROR_DEAD_OBJECT
        }
        val reason = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val ended = CountDownLatch(1)
        val p = SignalPlayer(
            onEnded = { _, r -> reason.set(r); ended.countDown() },
            openSink = { sink },
            warn = {},
        )
        p.start(TestSignal.Sine1k, SignalLevel.Low)

        assertTrue("끝났다고 알려야 한다", ended.await(5, TimeUnit.SECONDS))
        assertTrue(
            "장치가 끊겼다고 적어야 한다: ${reason.get()}",
            reason.get()!!.contains("연결이 끊겨"),
        )
        // 앞서 받은 128 개는 원본과 같아야 한다.
        assertArrayEquals("받은 만큼은 원본과 같다", expected(128), sink.snapshot(), 1e-6f)
        p.stop()
    }

    /**
     * **덩어리를 다 못 보낸 채 사람이 멈추면, 남은 부분은 버린다.**
     *
     * 검증자가 「사용자가 이미 중지한 경우에는 나머지를 버리는 것이
     * 맞으므로 두 경우를 구분해야 한다」고 적었다. 멈춘 뒤에도 남은
     * 896개를 마저 밀어 넣으면 「멈추기」가 즉시 듣지 않는다.
     */
    @Test
    fun `보내는 도중 멈추면 남은 부분을 버린다`() {
        val entered = CountDownLatch(1)
        val hold = CountDownLatch(1)
        val sink = object : SignalSink {
            val calls = AtomicInteger()
            val taken = AtomicInteger()
            val released = CountDownLatch(1)
            override fun open(sampleRate: Int, frames: Int) = true
            override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
                val call = calls.incrementAndGet()
                if (call == 1) {
                    entered.countDown()
                    check(hold.await(5, TimeUnit.SECONDS))
                    taken.addAndGet(128)
                    return 128
                }
                taken.addAndGet(frames)
                return frames
            }
            override fun stop() = hold.countDown()
            override fun release() {
                released.countDown()
            }
        }
        val p = SignalPlayer(openSink = { sink }, warn = {})
        p.start(TestSignal.Sine1k, SignalLevel.Low)
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        p.stop() // stop() 이 hold 를 풀고, write 는 128 만 받고 돌아온다

        assertTrue("자원을 놓아야 한다", sink.released.await(5, TimeUnit.SECONDS))
        assertEquals("멈춘 뒤에는 더 밀어 넣지 않는다", 128, sink.taken.get())
        assertNull(p.playing)
    }
}
