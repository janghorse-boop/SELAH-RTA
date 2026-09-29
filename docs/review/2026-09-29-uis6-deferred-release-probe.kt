// Independent JVM regression; use app/src/test/java/kr/joa/selahrta/audio/.
package kr.joa.selahrta.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DeferredReleaseIndependentProbe {
    @Test fun stopMayReturnBeforeReleaseWithoutLeaking() {
        val enteredWrite = CountDownLatch(1)
        val allowWriteReturn = CountDownLatch(1)
        val released = CountDownLatch(1)
        val stopped = AtomicBoolean(false)
        val inWrite = AtomicBoolean(false)
        val releasedDuringWrite = AtomicBoolean(false)
        val sink = object : SignalSink {
            override fun open(sampleRate: Int, frames: Int, channels: Int) = true
            override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
                inWrite.set(true)
                enteredWrite.countDown()
                try {
                    check(allowWriteReturn.await(10, TimeUnit.SECONDS))
                    return frames
                } finally { inWrite.set(false) }
            }
            override fun stop() { stopped.set(true) }
            override fun release(): Boolean {
                releasedDuringWrite.set(inWrite.get())
                released.countDown()
                return true
            }
        }
        val player = SignalPlayer(openSink = { sink }, warn = {})
        try {
            assertTrue(player.start(SignalRequest(TestSignal.Sine1k, DEFAULT_AMPLITUDE)) != SignalPlayer.NONE)
            assertTrue(enteredWrite.await(10, TimeUnit.SECONDS))
            player.stop()
            assertTrue(stopped.get())
            assertEquals("writer still owns the sink", 1L, released.count)
            assertEquals("the resource is tracked", 1, player.pendingCount)
            println("AFTER_STOP released=false pending=${player.pendingCount}")
        } finally { allowWriteReturn.countDown() }
        assertTrue("eventual release", released.await(10, TimeUnit.SECONDS))
        assertFalse("never release while write is active", releasedDuringWrite.get())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (player.pendingCount != 0 && System.nanoTime() < deadline) Thread.yield()
        assertEquals(0, player.pendingCount)
        println("AFTER_WRITER_RESUMES released=true pending=${player.pendingCount}")
    }
}
