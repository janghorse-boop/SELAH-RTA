package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * **지연을 못 찾는 것을 숫자로 내놓지 않는가** — 이 묶음에서 제일 중요한
 * 시험은 「관계없는 잡음」과 「범위 밖」이다. 조용한 자리에서는 잡음끼리도
 * 어딘가에서 가장 큰 값이 나오고, 그 값을 지연이라고 적으면 사람은 그
 * 숫자로 스피커를 정렬한다.
 */
class DelayEstimatorTest {

    private val n = 8192
    private val rng = Random(20261002)

    private fun noise(size: Int) = DoubleArray(size) { rng.nextDouble() * 2 - 1 }

    /** [src] 를 [lag] 만큼 뒤로 민 것. 앞은 0 으로 채운다. */
    private fun delayed(src: DoubleArray, lag: Int, gain: Double = 1.0) =
        DoubleArray(src.size) { if (it < lag) 0.0 else src[it - lag] * gain }

    private fun est() = DelayEstimator(analysisSize = n, maxLagSamples = 2000)

    @Test
    fun `지연 0 을 찾는다`() {
        val x = noise(n)
        val r = est().estimate(x, x)
        assertTrue(r.found)
        assertEquals(0, r.samples)
    }

    @Test
    fun `여러 지연을 그대로 찾는다`() {
        val x = noise(n)
        for (lag in listOf(1, 48, 240, 960, 1920)) {
            val r = est().estimate(x, delayed(x, lag))
            assertTrue("lag=$lag 를 못 찾았다", r.found)
            assertEquals("lag=$lag", lag, r.samples)
        }
    }

    @Test
    fun `잡음을 섞어도 찾는다`() {
        val x = noise(n)
        val bg = noise(n)
        val d = delayed(x, 500)
        val y = DoubleArray(n) { d[it] + bg[it] }   // SNR 0dB
        val r = est().estimate(x, y)
        assertTrue(r.found)
        assertEquals(500, r.samples)
    }

    @Test
    fun `반사가 섞여도 본 신호를 고른다`() {
        val x = noise(n)
        val direct = delayed(x, 300)
        val reflect = delayed(x, 300 + 480, gain = 0.5)
        val y = DoubleArray(n) { direct[it] + reflect[it] }
        val r = est().estimate(x, y)
        assertTrue(r.found)
        assertEquals(300, r.samples)
    }

    /** **PHAT 가 정말 더 또렷한가** — 말이 아니라 숫자로 본다. */
    @Test
    fun `잔향 속에서 PHAT 가 더 또렷하다`() {
        val x = noise(n)
        val y = DoubleArray(n)
        var gain = 1.0
        var lag = 400
        repeat(8) {
            val echo = delayed(x, lag, gain)
            for (i in 0 until n) y[i] += echo[i]
            lag += 170
            gain *= 0.72
        }
        val withPhat = DelayEstimator(n, 2000, phat = true).estimate(x, y)
        val plain = DelayEstimator(n, 2000, phat = false).estimate(x, y)

        assertTrue("PHAT 가 본 신호를 놓쳤다", withPhat.found)
        assertEquals("PHAT 가 엉뚱한 지연을 골랐다", 400, withPhat.samples)
        assertTrue(
            "PHAT 또렷함 ${withPhat.sharpness} 가 평범한 상관 ${plain.sharpness} 보다 크지 않다",
            withPhat.sharpness > plain.sharpness,
        )
    }

    /** **제일 중요한 시험.** */
    @Test
    fun `관계없는 잡음끼리는 못 찾았다고 말한다`() {
        assertFalse(est().estimate(noise(n), noise(n)).found)
    }

    @Test
    fun `탐색 범위를 넘는 지연은 못 찾았다고 말한다`() {
        val x = noise(n)
        assertFalse(DelayEstimator(n, 500).estimate(x, delayed(x, 3000)).found)
    }

    @Test
    fun `아무 소리도 없으면 못 찾았다고 말한다`() {
        assertFalse(est().estimate(DoubleArray(n), DoubleArray(n)).found)
    }

    /**
     * **꼬리를 본다** — [analysisSize] 보다 긴 입력을 주면 가장 최근
     * [analysisSize] 개만 봐야 한다. 앞쪽(과거)에는 못 찾아야 할 지연을,
     * 뒤쪽(꼬리, 「지금」)에는 찾아야 할 지연을 심어 둔다. 앞쪽을 보고 있으면
     * (옛 버그처럼 배열의 머리를 쓰면) `oldLag` 를 찾아 이 시험이 실패한다.
     */
    @Test
    fun `analysisSize 보다 긴 입력은 꼬리만 본다`() {
        val total = n * 2
        val reference = noise(total)
        val oldLag = 1200  // 앞쪽(버려야 할 구간)에 심은, 찾으면 안 되는 지연
        val newLag = 500   // 뒤쪽(꼬리, 봐야 할 구간)에 심은, 찾아야 하는 지연

        val measurement = DoubleArray(total) { i ->
            if (i < n) {
                if (i < oldLag) 0.0 else reference[i - oldLag]
            } else {
                if (i - n < newLag) 0.0 else reference[i - newLag]
            }
        }

        val r = est().estimate(reference, measurement)
        assertTrue("꼬리의 지연을 못 찾았다", r.found)
        assertEquals("앞쪽(버려야 할) 지연을 찾았다 — 꼬리를 보고 있지 않다", newLag, r.samples)
    }
}
