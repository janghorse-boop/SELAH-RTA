package kr.joa.selahrta.dsp

import org.junit.Test

/**
 * **Codex 가 보낸 `RCP-ApprovalProbe.kt` 를 본문 그대로 옮긴 것**
 * (`docs/review/RCP-ApprovalProbe.kt`, 2026-09-23 재검토 RCP-F01).
 *
 * 바꾼 것은 JUnit 껍데기와 import 뿐이다.
 *
 * **회귀 시험이 아니라 관측 도구다.**
 *
 * ## 고치기 전 (검토서가 보고한 값)
 *
 * ```
 * INTERSECTION target=20 reference=19 common=8 total=31 verdict=Pass auto=true correctionValid=24 normPoints=24
 * NARROW_CAL verdict=Pass auto=true correctionValid=4 normPoints=4 bands=2
 * ```
 *
 * 각 경로는 최소 비율 60% 를 넘는데 **교집합은 8/31 = 25.8%** 다.
 * 그리고 CAL 이 1000~1300Hz 뿐이어도 좁은 범위 경고조차 없다.
 */
class ApprovalProbeTest {

    @Test
    fun probe() {
        val n = ThirdOctave.BAND_COUNT
        fun flat(x: Double) = DoubleArray(n) { x }
        val s = CalibrationSession()
        repeat(8) { for (step in MeasureStep.entries) s.putFrame(step, flat(70.0)) }
        val r = s.result()!!
        val targetNoise = DoubleArray(n) { if (it <= 18 || it == 30) 40.0 else 70.0 }
        val refNoise = DoubleArray(n) { if (it >= 12) 40.0 else 70.0 }
        val q = qualityFromSession(
            r, targetNoise, referenceNoiseDb = refNoise,
            referenceCalRangeHz = 20.0..20000.0, dspVerifiedBySignal = true,
        )
        val judged = judgeQuality(q)
        val out = calibrateFromSession(r, q).getOrNull()
        // **최종 승인은 judgeCalibration 이 한다** — 곡선까지 보고 나서.
        val approved = out?.let { judgeCalibration(q, it) }
        println(
            "INTERSECTION target=${q.usableCount} reference=${q.referenceUsable.count { it }} " +
                "common=${q.bothUsable.count { it }} total=$n verdict=${judged.verdict} " +
                "auto=${approved?.mayAutoApply ?: judged.mayAutoApply} " +
                "correctionValid=${out?.correction?.validCount ?: "-"} " +
                "normPoints=${out?.normalizeSupportPoints ?: "-"}",
        )

        val all = qualityFromSession(
            r, flat(40.0), referenceNoiseDb = flat(40.0),
            referenceCalRangeHz = 1000.0..1300.0, dspVerifiedBySignal = true,
        )
        val narrow = calibrateFromSession(r, all).getOrNull()
        val narrowApproved = narrow?.let { judgeCalibration(all, it) }
        println(
            "NARROW_CAL verdict=${judgeQuality(all).verdict} " +
                "auto=${narrowApproved?.mayAutoApply ?: judgeQuality(all).mayAutoApply} " +
                "correctionValid=${narrow?.correction?.validCount ?: "-"} " +
                "normPoints=${narrow?.normalizeSupportPoints ?: "-"} " +
                "bands=${narrow?.normalizeBandsUsed ?: "-"}",
        )
    }
}
