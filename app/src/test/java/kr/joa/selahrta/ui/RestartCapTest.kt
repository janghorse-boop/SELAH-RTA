package kr.joa.selahrta.ui

import kr.joa.selahrta.audio.CaptureEnd
import kr.joa.selahrta.audio.DisconnectPolicy
import kr.joa.selahrta.audio.InputDeviceInfo
import kr.joa.selahrta.domain.MeasureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **말썽인 기기에서 끝없이 다시 열지 않는다.**
 *
 * 독립 검증자가 계속 미검증으로 남겨 둔 항목이다 — 「무한 자동 재시작이
 * 없다는 검증은 아직 없다」.
 *
 * 코드를 보니 경로가 있다. 분리 정책이 「내장 마이크로 전환」이면
 * `onCaptureEnded(DeviceLost)` → `applyDisconnectPolicy` → `stop()` +
 * `start()` 로 이어지는데, **새로 연 기기도 곧바로 끊기면** 그 과정이
 * 그대로 되풀이된다. 세는 것이 없어 멈출 자리가 없다.
 *
 * 실제로 일어나는 상황: 오디오 서버가 거듭 죽거나, USB 독이 깜박이거나,
 * 다른 앱이 마이크를 물었다 놨다 하는 때다.
 */
class RestartCapTest {

    private lateinit var devices: MutableList<InputDeviceInfo>
    private lateinit var sources: MutableList<FakeSource>
    private lateinit var controller: CaptureController
    private lateinit var clock: FakeClock

    /**
     * [failFirst] 개까지는 **열자마자 끊기는** 기기를 준다.
     *
     * 그 뒤로는 멀쩡한 기기를 준다 — 상한이 없는 구현에서 시험이
     * 무한히 돌지 않도록 막아 두는 것이지, 상한을 대신하는 것이 아니다.
     */
    private fun build(failFirst: Int) {
        devices = mutableListOf(builtInMic())
        sources = mutableListOf()
        clock = FakeClock()
        controller = CaptureController(
            listDevices = { devices },
            openSource = { target, hooks ->
                val fail = sources.size < failFirst
                FakeSource(
                    target,
                    hooks,
                    clock = clock,
                    endOnStart = if (fail) CaptureEnd.DeviceLost else null,
                ).also { sources.add(it) }
            },
            post = { it() },
            onRouteConfirmedHook = {},
            onStoppedHook = {},
            nowNs = { clock.ns },
        )
        controller.update {
            it.copy(
                meterSettings = it.meterSettings.copy(disconnectPolicy = DisconnectPolicy.FallBack),
            )
        }
    }

    /**
     * **거듭 끊기면 멈추고 알린다.**
     *
     * 상한이 없으면 `sources` 가 `failFirst` 까지 자란다. 상한이 있으면
     * 거기서 멈추고 **사람에게 말한다.**
     */
    @Test
    fun `거듭 끊기면 다시 열기를 멈추고 알린다`() {
        // 상한보다 넉넉히 많이 실패시킨다.
        build(failFirst = 12)

        controller.start()

        val opened = sources.size
        println("[재시작] 연 횟수 $opened · 상한 ${CaptureController.MAX_AUTO_RESTARTS}")

        assertTrue(
            "상한을 넘겨 다시 열면 안 된다 (연 횟수 $opened)",
            opened <= 1 + CaptureController.MAX_AUTO_RESTARTS,
        )
        assertFalse("멈춰 있어야 한다", controller.running)
        assertTrue("실패로 끝나야 한다", controller.baseState.value.measure is MeasureState.Failed)
        assertNotNull("까닭을 적어야 한다", controller.baseState.value.errorKo)
        assertTrue(
            "거듭 끊겼다는 것을 알려야 한다: ${controller.baseState.value.errorKo}",
            controller.baseState.value.errorKo!!.contains("거듭") ||
                controller.baseState.value.errorKo!!.contains("반복"),
        )
    }

    /**
     * **한 번 끊겼다가 잘 열리면 멈추지 않는다.**
     *
     * 상한이 정상적인 전환까지 막으면 안 된다 — 예배 도중 케이블이 한 번
     * 빠지는 것은 흔한 일이다.
     */
    @Test
    fun `한 번 끊겼다가 잘 열리면 계속 잰다`() {
        build(failFirst = 1)

        controller.start()

        println("[재시작] 한 번 실패 뒤 연 횟수 ${sources.size}")
        assertTrue("다시 열려 돌아야 한다", controller.running)
        assertEquals("두 번 열었어야 한다", 2, sources.size)
    }

    /**
     * **잘 재다가 나중에 끊기면 상한이 다시 넉넉해진다.**
     *
     * 세는 것이 세션을 가로질러 쌓이기만 하면, 두 시간 예배에서 드문드문
     * 끊긴 것이 모여 멀쩡한 전환까지 막는다. **잘 재고 있었으면 잊는다.**
     */
    @Test
    fun `잘 재고 있었으면 지난 실패를 잊는다`() {
        build(failFirst = 1)
        controller.start()
        assertTrue(controller.running)

        // 실제로 소리가 들어온다 — 이것이 「잘 되고 있다」는 증거다.
        repeat(20) { sources.last().deliver() }
        assertNotNull("측정값이 올라와야 한다", controller.measurement.value)

        // 이제 다시 끊겨도, 앞선 실패는 세지 않는다.
        sources.last().endWith(CaptureEnd.DeviceLost)

        println("[재시작] 잘 잰 뒤 끊김 — 연 횟수 ${sources.size}, 도는 중 ${controller.running}")
        assertTrue("다시 열려 돌아야 한다", controller.running)
    }
}
