package kr.joa.selahrta.calibration

import kr.joa.selahrta.dsp.ResponseCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 독립 재검토(2026-09-25, Codex)의 CA-02 회귀 시험을 그대로 옮긴 것.
 *
 * 출처: `docs/review/2026-09-25-combined-independent-review.md`
 * · 검토자 원본 `IndependentCombinedRegressionTest.kt`
 *
 * **재는 방법은 손대지 않았다.** fixture 의 점·유효 표·칸 번호가 검토자가
 * 쓴 그대로다. 한국어 이름과 설명만 붙였고, 검토자가 함께 보라고 한
 * 「유효 구간은 그대로 보정된다」를 대조군으로 덧붙였다.
 *
 * 고치기 전에 두 건이 실패하는 것을 먼저 확인했다.
 */
class CodexCombinedRegressionTest {

    /**
     * 검토자의 fixture. 2kHz 와 8kHz 가 **못 믿는 자리**다.
     *
     * 100 · 500 · 1000 은 이어진 유효 구간이고, 4000 은 양옆이 무효라
     * 혼자 남는다.
     */
    private fun curve() = correctionAsCurve(
        ResponseCurve(
            doubleArrayOf(100.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0),
            doubleArrayOf(6.0, 6.0, 0.0, 0.0, 6.0, 0.0),
            booleanArrayOf(true, true, true, false, true, false),
        ),
    ).getOrThrow()

    /**
     * **버린 구간을 다시 보정하면 안 된다.**
     *
     * 점을 빼는 것만으로는 남은 점 사이가 이어져 버려, 화면에서 「못
     * 믿는다」고 적은 대역을 실제로는 고치고 있었다. 검토자가 이 칸에서
     * **+3.008dB** 가 걸리는 것을 쟀다.
     */
    @Test
    fun `무효 구간 안쪽은 보정을 받지 않는다`() {
        val c = curve().binCorrectionLinear(4096, 48_000, 1000.0)
        assertEquals("무효인 2kHz 는 원래 전력 그대로여야 한다", 1.0, c[171], 1e-12)
    }

    /**
     * **잰 적 없는 대역도 마찬가지다.**
     *
     * 범위 밖은 끝점 값이 늘어나 걸렸다 — 검토자가 8kHz 에 +6dB 가
     * 걸리는 것을 쟀다.
     */
    @Test
    fun `측정 범위 밖은 보정을 받지 않는다`() {
        val c = curve().binCorrectionLinear(4096, 48_000, 1000.0)
        assertEquals("안 잰 8kHz 는 원래 전력 그대로여야 한다", 1.0, c[683], 1e-12)
    }

    /** 아래쪽 범위 밖도 같다. 검토자가 46.875Hz 에서 +6dB 를 쟀다. */
    @Test
    fun `측정 범위 아래도 보정을 받지 않는다`() {
        val c = curve().binCorrectionLinear(4096, 48_000, 1000.0)
        assertEquals("안 잰 46.9Hz", 1.0, c[4], 1e-12)
    }

    /**
     * **대조군 — 유효 구간은 그대로 보정돼야 한다.**
     *
     * 안 걸리게 만드는 것으로 위 셋을 통과하면 보정 기능 자체가 죽는다.
     * 100~1000Hz 는 이어진 유효 구간이므로 보정이 살아 있어야 한다.
     */
    @Test
    fun `유효 구간은 그대로 보정된다`() {
        val c = curve().binCorrectionLinear(4096, 48_000, 1000.0)
        // 500Hz ≈ 43번 칸. correction +6dB 는 전력에 10^(6/10) 을 곱한다.
        assertTrue("유효 구간에서 보정이 사라졌다: ${c[43]}", c[43] > 3.0)
        // 1kHz 는 기준이라 1.0 이다.
        assertEquals("기준 주파수", 1.0, c[85], 0.02)
    }

    /** 덮는 대역 표시도 구멍을 알아야 한다. */
    @Test
    fun `덮는 범위가 구멍을 알아본다`() {
        val c = curve()
        assertTrue("유효 구간 안", c.covers(500.0))
        assertFalse("무효 구간 안쪽", c.covers(2_000.0))
        assertFalse("측정 범위 밖", c.covers(8_000.0))
        assertFalse("측정 범위 아래", c.covers(46.875))
    }
}
