package kr.joa.selahrta.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kr.joa.selahrta.audio.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.*
import kotlin.math.abs

/** Real ViewModel + original callbacks, silent sink. Barriers control command ordering. */
class SrlCommandIndependentTest {
    private fun <T> main(block: () -> T): T {
        val task = FutureTask(Callable { block() })
        Handler(Looper.getMainLooper()).post(task)
        return task.get(10, TimeUnit.SECONDS)
    }
    private fun field(obj: Any, name: String): Any? =
        obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obj)
    private fun setField(obj: Any, name: String, value: Any) {
        obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(obj, value)
    }
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(5, TimeUnit.SECONDS))
    private fun eventually(test: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!test() && System.nanoTime() < until) Thread.sleep(5)
        assertTrue(test())
    }
    private class Sink(val holdOpen: Boolean = false, val openOk: Boolean = true) : SignalSink {
        val opening = CountDownLatch(1)
        val allowOpen = CountDownLatch(if (holdOpen) 1 else 0)
        val wrote = CountDownLatch(1)
        val released = CountDownLatch(1)
        @Volatile var error = false
        private val samples = ArrayList<Float>()
        override fun open(sampleRate: Int, frames: Int, channels: Int): Boolean {
            opening.countDown()
            // Native device opening is not required to cooperate with Thread.interrupt().
            while (true) {
                try { if (allowOpen.await(5, TimeUnit.SECONDS)) break else error("open gate timeout") }
                catch (_: InterruptedException) { }
            }
            return openOk
        }
        override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
            if (error) return SignalSink.ERROR_DEAD_OBJECT
            synchronized(samples) {
                for (i in 0 until frames step 2) if (samples.size < 8192) samples.add(buf[offset + i])
            }
            wrote.countDown()
            Thread.sleep(1)
            return frames
        }
        fun snapshot(): List<Float> = synchronized(samples) { samples.toList() }
        fun clearSamples() = synchronized(samples) { samples.clear() }
        override fun stop() { allowOpen.countDown() }
        override fun release(): Boolean { released.countDown(); return true }
    }
    private inner class Harness(factory: () -> Sink) {
        val store = ViewModelStore()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val vm = main { ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CaptureViewModel::class.java] }
        val sinks = Collections.synchronizedList(ArrayList<Sink>())
        val player: SignalPlayer
        val commands = field(field(vm, "signals")!!, "commands") as SerialCommands
        val executor = field(commands, "executor") as ExecutorService
        val interruptions = field(vm, "interruptions") as AudioInterruptions
        val ended = CountDownLatch(1)
        var cleared = false
        init {
            val old = field(field(vm, "signals")!!, "player") as SignalPlayer
            @Suppress("UNCHECKED_CAST") val callback = field(old, "onEnded") as ((Long, String) -> Unit)
            player = SignalPlayer(
                onEnded = { g, r -> callback(g, r); ended.countDown() },
                openSink = { factory().also { sinks.add(it) } }, warn = {},
            )
            main { setField(field(vm, "signals")!!, "player", player) }
        }
        fun base(): CaptureUiState = main { (field(vm, "controller") as CaptureController).baseState.value }
        fun drain() {
            executor.submit {}.get(5, TimeUnit.SECONDS)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            main {}
        }
        fun clear() { main { store.clear(); cleared = true } }
        fun cleanup() {
            synchronized(sinks) { sinks.forEach { it.allowOpen.countDown() } }
            if (!cleared) clear()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            // Test-only cleanup if the production teardown left a writer alive.
            player.stop()
            interruptions.release()
        }
    }
    private fun heldExecutor(h: Harness): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        h.executor.execute { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        await(entered)
        return release
    }

    @Test fun stopDuringOpenMustNotRestorePlayingUi() {
        val sink = Sink(holdOpen = true)
        val h = Harness { sink }
        try {
            main { h.vm.playSignal(TestSignal.Pink) }; await(sink.opening)
            main { h.vm.stopSignal() }
            sink.allowOpen.countDown(); await(sink.released); h.drain()
            println("SRLR_STOP ui=${h.base().playingSignal} player=${h.player.playing}")
            assertNull("A stale start completion must not undo a newer stop", h.base().playingSignal)
        } finally { h.cleanup() }
    }

    @Test fun clearedViewModelMustReleaseDelayedOpen() {
        val sink = Sink(holdOpen = true)
        val h = Harness { sink }
        try {
            main { h.vm.playSignal(TestSignal.Pink) }; await(sink.opening)
            h.clear()
            sink.allowOpen.countDown()
            assertTrue(h.executor.awaitTermination(5, TimeUnit.SECONDS))
            val closed = sink.released.await(500, TimeUnit.MILLISECONDS)
            println("SRLR_CLEAR released=$closed player=${h.player.playing} wrote=${sink.wrote.count == 0L}")
            assertTrue("The owner is cleared but the newly opened playback leaked", closed)
            assertNull(h.player.playing)
        } finally { h.cleanup() }
    }

    @Test fun retuneMustAlsoUpdateAPendingRestart() {
        val h = Harness { Sink() }
        var gate: CountDownLatch? = null
        try {
            main { h.vm.playSignal(TestSignal.Custom) }; h.drain(); await(h.sinks[0].wrote)
            gate = heldExecutor(h)
            main { h.vm.setSignalLevel(0.1); h.vm.setSignalToneHz(3000.0) }
            gate.countDown(); h.drain()
            assertEquals(2, h.sinks.size)
            eventually { h.sinks[1].snapshot().size >= 4096 }
            val samples = h.sinks[1].snapshot().subList(2048, 4096)
            val crossings = (1 until samples.size).count { samples[it - 1] <= 0 && samples[it] > 0 }
            val hz = crossings * 48000.0 / samples.size
            println("SRLR_RETUNE ui=${h.base().signalToneHz} outputHz=$hz")
            assertEquals(3000.0, h.base().signalToneHz, 0.0)
            assertEquals("Queued restart must not restore 1 kHz", 3000.0, hz, 100.0)
        } finally { gate?.countDown(); h.cleanup() }
    }

    @Test fun oldEndedCleanupMustNotCancelNewStart() {
        val h = Harness { Sink() }
        var gate: CountDownLatch? = null
        try {
            main { h.vm.playSignal(TestSignal.Custom) }; h.drain(); await(h.sinks[0].wrote)
            gate = heldExecutor(h)
            main { h.vm.playSignal(TestSignal.Pink) }
            h.sinks[0].error = true
            await(h.ended)
            // Ensure the old ending was delivered before releasing the queued start.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            main {}
            gate.countDown(); h.drain()
            println("SRLR_END queuedPink=true opened=${h.sinks.size} ui=${h.base().playingSignal}")
            assertEquals("Cleanup must not supersede a user's start", 2, h.sinks.size)
            assertEquals(TestSignal.Pink, h.base().playingSignal)
        } finally { gate?.countDown(); h.cleanup() }
    }

    @Test fun frequencyOnlyRetuneKeepsTheTrackAndUsesLatestHz() {
        val h = Harness { Sink() }
        try {
            main { h.vm.playSignal(TestSignal.Custom) }; h.drain(); await(h.sinks[0].wrote)
            main { h.vm.setSignalToneHz(3000.0) }; h.drain()
            assertEquals("An ordinary retune should keep the existing track", 1, h.sinks.size)
            h.sinks[0].clearSamples()
            eventually { h.sinks[0].snapshot().size >= 4096 }
            val samples = h.sinks[0].snapshot().subList(2048, 4096)
            val crossings = (1 until samples.size).count { samples[it - 1] <= 0 && samples[it] > 0 }
            assertEquals(3000.0, crossings * 48000.0 / samples.size, 100.0)
            assertEquals(TestSignal.Custom, h.player.playing)
        } finally { h.cleanup() }
    }

    @Test fun failedOpenMustReleaseFocusAndReceiver() {
        val h = Harness { Sink(openOk = false) }
        try {
            main { h.vm.playSignal(TestSignal.Pink) }; h.drain()
            assertNull(h.player.playing)
            val held = field(h.interruptions, "held") as Boolean
            println("SRLR_OPEN_FAIL player=${h.player.playing} interruptionHeld=$held")
            assertFalse("Failed start must unwind focus and receiver registration", held)
        } finally { h.cleanup() }
    }

    /** Run separately with: adb shell appops set kr.joa.selahrta TAKE_AUDIO_FOCUS ignore. */
    @Test fun deniedFocusMustNotOpenOutput() {
        val h = Harness { Sink() }
        try {
            val checkFocus = AudioInterruptions(h.app) {}
            val granted = checkFocus.acquire()
            checkFocus.release()
            assumeTrue("Requires the emulator-only deny-focus app-op", !granted)
            main { h.vm.playSignal(TestSignal.Pink) }; h.drain()
            println("SRLR_FOCUS denied=true opened=${h.sinks.size} ui=${h.base().playingSignal}")
            assertEquals("Do not claim normal playback when focus was denied", 0, h.sinks.size)
            assertFalse(field(h.interruptions, "held") as Boolean)
            assertNotNull(h.base().signalNoticeKo)
        } finally { h.cleanup() }
    }
}
