package kr.joa.selahrta.ui

import kotlin.math.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

fun main() {
    val clock = FakeClock()
    lateinit var src: FakeSource
    val c = CaptureController(
        listDevices = { listOf(builtInMic()) },
        openSource = { target, hooks -> FakeSource(target, hooks, clock = clock).also { src = it } },
        post = { it() }, onRouteConfirmedHook = {}, onStoppedHook = {}, nowNs = { clock.ns },
    )
    c.start()
    var sample = 0L
    repeat(100) {
        src.deliverRaw(FloatArray(1024) { (0.2 * sin(2 * PI * 1000 * sample++ / 48000)).toFloat() })
    }
    val before = c.state.value.withMeasurement(c.measurement.value)
    check(before.meter.currentSpl != null && before.rta != null && before.diagnostics.frames > 0)
    c.stop()
    val after = c.state.value.withMeasurement(c.measurement.value)
    println("STOP_BEFORE current=${before.meter.currentSpl} max=${before.meter.maxSpl} rta=${before.rta != null} frames=${before.diagnostics.frames}")
    println("STOP_AFTER current=${after.meter.currentSpl} max=${after.meter.maxSpl} rta=${after.rta != null} frames=${after.diagnostics.frames} log=${after.feedbackLog.size}")
    check(after.meter == before.meter && after.rta != null && after.diagnostics == before.diagnostics)
    println("Confirmed stop snapshot preservation using unmodified CaptureController and production state composition.")

    c.start()
    val old = src
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val workerFailure = AtomicReference<Throwable?>()
    c.postToCapture { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
    val worker = Thread({ try { old.deliver(count = 4096) } catch (t: Throwable) { workerFailure.set(t) } })
    worker.start()
    check(entered.await(5, TimeUnit.SECONDS)) // onBlock passed its active-session guard.
    c.stop()
    c.start()
    repeat(100) { src.deliver() }
    val newId = c.state.value.session
    check(c.measurement.value!!.session == newId)
    release.countDown()
    worker.join(5000)
    check(!worker.isAlive && workerFailure.get() == null)
    val stale = c.measurement.value!!
    val visible = c.state.value.withMeasurement(stale)
    println("LATE_SNAPSHOT active=$newId published=${stale.session} visibleCurrent=${visible.meter.currentSpl}")
    check(stale.session == newId && visible.meter.currentSpl != null)
    c.stop()
}

