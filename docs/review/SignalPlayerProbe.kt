package kr.joa.selahrta.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList

private fun await(l: CountDownLatch) = check(l.await(5, TimeUnit.SECONDS))

private class StuckSink : SignalSink {
    val entered = CountDownLatch(1)
    val unblock = CountDownLatch(1)
    val released = CountDownLatch(1)
    override fun open(sampleRate: Int, frames: Int) = true
    override fun write(buf: FloatArray, frames: Int): Int { entered.countDown(); await(unblock); return frames }
    override fun stop() {}
    override fun release() { released.countDown() }
}

private class StopRaceSink : SignalSink {
    val entered = CountDownLatch(1)
    val writeGate = CountDownLatch(1)
    val stopEntered = CountDownLatch(1)
    val finishStop = CountDownLatch(1)
    val released = CountDownLatch(1)
    @Volatile var insideStop = false
    @Volatile var releasedDuringStop = false
    override fun open(sampleRate: Int, frames: Int) = true
    override fun write(buf: FloatArray, frames: Int): Int { entered.countDown(); await(writeGate); return frames }
    override fun stop() { insideStop = true; stopEntered.countDown(); await(finishStop); insideStop = false }
    override fun release() { releasedDuringStop = insideStop; released.countDown() }
}

private class PartialSink : SignalSink {
    val calls = AtomicInteger()
    val secondEntered = CountDownLatch(1)
    val gate = CountDownLatch(1)
    val released = CountDownLatch(1)
    @Volatile var secondStart = 0f
    override fun open(sampleRate: Int, frames: Int) = true
    override fun write(buf: FloatArray, frames: Int): Int {
        if (calls.incrementAndGet() == 1) return 128
        secondStart = buf[0]
        secondEntered.countDown()
        await(gate)
        return frames
    }
    override fun stop() { gate.countDown() }
    override fun release() { released.countDown() }
}

fun main() {
    val sinks = CopyOnWriteArrayList<StuckSink>()
    val p = SignalPlayer(openSink = { StuckSink().also { sinks.add(it) } })
    repeat(3) {
        p.start(TestSignal.Sine1k, SignalLevel.Low)
        await(sinks.last().entered)
        p.stop()
    }
    val retained = sinks.count { it.released.count != 0L }
    println("TIMEOUT_RETAINED=$retained after 3 sequential start/stop cycles; playing=${p.playing}")
    check(retained == 3)
    sinks.forEach { it.unblock.countDown() }
    sinks.forEach { await(it.released) }

    val race = StopRaceSink()
    val q = SignalPlayer(openSink = { race })
    q.start(TestSignal.Sine1k, SignalLevel.Low)
    await(race.entered)
    val stopper = Thread { q.stop() }.apply { start() }
    await(race.stopEntered)
    race.writeGate.countDown()
    await(race.released)
    println("RELEASE_DURING_STOP=${race.releasedDuringStop}")
    check(race.releasedDuringStop)
    race.finishStop.countDown()
    stopper.join(2000)
    check(!stopper.isAlive)

    val partial = PartialSink()
    val r = SignalPlayer(openSink = { partial })
    r.start(TestSignal.Sine1k, SignalLevel.Low)
    await(partial.secondEntered)
    val expected = (0.05 * kotlin.math.sin(2 * kotlin.math.PI * 1000 * 128 / 48000)).toFloat()
    println("PARTIAL_NEXT_SAMPLE=${partial.secondStart}; expected_after_128=$expected; skipped=896")
    check(kotlin.math.abs(partial.secondStart - expected) > 0.01)
    r.stop()
    await(partial.released)
}

