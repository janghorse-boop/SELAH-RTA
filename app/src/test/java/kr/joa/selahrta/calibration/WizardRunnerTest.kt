package kr.joa.selahrta.calibration

import kotlinx.coroutines.runBlocking
import kr.joa.selahrta.audio.TestSignal
import kr.joa.selahrta.dsp.CalibrationCurve
import kr.joa.selahrta.dsp.CalibrationSession
import kr.joa.selahrta.dsp.CurvePoint
import kr.joa.selahrta.dsp.DspVerdict
import kr.joa.selahrta.dsp.MeasureStep
import kr.joa.selahrta.dsp.MeasurementTap
import kr.joa.selahrta.dsp.ThirdOctave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **측정 순서를 기기 없이 돌려 본다.**
 *
 * 기다림을 주입받게 만든 까닭이 여기 있다. 가짜 기다림이 통로에 장을
 * 밀어 넣으므로, 「신호를 틀고 → 모으고 → 멈추고 → 판정한다」가 정말
 * 그 순서로 도는지를 시험이 볼 수 있다.
 */
class WizardRunnerTest {

    private val fftSize = 1024
    private val rate = 48_000
    private val bins = fftSize / 2 + 1
    private val deviceKey = "Usb|UMC404HD|"

    private fun curve() = CalibrationCurve.of(
        listOf(CurvePoint(10.0, 0.0), CurvePoint(25_000.0, 0.0)),
    ).getOrThrow()

    /** 무슨 일이 어떤 차례로 일어났는지 적어 두는 가짜. */
    private class FakeCapture(
        override var openedDeviceKey: String? = "Usb|UMC404HD|",
        var clipping: Boolean = false,
    ) : WizardCapture {
        override val openedCalKey: kr.joa.selahrta.calibration.CalibrationKey?
            get() = openedDeviceKey?.let {
                kr.joa.selahrta.calibration.CalibrationKey(
                    deviceKey = it,
                    source = kr.joa.selahrta.audio.CaptureSource.Unprocessed,
                )
            }

        /**
         * **실제 모양의 수집 신원**(독립 재검토 CAR-01·CAR-05).
         *
         * 가짜가 신원을 못 내놓으면, 신원을 대조하는 새 관문이 시험에서
         * 늘 「경로 미확인」으로 막혀 아무것도 확인하지 못한다.
         */
        override val identity: kr.joa.selahrta.calibration.CaptureIdentity?
            get() = openedCalKey?.let {
                kr.joa.selahrta.calibration.CaptureIdentity(
                    calKey = it,
                    routedAddress = "card=1;device=0",
                    sampleRate = 48_000,
                    routeConfirmed = true,
                    generation = 1L,
                )
            }

        override val openedOffsetDb: Double? = null

        val log = mutableListOf<String>()
        var marks = 0

        override val clippedSinceMark: Boolean get() = clipping

        override fun markClippingBaseline() {
            marks++
            log += "mark"
        }
        var taps = 0
        var playing: TestSignal? = null

        override fun installTap(tap: MeasurementTap) {
            taps++; log += "tap+"
        }

        override fun removeTap(tap: MeasurementTap) {
            taps--; log += "tap-"
        }

        var level: Double? = null

        override fun playSignal(signal: TestSignal, amplitude: Double) {
            playing = signal; this.level = amplitude; log += "play:${signal.name}"
        }

        override fun stopSignal() {
            playing = null; log += "stop"
        }
    }

    private fun tap(max: Int = 1_000) = MeasurementTap(fftSize, rate, maxFrames = max)

    /**
     * 기다릴 때마다 통로에 장을 한 장 밀어 넣는 가짜 시간.
     *
     * [level] 을 바꿔 가며 밀면 AGC 같은 시간 변화를 흉내 낼 수 있다.
     */
    private class FakeClock(
        private val tap: MeasurementTap,
        private val bins: Int,
        var level: Double = 1e-6,
        var perTick: Int = 1,
    ) {
        var ticks = 0
        val wait: suspend () -> Unit = {
            ticks++
            repeat(perTick) { tap.onSpectrum(DoubleArray(bins) { level }) }
        }
    }

    // ------------------------------------------------------------------
    // 잡음 바닥
    // ------------------------------------------------------------------

    @Test
    fun `잡음은 신호를 끄고 잰다`() = runBlocking {
        val cap = FakeCapture()
        val t = tap()
        val clock = FakeClock(t, bins, level = 1e-10)
        val runner = WizardRunner(cap, clock.wait)

        val r = runner.measureNoiseFloor(t, frames = 10)

        assertTrue("$r", r is RunOutcome.Done)
        assertEquals(ThirdOctave.BAND_COUNT, (r as RunOutcome.Done).value.size)
        assertFalse("신호를 틀면 안 된다", cap.log.any { it.startsWith("play") })
        assertEquals("stop", cap.log.first())
    }

    @Test
    fun `장이 안 들어오면 잡음 재기가 실패한다`() = runBlocking {
        val cap = FakeCapture()
        val t = tap()
        // 아무것도 밀어 넣지 않는 시계.
        val runner = WizardRunner(cap, tick = {}, maxTicks = 5)

        val r = runner.measureNoiseFloor(t, frames = 10)
        assertTrue("$r", r is RunOutcome.Failed)
    }

    // ------------------------------------------------------------------
    // DSP 점검
    // ------------------------------------------------------------------

    @Test
    fun `DSP 점검은 틀고 모으고 끈다`() = runBlocking {
        val cap = FakeCapture()
        val t = tap()
        val clock = FakeClock(t, bins)
        val runner = WizardRunner(cap, clock.wait)

        val r = runner.checkDsp(t, noiseFloorDb = null, frames = 30)

        assertTrue("$r", r is RunOutcome.Done)
        assertEquals(DspVerdict.NoTimeVaryingFound, (r as RunOutcome.Done).value.verdict)
        assertEquals(listOf("play:Pink", "stop"), cap.log)
        assertFalse("끝나고도 틀어 놓으면 안 된다", cap.playing != null)
    }

    /**
     * **화면을 나간 뒤에는 소리를 내지 않는다**(독립 검토 CA-05).
     *
     * 원래 결함은 배경을 재는 동안 홈으로 나가도 작업이 살아남아, 나간
     * 뒤에 핑크 잡음이 시작되는 것이었다. 예배당에서 멈출 방법이 없다.
     */
    @Test
    fun `앞에 없으면 소리를 내지 않는다`() = runBlocking {
        val cap = FakeCapture()
        val t = tap()
        val runner = WizardRunner(cap, FakeClock(t, bins).wait, mayPlay = { false })

        val r = runner.checkDsp(t, noiseFloorDb = null, frames = 30)

        assertTrue("$r", r is RunOutcome.Failed)
        assertEquals(LEFT_SCREEN_KO, (r as RunOutcome.Failed).reasonKo)
        assertFalse("소리가 났다", cap.log.any { it.startsWith("play") })
    }

    /**
     * **다시 들어오면 정상으로 돌아온다**(독립 재검토 CA-R04).
     *
     * 위 결함을 고치면서 이쪽을 막았다 — 탭을 옮기면 `stopWork()` 가
     * 불리는데 그것이 전경 상태까지 내려, 마법사를 다시 열어도 영영
     * 소리를 못 냈다. 「다시 시작」을 눌러도 풀리지 않았다.
     *
     * 막는 것과 되돌아오는 것은 **함께** 지켜져야 한다.
     */
    @Test
    fun `다시 앞으로 오면 소리를 낼 수 있다`() = runBlocking {
        val cap = FakeCapture()
        var foreground = false
        val t = tap()
        val runner = WizardRunner(cap, FakeClock(t, bins).wait, mayPlay = { foreground })

        runner.checkDsp(t, noiseFloorDb = null, frames = 30)
        assertFalse("나가 있는데 소리가 났다", cap.log.any { it.startsWith("play") })

        foreground = true
        val again = runner.checkDsp(t, noiseFloorDb = null, frames = 30)
        assertTrue("다시 열었는데 못 냈다: $again", again is RunOutcome.Done)
        assertTrue("소리를 내야 한다", cap.log.any { it == "play:Pink" })
    }

    /**
     * **스피커가 데워지는 동안의 무음이 판정에 들어가면 안 된다.**
     *
     * 이 시험은 처음에 무음을 **한 장만** 밀어 넣었고, 그래서 아무것도
     * 재지 못했다 — 한 장은 중앙값이 이미 걸러 준다. 「먼저 모으기
     * 시작」으로 바꿔도 시험이 통과해서 그 사실을 알았다.
     *
     * 실제 위험은 한 장이 아니라 **AudioTrack 이 데워지는 수백 ms** 다.
     * 여기서는 그만큼(앞창을 넘는 장 수)을 무음으로 밀어 넣는다.
     */
    @Test
    fun `스피커가 데워지는 동안의 무음은 판정에 안 들어간다`() = runBlocking {
        val cap = FakeCapture()
        val t = tap()
        val noise = List(ThirdOctave.BAND_COUNT) { -120.0 }
        var pushed = 0
        // 앞의 열두 틱은 무음(앞창 여덟 장보다 많다), 그 뒤로 신호.
        val runner = WizardRunner(cap, {
            pushed++
            t.onSpectrum(DoubleArray(bins) { if (pushed <= 12) 1e-14 else 1e-6 })
        })

        val r = runner.checkDsp(t, noiseFloorDb = noise, frames = 30)

        assertTrue("$r", r is RunOutcome.Done)
        val res = (r as RunOutcome.Done).value
        assertEquals(res.reasonsKo.toString(), DspVerdict.NoTimeVaryingFound, res.verdict)
    }

    /**
     * 소리가 아예 안 나오면 **무음을 예순 장 모으고 엉뚱한 판정을
     * 내놓는 대신** 거기서 말한다.
     */
    @Test
    fun `소리가 안 들리면 그 자리에서 말한다`() = runBlocking {
        val cap = FakeCapture()
        val t = tap()
        val noise = List(ThirdOctave.BAND_COUNT) { -120.0 }
        val runner = WizardRunner(cap, {
            t.onSpectrum(DoubleArray(bins) { 1e-14 }) // 영영 무음
        }, maxTicks = 20)

        val r = runner.checkDsp(t, noiseFloorDb = noise, frames = 30)
        assertTrue("$r", r is RunOutcome.Failed)
        assertEquals(NOT_AUDIBLE_KO, (r as RunOutcome.Failed).reasonKo)
        assertEquals("실패해도 신호는 꺼야 한다", "stop", cap.log.last())
    }

    @Test
    fun `이득이 변하면 점검이 잡는다`() = runBlocking {
        val cap = FakeCapture()
        val t = tap()
        var level = 1e-8
        val runner = WizardRunner(cap, {
            t.onSpectrum(DoubleArray(bins) { level })
            level *= 1.15 // 장마다 조금씩 커진다 = AGC
        })

        val r = runner.checkDsp(t, noiseFloorDb = null, frames = 30)
        val res = (r as RunOutcome.Done).value
        assertEquals(DspVerdict.Suspect, res.verdict)
        assertTrue(res.reasonsKo.toString(), res.reasonsKo.any { it.contains("AGC") })
    }

    @Test
    fun `소리가 안 나오면 까닭과 함께 실패한다`() = runBlocking {
        val cap = FakeCapture()
        val t = tap()
        val runner = WizardRunner(cap, tick = {}, maxTicks = 5)

        val r = runner.checkDsp(t, noiseFloorDb = null, frames = 30)
        assertTrue("$r", r is RunOutcome.Failed)
        assertTrue((r as RunOutcome.Failed).reasonKo, r.reasonKo.contains("스피커"))
        assertEquals("실패해도 신호는 꺼야 한다", "stop", cap.log.last())
    }

    // ------------------------------------------------------------------
    // 기준 · 대상
    // ------------------------------------------------------------------

    @Test
    fun `기준 장이 증거를 달고 세션에 들어간다`() = runBlocking {
        val cap = FakeCapture()
        val t = tap()
        val clock = FakeClock(t, bins)
        val runner = WizardRunner(cap, clock.wait)
        val session = CalibrationSession()

        val r = runner.measureReference(
            t, session, MeasureStep.ReferenceBefore, curve(),
            "17860.txt", "abc123", deviceKey, frames = 20,
        )

        assertTrue("$r", r is RunOutcome.Done)
        assertEquals(20, session.frameCount(MeasureStep.ReferenceBefore))
        assertEquals("17860.txt", session.referenceProof?.calFileName)
    }

    @Test
    fun `대상 장은 CAL 없이 들어간다`() = runBlocking {
        val cap = FakeCapture(openedDeviceKey = "BuiltIn|SM-S918N|back")
        val t = tap()
        val clock = FakeClock(t, bins)
        val runner = WizardRunner(cap, clock.wait)
        val session = CalibrationSession()

        val r = runner.measureTarget(t, session, "BuiltIn|SM-S918N|back", frames = 20)

        assertTrue("$r", r is RunOutcome.Done)
        assertEquals(20, session.frameCount(MeasureStep.Target))
        assertEquals("대상에는 증거가 없다", null, session.referenceProof)
    }

    /**
     * **엉뚱한 마이크로 재지 않는다.**
     *
     * 여기서 넘어가면 다른 마이크의 값이 기준으로 들어가고, 그 뒤 모든
     * 셈이 조용히 틀린다 — 곡선은 멀쩡해 보인다.
     */
    @Test
    fun `열린 기기가 다르면 재지 않는다`() = runBlocking {
        val cap = FakeCapture(openedDeviceKey = "BuiltIn|SM-S918N|back")
        val t = tap()
        val clock = FakeClock(t, bins)
        val runner = WizardRunner(cap, clock.wait)
        val session = CalibrationSession()

        val r = runner.measureReference(
            t, session, MeasureStep.ReferenceBefore, curve(),
            "17860.txt", "abc", deviceKey, frames = 20,
        )

        assertTrue("$r", r is RunOutcome.Failed)
        assertTrue((r as RunOutcome.Failed).reasonKo, r.reasonKo.contains("UMC404HD"))
        assertEquals("재지도 말아야 한다", 0, session.frameCount(MeasureStep.ReferenceBefore))
        assertTrue("소리도 틀면 안 된다", cap.log.isEmpty())
    }

    @Test
    fun `열린 것이 없으면 그렇게 말한다`() = runBlocking {
        val cap = FakeCapture(openedDeviceKey = null)
        val runner = WizardRunner(cap, tick = {})
        val r = runner.measureTarget(tap(), CalibrationSession(), deviceKey, frames = 5)
        assertTrue((r as RunOutcome.Failed).reasonKo, r.reasonKo.contains("열린 것 없음"))
    }

    /** 찌그러진 값으로 만든 보정은 엉뚱한 쪽으로 밀어 놓는다. */
    @Test
    fun `찌그러지면 세션에 넣지 않는다`() = runBlocking {
        val cap = FakeCapture(clipping = true)
        val t = tap()
        val clock = FakeClock(t, bins)
        val runner = WizardRunner(cap, clock.wait)
        val session = CalibrationSession()

        val r = runner.measureReference(
            t, session, MeasureStep.ReferenceBefore, curve(),
            "17860.txt", "abc", deviceKey, frames = 20,
        )

        assertTrue("$r", r is RunOutcome.Failed)
        assertEquals(CLIPPED_KO, (r as RunOutcome.Failed).reasonKo)
        assertEquals("찌그러진 장이 들어가면 안 된다", 0, session.frameCount(MeasureStep.ReferenceBefore))
    }

    @Test
    fun `대상을 기준으로 넣으려 하면 막힌다`() = runBlocking {
        val runner = WizardRunner(FakeCapture(), tick = {})
        val e = runCatching {
            runner.measureReference(
                tap(), CalibrationSession(), MeasureStep.Target, curve(),
                "a", "b", deviceKey,
            )
        }.exceptionOrNull()
        assertTrue("$e", e is IllegalArgumentException)
    }

    // ------------------------------------------------------------------
    // 끝없이 기다리지 않는다
    // ------------------------------------------------------------------

    @Test
    fun `장이 안 차면 정해진 만큼만 기다린다`() = runBlocking {
        val cap = FakeCapture()
        var ticks = 0
        val runner = WizardRunner(cap, tick = { ticks++ }, maxTicks = 7)

        runner.measureTarget(tap(), CalibrationSession(), deviceKey, frames = 100)
        // 신호를 튼 뒤의 한 번 + 기다린 일곱 번.
        assertEquals(8, ticks)
    }

    // ------------------------------------------------------------------
    // 세 번을 다 재면 셈이 된다
    // ------------------------------------------------------------------

    @Test
    fun `세 단계를 다 재면 결과가 나온다`() = runBlocking {
        val cap = FakeCapture()
        val t = tap()
        val clock = FakeClock(t, bins)
        val runner = WizardRunner(cap, clock.wait)
        val session = CalibrationSession()

        runner.measureReference(
            t, session, MeasureStep.ReferenceBefore, curve(), "c.txt", "s", deviceKey, frames = 12,
        )
        cap.openedDeviceKey = "BuiltIn|SM-S918N|back"
        runner.measureTarget(t, session, "BuiltIn|SM-S918N|back", frames = 12)
        cap.openedDeviceKey = deviceKey
        runner.measureReference(
            t, session, MeasureStep.ReferenceAfter, curve(), "c.txt", "s", deviceKey, frames = 12,
        )

        assertTrue(session.complete)
        val result = session.result()
        assertTrue("셈이 나와야 한다", result != null)
        assertEquals("c.txt", result!!.referenceProof?.calFileName)
    }
}
