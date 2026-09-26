package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.domain.CalibrationState
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.MeasureStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **새 관문과 실제 소비·저장 경로 사이**(독립 재검토 CARF-01~04).
 *
 * 지난번 지적은 「관문이 없다」였고 이번은 「관문은 있는데 **그 옆으로
 * 지나가는 길**이 있다」다. 그래서 여기서는 관문 자체가 아니라 **값이
 * 실제로 흘러가는 자리**를 붙든다.
 */
class CarfRegressionTest {

    private fun builtIn(address: String, generation: Long = 1L) = CaptureIdentity(
        calKey = CalibrationKey(
            deviceKey = realStableKey(address = address),
            source = CaptureSource.Unprocessed,
        ),
        routedAddress = address,
        sampleRate = 48_000,
        routeConfirmed = true,
        generation = generation,
    )

    private fun saved(route: String?) = GlobalCalibration(
        offsetDb = 110.0,
        savedAtEpochMs = 0L,
        referenceDb = 94.0,
        measuredDbfs = -16.0,
        routeAddress = route,
    )

    // ------------------------------------------------------------------
    // CARF-01 — 증거 열쇠에 실제 자리가 들어간다
    // ------------------------------------------------------------------

    /**
     * **같은 저장 열쇠라도 자리가 다르면 다른 증거다.**
     *
     * 검토자가 재현했다 — bottom 에서 입력 점검을 마치고 back 으로 다시
     * 열어 측정했더니, bottom 의 조용한 배경으로 31/31 밴드가 「쓸 수
     * 있음」이 됐다. 실제 back 의 배경으로 세면 0/31 이다.
     */
    @Test
    fun `자리가 다르면 증거를 꺼내 쓰지 못한다`() {
        val bottom = builtIn("bottom")
        val back = builtIn("back")
        assertEquals("전제 — 저장 열쇠는 같다", bottom.calKey, back.calKey)
        assertNotEquals(
            "자리가 다른데 증거 이름이 같다",
            bottom.evidenceKey(4096),
            back.evidenceKey(4096),
        )
    }

    /** 대조군 — 같은 자리로 돌아오면 그대로 쓴다. */
    @Test
    fun `같은 자리면 증거를 이어 쓴다`() {
        assertEquals(
            builtIn("bottom", generation = 1L).evidenceKey(4096),
            builtIn("bottom", generation = 9L).evidenceKey(4096),
        )
    }

    /**
     * **자리를 모르는 증거는 다른 캡처가 물려받지 않는다.**
     *
     * 모르면 세대가 이름에 들어가므로, 다시 열면 이름이 달라져 저절로
     * 버려진다. 그때 사람은 다시 재기만 하면 된다 — 물려받으면 틀린 채로
     * 통과한다.
     */
    @Test
    fun `자리를 모르면 다시 열 때 증거가 따라오지 않는다`() {
        val a = builtIn("").copy(generation = 1L)
        val b = builtIn("").copy(generation = 2L)
        assertNotEquals(a.evidenceKey(4096), b.evidenceKey(4096))
    }

    /** 격자가 다르면 잣대가 다르다 — 예전 계약 그대로. */
    @Test
    fun `격자가 다르면 다른 증거다`() {
        val id = builtIn("bottom")
        assertNotEquals(id.evidenceKey(4096), id.evidenceKey(8192))
    }

    // ------------------------------------------------------------------
    // CARF-02 — 보류한 값이 새어 나가지 않는다
    // ------------------------------------------------------------------

    /**
     * **가장 나쁜 자리.** 화면에는 「미보정 · 적용 보류」라고 적히면서
     * 그 값이 **다른 마이크의 절대 보정으로 복제**됐다.
     */
    @Test
    fun `적용을 보류한 보정은 옮길 값으로 내주지 않는다`() {
        for (route in listOf(null, "other")) {
            val cal = ActiveCalibration.from(
                saved(route),
                nowRoute = "bottom",
                routeConfirmed = true,
            )
            assertTrue("전제 — 보류 상태여야 한다", cal.heldForRoute)
            assertNotNull("값은 지우지 않는다", cal.saved)
            assertNull("보류한 값이 새어 나갔다(route=$route)", cal.appliedOffsetDb)
        }
    }

    /** 대조군 — 같은 자리면 그 값을 옮길 수 있다. */
    @Test
    fun `걸려 있는 보정은 옮길 값으로 내준다`() {
        val cal = ActiveCalibration.from(
            saved("bottom"),
            nowRoute = "bottom",
            routeConfirmed = true,
        )
        assertEquals(CalibrationState.GlobalCalibrated, cal.state)
        assertEquals(110.0, cal.appliedOffsetDb!!, 0.0)
    }

    /** 저장된 것이 아예 없으면 옮길 근거도 없다 — 짐작값을 옮기지 않는다. */
    @Test
    fun `미보정이면 옮길 값이 없다`() {
        assertNull(ActiveCalibration.assumed.appliedOffsetDb)
    }

    // ------------------------------------------------------------------
    // CARF-03 — 파일에 적힐 환경이 잰 경로와 같은가
    // ------------------------------------------------------------------

    private fun env(
        address: String,
        sampleRate: Int = 48_000,
        kind: MicKind = MicKind.BuiltIn,
    ) = ProfileEnvironment(
        deviceKey = realStableKey(kind = kind, address = address),
        deviceAddress = address,
        micKind = kind,
        audioSource = CaptureSource.Unprocessed,
        sampleRate = sampleRate,
        channelCount = 1,
        channelIndex = 0,
        manufacturer = "samsung",
        model = "SM-S918N",
        osBuild = "UP1A.231005.007",
    )

    /**
     * 검사는 `now` 로, 기록은 `environment` 로 한다 — 둘이 다른 순간에서
     * 오므로 같다는 보장이 없었다. 검토자가 **올바른 신원을 검사했는데
     * 산출물은 기준 마이크의 것**인 저장을 실제로 성공시켰다.
     */
    @Test
    fun `파일 환경이 잰 자리와 다르면 저장을 막는다`() {
        val why = environmentMismatchKo(builtIn("bottom"), env("back"))
        assertNotNull("자리가 다른데 저장이 지나갔다", why)
        assertTrue(why!!, why.contains("마이크 자리"))
    }

    @Test
    fun `파일 환경의 기기가 다르면 저장을 막는다`() {
        val why = environmentMismatchKo(builtIn("bottom"), env("card=1;device=0", kind = MicKind.Usb))
        assertNotNull(why)
        assertTrue(why!!, why.contains("입력 경로"))
    }

    @Test
    fun `파일 환경의 샘플레이트가 다르면 저장을 막는다`() {
        val why = environmentMismatchKo(builtIn("bottom"), env("bottom", sampleRate = 44_100))
        assertNotNull(why)
        assertTrue(why!!, why.contains("샘플레이트"))
    }

    /** 대조군 — 같은 경로면 저장된다. */
    @Test
    fun `파일 환경이 같으면 저장된다`() {
        assertNull(environmentMismatchKo(builtIn("bottom"), env("bottom")))
    }

    // ------------------------------------------------------------------
    // CARF-04 — 장을 버리면 판정도 함께 사라진다
    // ------------------------------------------------------------------

    /**
     * 집계기만 비우면 이미 화면으로 나간 판정이 남아, **장은 0인데 저장
     * 관문은 옛 Pass 를 본다.** 검토자가 그 상태를 재현했다:
     *
     * ```
     * DISCARD_OLD_RESULT liveFrames=0 publishedFrames=120
     * sameOutcome=true transferAllowed=true
     * ```
     */
    @Test
    fun `장을 버리면 발행한 판정도 사라진다`() {
        val done = WizardState(
            targetIdentity = builtIn("bottom"),
            targetCalKey = builtIn("bottom").calKey,
            targetDeviceKey = builtIn("bottom").calKey.deviceKey,
            targetEvidenceKey = "ev",
            referenceIdentity = builtIn("card=1;device=0"),
        )
        val after = done.discardingStep(MeasureStep.Target)

        assertNull("옛 결과가 남았다", after.session)
        assertNull("옛 판정이 남았다", after.quality)
        assertNull("옛 결론이 남았다", after.outcome)
        assertNull("옮길 값이 남았다", after.levelTransfer)
        assertNull("대상 이름표가 남았다", after.targetIdentity)
        assertNull(after.targetCalKey)
        assertNull(after.targetEvidenceKey)
    }

    /** **버린 단계의 것만** 지운다. 멀쩡히 잰 것을 다시 재게 하면 안 된다. */
    @Test
    fun `대상을 버려도 기준은 남는다`() {
        val ref = builtIn("card=1;device=0")
        val after = WizardState(referenceIdentity = ref, referenceCalKey = ref.calKey)
            .discardingStep(MeasureStep.Target)
        assertEquals(ref, after.referenceIdentity)
        assertEquals(ref.calKey, after.referenceCalKey)
    }

    @Test
    fun `첫 기준을 버리면 기준 이름표가 사라진다`() {
        val ref = builtIn("card=1;device=0")
        val tgt = builtIn("bottom")
        val after = WizardState(referenceIdentity = ref, targetIdentity = tgt)
            .discardingStep(MeasureStep.ReferenceBefore)
        assertNull(after.referenceIdentity)
        assertEquals("대상까지 지웠다", tgt, after.targetIdentity)
    }

    /** 마지막 기준에는 제 이름표가 없다 — 장만 사라지고 나머지는 그대로다. */
    @Test
    fun `마지막 기준을 버려도 이름표는 그대로다`() {
        val ref = builtIn("card=1;device=0")
        val tgt = builtIn("bottom")
        val after = WizardState(referenceIdentity = ref, targetIdentity = tgt)
            .discardingStep(MeasureStep.ReferenceAfter)
        assertEquals(ref, after.referenceIdentity)
        assertEquals(tgt, after.targetIdentity)
    }
}
