package kr.joa.selahrta.calibration

import kr.joa.selahrta.dsp.CalibrationCurve
import kr.joa.selahrta.dsp.CalibrationSession
import kr.joa.selahrta.dsp.CurvePoint
import kr.joa.selahrta.dsp.MeasureStep
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.dsp.applyMicCalibration
import kr.joa.selahrta.dsp.calibrateFromSession
import kr.joa.selahrta.dsp.judgeQuality
import kr.joa.selahrta.dsp.qualityFromSession
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * **Codex 가 보낸 `Calibration-PipelineProbe.kt` 를 본문 그대로 옮긴 것.**
 * (`docs/review/Calibration-PipelineProbe.kt`, 2026-09-23 검토서 CP01~CP04)
 *
 * 바꾼 것은 JUnit 껍데기와 import, 그리고 **한 줄**뿐이다. 계산과 출력
 * 줄은 손대지 않았다 — 고치기 전 숫자가 검토서의 값과 같은지부터 봐야 한다.
 *
 * 바꾼 한 줄: `CODEC_NAN` 에서 `decoded.getOrThrow()` 를 `getOrNull()` 로
 * 했다. 성공일 때 값이 같으므로 **고치기 전 출력은 그대로**이고, 고친
 * 뒤에는 decode 가 실패를 돌려주어야 하는데 `getOrThrow()` 면 probe 가
 * 예외로 죽어 아무것도 못 보게 된다.
 *
 * **이 파일은 회귀 시험이 아니다.** 지금 있는 결함을 **관측**하는 도구다.
 * 고친 뒤에는 관측값이 바뀌고, 그때 제대로 된 회귀 시험을 따로 쓴다.
 */
class PipelineProbeTest {

    @Test
    fun probe() {
        val n = ThirdOctave.BAND_COUNT
        fun flat(x: Double) = DoubleArray(n) { x }

        val s = CalibrationSession()
        for (step in listOf(MeasureStep.ReferenceBefore, MeasureStep.ReferenceAfter)) {
            s.record(step, flat(40.0)); s.record(step, flat(90.0))
        }
        repeat(4) { s.record(MeasureStep.Target, flat(60.0)) }
        val r = s.result()!!
        println(
            "UNSTABLE_REFERENCE spread=${r.referenceBefore.levelSpreadDb} " +
                "kept=${r.referenceBefore.keptFrames} dropped=${r.referenceBefore.droppedFrames} " +
                "verdict=${judgeQuality(qualityFromSession(r, flat(10.0), dspVerifiedBySignal = true)).verdict}",
        )

        val s2 = CalibrationSession()
        val a = flat(50.0); a[20] = 60.0
        val b = flat(50.0); b[24] = 60.0
        repeat(4) {
            s2.record(MeasureStep.ReferenceBefore, a)
            s2.record(MeasureStep.ReferenceAfter, b)
            s2.record(MeasureStep.Target, a)
        }
        val r2 = s2.result()!!
        println(
            "SPECTRAL_DRIFT drift=${r2.referenceDriftDb} " +
                "verdict=${judgeQuality(qualityFromSession(r2, flat(10.0), dspVerifiedBySignal = true)).verdict} " +
                "correctionMax=${calibrateFromSession(r2, null).correction.db.maxOf { abs(it) }}",
        )

        val noise = flat(10.0); noise[20] = a[20]
        val q = qualityFromSession(r2, noise, dspVerifiedBySignal = true)
        val c = calibrateFromSession(
            r2,
            CalibrationCurve.of(listOf(CurvePoint(200.0, 0.0), CurvePoint(10000.0, 0.0))).getOrThrow(),
        )
        val ix = c.correction.hz.indices.minBy { abs(ln(c.correction.hz[it] / ThirdOctave.exactCenter(20))) }
        val lo = c.correction.hz.indices.minBy { abs(c.correction.hz[it] - 30.0) }
        println(
            "MASK quality=${judgeQuality(q).verdict} snrUsable=${q.usable[20]} " +
                "correctionValid=${c.correction.valid[ix]} calOutside30HzValid=${c.correction.valid[lo]}",
        )

        val text = encodeCurves(c)
        val bad = text.lineSequence().map {
            if (it.startsWith("correction.db=")) {
                "correction.db=" + c.correction.db.indices.joinToString(",") { "NaN" }
            } else {
                it
            }
        }.joinToString("\n")
        val decoded = decodeCurves(bad)
        println(
            "CODEC_NAN success=${decoded.isSuccess} validNan=${
                decoded.getOrNull()?.let { d ->
                    d.correction.db.indices.count { d.correction.valid[it] && d.correction.db[it].isNaN() }
                } ?: "-"
            }",
        )

        val center = ThirdOctave.exactCenter(17)
        val centerCurve = CalibrationCurve.of(
            listOf(CurvePoint(900.0, 6.0), CurvePoint(center, 0.0), CurvePoint(1100.0, -6.0)),
        ).getOrThrow()
        val integrated = 10 * log10((10.0.pow(6.0 / 10) + 10.0.pow(-6.0 / 10)) / 2)
        val bandSample = flat(0.0); bandSample[17] = integrated
        println(
            "CENTER_CAL expected=0.0 actual=${applyMicCalibration(bandSample, centerCurve)[17]} " +
                "centerGain=${centerCurve.gainDbAt(center)}",
        )
    }
}
