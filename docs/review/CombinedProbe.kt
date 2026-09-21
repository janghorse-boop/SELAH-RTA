import kr.joa.selahrta.dsp.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

private fun power(hz: Double): DoubleArray {
    val p = DoubleArray(2049)
    PowerSpectrum(4096).compute(DoubleArray(4096) { 0.2 * sin(2 * PI * hz * it / 48000) }, 0, p)
    return p
}

// Test-only instrumentation: a normal ArrayList iterator, paused at a precise
// legal scheduling point. Actual FeedbackDetector process/finish code is unchanged.
private class BarrierList<T>(private val reached: CountDownLatch, private val resume: CountDownLatch) : ArrayList<T>() {
    override fun iterator(): MutableIterator<T> {
        val delegate = super.iterator()
        var paused = false
        return object : MutableIterator<T> {
            override fun hasNext() = delegate.hasNext()
            override fun remove() = delegate.remove()
            override fun next(): T {
                val item = delegate.next()
                if (Thread.currentThread().name == "review-finish" && !paused) {
                    paused = true
                    reached.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                }
                return item
            }
        }
    }
}

fun main() {
    val p = power(1000.0)
    val tail = FeedbackDetector(4096, 48000)
    for (i in 0..34) tail.process(p, i * 43L)
    check(tail.events.isEmpty())
    tail.process(DoubleArray(2049), 1505)
    println("SILENCE_PROMOTION events=${tail.events}")
    check(tail.events.any { it.durationMs < 1500 })

    val d = FeedbackDetector(4096, 48000)
    val reached = CountDownLatch(1)
    val resume = CountDownLatch(1)
    val field = FeedbackDetector::class.java.getDeclaredField("tracks").apply { isAccessible = true }
    field.set(d, BarrierList<Any>(reached, resume))
    for (i in 0..60) d.process(p, i * 43L)
    val failure = AtomicReference<Throwable?>()
    val ending = Thread({ try { d.finish() } catch (t: Throwable) { failure.set(t) } }, "review-finish")
    ending.start()
    check(reached.await(5, TimeUnit.SECONDS))
    // Represents a callback that passed active !== session before stop invalidated it.
    try { d.process(power(3150.0), 2623) } finally { resume.countDown() }
    ending.join(5000)
    check(!ending.isAlive)
    println("CONCURRENT_FINISH failure=${failure.get()?.javaClass?.name}")
    check(failure.get() is ConcurrentModificationException)
    println("Confirmed two residual defects; no Android runtime or ViewModel was executed.")
}
