package kr.joa.selahrta.transfer

import android.media.AudioDeviceInfo
import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.audio.EffectState
import kr.joa.selahrta.audio.EffectsReport
import kr.joa.selahrta.audio.OpenedFormat
import kr.joa.selahrta.audio.PcmEncoding
import kr.joa.selahrta.audio.OutputRouteState
import kr.joa.selahrta.audio.RouteOrigin
import kr.joa.selahrta.audio.RouteSnapshot
import kr.joa.selahrta.audio.sameOutputKey
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.DelayResult
import kr.joa.selahrta.dsp.MeasureOutcome
import kr.joa.selahrta.dsp.TimebaseStatus
import kr.joa.selahrta.dsp.TransferMeasurement
import kr.joa.selahrta.dsp.TransferResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TF 세션 조율(TF 설계 3~6장)을 가짜 신호·입력 포트로. `CaptureViewModel`·`CaptureController` 와의 접착은
 * 포트 구현이 맡고, 그 자리의 실제 배선은 계측 시험의 몫이다.
 */
class TransferControllerTest {

    private class FakeSignal : TransferSignalPort {
        var wired = true
        var label: String? = "USB-Audio - UMC404HD 192k"
        var nextSession = 0L
        val plays = ArrayList<Long>()
        val stops = ArrayList<String?>()
        var underruns: Int? = 0
        var tap: ((PlaybackAttempt) -> ((FloatArray, Int, Int) -> Unit)?)? = null
        var onRoute: ((PlaybackAttempt, OutputRouteState) -> Unit)? = null
        var onEnded: ((Long, String?) -> Unit)? = null
        override fun transferOutputSettingIsWired() = wired
        override fun transferOutputLabel() = label
        override fun playTransferSignal(): Long = (++nextSession).also { plays += it }
        override fun stopSignal(reasonKo: String?) { stops += reasonKo }
        override fun transferUnderruns(session: Long) = underruns
        override fun bindTransfer(
            tapFactory: ((PlaybackAttempt) -> ((FloatArray, Int, Int) -> Unit)?)?,
            onRoute: ((PlaybackAttempt, OutputRouteState) -> Unit)?,
            onSessionEnded: ((Long, String?) -> Unit)?,
        ) {
            tap = tapFactory
            this.onRoute = onRoute
            onEnded = onSessionEnded
        }
    }

    private class FakeCapture : TransferCapturePort {
        override var running = true
        var format: OpenedFormat? = builtIn()
        var captureId = 5L
        var readSeq = 100L
        var port: TransferInputPort? = null
        override fun confirmedFormat() = format
        override fun currentCaptureId() = captureId
        override fun currentReadSeq() = readSeq
        override fun bindInput(port: TransferInputPort?) { this.port = port }
    }

    private companion object {
        fun builtIn(kind: MicKind = MicKind.BuiltIn, rate: Int = 48_000) = OpenedFormat(
            micKind = kind,
            sampleRate = rate,
            channelCount = 1,
            channelIndex = 0,
            encoding = PcmEncoding.Float,
            audioSource = CaptureSource.VoiceRecognition,
            bufferSizeBytes = 4096,
            deviceLabel = "SM-S918N",
            deviceKey = "k",
            unprocessedSupported = false,
            effects = EffectsReport(
                EffectState("AGC", available = false, wasEnabled = false, disabled = true),
                EffectState("NS", available = false, wasEnabled = false, disabled = true),
                EffectState("AEC", available = false, wasEnabled = false, disabled = true),
            ),
            routeConfirmed = true,
        )

        val UMC = sameOutputKey(AudioDeviceInfo.TYPE_USB_DEVICE, "UMC404HD 192k", "card=1;device=0")

        fun route(origin: RouteOrigin = RouteOrigin.Initial, actual: String? = UMC) =
            OutputRouteState(origin, UMC, false, AudioDeviceInfo.TYPE_USB_DEVICE, actual)
    }

    private var now = 0L
    private val sig = FakeSignal()
    private val cap = FakeCapture()
    private val c = TransferController(sig, cap, nowMs = { now })

    private fun startAndArm() {
        c.start()
        sig.onRoute!!(PlaybackAttempt(SignalOwner.Transfer(c.session), 1), route())
    }

    // ── 시작 관문 ───────────────────────────────────────────────────────

    @Test
    fun `입력 확인 전이면 소리를 내지 않는다 — 저절로 이어 시작하지도 않는다`() {
        cap.format = null
        c.start()
        assertTrue(sig.plays.isEmpty())
        assertFalse(c.state.value.running)
        cap.format = builtIn()
        assertTrue("확인돼도 저절로 시작하지 않는다", sig.plays.isEmpty())
    }

    @Test
    fun `폰 마이크가 아니거나 48k 가 아니거나 유선이 아니면 시작하지 않는다`() {
        cap.format = builtIn(kind = MicKind.Usb)
        c.start()
        cap.format = builtIn(rate = 44_100)
        c.start()
        cap.format = builtIn()
        sig.wired = false
        c.start()
        assertTrue(sig.plays.isEmpty())
    }

    @Test
    fun `측정이 멈춰 있으면 시작하지 않는다`() {
        cap.running = false
        c.start()
        assertTrue(sig.plays.isEmpty())
    }

    // ── 무장 ────────────────────────────────────────────────────────────

    @Test
    fun `출력이 고른 USB 로 확인되면 그 순간 읽기 번호를 경계로 무장한다`() {
        c.start()
        assertEquals(listOf(1L), sig.plays)
        assertNull("확인 전에는 박자가 없다", c.beginTick())
        sig.onRoute!!(PlaybackAttempt(SignalOwner.Transfer(1), 1), route())
        assertNotNull(c.beginTick())
        assertTrue(c.state.value.outputOk)
        assertTrue(c.state.value.inputOk)
    }

    @Test
    fun `폰 스피커로 나가면 무장하지 않고 3초 뒤 멈춘다`() {
        c.start()
        sig.onRoute!!(
            PlaybackAttempt(SignalOwner.Transfer(1), 1),
            OutputRouteState(RouteOrigin.Initial, UMC, false, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "spk"),
        )
        assertNull(c.beginTick())
        now = TransferController.CONFIRM_LIMIT_MS
        assertNull(c.beginTick())
        assertEquals(1, sig.stops.size)
        assertFalse(c.state.value.running)
    }

    @Test
    fun `다른 세션의 경로 보고는 무시한다`() {
        c.start()
        sig.onRoute!!(PlaybackAttempt(SignalOwner.Transfer(99), 1), route())
        assertNull(c.beginTick())
    }

    // ── 무장 뒤 경로 사건 ─────────────────────────────────────────────────

    @Test
    fun `무장 뒤 출력 사건은 같은 키여도 멈춘다`() {
        startAndArm()
        sig.onRoute!!(PlaybackAttempt(SignalOwner.Transfer(1), 1), route(origin = RouteOrigin.Event))
        assertEquals(1, sig.stops.size)
        assertFalse(c.state.value.running)
    }

    @Test
    fun `무장 뒤 입력 통지 다음 같은 경로 스냅샷이면 곧바로 다시 무장한다`() {
        startAndArm()
        cap.port!!.onRawNotice(5)
        assertNull("통지 순간 무장이 풀린다", c.beginTick())
        cap.port!!.onSnapshot(RouteSnapshot(5, 1, builtIn(), readSeqAtSnapshot = 140))
        assertNotNull("같은 경로면 3초를 기다리지 않고 다시 무장", c.beginTick())
        assertTrue(sig.stops.isEmpty())
    }

    @Test
    fun `무장 뒤 입력 통지 다음 확인이 안 서면 3초 뒤 멈춘다`() {
        startAndArm()
        now = 1_000
        cap.port!!.onRawNotice(5)
        cap.port!!.onSnapshot(RouteSnapshot(5, 1, builtIn(kind = MicKind.Usb), 140))
        now = 1_000 + TransferController.CONFIRM_LIMIT_MS
        assertNull(c.beginTick())
        assertEquals(1, sig.stops.size)
    }

    @Test
    fun `옛 캡처의 통지와 스냅샷은 무시한다`() {
        startAndArm()
        cap.port!!.onRawNotice(4)
        assertNotNull(c.beginTick())
    }

    @Test
    fun `캡처가 끝나면 멈춘다`() {
        startAndArm()
        cap.port!!.onCaptureEnded(5)
        assertEquals(1, sig.stops.size)
    }

    // ── 박자·게시 ───────────────────────────────────────────────────────

    private fun measured(end: Long, found: Boolean = true) = MeasureOutcome.Measured(
        TransferMeasurement(
            delay = DelayResult(6000, 4.0, found),
            transfer = if (found) TransferResult(
                magnitudeDb = DoubleArray(4097) { 0.0 },
                coherence = DoubleArray(4097) { 0.95 },
                valid = BooleanArray(4097) { it > 0 },
                averages = 16,
            ) else null,
            windowEnd = end,
            epoch = 1,
            timebase = TimebaseStatus.Unverified,
        ),
    )

    @Test
    fun `게시하면 그래프가 생기고 시간축은 미검증이다`() {
        startAndArm()
        val t = c.beginTick()!!
        c.finishTick(t, measured(100_000))
        val s = c.state.value
        assertNotNull(s.graphs)
        assertNull(s.statusKo)
        assertFalse(s.timebaseVerified)
        assertTrue(s.match is MatchState.Found)
    }

    @Test
    fun `계산 중에 무장이 풀린 결과는 버린다`() {
        startAndArm()
        val t = c.beginTick()!!
        cap.port!!.onRawNotice(5)
        cap.port!!.onSnapshot(RouteSnapshot(5, 1, builtIn(), 140))
        c.finishTick(t, measured(100_000))
        assertNull("옛 세대의 결과가 게시되지 않았다", c.state.value.graphs)
    }

    @Test
    fun `멈춘 뒤 끝난 계산은 게시되지 않는다`() {
        startAndArm()
        val t = c.beginTick()!!
        c.stop("멈춤")
        c.finishTick(t, measured(100_000))
        assertNull(c.state.value.graphs)
    }

    @Test
    fun `언더런 수가 늘면 곡선을 지우고 다시 모은다`() {
        startAndArm()
        c.finishTick(c.beginTick()!!, measured(100_000))
        assertNotNull(c.state.value.graphs)
        sig.underruns = 1
        c.finishTick(c.beginTick()!!, measured(150_000))
        assertNull(c.state.value.graphs)
        assertEquals("출력이 끊겨 다시 모읍니다", c.state.value.statusKo)
    }

    @Test
    fun `언더런 수를 모르면 게시하지 않는다`() {
        startAndArm()
        sig.underruns = null
        c.finishTick(c.beginTick()!!, measured(100_000))
        assertNull(c.state.value.graphs)
    }

    @Test
    fun `신호 쪽이 세션을 끝내면 화면도 멈춘다 — 포커스 손실 따위`() {
        startAndArm()
        sig.onEnded!!(1L, "다른 앱이 소리를 가져갔습니다")
        assertFalse(c.state.value.running)
        assertEquals("다른 앱이 소리를 가져갔습니다", c.state.value.statusKo)
        assertNull(c.beginTick())
    }

    @Test
    fun `다른 세션의 끝 소식은 무시한다`() {
        startAndArm()
        sig.onEnded!!(99L, "옛 세션")
        assertTrue(c.state.value.running)
    }

    @Test
    fun `멈추면 입력 통로와 신호 콜백을 뗀다`() {
        startAndArm()
        c.stop(null)
        assertNull(cap.port)
        assertNull(sig.tap)
    }
}
