package kr.joa.selahrta.ui

import kr.joa.selahrta.audio.CaptureEnd
import kr.joa.selahrta.audio.DisconnectPolicy
import kr.joa.selahrta.audio.InputDeviceInfo
import kr.joa.selahrta.domain.MeasureState
import kr.joa.selahrta.domain.MicKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * **주 스레드 큐가 밀릴 때** 무슨 일이 생기는가.
 *
 * 독립 검증자가 게이트 재검증에서 적은 지적을 그대로 따른다:
 *
 * > `post={ it() }` 는 기본 기능 검증에는 유용하나 실제 main queue 대기와
 * > 순서 역전을 만들지 못한다. **보류·배출 가능한 fake dispatcher/queue 를
 * > 추가해** late event 를 검사해야 한다.
 *
 * > 두 분리 순서 시험은 각각 한 종류의 첫 이벤트를 보내는 방식이다.
 * > error→remove 와 remove→늦은 error 의 **두 이벤트 전체**, 중복 이벤트,
 * > post 큐 지연을 함께 넣어야 **한 번만 전환된다**는 성질을 검증할 수 있다.
 *
 * 그리고 G01 이 「멈추기」 하나만이 아니라 **모든 종료 경로**의 문제라는
 * 점도 여기서 함께 본다.
 */
class CaptureControllerOrderingTest {

    /**
     * 붙들었다 한꺼번에 흘려보낼 수 있는 주 스레드 대역.
     *
     * 실제 기기에서 주 스레드는 화면을 그리느라 밀린다. 그동안 오디오
     * 스레드는 계속 돌고, 멈춤·시작도 그 사이에 일어난다. `post={it()}`
     * 로는 그 순서를 만들 수 없다.
     */
    private class HeldMain {
        private val queue = ArrayDeque<() -> Unit>()
        var held = false

        val post: (() -> Unit) -> Unit = { task -> if (held) queue.add(task) else task() }

        val pending: Int get() = queue.size

        /** 밀린 일을 **들어온 순서대로** 처리한다. */
        fun drain() {
            held = false
            while (queue.isNotEmpty()) queue.removeFirst().invoke()
        }
    }

    private lateinit var devices: MutableList<InputDeviceInfo>
    private lateinit var sources: MutableList<FakeSource>
    private lateinit var controller: CaptureController
    private lateinit var clock: FakeClock
    private lateinit var main: HeldMain

    private fun build(deviceList: List<InputDeviceInfo> = listOf(builtInMic())) {
        devices = deviceList.toMutableList()
        sources = mutableListOf()
        clock = FakeClock()
        main = HeldMain()
        controller = CaptureController(
            listDevices = { devices },
            openSource = { target, hooks ->
                FakeSource(target, hooks, clock = clock).also { sources.add(it) }
            },
            post = main.post,
            onRouteConfirmedHook = {},
            onStoppedHook = {},
            nowNs = { clock.ns },
        )
    }

    private val last: FakeSource get() = sources.last()
    private fun composed() = controller.composed()

    private var sample = 0L

    /** 1kHz 순음을 [blocks] 덩어리 넣는다. */
    private fun feed(src: FakeSource, blocks: Int = 100) {
        repeat(blocks) {
            src.deliverRaw(FloatArray(1024) { (0.2 * sin(2 * PI * 1000 * sample++ / 48000)).toFloat() })
        }
    }

    // ---- 1. 주 스레드가 밀린 동안의 순서 ----

    /**
     * **주 스레드 큐에 밀려 있던 옛 스냅샷이 새 값을 덮지 않는다.**
     *
     * G02 의 고침이 「덩어리를 흘릴 때」가 아니라 **「받아들일 때」** 세대를
     * 보기 때문에 성립한다. 이 시험은 `post` 를 실제로 붙들어, 옛 세션의
     * 스냅샷이 새 세션이 시작된 **뒤에** 큐에서 나오게 만든다.
     */
    @Test
    fun `주 스레드가 밀린 동안 온 옛 스냅샷은 새 값을 덮지 않는다`() {
        build()
        controller.start()
        main.drain() // 시작 시점의 경로 확인은 먼저 흘려보낸다

        main.held = true
        val old = last
        feed(old)
        assertTrue("옛 세션의 스냅샷이 큐에 쌓여야 한다", main.pending > 0)

        // 큐가 밀려 있는 동안 멈추고 새로 시작한다.
        controller.stop()
        controller.start()
        val fresh = last
        feed(fresh)

        main.drain()

        val m = controller.measurement.value
        assertNotNull("새 세션의 값이 있어야 한다", m)
        assertEquals("새 세션의 것이어야 한다", controller.state.value.session, m!!.session)
        assertNotNull("화면에 보이는 값이 비면 안 된다", composed().meter.currentSpl)
    }

    // ---- 2. 분리: 두 이벤트를 **모두** 보낸다 ----

    /**
     * **오류가 먼저, 목록 변경이 나중에 와도 한 번만 전환한다.**
     *
     * 예전 시험은 둘 중 하나만 보냈다. 실제로는 **둘 다** 오고, 중복으로도
     * 온다. 전환이 두 번 일어나면 마이크를 두 번 열고 측정이 한 번 더
     * 끊긴다.
     */
    @Test
    fun `오류와 목록 변경이 둘 다 와도 한 번만 전환한다`() {
        build(deviceList = listOf(usbMic(), builtInMic()))
        controller.update {
            it.copy(
                meterSettings = it.meterSettings.copy(
                    preferredInputKey = usbMic().stableKey,
                    disconnectPolicy = DisconnectPolicy.FallBack,
                ),
            )
        }
        controller.start()
        assertEquals("USB 로 열렸다", usbMic().stableKey, controller.state.value.opened?.deviceKey)
        val opened = sources.size

        val before = devices.toList()
        devices.removeAll { it.kind == MicKind.Usb }

        // ① 읽기 오류가 먼저 온다.
        sources.last().endWith(CaptureEnd.DeviceLost)
        // ② 목록 변경이 뒤따라온다.
        controller.onDeviceListChanged(before, devices)
        // ③ 같은 오류가 한 번 더 온다(실제로 겹쳐 오는 일이 있다).
        sources.first().endWith(CaptureEnd.DeviceLost)

        assertTrue("다시 돌아야 한다", controller.running)
        assertEquals("내장으로 바뀌어야 한다", MicKind.BuiltIn, controller.state.value.opened?.micKind)
        assertEquals("마이크를 한 번만 더 열어야 한다", opened + 1, sources.size)
    }

    /** 순서를 뒤집어도 같다 — 목록 변경이 먼저, 늦은 오류가 나중. */
    @Test
    fun `목록 변경이 먼저 와도 한 번만 전환한다`() {
        build(deviceList = listOf(usbMic(), builtInMic()))
        controller.update {
            it.copy(
                meterSettings = it.meterSettings.copy(
                    preferredInputKey = usbMic().stableKey,
                    disconnectPolicy = DisconnectPolicy.FallBack,
                ),
            )
        }
        controller.start()
        val opened = sources.size
        val usbSource = sources.last()

        val before = devices.toList()
        devices.removeAll { it.kind == MicKind.Usb }
        controller.onDeviceListChanged(before, devices)
        // 늦은 오류가 뒤따라온다.
        usbSource.endWith(CaptureEnd.DeviceLost)
        usbSource.endWith(CaptureEnd.ReadError)

        assertTrue(controller.running)
        assertEquals(MicKind.BuiltIn, controller.state.value.opened?.micKind)
        assertEquals("마이크를 한 번만 더 열어야 한다", opened + 1, sources.size)
    }

    // ---- 3. G01 은 「멈추기」만의 문제가 아니다 ----

    /**
     * **오류로 끝나도 마지막 측정 결과가 남는다.**
     *
     * 검증자가 적은 대로 「일반 stop 뿐 아니라 홈 이동·오류 종료 등 동일
     * 종료 경로」가 모두 같은 자리를 지난다.
     */
    @Test
    fun `오류로 끝나도 마지막 측정 결과가 남는다`() {
        build()
        controller.update {
            it.copy(meterSettings = it.meterSettings.copy(disconnectPolicy = DisconnectPolicy.Pause))
        }
        controller.start()
        feed(last)

        val before = composed()
        assertNotNull("끝나기 전에 값이 있어야 한다", before.meter.currentSpl)

        last.endWith(CaptureEnd.ReadError)

        val after = composed()
        assertTrue("실패로 끝나야 한다", after.measure is MeasureState.Failed)
        assertEquals("현재 SPL 이 남아야 한다", before.meter.currentSpl!!, after.meter.currentSpl!!, 1e-9)
        assertEquals("MAX 가 남아야 한다", before.meter.maxSpl!!, after.meter.maxSpl!!, 1e-9)
        assertNotNull("RTA 가 남아야 한다", after.rta)
        assertEquals("진단이 남아야 한다", before.diagnostics.frames, after.diagnostics.frames)
    }

    /** 기기가 빠져 「멈추기」 정책으로 끝나도 결과가 남는다. */
    @Test
    fun `분리 정책이 멈추기여도 마지막 측정 결과가 남는다`() {
        build(deviceList = listOf(usbMic(), builtInMic()))
        controller.update {
            it.copy(
                meterSettings = it.meterSettings.copy(
                    preferredInputKey = usbMic().stableKey,
                    disconnectPolicy = DisconnectPolicy.Pause,
                ),
            )
        }
        controller.start()
        feed(last)
        val before = composed()
        assertNotNull(before.meter.currentSpl)

        val prev = devices.toList()
        devices.removeAll { it.kind == MicKind.Usb }
        controller.onDeviceListChanged(prev, devices)

        assertFalse("멈춰야 한다", controller.running)
        assertEquals("값이 남아야 한다", before.meter.currentSpl!!, composed().meter.currentSpl!!, 1e-9)
    }

    /** 두 번 멈춰도 굳혀 둔 값이 흔들리지 않는다. */
    @Test
    fun `두 번 멈춰도 결과가 그대로다`() {
        build()
        controller.start()
        feed(last)
        controller.stop()
        val once = composed()
        assertNotNull(once.meter.currentSpl)

        controller.stop()

        assertEquals("두 번째 멈추기가 값을 지우면 안 된다", once.meter.currentSpl!!, composed().meter.currentSpl!!, 1e-9)
        assertEquals(once.diagnostics.frames, composed().diagnostics.frames)
    }

    /** 새로 시작하면 지난 측정의 숫자는 비운다 — 이어 붙이면 안 된다. */
    @Test
    fun `새로 시작하면 지난 숫자를 비운다`() {
        build()
        controller.start()
        feed(last)
        controller.stop()
        assertNotNull("멈춘 뒤에는 남아 있다", composed().meter.currentSpl)

        controller.start()

        val fresh = composed()
        assertEquals("새 측정은 0 프레임에서 시작한다", 0L, fresh.diagnostics.frames)
        assertTrue("돌고 있어야 한다", controller.running)
    }

    // ---- 4. 밖에서 오는 설정이 결과를 지우지 않는가 ----

    /**
     * **같은 설정이 다시 발행돼도 Leq·MAX 가 사라지지 않는다.**
     *
     * DataStore 는 같은 값을 다시 흘릴 수 있다. 그때마다 엔진을 새로
     * 만들면 재던 Leq 와 MAX 가 리셋된다 — 사용자에게는 아무 짓도 안 했는데
     * 숫자가 사라지는 것으로 보인다(검증자 답변 3번의 「같은 값 재발행」).
     */
    @Test
    fun `같은 설정을 다시 발행해도 재던 값이 사라지지 않는다`() {
        build()
        controller.start()
        feed(last)
        val before = composed()
        assertNotNull(before.meter.maxSpl)

        val same = controller.state.value.meterSettings
        controller.onSettingsChanged(same, same)

        assertEquals("MAX 가 그대로여야 한다", before.meter.maxSpl!!, composed().meter.maxSpl!!, 1e-9)
    }
}
