package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
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
        // x·y 는 위상이 달라(아래 부호 시험과 같은 쌍) syy·sxyIm 도 reset 전에는
        // 뚜렷이 0 이 아니다 — 그래야 네 배열 전부가 reset 으로 비워지는지 가린다.
        val x = tone(n, 64)
        val y = DoubleArray(n) { sin(2.0 * PI * 64 * it / n - PI / 2) }
        a.addBlock(x, 0, y, 0)
        a.reset()
        assertEquals(0, a.count)
        assertEquals(0.0, a.sxx.sum(), 1e-12)
        assertEquals(0.0, a.syy.sum(), 1e-12)
        // 부호가 섞여 합이 우연히 0 에 가까워질 수 있으니 절댓값으로 더해 본다.
        assertEquals(0.0, a.sxyRe.sumOf { abs(it) }, 1e-12)
        assertEquals(0.0, a.sxyIm.sumOf { abs(it) }, 1e-12)
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
            // conj(X)·X = |X|² 이므로 Sxy 실수는 Sxx 와 정확히 같아야 한다(부호도 포함).
            assertEquals("bin $i 의 Sxy 실수", a.sxx[i], a.sxyRe[i], 1e-9)
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

    /**
     * ref 와 meas 의 offset 이 **서로 다를 때도** 각자 제자리에서 읽는지 가린다.
     * 지금까지의 시험은 두 offset 이 늘 같아서(0,0 또는 n,n), 구현이 안에서
     * `refOffset`/`measOffset` 을 서로 바꿔 써도 걸리지 않았다. ref 와 meas
     * 를 서로 다른 신호·다른 자리로 묻어 두고, 직접 자른 조각과 비교한다.
     */
    @Test
    fun `ref 와 meas 의 offset 이 달라도 각자 제자리에서 읽는다`() {
        val refTone = tone(n, 64)
        val measTone = tone(n, 96, amp = 0.5)

        // refTone 은 refLong 의 [n, 2n) 에, measTone 은 measLong 의 [2n, 3n) 에 묻는다.
        // 서로 다른 자리라서, offset 을 바꿔 읽으면 둘 다 비어 있는 구간(0)을 읽게 된다.
        val refLong = DoubleArray(n * 3)
        System.arraycopy(refTone, 0, refLong, n, n)
        val measLong = DoubleArray(n * 3)
        System.arraycopy(measTone, 0, measLong, 2 * n, n)

        val direct = SpectralAverager(n).apply { addBlock(refTone, 0, measTone, 0) }
        val sliced = SpectralAverager(n).apply { addBlock(refLong, n, measLong, 2 * n) }

        for (i in 0 until direct.bins) {
            assertEquals("bin $i sxx", direct.sxx[i], sliced.sxx[i], 1e-9)
            assertEquals("bin $i syy", direct.syy[i], sliced.syy[i], 1e-9)
            assertEquals("bin $i sxyRe", direct.sxyRe[i], sliced.sxyRe[i], 1e-9)
            assertEquals("bin $i sxyIm", direct.sxyIm[i], sliced.sxyIm[i], 1e-9)
        }
    }

    /**
     * 측정이 기준보다 위상만큼 뒤지면 Sxy 의 허수는 그 부호를 따른다.
     *
     * 유도(코드가 아니라 수학에서 끌어온다): y 가 x 보다 θ 만큼 뒤지면
     * `Y = X·e^{-iθ}` 이므로 `Sxy = conj(X)·Y = |X|²·e^{-iθ}`,
     * 즉 `Im(Sxy) = -|X|²·sinθ`. θ = π/2 이면 sinθ = 1 이므로
     * **Im(Sxy) 는 음수라야 한다.**
     *
     * 지금까지의 시험은 모두 위상차가 없는 쌍(y=x, y=2x, 잡음은 제곱해 부호가
     * 지워짐)만 썼기 때문에, `sxyIm` 누적의 부호를 통째로 반전해도
     * (`xr*yi - xi*yr` → `xi*yr - xr*yi`) 전부 통과했다. 이 시험이 그 구멍을 막는다.
     */
    @Test
    fun `측정이 기준보다 뒤지면 Sxy 의 허수는 음수다`() {
        val k = 64
        val theta = PI / 2
        val x = DoubleArray(n) { sin(2.0 * PI * k * it / n) }
        val y = DoubleArray(n) { sin(2.0 * PI * k * it / n - theta) }

        val a = SpectralAverager(n)
        a.addBlock(x, 0, y, 0)

        assertTrue("Im(Sxy) 가 음수라야 한다: ${a.sxyIm[k]}", a.sxyIm[k] < 0.0)
        assertTrue(
            "Im(Sxy) 크기가 충분히 커야 한다: Im=${a.sxyIm[k]} vs Sxx=${a.sxx[k]}",
            abs(a.sxyIm[k]) > 0.5 * a.sxx[k]
        )
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
