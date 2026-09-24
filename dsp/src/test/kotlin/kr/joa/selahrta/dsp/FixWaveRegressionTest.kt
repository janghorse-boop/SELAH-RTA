package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * 독립 재검증자(Codex)가 2026-09-21 에 만든 두 번째 회귀 시험.
 *
 * **검증자가 쓴 `docs/review/FixWaveProbe.kt` 의 측정 논리를 그대로 옮겼다.**
 * 기대값도 `docs/review/fix-wave-verification-log.txt` 의 실행 수치 그대로다.
 * 검증자의 파일은 `main()` 에서 값을 찍기만 하므로, 재는 방법은 손대지 않고
 * **찍던 값을 그대로 단언으로 바꿨다.**
 *
 * 내가 다시 쓰면 또 나에게 유리한 재현을 만들 위험이 있다 — 1차 검증에서
 * 실제로 그런 일이 있었다.
 */
class FixWaveRegressionTest {

    private val fs = 48000
    private val n = 4096

    /** 검증자와 같은 자리에서 같은 방식으로 잰다. */
    private fun lossAt(ba: BandAnalyzer, ps: PowerSpectrum, b: Int, t: Double): Double {
        val f = ThirdOctave.lowerEdge(b) *
            (ThirdOctave.upperEdge(b) / ThirdOctave.lowerEdge(b)).pow(t)
        val p = DoubleArray(n / 2 + 1)
        val bp = DoubleArray(31)
        ps.compute(DoubleArray(n) { sin(2 * PI * f * it / fs) }, 0, p)
        ba.toBandPower(p, bp)
        return -10 * log10(bp[b] / 0.5)
    }

    /**
     * F05 — 표기한 손실은 **상한이 아니라 표본**이다.
     *
     * 검증자의 로그 그대로: 125Hz 밴드는 표기 0.9194dB·resolved 인데
     * t=0.9 에서 1.9186dB, t=0.99 에서 2.8169dB 이 빠진다. 1kHz 밴드조차
     * 경계에서는 2.36dB 이다 — 가장자리 손실은 저역만의 문제가 아니다.
     */
    @Test
    fun `표기한 손실은 밴드 전체의 상한이 아니다`() {
        val ba = BandAnalyzer(n, fs)
        val ps = PowerSpectrum(n)

        assertEquals("125Hz 표기값", 0.9194, ba.bandLossDb[8], 0.001)
        assertTrue("125Hz 는 분해됐다고 본다", ba.bandResolved[8])
        assertEquals("125Hz t=0.9", 1.9186, lossAt(ba, ps, 8, 0.9), 0.001)
        assertEquals("125Hz t=0.99", 2.8169, lossAt(ba, ps, 8, 0.99), 0.001)

        // 가장자리 손실은 **모든 밴드**에 있다. 1kHz 는 표기값이 사실상 0 인데도
        // 경계에서는 2.36dB 이 빠진다. 이것까지 판정에 넣으면 31개 밴드가
        // 전부 미분해가 된다 — 그래서 가운데 구간의 표본으로 판정하고,
        // 화면이 「표본」이라고 적는다.
        assertTrue("1kHz 표기값은 사실상 0", ba.bandLossDb[17] < 0.001)
        assertEquals("1kHz t=0.01", 2.3643, lossAt(ba, ps, 17, 0.01), 0.001)
        assertTrue(
            "가장자리를 넣으면 1kHz 도 기준(${LEAKAGE_TOLERANCE_DB}dB)을 넘는다",
            lossAt(ba, ps, 17, 0.01) > LEAKAGE_TOLERANCE_DB,
        )
    }

    /**
     * R05 재확인 — **실제 `RtaEngine` 경로**로 보정이 걸리는가.
     *
     * 1차 시험은 `bandGainsDb()` 를 빼는 옛 경로였다. 검증자가 짚은 대로
     * 그 시험이 통과한 것만으로는 새 경로를 검증한 것이 아니다.
     */
    @Test
    fun `실제 RTA 경로에서 칸별 보정이 걸린다`() {
        val c = CalibrationCurve.of(
            listOf(
                CurvePoint(ThirdOctave.lowerEdge(17), -6.0),
                CurvePoint(1000.0, 0.0),
                CurvePoint(ThirdOctave.upperEdge(17), 6.0),
            ),
        ).getOrThrow()
        val engine = RtaEngine(fs, smoothingFactor = 0.0)
        engine.setCurve(c)
        engine.process(FloatArray(n) { (0.5 * sin(2 * PI * 1000 * it / fs)).toFloat() }, n)

        val actual = engine.frame()!!.bandsDbfs[17]
        val expected = 10 * log10(0.125)
        assertTrue(
            "1kHz 순음 보정 후 ${"%.6f".format(actual)} (기대 ${"%.6f".format(expected)})",
            abs(actual - expected) < 0.05,
        )
    }

    /**
     * F06 — 곡선을 바꾸면 **옛 곡선으로 계산한 프레임을 내놓지 않는다.**
     *
     * 검증자 로그: `AFTER_SET_CURVE priorFrameStillReturned=true` —
     * 곡선을 +10dB 짜리로 갈아 끼운 직후에도 이전 프레임(−9.015380)이
     * 그대로 나왔고, 다음 FFT 뒤에야 −19.030901 로 바뀌었다. 그 사이
     * 화면은 새 곡선의 이름표를 붙이고 있었다.
     *
     * ## 2026-09-23 — 재는 자리를 2kHz 로 옮겼다
     *
     * 걸리는 곡선이 [CURVE_REFERENCE_HZ](1kHz)에 못이 박히면서, **1kHz
     * 순음의 값은 어느 곡선을 걸어도 같아졌다**(그것이 그 변경의 목적이다).
     * 그래서 예전 숫자(−19.03)로는 더 이상 옛 프레임과 새 프레임을 가를 수
     * 없다 — 시험이 통과해도 아무 말도 하지 않게 된다.
     *
     * **허용치를 늘리거나 단언을 지우지 않았다.** 신호와 곡선을 1kHz 가
     * 아닌 자리로 옮겨, 값이 실제로 움직이는 조건에서 같은 것을 묻는다.
     * F06 이 물은 것은 「숫자가 얼마냐」가 아니라 「곡선을 바꾼 뒤에도 옛
     * 프레임을 내놓느냐」다.
     */
    @Test
    fun `곡선을 바꾸면 옛 프레임을 내놓지 않는다`() {
        // 2kHz 순음. 1kHz 는 못이 박힌 자리라 값이 안 움직인다.
        val probeHz = 2000.0
        val probeBand = ThirdOctave.nearestBand(probeHz)
        val engine = RtaEngine(fs, smoothingFactor = 0.0)
        // 아무 데도 손대지 않는 곡선. 2kHz 에서 0dB 이다.
        engine.setCurve(
            CalibrationCurve.of(listOf(CurvePoint(20.0, 0.0), CurvePoint(20000.0, 0.0)))
                .getOrThrow(),
        )
        engine.process(FloatArray(n) { (0.5 * sin(2 * PI * probeHz * it / fs)).toFloat() }, n)

        val old = engine.frame()
        assertNotNull(old)
        val genBefore = old!!.curveGeneration
        val before = old.bandsDbfs[probeBand]

        // 1kHz 는 그대로 두고 2kHz 만 +10dB 인 곡선. 못이 박혀도 이 자리는
        // 움직인다.
        engine.setCurve(
            CalibrationCurve.of(
                listOf(
                    CurvePoint(1000.0, 0.0),
                    CurvePoint(probeHz, 10.0),
                    CurvePoint(20000.0, 10.0),
                ),
            ).getOrThrow(),
        )
        assertFalse(
            "곡선을 바꾸면 옛 프레임은 사라져야 한다",
            engine.frame() === old,
        )

        // 다음 FFT 가 돌면 새 곡선으로 계산한 값이 나온다. +10dB 응답을
        // 되돌리므로 10dB 낮아진다.
        engine.process(
            FloatArray(n / 2) { (0.5 * sin(2 * PI * probeHz * (it + n) / fs)).toFloat() },
            n / 2,
        )
        val fresh = engine.frame()!!
        assertEquals("새 곡선으로 계산된 값", before - 10.0, fresh.bandsDbfs[probeBand], 0.05)
        assertTrue("세대가 올라가야 한다", fresh.curveGeneration > genBefore)
        assertEquals(
            "엔진이 들고 있는 세대와 같아야 한다",
            engine.currentCurveGeneration,
            fresh.curveGeneration,
        )
    }
}
