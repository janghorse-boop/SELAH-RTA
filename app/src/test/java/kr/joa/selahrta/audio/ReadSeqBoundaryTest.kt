package kr.joa.selahrta.audio

import kr.joa.selahrta.dsp.BlockStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 읽기 번호는 **읽기 전에** 예약된다(TF 설계 3.3, 33회차 R33-04).
 *
 * 4판은 「넘긴 블록」의 번호를 스냅샷에 적었다 — 이미 읽었지만 아직 안 넘긴 블록이 스냅샷 뒤에 새 번호를
 * 받아 새 무장에 들어갔다. 예약 번호를 적으면, 통지가 왔을 때 **읽는 중이던 블록**도 스냅샷 번호 이하라
 * 경계 앞으로 간다.
 */
class ReadSeqBoundaryTest {

    private class SeqCallbacks : CaptureLoopCallbacks {
        val seqs: MutableList<Long> = Collections.synchronizedList(ArrayList())
        val delivered = CountDownLatch(1)
        override fun onBlock(block: AudioBlock, stats: BlockStats) {
            if (block.frames > 0) {
                seqs += block.readSeq
                delivered.countDown()
            }
        }
        override fun onRouteConfirmed(device: InputDeviceInfo) = Unit
        override fun onEnded(end: CaptureEnd) = Unit
    }

    @Test
    fun `블록마다 1 부터 이어지는 읽기 번호가 붙는다`() {
        val running = AtomicBoolean(true)
        val seq = AtomicLong()
        val rec = FakeRecorder(listOf(64, 64, 64, -3))
        val cb = SeqCallbacks()
        runCaptureLoop(rec, 48_000, running, cb, { true }, { 0L }, 64, seq)
        assertEquals(listOf(1L, 2L, 3L), cb.seqs.toList())
    }

    @Test
    fun `번호를 주지 않으면 0 이다 — 기존 소스는 그대로`() {
        val running = AtomicBoolean(true)
        val rec = FakeRecorder(listOf(64, -3))
        val cb = SeqCallbacks()
        runCaptureLoop(rec, 48_000, running, cb, { true }, { 0L }, 64)
        assertEquals(listOf(0L), cb.seqs.toList())
    }

    /**
     * 읽기가 막혀 있는 동안(읽는 중) 스냅샷을 뜬다. 그 읽기의 번호는 이미 예약됐으므로 스냅샷 번호 이하다 —
     * `blockSeq > readSeqAtSnapshot` 만 받는 쪽은 그 블록을 버린다.
     */
    @Test
    fun `읽는 중이던 블록은 그때 뜬 스냅샷 번호 이하다`() {
        val running = AtomicBoolean(true)
        val seq = AtomicLong()
        val rec = FakeRecorder(listOf(64, 64, -3))
        val cb = SeqCallbacks()
        val gate = CountDownLatch(1)
        rec.gate = gate
        val t = Thread { runCaptureLoop(rec, 48_000, running, cb, { true }, { 0L }, 64, seq) }
        t.start()
        assertTrue("첫 읽기가 막힌 자리에 닿아야 한다", rec.reachedGate.await(5, TimeUnit.SECONDS))

        // 통지가 와서 스냅샷을 뜬다 — 읽는 중이던 1번은 이미 예약됐다.
        val readSeqAtSnapshot = seq.get()
        assertEquals(1L, readSeqAtSnapshot)

        rec.gate = null
        gate.countDown()
        t.join(5_000)

        val first = cb.seqs.first()
        assertTrue("읽는 중이던 블록($first)은 스냅샷 번호($readSeqAtSnapshot) 이하", first <= readSeqAtSnapshot)
        assertTrue("그 뒤 블록은 스냅샷 번호보다 크다", cb.seqs.drop(1).all { it > readSeqAtSnapshot })
    }
}
