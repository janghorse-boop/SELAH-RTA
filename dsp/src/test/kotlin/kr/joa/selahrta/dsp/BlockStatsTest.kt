package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockStatsTest {

    private fun floats(vararg v: Double) = FloatArray(v.size) { v[it].toFloat() }

    @Test
    fun `풀스케일 사인파의 RMS 와 피크가 이론값과 맞다`() {
        val n = SignalGenerator.wholeCycleLength(1000.0, 48_000, 4096)
        val d = SignalGenerator.sine(1000.0, 48_000, n, 1.0)
        val f = FloatArray(n) { d[it].toFloat() }
        val s = blockStats(f, n)
        assertEquals(0.70710678, s.rms, 1e-5)
        assertEquals(1.0, s.peakAbs, 1e-5)
    }

    @Test
    fun `피크는 부호와 무관하게 절대값이다`() {
        val s = blockStats(floats(0.1, -0.9, 0.3), 3)
        assertEquals(0.9, s.peakAbs, 1e-6)
    }

    @Test
    fun `풀스케일에 닿으면 잘린 것으로 본다`() {
        // 16비트 최대값 32767/32768 도 잘린 것이다. 정확히 1.0 만 세면 놓친다.
        val s = blockStats(floats(0.5, 32767.0 / 32768.0, -0.2), 3)
        assertTrue(s.clipped)
        assertEquals(1, s.clippedSamples)
    }

    @Test
    fun `한 샘플만 잘려도 알린다`() {
        // 정도의 문제가 아니다 — 잘린 파형은 RMS 가 낮게 나오고
        // FFT 에 없던 고조파가 생긴다. 그 값을 믿으면 안 된다.
        val big = FloatArray(1000) { 0.1f }
        big[500] = 1.0f
        val s = blockStats(big, 1000)
        assertTrue(s.clipped)
        assertEquals(1, s.clippedSamples)
    }

    @Test
    fun `잘리지 않은 큰 신호는 잘렸다고 하지 않는다`() {
        val s = blockStats(floats(0.99, -0.99, 0.5), 3)
        assertFalse(s.clipped)
        assertEquals(0, s.clippedSamples)
    }

    @Test
    fun `빈 덩어리는 0 이고 터지지 않는다`() {
        val s = blockStats(FloatArray(10), 0)
        assertEquals(0.0, s.peakAbs, 0.0)
        assertEquals(0.0, s.rms, 0.0)
        assertFalse(s.clipped)
    }

    @Test
    fun `버퍼보다 큰 frames 는 거부한다`() {
        // 버퍼를 재사용하므로 frames 를 잘못 넘기면 지난 덩어리의 꼬리를
        // 이번 값에 섞어 읽는다. 조용히 틀린 값이 나오는 길이라 막는다.
        assertThrows(IllegalArgumentException::class.java) { blockStats(FloatArray(10), 11) }
        assertThrows(IllegalArgumentException::class.java) { blockStats(FloatArray(10), -1) }
    }

    @Test
    fun `버퍼 뒤쪽의 옛 값은 계산에 들어가지 않는다`() {
        // 재사용 버퍼의 핵심 계약. 앞 4개만 유효한데 뒤에 큰 값이 남아 있다.
        val buf = floats(0.1, 0.1, 0.1, 0.1, 0.95, 0.95)
        val s = blockStats(buf, 4)
        assertEquals(0.1, s.peakAbs, 1e-6)
        assertEquals(0.1, s.rms, 1e-6)
    }
}
