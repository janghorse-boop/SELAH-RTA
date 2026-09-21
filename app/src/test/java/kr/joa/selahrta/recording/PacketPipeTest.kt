package kr.joa.selahrta.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * SPSC 링과 버퍼 풀(녹음 설계 4차 M31).
 *
 * 설계가 요구한 경우를 그대로 만든다 — **풀을 말린 상태, writer 정지,
 * 번호 받은 직후 close, interrupt 를 건 상태, 복사 실패 주입.** 모든
 * 경우에 비움이 끝나고 **모든 버퍼가 정확히 한 번** 돌아오는지 본다.
 *
 * 검증자가 나눈 구분을 지킨다:
 *
 * | 무엇 | 뜻 |
 * |---|---|
 * | 풀에서 못 잡음 | **정상**이다. 그것이 곧 손실이고 세어서 남긴다 |
 * | 번호 받은 뒤 게시 실패 | **불변식 위반**이다. 일어나면 안 된다 |
 */
class PacketPipeTest {

    private val frames = 64

    /**
     * 오디오 스레드가 하는 일 한 번.
     *
     * 순서가 설계 그대로여야 한다 — **풀에서 잡기 → 복사 → 번호 받기 →
     * 게시.** 번호 받기와 게시 사이에는 필드 대입과 색인 저장뿐이다.
     */
    private fun offer(
        pool: BufferPool,
        ring: PacketRing,
        adm: Admission,
        frameStart: Long,
        epochId: Int = 0,
        copy: (FloatArray) -> Unit = {},
    ): OfferResult {
        val bufferIndex = pool.acquire()
        if (bufferIndex < 0) return OfferResult.PoolEmpty

        try {
            copy(pool.buffer(bufferIndex))
        } catch (t: Throwable) {
            // 아직 번호를 받기 전이다. 버퍼만 한 번 돌려주고 물러난다.
            pool.release(bufferIndex)
            return OfferResult.CopyFailed
        }

        val seq = adm.next()
        if (seq < 0) {
            // 닫혔다. 역시 버퍼만 돌려준다.
            pool.release(bufferIndex)
            return OfferResult.Closed
        }

        val ok = ring.publish(seq, frameStart, frames, epochId, bufferIndex)
        // **여기서 실패하면 불변식이 깨진 것이다.** 손실로 세지 않는다.
        if (!ok) return OfferResult.PublishFailed
        return OfferResult.Published
    }

    private enum class OfferResult { Published, PoolEmpty, Closed, CopyFailed, PublishFailed }

    // ------------------------------------------------------------------

    @Test
    fun `잡은 버퍼마다 반드시 빈 칸이 있다`() {
        val n = 8
        val pool = BufferPool(n, frames)
        val ring = PacketRing(n)
        val adm = Admission()

        // 소비자를 아예 돌리지 않는다. 풀이 마를 때까지 넣는다.
        var published = 0
        var poolEmpty = 0
        repeat(n * 3) { i ->
            when (offer(pool, ring, adm, i.toLong() * frames)) {
                OfferResult.Published -> published++
                OfferResult.PoolEmpty -> poolEmpty++
                else -> throw AssertionError("여기서는 나올 수 없는 결과다")
            }
        }
        assertEquals("풀 크기만큼만 들어간다", n, published)
        assertEquals("나머지는 손실이다", n * 2, poolEmpty)
        assertEquals("게시 실패는 하나도 없어야 한다", n, ring.size)
    }

    @Test
    fun `풀이 마르면 막지 않고 곧바로 돌아온다`() {
        val pool = BufferPool(2, frames)
        val ring = PacketRing(2)
        val adm = Admission()
        offer(pool, ring, adm, 0)
        offer(pool, ring, adm, 64)

        // 막히면 여기서 영영 돌아오지 않는다. 시간을 재서 본다.
        val t0 = System.nanoTime()
        repeat(1000) {
            assertEquals(OfferResult.PoolEmpty, offer(pool, ring, adm, 999))
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("1000번이 $ms ms 걸렸다 — 어딘가 기다린다", ms < 500)
    }

    /** **interrupt 가 걸려 있어도** 그대로 동작한다. 표준 큐는 여기서 던진다. */
    @Test
    fun `interrupt 가 걸린 스레드에서도 던지지 않는다`() {
        val pool = BufferPool(4, frames)
        val ring = PacketRing(4)
        val adm = Admission()
        val slot = PacketSlot()

        Thread.currentThread().interrupt()
        try {
            assertEquals(OfferResult.Published, offer(pool, ring, adm, 0))
            assertTrue(ring.poll(slot))
            pool.release(slot.bufferIndex)
            assertTrue("인터럽트 표시는 그대로 남아야 한다", Thread.currentThread().isInterrupted)
        } finally {
            // 표시를 지워 다른 시험에 새지 않게 한다.
            Thread.interrupted()
        }
    }

    /** 복사가 실패하면 **번호를 받기 전**이므로 버퍼만 한 번 돌아온다. */
    @Test
    fun `복사가 실패하면 버퍼만 한 번 돌아온다`() {
        val pool = BufferPool(3, frames)
        val ring = PacketRing(3)
        val adm = Admission()

        repeat(10) {
            assertEquals(
                OfferResult.CopyFailed,
                offer(pool, ring, adm, 0, copy = { throw IllegalStateException("복사 실패 주입") }),
            )
        }
        assertEquals("버퍼가 모두 돌아와 있어야 한다", 0, pool.inUse)
        assertEquals("번호를 받지 않았으므로 아무것도 게시되지 않는다", 0, ring.size)
    }

    /** 닫힌 뒤에는 번호를 주지 않고, 잡았던 버퍼는 돌아온다. */
    @Test
    fun `닫힌 뒤에는 버퍼만 돌려주고 물러난다`() {
        val pool = BufferPool(3, frames)
        val ring = PacketRing(3)
        val adm = Admission()

        assertEquals(OfferResult.Published, offer(pool, ring, adm, 0))
        adm.close()

        repeat(5) {
            assertEquals(OfferResult.Closed, offer(pool, ring, adm, 64))
        }
        assertEquals("게시된 하나만 빌려 나가 있다", 1, pool.inUse)
        assertEquals(1, ring.size)
    }

    /** **번호를 받은 직후 닫혀도** 그 조각은 들어간다. */
    @Test
    fun `번호를 받은 뒤 닫혀도 그 조각은 들어간다`() {
        val pool = BufferPool(2, frames)
        val ring = PacketRing(2)
        val adm = Admission()

        val bufferIndex = pool.acquire()
        val seq = adm.next()
        adm.close() // 번호를 받은 바로 뒤에 닫힌다
        assertTrue("번호를 받았으면 반드시 들어가야 한다", ring.publish(seq, 0, frames, 0, bufferIndex))
        assertEquals(1, ring.size)
        assertTrue("그 뒤로는 번호를 주지 않는다", adm.next() < 0)
    }

    /**
     * **writer 를 세워 두고** 넣다가, 풀린 뒤 전부 돌아오는지 본다.
     *
     * 칸은 인수한 즉시 돌아오지만 **PCM 은 writer 가 다 쓴 뒤**다.
     * 둘을 같은 시점으로 묶으면 아직 쓰고 있는 PCM 이 덮인다.
     */
    @Test
    fun `writer 를 세워 두었다 풀어도 모든 버퍼가 정확히 한 번 돌아온다`() {
        val n = 16
        val pool = BufferPool(n, frames)
        val ring = PacketRing(n)
        val adm = Admission()

        val released = IntArray(n)
        val writerGate = CountDownLatch(1)
        val consumed = AtomicInteger(0)
        val consumerDone = CountDownLatch(1)
        val stop = AtomicBoolean(false)
        val invariantBroken = AtomicBoolean(false)

        val consumer = Thread {
            val slot = PacketSlot()
            val held = ArrayList<Int>()
            while (!stop.get() || ring.size > 0) {
                if (ring.poll(slot)) {
                    // 칸은 이미 돌려받았다. 버퍼는 writer 가 끝나야 한다.
                    held.add(slot.bufferIndex)
                    consumed.incrementAndGet()
                } else {
                    Thread.sleep(1)
                }
            }
            // writer 가 풀릴 때까지 붙들고 있다가 한꺼번에 놓는다.
            writerGate.await()
            held.forEach { i -> released[i]++; pool.release(i) }
            consumerDone.countDown()
        }
        consumer.start()

        var published = 0
        var lost = 0
        repeat(n * 40) { i ->
            when (offer(pool, ring, adm, i.toLong() * frames)) {
                OfferResult.Published -> published++
                OfferResult.PoolEmpty -> lost++
                OfferResult.PublishFailed -> invariantBroken.set(true)
                else -> invariantBroken.set(true)
            }
        }
        stop.set(true)
        writerGate.countDown()
        assertTrue("소비자가 끝나야 한다", consumerDone.await(10, TimeUnit.SECONDS))
        consumer.join(5_000)

        assertFalse("번호를 받고도 게시하지 못한 적이 있다 — 불변식 위반", invariantBroken.get())
        assertEquals("게시한 수와 인수한 수가 같아야 한다", published, consumed.get())
        assertEquals("남은 것이 없어야 한다", 0, ring.size)
        assertEquals("모든 버퍼가 돌아와야 한다", 0, pool.inUse)
        released.forEachIndexed { i, times ->
            assertTrue("버퍼 $i 를 $times 번 돌려줬다 — 한 번씩이어야 한다", times <= 1)
        }
        println("PIPE published=$published lost=$lost consumed=${consumed.get()}")
    }

    /**
     * 오래 돌려도 순서와 번호가 이어진다. 생산자·소비자가 진짜로 돈다.
     *
     * **몇 건이 들어갈지를 단언하지 않는다.** 처음에는 「20,000번 시도해
     * 1,000건 넘게 들어가야 한다」로 적었는데, 전체 빌드에서 다른 시험과
     * 함께 돌자 소비자가 CPU 를 못 얻어 **8건**만 들어갔다. 기계 부하에
     * 좌우되는 단언이었다.
     *
     * 그래서 **정해진 수를 채울 때까지** 넣는다. 풀이 마르면 시험은
     * 기다린다 — **오디오 스레드는 그러지 않는다.** 막지 않는 성질은
     * `풀이 마르면 막지 않고 곧바로 돌아온다` 가 따로 본다.
     */
    @Test
    fun `오래 돌려도 순서와 번호가 이어진다`() {
        val n = 8
        val pool = BufferPool(n, frames)
        val ring = PacketRing(n)
        val adm = Admission()

        val lastSeq = AtomicLong(-1)
        val outOfOrder = AtomicInteger(0)
        val stop = AtomicBoolean(false)
        val done = CountDownLatch(1)

        val consumer = Thread {
            val slot = PacketSlot()
            while (!stop.get() || ring.size > 0) {
                if (ring.poll(slot)) {
                    if (slot.seq <= lastSeq.get()) outOfOrder.incrementAndGet()
                    lastSeq.set(slot.seq)
                    // 읽은 값이 온전한지 본다 — 반쯤 채워진 칸을 읽으면 어긋난다.
                    if (slot.frames != frames) outOfOrder.incrementAndGet()
                    pool.release(slot.bufferIndex)
                } else {
                    Thread.yield()
                }
            }
            done.countDown()
        }
        consumer.start()

        val target = 2_000
        var published = 0
        var waited = 0L
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (published < target) {
            when (offer(pool, ring, adm, published.toLong() * frames)) {
                OfferResult.Published -> published++
                OfferResult.PoolEmpty -> { waited++; Thread.yield() }
                else -> throw AssertionError("여기서는 나올 수 없는 결과다")
            }
            if (System.nanoTime() > deadline) {
                throw AssertionError("30초 안에 $target 건을 채우지 못했다: $published")
            }
        }
        stop.set(true)
        assertTrue(done.await(30, TimeUnit.SECONDS))
        consumer.join(5_000)

        assertEquals("번호가 뒤집히거나 칸이 반쯤 읽힌 적이 있다", 0, outOfOrder.get())
        assertEquals("모든 버퍼가 돌아와야 한다", 0, pool.inUse)
        assertEquals("마지막 번호가 맞아야 한다", (target - 1).toLong(), lastSeq.get())
        println("PIPE_LONG published=$published poolEmptyWaits=$waited")
    }

    /**
     * **가득 찬 링은 덮어쓰지 않고 거절한다.**
     *
     * 정상 흐름에서는 여기 닿지 않는다 — 버퍼를 잡았으면 칸이 반드시
     * 있기 때문이다. 그래서 풀을 거치는 시험만으로는 이 검사를 지워도
     * 아무도 못 잡는다(되돌려 보고 알았다). **안전망은 따로 두드린다.**
     *
     * 이 검사가 없으면, 언젠가 불변식이 깨졌을 때 조용히 옛 칸을 덮어
     * 이미 게시한 조각이 사라진다.
     */
    @Test
    fun `링이 가득 차면 덮어쓰지 않고 거절한다`() {
        val cap = 4
        val ring = PacketRing(cap)
        repeat(cap) { i ->
            assertTrue(ring.publish(i.toLong(), i.toLong() * frames, frames, 0, i))
        }
        assertEquals(cap, ring.size)

        assertFalse("가득 찼으면 거절해야 한다", ring.publish(99, 0, frames, 0, 3))
        assertEquals("거절했으니 수가 늘면 안 된다", cap, ring.size)

        // 먼저 넣은 것이 그대로 나와야 한다 — 덮였다면 99 가 섞인다.
        val slot = PacketSlot()
        repeat(cap) { i ->
            assertTrue(ring.poll(slot))
            assertEquals("덮어썼다", i.toLong(), slot.seq)
        }
        assertFalse(ring.poll(slot))
    }

    @Test
    fun `두 번 돌려주면 터진다`() {
        val pool = BufferPool(2, frames)
        val i = pool.acquire()
        pool.release(i)
        val e = runCatching { pool.release(i) }.exceptionOrNull()
        assertNotNull("두 번 돌려준 것을 잡아야 한다", e)
    }

    @Test
    fun `말이 안 되는 크기는 만들지 않는다`() {
        assertNotNull(runCatching { BufferPool(0, frames) }.exceptionOrNull())
        assertNotNull(runCatching { BufferPool(4, 0) }.exceptionOrNull())
        assertNotNull(runCatching { PacketRing(0) }.exceptionOrNull())
        assertNotNull(runCatching { BufferPool(2, frames).release(5) }.exceptionOrNull())
    }
}
