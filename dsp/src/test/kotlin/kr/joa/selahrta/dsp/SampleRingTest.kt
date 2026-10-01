package kr.joa.selahrta.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SampleRingTest {

    @Test
    fun `가장 최근 것을 시간 순으로 돌려준다`() {
        val ring = SampleRing(4)
        ring.write(floatArrayOf(1f, 2f, 3f, 4f, 5f), 0, 5)
        val out = DoubleArray(4)
        assertEquals(4, ring.snapshot(out))
        assertArrayEquals(doubleArrayOf(2.0, 3.0, 4.0, 5.0), out, 1e-9)
    }

    @Test
    fun `아직 덜 찼으면 채운 수를 돌려주고 앞을 0 으로 둔다`() {
        val ring = SampleRing(4)
        ring.write(floatArrayOf(7f, 8f), 0, 2)
        val out = DoubleArray(4)
        assertEquals(2, ring.snapshot(out))
        // 뒤쪽 두 칸에 자료가 오고 앞은 0 이다 — 뜬 창의 **끝**이 지금이다.
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 7.0, 8.0), out, 1e-9)
    }

    @Test
    fun `offset 을 지킨다`() {
        val ring = SampleRing(3)
        ring.write(floatArrayOf(9f, 1f, 2f, 3f), 1, 3)
        val out = DoubleArray(3)
        ring.snapshot(out)
        assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0), out, 1e-9)
    }

    @Test
    fun `들어온 총 개수를 센다`() {
        val ring = SampleRing(4)
        ring.write(FloatArray(10), 0, 10)
        assertEquals(10L, ring.written)
    }

    @Test
    fun `clear 하면 비고 센 수도 0 이 된다`() {
        val ring = SampleRing(4)
        ring.write(floatArrayOf(1f, 2f), 0, 2)
        ring.clear()
        val out = DoubleArray(4)
        assertEquals(0, ring.snapshot(out))
        assertEquals(0L, ring.written)
    }
}
