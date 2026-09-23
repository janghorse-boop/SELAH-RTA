package kr.joa.selahrta.calibration

import kr.joa.selahrta.dsp.CalibrationCurve
import kr.joa.selahrta.dsp.CalibrationSession
import kr.joa.selahrta.dsp.CurvePoint
import kr.joa.selahrta.dsp.MeasureStep
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.dsp.calibrateFromSession
import kr.joa.selahrta.dsp.judgeQuality
import kr.joa.selahrta.dsp.qualityFromSession
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

/**
 * **Codex 가 보낸 `Calibration-PipelineProbe.kt` 의 관측점들**
 * (`docs/review/Calibration-PipelineProbe.kt`, 2026-09-23 검토서 CP01~CP04).
 *
 * ## 이 파일은 회귀 시험이 아니다
 *
 * 결함을 **관측**하는 도구다. 지금의 값을 정답으로 못 박지 않는다 —
 * 회귀는 `CalibrationSessionTest`·`CalibrationQualityTest`·
 * `ProfileCodecTest` 가 잰다.
 *
 * ## 고치기 전 (본문 그대로 옮겨 재현한 값)
 *
 * ```
 * UNSTABLE_REFERENCE spread=50.0 kept=2 dropped=0 verdict=Pass
 * SPECTRAL_DRIFT drift=0.0 verdict=Pass correctionMax=6.73374204940048
 * MASK quality=Pass snrUsable=false correctionValid=true calOutside30HzValid=true
 * CODEC_NAN success=true validNan=120
 * CENTER_CAL expected=0.0 actual=3.2554237993212123 centerGain=0.0
 * ```
 *
 * 다섯 줄 모두 검토서의 값과 일치했다.
 *
 * ## 고친 뒤
 *
 * API 가 바뀌어 본문 그대로는 더 이상 컴파일되지 않는다(그것이 CP04 의
 * 고침이다 — `applyMicCalibration` 이 사라졌다). **관측점은 그대로 두고**
 * 새 API 로 겨누었다. 값은 실행이 찍는다.
 */
class PipelineProbeTest {

    @Test
    fun probe() {
        val n = ThirdOctave.BAND_COUNT
        fun flat(x: Double) = DoubleArray(n) { x }

        // ── CP01 ① 기준 단계가 통째로 불안정한 경우 ────────────────────
        val s = CalibrationSession(referenceCalApplied = true)
        for (step in listOf(MeasureStep.ReferenceBefore, MeasureStep.ReferenceAfter)) {
            s.record(step, flat(40.0)); s.record(step, flat(90.0))
        }
        repeat(4) { s.record(MeasureStep.Target, flat(60.0)) }
        val r = s.result()!!
        println(
            "UNSTABLE_REFERENCE spread=${r.referenceBefore.levelSpreadDb} " +
                "kept=${r.referenceBefore.keptFrames} dropped=${r.referenceBefore.droppedFrames} " +
                "noStable=${r.referenceBefore.noStableFrames} " +
                "verdict=${judgeQuality(qualityFromSession(r, flat(10.0), dspVerifiedBySignal = true)).verdict}",
        )

        // ── CP01 ② 광대역은 같은데 모양이 변한 경우 ────────────────────
        val s2 = CalibrationSession(referenceCalApplied = true)
        val a = flat(50.0); a[20] = 60.0
        val b = flat(50.0); b[24] = 60.0
        repeat(4) {
            s2.record(MeasureStep.ReferenceBefore, a)
            s2.record(MeasureStep.ReferenceAfter, b)
            s2.record(MeasureStep.Target, a)
        }
        val r2 = s2.result()!!
        val q2 = qualityFromSession(r2, flat(10.0), dspVerifiedBySignal = true)
        val out2 = calibrateFromSession(r2, q2, null)
        println(
            "SPECTRAL_DRIFT drift=${r2.referenceDriftDb} bandDrift=${r2.referenceBandDriftDb} " +
                "verdict=${judgeQuality(q2).verdict} " +
                "correctionMax=${out2.getOrNull()?.correction?.db?.maxOf { abs(it) } ?: "-"}",
        )

        // ── CP02 SNR 마스크와 CAL 범위가 보정으로 넘어가는가 ───────────
        val noise = flat(10.0); noise[20] = a[20]
        // 기준 경로 배경도 준다 — 안 주면 보정 자체를 거절하므로(RCP02)
        // 이 관측점이 재려던 것을 볼 수 없다.
        val q = qualityFromSession(
            r2, noise, referenceNoiseDb = flat(10.0), dspVerifiedBySignal = true,
        )
        val cal = CalibrationCurve.of(
            listOf(CurvePoint(200.0, 0.0), CurvePoint(10000.0, 0.0)),
        ).getOrThrow()
        val c = calibrateFromSession(r2, q, cal.rangeHz).getOrThrow()
        val ix = c.correction.hz.indices.minBy { abs(ln(c.correction.hz[it] / ThirdOctave.exactCenter(20))) }
        val lo = c.correction.hz.indices.minBy { abs(c.correction.hz[it] - 30.0) }
        println(
            "MASK quality=${judgeQuality(q).verdict} snrUsable=${q.usable[20]} " +
                "correctionValid=${c.correction.valid[ix]} calOutside30HzValid=${c.correction.valid[lo]}",
        )

        // ── CP03 손상된 곡선 파일 ───────────────────────────────────────
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

        // ── CP04 CAL 을 밴드 중심에서 빼던 자리 ────────────────────────
        // 그 함수(applyMicCalibration)는 사라졌다. 이제 확인할 것은
        // 「칸에 걸지 않은 기준으로는 보정을 만들지 않는가」다.
        val s3 = CalibrationSession(referenceCalApplied = false)
        repeat(8) {
            s3.record(MeasureStep.ReferenceBefore, flat(70.0))
            s3.record(MeasureStep.Target, flat(70.0))
            s3.record(MeasureStep.ReferenceAfter, flat(70.0))
        }
        val r3 = s3.result()!!
        val out3 = calibrateFromSession(r3, qualityFromSession(r3, flat(40.0)), null)
        println(
            "CENTER_CAL refusedWhenCalNotApplied=${out3.isFailure} " +
                "why=${out3.exceptionOrNull()?.message?.take(40)}",
        )
    }
}
