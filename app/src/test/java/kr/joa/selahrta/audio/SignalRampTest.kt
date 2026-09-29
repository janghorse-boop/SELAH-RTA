package kr.joa.selahrta.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * **틀고 끌 때 「딱」 소리가 나지 않는가**(지시서 §3: 20~50ms 게인 램프).
 *
 * ## 무엇이 딱 소리를 내나
 *
 * 지금은 첫 표본부터 최대 진폭이 나간다. 0 에서 갑자기 0.5 로 뛰면
 * 파형에 수직인 벽이 생기고, 스피커는 그 벽을 「딱」으로 낸다. 멈출
 * 때도 같다 — 파형 한가운데서 뚝 끊는다.
 *
 * 슬라이더로 주파수를 옮기는 쪽은 위상을 이어 이미 매끄럽다
 * ([ToneRetuneTest]). **시작과 정지만 그대로였다.**
 *
 * ## 어떻게 재나
 *
 * 순음을 쓰고 **포락선**(작은 창마다의 최대 절대값)을 본다. 잡음은
 * 표본마다 값이 튀어 한 점만 봐서는 아무것도 못 가른다.
 */
class SignalRampTest {

    private val amp = MAX_AMPLITUDE
    /** 1ms = 48 표본. 5ms 창으로 본다. */
    private val win = 240

    private class RecordingSink : SignalSink {
        val samples = ArrayList<Float>()
        val writes = AtomicInteger()
        private val lock = Any()

        override fun open(sampleRate: Int, frames: Int, channels: Int) = true

        override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
            synchronized(lock) {
                var i = offset
                while (i < offset + frames) {
                    samples.add(buf[i])   // 왼쪽 채널만. 양쪽이면 같은 값이다.
                    i += 2
                }
            }
            writes.incrementAndGet()
            return frames
        }

        override fun stop() = Unit
        override fun release() = true

        fun snapshot(): List<Float> = synchronized(lock) { ArrayList(samples) }
    }

    private fun await(sink: RecordingSink, n: Int) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (sink.snapshot().size < n && System.nanoTime() < until) Thread.sleep(5)
        assertTrue("표본이 안 쌓인다(${sink.snapshot().size}개)", sink.snapshot().size >= n)
    }

    /** [win] 표본마다의 최대 절대값. */
    private fun envelope(v: List<Float>): List<Double> =
        v.chunked(win).map { c -> c.maxOf { abs(it).toDouble() } }

    private fun play(sink: RecordingSink): SignalPlayer {
        val p = SignalPlayer(onEnded = { _, _ -> }, openSink = { sink }, warn = {})
        p.start(SignalRequest(TestSignal.Custom, amp, toneHz = 1_000.0))
        return p
    }

    // ── 틀 때 ────────────────────────────────────────────

    /**
     * **첫 5ms 는 아직 작아야 한다.** 30ms 램프라면 그 창이 끝날 때
     * 겨우 1/6 에 이른다.
     */
    @Test
    fun `틀 때 소리가 서서히 커진다`() {
        val sink = RecordingSink()
        val p = play(sink)
        await(sink, win * 12)
        p.stop()

        val e = envelope(sink.snapshot())
        assertTrue(
            "첫 5ms 가 벌써 %.3f 다(진폭 %.3f) — 램프 없이 튀어나왔다".format(e[0], amp),
            e[0] < amp * 0.35,
        )
    }

    /** **그리고 50ms 안에는 다 올라와야 한다.** 램프가 길면 소리가 늦는다. */
    @Test
    fun `50ms 안에 제 크기가 된다`() {
        val sink = RecordingSink()
        val p = play(sink)
        await(sink, win * 12)
        p.stop()

        val e = envelope(sink.snapshot())
        // 창 10 = 50~55ms.
        assertTrue(
            "50ms 뒤에도 %.3f 뿐이다(진폭 %.3f)".format(e[10], amp),
            e[10] > amp * 0.9,
        )
    }

    // ── 끌 때 ────────────────────────────────────────────

    /**
     * **끊지 말고 내린다.**
     *
     * 이것이 안 되면 파형이 한가운데서 뚝 끊긴다 — 예배당에서 순음을
     * 끌 때마다 「딱」이 따라 나온다.
     */
    @Test
    fun `끌 때 소리가 서서히 작아진다`() {
        val sink = RecordingSink()
        val p = play(sink)
        await(sink, win * 20)
        p.stop()
        // 멈춘 뒤에도 마지막 덩어리가 나갈 틈을 준다.
        Thread.sleep(200)

        val v = sink.snapshot()
        // **마지막 30ms 가 램프다.** 다 내린 뒤의 0 을 덧붙이지 않으므로
        // (그러면 「멈추기」가 그만큼 늦게 듣는다) 꼬리는 램프에서 끝난다.
        val tail = v.takeLast(48 * 30)
        val e = envelope(tail)
        assertTrue("꼬리가 너무 짧다(${e.size}창)", e.size >= 5)

        // 창마다 작아져야 한다. 「서서히」가 뜻하는 것이 이것이다.
        for (i in 1 until e.size) {
            assertTrue(
                "창 $i 에서 다시 커졌다(%.4f → %.4f)".format(e[i - 1], e[i]),
                e[i] <= e[i - 1] + 1e-4,
            )
        }
        assertTrue(
            "끝까지 내려오지 않았다(처음 %.4f → 끝 %.4f)".format(e.first(), e.last()),
            e.last() < e.first() * 0.45,
        )
        assertTrue(
            "마지막 표본이 %.5f 다 — 0 에서 끝나지 않았다".format(v.last()),
            abs(v.last()) < amp * 0.05,
        )

        // **내리는 데 걸린 시간도 본다**(0.5 초짜리 램프도 위 검사들을
        // 통과했다 — 모양만 보고 길이를 안 봤기 때문이다). 램프가 길면
        // 「멈추기」를 눌러도 소리가 한참 남는다.
        //
        // 제 크기였던 마지막 자리부터 끝까지를 센다. 1kHz 순음은 1ms 마다
        // 마루를 지나므로 그 자리는 내리기 시작한 지점에서 몇 밀리초
        // 안이다.
        val lastFull = v.indexOfLast { abs(it) > amp * 0.9 }
        assertTrue("제 크기로 난 적이 없다", lastFull >= 0)
        val fadeSamples = v.size - lastFull - 1
        assertTrue(
            "내리는 데 %.1fms 걸렸다 — 50ms 를 넘는다".format(fadeSamples / 48.0),
            fadeSamples <= 48 * 55,
        )
    }

    /**
     * **내리기 전에는 제 크기였어야 한다.** 위 시험만 있으면 「처음부터
     * 아무 소리도 안 냈다」도 통과한다.
     */
    @Test
    fun `내리기 전까지는 제 크기로 났다`() {
        val sink = RecordingSink()
        val p = play(sink)
        await(sink, win * 20)
        p.stop()
        Thread.sleep(200)

        val e = envelope(sink.snapshot())
        assertTrue(
            "가장 큰 창이 %.3f 뿐이다(진폭 %.3f)".format(e.max(), amp),
            e.max() > amp * 0.9,
        )
    }
}
