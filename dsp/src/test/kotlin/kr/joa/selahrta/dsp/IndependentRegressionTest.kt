package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin

/**
 * 독립 검증자(Codex)가 2026-09-21 에 만든 회귀 시험.
 *
 * **검증자가 쓴 `inbox/ReviewProbe.kt` 를 그대로 옮겼다.** 기댓값도 실행
 * 로그(`docs/review/2026-09-21-verification-log.txt`)의 수치 그대로다.
 * 내가 다시 쓰면 또 나에게 유리한 재현을 만들 위험이 있다 — 실제로
 * 그런 일이 있었다. 「실제 예배 소리에서는 0.15dB 미만」 시험은 내가 만든
 * 스펙트럼 모형이 8kHz 에서 -689dB 을 만들어 고역 기여를 사실상 지운
 * 위에서 통과했다.
 *
 * 패키지와 import 만 이 저장소에 맞췄고, 검증 논리는 손대지 않았다.
 */
class IndependentRegressionTest {

    /** R06 — MAX 는 덩어리를 어떻게 잘라 넣든 같아야 한다. */
    @Test
    fun maximumMustNotDependOnBlockPartition() {
        val x = FloatArray(1024).also { it[1] = 1f }
        val block = SplEngine(48000, Weighting.Z, TimeWeight.Fast).process(x, x.size)
        val each = SplEngine(48000, Weighting.Z, TimeWeight.Fast)
        var ref = each.process(floatArrayOf(0f), 0)
        for (v in x) ref = each.process(floatArrayOf(v), 1)
        assertEquals("Same PCM must give same MAX", ref.maxDbfs.value, block.maxDbfs.value, 1e-8)
    }

    /** R07 — 시간가중은 선언한 시간상수대로 시작해야 한다. */
    @Test
    fun coldStartMustUseDeclaredTimeConstant() {
        val w = ExponentialTimeWeighting(TimeWeight.Slow, 48000)
        val actual = w.push(1.0)
        val expected = 1.0 - exp(-1.0 / 48000)
        assertEquals(expected, actual, 1e-12)
    }

    /** R05 — 좁은 순음은 그 주파수의 보정값을 받아야 한다. */
    @Test
    fun narrowToneMustUseItsOwnFrequencyCorrection() {
        val b = 17
        val c = CalibrationCurve.of(
            listOf(
                CurvePoint(ThirdOctave.lowerEdge(b), -6.0),
                CurvePoint(1000.0, 0.0),
                CurvePoint(ThirdOctave.upperEdge(b), 6.0),
            ),
        ).getOrThrow()
        val x = DoubleArray(4096) { 0.5 * sin(2 * PI * 1000 * it / 48000) }
        val p = DoubleArray(2049)
        PowerSpectrum(4096).compute(x, 0, p)
        val bp = DoubleArray(31)
        BandAnalyzer(4096, 48000).toBandPower(p, bp)
        val corrected = 10 * log10(bp[b]) - c.bandGainsDb()[b]
        val expected = 10 * log10(0.125)
        assertEquals(expected, corrected, 0.05)
    }

    /**
     * 파스발 — **창 가중** 등식이 맞는지 본다.
     *
     * 내가 적었던 「칸 전력 합 = 원신호 평균 제곱」은 틀린 주석이었다.
     * 정확한 등식은 sum(P[k]) = sum(x²·w²) / sum(w²) 이다.
     */
    @Test
    fun windowedParsevalIsExactForTransient() {
        val n = 4096
        val w = HannWindow.create(n)
        for (pos in listOf(0, n / 2, n - 1)) {
            val x = DoubleArray(n).also { it[pos] = 1.0 }
            val p = DoubleArray(n / 2 + 1)
            PowerSpectrum(n).compute(x, 0, p)
            val expected = x.indices.sumOf { x[it] * x[it] * w[it] * w[it] } / w.sumOf { it * it }
            assertEquals(expected, p.sum(), 1e-14)
        }
    }
}
