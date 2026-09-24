package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.audio.MicSeparation
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.BandNoise
import kr.joa.selahrta.dsp.CalibrationOutcome
import kr.joa.selahrta.dsp.CalibrationSettings
import kr.joa.selahrta.dsp.CurvePoint
import kr.joa.selahrta.dsp.QualityReport
import kr.joa.selahrta.dsp.QualityVerdict
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.dsp.calibrateResponse
import kr.joa.selahrta.dsp.judgeCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **저장하는 길이 판정을 반드시 거치는가.**
 *
 * 독립 검증이 여러 차례 남겨 둔 통합 항목이다:
 *
 * > 실제 저장·자동 적용이 judgeCalibration 의 최종 결과를 사용하는지
 * > 통합 시험이 필요하다.
 *
 * 화면이 판정을 따로 부르고 그 결과를 보고 저장하면 두 곳이 갈라질 수
 * 있다. 그래서 **프로파일을 만드는 길이 하나뿐이고 그 길이 판정을
 * 품는다** — 여기서 그것을 잰다.
 */
class ProfileBuilderTest {

    private val n = ThirdOctave.BAND_COUNT

    private fun env() = ProfileEnvironment(
        deviceKey = "BuiltIn|SM-S918N|bottom",
        deviceAddress = "bottom",
        micKind = MicKind.BuiltIn,
        audioSource = CaptureSource.Unprocessed,
        sampleRate = 48_000,
        channelCount = 1,
        channelIndex = 0,
        manufacturer = "samsung",
        model = "SM-S918N",
        osBuild = "BP4A.251205.006",
    )

    /** 멀쩡한 보정 — 고역만 6dB 모자란 흔한 모양. */
    private fun goodOutcome(): CalibrationOutcome {
        val ref = (0 until n).map { CurvePoint(ThirdOctave.exactCenter(it), 70.0) }
        val tgt = (0 until n).map {
            CurvePoint(ThirdOctave.exactCenter(it), if (it >= 26) 64.0 else 70.0)
        }
        return calibrateResponse(ref, tgt)
    }

    /** 상한이 대부분을 잘라 낸 보정. */
    private fun ruinedOutcome(): CalibrationOutcome {
        val ref = (0 until n).map {
            val db = if (it < 2 || it > 28) 70.0 else if (it % 2 == 0) 100.0 else 40.0
            CurvePoint(ThirdOctave.exactCenter(it), db)
        }
        val tgt = (0 until n).map { CurvePoint(ThirdOctave.exactCenter(it), 70.0) }
        return calibrateResponse(ref, tgt)
    }

    private fun quality(
        snrDb: Double = 30.0,
        dspVerified: Boolean = true,
        repeatStdevDb: Double? = 0.5,
    ) = QualityReport(
        bands = (0 until n).map { BandNoise(ThirdOctave.exactCenter(it), 70.0, 70.0 - snrDb) },
        repeatStdevDb = repeatStdevDb,
        referenceDriftDb = 0.1,
        referenceBandDriftDb = 0.3,
        minFramesPerStep = 16,
        referenceBands = (0 until n).map { BandNoise(ThirdOctave.exactCenter(it), 70.0, 70.0 - snrDb) },
        referenceCalRangeHz = 10.0..25_000.0,
        dspVerifiedBySignal = dspVerified,
    )

    private fun build(
        outcome: CalibrationOutcome,
        q: QualityReport = quality(),
        caseRemoved: Boolean? = null,
    ) = buildProfileForSave(
        session = sessionStub(q),
        quality = q,
        outcome = outcome,
        environment = env(),
        separation = MicSeparation.Separable,
        caseRemoved = caseRemoved,
        nowEpochMs = 1_700_000_000_000L,
        id = "fixed-id",
    )

    /**
     * 세션은 증거만 쓰이므로 최소로 둔다 — dsp 의 `internal` 을 여기서
     * 만들 수 없어 증거 없는 세션을 쓴다. 그래서 CAL 이름·해시는 null 로
     * 적히고, 그 사실을 아래 시험 하나가 못 박는다.
     */
    // 틀은 TestProfiles.kt 에 하나만 둔다 — 증거(referenceProof)는 꾸며 낼
    // 수 없어서 진짜 곡선으로 한 번 돌려 얻는다.
    private fun sessionStub(q: QualityReport) = fakeSession(
        withProof = false,
        referenceDriftDb = q.referenceDriftDb ?: 0.0,
        referenceBandDriftDb = q.referenceBandDriftDb ?: 0.0,
        repeatStdevDb = q.repeatStdevDb,
    )

    // ------------------------------------------------------------------
    // 관문
    // ------------------------------------------------------------------

    @Test
    fun `멀쩡한 측정은 저장할 프로파일이 나온다`() {
        val r = build(goodOutcome())
        assertTrue("$r", r is ProfileBuildResult.Ready)
        val ready = r as ProfileBuildResult.Ready
        assertEquals(QualityVerdict.Pass, ready.judged.verdict)
        assertTrue("통과면 자동으로 걸린다", ready.profile.enabled)
    }

    /**
     * **판정이 막으면 프로파일 자체가 만들어지지 않는다.**
     *
     * 「만들어 두고 저장만 막는다」가 아니다 — 만들어 두면 누군가
     * 저장한다.
     */
    @Test
    fun `판정이 막으면 프로파일이 만들어지지 않는다`() {
        val r = build(ruinedOutcome())
        assertTrue("$r", r is ProfileBuildResult.Blocked)
        val blocked = r as ProfileBuildResult.Blocked
        assertEquals(QualityVerdict.Fail, blocked.judged.verdict)
        assertTrue(
            "까닭을 들고 와야 한다: ${blocked.judged.reasonsKo}",
            blocked.judged.reasonsKo.any { it.contains("보정이 걸리는 대역") },
        )
    }

    /**
     * **자동 적용은 부르는 쪽이 정하지 않는다.**
     *
     * DSP 를 신호로 확인하지 않은 측정은 저장은 되되 자동으로 걸리지
     * 않는다(지시서 2장). `enabled` 가 그것을 따라야 한다.
     */
    @Test
    fun `제한 판정이면 저장되되 자동 적용은 꺼진다`() {
        val r = build(goodOutcome(), quality(dspVerified = false))
        assertTrue("$r", r is ProfileBuildResult.Ready)
        val ready = r as ProfileBuildResult.Ready
        assertEquals(QualityVerdict.Degraded, ready.judged.verdict)
        assertFalse("자동 적용은 꺼져 있어야 한다", ready.profile.enabled)
    }

    /** 판정 결과가 **프로파일에 그대로** 들어간다. */
    @Test
    fun `판정과 지표가 프로파일에 남는다`() {
        val out = goodOutcome()
        val q = quality()
        val ready = build(out, q) as ProfileBuildResult.Ready

        assertEquals(judgeCalibration(q, out).verdict, ready.profile.quality.verdict)
        assertEquals(q.repeatStdevDb!!, ready.profile.quality.repeatStdevDb, 0.0)
        assertEquals(q.referenceDriftDb!!, ready.profile.quality.referenceDriftDb, 0.0)
        assertEquals(
            "쓸 수 있는 비율은 **계산 뒤** 지원 비율이다",
            out.supportedBandRatio, ready.profile.quality.usableBandRatio, 0.0,
        )
        assertEquals(out.internalNormalized.offsetDb, ready.profile.levelOffsetDb, 0.0)
        assertEquals(out.settings.maxCorrectionDb, ready.profile.maxCorrectionDb, 0.0)
    }

    /** 유효 범위는 **지원 대역의 경계**로 적는다. */
    @Test
    fun `유효 범위가 지원 대역을 따른다`() {
        val out = goodOutcome()
        val ready = build(out) as ProfileBuildResult.Ready
        val bands = out.supportedBands
        assertTrue("지원 대역이 있어야 한다", bands.isNotEmpty())
        assertEquals(ThirdOctave.lowerEdge(bands.first()), ready.profile.validFromHz!!, 1e-9)
        assertEquals(ThirdOctave.upperEdge(bands.last()), ready.profile.validToHz!!, 1e-9)
    }

    /**
     * CAL 신원은 **증거에서** 온다 — 화면이 따로 들고 있던 값이 아니다.
     *
     * 여기서는 증거 없는 세션이라 null 이 적힌다. 실제 경로에서는
     * [kr.joa.selahrta.dsp.applyReferenceCalibration] 이 넣은 값이 온다.
     */
    @Test
    fun `증거가 없으면 CAL 신원도 비어 있다`() {
        val ready = build(goodOutcome()) as ProfileBuildResult.Ready
        assertNull(ready.profile.reference.calFileName)
        assertNull(ready.profile.reference.calSha256)
    }

    @Test
    fun `케이스 여부는 적은 대로 남는다`() {
        for (state in listOf(true, false, null)) {
            val ready = build(goodOutcome(), caseRemoved = state) as ProfileBuildResult.Ready
            assertEquals("케이스=$state", state, ready.profile.caseRemoved)
        }
    }

    /** 만든 프로파일이 **그대로 되읽힌다** — 저장 경로가 이어진다. */
    @Test
    fun `만든 프로파일이 왕복한다`() {
        val ready = build(goodOutcome()) as ProfileBuildResult.Ready
        val back = decodeProfile(encodeProfile(ready.profile)).getOrThrow()
        assertEquals(ready.profile, back)
    }

    /** 막힌 까닭 문구에 **별표가 새지 않는다.** */
    @Test
    fun `막힘 문구에 별표가 없다`() {
        val blocked = build(ruinedOutcome()) as ProfileBuildResult.Blocked
        (blocked.judged.reasonsKo + blockedNoticeKo(blocked.judged)).forEach {
            assertFalse(it, it.contains("*"))
        }
    }

    /** 설정을 그대로 물려받는다 — 나중에 무엇으로 계산했는지 되짚을 수 있게. */
    @Test
    fun `계산 설정이 프로파일에 남는다`() {
        val settings = CalibrationSettings(smoothingFraction = 3.0, maxCorrectionDb = 9.0)
        val ref = (0 until n).map { CurvePoint(ThirdOctave.exactCenter(it), 70.0) }
        val tgt = (0 until n).map {
            CurvePoint(ThirdOctave.exactCenter(it), if (it >= 26) 64.0 else 70.0)
        }
        val out = calibrateResponse(ref, tgt, settings)
        val ready = build(out) as ProfileBuildResult.Ready
        assertEquals(3.0, ready.profile.smoothingFraction, 0.0)
        assertEquals(9.0, ready.profile.maxCorrectionDb, 0.0)
        assertEquals(settings.normalizeBandLowHz, ready.profile.normalizeBandLowHz, 0.0)
    }
}
