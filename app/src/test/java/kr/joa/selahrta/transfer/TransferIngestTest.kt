package kr.joa.selahrta.transfer

import kr.joa.selahrta.audio.AudioBlock
import kr.joa.selahrta.dsp.BlockStats
import kr.joa.selahrta.dsp.TransferEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * TF 설계 3.3 의 「한 경계」와 9장의 결과 창 클리핑(32회차 R32-03 · 33회차 R33-04 · 30회차 R30-04).
 */
class TransferIngestTest {

    private fun block(seq: Long, frames: Int = 1024) =
        AudioBlock(FloatArray(frames) { 0.1f }, frames, 48_000, 0L, seq)

    private val quiet = BlockStats(0.1, 0.05, 0)
    private val loud = BlockStats(1.0, 0.7, 3)

    // ── 받는 조건 ───────────────────────────────────────────────────────

    @Test
    fun `무장 전에는 아무것도 받지 않는다`() {
        val engine = TransferEngine()
        val ingest = TransferIngest(engine)
        assertFalse(ingest.offerMeasurement(1, block(5), quiet))
        ingest.offerReference(1, FloatArray(10), 0, 10)
        assertEquals(0L, engine.measurementCount)
        assertEquals(0L, engine.referenceCount)
    }

    @Test
    fun `경계 이하의 읽기 번호와 다른 캡처는 버린다`() {
        val engine = TransferEngine()
        val ingest = TransferIngest(engine)
        ingest.arm(session = 7, captureId = 3, readSeqFloor = 10)
        assertFalse("읽는 중이던(경계 이하) 블록", ingest.offerMeasurement(3, block(10), quiet))
        assertFalse("옛 캡처", ingest.offerMeasurement(2, block(11), quiet))
        assertTrue(ingest.offerMeasurement(3, block(11), quiet))
        assertEquals(1024L, engine.measurementCount)
    }

    @Test
    fun `다른 세션의 기준 표본은 버린다`() {
        val engine = TransferEngine()
        val ingest = TransferIngest(engine)
        ingest.arm(session = 7, captureId = 3, readSeqFloor = 0)
        ingest.offerReference(6, FloatArray(100), 0, 100)
        assertEquals(0L, engine.referenceCount)
        ingest.offerReference(7, FloatArray(100), 0, 100)
        assertEquals(100L, engine.referenceCount)
    }

    @Test
    fun `무장은 엔진을 비우고 무장을 풀면 더 받지 않는다`() {
        val engine = TransferEngine()
        val ingest = TransferIngest(engine)
        ingest.arm(1, 1, 0)
        ingest.offerMeasurement(1, block(1), quiet)
        ingest.arm(1, 1, 5)
        assertEquals("다시 무장하면 엔진이 빈다", 0L, engine.measurementCount)
        ingest.disarm()
        assertFalse(ingest.offerMeasurement(1, block(9), quiet))
    }

    /**
     * 두 스레드 — 캡처 스레드는 번호를 올려 가며 블록을 넣고, 주 스레드는 그 사이에 그 순간의 번호로 다시
     * 무장한다. 다시 무장한 뒤 엔진에 든 표본은 **모두 경계보다 큰 번호의 블록**에서 왔어야 한다.
     *
     * 번호 올리기는 잠금 밖이라 「경계 이하 번호를 예약한 블록이 무장 뒤에 도착하는」 경쟁은 그대로 일어난다. 다만
     * **넣기와 그 기록**은 시험의 잠금으로 묶고 무장도 그 안에서 한다 — 그러지 않으면 넣기와 기록 사이에 무장이 끼어
     * 무장 뒤 블록이 「앞」으로 세어진다(38회차 기준 실행에서 블록 4개 차이로 실패한 것은 이 시험 쪽 경쟁이었다).
     */
    @Test
    fun `다시 무장하는 동안 들어오는 블록이 경계를 넘지 않는다`() {
        val engine = TransferEngine()
        val ingest = TransferIngest(engine)
        ingest.arm(1, 1, 0)
        val seq = AtomicLong()
        val gate = Any()
        val after = ArrayList<Long>()
        val run = AtomicBoolean(true)
        val started = CountDownLatch(1)
        val t = Thread {
            started.countDown()
            while (run.get()) {
                val s = seq.incrementAndGet()
                synchronized(gate) {
                    if (ingest.offerMeasurement(1, block(s, 64), quiet)) after += s
                }
            }
        }
        t.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        Thread.sleep(20)
        val floor = seq.get()
        synchronized(gate) {
            after.clear()
            ingest.arm(1, 1, floor)
        }
        Thread.sleep(20)
        run.set(false)
        t.join(5_000)

        assertTrue("무장 뒤 받은 블록이 있어야 시험이 뜻이 있다", after.isNotEmpty())
        assertTrue("무장 뒤 받은 블록은 모두 경계($floor)보다 크다", after.all { it > floor })
        assertEquals("엔진에는 무장 뒤 받은 블록만 있다", after.size * 64L, engine.measurementCount)
    }

    // ── 결과 창 클리핑 ──────────────────────────────────────────────────

    @Test
    fun `결과 창 앞쪽에 든 넘친 블록을 잡는다`() {
        val engine = TransferEngine()
        val ingest = TransferIngest(engine)
        ingest.arm(1, 1, 0)
        // 0~1024 넘침, 그 뒤 100블록은 조용함 → 끝 = 1024 * 101
        ingest.offerMeasurement(1, block(1), loud)
        for (s in 2L..101L) ingest.offerMeasurement(1, block(s), quiet)
        val end = 1024L * 101
        assertEquals("창이 처음까지 닿으면 넘침", ClipVerdict.Clipped, ingest.clipVerdict(end, end))
        assertEquals("창이 넘친 블록 뒤에서 시작하면 없음", ClipVerdict.None, ingest.clipVerdict(end, end - 1024))
    }

    @Test
    fun `기록이 사라진 창은 모름이다`() {
        val ledger = ClipLedger(capacity = 4)
        for (k in 0 until 10) ledger.add(k * 100L, (k + 1) * 100L, wasClipped = false)
        // 남은 것은 600~1000. 창이 200 부터면 앞쪽 기록이 없다.
        assertEquals(ClipVerdict.Unknown, ledger.verdict(windowEnd = 1000, span = 800))
        assertEquals(ClipVerdict.None, ledger.verdict(windowEnd = 1000, span = 400))
    }

    @Test
    fun `기록이 없으면 모름이다`() {
        assertEquals(ClipVerdict.Unknown, ClipLedger().verdict(1000, 500))
    }
}
