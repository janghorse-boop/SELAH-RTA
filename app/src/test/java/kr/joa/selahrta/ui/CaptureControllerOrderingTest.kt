package kr.joa.selahrta.ui

import kr.joa.selahrta.audio.CaptureEnd
import kr.joa.selahrta.audio.DisconnectPolicy
import kr.joa.selahrta.audio.InputDeviceInfo
import kr.joa.selahrta.domain.MeasureState
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.TimeWeight
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
        assertEquals("새 세션의 것이어야 한다", controller.baseState.value.session, m!!.session)
        assertNotNull("화면에 보이는 값이 비면 안 된다", composed().meter.currentSpl)
    }

    /**
     * **주 스레드가 밀리면 큐가 얼마나 쌓이는가.**
     *
     * 검증자 답변 2번이 재라고 한 것이다:
     *
     * > 부하 상황에서 큐가 얼마나 쌓이고 회복 때 오래된 같은 세션 값이
     * > 연속 표시되는지는 별도다. main 큐를 0.5/2/5초 지연시킨 뒤 최신
     * > 값으로 수렴하는 시간·큐 길이를 측정하고, 필요하면 같은 세션의
     * > 미처리 snapshot 을 최신 하나로 합친다.
     *
     * **아직 합치지(conflate) 않는다.** 먼저 재고, 재 보고 정한다.
     *
     * **이 시험이 재는 것은 「시간」이 아니라 「데이터 시점」이다**(독립
     * 검증 답변 3번). 가짜 큐를 동기로 비우므로, 벽시계 회복 시간·실제
     * Main Looper 지연·Compose 비용·메모리는 **재지 않는다.** 그리고
     * `StateFlow` 의 conflation 은 값만 합칠 뿐 **이미 post 된 runnable 을
     * 없애지 않는다** — 58개는 실제로 58번 돈다.
     */
    @Test
    fun `주 스레드가 밀려도 회복하면 최신 값으로 수렴한다`() {
        for (heldSeconds in listOf(0.5, 2.0, 5.0)) {
            build()
            controller.start()
            main.drain()

            main.held = true
            // 48kHz·1024프레임이면 한 덩어리가 21.3ms 다.
            val blocks = (heldSeconds * 48_000 / 1024).toInt()
            feed(last, blocks)
            val queued = main.pending

            main.drain()

            val m = controller.measurement.value!!
            val behindBlocks = blocks - (m.diagnostics.frames / 1024).toInt()
            println(
                "[${heldSeconds}초 밀림] 덩어리 $blocks → 큐 $queued · " +
                    "발행 프레임 ${m.diagnostics.frames} (뒤처짐 ${behindBlocks}덩어리)",
            )
            // **마지막 덩어리까지는 아니다.** 화면 갱신이 66ms 에 한 번이라
            // 마지막으로 내보낸 스냅샷이 최신이고, 그 뒤 덩어리는 다음 발행
            // 때 실린다. 처음에는 이것을 몰라 시험이 틀렸었다.
            assertTrue(
                "한 발행 간격(약 4덩어리)보다 더 뒤처지면 안 된다 (뒤처짐 $behindBlocks)",
                behindBlocks in 0..4,
            )
            assertNotNull("화면 값이 비면 안 된다", composed().meter.currentSpl)
            // 66ms 마다 한 번 내므로, 큐 길이는 밀린 시간에 비례한다.
            assertTrue("큐가 쌓여야 이 시험이 뜻을 갖는다 (큐 $queued)", queued > 0)
        }
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
        assertEquals("USB 로 열렸다", usbMic().stableKey, controller.baseState.value.opened?.deviceKey)
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
        assertEquals("내장으로 바뀌어야 한다", MicKind.BuiltIn, controller.baseState.value.opened?.micKind)
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
        assertEquals(MicKind.BuiltIn, controller.baseState.value.opened?.micKind)
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
     *
     * **처음 쓴 시험은 이것을 못 잡았다**(독립 검증 L01). 엔진 교체는
     * `postToCapture` 로 **명령 큐에 들어가서** 다음 덩어리를 처리할 때
     * 실행되는데, 재발행 직후의 스냅샷만 보고 끝냈기 때문이다. 큐에 잘못된
     * 명령이 들어 있어도 아직 돌지 않았으니 통과했다.
     *
     * 그래서 이제 **큰 소리로 MAX 를 만든 뒤 작은 소리 덩어리를 여러 개
     * 더 흘린다.** 엔진이 새로 만들어졌다면 그 작은 소리가 새 MAX 가 되어
     * 값이 내려앉는다.
     */
    @Test
    fun `같은 설정을 다시 발행해도 재던 MAX 가 살아남는다`() {
        build()
        controller.start()

        // 큰 소리로 MAX 를 만든다.
        repeat(60) { last.deliver(amplitude = 0.8f) }
        val loud = composed()
        val maxBefore = loud.meter.maxSpl
        val leqBefore = loud.meter.leqShort
        assertNotNull("MAX 가 있어야 한다", maxBefore)

        // 같은 설정이 다시 온다.
        val same = controller.baseState.value.meterSettings
        controller.onSettingsChanged(same, same)

        // **여기가 핵심이다** — 명령 큐가 실제로 돌도록 덩어리를 더 흘린다.
        // 조용한 소리라, 엔진이 새로 만들어졌다면 MAX 가 이 값으로 내려앉는다.
        repeat(60) { last.deliver(amplitude = 0.02f) }

        val after = composed()
        assertNotNull("여전히 MAX 가 있어야 한다", after.meter.maxSpl)
        assertEquals(
            "조용해졌다고 MAX 가 내려가면 안 된다 (전 $maxBefore / 후 ${after.meter.maxSpl})",
            maxBefore!!,
            after.meter.maxSpl!!,
            1e-9,
        )
        // **Leq 도 이력을 잃지 않아야 한다.** null 검사만으로는 리셋을
        // 잡지 못한다(독립 검증 권고). 조용한 소리만 넣은 쪽과 견준다 —
        // 엔진이 새로 만들어졌다면 두 값이 같아진다.
        assertNotNull("Leq 가 있어야 한다", leqBefore)
        val withHistory = after.meter.leqShort
        assertNotNull(withHistory)

        val fresh = freshControllerFedQuietOnly()
        assertNotNull("견줄 값이 있어야 한다", fresh)
        assertTrue(
            "큰 소리의 이력이 남아 있어야 한다 (이어온 $withHistory / 새로 시작 $fresh)",
            withHistory!! > fresh!! + 3.0,
        )
    }

    /** 처음부터 조용한 소리만 넣은 새 측정의 Leq. 위 시험이 견줄 값이다. */
    private fun freshControllerFedQuietOnly(): Double? {
        val keepDevices = devices
        val keepSources = sources
        val keepController = controller
        val keepMain = main
        val keepClock = clock
        build()
        controller.start()
        repeat(60) { last.deliver(amplitude = 0.02f) }
        val v = controller.composed().meter.leqShort
        controller.stop()
        devices = keepDevices
        sources = keepSources
        controller = keepController
        main = keepMain
        clock = keepClock
        return v
    }

    /**
     * **거꾸로, 정말 바뀐 설정이면 엔진을 다시 만든다.**
     *
     * 위 시험만 있으면 「엔진을 아예 안 만드는」 구현도 통과한다. 짝을
     * 이루는 시험을 함께 둔다 — 시간가중을 바꾸면 MAX 가 새로 잡혀야 한다.
     */
    @Test
    fun `설정이 실제로 바뀌면 엔진을 다시 만든다`() {
        build()
        controller.start()
        repeat(60) { last.deliver(amplitude = 0.8f) }
        val maxBefore = composed().meter.maxSpl!!

        val old = controller.baseState.value.meterSettings
        val changed = old.copy(timeWeight = if (old.timeWeight == TimeWeight.Fast) TimeWeight.Slow else TimeWeight.Fast)
        controller.update { it.copy(meterSettings = changed) }
        controller.onSettingsChanged(old, changed)

        repeat(60) { last.deliver(amplitude = 0.02f) }

        val after = composed().meter.maxSpl
        assertNotNull(after)
        assertTrue(
            "엔진이 새로 만들어졌으면 조용한 값으로 다시 잡혀야 한다 (전 $maxBefore / 후 $after)",
            after!! < maxBefore - 10.0,
        )
    }
}
