import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun main() {
    val pending = CopyOnWriteArrayList(listOf(1, 2))
    val inPredicate = CountDownLatch(1)
    val removed = CountDownLatch(1)
    val worker = Thread {
        check(inPredicate.await(5, TimeUnit.SECONDS))
        pending.remove(1)
        pending.remove(2)
        removed.countDown()
    }.apply { start() }
    var failure: Throwable? = null
    try {
        pending.removeAll {
            inPredicate.countDown()
            check(removed.await(5, TimeUnit.SECONDS))
            true
        }
    } catch (t: Throwable) { failure = t }
    worker.join(5000)
    check(!worker.isAlive)
    println("COW_REMOVE_ALL_FAILURE=${failure?.javaClass?.name}: ${failure?.message}")
    failure?.printStackTrace(System.out)
    check(failure is IndexOutOfBoundsException)
}
