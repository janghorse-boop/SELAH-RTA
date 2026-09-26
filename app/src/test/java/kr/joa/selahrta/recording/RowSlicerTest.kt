package kr.joa.selahrta.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 덩어리를 경계에서 쪼개는 셈.
 *
 * [RowAggregator] 가 거부하는 조각을 **여기서 없애는 것**이 이 클래스의
 * 일이다. 그래서 시험이 확인하는 것도 그 계약 그대로다 — 행을 걸치지
 * 않을 것, epoch 을 걸치지 않을 것, 뒤로 가거나 겹치지 않을 것,
 * 그리고 **프레임을 하나도 잃지 않을 것**.
 */
class RowSlicerTest {

    /** 48kHz · 500ms 행 = 24,000 프레임. 실제로 쓰는 값이다. */
    private val perRow = 24_000L

    private fun epochs(vararg starts: Long): EpochTable {
        val t = EpochTable()
        starts.forEachIndexed { i, f ->
            t.add(RecordingEpoch(i, f, 100.0 + i, false, 60_000))
        }
        return t
    }

    private fun assertContract(slices: List<RowSlicer.Slice>, epochs: EpochTable) {
        var prevEnd = -1L
        for (s in slices) {
            assertTrue("조각이 비었다", s.frames > 0)
            assertTrue("뒤로 가거나 겹친다", s.frameStart >= prevEnd)
            val last = s.frameStart + s.frames - 1
            assertEquals(
                "행을 걸친다: ${s.frameStart}..$last",
                s.frameStart / perRow,
                last / perRow,
            )
            assertEquals("epoch 을 걸친다", epochs.idAt(s.frameStart), epochs.idAt(last))
            assertEquals("가리킨 epoch 이 다르다", s.epochId, epochs.idAt(s.frameStart))
            prevEnd = s.frameStart + s.frames
        }
    }

    @Test
    fun `행 안에 들어가면 쪼개지 않는다`() {
        val e = epochs(0)
        val s = RowSlicer.slice(1000, 3000, perRow, e)
        assertEquals(1, s.size)
        assertEquals(RowSlicer.Slice(1000, 3000, 0), s[0])
        assertContract(s, e)
    }

    /**
     * **여덟에 하나쯤 걸친다.** 60ms 덩어리 · 500ms 행이면 그렇다.
     * 드물다고 안 다루면 예배 두 시간에 천 번 틀린다.
     */
    @Test
    fun `행 경계를 걸치면 둘로 쪼갠다`() {
        val e = epochs(0)
        val s = RowSlicer.slice(perRow - 1000, 3000, perRow, e)
        assertEquals(2, s.size)
        assertEquals(RowSlicer.Slice(perRow - 1000, 1000, 0), s[0])
        assertEquals(RowSlicer.Slice(perRow, 2000, 0), s[1])
        assertContract(s, e)
    }

    @Test
    fun `여러 행을 지나면 행마다 쪼갠다`() {
        val e = epochs(0)
        val s = RowSlicer.slice(perRow - 100, (perRow * 2 + 200).toInt(), perRow, e)
        assertEquals(4, s.size)
        assertContract(s, e)
        assertEquals("잃은 프레임이 없다", perRow * 2 + 200, s.sumOf { it.frames }.toLong())
    }

    /**
     * **epoch 경계도 행 경계와 똑같이 끊는다.**
     *
     * 안 끊으면 보정이 바뀌는 자리에서 옛 보정을 받은 값이 새 epoch 의
     * 행에 들어간다 — 검증자의 `EPOCH_CROSS` 가 그것이다.
     */
    @Test
    fun `epoch 경계를 걸치면 거기서 끊는다`() {
        val e = epochs(0, 5000)
        val s = RowSlicer.slice(4000, 3000, perRow, e)
        assertEquals(2, s.size)
        assertEquals(RowSlicer.Slice(4000, 1000, 0), s[0])
        assertEquals(RowSlicer.Slice(5000, 2000, 1), s[1])
        assertContract(s, e)
    }

    @Test
    fun `행과 epoch 경계가 겹쳐도 한 번만 끊는다`() {
        val e = epochs(0, perRow)
        val s = RowSlicer.slice(perRow - 500, 1000, perRow, e)
        assertEquals(2, s.size)
        assertEquals(perRow, s[1].frameStart)
        assertEquals(1, s[1].epochId)
        assertContract(s, e)
    }

    /** 경계 여럿이 한 덩어리 안에 들어와도 차례로 끊는다. */
    @Test
    fun `한 덩어리에 경계가 여럿이어도 견딘다`() {
        val e = epochs(0, 1000, 2000, 3000)
        val s = RowSlicer.slice(500, 3000, perRow, e)
        assertEquals(4, s.size)
        assertContract(s, e)
        assertEquals(3000, s.sumOf { it.frames })
    }

    /**
     * **epoch 이 없는 구간은 버린다.**
     *
     * 보정이 무엇인지 모르는 값을 기록에 넣을 수 없다. 그 자리는
     * 집계기가 `missing` 으로 채운다.
     */
    @Test
    fun `epoch 이 없는 앞부분은 버린다`() {
        val e = epochs(1000)
        val s = RowSlicer.slice(0, 3000, perRow, e)
        assertEquals(1, s.size)
        assertEquals(1000, s[0].frameStart)
        assertEquals(2000, s[0].frames)
        assertContract(s, e)
    }

    @Test
    fun `epoch 이 하나도 없으면 아무것도 내지 않는다`() {
        assertTrue(RowSlicer.slice(0, 3000, perRow, EpochTable()).isEmpty())
    }

    @Test
    fun `빈 덩어리는 아무것도 내지 않는다`() {
        assertTrue(RowSlicer.slice(1000, 0, perRow, epochs(0)).isEmpty())
    }

    /**
     * 쪼갠 조각을 **그대로 집계기에 넣을 수 있어야 한다.** 이 시험이
     * 두 클래스의 계약을 실제로 맞춰 본다.
     */
    @Test
    fun `쪼갠 조각을 집계기가 받아들인다`() {
        val e = epochs(0, perRow + 5000)
        val agg = RowAggregator(48_000, e)
        var frame = 0L
        // 60ms 덩어리(2880 프레임)를 40번 — 두 행을 넘기고 epoch 도 바뀐다.
        repeat(40) {
            RowSlicer.slice(frame, 2880, agg.framesPerRow, e).forEach { s ->
                agg.add(
                    s.frameStart,
                    s.frames,
                    s.epochId,
                    RowSample(-30.0, -25.0, -12.0, false, FloatArray(31) { -40f }),
                )
            }
            frame += 2880
        }
        val rows = agg.finish()
        assertTrue("행이 나와야 한다", rows.isNotEmpty())
        assertEquals("행 번호가 0 부터 이어진다", rows.indices.toList(), rows.map { it.rowIndex })
    }
}
