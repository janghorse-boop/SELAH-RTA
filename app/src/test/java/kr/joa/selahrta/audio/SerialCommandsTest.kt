package kr.joa.selahrta.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * **끌리는 동안 쌓인 낡은 명령을 버리는가**(독립 검토 8회차 3장).
 *
 * 줄에 세우기만 하면 화면은 안 멎지만 **소리가 손가락을 뒤쫓는다** —
 * 실기기에서 대역 슬라이더 한 번이 144ms 이니, 스무 번 끌면 손을 뗀
 * 뒤로도 2.4초를 더 돈다.
 *
 * **시간에 기대지 않고 본다.** 실행자를 손으로 돌려, 스무 개를 먼저
 * 쌓아 둔 뒤 풀어 준다.
 */
class SerialCommandsTest {

    /** 시킨 것을 모아 두었다가 [drain] 할 때 한꺼번에 돌린다. */
    private class HeldExecutor : Executor {
        private val queued = ArrayList<Runnable>()
        override fun execute(command: Runnable) { queued.add(command) }
        fun drain() {
            val copy = ArrayList(queued)
            queued.clear()
            copy.forEach { it.run() }
        }
    }

    @Test
    fun `쌓인 것 중 마지막만 돈다`() {
        val held = HeldExecutor()
        val ran = ArrayList<Int>()
        val c = SerialCommands("t", held)

        repeat(20) { i -> c.post { ran.add(i) } }
        held.drain()

        assertEquals("마지막 하나만 돌아야 한다", listOf(19), ran)
    }

    /**
     * **차례대로 하나씩 오면 모두 돈다.** 버리는 것은 *쌓였을 때*의
     * 이야기지, 평소에 명령을 잃어버리면 안 된다.
     */
    @Test
    fun `하나씩 오면 모두 돈다`() {
        val held = HeldExecutor()
        val ran = ArrayList<Int>()
        val c = SerialCommands("t", held)

        repeat(5) { i ->
            c.post { ran.add(i) }
            held.drain()
        }

        assertEquals(listOf(0, 1, 2, 3, 4), ran)
    }

    /** **한 줄로 돈다.** 겹쳐 돌면 `start` 와 `stop` 이 서로를 앞지른다. */
    @Test
    fun `진짜 실행자에서도 차례가 지켜진다`() {
        val c = SerialCommands("selah-test-cmd")
        val ran = java.util.Collections.synchronizedList(ArrayList<Int>())
        val done = java.util.concurrent.CountDownLatch(1)

        // 하나씩 끝나기를 기다리며 넣으면 버려지지 않는다.
        for (i in 0 until 5) {
            val step = java.util.concurrent.CountDownLatch(1)
            c.post { ran.add(i); step.countDown() }
            check(step.await(5, TimeUnit.SECONDS))
        }
        c.post { done.countDown() }
        check(done.await(5, TimeUnit.SECONDS))

        assertEquals(listOf(0, 1, 2, 3, 4), ran.toList())
        c.shutdownNow()
    }

    /** 닫힌 뒤에 들어온 것은 **조용히 버린다.** 앱이 꺼지는 길이다. */
    @Test
    fun `닫힌 뒤에 넣어도 터지지 않는다`() {
        val c = SerialCommands("selah-test-cmd2", Executors.newSingleThreadExecutor())
        c.shutdownNow()
        c.post { error("돌면 안 된다") }
    }
}
