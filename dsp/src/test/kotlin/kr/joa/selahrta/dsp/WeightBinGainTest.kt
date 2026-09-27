package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10

/**
 * **가중을 FFT 칸마다 건다**(지시서 §18).
 *
 * 밴드 중심값 하나를 대역 전체에 걸면 저역에서 1dB 가까이 어긋난다 —
 * 1/3 옥타브 대역은 ±11.6% 폭이고, 저역에서 A-weighting 의 기울기는
 * 옥타브당 12dB 에 가까워 한 대역 안에서 4dB 가 달라지기 때문이다.
 *
 * 여기서 돌려주는 것은 **전력에 곱할 값**이다. 진폭비가 아니다 —
 * 헷갈리면 모든 가중이 정확히 절반 세기로 걸린다.
 */
class WeightBinGainTest {

    private val fftSize = 4096
    private val sampleRate = 48_000

    private fun binOf(hz: Double) = Math.round(hz * fftSize / sampleRate).toInt()

    /** 그 칸이 **실제로** 가리키는 주파수. 4096점·48kHz 면 칸 간격이 11.7Hz 다. */
    private fun hzOf(bin: Int) = bin * sampleRate.toDouble() / fftSize

    /** 전력 이득을 dB 로 되돌린다. 전력이므로 10log10 이다. */
    private fun gainDb(g: Double) = 10.0 * log10(g)

    // ── 독립된 기준 ──────────────────────────────────────
    //
    // **구현에서 뽑은 값을 기준으로 삼지 않는다.** 그러면 틀린 구현이
    // 자기 자신과 맞는지 확인하는 시험이 된다. IEC 61672-1 의 아날로그
    // 식을 여기 따로 적어 둔다.
    //
    // WeightingReference 표를 쓰지 않는 까닭: 그 표는 1/3 옥타브 중심
    // 주파수의 값인데, 칸은 그 자리에 떨어지지 않는다. 100Hz 를 물으면
    // 105.5Hz 칸이 잡히고, 그 자리에서 A-weighting 은 표값과 1.2dB
    // 차이가 난다 — 표와 견주면 맞는 구현도 틀렸다고 나온다.

    private val f1 = 20.598997
    private val f2 = 107.65265
    private val f3 = 737.86223
    private val f4 = 12194.217

    /** IEC 61672-1 A 가중의 **진폭비**(1kHz 에서 1). */
    private fun analyticA(hz: Double): Double {
        fun raw(f: Double): Double {
            val f2s = f * f
            return (f4 * f4 * f2s * f2s) /
                ((f2s + f1 * f1) *
                    Math.sqrt((f2s + f2 * f2) * (f2s + f3 * f3)) *
                    (f2s + f4 * f4))
        }
        return raw(hz) / raw(1000.0)
    }

    /** IEC 61672-1 C 가중의 **진폭비**(1kHz 에서 1). */
    private fun analyticC(hz: Double): Double {
        fun raw(f: Double): Double {
            val f2s = f * f
            return (f4 * f4 * f2s) / ((f2s + f1 * f1) * (f2s + f4 * f4))
        }
        return raw(hz) / raw(1000.0)
    }

    /** 진폭비를 dB 로. 진폭이므로 20log10 이다. */
    private fun ampDb(a: Double) = 20.0 * log10(a)

    @Test
    fun `Z 는 곱할 것이 없다`() {
        assertNull(weightBinGain(Weighting.Z, fftSize, sampleRate))
    }

    @Test
    fun `길이가 칸 수와 같다`() {
        val g = weightBinGain(Weighting.A, fftSize, sampleRate)!!
        assertEquals(fftSize / 2 + 1, g.size)
    }

    /** 규격이 정한 기준점이다. 여기가 틀리면 모든 값이 통째로 밀린다. */
    @Test
    fun `1kHz 칸은 이득이 1 이다`() {
        val g = weightBinGain(Weighting.A, fftSize, sampleRate)!!
        assertEquals(0.0, gainDb(g[binOf(1000.0)]), 0.05)
    }

    /**
     * **전력 이득이다.** 진폭비를 그대로 돌려주면 dB 가 정확히 절반으로
     * 나온다 — 100Hz 근처에서 -19dB 이어야 할 A-weighting 이 -9.6dB 로
     * 보이게 된다. 이 시험이 그 혼동을 잡는다.
     */
    @Test
    fun `진폭비가 아니라 전력 이득을 돌려준다`() {
        val g = weightBinGain(Weighting.A, fftSize, sampleRate)!!
        val bin = binOf(100.0)
        assertEquals(ampDb(analyticA(hzOf(bin))), gainDb(g[bin]), 0.3)
    }

    @Test
    fun `C 는 100Hz 근처를 거의 깎지 않는다`() {
        val g = weightBinGain(Weighting.C, fftSize, sampleRate)!!
        val bin = binOf(100.0)
        assertEquals(ampDb(analyticC(hzOf(bin))), gainDb(g[bin]), 0.2)
        // 값 자체가 0dB 근처라는 것도 함께 본다 — 기준식이 통째로 틀려도
        // 서로 맞으면 위 줄만으로는 지나간다.
        assertTrue("C 가 100Hz 를 너무 깎는다: ${gainDb(g[bin])}", gainDb(g[bin]) > -1.0)
    }

    /**
     * 여러 지점에서 맞는지 본다. 한 점만 보면 기울기가 통째로 틀려도
     * 지나갈 수 있다.
     */
    @Test
    fun `A 가중이 규격 식과 여러 지점에서 맞는다`() {
        val g = weightBinGain(Weighting.A, fftSize, sampleRate)!!
        listOf(31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0).forEach { hz ->
            val bin = binOf(hz)
            assertEquals(
                "칸 $bin(${hzOf(bin)}Hz) 가 규격 식과 다르다",
                ampDb(analyticA(hzOf(bin))),
                gainDb(g[bin]),
                0.4,
            )
        }
    }

    /**
     * 칸에서 읽은 값이 **규격 표**와도 크게 어긋나지 않아야 한다.
     *
     * 위의 식 비교와 따로 두는 까닭: 식을 옮겨 적다 틀리면 구현과 식이
     * 나란히 틀린 채 서로 맞을 수 있다. 표는 규격이 인쇄한 숫자다.
     * 칸이 중심주파수에 정확히 떨어지지 않으므로 여유를 넉넉히 준다.
     */
    @Test
    fun `A 가중이 규격 표와도 크게 어긋나지 않는다`() {
        val g = weightBinGain(Weighting.A, fftSize, sampleRate)!!
        listOf(125.0, 250.0, 500.0, 1000.0, 2000.0).forEach { hz ->
            assertEquals(
                "${hz}Hz 가 규격 표와 크게 다르다",
                WeightingReference.A[hz]!!,
                gainDb(g[binOf(hz)]),
                1.0,
            )
        }
    }

    /** 0Hz 칸은 응답을 셈할 수 없는 자리다. 셈이 NaN 으로 새면 화면이 빈다. */
    @Test
    fun `0Hz 칸도 유한하고 음수가 아니다`() {
        Weighting.entries.filter { it != Weighting.Z }.forEach { w ->
            val g = weightBinGain(w, fftSize, sampleRate)!!
            assertTrue("${w.name} 의 0Hz 칸이 ${g[0]} 이다", g[0].isFinite())
            assertTrue("${w.name} 의 0Hz 칸이 음수다: ${g[0]}", g[0] >= 0.0)
        }
    }

    /** 모든 칸이 성해야 한다. 한 칸이라도 NaN 이면 그 대역이 통째로 빈다. */
    @Test
    fun `모든 칸이 유한하다`() {
        Weighting.entries.filter { it != Weighting.Z }.forEach { w ->
            val g = weightBinGain(w, fftSize, sampleRate)!!
            g.forEachIndexed { i, v ->
                assertTrue("${w.name} 의 칸 $i 가 $v 이다", v.isFinite() && v >= 0.0)
            }
        }
    }

    /**
     * **이것이 이 파일의 한가운데다.** 밴드 중심 하나를 대역에 거는 것과
     * 칸마다 거는 것이 실제로 다름을 못박는다 — 「대충 걸어도 같다」는
     * 생각이 들 때 이 시험이 막는다.
     */
    @Test
    fun `칸마다 거는 것이 밴드 중심에 거는 것과 다르다`() {
        val g = weightBinGain(Weighting.A, fftSize, sampleRate)!!
        val band = ThirdOctave.CENTERS_HZ.indexOfFirst { abs(it - 63.0) < 1.0 }
        assertTrue("63Hz 대역을 못 찾았다", band >= 0)

        val loDb = gainDb(g[binOf(ThirdOctave.lowerEdge(band))])
        val hiDb = gainDb(g[binOf(ThirdOctave.upperEdge(band))])

        // 한 대역 안에서 2dB 넘게 달라진다 — 중심값 하나로 뭉갤 수 없다.
        assertTrue(
            "63Hz 대역 안의 가중 차이가 ${abs(hiDb - loDb)}dB 뿐이다",
            abs(hiDb - loDb) > 2.0,
        )
    }
}
