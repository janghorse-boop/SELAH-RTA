package kr.joa.selahrta.dsp

import org.junit.Test

/**
 * **Codex 가 보낸 `F01R-BoundaryProbe.kt` 를 본문 그대로 옮긴 것**
 * (`docs/review/F01R-BoundaryProbe.kt`, 2026-09-23 재검토 L01).
 *
 * 바꾼 것은 JUnit 껍데기와 import 뿐이다.
 *
 * **회귀 시험이 아니라 관측 도구다.**
 *
 * ## 고치기 전 (검토서가 보고한 값)
 *
 * ```
 * CAL_ONLY approved=18 supported=16 maxCorrection=0.0 pre=Fail final=Fail
 * ```
 *
 * 보정값이 전부 0dB 라 상한을 넘은 점이 하나도 없는데, 문구는
 * 「보정량이 상한(12dB)을 넘는 자리가 많다」고 말한다. 판정은 맞고
 * **까닭이 틀렸다.**
 *
 * 뒤의 `BOUNDARY` 줄은 CAL 경계 169조합에서 6·12·24점/옥타브의 지원
 * 대역 수가 갈리는지 보는 것이다 — 갈리면 찍힌다.
 */
class BoundaryProbeTest {

    @Test
    fun probe() {
        val s = CalibrationSession(referenceCalApplied = true)
        repeat(8) { for (step in MeasureStep.entries) s.record(step, DoubleArray(31) { 70.0 }) }
        val r = s.result()!!
        val q0 = qualityFromSession(
            r, DoubleArray(31) { 0.0 }, DoubleArray(31) { 0.0 },
            referenceCalRangeHz = 20.0..1300.0, dspVerifiedBySignal = true,
        )
        val o0 = calibrateFromSession(r, q0).getOrThrow()
        println(
            "CAL_ONLY approved=${q0.approvedCount} supported=${o0.supportedBands.size} " +
                "maxCorrection=${o0.correction.db.maxOf { kotlin.math.abs(it) }} " +
                "pre=${judgeQuality(q0).verdict} final=${judgeCalibration(q0, o0).verdict}",
        )
        println(judgeCalibration(q0, o0).reasonsKo)

        var shown = 0
        for (lo in 0..12) {
            for (hi in 18..30) {
                val range = (ThirdOctave.exactCenter(lo) * 0.999)..(ThirdOctave.exactCenter(hi) * 1.001)
                val q = qualityFromSession(
                    r, DoubleArray(31) { 0.0 }, DoubleArray(31) { 0.0 },
                    referenceCalRangeHz = range, dspVerifiedBySignal = true,
                )
                val os = listOf(6, 12, 24).map {
                    calibrateFromSession(r, q, CalibrationSettings(pointsPerOctave = it)).getOrNull()
                }
                if (os.any { it == null }) continue
                val counts = os.map { it!!.supportedBands.size }
                val vs = os.map { judgeCalibration(q, it!!).verdict }
                if (counts.distinct().size > 1 && shown++ < 10) {
                    println("BOUNDARY lo=$lo hi=$hi count=$counts verdict=$vs")
                }
            }
        }
    }
}
