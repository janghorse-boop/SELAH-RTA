package kr.joa.selahrta.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private fun await(l: CountDownLatch) = check(l.await(10, TimeUnit.SECONDS))

private class SlowReleaseSink : SignalSink {
    val writing = CountDownLatch(1)
    val writeGate = CountDownLatch(1)
    val releaseEntered = CountDownLatch(1)
    val releaseGate = CountDownLatch(1)
    val releaseFinished = CountDownLatch(1)
    val releaseCalls = AtomicInteger()
    override fun open(sampleRate: Int, frames: Int) = true
    override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
        writing.countDown()
        await(writeGate)
        return frames
    }
    override fun stop() { writeGate.countDown() }
    override fun release() {
        releaseCalls.incrementAndGet()
        releaseEntered.countDown()
        await(releaseGate)
        releaseFinished.countDown()
    }
}

fun main() {
    val sinks = CopyOnWriteArrayList<SlowReleaseSink>()
    val p = SignalPlayer(openSink = { SlowReleaseSink().also { sinks.add(it) } }, warn = {})
    try {
        var starts = 0
        repeat(4) {
            val id = p.start(TestSignal.Sine1k, SignalLevel.Low)
            if (id != SignalPlayer.NONE) {
                starts++
                await(sinks.last().writing)
                p.stop()
                await(sinks.last().releaseEntered)
            }
        }
        val unfinished = sinks.count { it.releaseFinished.count != 0L }
        println("RELEASE_PENDING starts=$starts unfinished=$unfinished pendingCount=${p.pendingCount} cap=${SignalPlayer.MAX_STUCK_PLAYBACKS}")
        check(unfinished > SignalPlayer.MAX_STUCK_PLAYBACKS) { "Reported bypass no longer reproduces" }
    } finally {
        sinks.forEach { it.writeGate.countDown(); it.releaseGate.countDown() }
        sinks.forEach { await(it.releaseFinished) }
    }
    check(sinks.all { it.releaseCalls.get() == 1 })
    println("CLEANUP all ${sinks.size} releases completed exactly once")
}
