package kr.joa.selahrta.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kr.joa.selahrta.audio.*
import kr.joa.selahrta.data.rta.*
import kr.joa.selahrta.dsp.RtaFrame
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.*

/** Dedicated silent emulator. Actual VM/coroutines/store; synthetic input snapshots. */
class RtaStoreVmIndependentTest {
    private fun <T> main(block: () -> T): T {
        val task = FutureTask(Callable { block() })
        Handler(Looper.getMainLooper()).post(task)
        return task.get(10, TimeUnit.SECONDS)
    }
    private fun field(obj: Any, name: String): Any? =
        obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obj)
    private fun setField(obj: Any, name: String, value: Any) =
        obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(obj, value)
    private class Sink : SignalSink {
        override fun open(sampleRate: Int, frames: Int, channels: Int) = true
        override fun write(buf: FloatArray, offset: Int, frames: Int): Int { Thread.sleep(2); return frames }
        override fun stop() {}
        override fun release() = true
    }
    private inner class Harness {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val owner = ViewModelStore()
        val vm = main { ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[CaptureViewModel::class.java] }
        val c = field(vm, "controller") as CaptureController
        val root = File(app.cacheDir, "rms-probe-" + UUID.randomUUID())
        val store = RtaMeasurementStore(root)
        val executor = field(field(vm, "signalCommands")!!, "executor") as ExecutorService
        val player: SignalPlayer
        @Suppress("UNCHECKED_CAST")
        val input = field(c, "_measurement") as MutableStateFlow<MeasurementSnapshot?>
        init {
            val old = field(vm, "player") as SignalPlayer
            @Suppress("UNCHECKED_CAST") val callback = field(old, "onEnded") as (Long, String) -> Unit
            player = SignalPlayer(onEnded = callback, openSink = { Sink() }, warn = {})
            main { setField(vm, "player", player); setField(vm, "rtaStore", store) }
        }
        fun base() = main { c.baseState.value }
        fun play() {
            main { vm.playSignal(TestSignal.Pink) }
            executor.submit {}.get(5, TimeUnit.SECONDS)
            main {}
            assertEquals(TestSignal.Pink, player.playing)
        }
        // Each synthetic snapshot carries a fresh analysis frame number, the way a
        // real engine does when a new FFT actually ran.
        var frameSeq = 0L
        fun frame(db: Double = -60.0, session: Long = 0) = main {
            input.value = MeasurementSnapshot(
                session = session, diagnostics = CaptureDiagnostics(), spl = null,
                rta = RtaFrame(DoubleArray(31) { db }, DoubleArray(31) { db }, BooleanArray(31) { true }, DoubleArray(31), 0, ++frameSeq),
                spectrum = null, atMonotonicMs = SystemClock.elapsedRealtime(), anyClipping = false,
                feedback = emptyList(), feedbackLog = emptyList(),
            )
        }
        fun feed(ms: Long, action: (Long) -> Double = { -60.0 }) {
            val begin = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - begin < ms) {
                val elapsed = SystemClock.elapsedRealtime() - begin
                frame(action(elapsed))
                Thread.sleep(80)
            }
            main {}
        }
        fun cleanup() {
            main { vm.cancelRtaCapture(); owner.clear() }
            executor.awaitTermination(5, TimeUnit.SECONDS)
            player.stop()
            (field(vm, "interruptions") as AudioInterruptions).release()
            root.deleteRecursively()
        }
    }

    /**
     * 검토자가 대조군으로 둠 시험이다. **전제가 바뀌어 고쳐 적는다.**
     *
     * 원래는 「합성 장을 밀어 넣으면 그 값이 그대로 저장된다」를
     * 보았다. 평균을 **분석 스레드에서 모으도록** 바꾸면서
     * (독립 검토 PND-03 단계 B) 그 전제가 깨졌다 — 이 시험은
     * **분석을 돌리지 않으므로 저장할 값이 없다.**
     *
     * 화면으로 나온 값을 다시 평균하던 예전에는 저장까지 갔다. 지금은
     * **안 가는 것이 맞는 동작**이다 — 분석이 안 돌았는데 저장하면
     * 그 값이 무엇인지 말할 수 없다.
     *
     * **실제로 재서 저장되는 대조군은 `RtaCoverageRecordedTest`** 가 맡는다.
     */
    @Test fun stableCaptureControlPersistsViaFreshStore() {
        val h = Harness()
        try {
            h.play()
            main { h.vm.startRtaCapture("stable", null) }
            h.feed(12300)
            val items = RtaMeasurementStore(h.root).list()
            val notice = h.base().rtaSaveNoticeKo
            println("RMS_VM_CONTROL saved=${items.size} notice=$notice")
            assertEquals(0, items.size)
            // 조건이 바뀌어서가 아니라 **분석이 안 돌아서** 멈춰야 한다.
            assertTrue(notice?.contains("바뀌어") != true)
            assertNull(h.base().rtaCapture)
        } finally { h.cleanup() }
    }

    @Test fun cancellationMustClearVisibleCaptureState() {
        val h = Harness()
        try {
            main { h.vm.startRtaCapture("cancel", null) }
            assertNotNull(h.base().rtaCapture)
            main { h.vm.cancelRtaCapture() }
            Thread.sleep(300)
            println("RMS_CANCEL jobActive=${(field(h.vm, "rtaJob") as Job).isActive} ui=${h.base().rtaCapture} phase=${(field(h.vm, "rtaRun") as? RtaCaptureRun)?.phase}")
            assertNull("Cancelled coroutine must clear the UI; otherwise only Cancel remains visible", h.base().rtaCapture)
        } finally { h.cleanup() }
    }

    @Test fun changedOutputChannelMustNotSaveMixedCurveAsFinalChannel() {
        val h = Harness()
        var changed = false
        try {
            h.play()
            main { h.vm.setSignalChannels(SignalChannels.Left); h.vm.startRtaCapture("left", null) }
            h.feed(12300) { t ->
                if (t >= 6000 && !changed) { main { h.vm.setSignalChannels(SignalChannels.Right) }; changed = true }
                if (changed) -20.0 else -60.0
            }
            val items = RtaMeasurementStore(h.root).list()
            println("RMS_CHANNEL saved=${items.size} label=${items.firstOrNull()?.channel} mean=${items.firstOrNull()?.bandsSpl?.get(0)} name=${items.firstOrNull()?.nameKo}")
            assertTrue("Channel change must cancel or restart the 10-second window", items.isEmpty())
        } finally { h.cleanup() }
    }

    @Test fun sequenceMustStopWhenAppGoesToBackground() {
        val h = Harness()
        try {
            h.play()
            main { h.vm.startRtaSequence("room") }
            h.feed(2300)
            main { h.vm.onBackground() }
            h.feed(10200)
            val items = RtaMeasurementStore(h.root).list()
            println("RMS_BACKGROUND saved=${items.size} storedSignal=${items.firstOrNull()?.signal} output=${h.player.playing} sequence=${h.base().rtaSequenceKo}")
            assertTrue("Signal loss must abort this guided output measurement", items.isEmpty())
            assertNull(h.base().rtaSequenceKo)
        } finally { h.cleanup() }
    }

    @Test fun sequenceMustWaitForOutputAcknowledgement() {
        val h = Harness()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            h.executor.execute { entered.countDown(); release.await() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            main { h.vm.playSignal(TestSignal.Pink); h.vm.startRtaSequence("pending") }
            h.feed(2300)
            println("RMS_ACK player=${h.player.playing} uiSignal=${h.base().playingSignal} capture=${h.base().rtaCapture}")
            assertNull(h.player.playing)
            assertTrue("Measuring cannot begin before the selected output is actually started", h.base().rtaCapture?.settling != false)
        } finally { release.countDown(); h.cleanup() }
    }
}
