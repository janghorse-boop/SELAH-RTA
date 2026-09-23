package kr.joa.selahrta.calibration

import kr.joa.selahrta.dsp.CalibrationSession
import kr.joa.selahrta.dsp.CalibrationSettings
import kr.joa.selahrta.dsp.MeasureStep
import kr.joa.selahrta.dsp.calibrateFromSession
import kr.joa.selahrta.dsp.judgeCalibration
import kr.joa.selahrta.dsp.qualityFromSession
import org.junit.Test

/**
 * **Codex 가 보낸 `L01-RoundTripProbe.kt` 를 본문 그대로 옮긴 것**
 * (`docs/review/L01-RoundTripProbe.kt`, 2026-09-23 재검토 L01-R).
 *
 * 바꾼 것은 JUnit 껍데기와 import 뿐이다.
 *
 * **회귀 시험이 아니라 관측 도구다.**
 *
 * ## 고치기 전 (검토서가 보고한 값)
 *
 * ```
 * ROUNDTRIP flags=67->0 maskEqual=true
 *           beforeLimit=true afterLimit=false
 *           beforeVerdict=Fail afterVerdict=Fail
 * ZERO_VALID generated=true norm=40 valid=0 final=Fail
 * ```
 *
 * 왕복하면 상한 표시 67개가 사라지고, 같은 결과의 까닭 안내가 달라진다.
 *
 * 그리고 `ZERO_VALID` 는 **제 요청서 §6 이 틀렸다는 증거**다 — 최종
 * 유효점 0개인 결과는 실제 계산 경로로 만들 수 있다.
 */
class RoundTripProbeTest {

    @Test
    fun probe() {
        val ref = DoubleArray(31) { if (it < 2 || it > 28) 70.0 else if (it % 2 == 0) 100.0 else 40.0 }
        val s = CalibrationSession(referenceCalApplied = true)
        repeat(8) {
            s.record(MeasureStep.ReferenceBefore, ref)
            s.record(MeasureStep.ReferenceAfter, ref)
            s.record(MeasureStep.Target, DoubleArray(31) { 70.0 })
        }
        val r = s.result()!!
        val q = qualityFromSession(
            r, DoubleArray(31) { 0.0 }, DoubleArray(31) { 0.0 },
            referenceCalRangeHz = 20.0..20000.0, dspVerifiedBySignal = true,
        )
        val o = calibrateFromSession(r, q).getOrThrow()
        val decoded = decodeCurves(encodeCurves(o)).getOrThrow()
        println(
            "ROUNDTRIP flags=${o.limitedByMaxCorrection.count { it }}->" +
                "${decoded.limitedByMaxCorrection.count { it }} " +
                "maskEqual=${o.correction.valid.contentEquals(decoded.correction.valid)} " +
                "beforeLimit=${o.unsupportedReasonsKo().any { it.contains("상한") }} " +
                "afterLimit=${decoded.unsupportedReasonsKo().any { it.contains("상한") }} " +
                "beforeVerdict=${judgeCalibration(q, o).verdict} " +
                "afterVerdict=${judgeCalibration(q, decoded).verdict}",
        )

        val tiny = calibrateFromSession(r, q, CalibrationSettings(maxCorrectionDb = 0.000001)).getOrThrow()
        println(
            "ZERO_VALID generated=true norm=${tiny.normalizeSupportPoints} " +
                "valid=${tiny.correction.validCount} final=${judgeCalibration(q, tiny).verdict}",
        )
    }
}
