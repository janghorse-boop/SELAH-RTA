package kr.joa.selahrta.dsp

import org.junit.Test
import kotlin.math.log2

/**
 * **Codex 가 보낸 `CP01-CP04-RecheckProbe.kt` 를 본문 그대로 옮긴 것**
 * (`docs/review/CP01-CP04-RecheckProbe.kt`, 2026-09-23 재검토 RCP01~RCP03).
 *
 * 바꾼 것은 JUnit 껍데기와 import 뿐이다. 계산과 출력 줄은 손대지 않았다.
 *
 * **회귀 시험이 아니라 관측 도구다.** 지금의 값을 정답으로 못 박지 않는다.
 *
 * ## 고치기 전 (검토서가 보고한 값)
 *
 * ```
 * KEPT_GATE kept=2 total=8 minimum=8 verdict=Pass
 * ZERO_NORMALIZATION verdict=Pass usable=19/31 pointsUsed=0 validCount=64 correction=10.0
 * UNEQUAL_SUPPORT verdict=Pass identicalInputs=true correction=-3.500000000000007 valid=48
 * ```
 */
class RecheckProbeTest {

    @Test
    fun probe() {
        val n = ThirdOctave.BAND_COUNT
        fun flat(x: Double) = DoubleArray(n) { x }
        fun session(ref: DoubleArray, target: DoubleArray): SessionResult {
            val s = CalibrationSession()
            repeat(8) {
                s.putFrame(MeasureStep.ReferenceBefore, ref)
                s.putFrame(MeasureStep.ReferenceAfter, ref)
                s.record(MeasureStep.Target, target)
            }
            return s.result()!!
        }

        val s = CalibrationSession()
        for (step in MeasureStep.entries) {
            repeat(3) { s.putFrame(step, flat(0.0)) }
            repeat(2) { s.putFrame(step, flat(50.0)) }
            repeat(3) { s.putFrame(step, flat(100.0)) }
        }
        val r = s.result()!!
        val q = qualityFromSession(r, flat(0.0), dspVerifiedBySignal = true)
        println(
            "KEPT_GATE kept=${r.target.keptFrames} total=${r.target.totalFrames} " +
                "minimum=${r.minKeptFramesPerStep} verdict=${judgeQuality(q).verdict}",
        )

        val r2 = session(flat(60.0), flat(50.0))
        val noise = DoubleArray(n) {
            if (ThirdOctave.exactCenter(it) in 250.0..3500.0) 50.0 else 0.0
        }
        val q2 = qualityFromSession(r2, noise, referenceCalRangeHz = 20.0..20000.0, dspVerifiedBySignal = true)
        val o2 = calibrateFromSession(r2, q2).getOrNull()
        println(
            "ZERO_NORMALIZATION verdict=${judgeQuality(q2).verdict} usable=${q2.usableCount}/$n " +
                "pointsUsed=${o2?.internalNormalized?.pointsUsed ?: "-"} " +
                "validCount=${o2?.correction?.validCount ?: "-"} " +
                "correction=${o2?.correction?.db?.filterIndexed { i, _ -> o2.correction.valid[i] }?.firstOrNull() ?: "거절"}",
        )

        val slope = DoubleArray(n) { 50 + 4 * log2(ThirdOctave.exactCenter(it) / 1000.0) }
        val r3 = session(slope, slope)
        val q3 = qualityFromSession(r3, flat(0.0), referenceCalRangeHz = 1000.0..16000.0, dspVerifiedBySignal = true)
        val o3 = calibrateFromSession(r3, q3).getOrNull()
        println(
            "UNEQUAL_SUPPORT verdict=${judgeQuality(q3).verdict} identicalInputs=true " +
                "correction=${o3?.correction?.db?.filterIndexed { i, _ -> o3.correction.valid[i] }?.firstOrNull() ?: "거절"} " +
                "valid=${o3?.correction?.validCount ?: "-"}",
        )

        // 기준 배경까지 재 준 경우 — RCP01 의 두 반례가 **계산 자체로도**
        // 고쳐졌는지 본다. 거절만으로 덮고 넘어가지 않는다.
        val q2b = qualityFromSession(r2, noise, referenceNoiseDb = noise, referenceCalRangeHz = 20.0..20000.0, dspVerifiedBySignal = true)
        val o2b = calibrateFromSession(r2, q2b)
        println(
            "ZERO_NORMALIZATION_WITH_REFSNR ok=${o2b.isSuccess} " +
                "why=${o2b.exceptionOrNull()?.message?.take(30) ?: "-"}",
        )
        val q3b = qualityFromSession(r3, flat(0.0), referenceNoiseDb = flat(0.0), referenceCalRangeHz = 1000.0..16000.0, dspVerifiedBySignal = true)
        val o3b = calibrateFromSession(r3, q3b).getOrNull()
        println(
            "UNEQUAL_SUPPORT_WITH_REFSNR identicalInputs=true " +
                "correction=${o3b?.correction?.db?.filterIndexed { i, _ -> o3b.correction.valid[i] }?.firstOrNull() ?: "거절"} " +
                "valid=${o3b?.correction?.validCount ?: "-"} " +
                "normPoints=${o3b?.normalizeSupportPoints ?: "-"} bands=${o3b?.normalizeBandsUsed ?: "-"}",
        )
    }
}
