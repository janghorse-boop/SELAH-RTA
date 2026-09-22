package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * **고른 채널만 정확히 나오는가.**
 *
 * USB 오디오 지시서 5장(채널 선택)과 17장 DoD 「선택 채널이 FFT 에
 * 정확히 매핑됨」의 바탕이다. 여기가 한 칸이라도 밀리면 다른 마이크의
 * 소리를 그 마이크의 보정값으로 재게 된다.
 */
class InterleaveTest {

    @Test
    fun `네 채널에서 고른 것만 뽑는다`() {
        // 프레임마다 [10, 20, 30, 40], [11, 21, 31, 41], ...
        val frames = 5
        val ch = 4
        val src = FloatArray(frames * ch) { i ->
            val f = i / ch
            val c = i % ch
            (10 * (c + 1) + f).toFloat()
        }
        val dst = FloatArray(frames)

        for (c in 0 until ch) {
            val n = deinterleave(src, src.size, ch, c, dst)
            assertEquals(frames, n)
            for (f in 0 until frames) {
                assertEquals("ch$c frame$f", (10 * (c + 1) + f).toFloat(), dst[f], 0f)
            }
        }
    }

    @Test
    fun `한 채널이면 그대로 베낀다`() {
        val src = floatArrayOf(1f, 2f, 3f, 4f)
        val dst = FloatArray(4)
        assertEquals(4, deinterleave(src, 4, 1, 0, dst))
        assertEquals(listOf(1f, 2f, 3f, 4f), dst.toList())
    }

    /**
     * **모자란 꼬리는 버린다.**
     *
     * 0 으로 채우면 없던 「툭」 소리가 생기고, 그것이 Peak 로 잡힌다.
     */
    @Test
    fun `반쯤 찬 프레임은 버린다`() {
        val ch = 3
        // 프레임 둘 + 한 표본
        val src = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f)
        val dst = FloatArray(4)
        val n = deinterleave(src, 7, ch, 0, dst)
        assertEquals("온전한 프레임만 센다", 2, n)
        assertEquals(1f, dst[0], 0f)
        assertEquals(4f, dst[1], 0f)
        assertEquals("남은 자리는 건드리지 않는다", 0f, dst[2], 0f)
    }

    @Test
    fun `읽은 만큼만 본다`() {
        val src = floatArrayOf(1f, 2f, 3f, 4f, 9f, 9f)
        val dst = FloatArray(3)
        // 뒤의 9 들은 이번에 안 읽힌 자리다.
        val n = deinterleave(src, 4, 2, 1, dst)
        assertEquals(2, n)
        assertEquals(2f, dst[0], 0f)
        assertEquals(4f, dst[1], 0f)
    }

    @Test
    fun `말이 안 되는 요청은 막는다`() {
        val src = FloatArray(8)
        val dst = FloatArray(4)
        assertNotNull(runCatching { deinterleave(src, 8, 0, 0, dst) }.exceptionOrNull())
        assertNotNull(runCatching { deinterleave(src, 8, 2, 2, dst) }.exceptionOrNull())
        assertNotNull(runCatching { deinterleave(src, 8, 2, -1, dst) }.exceptionOrNull())
        assertNotNull(runCatching { deinterleave(src, 9, 2, 0, dst) }.exceptionOrNull())
        assertNotNull(runCatching { deinterleave(src, -1, 2, 0, dst) }.exceptionOrNull())
        // 받을 곳이 작다
        assertNotNull(runCatching { deinterleave(src, 8, 1, 0, FloatArray(3)) }.exceptionOrNull())
    }

    @Test
    fun `프레임과 표본을 헷갈리지 않는다`() {
        assertEquals(4096, framesToSamples(1024, 4))
        assertEquals(1024, framesToSamples(1024, 1))
        assertNotNull(runCatching { framesToSamples(-1, 2) }.exceptionOrNull())
        assertNotNull(runCatching { framesToSamples(10, 0) }.exceptionOrNull())
    }

    /**
     * **다른 채널이 섞여 들어오면 주파수가 어긋난다.**
     *
     * 채널 0 에 1kHz, 채널 1 에 무음을 넣고 채널 0 을 뽑는다. 제대로
     * 뽑았으면 원래 사인과 같아야 한다. 한 칸이라도 밀리면 진폭이
     * 반으로 떨어지고 없던 성분이 생긴다.
     */
    @Test
    fun `뽑아낸 파형이 원래 파형과 같다`() {
        val fs = 48_000
        val hz = 1_000.0
        val frames = 4_096
        val mono = FloatArray(frames) { sin(2 * PI * hz * it / fs).toFloat() }

        // ch0 = 사인, ch1 = 무음
        val src = FloatArray(frames * 2)
        for (f in 0 until frames) {
            src[f * 2] = mono[f]
            src[f * 2 + 1] = 0f
        }

        val dst = FloatArray(frames)
        assertEquals(frames, deinterleave(src, src.size, 2, 0, dst))

        var maxDiff = 0.0
        for (f in 0 until frames) maxDiff = maxOf(maxDiff, abs(dst[f] - mono[f]).toDouble())
        assertEquals("원래 파형과 달라졌다", 0.0, maxDiff, 0.0)

        // 무음 채널을 뽑으면 무음이어야 한다.
        assertEquals(frames, deinterleave(src, src.size, 2, 1, dst))
        assertEquals(0.0, dst.maxOf { abs(it) }.toDouble(), 0.0)
    }
}
