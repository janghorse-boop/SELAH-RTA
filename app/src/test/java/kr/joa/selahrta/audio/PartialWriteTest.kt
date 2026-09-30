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

        /**
         * 출력은 **늘 두 채널**이다(2026-09-24 L/R 시험을 넣으며 그렇게 됐다).
         *
         * 그래서 한 덩어리의 「칸」 수는 프레임의 두 배이고, `write` 가 세는
         * 것은 칸이다. 이 시험이 파형을 칸 단위로 견주므로 둘을 갈라 둔다 —
         * 섞으면 절반만 견주고도 통과한다.
         */
        const val CHANNELS = 2
        const val FLOATS = FRAMES * CHANNELS
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

        override fun open(sampleRate: Int, frames: Int, channels: Int) = true

        @Synchronized
        override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
            val n = accept(calls.incrementAndGet(), frames)
            if (n > 0) {
                for (i in 0 until n) taken.add(buf[offset + i])
                if (taken.size >= FLOATS * 2) enough.countDown()
            }
            return n
        }

        override fun stop() {
            stopped = true
        }

        override fun release(): Boolean {
            released.countDown()
            return true
        }

        @Synchronized
        fun snapshot(): FloatArray = taken.toFloatArray()
    }

    /**
     * 1kHz 순음의 앞 [n] **칸**. 재생이 내야 할 원래 파형이다.
     *
     * **L·R 이 번갈아 든다.** 「양쪽」이라 두 칸에 같은 값이 들어가므로,
     * 칸 번호를 둘로 나눈 것이 프레임 번호다. 이 나눗셈을 빼먹으면 주파수가
     * 두 배로 보이고 — 그것이 바로 인터리브를 틀렸을 때 나는 증상이다.
     */
    private fun expected(n: Int): FloatArray = FloatArray(n) { i ->
        val frame = i / CHANNELS
        (DEFAULT_AMPLITUDE * rampGain(frame) * sin(2 * PI * 1000 * frame / SAMPLE_RATE)).toFloat()
    }

    /**
     * 시작 램프의 크기(2026-09-29, 지시서 §3).
     *
     * **소리는 0 에서 올라온다.** 첫 표본부터 제 크기로 내보내면 파형에
     * 수직인 벽이 생기고, 스피커는 그것을 「딱」으로 낸다.
     *
     * 이 시험이 보는 것은 **파형이 이어지는가**(표본이 빠지거나 겹치지
     * 않는가)이지 램프가 아니다. 그래서 원본 쪽에도 같은 램프를 곱해 두고
     * 나머지를 견준다. 램프 자체는 `SignalRampTest` 가 본다.
     *
     * **`SignalPlayer.RAMP_SECONDS` 와 같아야 한다.** 그쪽을 바꾸면 여기도
     * 바꾼다 — 그때 이 시험이 실패하는 것은 알림이지 고장이 아니다.
     */
    private fun rampGain(frame: Int): Double =
        ((frame + 1) / (SAMPLE_RATE * 0.030)).coerceAtMost(1.0)

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
        p.start(SignalRequest(TestSignal.Custom, DEFAULT_AMPLITUDE))
        assertTrue("두 덩어리는 나가야 한다", sink.enough.await(5, TimeUnit.SECONDS))
        p.stop()

        val got = sink.snapshot()
        assertTrue("충분히 받았어야 한다 (${got.size})", got.size >= FLOATS * 2)
        assertArrayEquals(
            "나간 파형이 원본과 같아야 한다",
            expected(FLOATS * 2),
            got.copyOf(FLOATS * 2),
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
        p.start(SignalRequest(TestSignal.Custom, DEFAULT_AMPLITUDE))

        assertTrue("끝났다고 알려야 한다", ended.await(5, TimeUnit.SECONDS))
        assertNull("내보내는 중이 아니어야 한다", p.playing)
        assertTrue("자원을 놓아야 한다", sink.released.await(5, TimeUnit.SECONDS))
        p.stop()
    }

    /**
     * **0 이 잠깐 나왔다가 다시 나아가면 세던 횟수를 되돌린다.**
     *
     * 검증자가 적은 회귀다 — 49회 0 → 양수 → 다시 0 이어도 정상 진행.
     * 되돌리지 않으면 **멀쩡한 출력이 잘린다.**
     */
    @Test
    fun `0 이 나왔다 다시 나아가면 횟수를 되돌린다`() {
        // 49회 0 → 한 번 받음 → 다시 49회 0 → 나머지 전부.
        val zeros = 49
        val sink = RecordingSink { call, offered ->
            when {
                call <= zeros -> 0
                call == zeros + 1 -> 128
                call <= zeros * 2 + 1 -> 0
                else -> offered
            }
        }
        val ended = java.util.concurrent.atomic.AtomicInteger(0)
        val p = SignalPlayer(onEnded = { _, _ -> ended.incrementAndGet() }, openSink = { sink }, warn = {})
        p.start(SignalRequest(TestSignal.Custom, DEFAULT_AMPLITUDE))
        assertTrue("두 덩어리는 나가야 한다", sink.enough.await(10, TimeUnit.SECONDS))
        p.stop()

        assertEquals("중간에 끝내면 안 된다", 0, ended.get())
        val got = sink.snapshot()
        assertArrayEquals(
            "0 을 건너뛴 뒤에도 파형이 이어져야 한다",
            expected(FLOATS * 2),
            got.copyOf(FLOATS * 2),
            1e-6f,
        )
    }

    /** 끝없이 0 이어도 **알림은 한 번뿐**이다. */
    @Test
    fun `0 이 이어져 끝나도 알림은 한 번뿐이다`() {
        val sink = RecordingSink { _, _ -> 0 }
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        val latch = CountDownLatch(1)
        val p = SignalPlayer(
            onEnded = { _, _ -> count.incrementAndGet(); latch.countDown() },
            openSink = { sink },
            warn = {},
        )
        p.start(SignalRequest(TestSignal.Custom, DEFAULT_AMPLITUDE))
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)
        p.stop()

        assertEquals("한 번만 알려야 한다", 1, count.get())
    }

    /** 0 을 기다리는 도중 사람이 멈추면 **알리지 않고** 끝난다. */
    @Test
    fun `0 을 기다리는 도중 멈추면 알리지 않는다`() {
        val started = CountDownLatch(1)
        val sink = RecordingSink { _, _ -> started.countDown(); 0 }
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        val p = SignalPlayer(onEnded = { _, _ -> count.incrementAndGet() }, openSink = { sink }, warn = {})
        p.start(SignalRequest(TestSignal.Custom, DEFAULT_AMPLITUDE))
        assertTrue(started.await(5, TimeUnit.SECONDS))

        p.stop()
        Thread.sleep(150)

        assertEquals("사람이 멈춘 것은 알림이 아니다", 0, count.get())
        assertNull(p.playing)
        assertTrue("자원을 놓아야 한다", sink.released.await(5, TimeUnit.SECONDS))
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
        p.start(SignalRequest(TestSignal.Custom, DEFAULT_AMPLITUDE))

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
     *
     * ## 2026-09-29 — 램프를 넣어도 여기서는 128 그대로다
     *
     * 지시서 §3 의 게인 램프를 넣었다. 멈출 때 소리를 30ms 에 걸쳐 0 까지
     * 내리고 그만큼을 더 내보낸다 — 파형 한가운데서 끊으면 그 자리가
     * 수직인 벽이 되어 「딱」 소리가 나기 때문이다.
     *
     * **그런데 이 시험에서는 램프가 나가지 않는다.** 여기 가짜 출력은
     * `stop()` 이 불려야만 `write` 에서 깨어나도록 만들어져 있다. `stop()`
     * 은 램프를 기다린 **뒤에** 출력을 멈추므로, 그리지 못하는 램프를
     * 기다리다 시간을 넘기고 예전처럼 끊는다.
     *
     * 그 퇴화는 설계대로다 — 기다림은 **소리를 곱게 끊으려는** 것이지
     * 지켜야 하는 값이 아니다. 램프가 실제로 그려지는 쪽은
     * `SignalRampTest` 가 본다.
     *
     * 검증자의 걱정은 그대로 지킨다: **이미 만들어 둔 제 크기의 나머지는
     * 어느 길로도 나가지 않는다.** 아래에서 그것을 직접 본다.
     */
    @Test
    fun `보내는 도중 멈추면 남은 부분을 버린다`() {
        val entered = CountDownLatch(1)
        val hold = CountDownLatch(1)
        val sink = object : SignalSink {
            val calls = AtomicInteger()
            val taken = AtomicInteger()
            /** 첫 128 칸 **뒤에** 나간 것들. 이것이 램프여야 한다. */
            val tail = ArrayList<Float>()
            val released = CountDownLatch(1)
            override fun open(sampleRate: Int, frames: Int, channels: Int) = true

            @Synchronized
            override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
                val call = calls.incrementAndGet()
                if (call == 1) {
                    entered.countDown()
                    check(hold.await(5, TimeUnit.SECONDS))
                    taken.addAndGet(128)
                    return 128
                }
                for (i in 0 until frames) tail.add(buf[offset + i])
                taken.addAndGet(frames)
                return frames
            }

            @Synchronized
            fun tailCopy(): List<Float> = ArrayList(tail)
            override fun stop() = hold.countDown()
            override fun release(): Boolean {
                released.countDown()
                return true
            }
        }
        val p = SignalPlayer(openSink = { sink }, warn = {})
        p.start(SignalRequest(TestSignal.Custom, DEFAULT_AMPLITUDE))
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        p.stop() // stop() 이 hold 를 풀고, write 는 128 만 받고 돌아온다

        assertTrue("자원을 놓아야 한다", sink.released.await(5, TimeUnit.SECONDS))

        assertEquals("멈춘 뒤에는 더 밀어 넣지 않는다", 128, sink.taken.get())
        // **만들어 둔 나머지 1920 칸이 한 개도 나가면 안 된다.** 그것이
        // 검증자가 막으라고 한 일이고, 램프를 넣은 뒤에도 그대로다.
        assertTrue(
            "멈춘 뒤 ${sink.tailCopy().size} 칸이 더 나갔다",
            sink.tailCopy().isEmpty(),
        )
        assertNull(p.playing)
    }
}
