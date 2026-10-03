package kr.joa.selahrta.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kr.joa.selahrta.audio.AudioInterruptions
import kr.joa.selahrta.audio.SignalChannels
import kr.joa.selahrta.audio.SignalPlayer
import kr.joa.selahrta.audio.SignalSink
import kr.joa.selahrta.audio.TestSignal
import kr.joa.selahrta.data.rta.RtaMeasurementStore
import kr.joa.selahrta.audio.CaptureDiagnostics
import kr.joa.selahrta.dsp.RtaFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * **끝나는 순간과, 무슨 소리로 쟀는가**(독립 검토 PND-01·PND-02).
 *
 * ## 왜 틱 검사만으로는 모자랐나
 *
 * 재는 동안 틱마다 조건을 본다. 그런데 **틱이 먼저 「끝났다」로 바꾸고
 * 나면 `cancel()` 은 아무 일도 하지 않는다** — 그 함수가 「도는 중일
 * 때만」 멈추기 때문이다. 그래서 **완료 경계에서 바뀐 조건**은 알아채고도
 * 막지 못하고 그대로 저장됐다.
 *
 * 여기서는 그 자리를 **일부러 만든다**: 조건을 바꾼 **바로 다음에** 마지막
 * 장을 넣는다. 그 장이 완료를 일으키므로, 바뀐 조건이 「완료 뒤」에 놓인다.
 *
 * ## 이 시험이 못 보는 것
 *
 * 합성 장과 소리 안 나는 sink 를 쓴다. **실제 AudioTrack·마이크·음향을
 * 확인한 것이 아니다.** 보는 것은 저장 계약 하나다.
 */
class RtaCommitBoundaryTest {

    private fun <T> main(block: () -> T): T {
        val task = FutureTask(Callable { block() })
        Handler(Looper.getMainLooper()).post(task)
        return task.get(20, TimeUnit.SECONDS)
    }

    private fun field(obj: Any, name: String): Any? =
        obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obj)

    private fun setField(obj: Any, name: String, value: Any) =
        obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(obj, value)

    private class Sink : SignalSink {
        override fun open(sampleRate: Int, frames: Int, channels: Int) = true
        override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
            Thread.sleep(2)
            return frames
        }
        override fun stop() {}
        override fun release() = true
    }

    private inner class Harness {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as Application
        val owner = ViewModelStore()
        val vm = main {
            ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[
                CaptureViewModel::class.java
            ]
        }
        val controller = field(vm, "controller") as CaptureController
        val root = File(app.cacheDir, "commit-" + UUID.randomUUID())
        val store = RtaMeasurementStore(root)
        val executor = field(field(field(vm, "signals")!!, "commands")!!, "executor") as ExecutorService
        val player: SignalPlayer

        @Suppress("UNCHECKED_CAST")
        val input = field(controller, "_measurement") as MutableStateFlow<MeasurementSnapshot?>

        private var seq = 0L

        init {
            val old = field(field(vm, "signals")!!, "player") as SignalPlayer
            @Suppress("UNCHECKED_CAST")
            val callback = field(old, "onEnded") as (Long, String) -> Unit
            player = SignalPlayer(onEnded = callback, openSink = { Sink() }, warn = {})
            main { setField(field(vm, "signals")!!, "player", player); setField(vm, "rtaStore", store) }
        }

        fun base() = main { controller.baseState.value }

        fun play(signal: TestSignal) {
            main { vm.playSignal(signal) }
            executor.submit {}.get(5, TimeUnit.SECONDS)
            main {}
            assertEquals(signal, player.playing)
        }

        /** 장 하나. **새 번호를 붙인다** — 같은 번호는 안 세어진다. */
        fun frame(db: Double = -60.0) = main {
            input.value = MeasurementSnapshot(
                session = 0,
                diagnostics = CaptureDiagnostics(),
                spl = null,
                rta = RtaFrame(
                    DoubleArray(31) { db },
                    DoubleArray(31) { db },
                    BooleanArray(31) { true },
                    DoubleArray(31),
                    0,
                    ++seq,
                ),
                spectrum = null,
                atMonotonicMs = SystemClock.elapsedRealtime(),
                anyClipping = false,
                feedback = emptyList(),
                feedbackLog = emptyList(),
            )
        }

        fun feed(ms: Long) {
            val begin = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - begin < ms) {
                frame()
                Thread.sleep(80)
            }
            main {}
        }

        fun saved() = RtaMeasurementStore(root).list()

        fun cleanup() {
            main { vm.cancelRtaCapture(); owner.clear() }
            executor.awaitTermination(5, TimeUnit.SECONDS)
            player.stop()
            (field(vm, "interruptions") as AudioInterruptions).release()
            root.deleteRecursively()
        }
    }

    /** 안정화 1.5초 + 측정 10초. 경계 **직전**까지만 먹인다. */
    private val justBeforeDoneMs = 11_300L

    /**
     * **완료 경계에서 조건이 바뀌면 저장하지 않는다**(PND-01).
     *
     * ## 그 틈을 어떻게 만드나
     *
     * 처음에는 「조건을 바꾼 다음에 마지막 장을 넣으면 되겠지」로 썼다.
     * **안 됐다** — 그 장이 완료를 일으키기 전에 틱이 먼저 돌아 정상적으로
     * 취소했다. 고침을 꺼도 시험이 통과해서 알았다.
     *
     * 진짜 틈은 **저장을 승인하기 직전**이다. 차례 측정은 거기서 명령
     * 스레드에 출력을 물어보며 **기다린다.** 그 스레드를 빗장으로 붙들어
     * 두고, 기다리는 동안 채널을 바꾼다 — 재는 일은 이미 끝났으므로
     * 틱은 손쓸 수 없다.
     */
    @Test
    fun 완료_경계에서_바뀐_조건은_저장되지_않는다() {
        val h = Harness()
        val gate = java.util.concurrent.CountDownLatch(1)
        try {
            h.play(TestSignal.Pink)
            main { h.vm.startRtaSequence("경계") }

            h.feed(10_800)
            // 명령 스레드를 붙든다. 저장 직전의 물음이 여기 걸린다.
            val entered = java.util.concurrent.CountDownLatch(1)
            h.executor.execute { entered.countDown(); gate.await(10, TimeUnit.SECONDS) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            // 완료될 때까지 계속 먹인다(끊기면 「장이 모자라다」가 된다).
            h.feed(1_200)

            // 재는 일은 끝났다. 이제 바꾼다 — 틱은 이미 손을 뗐다.
            main { h.vm.setSignalChannels(SignalChannels.Right) }
            gate.countDown()
            Thread.sleep(1_500)
            main {}

            val items = h.saved()
            println("PEND_BOUNDARY saved=${items.size} notice=${h.base().rtaSaveNoticeKo}")
            assertEquals("완료 경계에서 바뀐 조건이 그대로 저장됐다", 0, items.size)
            // **왜 안 저장했는지까지 본다.** 합성 장만 밀어 넣는 시험이라
            // **분석이 실제로 도는 것은 아니다** — 그만둘 수 있는 까닭이
            // 둘이다. 이유를 안 보면 「조건이 바뀌어서」와 「어차피 저장
            // 안 됨」이 구별되지 않는다.
            assertTrue(
                "조건이 바뀌어서 멈췄다고 적혀야 한다: ${h.base().rtaSaveNoticeKo}",
                h.base().rtaSaveNoticeKo?.contains("출력 채널") == true,
            )
        } finally {
            gate.countDown()
            h.cleanup()
        }
    }

    /**
     * **재는 도중 주파수를 바꾸면 그만둔다**(PND-02).
     *
     * 「주파수 지정」은 **retune 으로 바뀌므로 재생 세대가 그대로다.**
     * 세대만 보고 있으면 1kHz 와 2kHz 가 **한 곡선에 섞인다.**
     */
    @Test
    fun 재는_도중_순음_주파수를_바꾸면_저장하지_않는다() {
        val h = Harness()
        try {
            main { h.vm.setSignalToneHz(1_000.0) }
            h.play(TestSignal.Custom)
            main { h.vm.startRtaCapture("주파수", null) }

            h.feed(6_000)
            main { h.vm.setSignalToneHz(2_000.0) }
            h.feed(6_000)

            val items = h.saved()
            println("PEND_FREQUENCY saved=${items.size} notice=${h.base().rtaSaveNoticeKo}")
            assertEquals("주파수가 바뀌었는데 한 곡선으로 저장됐다", 0, items.size)
            assertTrue(
                "신호가 바뀌어서 멈췄다고 적혀야 한다: ${h.base().rtaSaveNoticeKo}",
                h.base().rtaSaveNoticeKo?.contains("신호 주파수") == true,
            )
        } finally {
            h.cleanup()
        }
    }

    /**
     * **대조군 — 아무것도 안 바꾸면 「다른 이유」로 멈춘다.**
     *
     * 위 둘이 「저장 안 됨」만 보면 **아무것도 저장 안 하는 코드로도
     * 통과**한다. 그래서 조건을 건드리지 않은 판을 함께 돌리고,
     * **그때는 이유가 다르다**는 것을 본다.
     *
     * ## 왜 여기서는 저장까지 안 가나
     *
     * 이 시험은 **합성 장을 밀어 넣을 뿐 분석을 돌리지 않는다.** 평균을
     * 분석 스레드에서 모으도록 바꾸면서(PND-03 단계 B) **분석이 안 돌면
     * 저장할 값도 없다.** 예전에는 화면으로 나온 값을 다시 평균했기 때문에
     * 이 시험도 저장까지 갔다.
     *
     * **실제로 재서 저장되는 대조군은 `RtaCoverageRecordedTest`** 가 맡는다 —
     * 거기는 마이크를 열고 분석을 실제로 돌린다.
     */
    @Test
    fun 조건을_안_바꾸면_다른_이유로_멈춘다() {
        val h = Harness()
        try {
            main { h.vm.setSignalToneHz(1_000.0) }
            h.play(TestSignal.Custom)
            main { h.vm.startRtaCapture("정상", null) }
            h.feed(12_300)

            val notice = h.base().rtaSaveNoticeKo
            println("PEND_CONTROL saved=${h.saved().size} notice=$notice")
            assertEquals(0, h.saved().size)
            assertTrue(
                "조건이 바뀌었다는 이유가 나오면 위 시험들이 헛돈 것이다: $notice",
                notice?.contains("바뀌어") != true,
            )
        } finally {
            h.cleanup()
        }
    }
}
