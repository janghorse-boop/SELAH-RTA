package kr.joa.selahrta.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **저장된 절대 SPL 교정을 아직 믿어도 되는가**(독립 검토 R06).
 *
 * gain 노브는 읽을 수 없다. 대신 잡음 바닥이 이득의 그림자라 그것을
 * 견준다 — **증거가 아니라 단서**이고, 판정도 「확인하라」에서 멈춘다.
 */
class GainDriftTest {

    @Test
    fun `비슷하면 그대로 믿는다`() {
        val v = judgeGainDrift(savedNoiseDbfs = -62.0, nowNoiseDbfs = -60.0)
        assertEquals(GainTrust.Consistent, v.trust)
        assertEquals(2.0, v.driftDb!!, 1e-9)
        assertNull("멀쩡하면 말을 얹지 않는다", v.noticeKo)
    }

    @Test
    fun `많이 벌어지면 확인하라고 한다`() {
        val v = judgeGainDrift(savedNoiseDbfs = -62.0, nowNoiseDbfs = -50.0)
        assertEquals(GainTrust.Suspect, v.trust)
        assertEquals(12.0, v.driftDb!!, 1e-9)
        assertNotNull(v.noticeKo)
        assertTrue(v.noticeKo!!, v.noticeKo.contains("올랐습니다"))
    }

    /** 내려간 것도 같은 고장이다 — 부호만 반대다. */
    @Test
    fun `내려간 것도 잡는다`() {
        val v = judgeGainDrift(savedNoiseDbfs = -50.0, nowNoiseDbfs = -62.0)
        assertEquals(GainTrust.Suspect, v.trust)
        assertTrue(v.noticeKo!!, v.noticeKo.contains("내렸습니다"))
    }

    /**
     * **단서일 뿐이라고 말한다.**
     *
     * 「이득이 바뀌었습니다」로 단정하면, 방이 시끄러워졌을 뿐인 경우에
     * 멀쩡한 교정을 다시 하게 만든다.
     */
    @Test
    fun `단정하지 않고 다른 까닭도 말한다`() {
        val s = judgeGainDrift(-62.0, -50.0).noticeKo!!
        assertTrue(s, s.contains("바뀌었으면"))
        assertTrue(s, s.contains("시끄러워졌거나"))
    }

    /**
     * **적어 두지 않은 것을 괜찮은 것으로 치지 않는다.**
     *
     * 예전에 저장한 교정에는 잡음이 없다. 그때 조용히 통과시키면 사람은
     * 앱이 지켜 주는 줄 안다.
     */
    @Test
    fun `적어 둔 것이 없으면 모른다고 한다`() {
        for (v in listOf(
            judgeGainDrift(null, -60.0),
            judgeGainDrift(-60.0, null),
            judgeGainDrift(Double.NaN, -60.0),
            judgeGainDrift(-60.0, Double.NaN),
        )) {
            assertEquals(GainTrust.Unknown, v.trust)
            assertEquals(GAIN_UNKNOWN_KO, v.noticeKo)
            assertNull(v.driftDb)
        }
    }

    @Test
    fun `문턱은 바꿀 수 있다`() {
        assertEquals(
            GainTrust.Suspect,
            judgeGainDrift(-62.0, -58.0, maxDriftDb = 3.0).trust,
        )
        assertEquals(
            GainTrust.Consistent,
            judgeGainDrift(-62.0, -58.0, maxDriftDb = 5.0).trust,
        )
    }

    /** 노브를 못 읽는다는 사실은 **단서가 있든 없든** 참이다. */
    @Test
    fun `늘 적는 말이 노브를 못 읽는다고 밝힌다`() {
        assertTrue(GAIN_NOT_READABLE_KO, GAIN_NOT_READABLE_KO.contains("읽을 수 없"))
        assertTrue(GAIN_UNKNOWN_KO, GAIN_UNKNOWN_KO.contains("읽을 수 없"))
    }

    @Test
    fun `화면 문구에 마크다운이 없다`() {
        val all = listOf(GAIN_UNKNOWN_KO, GAIN_NOT_READABLE_KO, gainSuspectKo(7.5))
        all.forEach {
            assertFalse("별표가 있다: $it", it.contains("*"))
            assertFalse("백틱이 있다: $it", it.contains("`"))
        }
    }
}
