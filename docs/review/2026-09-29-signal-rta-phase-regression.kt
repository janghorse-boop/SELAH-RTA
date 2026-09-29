package kr.joa.selahrta.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** Independent regression: preserve oscillator phase after discarded unsent frames. */
class SignalRtaIndependentProbeTest {
    @Test fun partialWriteThenFadePreservesAcceptedPhase() = checkPhase(128)
    @Test fun unacceptedBlockThenFadePreservesAcceptedPhase() = checkPhase(0)

    private fun checkPhase(acceptedThirdFrames: Int) {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val released = CountDownLatch(1)
        val samples = ArrayList<Float>()
        var call = 0
        val sink = object : SignalSink {
            override fun open(sampleRate: Int, frames: Int, channels: Int) = true
            override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
                val n = if (++call == 3) {
                    entered.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                    acceptedThirdFrames * 2
                } else frames
                for (i in 0 until n step 2) samples.add(buf[offset + i])
                return n
            }
            override fun stop() { resume.countDown() }
            override fun release(): Boolean { released.countDown(); return true }
        }
        val p = SignalPlayer(openSink = { sink }, warn = {})
        p.start(SignalRequest(TestSignal.Sine1k, MAX_AMPLITUDE))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        // start/stop remain on the same control thread. Only the sink is unblocked elsewhere.
        val helper = Thread {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (p.playing != null && System.nanoTime() < deadline) Thread.yield()
            resume.countDown()
        }.apply { start() }
        try {
            p.stop()
            assertTrue(released.await(5, TimeUnit.SECONDS))
            helper.join(5000)
            val prefix = 2048 + acceptedThirdFrames
            assertEquals("30 ms tail must be accepted", prefix + 1440, samples.size)
            val expected = FloatArray(1440) { i ->
                (MAX_AMPLITUDE * (1 - (i + 1) / 1440.0) *
                    sin(2 * PI * 1000 * (prefix + i) / 48000.0)).toFloat()
            }
            val got = samples.drop(prefix).toFloatArray()
            val err = got.indices.maxOf { abs(got[it] - expected[it]) }
            println("PHASE accepted=$acceptedThirdFrames prefix=$prefix first=${got[0]} expected=${expected[0]} maxError=$err")
            assertArrayEquals("Fade must continue from the last accepted phase", expected, got, 1e-5f)
        } finally { resume.countDown(); helper.join(5000); p.stop() }
    }
}
