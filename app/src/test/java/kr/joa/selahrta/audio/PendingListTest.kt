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
     * **정리 한가운데에서 지워도 터지지 않는다.**
     *
     * 예전 구현(`CopyOnWriteArrayList` + Kotlin `removeAll { }`)은 이
     * 순서에서 `ArrayIndexOutOfBoundsException` 을 냈다. 그것도 **주
     * 스레드의 `start()`** 에서 나므로 앱이 죽는다.
     */
    @Test
    fun `정리 도중에 같은 항목을 지워도 터지지 않는다`() {
        val list = PendingList<Item>()
        val a = Item("A").also { it.done = true }
        val b = Item("B").also { it.done = true }
        list.addIfPending(a) { false }
        list.addIfPending(b) { false }
        assertEquals(2, list.size)

        val inPredicate = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>(null)
        val workerDone = CountDownLatch(1)

        val worker = Thread {
            try {
                // 정리가 술어 안에 들어간 뒤에 지운다.
                check(inPredicate.await(5, TimeUnit.SECONDS))
                list.remove(a)
                list.remove(b)
            } catch (t: Throwable) {
                workerFailure.set(t)
            } finally {
                workerDone.countDown()
            }
        }.apply { start() }

        var sweepFailure: Throwable? = null
        var remaining = -1
        try {
            remaining = list.sweep {
                // **자물쇠 안이다.** 여기서 풀어 주면 지우는 쪽은 기다린다 —
                // 그것이 바로 이 구조가 지켜 주는 것이다.
                inPredicate.countDown()
                letGo.countDown()
                it.done
            }
        } catch (t: Throwable) {
            sweepFailure = t
        }

        assertTrue("지우는 쪽이 끝나야 한다", workerDone.await(5, TimeUnit.SECONDS))
        worker.join(5_000)

        assertNull("정리가 터지면 안 된다", sweepFailure)
        assertNull("지우는 쪽도 터지면 안 된다", workerFailure.get())
        assertEquals("둘 다 사라져야 한다", 0, remaining)
        assertEquals("목록도 비어야 한다", 0, list.size)
        assertFalse("지우는 스레드가 남으면 안 된다", worker.isAlive)
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
