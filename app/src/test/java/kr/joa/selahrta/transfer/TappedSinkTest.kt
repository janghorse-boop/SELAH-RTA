package kr.joa.selahrta.transfer

import kr.joa.selahrta.audio.SignalSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **조용히 틀리기 쉬운 자리를 못박는다.**
 *
 * `write()` 는 일부만 쓸 수 있다. 넘긴 버퍼 전체를 기준으로 적으면
 * 기준이 실제보다 앞서 가고, 그만큼 지연이 **작게** 나온다. 화면에는
 * 아무 표시도 안 난다.
 */
class TappedSinkTest {

    /** 요청의 일부만 받아들이는 가짜 출력. */
    private class PartialSink(private val accept: (Int) -> Int) : SignalSink {
        val got = mutableListOf<Float>()
        override fun open(sampleRate: Int, frames: Int, channels: Int) = true
        override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
            val n = accept(frames)
            for (i in 0 until maxOf(n, 0)) got.add(buf[offset + i])
            return n
        }
        override fun stop() {}
        override fun release() = true
    }

    private fun collector(): Pair<MutableList<Float>, (FloatArray, Int, Int) -> Unit> {
        val out = mutableListOf<Float>()
        return out to { b, o, c -> for (i in 0 until c) out.add(b[o + i]) }
    }

    /**
     * 스테레오 인터리브를 모노로 평균한다. `(L+R)/2` 다 — 상관은 크기에
     * 무관하지만, Magnitude(H1) 는 기준의 크기에 그대로 걸린다(독립 검토
     * ①). 「합친다」가 아니라 「평균한다」인 까닭이 그 때문이다.
     */
    @Test
    fun `인터리브를 모노로 평균한다`() {
        val (mono, cb) = collector()
        val sink = TappedSink(PartialSink { it }, cb)
        sink.write(floatArrayOf(1f, 2f, 3f, 4f), 0, 4)
        assertArrayEquals(floatArrayOf(1.5f, 3.5f), mono.toFloatArray(), 1e-6f)
    }

    /** **이것이 이번에 막는 자리다.** */
    @Test
    fun `받아들여진 칸만 적는다`() {
        val (mono, cb) = collector()
        val sink = TappedSink(PartialSink { 2 }, cb)
        sink.write(floatArrayOf(1f, 2f, 3f, 4f), 0, 4)
        assertArrayEquals(floatArrayOf(1.5f), mono.toFloatArray(), 1e-6f)
    }

    /** 홀수 칸으로 끊겨도 프레임 경계를 잃지 않는다. */
    @Test
    fun `홀수 칸으로 나누어 써도 같은 기준이 나온다`() {
        val (mono, cb) = collector()
        val sink = TappedSink(PartialSink { 1 }, cb)
        val buf = floatArrayOf(1f, 2f, 3f, 4f)
        for (i in 0 until 4) sink.write(buf, i, 1)
        assertArrayEquals(floatArrayOf(1.5f, 3.5f), mono.toFloatArray(), 1e-6f)
    }

    /**
     * **독립 검토가 요구한 회귀 시험.** `SignalPlayer` 의 `Both` 모드는
     * 양쪽에 **같은 신호 `s`** 를 싣는다 — 그래서 기준은 평균을 거쳐
     * **정확히 `s`** 가 되어야 한다(합이면 `2s`, Magnitude 가 6.02dB
     * 밀린다). 이것이 Magnitude 0dB 를 지키는 그물이다.
     */
    @Test
    fun `양쪽에 같은 신호를 실으면 기준이 그 신호 그대로 나온다`() {
        val (mono, cb) = collector()
        val sink = TappedSink(PartialSink { it }, cb)
        sink.write(floatArrayOf(0.5f, 0.5f, -0.25f, -0.25f), 0, 4)
        assertArrayEquals(floatArrayOf(0.5f, -0.25f), mono.toFloatArray(), 1e-6f)
    }

    /**
     * `channels` 를 보지 않고 무조건 2칸=1프레임으로 묶으므로, 모노로
     * 열리면 무관한 표본 둘이 하나로 합쳐진다 — 그 전에 막아야 한다.
     */
    @Test(expected = IllegalArgumentException::class)
    fun `모노로 열면 거부한다`() {
        val sink = TappedSink(PartialSink { it }) { _, _, _ -> }
        sink.open(48_000, 1024, 1)
    }

    @Test
    fun `오류면 아무것도 적지 않는다`() {
        val (mono, cb) = collector()
        val sink = TappedSink(PartialSink { SignalSink.ERROR_DEAD_OBJECT }, cb)
        sink.write(floatArrayOf(1f, 2f), 0, 2)
        assertTrue(mono.isEmpty())
    }

    @Test
    fun `나머지 호출을 그대로 넘긴다`() {
        val inner = PartialSink { it }
        val sink = TappedSink(inner) { _, _, _ -> }
        assertTrue(sink.open(48_000, 1024, 2))
        sink.write(floatArrayOf(5f, 6f), 0, 2)
        assertEquals(listOf(5f, 6f), inner.got)
        sink.stop()
        assertTrue(sink.release())
    }

    /** 멈췄다 다시 열면 반 프레임이 남아 있으면 안 된다. */
    @Test
    fun `다시 열면 남은 반 프레임을 버린다`() {
        val (mono, cb) = collector()
        val sink = TappedSink(PartialSink { 1 }, cb)
        sink.write(floatArrayOf(1f), 0, 1)
        sink.open(48_000, 1024, 2)
        sink.write(floatArrayOf(10f, 20f), 0, 1)
        sink.write(floatArrayOf(10f, 20f), 1, 1)
        assertArrayEquals(floatArrayOf(15f), mono.toFloatArray(), 1e-6f)
    }

    /**
     * `stop()` 뒤 `open()` 없이 바로 이어 써도 반 프레임이 남아 있으면 안
     * 된다. `SignalSink.stop()` 의 계약은 「그 뒤 write() 가 open() 없이
     * 다시 올 수 없다」를 보장하지 않는다 — 지금은 SignalPlayer 가 늘
     * stop() 뒤 release() 로 가서 드러나지 않을 뿐이다.
     */
    @Test
    fun `멈췄다 다시 써도 남은 반 프레임을 버린다`() {
        val (mono, cb) = collector()
        val sink = TappedSink(PartialSink { 1 }, cb)
        sink.write(floatArrayOf(1f), 0, 1)
        sink.stop()
        sink.write(floatArrayOf(10f, 20f), 0, 1)
        sink.write(floatArrayOf(10f, 20f), 1, 1)
        assertArrayEquals(floatArrayOf(15f), mono.toFloatArray(), 1e-6f)
    }
}
