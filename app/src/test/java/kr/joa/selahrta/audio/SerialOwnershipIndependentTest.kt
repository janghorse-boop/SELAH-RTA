package kr.joa.selahrta.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*

class SerialOwnershipIndependentTest {
    private class Held : Executor {
        val queue = ArrayList<Runnable>()
        override fun execute(r: Runnable) { queue.add(r) }
        fun drain() { queue.toList().also { queue.clear() }.forEach { it.run() } }
    }

    @Test fun lifecycleEventDoesNotCancelDesiredState() {
        val held = Held()
        val c = SerialCommands("probe", held)
        val actual = ArrayList<String>()
        c.post { actual.add("start") }
        c.postAlways { actual.add("old-end") }
        held.drain()
        assertEquals(listOf("start", "old-end"), actual)
    }

    @Test fun desiredStateDoesNotCancelLifecycleEvent() {
        val held = Held()
        val c = SerialCommands("probe", held)
        val actual = ArrayList<String>()
        c.postAlways { actual.add("event") }
        c.post { actual.add("old-state") }
        c.post { actual.add("new-state") }
        held.drain()
        assertEquals(listOf("event", "new-state"), actual)
    }

    @Test fun closeSubstitutesFinalizerForQueuedWorkExactlyOnce() {
        val held = Held()
        val c = SerialCommands("probe", held)
        val actual = ArrayList<String>()
        c.post { actual.add("state") }
        c.postAlways { actual.add("event") }
        c.close { actual.add("finalizer") }
        c.postAlways { actual.add("late-event") }
        c.post { actual.add("late-state") }
        c.close { actual.add("duplicate-finalizer") }
        held.drain()
        assertEquals(listOf("finalizer"), actual)
    }

    @Test fun finalizerWaitsForInflightOperationWithoutInterruptingIt() {
        val executor = Executors.newSingleThreadExecutor()
        val c = SerialCommands("probe", executor)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val actual = java.util.Collections.synchronizedList(ArrayList<String>())
        try {
            c.post {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                actual.add("inflight-completed")
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            c.close { actual.add("finalizer"); finished.countDown() }
            assertEquals(1L, finished.count)
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("inflight-completed", "finalizer"), actual)
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS) }
    }
}
