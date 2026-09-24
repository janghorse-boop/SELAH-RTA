package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **기준의 CAL 전 레벨이 세션을 지나 살아남는가.**
 *
 * 절대 레벨을 폰으로 옮기려면 이 값이 있어야 한다. 없으면 CAL 이 걸린
 * 값을 쓰게 되고, 그러면 **CAL 의 대역 평균만큼이 통째로 폰의 보정값에
 * 들어간다.**
 *
 * 되돌려 셈할 수 없다는 것이 요점이다 — 밴드 안에서 응답이 변하면 걸린
 * 뒤의 밴드 평균이 원시 평균과 다르다(R05 와 같은 까닭). 그래서 잴 때
 * 함께 남긴다.
 */
class RawReferenceLevelTest {

    private val n = ThirdOctave.BAND_COUNT
    private fun flat(db: Double) = DoubleArray(n) { db }

    private fun sessionWith(
        calDb: Double,
        rawDb: Double,
        targetDb: Double,
    ): SessionResult {
        val s = CalibrationSession(maxFrameDeviationDb = 50.0)
        repeat(8) {
            s.recordReference(
                MeasureStep.ReferenceBefore,
                testReferenceSpectrum(flat(calDb), rawBandsDb = flat(rawDb)),
            )
        }
        repeat(8) { s.record(MeasureStep.Target, flat(targetDb)) }
        repeat(8) {
            s.recordReference(
                MeasureStep.ReferenceAfter,
                testReferenceSpectrum(flat(calDb), rawBandsDb = flat(rawDb)),
            )
        }
        return s.result()!!
    }

    @Test
    fun `원시 레벨이 결과까지 따라온다`() {
        val r = sessionWith(calDb = -30.0, rawDb = -36.0, targetDb = -20.0)
        assertNotNull("원시 평균이 있어야 한다", r.referenceRawMeanDb)
        assertEquals("걸린 값이 아니라 원시 값이어야 한다", -36.0, r.referenceRawMeanDb!![0], 1e-9)
        assertEquals("걸린 값은 그대로다", -30.0, r.referenceMeanDb[0], 1e-9)
    }

    /**
     * **걸린 값을 쓰면 CAL 의 몫만큼 틀린다.** 그 차이가 실제로 나는지
     * 숫자로 보여 둔다 — 「원시를 써야 한다」가 규칙이 아니라 사실이 되게.
     */
    @Test
    fun `원시 대신 걸린 값을 쓰면 CAL 만큼 어긋난다`() {
        val r = sessionWith(calDb = -30.0, rawDb = -36.0, targetDb = -20.0)
        val usable = BooleanArray(n) { true }

        val right = computeLevelTransfer(
            referenceRawMeanDb = r.referenceRawMeanDb,
            targetMeanDb = flat(-20.0),
            referenceOffsetDb = 120.0,
            usable = usable,
        ).getOrThrow()

        val wrong = computeLevelTransfer(
            referenceRawMeanDb = r.referenceMeanDb, // 일부러 걸린 값을 넣는다
            targetMeanDb = flat(-20.0),
            referenceOffsetDb = 120.0,
            usable = usable,
        ).getOrThrow()

        // CAL 이 6dB 올린 값이었으므로 그만큼 어긋난다.
        assertEquals(6.0, wrong.targetOffsetDb - right.targetOffsetDb, 1e-9)
        assertTrue("작지 않은 차이다", kotlin.math.abs(wrong.targetOffsetDb - right.targetOffsetDb) > 1.0)
    }

    /** 대상만 잰 세션에는 원시 기준이 없다 — 그때는 옮기지 않는다. */
    @Test
    fun `기준을 안 재면 원시 레벨도 없다`() {
        val s = CalibrationSession(maxFrameDeviationDb = 50.0)
        repeat(8) { s.record(MeasureStep.Target, flat(-20.0)) }
        assertNull("세 단계가 안 찼으면 결과 자체가 없다", s.result())
    }

    @Test
    fun `되돌리면 기준과 같은 음압이 된다`() {
        val r = sessionWith(calDb = -30.0, rawDb = -36.0, targetDb = -20.0)
        val t = computeLevelTransfer(
            r.referenceRawMeanDb,
            flat(-20.0),
            referenceOffsetDb = 120.0,
            usable = BooleanArray(n) { true },
        ).getOrThrow()

        assertEquals(
            "기준으로 읽은 음압과 대상으로 읽은 음압이 같아야 한다",
            -36.0 + 120.0,
            -20.0 + t.targetOffsetDb,
            1e-9,
        )
    }
}
