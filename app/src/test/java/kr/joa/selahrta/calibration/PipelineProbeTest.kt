package kr.joa.selahrta.calibration

import kr.joa.selahrta.dsp.CurvePoint
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.dsp.calibrateResponse
import org.junit.Test

/**
 * **Codex 가 보낸 `Calibration-PipelineProbe.kt` 의 관측점 중 codec 쪽**
 * (`docs/review/Calibration-PipelineProbe.kt`, 2026-09-23 검토서 CP01~CP04).
 *
 * **회귀 시험이 아니라 관측 도구다.**
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
 * ## 세션 관측 넷은 dsp 로 옮겨 갔다
 *
 * CP04 후속 고침으로 기준 장이 `CalibratedReferenceSpectrum` 이어야
 * 하는데, 그 생성자는 dsp 모듈 안쪽이라 app 시험에서는 세션을 만들 수
 * 없다 — **그것이 보증의 요점이다**(app 의 제품 코드도 못 만든다).
 *
 * 그 관측들은 dsp 쪽 probe 가 이어서 찍는다:
 * `RecheckProbeTest`(불안정 기준·모양 변화), `ApprovalProbeTest`(교집합),
 * `PostLimitProbeTest`(상한 결손), `BoundaryProbeTest`(CAL 경계).
 * `CENTER_CAL` 이 재던 「칸에 걸지 않으면 거절하는가」는 이제 **타입이
 * 막으므로** `CalibrationSessionTest.기준 장을 대상 경로로 넣을 수 없다`
 * 가 잰다.
 *
 * 여기 남는 것은 **손상된 곡선 파일**(CODEC_NAN)이다 — codec 이 app 에
 * 있기 때문이다.
 */
class PipelineProbeTest {

    @Test
    fun probe() {
        val n = ThirdOctave.BAND_COUNT
        // 고역이 6dB 낮은 대상 — 보정이 생기는 평범한 곡선이면 된다.
        val reference = (0 until n).map { CurvePoint(ThirdOctave.exactCenter(it), 70.0) }
        val internal = (0 until n).map {
            CurvePoint(ThirdOctave.exactCenter(it), if (it >= 24) 64.0 else 70.0)
        }
        val c = calibrateResponse(reference, internal)

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
    }
}
