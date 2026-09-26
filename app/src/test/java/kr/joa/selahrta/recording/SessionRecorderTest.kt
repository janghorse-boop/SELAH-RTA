package kr.joa.selahrta.recording

import kr.joa.selahrta.domain.ChurchSegment
import kr.joa.selahrta.dsp.MultiWeightEngine
import kr.joa.selahrta.dsp.RtaEngine
import kr.joa.selahrta.dsp.ThirdOctave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 측정을 행으로 남기는 자리(Phase 10).
 *
 * **여기서 지키는 것은 시간이다.** 행 하나는 500ms 이고, 그 행의 값은
 * **그 행의 소리만** 담아야 한다. 어긋나면 나중에 「그때 88dB 였다」를
 * 되돌릴 방법이 없다.
 */
class SessionRecorderTest {

    private val fs = 48_000
    private val blockFrames = 2_880 // 60ms — 실제 캡처와 같은 크기

    private fun recorder(offsetDb: Double = 120.0) = SessionRecorder(
        id = "s1",
        nominalSampleRate = fs,
        startOffsetDb = offsetDb,
        startReferenceOnly = true,
        startLeqWindowMs = 60_000,
    )

    private fun engines() =
        MultiWeightEngine(fs, kr.joa.selahrta.dsp.TimeWeight.Fast) to RtaEngine(fs)

    /** 60ms 덩어리 한 개 분량의 소리. */
    private fun block(level: Double, at: Int): FloatArray {
        return FloatArray(blockFrames) {
            val t = (at * blockFrames + it).toDouble() / fs
            (level * sin(2 * PI * 1000.0 * t)).toFloat()
        }
    }

    private fun feed(r: SessionRecorder, blocks: Int, level: Double = 0.2) {
        val (spl, rta) = engines()
        repeat(blocks) { i -> r.onBlock(block(level, i), blockFrames, false, spl, rta) }
    }

    @Test
    fun `행이 500ms 마다 하나씩 생긴다`() {
        val r = recorder()
        // 2초 = 4행. 60ms 덩어리 34개면 2.04초다.
        feed(r, 34)
        val s = r.finish()
        assertEquals("행 번호가 0 부터 이어진다", s.rows.indices.toList(), s.rows.map { it.rowIndex })
        assertEquals("2.04초 → 4행(마지막 행은 열린 채 닫힌다)", 5, s.rows.size)
        assertEquals("길이", 2_040L, s.durationMs)
    }

    /**
     * **행 경계를 걸친 덩어리가 두 행에 나뉘어 들어간다.**
     *
     * 이것이 쪼개는 까닭이다. 안 쪼개면 덩어리 값 하나가 한 행에
     * 통째로 들어가, 최대 60ms 가 옆 행에 섞인다.
     */
    @Test
    fun `경계를 걸친 덩어리도 잃지 않는다`() {
        val r = recorder()
        val (spl, rta) = engines()
        // 24,000 프레임(한 행)을 60ms 로 나누면 8.33개다 — 아홉째가 걸친다.
        repeat(9) { i -> r.onBlock(block(0.2, i), blockFrames, false, spl, rta) }
        val s = r.finish()
        assertEquals("두 행이 나와야 한다", 2, s.rows.size)
        assertFalse("첫 행이 비었다", s.rows[0].missing)
        assertFalse("둘째 행이 비었다", s.rows[1].missing)
    }

    @Test
    fun `읽기 오류는 행을 채우지 않고 세어 둔다`() {
        val r = recorder()
        val (spl, rta) = engines()
        assertNull("버린 덩어리에서 값이 나왔다", r.onBlock(FloatArray(0), 0, false, spl, rta))
        repeat(9) { i -> r.onBlock(block(0.2, i), blockFrames, false, spl, rta) }
        val s = r.finish()
        assertEquals(1, s.droppedPackets)
        assertTrue("행은 그대로 쌓여야 한다", s.rows.isNotEmpty())
    }

    @Test
    fun `보정이 바뀌면 새 구간이 열린다`() {
        val r = recorder(offsetDb = 120.0)
        feed(r, 9)
        assertTrue(r.noteEpoch(100.0, referenceOnly = false, leqWindowMs = 60_000))
        feed(r, 9)
        val s = r.finish()
        assertEquals("구간이 둘이어야 한다", 2, s.epochs.size)
        assertEquals(100.0, s.epochs[1].calibrationOffsetDb, 0.0)
        assertTrue("사건으로도 남아야 한다", s.events.any { it.kind == SessionEventKind.CalibrationChange })
    }

    /** 같은 값으로 다시 부르면 구간을 늘리지 않는다. */
    @Test
    fun `바뀌지 않으면 구간을 늘리지 않는다`() {
        val r = recorder(offsetDb = 120.0)
        feed(r, 9)
        repeat(5) { r.noteEpoch(120.0, referenceOnly = true, leqWindowMs = 60_000) }
        assertEquals(1, r.finish().epochs.size)
    }

    @Test
    fun `구간 바뀜이 시각과 함께 남는다`() {
        val r = recorder()
        feed(r, 9)
        r.note(SessionEventKind.SegmentChange, "찬양", ChurchSegment.Worship)
        val s = r.finish()
        val e = s.events.first { it.kind == SessionEventKind.SegmentChange }
        assertEquals(ChurchSegment.Worship, e.segment)
        assertEquals("9덩어리 = 540ms", 540L, e.atMs)
    }

    @Test
    fun `밴드가 31개로 들어간다`() {
        val r = recorder()
        feed(r, 20)
        val s = r.finish()
        assertTrue(s.rows.all { it.bands.size == ThirdOctave.BAND_COUNT })
    }

    /**
     * **첫 FFT 가 차기 전에도 행이 나온다.**
     *
     * 4096점이 차려면 85ms 가 걸리는데 행은 500ms 다. 그 사이의 행을
     * 비워 두면 기록이 늘 앞이 잘린다 — 조용한 값으로 채운다.
     */
    @Test
    fun `첫 FFT 전에도 행이 나온다`() {
        val r = recorder()
        val (spl, rta) = engines()
        r.onBlock(block(0.2, 0), blockFrames, false, spl, rta)
        val s = r.finish()
        assertEquals(1, s.rows.size)
        assertEquals(ThirdOctave.BAND_COUNT, s.rows[0].bands.size)
    }

    @Test
    fun `끝낸 뒤에는 더 넣을 수 없다`() {
        val r = recorder()
        feed(r, 5)
        r.finish()
        val (spl, rta) = engines()
        var blocked = false
        try {
            r.onBlock(block(0.2, 0), blockFrames, false, spl, rta)
        } catch (e: IllegalStateException) {
            blocked = true
        }
        assertTrue("끝낸 뒤에도 받았다", blocked)
    }

    /** 잰 길이는 **프레임으로 센다.** 벽시계가 뒤로 가도 흔들리지 않는다. */
    @Test
    fun `길이를 프레임으로 센다`() {
        val r = recorder()
        assertEquals(0L, r.elapsedMs)
        feed(r, 10)
        assertEquals(600L, r.elapsedMs)
        assertNotNull(r.finish())
    }
}
