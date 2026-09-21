package kr.joa.selahrta.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private fun await(l: CountDownLatch) = check(l.await(5, TimeUnit.SECONDS))

private class FailSink(val autonomous: Boolean, val throwsOnRelease: Boolean) : SignalSink {
    val entered = CountDownLatch(1)
    val gate = CountDownLatch(1)
    val releases = AtomicInteger()
    override fun open(sampleRate: Int, frames: Int) = true
    override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
        entered.countDown()
        if (autonomous) return SignalSink.ERROR_DEAD_OBJECT
        await(gate)
        return frames
    }
    override fun stop() { gate.countDown() }
    override fun release(): Boolean {
        releases.incrementAndGet()
        if (throwsOnRelease) throw IllegalStateException("injected release failure")
        return false
    }
}

private fun checkReleasePath(autonomous: Boolean, throwsOnRelease: Boolean) {
    val sinks = CopyOnWriteArrayList<FailSink>()
    var ended = CountDownLatch(1)
    val p = SignalPlayer(onEnded = { _, _ -> ended.countDown() }, openSink = { FailSink(autonomous, throwsOnRelease).also { sinks.add(it) } }, warn = {})
    repeat(4) {
        ended = CountDownLatch(1)
        val id = p.start(TestSignal.Sine1k, SignalLevel.Low)
        if (id != SignalPlayer.NONE) {
            if (autonomous) await(ended) else { await(sinks.last().entered); p.stop() }
        }
    }
    println("RELEASE_PATH autonomous=$autonomous throws=$throwsOnRelease starts=${sinks.size} failedCount=${p.failedReleaseCount} pending=${p.pendingCount}")
    if (autonomous) check(sinks.size == SignalPlayer.MAX_STUCK_PLAYBACKS && p.pendingCount == sinks.size && p.failedReleaseCount == sinks.size)
    else check(sinks.size == SignalPlayer.MAX_STUCK_PLAYBACKS && p.pendingCount == sinks.size && p.failedReleaseCount == sinks.size)
    check(sinks.all { it.releases.get() == 1 })
    p.stop()
}

private fun checkPendingLock() {
    val list = PendingList<Int>()
    list.addIfPending(1) { false }
    list.addIfPending(2) { false }
    val predicateEntered = CountDownLatch(1)
    val tryingRemove = CountDownLatch(1)
    val done = CountDownLatch(1)
    val worker = Thread {
        await(predicateEntered)
        tryingRemove.countDown()
        list.remove(1)
        done.countDown()
    }.apply { start() }
    var blocked = false
    val remaining = list.sweep {
        if (it == 1) {
            predicateEntered.countDown()
            await(tryingRemove)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < deadline && worker.state != Thread.State.BLOCKED && done.count != 0L) Thread.yield()
            blocked = worker.state == Thread.State.BLOCKED
        }
        it == 1
    }
    await(done)
    worker.join(5000)
    check(blocked && remaining == 1 && list.size == 1 && list.count { it == 2 } == 1)
    println("PENDING_LOCK blocked=$blocked remaining=$remaining pendingItemPreserved=true")
}

fun main() {
    checkPendingLock()
    checkReleasePath(false, false)
    checkReleasePath(false, true)
    checkReleasePath(true, false)
    checkReleasePath(true, true)
}

