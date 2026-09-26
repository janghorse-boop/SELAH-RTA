package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.MicSeparation
import kr.joa.selahrta.audio.MicSeparationResult
import kr.joa.selahrta.dsp.BandNoise
import kr.joa.selahrta.dsp.ColumnDeclaration
import kr.joa.selahrta.dsp.CurvePoint
import kr.joa.selahrta.dsp.CurveReading
import kr.joa.selahrta.dsp.SignEvidence
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

    /**
     * 머리글이 「응답」으로 분명한 파일. 그래야 읽는 법을 묻지 않는다
     * (독립 검토 R04) — 다른 시험들이 그 물음에 걸리지 않게 한다.
     */
    /**
     * 머리글이 **둘째 열을 응답이라 선언한** 파일. 그래야 기준 CAL 의
     * 부호가 저절로 정해진다(독립 재검토 CA-R05 · CAR-04) — 설명문이나
     * 뜻 모를 열 이름으로는 묻는다.
     */
    private val cal = CalInfo(
        "17860.txt", "abc123", 10.0, 25_000.0, 300,
        evidence = SignEvidence.LooksLikeResponse,
        columns = ColumnDeclaration.Second(CurveReading.Response),
    )

    /**
     * **설명문만 있는 파일은 저절로 정해지지 않는다**(독립 재검토 CA-R05).
     *
     * `# Reference SPL: 94 dB` 나 `# For frequency response measurements`
     * 같은 줄은 둘째 열이 무엇인지 말하지 않는다. 기준 CAL 이 뒤집히면
     * 그것으로 만든 프로파일이 전부 같은 방향으로 틀어진다.
     */
    @Test
    fun `열 선언이 없으면 사람에게 묻는다`() {
        val prose = cal.copy(columns = ColumnDeclaration.None)
        assertFalse("설명문만으로 정해졌다", prose.readingSettled)
        assertTrue("사람이 고르면 정해진다", prose.copy(readingChosenByPerson = true).readingSettled)
    }

    /**
     * **둘째 열이 뜻 모를 이름이면 묻는다**(독립 재검토 CAR-04).
     *
     * `Frequency,Phase,SPL` 은 선언이 맞지만, 파서는 언제나 둘째 값을
     * 읽으므로 SPL 이 아니라 **위상**을 보정에 쓴다.
     */
    @Test
    fun `둘째 열을 모르면 사람에게 묻는다`() {
        val phase = cal.copy(columns = ColumnDeclaration.Unsupported("Phase"))
        assertFalse("둘째 열을 모르는데 정해졌다", phase.readingSettled)
    }

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
        referenceHookup = ReferenceHookup.XlrInterface,
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
        val g = gateFor(WizardState(referenceHookup = ReferenceHookup.XlrInterface, phantomAcknowledged = true), WizardStep.Equipment)
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
        val g = gateFor(
            WizardState(cal = cal, referenceHookup = ReferenceHookup.XlrInterface),
            WizardStep.Equipment,
        )
        assertTrue(g is StepGate.Blocked)
        assertTrue(g.reasonsKo.toString(), g.reasonsKo.any { it.contains("앱은 이 상태를 알 수 없습니다") })
    }

    /**
     * **USB 직결 마이크에는 팬텀전원이 없다.**
     *
     * 가격·구독 전략 3장이 「iMM-6C 와 EMM-6 을 품질 등급으로 나누지
     * 않는다」고 못박았다. 다른 것은 연결 방식뿐이다. 그런데 예전 관문은
     * 모두에게 +48V 확인을 요구해서, USB 직결을 쓰는 사람은 **자기 장비에
     * 없는 스위치**를 켰다고 체크해야만 다음으로 갈 수 있었다.
     */
    @Test
    fun `USB 직결이면 팬텀전원을 묻지 않는다`() {
        val s = WizardState(cal = cal, referenceHookup = ReferenceHookup.UsbDirect)
        assertEquals(StepGate.Allowed, gateFor(s, WizardStep.Equipment))
    }

    @Test
    fun `연결 방식을 안 고르면 막힌다`() {
        // 안 물어보고 XLR 로 가정하면, USB 직결 사용자에게 없는 스위치를
        // 켜라고 하게 된다. 모르는 것은 묻는다.
        val g = gateFor(WizardState(cal = cal), WizardStep.Equipment)
        assertTrue(g is StepGate.Blocked)
        assertTrue(g.reasonsKo.toString(), g.reasonsKo.any { it.contains("어떻게 물렸는지") })
    }

    @Test
    fun `XLR 이면 팬텀전원을 여전히 묻는다`() {
        // 접어서 없앤 것이 아니다. 해당되는 경로에서는 그대로 막는다.
        val s = WizardState(cal = cal, referenceHookup = ReferenceHookup.XlrInterface)
        val g = gateFor(s, WizardStep.Equipment)
        assertTrue(g is StepGate.Blocked)
        assertTrue(g.reasonsKo.toString(), g.reasonsKo.any { it.contains("팬텀전원") })
    }

    /**
     * **읽는 법을 정하지 않으면 못 간다**(독립 검토 R04).
     *
     * 단서 없는 파일이 대부분이지만, 이 파일은 교정의 기준이다. 잘못
     * 읽으면 이 기준으로 만든 프로파일이 **전부 같은 방향으로** 틀어지고
     * 곡선은 멀쩡해 보인다.
     */
    @Test
    fun `CAL 읽는 법이 안 정해지면 막힌다`() {
        // **선언도 설명문도 없어야 「단서 없음」이다**(독립 재검토 CAR-04).
        // 열 선언이 있으면 그것이 가장 센 증거라 설명문이 없어도 정해진다.
        val unclear = cal.copy(
            evidence = SignEvidence.Unknown,
            columns = ColumnDeclaration.None,
        )
        val g = gateFor(
            WizardState(cal = unclear, referenceHookup = ReferenceHookup.XlrInterface, phantomAcknowledged = true),
            WizardStep.Equipment,
        )
        assertTrue("$g", g is StepGate.Blocked)
        assertTrue(g.reasonsKo.toString(), g.reasonsKo.any { it.contains("기준") })
    }

    @Test
    fun `사람이 고르면 지나간다`() {
        val chosen = cal.copy(
            evidence = SignEvidence.Unknown,
            reading = CurveReading.Correction,
            readingChosenByPerson = true,
        )
        val s = WizardState(cal = chosen, referenceHookup = ReferenceHookup.XlrInterface, phantomAcknowledged = true)
        assertEquals(StepGate.Allowed, gateFor(s, WizardStep.Equipment))
    }

    /** 머리글이 **반대**를 가리키면 사람이 고르기 전에는 못 간다. */
    @Test
    fun `머리글이 보정값이면 골라야 지나간다`() {
        val contrary = cal.copy(evidence = SignEvidence.LooksLikeCorrection)
        val s = WizardState(cal = contrary, referenceHookup = ReferenceHookup.XlrInterface, phantomAcknowledged = true)
        assertTrue(gateFor(s, WizardStep.Equipment) is StepGate.Blocked)

        val settled = contrary.copy(
            reading = CurveReading.Correction,
            readingChosenByPerson = true,
        )
        assertEquals(
            StepGate.Allowed,
            gateFor(WizardState(cal = settled, referenceHookup = ReferenceHookup.XlrInterface, phantomAcknowledged = true), WizardStep.Equipment),
        )
    }

    @Test
    fun `둘 다 갖추면 지나간다`() {
        val s = WizardState(cal = cal, referenceHookup = ReferenceHookup.XlrInterface, phantomAcknowledged = true)
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
        repeatStdevDb = 0.5,
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
