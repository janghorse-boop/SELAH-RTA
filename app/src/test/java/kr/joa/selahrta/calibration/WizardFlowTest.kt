package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.MicSeparation
import kr.joa.selahrta.audio.MicSeparationResult
import kr.joa.selahrta.dsp.BandNoise
import kr.joa.selahrta.dsp.CurvePoint
import kr.joa.selahrta.dsp.DspProbeResult
import kr.joa.selahrta.dsp.DspVerdict
import kr.joa.selahrta.dsp.QualityReport
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.dsp.calibrateResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **순서와 관문.**
 *
 * 여기가 틀리면 「재지도 않고 저장된 프로파일」이 생긴다. 화면 안에 두면
 * 기기를 들고 눌러 봐야만 확인되므로 밖으로 뺐고, 그러니 여기서 전부
 * 확인한다.
 */
class WizardFlowTest {

    private val cal = CalInfo("17860.txt", "abc123", 10.0, 25_000.0, 300)

    private fun dsp(
        verdict: DspVerdict,
        reasons: List<String> = listOf("까닭"),
    ) = DspProbeResult(
        verdict = verdict,
        broadbandDriftDb = 0.1,
        bandShapeDriftDb = 0.2,
        worstBand = 15,
        repeatSpreadDb = null,
        bandsConsidered = 31,
        framesUsed = 40,
        reasonsKo = reasons,
    )

    private fun separable() = MicSeparationResult(MicSeparation.Separable, "둘이 갈린다")

    private fun sessionResult(
        noStable: Boolean = false,
        withProof: Boolean = true,
    ) = fakeSession(noStable = noStable, withProof = withProof)

    /** 1~3단계를 다 갖춘 상태. */
    private fun ready() = WizardState(
        cal = cal,
        phantomAcknowledged = true,
        dsp = dsp(DspVerdict.NoTimeVaryingFound),
        effectsAllClear = true,
        separation = separable(),
    )

    // ------------------------------------------------------------------
    // 1단계 — 기준이 없으면 못 간다
    // ------------------------------------------------------------------

    @Test
    fun `CAL 이 없으면 막힌다`() {
        val g = gateFor(WizardState(phantomAcknowledged = true), WizardStep.Equipment)
        assertTrue(g is StepGate.Blocked)
        assertTrue(g.reasonsKo.toString(), g.reasonsKo.any { it.contains("CAL") })
    }

    /**
     * **팬텀전원은 확인이 아니라 들은 것이다.**
     *
     * 앱은 +48V 상태를 알 수 없다(지시서 3장). 그래도 묻는 까닭은,
     * 안 켜면 신호가 아예 안 들어와 사람이 한참을 헤매기 때문이다.
     */
    @Test
    fun `팬텀전원을 확인받지 않으면 막힌다`() {
        val g = gateFor(WizardState(cal = cal), WizardStep.Equipment)
        assertTrue(g is StepGate.Blocked)
        assertTrue(g.reasonsKo.toString(), g.reasonsKo.any { it.contains("앱은 이 상태를 알 수 없습니다") })
    }

    @Test
    fun `둘 다 갖추면 지나간다`() {
        val s = WizardState(cal = cal, phantomAcknowledged = true)
        assertEquals(StepGate.Allowed, gateFor(s, WizardStep.Equipment))
        assertEquals(WizardStep.InputCheck, nextStep(s))
    }

    // ------------------------------------------------------------------
    // 2단계 — 막는 것과 알리는 것
    // ------------------------------------------------------------------

    @Test
    fun `점검하지 않으면 막힌다`() {
        val s = ready().copy(dsp = null, step = WizardStep.InputCheck)
        assertTrue(gateFor(s, WizardStep.InputCheck) is StepGate.Blocked)
        assertNull(nextStep(s))
    }

    /** **재지 못한 것은 통과가 아니다.** */
    @Test
    fun `장이 모자라 판정 못 한 것은 막힌다`() {
        val s = ready().copy(dsp = dsp(DspVerdict.NotEnoughData))
        assertTrue(gateFor(s, WizardStep.InputCheck) is StepGate.Blocked)
    }

    /**
     * **DSP 가 의심돼도 막지 않는다**(지시서 3장).
     *
     * 「제한적 정확도」로 적고 자동 적용만 금지한다. 다 막으면 보정 전후를
     * 눈으로 견주는 일까지 못 하게 된다.
     */
    @Test
    fun `DSP 가 의심되면 막지 않고 알린다`() {
        val s = ready().copy(dsp = dsp(DspVerdict.Suspect, listOf("AGC 자취")))
        val g = gateFor(s, WizardStep.InputCheck)
        assertTrue(g.toString(), g is StepGate.AllowedWithWarning)
        assertTrue(g.passable)
        assertTrue(g.reasonsKo.contains("AGC 자취"))
        assertTrue(g.reasonsKo.contains(LIMITED_ACCURACY_KO))
    }

    @Test
    fun `클리핑은 막는다`() {
        val s = ready().copy(clipped = true)
        val g = gateFor(s, WizardStep.InputCheck)
        assertTrue(g is StepGate.Blocked)
        assertTrue(g.reasonsKo.toString(), g.reasonsKo.any { it.contains("클리핑") })
    }

    /**
     * 신호로 못 찾았다고 **없는 것은 아니다.** API 가 못 껐다고 한 것이
     * 있으면 그대로 알린다.
     */
    @Test
    fun `API 로 못 끈 처리가 있으면 알린다`() {
        val s = ready().copy(effectsAllClear = false)
        val g = gateFor(s, WizardStep.InputCheck)
        assertTrue(g is StepGate.AllowedWithWarning)
        assertTrue(g.reasonsKo.contains(EFFECTS_STILL_ON_KO))
    }

    @Test
    fun `둘 다 깨끗하면 조용히 지나간다`() {
        assertEquals(StepGate.Allowed, gateFor(ready(), WizardStep.InputCheck))
    }

    // ------------------------------------------------------------------
    // 3단계 — 지시서 2.5 의 세 상태
    // ------------------------------------------------------------------

    @Test
    fun `구분 불가면 개별 교정을 막는다`() {
        val s = ready().copy(
            separation = MicSeparationResult(MicSeparation.Indistinguishable, "한 스트림에 섞인다"),
        )
        val g = gateFor(s, WizardStep.MicJudgement)
        assertTrue(g is StepGate.Blocked)
        assertTrue(g.reasonsKo.contains(INDISTINGUISHABLE_KO))
        assertTrue("판정 까닭도 같이 보여야 한다", g.reasonsKo.contains("한 스트림에 섞인다"))
    }

    @Test
    fun `논리 입력만 갈리면 이름을 붙이지 않는다고 알린다`() {
        val s = ready().copy(
            separation = MicSeparationResult(MicSeparation.LogicalOnly, "목록만 둘이다"),
        )
        val g = gateFor(s, WizardStep.MicJudgement)
        assertTrue(g is StepGate.AllowedWithWarning)
        assertTrue(g.reasonsKo.contains(LOGICAL_ONLY_KO))
    }

    @Test
    fun `갈리면 조용히 지나간다`() {
        assertEquals(StepGate.Allowed, gateFor(ready(), WizardStep.MicJudgement))
    }

    @Test
    fun `판정하지 않으면 막힌다`() {
        val s = ready().copy(separation = null)
        assertTrue(gateFor(s, WizardStep.MicJudgement) is StepGate.Blocked)
    }

    // ------------------------------------------------------------------
    // 4단계
    // ------------------------------------------------------------------

    @Test
    fun `세 번을 다 재지 않으면 막힌다`() {
        assertTrue(gateFor(ready(), WizardStep.Measure) is StepGate.Blocked)
    }

    @Test
    fun `쓸 만한 장이 없으면 막힌다`() {
        val s = ready().copy(session = sessionResult(noStable = true))
        val g = gateFor(s, WizardStep.Measure)
        assertTrue(g is StepGate.Blocked)
        assertTrue(g.reasonsKo.toString(), g.reasonsKo.any { it.contains("한 장도") })
    }

    /** 통로를 잘못 쓰면 여기까지 올 수 있다 — 조용히 넘기지 않는다. */
    @Test
    fun `기준에 CAL 이 안 걸렸으면 막힌다`() {
        val s = ready().copy(session = sessionResult(withProof = false))
        val g = gateFor(s, WizardStep.Measure)
        assertTrue(g is StepGate.Blocked)
        assertTrue(g.reasonsKo.toString(), g.reasonsKo.any { it.contains("CAL") })
    }

    @Test
    fun `케이스를 적지 않으면 알린다`() {
        val s = ready().copy(session = sessionResult(), caseRemoved = null)
        val g = gateFor(s, WizardStep.Measure)
        assertTrue(g is StepGate.AllowedWithWarning)
        assertTrue(g.reasonsKo.contains(CASE_UNKNOWN_KO))
    }

    @Test
    fun `케이스를 적으면 조용하다`() {
        val s = ready().copy(session = sessionResult(), caseRemoved = true)
        assertEquals(StepGate.Allowed, gateFor(s, WizardStep.Measure))
    }

    // ------------------------------------------------------------------
    // 5·6단계
    // ------------------------------------------------------------------

    private fun goodOutcome() = calibrateResponse(
        referencePoints = (0 until ThirdOctave.BAND_COUNT).map {
            CurvePoint(ThirdOctave.exactCenter(it), 70.0)
        },
        internalPoints = (0 until ThirdOctave.BAND_COUNT).map {
            CurvePoint(ThirdOctave.exactCenter(it), 50.0)
        },
    )

    private fun goodQuality(dspVerified: Boolean = true) = QualityReport(
        bands = (0 until ThirdOctave.BAND_COUNT).map {
            BandNoise(ThirdOctave.exactCenter(it), 70.0, 40.0)
        },
        repeatSpreadDb = 0.5,
        referenceDriftDb = 0.2,
        referenceBandDriftDb = 0.4,
        minFramesPerStep = 16,
        referenceBands = (0 until ThirdOctave.BAND_COUNT).map {
            BandNoise(ThirdOctave.exactCenter(it), 70.0, 40.0)
        },
        referenceCalRangeHz = 10.0..25_000.0,
        dspVerifiedBySignal = dspVerified,
    )

    @Test
    fun `곡선이 없으면 검토도 저장도 막힌다`() {
        val s = ready().copy(session = sessionResult(), caseRemoved = true)
        assertTrue(gateFor(s, WizardStep.Review) is StepGate.Blocked)
        assertTrue(gateFor(s, WizardStep.Save) is StepGate.Blocked)
    }

    @Test
    fun `멀쩡한 측정은 저장까지 간다`() {
        val o = goodOutcome()
        val s = ready().copy(
            session = sessionResult(),
            caseRemoved = true,
            outcome = o,
            quality = goodQuality(),
        )
        assertEquals(StepGate.Allowed, gateFor(s, WizardStep.Review))
        assertEquals(gateFor(s, WizardStep.Save).reasonsKo.toString(), StepGate.Allowed, gateFor(s, WizardStep.Save))
    }

    /**
     * **DSP 를 신호로 확인하지 못했으면 저장은 되되 자동 적용은 막힌다.**
     *
     * 지시서 3장이 정한 그대로다. 관문이 막지 않고 **판정이** 내린다.
     */
    @Test
    fun `DSP 미확인은 저장되되 자동 적용이 막힌다`() {
        val o = goodOutcome()
        val s = ready().copy(
            session = sessionResult(),
            caseRemoved = true,
            outcome = o,
            quality = goodQuality(dspVerified = false),
        )
        val g = gateFor(s, WizardStep.Save)
        assertTrue(g.toString(), g is StepGate.AllowedWithWarning)
        assertTrue(g.passable)
    }

    // ------------------------------------------------------------------
    // 길을 건너뛰지 못한다
    // ------------------------------------------------------------------

    /**
     * 뒤로 가서 값을 지우고 앞으로 건너뛰는 길을 막는다 — CAL 을 비운 채
     * 저장까지 가면 기준 없는 프로파일이 생긴다.
     */
    @Test
    fun `앞 단계가 비면 뒤에서 알아챈다`() {
        val s = ready().copy(
            cal = null,
            step = WizardStep.Save,
            session = sessionResult(),
            caseRemoved = true,
            outcome = goodOutcome(),
            quality = goodQuality(),
        )
        assertEquals(WizardStep.Equipment, blockedBefore(s))
    }

    @Test
    fun `길이 멀쩡하면 걸리는 것이 없다`() {
        val s = ready().copy(
            step = WizardStep.Save,
            session = sessionResult(),
            caseRemoved = true,
            outcome = goodOutcome(),
            quality = goodQuality(),
        )
        assertNull(blockedBefore(s))
    }

    @Test
    fun `막히면 다음으로 못 간다`() {
        val s = WizardState(step = WizardStep.Equipment)
        assertNull(nextStep(s))
    }

    @Test
    fun `경고는 넘어간다`() {
        val s = ready().copy(
            step = WizardStep.InputCheck,
            dsp = dsp(DspVerdict.Suspect),
        )
        assertEquals(WizardStep.MicJudgement, nextStep(s))
    }

    @Test
    fun `마지막 단계 다음은 없다`() {
        val s = ready().copy(
            step = WizardStep.Save,
            session = sessionResult(),
            caseRemoved = true,
            outcome = goodOutcome(),
            quality = goodQuality(),
        )
        assertNull(nextStep(s))
    }

    @Test
    fun `첫 단계 앞은 없다`() {
        assertNull(previousStep(WizardState()))
        assertEquals(WizardStep.Equipment, previousStep(WizardState(step = WizardStep.InputCheck)))
    }

    // ------------------------------------------------------------------
    // 문구
    // ------------------------------------------------------------------

    @Test
    fun `화면 문구에 마크다운이 없다`() {
        val all = listOf(
            LIMITED_ACCURACY_KO, EFFECTS_STILL_ON_KO, INDISTINGUISHABLE_KO,
            LOGICAL_ONLY_KO, CASE_UNKNOWN_KO,
        ) + WizardStep.entries.flatMap { listOf(it.titleKo, it.whatKo) }
        all.forEach {
            assertFalse("별표가 있다: $it", it.contains("*"))
            assertFalse("백틱이 있다: $it", it.contains("`"))
        }
    }

    @Test
    fun `단계 번호가 1부터다`() {
        assertEquals(1, WizardStep.Equipment.number)
        assertEquals(6, WizardStep.Save.number)
    }
}
