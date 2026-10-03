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
class PendingRmsVmTest {
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
        val executor = field(field(field(vm, "signals")!!, "commands")!!, "executor") as ExecutorService
        val player: SignalPlayer
        @Suppress("UNCHECKED_CAST")
        val input = field(c, "_measurement") as MutableStateFlow<MeasurementSnapshot?>
        init {
            val old = field(field(vm, "signals")!!, "player") as SignalPlayer
            @Suppress("UNCHECKED_CAST") val callback = field(old, "onEnded") as (Long, String) -> Unit
            player = SignalPlayer(onEnded = callback, openSink = { Sink() }, warn = {})
            main { setField(field(vm, "signals")!!, "player", player); setField(vm, "rtaStore", store) }
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

    @Test fun contextChangeAtCompletionMustPreventSave() {
        val h = Harness()
        try {
            h.play()
            main { h.vm.startRtaCapture("boundary", null) }
            h.feed(11200)
            main {
                val r = field(h.vm, "rtaRun") as RtaCaptureRun
                assertEquals(RtaCapturePhase.Measuring, r.phase)
                // Hold only the UI scheduler across the real completion deadline.
                // No production fields, frame counts or clock values are changed.
                val start = field(r, "measureStartedAtMs") as Long
                val remaining = start + 10000L - SystemClock.elapsedRealtime()
                assertTrue("Boundary setup needs <600ms remaining: $remaining", remaining in 0..600)
                Thread.sleep(remaining + 15)
                h.vm.setSignalChannels(SignalChannels.Right)
            }
            Thread.sleep(500)
            main {}
            val saved = RtaMeasurementStore(h.root).list()
            println("PEND_BOUNDARY saved=${saved.size} notice=${h.base().rtaSaveNoticeKo}")
            assertTrue("Context invalidation must win over Done", saved.isEmpty())
        } finally { h.cleanup() }
    }
    @Test fun customFrequencyChangeMustInvalidateWindow() {
        val h = Harness()
        var changed = false
        try {
            main { h.vm.playSignal(TestSignal.Custom); h.vm.setSignalToneHz(1000.0) }
            h.executor.submit {}.get(5, TimeUnit.SECONDS)
            main { h.vm.startRtaSequence("frequency") }
            h.feed(12300) { t ->
                if (t >= 6000 && !changed) {
                    main { h.vm.setSignalToneHz(2000.0) }; changed = true
                }
                if (changed) -20.0 else -60.0
            }
            val saved = RtaMeasurementStore(h.root).list()
            println("PEND_FREQUENCY saved=${saved.size} signal=${saved.firstOrNull()?.signal} mean=${saved.firstOrNull()?.bandsSpl?.get(0)}")
            assertTrue("Custom tone retune must invalidate the averaging window", saved.isEmpty())
        } finally { h.cleanup() }
    }
}

