package kr.joa.selahrta.calibration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.StandardTestDispatcher
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

    /** 대상 마이크 — 기준과 **다른 기기**여야 한다. */
    private fun builtInIdentity() = CaptureIdentity(
        calKey = CalibrationKey(
            deviceKey = realStableKey(address = "bottom"),
            source = CaptureSource.Unprocessed,
        ),
        routedAddress = "bottom",
        sampleRate = rate,
        routeConfirmed = true,
        generation = 1L,
    )

    private fun coordinator(scope: TestScope) =
        WizardCoordinator(scope, ProfileStore(temp.newFolder()))

    /** 기다릴 때마다 통로에 장을 밀어 넣는 가짜 시간. */
    private fun ticker(cap: FakeCapture, level: () -> Double): suspend () -> Unit = {
        cap.tap?.onSpectrum(DoubleArray(bins) { level() })
    }

    /**
     * 그 입력의 **배경·DSP 증거**를 만든다.
     *
     * 측정 단계는 이것 없이 셈하지 않는다 — 「기준 마이크가 실제로 신호를
     * 잡았는지 알 수 없어 보정을 만들지 않습니다」. 맞는 관문이라 시험도
     * 실제 순서를 밟는다.
     */
    private fun TestScope.check(core: WizardCoordinator, cap: FakeCapture) {
        var pushed = 0
        core.runInputCheck(cap, fft, rate) {
            pushed++
            // 배경은 조용히, 그 뒤 신호는 크게 — 가청 상승이 잡혀야
            // `verifiedBySignal` 이 선다.
            val level = if (pushed <= 40) 1e-9 else 1e-3
            cap.tap?.onSpectrum(DoubleArray(bins) { level })
        }
        testScheduler.advanceUntilIdle()
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
     * **성공 → 재검사 → 수집 도중 취소** 뒤에 옛 성공이 남지 않는다.
     *
     * 검토자가 실제 VM 으로 재현한 순서다. 실패 분기에서만 지우던 때에는
     * 취소가 그 분기로 들어가지 않아, 취소 전의 성공이 그대로 승인
     * 근거가 되었다(SNR 2dB 자료가 Pass).
     *
     * ## 시험이 실제로 취소하게 고쳤다 (독립 재검토 CF2-03)
     *
     * 처음에는 `UnconfinedTestDispatcher` 에 **일시중단하지 않는** tick 을
     * 썼다. 그러면 `stopWork` 를 부르기 전에 흐름이 이미 끝나 있어
     * **취소되는 것이 없었다** — 이름만 「취소」인 시험이었다.
     *
     * 이제 `StandardTestDispatcher` 와 실제로 `delay` 하는 tick 을 쓰고,
     * 끊기 직전에 **돌고 있는지·통로가 붙어 있는지**를 먼저 단언한다.
     * 그래야 끊을 것이 있었다는 말이 된다.
     */
    @Test
    fun `재검사를 수집 도중에 취소하면 옛 증거가 남지 않는다`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val core = coordinator(scope)
        val cap = FakeCapture(identity(0, "card=1;device=0"))
        val key = cap.now!!.evidenceKey(fft)

        // 한 번 끝까지 마친다 — **신호를 크게 주어** DSP 점검까지 통과시킨다.
        var pushed = 0
        core.runInputCheck(cap, fft, rate) {
            pushed++
            // 배경 40장은 조용히, 그 뒤 신호는 크게. 그래야 가청 상승이
            // 잡혀 `verifiedBySignal` 이 선다.
            val level = if (pushed <= 40) 1e-9 else 1e-3
            cap.tap?.onSpectrum(DoubleArray(bins) { level })
        }
        testScheduler.advanceUntilIdle()
        assertNotNull("전제 — 배경이 쌓여야 한다", core.state.value.noiseFloorByKey[key])
        assertTrue(
            "전제 — 신호로 확인된 DSP 점검이 있어야 한다",
            core.state.value.dspByKey[key]?.verifiedBySignal == true,
        )

        // 다시 재기 시작한다. **실제로 기다리는** tick 이라 끊을 것이 남는다.
        core.runInputCheck(cap, fft, rate) {
            delay(10)
            cap.tap?.onSpectrum(DoubleArray(bins) { 1e-9 })
        }
        testScheduler.advanceTimeBy(120)

        assertNotNull("끊을 것이 없다 — 통로가 안 붙었다", cap.tap)
        assertNotNull("끊을 것이 없다 — 돌고 있지 않다", core.busyKo.value)

        core.stopWork()
        testScheduler.advanceUntilIdle()

        assertNull("취소 뒤에도 옛 배경이 남았다", core.state.value.noiseFloorByKey[key])
        assertNull("취소 뒤에도 옛 판정이 남았다", core.state.value.dspByKey[key])
        assertNull("끊었는데 통로가 남았다", cap.tap)
        assertNull("끊었는데 「재는 중」이 남았다", core.busyKo.value)
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

    /**
     * **가장 중요한 시험**(독립 재검토 CF2-01) — 실제 흐름으로.
     *
     * 정상 순서에서 **마지막** 기준을 바꾸는 것은 막혀 있었는데, 완료 뒤
     * **처음** 기준만 다른 채널로 다시 재는 역방향이 뚫려 있었다.
     * 마지막 기준은 제 이름표가 없어 새 첫 기준의 것을 물려받았고,
     * 세 단계가 차 있으니 곧바로 셈이 돌아 Pass 가 났다:
     *
     * ```
     * REFERENCE_RETRY newBeforeChannel=1 retainedAfterChannel=0
     * retainedAfterFrames=120 verdict=Pass
     * ```
     *
     * **순수 함수 시험으로는 이것을 잡지 못했다.** 관문 함수는 맞게
     * 판정하는데 흐름이 그것을 부르지 않는 자리였다 — 고치기 전 코드를
     * 되살려 확인했다.
     */
    @Test
    fun `완료 뒤 처음 기준만 다른 채널로 다시 재면 셈하지 않는다`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val core = coordinator(scope)
        loadFlatCal(core)

        val ref = FakeCapture(identity(0, "card=1;device=0"))
        val tgt = FakeCapture(builtInIdentity())
        check(core, ref)
        check(core, tgt)

        // 기준 ch0 → 대상 → 기준 ch0 을 정상 완료한다.
        core.measureStep(MeasureStep.ReferenceBefore, ref, fft, rate, ticker(ref) { 1e-6 })
        testScheduler.advanceUntilIdle()
        core.measureStep(MeasureStep.Target, tgt, fft, rate, ticker(tgt) { 1e-6 })
        testScheduler.advanceUntilIdle()
        core.measureStep(MeasureStep.ReferenceAfter, ref, fft, rate, ticker(ref) { 1e-6 })
        testScheduler.advanceUntilIdle()
        assertNotNull("전제 — 정상 순서는 셈이 나와야 한다", core.state.value.outcome)

        // 처음 기준만 **다른 채널**로 다시 잰다. 마지막 기준은 그대로 둔다.
        val ref1 = FakeCapture(identity(1, "card=1;device=0"))
        core.measureStep(MeasureStep.ReferenceBefore, ref1, fft, rate, ticker(ref1) { 1e-6 })
        testScheduler.advanceUntilIdle()

        assertNull("옛 채널의 마지막 기준으로 셈이 났다", core.state.value.outcome)
        assertNull(core.state.value.quality)
        assertNull("옮길 값까지 나왔다", core.state.value.levelTransfer)
        assertEquals(
            "옛 채널의 마지막 기준 장이 남았다",
            0,
            core.framesFor(MeasureStep.ReferenceAfter),
        )
        assertNotNull("사람에게 말해야 한다", core.noticeKo.value)
    }

    /**
     * **같은 입력이어도 뒤 단계는 버린다**(독립 재검토 CFR-01).
     *
     * 예전 계약은 「같은 경로면 남긴다」였다. 멀쩡한 대상 120장을 다시
     * 재게 하지 않으려던 것인데, **경로가 같다고 순서가 맞는 것은
     * 아니다.**
     *
     * 두 기준의 차이로 「재는 동안 무엇이 변했나」를 보는데, 두 기준을
     * 다 잰 뒤 첫 기준만 다시 재면 마지막 기준이 **시간상 앞**에 놓인다.
     * 그 상태의 차이는 아무것도 말하지 않는다.
     */
    @Test
    fun `같은 입력으로 처음 기준을 다시 재도 뒤 단계는 버린다`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val core = coordinator(scope)
        loadFlatCal(core)

        val ref = FakeCapture(identity(0, "card=1;device=0"))
        val tgt = FakeCapture(builtInIdentity())
        check(core, ref)
        check(core, tgt)
        core.measureStep(MeasureStep.ReferenceBefore, ref, fft, rate, ticker(ref) { 1e-6 })
        testScheduler.advanceUntilIdle()
        core.measureStep(MeasureStep.Target, tgt, fft, rate, ticker(tgt) { 1e-6 })
        testScheduler.advanceUntilIdle()
        core.measureStep(MeasureStep.ReferenceAfter, ref, fft, rate, ticker(ref) { 1e-6 })
        testScheduler.advanceUntilIdle()
        assertNotNull("전제 — 정상 순서는 셈이 나와야 한다", core.state.value.outcome)

        // **같은 입력**으로 처음 기준만 다시 잰다.
        core.measureStep(MeasureStep.ReferenceBefore, ref, fft, rate, ticker(ref) { 1e-6 })
        testScheduler.advanceUntilIdle()

        assertEquals("대상이 남았다", 0, core.framesFor(MeasureStep.Target))
        assertEquals("마지막 기준이 남았다", 0, core.framesFor(MeasureStep.ReferenceAfter))
        assertNull("옛 셈이 남았다", core.state.value.outcome)
        assertNotNull("무엇을 버렸는지 말해야 한다", core.noticeKo.value)
        // 다시 잰 첫 기준은 남는다 — 그것이 지금 시도의 출발점이다.
        assertTrue(core.framesFor(MeasureStep.ReferenceBefore) > 0)
    }

    /**
     * **대상을 다시 재면 마지막 기준만 다시 요구한다**(CFR-01).
     *
     * 검토자가 대상 전력만 10배로 올려 다시 재고 Pass 를 받아 냈다 —
     * 두 기준을 다 잰 뒤의 변화는 그 둘의 차이에 나타날 수 없다.
     */
    @Test
    fun `대상을 다시 재면 마지막 기준을 다시 요구한다`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val core = coordinator(scope)
        loadFlatCal(core)

        val ref = FakeCapture(identity(0, "card=1;device=0"))
        val tgt = FakeCapture(builtInIdentity())
        check(core, ref)
        check(core, tgt)
        core.measureStep(MeasureStep.ReferenceBefore, ref, fft, rate, ticker(ref) { 1e-6 })
        testScheduler.advanceUntilIdle()
        core.measureStep(MeasureStep.Target, tgt, fft, rate, ticker(tgt) { 1e-6 })
        testScheduler.advanceUntilIdle()
        core.measureStep(MeasureStep.ReferenceAfter, ref, fft, rate, ticker(ref) { 1e-6 })
        testScheduler.advanceUntilIdle()
        assertNotNull("전제", core.state.value.outcome)

        // 대상만 **전력을 10배로** 올려 다시 잰다.
        core.measureStep(MeasureStep.Target, tgt, fft, rate, ticker(tgt) { 1e-5 })
        testScheduler.advanceUntilIdle()

        assertEquals("마지막 기준이 남았다", 0, core.framesFor(MeasureStep.ReferenceAfter))
        assertNull("옛 셈으로 통과했다", core.state.value.outcome)
        assertNull(core.state.value.levelTransfer)
        // 앞선 첫 기준은 시간상 여전히 앞이라 그대로 쓴다.
        assertTrue("멀쩡한 첫 기준까지 버렸다", core.framesFor(MeasureStep.ReferenceBefore) > 0)
    }

    /** 마지막 기준만 다시 재는 것은 **앞을 건드리지 않는다.** */
    @Test
    fun `마지막 기준만 다시 재면 앞은 그대로다`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val core = coordinator(scope)
        loadFlatCal(core)

        val ref = FakeCapture(identity(0, "card=1;device=0"))
        val tgt = FakeCapture(builtInIdentity())
        check(core, ref)
        check(core, tgt)
        core.measureStep(MeasureStep.ReferenceBefore, ref, fft, rate, ticker(ref) { 1e-6 })
        testScheduler.advanceUntilIdle()
        core.measureStep(MeasureStep.Target, tgt, fft, rate, ticker(tgt) { 1e-6 })
        testScheduler.advanceUntilIdle()
        core.measureStep(MeasureStep.ReferenceAfter, ref, fft, rate, ticker(ref) { 1e-6 })
        testScheduler.advanceUntilIdle()

        core.measureStep(MeasureStep.ReferenceAfter, ref, fft, rate, ticker(ref) { 1e-6 })
        testScheduler.advanceUntilIdle()

        assertTrue(core.framesFor(MeasureStep.ReferenceBefore) > 0)
        assertTrue(core.framesFor(MeasureStep.Target) > 0)
        assertNotNull("다시 셈이 나야 한다", core.state.value.outcome)
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

    // ── 작업마다 소리의 주인 (36회차 「추가 경계」) ─────────────────────────

    /**
     * **실제 신호 조율기**에 주인별로 잇는 캡처. [forWork] 마다 새 주인을 받는다 — `WizardCaptureBridge` 와 같은
     * 계약이다(그쪽은 `CaptureViewModel` 이 있어야 해 JVM 에서 만들지 못한다). [shareOwner] 면 작업이 주인을
     * 나누지 않는다 — 고치기 전의 bridge 와 같다.
     */
    /** 작업 창들이 함께 쓰는 기록 — 시험이 창 나누기에 기대지 않고 순서를 쥔다. */
    private class Shared {
        var plays = 0
        var removed = 0
        var tap: MeasurementTap? = null
    }

    private class OwnedCapture(
        val signals: kr.joa.selahrta.audio.SignalController,
        val now: CaptureIdentity,
        val shareOwner: Boolean = false,
        val owner: kr.joa.selahrta.transfer.SignalOwner =
            kr.joa.selahrta.transfer.SignalOwner.Wizard(signals.newOwnerId()),
        val shared: Shared = Shared(),
    ) : WizardCapture {
        override val openedDeviceKey: String? get() = now.calKey.deviceKey
        override val openedCalKey: CalibrationKey? get() = now.calKey
        override val identity: CaptureIdentity? get() = now
        override val openedOffsetDb: Double? = null
        override val clippedSinceMark: Boolean = false
        override fun markClippingBaseline() = Unit
        override fun installTap(tap: MeasurementTap) { shared.tap = tap }
        override fun removeTap(tap: MeasurementTap) {
            shared.removed++
            if (shared.tap === tap) shared.tap = null
        }
        override fun playSignal(signal: TestSignal, amplitude: Double) {
            shared.plays++
            signals.playSignal(signal, amplitude, owner)
        }
        override fun stopSignal() = signals.stopSignalOwnedBy(owner)
        override fun forWork(): WizardCapture =
            if (shareOwner) OwnedCapture(signals, now, shareOwner, owner, shared)
            else OwnedCapture(signals, now, shared = shared)
    }

    /**
     * 작업 하나의 tick — **그 작업이 시작한 뒤** 소리가 났으면 [gate] 에서 기다린다. [nonCancellable] 이면 취소에도
     * 깨지 않아 끊어도 finally 가 늦는다.
     */
    private fun gatedTick(
        cap: OwnedCapture,
        gate: kotlinx.coroutines.CompletableDeferred<Unit>,
        nonCancellable: Boolean,
    ): suspend () -> Unit {
        var startPlays = -1
        return {
            if (startPlays < 0) startPlays = cap.shared.plays
            if (cap.shared.plays > startPlays) {
                if (nonCancellable) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
                    currentCoroutineContext().ensureActive()
                } else {
                    gate.await()
                }
            }
            cap.shared.tap?.onSpectrum(DoubleArray(bins) { 1e-9 })
        }
    }

    /** 화면·포커스 가짜. 명령도 주 스레드 넘기기도 그 자리에서 돈다 — 순서는 코루틴 쪽이 쥔다. */
    private class InlineHost(vararg sinks: kr.joa.selahrta.audio.FakeSink) : kr.joa.selahrta.audio.SignalHost {
        val sinks = java.util.ArrayDeque(sinks.toList())
        var shown: TestSignal? = null
        override fun postMain(block: () -> Unit) = block()
        override fun acquireFocus() = true
        override fun releaseFocus() = Unit
        override fun showSignal(playing: TestSignal?, noticeKo: String?) { shown = playing }
        override fun showPlaying(playing: TestSignal?) { shown = playing }
        override fun showNotice(noticeKo: String?) = Unit
        override fun userRequest(signal: TestSignal, amplitude: Double?) =
            kr.joa.selahrta.audio.SignalRequest(signal, amplitude ?: kr.joa.selahrta.audio.DEFAULT_AMPLITUDE)
        override fun openSink(onRouteState: ((kr.joa.selahrta.audio.OutputRouteState) -> Unit)?) =
            sinks.pollFirst() ?: kr.joa.selahrta.audio.FakeSink()
        override fun nowMs() = 0L
    }

    /**
     * 끊긴 옛 작업의 `finally` 가 **새 작업이 소리를 낸 뒤에** 돈다. `stopWork` 는 「재는 중」을 곧바로 지우므로 새
     * 작업이 그 사이 시작할 수 있다. 예전에는 bridge 하나가 주인 하나를 끝까지 써서, 옛 `finally` 의 주인별 정지가
     * 새 작업의 소리를 껐다.
     */
    /** @return 옛 작업의 정리 **직후** 새 작업의 소리가 꺼져 있었는가, 그때 화면에 보인 신호. */
    private fun lateFinallyOfOldWork(shareOwner: Boolean): Pair<Boolean, TestSignal?> {
        val oldSink = kr.joa.selahrta.audio.FakeSink()
        val newSink = kr.joa.selahrta.audio.FakeSink()
        val host = InlineHost(oldSink, newSink)
        val signals = kr.joa.selahrta.audio.SignalController(
            host = host,
            commands = kr.joa.selahrta.audio.SerialCommands("wizard-probe") { it.run() },
        )
        val seen = arrayOfNulls<Pair<Boolean, TestSignal?>>(1)
        try {
            runLateFinally(signals, shareOwner, oldSink, newSink) {
                seen[0] = (newSink.stopped || newSink.released) to host.shown
            }
            return seen[0]!!
        } finally {
            signals.close()
        }
    }

    private fun runLateFinally(
        signals: kr.joa.selahrta.audio.SignalController,
        shareOwner: Boolean,
        oldSink: kr.joa.selahrta.audio.FakeSink,
        newSink: kr.joa.selahrta.audio.FakeSink,
        afterOldFinally: () -> Unit,
    ) = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val core = coordinator(scope)
        run {
            val cap = OwnedCapture(signals, identity(0, "card=1;device=0"), shareOwner)
            val oldGate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val newGate = kotlinx.coroutines.CompletableDeferred<Unit>()

            // 옛 작업: 소리를 낸 뒤의 기다림은 **취소에도 깨지 않는다** — 그래서 끊어도 finally 가 늦는다.
            core.runInputCheck(cap, fft, rate, gatedTick(cap, oldGate, nonCancellable = true))
            testScheduler.advanceUntilIdle()
            assertTrue("전제 — 옛 작업이 소리를 냈다", oldSink.opened)

            core.stopWork()
            core.runInputCheck(cap, fft, rate, gatedTick(cap, newGate, nonCancellable = false))
            testScheduler.advanceUntilIdle()
            assertTrue("전제 — 새 작업이 소리를 냈다", newSink.opened)
            assertEquals("전제 — 옛 작업의 정리는 아직이다", 0, cap.shared.removed)

            oldGate.complete(Unit) // 이제야 옛 작업의 finally
            testScheduler.advanceUntilIdle()
            assertEquals("전제 — 옛 작업의 정리가 돌았다", 1, cap.shared.removed)
            afterOldFinally()
            newGate.complete(Unit)
            core.stopWork()
            testScheduler.advanceUntilIdle()
        }
    }

    @Test
    fun `끊긴 옛 작업의 늦은 정리가 새 작업의 소리를 끄지 않는다`() {
        val (cut, shown) = lateFinallyOfOldWork(shareOwner = false)
        assertFalse("새 작업의 소리가 꺼졌다", cut)
        assertEquals(TestSignal.Pink, shown)
    }

    /** 대조: 작업이 주인을 나누지 않으면(고치기 전의 bridge) 옛 정리가 새 소리를 끈다 — 위 시험이 그 차이를 가른다. */
    @Test
    fun `대조 — 주인을 나누지 않으면 옛 정리가 새 작업의 소리를 끈다`() {
        val (cut, _) = lateFinallyOfOldWork(shareOwner = true)
        assertTrue(cut)
    }
}

/** 시험 안에서만 쓰는 짧은 물음. */
private fun WizardState.readyToMeasure(): Boolean = cal != null
