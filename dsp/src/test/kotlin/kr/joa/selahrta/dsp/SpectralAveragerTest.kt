package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class SpectralAveragerTest {

    private val n = 1024

    private fun tone(size: Int, bin: Int, amp: Double = 1.0) =
        DoubleArray(size) { amp * sin(2.0 * PI * bin * it / size) }

    @Test
    fun `칸 수는 FFT 길이의 절반 더하기 하나다`() {
        assertEquals(n / 2 + 1, SpectralAverager(n).bins)
    }

    @Test
    fun `블록을 더하면 수가 는다`() {
        val a = SpectralAverager(n)
        val x = tone(n, 64)
        assertEquals(0, a.count)
        a.addBlock(x, 0, x, 0)
        a.addBlock(x, 0, x, 0)
        assertEquals(2, a.count)
    }

    @Test
    fun `reset 하면 수와 누적이 비워진다`() {
        val a = SpectralAverager(n)
        val x = tone(n, 64)
        a.addBlock(x, 0, x, 0)
        a.reset()
        assertEquals(0, a.count)
        assertEquals(0.0, a.sxx.sum(), 1e-12)
        assertEquals(0.0, a.sxyRe.sum(), 1e-12)
    }

    /** 순음은 제 칸에 가장 큰 에너지를 남긴다. */
    @Test
    fun `순음이 제 칸에 앉는다`() {
        val a = SpectralAverager(n)
        val x = tone(n, 64)
        a.addBlock(x, 0, x, 0)
        val peak = a.sxx.indices.maxByOrNull { a.sxx[it] }
        assertEquals(64, peak)
    }

    /** 같은 신호를 넣으면 Sxx 와 Syy 가 같고, Sxy 는 실수축에 선다. */
    @Test
    fun `같은 신호면 Sxx 와 Syy 가 같고 Sxy 는 실수다`() {
        val a = SpectralAverager(n)
        val x = tone(n, 64)
        a.addBlock(x, 0, x, 0)
        for (i in 0 until a.bins) {
            assertEquals("bin $i", a.sxx[i], a.syy[i], 1e-9)
            assertEquals("bin $i 의 Sxy 허수", 0.0, a.sxyIm[i], 1e-9)
            assertTrue("bin $i 의 Sxy 실수가 음수다", a.sxyRe[i] >= -1e-12)
        }
    }

    /** 측정이 두 배면 Sxy 도 두 배, Syy 는 네 배다. */
    @Test
    fun `크기가 곱해지면 누적도 그만큼 는다`() {
        val x = tone(n, 64)
        val y = DoubleArray(n) { x[it] * 2.0 }

        val one = SpectralAverager(n).apply { addBlock(x, 0, x, 0) }
        val two = SpectralAverager(n).apply { addBlock(x, 0, y, 0) }

        val b = 64
        assertEquals(one.sxx[b], two.sxx[b], 1e-9)
        assertEquals(one.sxyRe[b] * 2.0, two.sxyRe[b], 1e-6)
        assertEquals(one.syy[b] * 4.0, two.syy[b], 1e-6)
    }

    /** offset 을 지킨다 — 긴 버퍼에서 조각을 떠서 넣는다. */
    @Test
    fun `offset 을 지킨다`() {
        val long = DoubleArray(n * 2)
        val x = tone(n, 64)
        System.arraycopy(x, 0, long, n, n)

        val direct = SpectralAverager(n).apply { addBlock(x, 0, x, 0) }
        val sliced = SpectralAverager(n).apply { addBlock(long, n, long, n) }

        for (i in 0 until direct.bins) {
            assertEquals("bin $i", direct.sxx[i], sliced.sxx[i], 1e-9)
        }
    }

    /** 관계없는 잡음끼리는 Sxy 가 Sxx·Syy 에 견주어 작다. */
    @Test
    fun `관계없는 잡음끼리는 상호 스펙트럼이 작다`() {
        val rng = Random(11)
        val a = SpectralAverager(n)
        repeat(32) {
            val x = DoubleArray(n) { rng.nextDouble() * 2 - 1 }
            val y = DoubleArray(n) { rng.nextDouble() * 2 - 1 }
            a.addBlock(x, 0, y, 0)
        }
        val b = n / 4
        val gamma2 = (a.sxyRe[b] * a.sxyRe[b] + a.sxyIm[b] * a.sxyIm[b]) /
            (a.sxx[b] * a.syy[b])
        assertTrue("관계없는 잡음인데 상관이 높다: $gamma2", gamma2 < 0.3)
    }
}
