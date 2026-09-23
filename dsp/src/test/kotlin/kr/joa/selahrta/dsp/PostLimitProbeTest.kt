package kr.joa.selahrta.dsp

import org.junit.Test
import kotlin.math.abs

/**
 * **Codex 가 보낸 `RCPF-PostLimitProbe.kt` 를 본문 그대로 옮긴 것**
 * (`docs/review/RCPF-PostLimitProbe.kt`, 2026-09-23 재검토 F01-R).
 *
 * 바꾼 것은 JUnit 껍데기와 import 뿐이다.
 *
 * **회귀 시험이 아니라 관측 도구다.**
 *
 * ## 고치기 전 (검토서가 보고한 값)
 *
 * ```
 * POST_LIMIT approved=30/31 pre=Pass final=Pass auto=true
 * valid=49/120 centers=3/31
 * span=25.19842099789747..19330.54592372118 norm=40
 * ```
 *
 * 축 120점 중 49점만 유효하고 원래 중심 31개 중 3개만 살아남았는데,
 * **양끝에 유효점이 있다는 이유로** 최종 Pass 가 난다.
 */
class PostLimitProbeTest {

    @Test
    fun probe() {
        val n = ThirdOctave.BAND_COUNT
        fun flat(x: Double) = DoubleArray(n) { x }
        // 승인이 본 CAL 범위와 실제로 건 범위가 같아야 하므로
        // 관측마다 세션을 그 범위로 만든다.
        fun session(ref: DoubleArray, cal: ClosedFloatingPointRange<Double>): SessionResult {
            val s = CalibrationSession()
            repeat(8) {
                s.putFrame(MeasureStep.ReferenceBefore, ref, calRangeHz = cal)
                s.putFrame(MeasureStep.ReferenceAfter, ref, calRangeHz = cal)
                s.record(MeasureStep.Target, flat(70.0))
            }
            return s.result()!!
        }
        val r = session(flat(70.0), 20.0..20000.0)
        val tn = DoubleArray(n) { if (it <= 18 || it == 30) 40.0 else 70.0 }
        val rn = DoubleArray(n) { if (it >= 12) 40.0 else 70.0 }
        val q = qualityFromSession(r, tn, rn, referenceCalRangeHz = 20.0..20000.0, dspVerifiedBySignal = true)
        val o = calibrateFromSession(r, q).getOrThrow()
        println(
            "INTERSECTION approved=${q.approvedCount} verdict=${judgeCalibration(q, o).verdict} " +
                "auto=${judgeCalibration(q, o).mayAutoApply}",
        )

        val rNarrow = session(flat(70.0), 1000.0..1300.0)
        val nq = qualityFromSession(rNarrow, flat(40.0), flat(40.0), referenceCalRangeHz = 1000.0..1300.0, dspVerifiedBySignal = true)
        val no = calibrateFromSession(rNarrow, nq).getOrThrow()
        println("NARROW verdict=${judgeCalibration(nq, no).verdict} auto=${judgeCalibration(nq, no).mayAutoApply}")

        val ref = DoubleArray(n) { if (it < 2 || it > 28) 70.0 else if (it % 2 == 0) 100.0 else 40.0 }
        val rr = session(ref, 20.0..20000.0)
        val qq = qualityFromSession(rr, flat(0.0), flat(0.0), referenceCalRangeHz = 20.0..20000.0, dspVerifiedBySignal = true)
        val oo = calibrateFromSession(rr, qq).getOrThrow()
        val c = oo.correction
        val centers = (0 until n).count { k ->
            val i = c.hz.indices.minBy { abs(c.hz[it] - ThirdOctave.exactCenter(k)) }
            c.valid[i]
        }
        val f = c.hz.filterIndexed { i, _ -> c.valid[i] }
        println(
            "POST_LIMIT approved=${qq.approvedCount}/$n pre=${judgeQuality(qq).verdict} " +
                "final=${judgeCalibration(qq, oo).verdict} auto=${judgeCalibration(qq, oo).mayAutoApply} " +
                "valid=${c.validCount}/${c.size} centers=$centers/$n " +
                "span=${f.first()}..${f.last()} norm=${oo.normalizeSupportPoints}",
        )
    }
}
