package kr.joa.selahrta.ui

import kr.joa.selahrta.audio.CaptureEnd
import kr.joa.selahrta.audio.InputDeviceInfo
import kr.joa.selahrta.audio.OpenFailure
import kr.joa.selahrta.domain.MeasureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **캡처가 끝나면 서비스도 끝난다.** 모든 길에서(독립 검증 FS01).
 *
 * 검증자가 짚은 것: 서비스를 내리는 자리가 `ViewModel.stop`·`onCleared`
 * 뿐이었다. 그래서 컨트롤러 안에서 끝나는 길 — 입력 없음, open 실패,
 * read 오류, USB 분리의 Pause, 자동 전환 한도 초과 — 에서는 측정이 끝나도
 * 서비스가 「측정 중입니다」 알림을 달고 그대로 남았다.
 *
 * ## 왜 `onStoppedHook` 에 stop 을 더하면 안 되는가
 *
 * 분리 정책이 「내장 마이크로 전환」이면 `stop()` 다음에 곧바로 `start()`
 * 가 온다. 그 사이의 `stop` 에 서비스를 내리면, **백그라운드에서는 다시
 * 띄울 수 없다** — 안드로이드 14부터 `microphone` 형 포그라운드 서비스는
 * 앱이 앞에 있을 때만 띄운다. 예배 중 케이블이 한 번 흔들린 것으로
 * 측정이 통째로 끊긴다. 검증자가 그것을 미리 적어 두었다.
 *
 * 그래서 **[CaptureLifecycle] 이라는 최종 신호**를 따로 둔다. 이 시험은
 * 그 신호가 각 길에서 어떻게 나오는지를 본다 — 가짜 서비스 경계를
 * 컨트롤러에 그대로 꽂는다.
 */
class ServiceLifecycleTest {

    private lateinit var devices: MutableList<InputDeviceInfo>
    private lateinit var sources: MutableList<FakeSource>
    private lateinit var controller: CaptureController
    private lateinit var clock: FakeClock

    /** 가짜 서비스 경계. 들어온 신호를 순서대로 적는다. */
    private val signals = mutableListOf<CaptureLifecycle>()

    /** 실제 [CaptureViewModel.reconcileService] 와 같은 대응. */
    private val serviceUp: Boolean
        get() = signals.lastOrNull() == CaptureLifecycle.Active

    /**
     * @param failToOpen 몇 번째 열기부터 실패시킬지. null 이면 다 열린다.
     * @param endOnStart 열자마자 끊기는 기기를 몇 개까지 줄지.
     */
    private fun build(
        deviceList: MutableList<InputDeviceInfo> = mutableListOf(builtInMic()),
        failFrom: Int = Int.MAX_VALUE,
        endOnStartFirst: Int = 0,
    ) {
        devices = deviceList
        sources = mutableListOf()
        clock = FakeClock()
        signals.clear()
        controller = CaptureController(
            listDevices = { devices },
            openSource = { target, hooks ->
                val n = sources.size
                FakeSource(
                    target,
                    hooks,
                    clock = clock,
                    failToOpen = if (n >= failFrom) OpenFailure.Busy else null,
                    endOnStart = if (n < endOnStartFirst) CaptureEnd.DeviceLost else null,
                ).also { sources.add(it) }
            },
            post = { it() },
            onRouteConfirmedHook = {},
            onStoppedHook = {},
            onLifecycle = { signals.add(it) },
            nowNs = { clock.ns },
        )
        controller.update {
            it.copy(meterSettings = it.meterSettings)
        }
    }

    private fun report(name: String) {
        println("[FS01] $name → $signals · 재는 중 ${controller.running} · 서비스 $serviceUp")
    }

    // ------------------------------------------------------------------
    // 시작조차 못 한 길 — 서비스는 한 번도 떠서는 안 된다
    // ------------------------------------------------------------------

    @Test
    fun `입력이 없으면 서비스를 띄우지 않는다`() {
        build(deviceList = mutableListOf())

        controller.start()

        report("입력 없음")
        assertTrue("실패로 끝나야 한다", controller.baseState.value.measure is MeasureState.Failed)
        assertFalse("서비스가 떠 있으면 안 된다", serviceUp)
        assertFalse("한 번도 Active 가 되면 안 된다", signals.contains(CaptureLifecycle.Active))
    }

    @Test
    fun `마이크를 못 열면 서비스를 내린다`() {
        build(failFrom = 0)

        controller.start()

        report("open 실패")
        assertTrue(controller.baseState.value.measure is MeasureState.Failed)
        assertFalse("서비스가 떠 있으면 안 된다", serviceUp)
        // 아무 신호도 나가지 않는 것이 맞다 — 떠 있지 않았으니 내릴 것도
        // 없다. **단, 밖에서 먼저 띄우면 이 약속이 깨진다** — 그래서
        // [CaptureViewModel.start] 는 서비스를 건드리지 않고
        // [CaptureViewModel.reconcileService] 에게만 맡긴다.
        assertEquals("떠지도 않았으므로 할 말이 없다", emptyList<CaptureLifecycle>(), signals)
    }

    // ------------------------------------------------------------------
    // 재다가 끝난 길
    // ------------------------------------------------------------------

    @Test
    fun `읽기 오류로 끝나면 서비스도 내린다`() {
        build()
        controller.start()
        assertTrue("먼저 떠 있어야 한다", serviceUp)

        sources.last().endWith(CaptureEnd.ReadError)

        report("read 오류")
        assertFalse("멈춰 있어야 한다", controller.running)
        assertFalse("서비스가 남으면 안 된다", serviceUp)
        assertEquals(listOf(CaptureLifecycle.Active, CaptureLifecycle.Finished), signals)
    }

    @Test
    fun `USB 가 빠지면 서비스도 내린다`() {
        build()
        controller.start()
        assertTrue(serviceUp)

        sources.last().endWith(CaptureEnd.DeviceLost)

        report("USB Pause")
        assertFalse(controller.running)
        assertFalse("서비스가 남으면 안 된다", serviceUp)
        assertEquals(listOf(CaptureLifecycle.Active, CaptureLifecycle.Finished), signals)
    }

    @Test
    fun `사람이 멈추면 서비스도 내린다`() {
        build()
        controller.start()

        controller.stop()

        report("수동 stop")
        assertFalse(serviceUp)
        assertEquals(listOf(CaptureLifecycle.Active, CaptureLifecycle.Finished), signals)
    }

    @Test
    fun `두 번 멈춰도 한 번만 알린다`() {
        build()
        controller.start()

        controller.stop()
        controller.stop()
        controller.stop()

        report("stop 세 번")
        assertEquals(
            "같은 말을 되풀이하면 서비스를 쓸데없이 다시 건드린다",
            listOf(CaptureLifecycle.Active, CaptureLifecycle.Finished),
            signals,
        )
    }

    // ------------------------------------------------------------------
    // 기기가 빠진 길 — **내려야 한다**
    //
    // 2026-09-24 에 자동 전환(FallBack)을 없앴다. 예전에는 이 자리에
    // 「갈아타는 동안 서비스를 놓지 않는다」류 시험 넷이 있었는데,
    // 갈아타는 길 자체가 사라져 지웠다 — 이제 기기가 빠지면 언제나
    // 멈추고, 멈추면 서비스도 내린다(위 「USB 가 빠지면」 시험).
    // ------------------------------------------------------------------

    /** 다시 시작하면 다시 떠야 한다. */
    @Test
    fun `멈췄다 다시 시작하면 다시 띄운다`() {
        build()
        controller.start()
        controller.stop()
        controller.start()

        report("start → stop → start")
        assertTrue(serviceUp)
        assertEquals(
            listOf(
                CaptureLifecycle.Active,
                CaptureLifecycle.Finished,
                CaptureLifecycle.Active,
            ),
            signals,
        )
    }

    /** 이미 돌고 있는데 또 시작해도 아무 말도 하지 않는다. */
    @Test
    fun `이미 돌고 있으면 다시 알리지 않는다`() {
        build()
        controller.start()
        controller.start()

        report("start 두 번")
        assertEquals(listOf(CaptureLifecycle.Active), signals)
    }
}
