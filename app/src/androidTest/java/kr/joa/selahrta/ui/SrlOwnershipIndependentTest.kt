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
class SrlOwnershipIndependentTest {
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
        private val rightSamples = ArrayList<Float>()
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
                for (i in 0 until frames step 2) if (samples.size < 8192) { samples.add(buf[offset + i]); rightSamples.add(buf[offset + i + 1]) }
            }
            wrote.countDown()
            Thread.sleep(1)
            return frames
        }
        fun snapshot(): List<Float> = synchronized(samples) { samples.toList() }
        fun rightSnapshot(): List<Float> = synchronized(samples) { rightSamples.toList() }
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
        h.executor.execute { entered.countDown(); release.await() }
        await(entered)
        return release
    }

    private fun focusMode(mode: String) {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("appops set kr.joa.selahrta TAKE_AUDIO_FOCUS $mode")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
    }

    @Test fun focusLossThenSameToneRetryMustAcquireFocusAgain() {
        val h = Harness { Sink() }
        var gate: CountDownLatch? = null
        try {
            focusMode("allow")
            main { h.vm.playSignal(TestSignal.Custom) }; h.drain(); await(h.sinks[0].wrote)
            gate = heldExecutor(h)
            // Deliver the actual registered listener's LOSS callback on main.
            val listener = field(h.interruptions, "listener") as android.media.AudioManager.OnAudioFocusChangeListener
            main { listener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS) }
            assertNull(h.base().playingSignal)
            focusMode("ignore")
            val check = AudioInterruptions(h.app) {}
            assertFalse("The retry really has no focus", check.acquire())
            check.release()
            main { h.vm.playSignal(TestSignal.Custom) }
            gate.countDown(); h.drain()
            println("SRLRO_FOCUS_RETRY playing=${h.player.playing} ui=${h.base().playingSignal} opened=${h.sinks.size} held=${field(h.interruptions, "held")}")
            assertNull("Coalescing a stop must not retune the old playback without reacquiring focus", h.player.playing)
            assertFalse(field(h.interruptions, "held") as Boolean)
            assertNotNull(h.base().signalNoticeKo)
        } finally { gate?.countDown(); h.cleanup(); focusMode("allow") }
    }

    @Test fun closeReplacesQueuedAndLateEndCleanup() {
        val h = Harness { Sink() }
        var gate: CountDownLatch? = null
        try {
            main { h.vm.playSignal(TestSignal.Custom) }; h.drain(); await(h.sinks[0].wrote)
            gate = heldExecutor(h)
            h.sinks[0].error = true
            await(h.ended) // postAlways is now queued behind the held executor.
            h.clear()     // close must replace its resource cleanup.
            @Suppress("UNCHECKED_CAST") val callback = field(h.player, "onEnded") as ((Long, String) -> Unit)
            callback(1L, "late after close") // Must not reject/crash after close.
            gate.countDown()
            assertTrue(h.executor.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(h.sinks[0].released.await(500, TimeUnit.MILLISECONDS))
            assertFalse(field(h.interruptions, "held") as Boolean)
            assertNull(h.player.playing)
        } finally { gate?.countDown(); h.cleanup() }
    }

    @Test fun channelThenFrequencyUsesOneLatestCompleteRequest() {
        val h = Harness { Sink() }
        var gate: CountDownLatch? = null
        try {
            main { h.vm.playSignal(TestSignal.Custom) }; h.drain(); await(h.sinks[0].wrote)
            gate = heldExecutor(h)
            main { h.vm.setSignalChannels(SignalChannels.Right); h.vm.setSignalToneHz(3000.0) }
            gate.countDown(); h.drain()
            assertEquals(2, h.sinks.size)
            eventually { h.sinks[1].snapshot().size >= 4096 }
            assertTrue(h.sinks[1].snapshot().all { it == 0f })
            val r = h.sinks[1].rightSnapshot().subList(2048, 4096)
            val crossings = (1 until r.size).count { r[it - 1] <= 0 && r[it] > 0 }
            assertEquals(3000.0, crossings * 48000.0 / r.size, 100.0)
        } finally { gate?.countDown(); h.cleanup() }
    }

    @Test fun oldEndAfterReplacementDoesNotReleaseNewFocus() {
        val h = Harness { Sink() }
        try {
            main { h.vm.playSignal(TestSignal.Custom) }; h.drain()
            main { h.vm.playSignal(TestSignal.Pink) }; h.drain()
            @Suppress("UNCHECKED_CAST") val callback = field(h.player, "onEnded") as ((Long, String) -> Unit)
            callback(1L, "late old generation")
            h.drain()
            assertEquals(TestSignal.Pink, h.player.playing)
            assertEquals(TestSignal.Pink, h.base().playingSignal)
            assertNull(h.base().signalNoticeKo)
            assertTrue(field(h.interruptions, "held") as Boolean)
        } finally { h.cleanup() }
    }
}
