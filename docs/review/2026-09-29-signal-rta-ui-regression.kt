package kr.joa.selahrta.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import kr.joa.selahrta.MainActivity
import kr.joa.selahrta.audio.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Review-only reflection injects a silent sink into the actual Activity/VM path.
 * No microphone, AudioTrack or physical sound is opened by this test.
 */
class SignalFailureIndependentProbeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private class HeldSink : SignalSink {
        val wake = CountDownLatch(1)
        val released = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val stops = AtomicInteger()
        @Volatile var error = false
        override fun open(sampleRate: Int, frames: Int, channels: Int) = true
        override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
            entered.countDown()
            wake.await(60, TimeUnit.SECONDS)
            return if (error) SignalSink.ERROR_DEAD_OBJECT else 0
        }
        override fun stop() { stops.incrementAndGet(); wake.countDown() }
        override fun release(): Boolean { released.countDown(); return true }
    }

    private fun withPlayer(body: (CaptureViewModel, HeldSink) -> Unit) {
        lateinit var vm: CaptureViewModel
        lateinit var original: SignalPlayer
        val sink = HeldSink()
        val field = CaptureViewModel::class.java.getDeclaredField("player").apply { isAccessible = true }
        compose.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[CaptureViewModel::class.java]
            original = field.get(vm) as SignalPlayer
            val callback = SignalPlayer::class.java.getDeclaredField("onEnded").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val ended = callback.get(original) as ((Long, String) -> Unit)?
            field.set(vm, SignalPlayer(onEnded = ended, openSink = { sink }, warn = {}))
        }
        compose.waitUntil(10000) { vm.state.value.settingsLoaded }
        try {
            compose.activityRule.scenario.onActivity { vm.playSignal(TestSignal.Pink) }
            assertTrue(sink.entered.await(5, TimeUnit.SECONDS))
            // Await the real splash and actual SelahApp wiring, not an isolated bottom bar.
            compose.waitUntil(10000) {
                compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("테스트 신호 멈추기"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("도구").performClick()
            compose.onNodeWithContentDescription("RTA에서 재기").performScrollTo().performClick()
            compose.onNodeWithContentDescription("테스트 신호 멈추기").assertIsDisplayed()
            assertEquals(TestSignal.Pink, vm.state.value.playingSignal)
            assertEquals(0, sink.stops.get())
            assertEquals(1L, sink.released.count)
            body(vm, sink)
        } finally {
            sink.wake.countDown()
            compose.activityRule.scenario.onActivity { vm.stopSignal(); field.set(vm, original) }
        }
    }

    @Test fun miniBarStopsTheActualViewModelPlayback() = withPlayer { vm, sink ->
        compose.onNodeWithContentDescription("테스트 신호 멈추기").performClick()
        compose.waitUntil(5000) { vm.state.value.playingSignal == null }
        assertTrue(sink.released.await(5, TimeUnit.SECONDS))
        assertTrue("The held playback must receive stop", sink.stops.get() > 0)
        compose.onNodeWithContentDescription("테스트 신호 멈추기").assertDoesNotExist()
    }

    @Test fun playbackErrorIsVisibleOutsideTheSignalScreen() = withPlayer { vm, sink ->
        sink.error = true
        sink.wake.countDown()
        compose.waitUntil(5000) { vm.state.value.signalNoticeKo != null }
        assertNull(vm.state.value.playingSignal)
        assertTrue(sink.released.await(5, TimeUnit.SECONDS))
        val reason = vm.state.value.signalNoticeKo!!
        // Current defect: the VM has the reason but the actual main screen hides it.
        compose.onNodeWithText(reason).assertIsDisplayed()
    }
}
