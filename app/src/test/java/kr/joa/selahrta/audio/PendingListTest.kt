package kr.joa.selahrta.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * **RC01 의 회귀** — 정리하는 도중에 다른 스레드가 같은 항목을 지워도
 * 탈나지 않아야 한다.
 *
 * 검증자가 요구한 방식 그대로 **barrier 로 순서를 고정한다.** 되풀이해
 * 돌려 보고 통과하는 것으로 대신하지 않는다 — 실제로 그 경합은
 * 간헐적이라, 내 실행에서는 274건이 다 통과했는데 검증자의 독립 실행에서
 * 1건이 터졌다.
 */
class PendingListTest {

    private class Item(val name: String) {
        @Volatile
        var done = false
        override fun toString() = name
    }

    /**
     * **정리 한가운데에서 지우려 하면 그쪽이 기다린다.**
     *
     * 예전 구현(`CopyOnWriteArrayList` + Kotlin `removeAll { }`)은 이
     * 순서에서 `ArrayIndexOutOfBoundsException` 을 냈다. 그것도 **주
     * 스레드의 `start()`** 에서 나므로 앱이 죽는다.
     *
     * **기다린다는 것을 실제로 확인한다.** 처음 쓴 시험은 술어 안에서
     * latch 만 열고 끝나서, 지우는 쪽이 `remove` 에 닿기도 전에 정리가
     * 끝나 버릴 수 있었다 — 그러면 아무것도 보지 않은 것이다(검증자
     * 보완 제안). 이제 그 스레드가 정말 `BLOCKED` 가 되는 것을 본다.
     */
    @Test
    fun `정리 도중에 지우려 하면 그쪽이 기다린다`() {
        val list = PendingList<Item>()
        val done = Item("끝남").also { it.done = true }
        val pending = Item("진행중")
        list.addIfPending(done) { false }
        list.addIfPending(pending) { false }

        val inPredicate = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                check(inPredicate.await(5, TimeUnit.SECONDS))
                list.remove(pending)
            } catch (t: Throwable) {
                workerFailure.set(t)
            }
        }.apply { name = "pending-remover"; start() }

        var blocked = false
        var sweepFailure: Throwable? = null
        var remaining = -1
        try {
            remaining = list.sweep { item ->
                inPredicate.countDown()
                // 지우려는 쪽이 **자물쇠 앞에서 막히는지** 본다.
                val until = System.nanoTime() + 3_000_000_000L
                while (!blocked && System.nanoTime() < until) {
                    if (worker.state == Thread.State.BLOCKED) blocked = true
                }
                item.done
            }
        } catch (t: Throwable) {
            sweepFailure = t
        }

        worker.join(5_000)

        println("[RC01] 막혔는가=$blocked · 남은 수=$remaining")
        assertNull("정리가 터지면 안 된다", sweepFailure)
        assertNull("지우는 쪽도 터지면 안 된다", workerFailure.get())
        assertTrue("지우려는 쪽이 자물쇠에서 기다려야 한다", blocked)
        assertEquals("끝난 것만 치우고 나머지는 남긴다", 1, remaining)
        assertFalse("지우는 스레드가 남으면 안 된다", worker.isAlive)
        // 정리가 끝난 뒤 지우는 쪽이 제 일을 마쳐 결국 비워진다.
        assertEquals("지우는 쪽 일까지 끝나면 빈다", 0, list.size)
    }

    /** **아직 안 끝난 것은 정리에 쓸려 가지 않는다.** */
    @Test
    fun `끝나지 않은 것은 남긴다`() {
        val list = PendingList<Item>()
        val done = Item("끝남").also { it.done = true }
        val pending = Item("진행중")
        list.addIfPending(done) { false }
        list.addIfPending(pending) { false }

        val remaining = list.sweep { it.done }

        assertEquals("하나만 남는다", 1, remaining)
        assertEquals(1, list.size)
        assertEquals("남은 것은 진행 중인 것", 1, list.count { it === pending })
    }

    /**
     * **넣을지 말지를 같은 자물쇠 안에서 본다.**
     *
     * 밖에서 보고 넣으면, 보는 사이에 끝난 것이 목록에 남아 세는 수가
     * 실제보다 커진다(검증자 후속 점검).
     */
    @Test
    fun `이미 끝난 것은 넣지 않는다`() {
        val list = PendingList<Item>()
        val finished = Item("끝남").also { it.done = true }

        val added = list.addIfPending(finished) { it.done }

        assertFalse("끝난 것은 넣지 않는다", added)
        assertEquals(0, list.size)
    }

    /** 같은 것을 두 번 넣지 않는다. */
    @Test
    fun `같은 것을 두 번 넣지 않는다`() {
        val list = PendingList<Item>()
        val one = Item("하나")
        list.addIfPending(one) { it.done }
        list.addIfPending(one) { it.done }
        assertEquals(1, list.size)
    }
}
