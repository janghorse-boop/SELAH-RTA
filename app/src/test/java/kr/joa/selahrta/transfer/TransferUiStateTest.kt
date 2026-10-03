package kr.joa.selahrta.transfer

import kr.joa.selahrta.dsp.TransferResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TF 설계 6·9·10장의 순수 상태 — 30~32회차 반례를 그대로 넣는다.
 */
class TransferUiStateTest {

    private val bins = displayBins(48_000, 8192)
    private val floor = -60.0

    private fun result(
        mag: (Int) -> Double = { 0.0 },
        coh: (Int) -> Double = { 0.9 },
        valid: (Int) -> Boolean = { it > 0 },
        averages: Int = 16,
    ) = TransferResult(
        magnitudeDb = DoubleArray(4097) { mag(it) },
        coherence = DoubleArray(4097) { coh(it) },
        valid = BooleanArray(4097) { valid(it) },
        averages = averages,
    )

    // ── 표시 대역 ───────────────────────────────────────────────────────

    @Test
    fun `48k FFT 8192 의 표시 대역은 4 부터 3413 까지 3410칸`() {
        assertEquals(4, bins.first)
        assertEquals(3413, bins.last)
        assertEquals(3410, bins.last - bins.first + 1)
    }

    // ── 그래프 상태 (R31-04 · R30-05) ───────────────────────────────────

    @Test
    fun `크기가 모두 마이너스 무한이고 코히런스가 유한하면 코히런스만 그린다`() {
        val g = buildTransferGraphs(result(mag = { Double.NEGATIVE_INFINITY }, coh = { 0.0 }), bins, floor)
        assertEquals(3410, g.referenceValid)
        assertTrue(g.magnitudeEmpty)
        assertFalse(g.coherenceEmpty)
        assertFalse(g.nothingDrawable)
        assertEquals(3410, g.hidden.nonFinite)
        val s = g.coherenceSummary as CoherenceSummary.Median
        assertEquals(0.0, s.median, 0.0)
        assertEquals(3410, s.bins)
    }

    @Test
    fun `크기가 모두 축 아래면 크기만 비고 코히런스는 남는다`() {
        val g = buildTransferGraphs(result(mag = { -120.0 }), bins, floor)
        assertTrue(g.magnitudeEmpty)
        assertFalse(g.coherenceEmpty)
        assertEquals(3410, g.hidden.belowAxis)
    }

    @Test
    fun `둘 다 그릴 칸이 없으면 그렇다고 한다`() {
        val g = buildTransferGraphs(result(mag = { Double.NaN }, coh = { Double.NaN }), bins, floor)
        assertTrue(g.nothingDrawable)
        assertTrue(g.coherenceSummary is CoherenceSummary.NoBins)
    }

    @Test
    fun `기준 유효 칸이 없으면 V 가 0`() {
        val g = buildTransferGraphs(result(valid = { false }), bins, floor)
        assertEquals(0, g.referenceValid)
        assertEquals(3410, g.hidden.invalid)
        assertTrue(g.nothingDrawable)
    }

    @Test
    fun `유효 무효 유효는 두 선분이다 — 남은 점을 하나로 잇지 않는다`() {
        val g = buildTransferGraphs(result(valid = { it != 100 }), bins, floor)
        assertEquals(2, g.magnitude.segments.size)
        assertEquals(99, g.magnitude.segments[0].bins.last())
        assertEquals(101, g.magnitude.segments[1].bins.first())
        assertEquals(1, g.hidden.invalid)
    }

    @Test
    fun `마이너스 무한 한 칸에서 크기 선분이 끊기고 코히런스는 이어진다`() {
        val g = buildTransferGraphs(result(mag = { if (it == 200) Double.NEGATIVE_INFINITY else 0.0 }), bins, floor)
        assertEquals(2, g.magnitude.segments.size)
        assertEquals(1, g.coherence!!.segments.size)
    }

    // ── 코히런스 진단 (R30-04) ──────────────────────────────────────────

    /** 30회차 반례 — 전체 유효 칸 중앙값은 0.99 인데 표시 대역은 0.10. 표시 대역 값을 낸다. */
    @Test
    fun `코히런스 중앙값은 표시 대역에서 센다`() {
        val coh: (Int) -> Double = { k -> if (k in 4..2000) 0.10 else 0.99 }
        val g = buildTransferGraphs(result(coh = coh), bins, floor)
        val s = g.coherenceSummary as CoherenceSummary.Median
        // 표시 대역 3,410칸 중 1,997칸이 0.10 → 중앙값 0.10
        assertEquals(0.10, s.median, 1e-12)
    }

    @Test
    fun `평균이 모자라면 코히런스를 숨기고 8~15 면 안정화 중`() {
        val hidden = buildTransferGraphs(result(averages = 7), bins, floor)
        assertNull(hidden.coherence)
        assertEquals(CoherenceSummary.NotEnoughAverages, hidden.coherenceSummary)
        val stab = buildTransferGraphs(result(averages = 8), bins, floor)
        assertTrue((stab.coherenceSummary as CoherenceSummary.Median).stabilizing)
    }

    // ── 박자 (R31-03 · R32-04) ──────────────────────────────────────────

    private fun ticker() = TransferTicker()
    private fun m(end: Long, found: Boolean = true) = TickResult.Measured(end, found)

    @Test
    fun `자료 부족과 자리 잃음이 번갈아도 시도 3번이면 멈춘다`() {
        val t = ticker()
        repeat(2) {
            assertEquals(TickAction.Wait, t.step(TickResult.Insufficient))
            assertEquals(TickAction.Resync, t.step(TickResult.Retention))
        }
        t.step(TickResult.Insufficient)
        assertEquals(TickAction.Stop(TickReason.RecoveryLimit), t.step(TickResult.Retention))
    }

    @Test
    fun `바쁨이 끼어도 시도 횟수는 지워지지 않는다`() {
        val t = ticker()
        t.step(TickResult.Retention)
        t.step(TickResult.Busy)
        t.step(TickResult.Retention)
        assertEquals(2, t.recoveryAttempts)
    }

    @Test
    fun `복구 성공 뒤 다음 실패는 1부터`() {
        val t = ticker()
        t.step(TickResult.Retention)
        t.step(TickResult.Retention)
        assertEquals(TickAction.Publish, t.step(m(100)))
        assertEquals(0, t.recoveryAttempts)
        assertEquals(TickAction.Resync, t.step(TickResult.Retention))
        assertEquals(1, t.recoveryAttempts)
    }

    /** 32회차 반례 — 모으는 구간의 못 맞춤이 셈을 안 올려 100박자에도 0 이던 것. */
    @Test
    fun `모으는 구간에서 못 맞춤만 10번이면 멈춘다`() {
        val t = ticker()
        var last: TickAction = TickAction.Wait
        for (i in 1..10) last = t.step(m(i * 1000L, found = false))
        assertTrue(last is TickAction.Stop)
    }

    @Test
    fun `같은 창의 못 맞춤은 창이 나아가지 않은 것으로 센다`() {
        val t = ticker()
        assertEquals(TickAction.Publish, t.step(m(100)))
        assertEquals(TickAction.KeepOld(1), t.step(m(100, found = false)))
        assertEquals(TickAction.KeepOld(2), t.step(m(100, found = true)))
        assertEquals(TickAction.Clear(TickReason.NoNewData), t.step(TickResult.Busy))
    }

    @Test
    fun `게시 뒤 못 맞춤은 곡선을 지우고 자료가 흐르므로 새 자료 없음은 0`() {
        val t = ticker()
        t.step(m(100))
        assertEquals(TickAction.Clear(TickReason.NotFound), t.step(m(200, found = false)))
        // 이어서 바쁨 두 번은 아직 3 이 아니다.
        assertEquals(TickAction.KeepOld(1), t.step(TickResult.Busy))
    }

    @Test
    fun `게시가 끼면 못 맞춤 셈이 0 으로`() {
        val t = ticker()
        for (i in 1..9) t.step(m(i * 100L, found = false))
        assertEquals(TickAction.Publish, t.step(m(1000)))
        for (i in 11..19) assertTrue(t.step(m(i * 100L, found = false)) !is TickAction.Stop)
    }

    @Test
    fun `새 epoch 는 창 기준을 처음으로 — 옛 창보다 작은 새 창도 나아간 것으로 본다`() {
        val t = ticker()
        t.step(m(50_000))
        t.newEpoch()
        assertEquals(TickAction.Publish, t.step(m(1_000)))
    }

    @Test
    fun `옛 결과는 아무것도 세지 않는다`() {
        val t = ticker()
        repeat(20) { assertEquals(TickAction.Wait, t.step(TickResult.Stale)) }
        assertEquals(TickAction.Publish, t.step(m(1)))
    }

    @Test
    fun `새 세션은 시도 횟수까지 0`() {
        val t = ticker()
        t.step(TickResult.Retention)
        t.newSession()
        assertEquals(0, t.recoveryAttempts)
    }
}
