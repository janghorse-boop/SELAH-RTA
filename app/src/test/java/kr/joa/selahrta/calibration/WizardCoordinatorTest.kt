package kr.joa.selahrta.calibration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.audio.TestSignal
import kr.joa.selahrta.dsp.MeasureStep
import kr.joa.selahrta.dsp.MeasurementTap
import kr.joa.selahrta.dsp.ThirdOctave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **실제 마법사를 돌린다**(독립 재검토 CARF-06).
 *
 * 검토자가 요구한 범위가 이것이다 — 「증거 수집 취소·신원 변경, 결과
 * 폐기까지 실제로 시험한다」. 순수 함수만 떼어 시험하면 **연결 경계**가
 * 빠지고, 그 자리가 바로 CARF-01~04 가 나온 곳이다.
 *
 * 여기서 도는 것은 운영 코드의 [WizardCoordinator] 그대로다. 가짜는
 * 캡처(마이크) 하나뿐이고, 판정·상태 전이·작업 취소는 전부 제품 코드다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WizardCoordinatorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val fft = 512
    private val rate = 48_000
    private val bins = fft / 2 + 1

    private fun identity(channel: Int?, address: String, generation: Long = 1L) =
        CaptureIdentity(
            calKey = CalibrationKey(
                deviceKey = realStableKey(
                    kind = kr.joa.selahrta.domain.MicKind.Usb,
                    productName = "UMC404HD",
                    address = "card=1;device=0",
                ),
                source = CaptureSource.Unprocessed,
                channelIndex = channel,
            ),
            routedAddress = address,
            sampleRate = rate,
            routeConfirmed = true,
            generation = generation,
        )

    /**
     * 통로를 붙잡아 두는 가짜 마이크.
     *
     * **신원을 도중에 바꿀 수 있다** — 그것이 이 시험의 요점이다.
     */
    private class FakeCapture(var now: CaptureIdentity?) : WizardCapture {
        var tap: MeasurementTap? = null
        var played = 0

        override val openedDeviceKey: String? get() = now?.calKey?.deviceKey
        override val openedCalKey: CalibrationKey? get() = now?.calKey
        override val identity: CaptureIdentity? get() = now
        override val openedOffsetDb: Double? = null
        override val clippedSinceMark: Boolean = false
        override fun markClippingBaseline() = Unit
        override fun installTap(tap: MeasurementTap) { this.tap = tap }
        override fun removeTap(tap: MeasurementTap) { if (this.tap === tap) this.tap = null }
        override fun playSignal(signal: TestSignal, amplitude: Double) { played++ }
        override fun stopSignal() = Unit
    }

    private fun coordinator(scope: TestScope) =
        WizardCoordinator(scope, ProfileStore(temp.newFolder()))

    /** 기다릴 때마다 통로에 장을 밀어 넣는 가짜 시간. */
    private fun ticker(cap: FakeCapture, level: () -> Double): suspend () -> Unit = {
        cap.tap?.onSpectrum(DoubleArray(bins) { level() })
    }

    /** 밴드가 다 찬 평탄한 CAL. 측정 단계가 이것을 요구한다. */
    private fun loadFlatCal(core: WizardCoordinator) {
        core.loadCal("flat.txt", "Frequency,SPL\n10,0\n25000,0\n")
        assertNotNull("전제 — CAL 이 실려야 한다", core.state.value.cal)
    }

    // ------------------------------------------------------------------
    // 증거 수집 — 취소와 신원 변경
    // ------------------------------------------------------------------

    /**
     * **성공 → 재검사 → 취소** 뒤에 옛 성공이 남지 않는다.
     *
     * 검토자가 실제 VM 으로 재현한 순서다. 실패 분기에서만 지우던 때에는
     * 취소가 그 분기로 들어가지 않아, 취소 전의 성공이 그대로 승인
     * 근거가 되었다(SNR 2dB 자료가 Pass).
     */
    @Test
    fun `재검사를 취소하면 옛 증거가 남지 않는다`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val core = coordinator(scope)
        val cap = FakeCapture(identity(0, "card=1;device=0"))
        val key = cap.now!!.evidenceKey(fft)

        // 한 번 끝까지 마친다.
        core.runInputCheck(cap, fft, rate, ticker(cap) { 1e-9 })
        testScheduler.advanceUntilIdle()
        assertNotNull("전제 — 배경이 쌓여야 한다", core.state.value.noiseFloorByKey[key])

        // 다시 재기 시작만 하고 끊는다.
        core.runInputCheck(cap, fft, rate) { /* 장을 넣지 않는다 */ }
        core.stopWork()
        testScheduler.advanceUntilIdle()

        assertNull("취소 뒤에도 옛 배경이 남았다", core.state.value.noiseFloorByKey[key])
        assertNull("취소 뒤에도 옛 판정이 남았다", core.state.value.dspByKey[key])
    }

    /**
     * **수집 도중에 채널이 바뀌면 그 짝을 적지 않는다.**
     *
     * 검토자가 잰 것: `CHECK_INPUT_CHANGED … savedUnderCh0=true` —
     * ch1 의 결과가 ch0 의 이름으로 저장됐다.
     */
    @Test
    fun `수집 도중 입력이 바뀌면 증거를 적지 않는다`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val core = coordinator(scope)
        val cap = FakeCapture(identity(0, "card=1;device=0"))
        val startKey = cap.now!!.evidenceKey(fft)

        var pushed = 0
        core.runInputCheck(cap, fft, rate) {
            pushed++
            // 배경 40장이 차기 직전에 채널을 바꾼다.
            if (pushed == 39) cap.now = identity(1, "card=1;device=0")
            cap.tap?.onSpectrum(DoubleArray(bins) { 1e-9 })
        }
        testScheduler.advanceUntilIdle()

        assertNull("바뀐 뒤의 자료가 옛 이름으로 적혔다", core.state.value.noiseFloorByKey[startKey])
        assertNull(core.state.value.dspByKey[startKey])
        assertNotNull("사람에게 말해야 한다", core.noticeKo.value)
    }

    /** 대조군 — 그대로 있으면 끝까지 쌓인다. */
    @Test
    fun `바뀌지 않으면 증거가 쌓인다`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val core = coordinator(scope)
        val cap = FakeCapture(identity(0, "card=1;device=0"))

        core.runInputCheck(cap, fft, rate, ticker(cap) { 1e-9 })
        testScheduler.advanceUntilIdle()

        val key = cap.now!!.evidenceKey(fft)
        assertEquals(
            ThirdOctave.BAND_COUNT,
            core.state.value.noiseFloorByKey[key]?.size,
        )
        assertTrue("신호를 틀어 점검해야 한다", cap.played > 0)
    }

    /**
     * **자리가 다르면 증거를 꺼내 쓰지 못한다**(CARF-01 이 실제 흐름에서).
     *
     * 같은 저장 열쇠라도 bottom 에서 모은 것을 back 에서 조회하면 안 된다.
     */
    @Test
    fun `자리가 다르면 옛 증거가 조회되지 않는다`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val core = coordinator(scope)
        val cap = FakeCapture(identity(0, "bottom"))

        core.runInputCheck(cap, fft, rate, ticker(cap) { 1e-9 })
        testScheduler.advanceUntilIdle()
        assertNotNull(core.state.value.noiseFloorByKey[identity(0, "bottom").evidenceKey(fft)])

        // 자리만 바꾼다. 저장 열쇠는 그대로다.
        val back = identity(0, "back")
        assertEquals("전제 — 저장 열쇠는 같다", cap.now!!.calKey, back.calKey)
        assertNull(
            "다른 자리에서 옛 증거가 조회됐다",
            core.state.value.noiseFloorByKey[back.evidenceKey(fft)],
        )
    }

    // ------------------------------------------------------------------
    // 전경 상태 — 실제 흐름에서
    // ------------------------------------------------------------------

    /**
     * 뒤로 가면 **소리를 내지 않는다.** 그리고 돌아오면 다시 낼 수 있다.
     *
     * `stopWork` 가 전경 상태까지 내리던 회귀(CA-R04)는 여기서 걸린다 —
     * 탭을 옮긴 뒤 다시 시작했는데 신호가 안 나가면 실패한다.
     */
    @Test
    fun `뒤로 갔다 오면 다시 소리를 낼 수 있다`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val core = coordinator(scope)
        val cap = FakeCapture(identity(0, "card=1;device=0"))

        core.onBackground()
        core.runInputCheck(cap, fft, rate, ticker(cap) { 1e-9 })
        testScheduler.advanceUntilIdle()
        assertEquals("뒤에 있는데 소리를 냈다", 0, cap.played)

        core.onForeground()
        core.runInputCheck(cap, fft, rate, ticker(cap) { 1e-9 })
        testScheduler.advanceUntilIdle()
        assertTrue("돌아왔는데 소리를 못 냈다", cap.played > 0)
    }

    /** 탭 이동·닫기는 **끊기만** 한다. 그 뒤에도 소리를 낼 수 있어야 한다. */
    @Test
    fun `끊은 뒤에도 소리를 낼 수 있다`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val core = coordinator(scope)
        val cap = FakeCapture(identity(0, "card=1;device=0"))

        core.runInputCheck(cap, fft, rate) { /* 안 채운다 */ }
        core.stopWork()
        testScheduler.advanceUntilIdle()

        core.runInputCheck(cap, fft, rate, ticker(cap) { 1e-9 })
        testScheduler.advanceUntilIdle()
        assertTrue("끊었다고 소리를 막았다", cap.played > 0)
    }

    // ------------------------------------------------------------------
    // 결과 폐기 (CARF-04) — 실제 흐름에서
    // ------------------------------------------------------------------

    /**
     * **다시 재기 시작하면 옛 판정이 먼저 사라진다.**
     *
     * 화면의 「다시」는 이미 장이 있는 단계에도 눌린다. 그때 옛
     * `quality`·`outcome` 이 남아 있으면 새 시도가 끝나기도 전에 저장
     * 관문이 지난 Pass 를 본다.
     */
    @Test
    fun `다시 재기 시작하면 옛 이름표가 사라진다`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val core = coordinator(scope)
        loadFlatCal(core)

        val ref = FakeCapture(identity(0, "card=1;device=0"))
        core.measureStep(MeasureStep.ReferenceBefore, ref, fft, rate, ticker(ref) { 1e-6 })
        testScheduler.advanceUntilIdle()
        assertNotNull("전제 — 기준이 찍혀야 한다", core.state.value.referenceIdentity)
        assertTrue("전제 — 장이 쌓여야 한다", core.framesFor(MeasureStep.ReferenceBefore) > 0)

        // 같은 단계를 다시 재기 시작하면서 도중에 입력을 바꾼다.
        var pushed = 0
        core.measureStep(MeasureStep.ReferenceBefore, ref, fft, rate) {
            pushed++
            if (pushed == 1) ref.now = identity(1, "card=1;device=0")
            ref.tap?.onSpectrum(DoubleArray(bins) { 1e-6 })
        }
        testScheduler.advanceUntilIdle()

        assertNull("버린 시도의 이름표가 남았다", core.state.value.referenceIdentity)
        assertEquals("버린 시도의 장이 남았다", 0, core.framesFor(MeasureStep.ReferenceBefore))
        assertNull(core.state.value.quality)
        assertNull(core.state.value.outcome)
    }

    /** 처음부터 다시 하면 **곡선까지** 버린다 — 남기면 다음이 물려받는다. */
    @Test
    fun `처음부터 다시 하면 곡선도 버린다`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val core = coordinator(scope)
        loadFlatCal(core)
        assertNotNull(core.curve)

        core.reset()

        assertNull("곡선이 남았다", core.curve)
        assertNull("CAL 이 남았다", core.state.value.cal)
        assertFalse(core.state.value.readyToMeasure())
    }
}

/** 시험 안에서만 쓰는 짧은 물음. */
private fun WizardState.readyToMeasure(): Boolean = cal != null
