package kr.joa.selahrta.calibration

import kr.joa.selahrta.dsp.BandNoise
import kr.joa.selahrta.dsp.CalibrationSettings
import kr.joa.selahrta.dsp.CurvePoint
import kr.joa.selahrta.dsp.QualityReport
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.dsp.calibrateResponse
import kr.joa.selahrta.dsp.judgeCalibration
import org.junit.Test

/**
 * **Codex 가 보낸 `L01-RoundTripProbe.kt` 의 관측점들**
 * (`docs/review/L01-RoundTripProbe.kt`, 2026-09-23 재검토 L01-R).
 *
 * **회귀 시험이 아니라 관측 도구다.**
 *
 * ## 고치기 전 (본문 그대로 옮겨 재현한 값)
 *
 * ```
 * ROUNDTRIP flags=67->0 maskEqual=true
 *           beforeLimit=true afterLimit=false
 *           beforeVerdict=Fail afterVerdict=Fail
 * ZERO_VALID generated=true norm=40 valid=0 final=Fail
 * ```
 *
 * ## 새 API 로 겨눈 것
 *
 * 세션으로 만들던 것을 [calibrateResponse] 로 바로 만든다. 기준 장을
 * 세션에 넣으려면 `CalibratedReferenceSpectrum` 이 필요한데 그 생성자는
 * dsp 모듈 안쪽이라 여기서 만들 수 없다 — **그것이 CP04 후속 고침의
 * 요점이다.** 관측점(왕복이 까닭을 잃는가, 최종 유효점 0개가 나오는가)은
 * 그대로다.
 */
class RoundTripProbeTest {

    private val n = ThirdOctave.BAND_COUNT

    /** 톱니형 기준 — 상한을 넘게 만든다. */
    private fun sawReference() = (0 until n).map {
        val db = if (it < 2 || it > 28) 70.0 else if (it % 2 == 0) 100.0 else 40.0
        CurvePoint(ThirdOctave.exactCenter(it), db)
    }

    private fun flatTarget() = (0 until n).map { CurvePoint(ThirdOctave.exactCenter(it), 70.0) }

    /** 두 경로 다 조용하고 CAL 이 전 대역인 보고. 판정을 내리려면 필요하다. */
    private fun quality() = QualityReport(
        bands = (0 until n).map { BandNoise(ThirdOctave.exactCenter(it), 70.0, 0.0) },
        repeatSpreadDb = 0.5,
        referenceDriftDb = 0.0,
        referenceBandDriftDb = 0.0,
        minFramesPerStep = 16,
        referenceBands = (0 until n).map { BandNoise(ThirdOctave.exactCenter(it), 70.0, 0.0) },
        referenceCalRangeHz = 20.0..20_000.0,
        dspVerifiedBySignal = true,
    )

    @Test
    fun probe() {
        val q = quality()
        val o = calibrateResponse(sawReference(), flatTarget())
        val decoded = decodeCurves(encodeCurves(o)).getOrThrow()
        println(
            "ROUNDTRIP flags=${o.limitedByMaxCorrection?.count { it }}->" +
                "${decoded.limitedByMaxCorrection?.count { it }} " +
                "maskEqual=${o.correction.valid.contentEquals(decoded.correction.valid)} " +
                "beforeLimit=${o.unsupportedReasonsKo().any { it.contains("상한") }} " +
                "afterLimit=${decoded.unsupportedReasonsKo().any { it.contains("상한") }} " +
                "beforeVerdict=${judgeCalibration(q, o).verdict} " +
                "afterVerdict=${judgeCalibration(q, decoded).verdict}",
        )

        // 아주 작은 상한은 현장 값이 아니라 **그 분기에 닿기 위한 설정**이다.
        val tiny = calibrateResponse(
            sawReference(), flatTarget(), CalibrationSettings(maxCorrectionDb = 0.000001),
        )
        println(
            "ZERO_VALID generated=true norm=${tiny.normalizeSupportPoints} " +
                "valid=${tiny.correction.validCount} final=${judgeCalibration(q, tiny).verdict}",
        )
    }
}
