package kr.joa.selahrta.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * SPSC 링과 버퍼 풀(녹음 설계 4차 M31, 독립 검증 REC01).
 *
 * 설계가 요구한 경우를 그대로 만든다 — **풀을 말린 상태, writer 정지,
 * 번호 받은 직후 close, interrupt 를 건 상태, 복사 실패 주입.** 모든
 * 경우에 비움이 끝나고 **모든 버퍼가 정확히 한 번** 돌아오는지 본다.
 *
 * **넣는 순서는 제품 코드([PacketPipe.offer])를 쓴다.** 예전에는 그
 * 순서를 시험 안에만 적어 두었고, 거기서 생산자가 free-ring 에 돌려주게
 * 해 「돌려주는 쪽은 하나」라는 전제를 깼다. 시험에만 있는 규칙은
 * 제품이 지키는지 아무도 모른다(REC01).
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

    // ------------------------------------------------------------------

    @Test
    fun `잡은 버퍼마다 반드시 빈 칸이 있다`() {
        val n = 8
        val pipe = PacketPipe(n, frames)

        // 소비자를 아예 돌리지 않는다. 풀이 마를 때까지 넣는다.
        var published = 0
        var dropped = 0
        repeat(n * 3) { i ->
            when (pipe.offer(i.toLong() * frames, frames, 0) {}) {
                PacketPipe.Offer.Published -> published++
                PacketPipe.Offer.Dropped -> dropped++
                else -> throw AssertionError("여기서는 나올 수 없는 결과다")
            }
        }
        assertEquals("풀 크기만큼만 들어간다", n, published)
        assertEquals("나머지는 손실이다", n * 2, dropped)
        assertEquals("손실을 세고 있어야 한다", (n * 2).toLong(), pipe.dropped)
        assertEquals("게시 실패는 하나도 없어야 한다", n, pipe.ring.size)
    }

    @Test
    fun `풀이 마르면 막지 않고 곧바로 돌아온다`() {
        val pipe = PacketPipe(2, frames)
        pipe.offer(0, frames, 0) {}
        pipe.offer(64, frames, 0) {}

        // 막히면 여기서 영영 돌아오지 않는다. 시간을 재서 본다.
        val t0 = System.nanoTime()
        repeat(1000) {
            assertEquals(PacketPipe.Offer.Dropped, pipe.offer(999, frames, 0) {})
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("1000번이 $ms ms 걸렸다 — 어딘가 기다린다", ms < 500)
    }

    /** **interrupt 가 걸려 있어도** 그대로 동작한다. 표준 큐는 여기서 던진다. */
    @Test
    fun `interrupt 가 걸린 스레드에서도 던지지 않는다`() {
        val pipe = PacketPipe(4, frames)
        val slot = PacketSlot()

        Thread.currentThread().interrupt()
        try {
            assertEquals(PacketPipe.Offer.Published, pipe.offer(0, frames, 0) {})
            assertTrue(pipe.poll(slot))
            pipe.releaseAfterWrite(slot.bufferIndex)
            assertTrue("인터럽트 표시는 그대로 남아야 한다", Thread.currentThread().isInterrupted)
        } finally {
            // 표시를 지워 다른 시험에 새지 않게 한다.
            Thread.interrupted()
        }
    }

    /**
     * 복사가 실패하면 **번호를 받기 전**이므로 아무것도 남지 않는다.
     *
     * 되돌린 버퍼는 **free-ring 이 아니라 생산자 제 자리**로 간다 —
     * 그래야 writer 와 다투지 않는다(REC01).
     */
    @Test
    fun `복사가 실패하면 버퍼가 생산자 자리로 되돌아간다`() {
        val pipe = PacketPipe(3, frames)

        repeat(10) {
            assertEquals(
                PacketPipe.Offer.CopyFailed,
                pipe.offer(0, frames, 0) { throw IllegalStateException("복사 실패 주입") },
            )
        }
        assertEquals("writer 에게 나간 것은 없다", 0, pipe.pool.inUse)
        assertTrue("생산자가 하나를 쥐고 있다", pipe.pool.hasProducerHeld)
        assertEquals("번호를 받지 않았으므로 아무것도 게시되지 않는다", 0, pipe.ring.size)

        // 끝낼 때 넘겨야 영영 잃지 않는다.
        pipe.handOverProducerHeld()
        assertFalse(pipe.pool.hasProducerHeld)
        assertEquals("전부 free-ring 으로 돌아와야 한다", 3, drainFreeCount(pipe))
    }

    /** 닫힌 뒤에는 번호를 주지 않고, 잡았던 버퍼는 생산자 자리로 돌아온다. */
    @Test
    fun `닫힌 뒤에는 버퍼를 되돌리고 물러난다`() {
        val pipe = PacketPipe(3, frames)

        assertEquals(PacketPipe.Offer.Published, pipe.offer(0, frames, 0) {})
        pipe.close()

        repeat(5) {
            assertEquals(PacketPipe.Offer.Closed, pipe.offer(64, frames, 0) {})
        }
        assertEquals("게시된 하나만 writer 쪽에 있다", 1, pipe.pool.inUse)
        assertTrue(pipe.pool.hasProducerHeld)
        assertEquals(1, pipe.ring.size)
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
        val pipe = PacketPipe(n, frames)

        val released = IntArray(n)
        val writerGate = CountDownLatch(1)
        val consumed = AtomicInteger(0)
        val consumerDone = CountDownLatch(1)
        val stop = AtomicBoolean(false)
        val invariantBroken = AtomicBoolean(false)

        val consumer = Thread {
            val slot = PacketSlot()
            val held = ArrayList<Int>()
            while (!stop.get() || pipe.ring.size > 0) {
                if (pipe.poll(slot)) {
                    // 칸은 이미 돌려받았다. 버퍼는 writer 가 끝나야 한다.
                    held.add(slot.bufferIndex)
                    consumed.incrementAndGet()
                } else {
                    Thread.sleep(1)
                }
            }
            // writer 가 풀릴 때까지 붙들고 있다가 한꺼번에 놓는다.
            writerGate.await()
            held.forEach { i -> released[i]++; pipe.releaseAfterWrite(i) }
            consumerDone.countDown()
        }
        consumer.start()

        var published = 0
        var lost = 0
        repeat(n * 40) { i ->
            when (pipe.offer(i.toLong() * frames, frames, 0) {}) {
                PacketPipe.Offer.Published -> published++
                PacketPipe.Offer.Dropped -> lost++
                else -> invariantBroken.set(true)
            }
        }
        stop.set(true)
        writerGate.countDown()
        assertTrue("소비자가 끝나야 한다", consumerDone.await(30, TimeUnit.SECONDS))
        consumer.join(5_000)

        assertFalse("번호를 받고도 게시하지 못한 적이 있다 — 불변식 위반", invariantBroken.get())
        assertEquals("게시한 수와 인수한 수가 같아야 한다", published, consumed.get())
        assertEquals("남은 것이 없어야 한다", 0, pipe.ring.size)
        assertEquals("모든 버퍼가 돌아와야 한다", 0, pipe.pool.inUse)
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
     * 함께 돌자 소비자가 CPU 를 못 얻어 **8건**만 들어가 실패했다. 기계
     * 부하에 좌우되는 단언이었다.
     *
     * 그래서 **정해진 수를 채울 때까지** 넣는다. 풀이 마르면 시험은
     * 기다린다 — **오디오 스레드는 그러지 않는다.** 막지 않는 성질은
     * `풀이 마르면 막지 않고 곧바로 돌아온다` 가 따로 본다.
     */
    @Test
    fun `오래 돌려도 순서와 번호가 이어진다`() {
        val n = 8
        val pipe = PacketPipe(n, frames)

        val lastSeq = AtomicLong(-1)
        val outOfOrder = AtomicInteger(0)
        val stop = AtomicBoolean(false)
        val done = CountDownLatch(1)

        val consumer = Thread {
            val slot = PacketSlot()
            while (!stop.get() || pipe.ring.size > 0) {
                if (pipe.poll(slot)) {
                    if (slot.seq <= lastSeq.get()) outOfOrder.incrementAndGet()
                    lastSeq.set(slot.seq)
                    // 읽은 값이 온전한지 본다 — 반쯤 채워진 칸을 읽으면 어긋난다.
                    if (slot.frames != frames) outOfOrder.incrementAndGet()
                    pipe.releaseAfterWrite(slot.bufferIndex)
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
            when (pipe.offer(published.toLong() * frames, frames, 0) {}) {
                PacketPipe.Offer.Published -> published++
                PacketPipe.Offer.Dropped -> { waited++; Thread.yield() }
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
        assertEquals("모든 버퍼가 돌아와야 한다", 0, pipe.pool.inUse)
        assertEquals("마지막 번호가 맞아야 한다", (target - 1).toLong(), lastSeq.get())
        println("PIPE_LONG published=$published poolEmptyWaits=$waited")
    }

    // ------------------------------------------------------------------
    // REC01 — 소유권
    // ------------------------------------------------------------------

    /**
     * **writer 가 돌려주는 바로 그 순간** 생산자가 물러나도 잃지 않는다.
     *
     * 검증자가 요구한 시험이다 — *「writer 가 이전 버퍼를 반환하는 바로
     * 그 순간 copy 실패/close 거절을 주입한다」*.
     *
     * ## 무엇을 단언해야 결정적인가
     *
     * 처음에는 **겹친 뒤에 잃었는지만** 봤다. 그 시험은 `cancel` 을
     * `release` 로 되돌려도 **통과했다** — 생산자가 예외를 만드느라 느려
     * 두 반환이 실제로 겹치지 않았기 때문이다. 「겹치기를 바라는」 시험은
     * 결정적이지 않다.
     *
     * 그래서 **겹쳤는지와 무관한 것**을 본다: 이 라운드에서 free-ring 으로
     * 돌아온 횟수는 **정확히 1**(writer 것 하나)이어야 한다. 생산자가
     * free-ring 을 건드리면 겹치든 말든 이 수가 어긋난다.
     *
     * 겹침 자체는 예외 없는 **close 경로**로 만들어 창을 좁혀 둔다.
     */
    @Test
    fun `writer 가 돌려주는 순간 생산자가 물러나도 잃지 않는다`() {
        val rounds = 20_000
        val n = 2
        var lost = 0
        var producerTouchedFreeRing = 0

        repeat(rounds) {
            val pipe = PacketPipe(n, frames)
            val slot = PacketSlot()

            // 하나는 게시해서 writer 쪽에 보낸다.
            assertEquals(PacketPipe.Offer.Published, pipe.offer(0, frames, 0) {})
            assertTrue(pipe.poll(slot))
            val writerBuffer = slot.bufferIndex

            // 닫아 둔다. 생산자는 번호를 못 받고 물러난다 — 예외가 없어 빠르다.
            pipe.close()
            val before = pipe.pool.releasedToFree

            val gate = CyclicBarrier(2)
            val writer = Thread {
                gate.await()
                pipe.releaseAfterWrite(writerBuffer)
            }
            writer.start()

            gate.await()
            // 같은 순간 생산자는 남은 버퍼를 잡았다가 되돌린다.
            assertEquals(PacketPipe.Offer.Closed, pipe.offer(64, frames, 0) {})
            writer.join()

            // **여기가 결정적인 단언이다.** writer 하나만 free-ring 에 넣었어야 한다.
            if (pipe.pool.releasedToFree - before != 1L) producerTouchedFreeRing++

            pipe.handOverProducerHeld()
            // 둘 다 돌아왔으면 풀이 다시 가득 차 있어야 한다.
            if (drainFreeCount(pipe) != n) lost++
        }

        println("CONCURRENT_RETURN rounds=$rounds lostReturns=$lost freeRingTouched=$producerTouchedFreeRing")
        assertEquals("생산자가 free-ring 에 손을 댔다", 0, producerTouchedFreeRing)
        assertEquals("반환이 사라졌다", 0, lost)
    }

    /**
     * 물러나는 두 경로(복사 실패·닫힘) 모두 **free-ring 을 건드리지 않는다.**
     *
     * 스레드 없이, 타이밍 없이 본다. 위 시험의 라운드당 단언을 한 번에
     * 못박아 둔 것이다.
     */
    @Test
    fun `생산자가 물러날 때는 free-ring 을 건드리지 않는다`() {
        val pipe = PacketPipe(3, frames)
        val before = pipe.pool.releasedToFree

        assertEquals(
            PacketPipe.Offer.CopyFailed,
            pipe.offer(0, frames, 0) { throw IllegalStateException("복사 실패 주입") },
        )
        assertEquals("복사 실패는 free-ring 을 건드리면 안 된다", before, pipe.pool.releasedToFree)

        pipe.close()
        assertEquals(PacketPipe.Offer.Closed, pipe.offer(64, frames, 0) {})
        assertEquals("닫힘도 free-ring 을 건드리면 안 된다", before, pipe.pool.releasedToFree)

        // 넘겨줄 때에야 비로소 free-ring 으로 간다.
        pipe.handOverProducerHeld()
        assertEquals(before + 1, pipe.pool.releasedToFree)
    }

    /**
     * **같은 번호를 두 번 돌려주면 막는다.**
     *
     * 예전에는 개수만 보고 번호를 보지 않아, 둘을 빌린 상태에서 같은
     * 번호를 두 번 돌려줘도 통과했고 그 뒤 `acquire` 가 `0, 0` 을
     * 내줬다(독립 검증 REC01의 두 번째 지적).
     */
    @Test
    fun `둘을 빌린 상태에서 같은 번호를 두 번 돌려주면 막는다`() {
        val pool = BufferPool(2, frames)
        val x = pool.acquire()
        pool.acquire()
        pool.release(x)

        val e = runCatching { pool.release(x) }.exceptionOrNull()
        assertNotNull("두 번 돌려준 것을 잡아야 한다", e)

        // 같은 번호가 두 번 나오면 안 된다.
        val a = pool.acquire()
        val b = pool.acquire()
        assertEquals("남은 것은 하나뿐이다", -1, b)
        assertEquals(x, a)
    }

    @Test
    fun `빌리지 않은 버퍼는 되돌리지도 돌려주지도 못한다`() {
        val pool = BufferPool(2, frames)
        assertNotNull(runCatching { pool.release(0) }.exceptionOrNull())
        assertNotNull(runCatching { pool.cancel(0) }.exceptionOrNull())
    }

    @Test
    fun `되돌린 버퍼를 다음에 다시 쓴다`() {
        val pool = BufferPool(1, frames)
        val a = pool.acquire()
        pool.cancel(a)
        assertEquals("되돌린 것을 그대로 다시 준다", a, pool.acquire())
        assertEquals("하나뿐이므로 더는 없다", -1, pool.acquire())
    }

    @Test
    fun `한 번에 하나만 되돌려 쥔다`() {
        val pool = BufferPool(2, frames)
        val a = pool.acquire()
        val b = pool.acquire()
        pool.cancel(a)
        assertNotNull("둘을 동시에 쥐면 막아야 한다", runCatching { pool.cancel(b) }.exceptionOrNull())
    }

    // ------------------------------------------------------------------

    /**
     * **가득 찬 링은 덮어쓰지 않고 거절한다.**
     *
     * 정상 흐름에서는 여기 닿지 않는다 — 버퍼를 잡았으면 칸이 반드시
     * 있기 때문이다. 그래서 풀을 거치는 시험만으로는 이 검사를 지워도
     * 아무도 못 잡는다(되돌려 보고 알았다). **안전망은 따로 두드린다.**
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
    fun `말이 안 되는 크기는 만들지 않는다`() {
        assertNotNull(runCatching { BufferPool(0, frames) }.exceptionOrNull())
        assertNotNull(runCatching { BufferPool(4, 0) }.exceptionOrNull())
        assertNotNull(runCatching { PacketRing(0) }.exceptionOrNull())
        assertNotNull(runCatching { BufferPool(2, frames).release(5) }.exceptionOrNull())
    }

    /** 풀에 남아 있는 빈 버퍼 수. 전부 꺼내 세고 그대로 둔다. */
    private fun drainFreeCount(pipe: PacketPipe): Int = drainFreeCount(pipe.pool)

    private fun drainFreeCount(pool: BufferPool): Int {
        var n = 0
        while (pool.acquire() >= 0) n++
        return n
    }
}
