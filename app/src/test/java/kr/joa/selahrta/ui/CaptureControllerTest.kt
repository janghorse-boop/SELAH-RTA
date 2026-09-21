package kr.joa.selahrta.ui

import kr.joa.selahrta.audio.CaptureEnd
import kr.joa.selahrta.audio.DisconnectPolicy
import kr.joa.selahrta.audio.InputDeviceInfo
import kr.joa.selahrta.audio.OpenedFormat
import kr.joa.selahrta.calibration.ActiveCalibration
import kr.joa.selahrta.domain.CalibrationState
import kr.joa.selahrta.calibration.GlobalCalibration
import kr.joa.selahrta.domain.FailureReason
import kr.joa.selahrta.domain.MeasureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 측정 세션의 **상태 전이**를 시험한다.
 *
 * 독립 검증이 요구한 것이다 — 「캡처 루프와 세대 클래스의 단위 시험만으로는
 * 부족하며, ViewModel 의 상태 전이까지 검증해야 한다」. 실제로 두 결함이
 * 그 틈에서 났다:
 *
 * - **P9-01**: 기록이 스냅샷에서만 오는데 `stop()` 이 옮기지 않아 사라졌다.
 * - **C01**: 입구 검사를 이미 지난 콜백과 `finish()` 가 부딪혔다.
 *
 * 둘 다 DSP 시험은 전부 통과하는 상태였다.
 *
 * **시험용 사본을 쓰지 않는다.** 여기서 도는 것은 실제 [CaptureController]
 * 이고, 바꿔 끼운 것은 안드로이드에 매인 넷뿐이다.
 */
class CaptureControllerTest {

    private lateinit var devices: MutableList<InputDeviceInfo>
    private lateinit var sources: MutableList<FakeSource>
    private lateinit var confirmed: MutableList<OpenedFormat>
    private var stoppedHookCount = 0
    private lateinit var controller: CaptureController
    private lateinit var clock: FakeClock

    /** 주 스레드로 넘기는 일을 **그 자리에서** 한다. 순서를 시험이 쥔다. */
    private val inline: (() -> Unit) -> Unit = { it() }

    private fun build(
        deviceList: List<InputDeviceInfo> = listOf(builtInMic()),
        confirmOnStart: Boolean = true,
    ) {
        devices = deviceList.toMutableList()
        sources = mutableListOf()
        confirmed = mutableListOf()
        stoppedHookCount = 0
        clock = FakeClock()
        controller = CaptureController(
            listDevices = { devices },
            openSource = { target, hooks ->
                FakeSource(target, hooks, confirmOnStart = confirmOnStart, clock = clock)
                    .also { sources.add(it) }
            },
            post = inline,
            onRouteConfirmedHook = { confirmed.add(it) },
            onStoppedHook = { stoppedHookCount++ },
            // **시험이 시계를 쥔다.** 실제 시계를 쓰면 화면 갱신 간격(66ms)이
            // 기계 속도에 좌우돼 같은 시험이 통과했다 실패했다 한다.
            nowNs = { clock.ns },
        )
    }

    private val last: FakeSource get() = sources.last()
    private val state get() = controller.state.value

    /** 화면이 보는 값 — 측정 결과를 합친 뒤의 상태. */
    private fun composed() = state.withMeasurement(controller.measurement.value)

    // ---- 기본 흐름 ----

    @Test
    fun `시작하면 열고 경로가 확인되면 보정을 지켜본다`() {
        build()
        controller.start()

        assertTrue("열려야 한다", last.started)
        assertTrue(state.measure is MeasureState.Running)
        assertTrue("경로가 확인됐다", state.routeConfirmed)
        assertEquals("확인된 뒤에야 보정을 지켜본다", 1, confirmed.size)
    }

    /**
     * R01 — 경로가 확인되기 전에는 보정을 걸지 않는다.
     *
     * 확인 전의 열쇠는 「요청한 기기」의 것이라, 그 열쇠로 보정을 걸면
     * 다른 마이크의 소리에 엉뚱한 감도를 적용하게 된다.
     */
    @Test
    fun `경로가 확인되기 전에는 보정을 지켜보지 않는다`() {
        build(confirmOnStart = false)
        controller.start()

        assertTrue(state.measure is MeasureState.Running)
        assertFalse("아직 확인 안 됨", state.routeConfirmed)
        assertTrue("구독도 아직", confirmed.isEmpty())
        assertTrue("미보정이어야 한다", state.calibration.isReferenceOnly)

        last.confirmRoute()
        assertTrue(state.routeConfirmed)
        assertEquals(1, confirmed.size)
    }

    @Test
    fun `쓸 수 있는 기기가 없으면 그 사실을 알린다`() {
        build(deviceList = emptyList())
        controller.start()

        assertEquals(MeasureState.Failed(FailureReason.NoInputDevice), state.measure)
        assertNotNull(state.errorKo)
        assertFalse(controller.running)
    }

    // ---- 검증자가 요구한 순서들 ----

    /**
     * **onBlock 에 들어온 뒤 stop.**
     *
     * C01 이 난 자리다. 콜백이 입구 검사를 지난 뒤 주 스레드가 멈추면,
     * 예전에는 `finish()` 와 DSP 처리가 같은 객체를 동시에 만졌다.
     */
    @Test
    fun `덩어리를 처리한 직후에 멈춰도 탈나지 않는다`() {
        build()
        controller.start()
        repeat(40) { last.deliver() }

        controller.stop()

        assertFalse(controller.running)
        assertEquals(MeasureState.Idle, state.measure)
        assertEquals("보정 구독을 끊는다", 1, stoppedHookCount)
        assertTrue("소스를 닫는다", last.closed)
    }

    /**
     * **P9-01 — 멈춘 뒤에도 기록이 남는다.**
     *
     * 이것이 DSP 시험으로 안 잡히던 결함이다. 기록은 스냅샷에서만 화면
     * 상태로 오는데, `stop()` 이 스냅샷을 비우면서 옮기지 않았다.
     */
    @Test
    fun `멈춘 뒤에도 하울링 기록이 남는다`() {
        build()
        controller.start()
        // 1kHz 순음을 충분히 길게 넣어 「지속」까지 보낸다.
        feedTone(1000.0, seconds = 2.5)
        assertTrue("멈추기 전에 기록이 있어야 한다", composed().feedbackLog.isNotEmpty())

        controller.stop()

        val log = composed().feedbackLog
        assertTrue("멈춘 뒤에도 남아야 한다", log.isNotEmpty())
        assertTrue("모두 닫혀야 한다", log.none { it.ongoing })
        assertTrue("지금 울리는 후보는 없다", composed().feedback.isEmpty())
    }

    /** 새 측정을 시작하면 지난 기록은 비운다. */
    @Test
    fun `새 측정을 시작하면 지난 기록을 비운다`() {
        build()
        controller.start()
        feedTone(1000.0, seconds = 2.5)
        controller.stop()
        assertTrue(composed().feedbackLog.isNotEmpty())

        controller.start()
        assertTrue("새 측정은 빈 기록으로 시작한다", composed().feedbackLog.isEmpty())
    }

    /**
     * **F04 — 멈춘 뒤 늦게 온 경로 확인이 보정을 되살리지 않는다.**
     */
    @Test
    fun `멈춘 뒤 늦게 온 경로 확인은 버린다`() {
        build(confirmOnStart = false)
        controller.start()
        val src = last
        controller.stop()

        src.confirmRoute()

        assertTrue("구독이 시작되면 안 된다", confirmed.isEmpty())
        assertEquals("멈춘 상태 그대로", MeasureState.Idle, state.measure)
    }

    /** 멈춘 뒤 늦게 온 오류가 정상 종료를 실패로 뒤집지 않는다. */
    @Test
    fun `멈춘 뒤 늦게 온 오류는 버린다`() {
        build()
        controller.start()
        val src = last
        controller.stop()

        src.endWith(CaptureEnd.ReadError)

        assertEquals(MeasureState.Idle, state.measure)
        assertNull(state.errorKo)
    }

    /**
     * **F02 — A→B 로 바꾼 뒤 A 의 덩어리가 B 에 섞이지 않는다.**
     */
    @Test
    fun `이전 세션의 덩어리는 새 세션에 섞이지 않는다`() {
        build()
        controller.start()
        val a = last
        controller.stop()
        controller.start()
        val b = last

        // A 가 늦게 깨어나 덩어리를 흘린다.
        repeat(10) { a.deliver(amplitude = 0.9f) }

        val m = controller.measurement.value
        if (m != null) {
            assertEquals("새 세션의 것만 받아들인다", state.session, m.session)
        }
        // B 의 덩어리는 정상으로 받는다.
        repeat(5) { b.deliver(amplitude = 0.1f) }
        assertEquals(state.session, controller.measurement.value!!.session)
    }

    // ---- 분리 정책: 두 순서를 바꿔 본다 ----

    /**
     * **F03 — 읽기 오류가 먼저 와도 분리 정책이 돈다.**
     *
     * 예전에는 오류가 먼저 오면 `stop()` 이 `source` 를 비워, 뒤따라온
     * 목록 변경 처리를 건너뛰어 정책이 통째로 실행되지 않았다.
     */
    @Test
    fun `읽기 오류가 먼저 와도 내장으로 전환한다`() {
        build(deviceList = listOf(usbMic(), builtInMic()))
        controller.update { it.copy(meterSettings = it.meterSettings.copy(
            preferredInputKey = usbMic().stableKey,
            disconnectPolicy = DisconnectPolicy.FallBack,
        )) }
        controller.start()
        assertEquals("USB 로 열렸다", usbMic().stableKey, state.opened?.deviceKey)

        // USB 가 빠졌다 — 읽기 오류가 먼저 온다.
        devices.removeAll { it.kind == kr.joa.selahrta.domain.MicKind.Usb }
        sources.last().endWith(CaptureEnd.DeviceLost)

        assertTrue("다시 돌아야 한다", controller.running)
        assertEquals(
            "내장으로 바뀌어야 한다",
            kr.joa.selahrta.domain.MicKind.BuiltIn,
            state.opened?.micKind,
        )
    }

    /** 목록 변경이 먼저 와도 결과가 같아야 한다. */
    @Test
    fun `목록 변경이 먼저 와도 내장으로 전환한다`() {
        val before = listOf(usbMic(), builtInMic())
        build(deviceList = before)
        controller.update { it.copy(meterSettings = it.meterSettings.copy(
            preferredInputKey = usbMic().stableKey,
            disconnectPolicy = DisconnectPolicy.FallBack,
        )) }
        controller.start()

        devices.removeAll { it.kind == kr.joa.selahrta.domain.MicKind.Usb }
        controller.onDeviceListChanged(before, devices)

        assertTrue(controller.running)
        assertEquals(
            kr.joa.selahrta.domain.MicKind.BuiltIn,
            state.opened?.micKind,
        )
    }

    /** 「멈추고 기다리기」 정책이면 멈춘 채로 둔다. */
    @Test
    fun `분리 정책이 멈추기면 다시 시작하지 않는다`() {
        val before = listOf(usbMic(), builtInMic())
        build(deviceList = before)
        controller.update { it.copy(meterSettings = it.meterSettings.copy(
            preferredInputKey = usbMic().stableKey,
            disconnectPolicy = DisconnectPolicy.Pause,
        )) }
        controller.start()

        devices.removeAll { it.kind == kr.joa.selahrta.domain.MicKind.Usb }
        controller.onDeviceListChanged(before, devices)

        assertFalse(controller.running)
        assertEquals(MeasureState.Failed(FailureReason.DeviceLost), state.measure)
    }

    /**
     * **F01 — 재는 도중 경로가 바뀌면 그 세션을 끝낸다.**
     *
     * 안내문만 띄우고 계속 재면 B 마이크의 소리에 A 의 보정을 건다.
     */
    @Test
    fun `재는 도중 경로가 바뀌면 그 세션을 끝낸다`() {
        build(deviceList = listOf(builtInMic(1, "bottom"), builtInMic(2, "back")))
        controller.update { it.copy(meterSettings = it.meterSettings.copy(
            disconnectPolicy = DisconnectPolicy.Pause,
        )) }
        controller.start()
        val first = last

        first.rerouteTo(builtInMic(2, "back"))

        assertFalse("측정이 끝나야 한다", controller.running)
        assertNotNull("까닭을 알려야 한다", state.errorKo)
    }

    // ---- 보정이 늦게 도착하는 경우 ----

    /**
     * **R03 — 보정이 도착하기 전의 값은 미보정이다.**
     *
     * 기기를 바꾼 직후 첫 덩어리들이 지난 마이크의 보정으로 나가면 안 된다.
     */
    @Test
    fun `보정이 도착하기 전에는 미보정으로 그린다`() {
        build()
        controller.start()
        repeat(20) { last.deliver() }
        val before = composed()
        assertTrue("아직 미보정", before.calibration.isReferenceOnly)
        val uncalibrated = before.meter.currentSpl

        // 이제 보정이 도착한다(ViewModel 의 DataStore 구독이 하는 일).
        controller.update {
            it.copy(
                calibration = ActiveCalibration.from(
                    // **미보정 기본값(120dB)과 다른 값을 쓴다.** 같은 값을
                    // 주면 보정 전후 숫자가 같아 시험이 아무것도 못 본다.
                    GlobalCalibration(
                        offsetDb = 95.0,
                        savedAtEpochMs = 0,
                        referenceDb = 80.0,
                        measuredDbfs = -15.0,
                    ),
                ),
            )
        }
        val after = composed()

        assertEquals(CalibrationState.GlobalCalibrated, after.calibration.state)
        assertNotNull("보정 전에도 값은 있었다", uncalibrated)
        assertEquals(
            "같은 소리가 25dB 낮은 눈금으로 다시 그려진다",
            uncalibrated!! - 25.0,
            after.meter.currentSpl!!,
            1e-9,
        )
    }

    /** 멈추면 보정을 놓는다 — 다음 기기에 붙지 않게. */
    @Test
    fun `멈추면 보정을 놓는다`() {
        build()
        controller.start()
        controller.update {
            it.copy(
                calibration = ActiveCalibration.from(
                    GlobalCalibration(95.0, 0, 80.0, -15.0),
                ),
            )
        }
        assertFalse(state.calibration.isReferenceOnly)

        controller.stop()

        assertTrue("미보정으로 돌아가야 한다", state.calibration.isReferenceOnly)
        assertNull("열린 기기도 지운다", state.opened)
        assertNotNull("마지막으로 쓴 기기는 남는다", state.lastInput)
    }

    // ---- 도우미 ----

    /** 순음을 실제 시간에 맞춰 넣는다. 하울링 「지속」까지 보내려고 쓴다. */
    private fun feedTone(hz: Double, seconds: Double) {
        val fs = 48_000
        val block = 1024
        val blocks = (seconds * fs / block).toInt()
        var n = 0
        repeat(blocks) {
            val buf = FloatArray(block) {
                (0.3 * kotlin.math.sin(2 * Math.PI * hz * (n++) / fs)).toFloat()
            }
            last.deliverRaw(buf)
        }
    }
}
