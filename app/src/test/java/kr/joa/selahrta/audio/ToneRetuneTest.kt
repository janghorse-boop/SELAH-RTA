package kr.joa.selahrta.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.asin

/**
 * **주파수를 바꿔도 소리가 끊기지 않는가**(담당자 지시 2026-09-29:
 * 「손가락으로 슬라이더를 움직일 때 아주 부드럽게」).
 *
 * ## 무엇이 뻑뻑했나
 *
 * 예전에는 주파수가 바뀔 때마다 `start()` 가 불렸다. 그 안에서
 * **AudioTrack 을 닫고 다시 열었다** — 슬라이더를 끌면 손가락 움직임마다
 * 그 일이 일어나, 화면이 버벅이고 소리가 뚝뚝 끊겼다.
 *
 * ## 위상이 이어져야 「틱」이 안 난다
 *
 * 다시 열지 않더라도, 파형을 `sin(2π·f·t)` 로 그리면 f 가 바뀌는 순간
 * **같은 t 에서 각이 갑자기 달라진다.** 그 자리에서 파형이 수직으로
 * 튀고 스피커는 그것을 「틱」으로 낸다.
 *
 * 그래서 표본마다 각을 조금씩 **더해 간다.** 주파수가 바뀌어도 각은
 * 이어지고, 소리는 미끄러지듯 따라온다.
 */
class ToneRetuneTest {

    /** 나간 표본을 모아 두는 출력. 파형을 나중에 들여다본다. */
    private class RecordingSink : SignalSink {
        val samples = ArrayList<Float>()
        val writes = AtomicInteger()
        private val lock = Any()

        override fun open(sampleRate: Int, frames: Int, channels: Int) = true

        override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
            synchronized(lock) {
                // 왼쪽 채널만 본다. 양쪽이면 같은 값이다.
                var i = offset
                while (i < offset + frames) {
                    samples.add(buf[i])
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

    private fun player(sink: RecordingSink) = SignalPlayer(
        onEnded = { _, _ -> },
        openSink = { sink },
        warn = {},
    )

    /** 표본이 [n] 개 넘게 쌓일 때까지 기다린다. */
    private fun await(sink: RecordingSink, n: Int) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (sink.snapshot().size < n && System.nanoTime() < until) Thread.sleep(5)
        assertTrue("표본이 안 쌓인다(${sink.snapshot().size}개)", sink.snapshot().size >= n)
    }

    // ── 다시 열지 않는다 ──────────────────────────────────

    /**
     * **이것이 뻑뻑함의 뿌리였다.** 주파수를 바꿀 때 출력을 새로 열면
     * 그 사이에 소리가 끊기고 화면도 멈춘다.
     */
    @Test
    fun `주파수를 바꿔도 출력을 다시 열지 않는다`() {
        val sink = RecordingSink()
        val opens = AtomicInteger()
        val p = SignalPlayer(
            onEnded = { _, _ -> },
            openSink = { opens.incrementAndGet(); sink },
            warn = {},
        )
        p.start(SignalRequest(TestSignal.Custom, DEFAULT_AMPLITUDE, toneHz = 1_000.0))
        await(sink, 512)
        assertEquals(1, opens.get())

        repeat(50) { p.retune(1_000.0 + it * 10.0) }
        await(sink, 4_096)
        assertEquals("주파수를 바꾸며 출력을 다시 열었다", 1, opens.get())
        p.stop()
    }

    @Test
    fun `순음이 아니면 바꿀 것이 없다`() {
        val sink = RecordingSink()
        val p = player(sink)
        p.start(SignalRequest(TestSignal.Pink, DEFAULT_AMPLITUDE))
        await(sink, 256)
        assertFalse("잡음인데 주파수를 바꿨다고 한다", p.retune(500.0))
        p.stop()
    }

    @Test
    fun `내보내는 중이 아니면 바꿀 것이 없다`() {
        val sink = RecordingSink()
        val p = player(sink)
        assertFalse(p.retune(500.0))
    }

    // ── 위상이 이어지는가 ────────────────────────────────

    /**
     * **파형에 수직으로 튀는 자리가 없어야 한다.**
     *
     * 이웃한 두 표본의 차이를 본다. 48kHz 에서 20kHz 순음이라도 한 표본
     * 사이의 변화는 진폭의 2.6배를 넘지 못한다(`2·sin(π·f/fs)`).
     * 위상이 튀면 그 한계를 넘는 자리가 생긴다.
     *
     * 여기서는 1kHz 에서 8kHz 까지 잘게 옮기며 그런 자리가 **하나도**
     * 없는지 본다.
     */
    @Test
    fun `주파수를 옮겨도 파형이 튀지 않는다`() {
        val sink = RecordingSink()
        val p = player(sink)
        p.start(SignalRequest(TestSignal.Custom, MAX_AMPLITUDE, toneHz = 1_000.0))
        await(sink, 1_024)

        // 1kHz → 8kHz 를 40번에 나눠 옮긴다(슬라이더를 끄는 것과 같다).
        repeat(40) {
            p.retune(1_000.0 + it * 175.0)
            Thread.sleep(6)
        }
        await(sink, 24_000)
        p.stop()

        val v = sink.snapshot()
        // 가장 높은 주파수(8kHz)에서 한 표본 사이에 날 수 있는 최대 변화.
        val maxStep = 2.0 * kotlin.math.sin(Math.PI * 8_000.0 / 48_000.0) * MAX_AMPLITUDE
        var worst = 0.0
        var at = -1
        for (i in 1 until v.size) {
            val d = abs(v[i] - v[i - 1]).toDouble()
            if (d > worst) { worst = d; at = i }
        }
        assertTrue(
            "표본 %d 에서 %.4f 만큼 튀었다(한계 %.4f) — 위상이 안 이어진다"
                .format(at, worst, maxStep * 1.2),
            worst <= maxStep * 1.2,
        )
    }

    /**
     * **바꾼 주파수가 실제로 나와야 한다.** 부드럽기만 하고 안 바뀌면
     * 쓸모가 없다.
     *
     * 끝부분에서 영교차 사이의 간격을 세어 주파수를 되짚는다.
     */
    @Test
    fun `바꾼 주파수가 실제로 나온다`() {
        val sink = RecordingSink()
        val p = player(sink)
        p.start(SignalRequest(TestSignal.Custom, MAX_AMPLITUDE, toneHz = 1_000.0))
        await(sink, 2_048)
        p.retune(4_000.0)
        Thread.sleep(200)
        await(sink, 24_000)
        // **멈추기 전에 떠 둔다**(2026-09-29 램프 도입).
        //
        // 멈추면 소리를 30ms 에 걸쳐 0 으로 내린다. 그 꼬리에는 0 이
        // 줄지어 들어 있어 영교차가 세어지지 않는다 — 꼬리째 세면
        // 4000Hz 가 3750Hz 로 보인다(실제로 그렇게 나왔다).
        val v = sink.snapshot()
        p.stop()
        val tail = v.subList(maxOf(0, v.size - 9_600), v.size)   // 마지막 0.2초
        var crossings = 0
        for (i in 1 until tail.size) {
            if (tail[i - 1] < 0f && tail[i] >= 0f) crossings++
        }
        val hz = crossings * 48_000.0 / tail.size
        assertEquals("4kHz 로 바꿨는데 %.0fHz 가 나온다".format(hz), 4_000.0, hz, 200.0)
    }
}
