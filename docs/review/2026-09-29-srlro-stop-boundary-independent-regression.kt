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
class SrlStopBoundaryIndependentTest {
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
        val commands = field(vm, "signalCommands") as SerialCommands
        val executor = field(commands, "executor") as ExecutorService
        val interruptions = field(vm, "interruptions") as AudioInterruptions
        val ended = CountDownLatch(1)
        var cleared = false
        init {
            val old = field(vm, "player") as SignalPlayer
            @Suppress("UNCHECKED_CAST") val callback = field(old, "onEnded") as ((Long, String) -> Unit)
            player = SignalPlayer(
                onEnded = { g, r -> callback(g, r); ended.countDown() },
                openSink = { factory().also { sinks.add(it) } }, warn = {},
            )
            main { setField(vm, "player", player) }
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

    private enum class Stop { BUTTON, BACKGROUND, NOISY, LOSS, TRANSIENT, DUCK }

    private fun stop(h: Harness, cause: Stop) = main {
        when (cause) {
            Stop.BUTTON -> h.vm.stopSignal()
            Stop.BACKGROUND -> h.vm.onBackground()
            Stop.NOISY -> (field(h.interruptions, "noisy") as android.content.BroadcastReceiver)
                .onReceive(h.app, android.content.Intent(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            else -> {
                val change = when (cause) {
                    Stop.LOSS -> android.media.AudioManager.AUDIOFOCUS_LOSS
                    Stop.TRANSIENT -> android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    else -> android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                }
                (field(h.interruptions, "listener") as android.media.AudioManager.OnAudioFocusChangeListener)
                    .onAudioFocusChange(change)
            }
        }
    }

    private fun exercise(cause: Stop, granted: Boolean) {
        val h = Harness { Sink() }
        var gate: CountDownLatch? = null
        try {
            focusMode("allow")
            main { h.vm.playSignal(TestSignal.Custom) }; h.drain(); await(h.sinks[0].wrote)
            val stopCounter = field(h.vm, "lastSignalStopIntent") as java.util.concurrent.atomic.AtomicLong
            val before = stopCounter.get()
            gate = heldExecutor(h)
            stop(h, cause)
            assertTrue("$cause must record a stop before its command runs", stopCounter.get() > before)
            assertNull(h.base().playingSignal)
            if (!granted) {
                focusMode("ignore")
                val check = AudioInterruptions(h.app) {}
                assertFalse(check.acquire())
                check.release()
            }
            main {
                if (cause == Stop.BACKGROUND) h.vm.onForeground()
                h.vm.playSignal(TestSignal.Custom)
                // Coalesce another frequency change with the pending restart.
                h.vm.setSignalToneHz(1700.0)
            }
            gate.countDown(); h.drain()
            if (granted) {
                assertEquals("$cause: a stopped generation must not be reused", 2, h.sinks.size)
                assertEquals(TestSignal.Custom, h.player.playing)
                assertTrue(field(h.interruptions, "held") as Boolean)
                main { h.vm.setSignalToneHz(3000.0) }; h.drain()
                assertEquals("$cause: retune becomes legal again after a fresh start", 2, h.sinks.size)
                h.sinks[1].clearSamples()
                eventually { h.sinks[1].snapshot().size >= 4096 }
                val samples = h.sinks[1].snapshot().subList(2048, 4096)
                val n = (1 until samples.size).count { samples[it - 1] <= 0 && samples[it] > 0 }
                assertEquals(3000.0, n * 48000.0 / samples.size, 100.0)
            } else {
                assertEquals(1, h.sinks.size)
                assertNull(h.player.playing)
                assertNull(h.base().playingSignal)
                assertFalse(field(h.interruptions, "held") as Boolean)
                assertNotNull(h.base().signalNoticeKo)
            }
            await(h.sinks[0].released)
            println("SRLRO_MATRIX cause=$cause granted=$granted sinks=${h.sinks.size} playing=${h.player.playing}")
        } finally { gate?.countDown(); h.cleanup(); focusMode("allow") }
    }

    @Test fun buttonDenied() = exercise(Stop.BUTTON, false)
    @Test fun buttonGranted() = exercise(Stop.BUTTON, true)
    @Test fun backgroundDenied() = exercise(Stop.BACKGROUND, false)
    @Test fun backgroundGranted() = exercise(Stop.BACKGROUND, true)
    @Test fun noisyDenied() = exercise(Stop.NOISY, false)
    @Test fun noisyGranted() = exercise(Stop.NOISY, true)
    @Test fun lossDenied() = exercise(Stop.LOSS, false)
    @Test fun lossGranted() = exercise(Stop.LOSS, true)
    @Test fun transientDenied() = exercise(Stop.TRANSIENT, false)
    @Test fun transientGranted() = exercise(Stop.TRANSIENT, true)
    @Test fun duckDenied() = exercise(Stop.DUCK, false)
    @Test fun duckGranted() = exercise(Stop.DUCK, true)

    @Test fun clearPermanentlyPreventsRestart() {
        val h = Harness { Sink() }
        var gate: CountDownLatch? = null
        try {
            focusMode("allow")
            main { h.vm.playSignal(TestSignal.Custom) }; h.drain(); await(h.sinks[0].wrote)
            val stopCounter = field(h.vm, "lastSignalStopIntent") as java.util.concurrent.atomic.AtomicLong
            val intent = field(h.vm, "signalIntent") as java.util.concurrent.atomic.AtomicLong
            val beforeStop = stopCounter.get()
            val beforeIntent = intent.get()
            gate = heldExecutor(h)
            h.clear()
            main { h.vm.playSignal(TestSignal.Custom) }
            gate.countDown()
            assertTrue(h.executor.awaitTermination(5, TimeUnit.SECONDS))
            await(h.sinks[0].released)
            assertEquals(1, h.sinks.size)
            assertNull(h.player.playing)
            assertFalse(field(h.interruptions, "held") as Boolean)
            println("SRLRO_CLEAR stopBefore=$beforeStop stopAfter=${stopCounter.get()} intentBefore=$beforeIntent intentAfter=${intent.get()} closed=${field(h.vm, "signalClosed")} sinks=${h.sinks.size} playing=${h.player.playing}")
        } finally { gate?.countDown(); h.cleanup(); focusMode("allow") }
    }
}
