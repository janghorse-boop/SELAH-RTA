package kr.joa.selahrta.calibration

import kr.joa.selahrta.dsp.DspProbeResult
import kr.joa.selahrta.dsp.DspVerdict
import kr.joa.selahrta.dsp.ThirdOctave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **증거의 일생**(독립 재검토 CAR-02).
 *
 * 검토자가 재현한 것: 성공한 입력 점검이 있고, 배경이 나빠져 다시
 * 검사하다가 **취소**하면, 취소 전의 성공이 그대로 승인 근거로 남았다.
 * 실패 분기에서만 지웠고 취소는 그 분기로 들어가지 않았기 때문이다.
 *
 * ```
 * CANCELLED_RECHECK_VERDICT verdict=Pass staleUsable=31 actualUsable=0
 * ```
 *
 * 「몇 시간 지난 증거를 허용할지」의 경계 문제가 아니다. **재검사를
 * 시작했는데 취소 전후가 구별되지 않는** 즉시 재현이다.
 *
 * 고친 방식: **시작하는 순간 버린다.** 끝까지 마친 검사만 되살린다.
 */
class EvidenceLifecycleTest {

    private val evidence = "cal|Usb|UMC404HD|card=1;device=0|Unprocessed|ch0|fs48000|n4096"
    private val other = "cal|Usb|UMC404HD|card=1;device=0|Unprocessed|ch1|fs48000|n4096"

    private fun noise(level: Double) = List(ThirdOctave.BAND_COUNT) { level }

    private fun dsp(verdict: DspVerdict = DspVerdict.NoTimeVaryingFound) = DspProbeResult(
        verdict = verdict,
        broadbandDriftDb = 0.1,
        bandShapeDriftDb = 0.2,
        worstBand = 15,
        repeatSpreadDb = null,
        bandsConsidered = ThirdOctave.BAND_COUNT,
        framesUsed = 40,
        reasonsKo = listOf("까닭"),
    )

    /** 성공한 검사 하나가 들어 있는 상태. */
    private fun passed(): WizardState = WizardState()
        .startingCheck(evidence)
        .withNoise(evidence, noise(-90.0))
        .withDsp(evidence, dsp(), clipped = false)

    @Test
    fun `끝까지 마친 검사는 남는다`() {
        val st = passed()
        assertEquals(noise(-90.0), st.noiseFloorByKey[evidence])
        assertTrue(st.dspByKey[evidence]?.verifiedBySignal == true)
    }

    /**
     * **가장 중요한 시험.** 재검사를 시작하고 취소하면 옛 성공이 남지
     * 않는다 — 검토자가 재현한 바로 그 순서다.
     */
    @Test
    fun `재검사를 취소하면 옛 성공이 남지 않는다`() {
        val after = passed().startingCheck(evidence) // 시작만 하고 취소
        assertNull("취소 뒤에도 옛 배경이 남았다", after.noiseFloorByKey[evidence])
        assertNull("취소 뒤에도 옛 DSP 판정이 남았다", after.dspByKey[evidence])
    }

    /** 배경만 재고 끊겨도, **짝이 아닌 증거**는 남지 않는다. */
    @Test
    fun `배경만 재고 끊기면 DSP 는 없다`() {
        val half = passed().startingCheck(evidence).withNoise(evidence, noise(-40.0))
        assertEquals(noise(-40.0), half.noiseFloorByKey[evidence])
        assertNull("옛 DSP 판정이 새 배경과 짝지어졌다", half.dspByKey[evidence])
    }

    /**
     * **다른 경로의 증거는 건드리지 않는다.**
     *
     * ch0 을 다시 재느라 ch1 의 증거까지 지우면, 멀쩡한 자료를 잃고
     * 사람이 까닭 없이 다시 재게 된다.
     */
    @Test
    fun `다른 경로의 증거는 그대로 둔다`() {
        val both = passed()
            .withNoise(other, noise(-80.0))
            .withDsp(other, dsp(), clipped = false)
        val after = both.startingCheck(evidence)
        assertNull(after.noiseFloorByKey[evidence])
        assertEquals("남의 증거를 지웠다", noise(-80.0), after.noiseFloorByKey[other])
        assertTrue(after.dspByKey[other]?.verifiedBySignal == true)
    }

    /**
     * **화면 값도 함께 비운다.**
     *
     * 남겨 두면 새 결과 옆에 옛 수치가 그대로 붙어 있어, 방금 잰 것처럼
     * 읽힌다(기기에서 확인한 자리다).
     */
    @Test
    fun `시작하면 화면 값도 비운다`() {
        val after = passed().startingCheck(evidence)
        assertNull(after.noiseFloorDb)
        assertNull(after.dsp)
    }

    /**
     * 수집 중에 입력이 바뀌면 **그 짝을 통째로 버린다.**
     *
     * 배경만 적히고 DSP 는 다른 입력의 것이 되면, 그 둘은 짝이 아니다.
     * 검토자가 `CHECK_INPUT_CHANGED … savedUnderCh0=true` 로 ch1 의
     * 결과가 ch0 이름으로 저장되는 것을 보였다.
     */
    @Test
    fun `수집 중에 입력이 바뀌면 짝을 통째로 버린다`() {
        val half = WizardState().startingCheck(evidence).withNoise(evidence, noise(-90.0))
        val after = half.forgetEvidence(evidence)
        assertNull(after.noiseFloorByKey[evidence])
        assertNull(after.dspByKey[evidence])
    }
}
