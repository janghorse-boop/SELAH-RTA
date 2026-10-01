package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class TransferFunctionTest {

    private val n = 1024
    private val rng = Random(20261002)

    private fun noise(size: Int) = DoubleArray(size) { rng.nextDouble() * 2 - 1 }
    private fun tone(size: Int, bin: Int) =
        DoubleArray(size) { sin(2.0 * PI * bin * it / size) }

    /** 기준과 측정이 같으면 0dB 이고 상관은 1 이다. */
    @Test
    fun `같은 신호는 0dB 이고 상관이 1 이다`() {
        val a = SpectralAverager(n)
        repeat(16) { val x = noise(n); a.addBlock(x, 0, x, 0) }
        val r = transferFunction(a)

        val b = n / 4
        assertTrue("bin $b 가 무효다", r.valid[b])
        assertEquals(0.0, r.magnitudeDb[b], 0.01)
        assertEquals(1.0, r.coherence[b], 0.001)
    }

    /** 측정이 두 배면 +6.02dB 다. */
    @Test
    fun `두 배는 6dB 이다`() {
        val a = SpectralAverager(n)
        repeat(16) {
            val x = noise(n)
            a.addBlock(x, 0, DoubleArray(n) { i -> x[i] * 2.0 }, 0)
        }
        val r = transferFunction(a)
        assertEquals(6.0206, r.magnitudeDb[n / 4], 0.01)
    }

    /** 관계없는 잡음은 상관이 낮다. */
    @Test
    fun `관계없는 잡음은 상관이 낮다`() {
        val a = SpectralAverager(n)
        repeat(32) { a.addBlock(noise(n), 0, noise(n), 0) }
        val r = transferFunction(a)
        assertTrue("상관이 ${r.coherence[n / 4]} 로 높다", r.coherence[n / 4] < 0.3)
    }

    /** **이번에 새로 막는 자리.** 기준이 없는 칸은 무효다. */
    @Test
    fun `기준이 약한 칸은 무효다`() {
        val a = SpectralAverager(n)
        // 한 칸에만 에너지가 있는 순음을 기준으로 쓴다.
        repeat(16) { val x = tone(n, 64); a.addBlock(x, 0, x, 0) }
        val r = transferFunction(a, refFloorDb = -40.0)

        assertTrue("순음이 있는 칸이 무효다", r.valid[64])
        // 에너지가 없는 먼 칸은 막혀야 한다.
        assertFalse("기준이 없는 칸이 유효로 나온다", r.valid[300])
    }

    @Test
    fun `무효인 칸의 상관은 0 으로 둔다`() {
        val a = SpectralAverager(n)
        repeat(16) { val x = tone(n, 64); a.addBlock(x, 0, x, 0) }
        val r = transferFunction(a, refFloorDb = -40.0)
        assertEquals(0.0, r.coherence[300], 1e-12)
    }

    @Test
    fun `평균 수를 그대로 들고 나온다`() {
        val a = SpectralAverager(n)
        repeat(9) { val x = noise(n); a.addBlock(x, 0, x, 0) }
        assertEquals(9, transferFunction(a).averages)
    }

    /** 명세 9장의 표시 정책. */
    @Test
    fun `평균 수에 따라 상관을 보일지 가른다`() {
        assertEquals(CoherenceDisplay.Hidden, coherenceDisplay(0))
        assertEquals(CoherenceDisplay.Hidden, coherenceDisplay(1))
        assertEquals(CoherenceDisplay.Hidden, coherenceDisplay(7))
        assertEquals(CoherenceDisplay.Stabilizing, coherenceDisplay(8))
        assertEquals(CoherenceDisplay.Stabilizing, coherenceDisplay(15))
        assertEquals(CoherenceDisplay.Shown, coherenceDisplay(16))
        assertEquals(CoherenceDisplay.Shown, coherenceDisplay(100))
    }

    /** **단일 블록은 상관이 수식상 1 이다** — 그래서 숨긴다. */
    @Test
    fun `한 블록만 모으면 상관이 1 이고 그래서 숨긴다`() {
        val a = SpectralAverager(n)
        a.addBlock(noise(n), 0, noise(n), 0)      // 관계없는 잡음인데도
        val r = transferFunction(a)
        assertEquals(1.0, r.coherence[n / 4], 1e-9)
        assertEquals(CoherenceDisplay.Hidden, coherenceDisplay(r.averages))
    }

    @Test
    fun `아무것도 안 모으면 전부 무효다`() {
        val r = transferFunction(SpectralAverager(n))
        assertEquals(0, r.averages)
        assertFalse(r.valid.any { it })
    }
}
