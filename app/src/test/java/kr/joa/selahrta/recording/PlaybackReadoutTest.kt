package kr.joa.selahrta.recording

import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **소리를 들으며 그 순간의 값을 함께 본다**(담당자 지시 2026-09-27).
 *
 * 여기서 지키는 두 줄:
 *
 * 1. **옆 행을 가져다 놓지 않는다.** 그 자리에 행이 없으면 없다고
 *    적는다 — 가져다 놓으면 소리와 숫자가 어긋난 채 그럴듯해 보인다.
 * 2. **행마다 제 보정을 건다.** 겉장의 한 값을 모든 행에 걸면 보정이
 *    바뀐 뒤 구간이 조용히 틀린다.
 */
class PlaybackReadoutTest {

    private fun row(index: Int, epoch: Int, raw: Double, missing: Boolean = false) = TimelineRow(
        rowIndex = index,
        currentRaw = raw,
        currentEpoch = if (missing) TimelineFormat.EPOCH_NONE else epoch,
        maxRaw = raw + 2.0,
        maxEpoch = if (missing) TimelineFormat.EPOCH_NONE else epoch,
        peakRaw = raw + 5.0,
        peakEpoch = if (missing) TimelineFormat.EPOCH_NONE else epoch,
        clipped = false,
        missing = missing,
        bands = FloatArray(ThirdOctave.BAND_COUNT) { raw.toFloat() },
    )

    private fun meta(
        epochs: List<RecordingEpoch> = emptyList(),
        offset: Double = 110.0,
    ) = SessionMeta(
        id = "s1",
        startedAtEpochMs = 0L,
        endedAtEpochMs = 0L,
        durationMs = 2_000L,
        deviceKey = "k",
        deviceLabel = "SM-S918N",
        micKind = MicKind.BuiltIn,
        sampleRate = 48_000,
        encoding = "Float",
        channelCount = 1,
        channelIndex = 0,
        calibrationOffsetDb = offset,
        referenceOnly = false,
        curveApplied = false,
        weighting = Weighting.A,
        timeWeight = TimeWeight.Fast,
        leqWindowMs = 10_000L,
        leqDb = 70.0,
        minDb = 60.0,
        maxDb = 80.0,
        peakDb = 90.0,
        epochs = epochs,
    )

    // ── 자리를 맞게 찾는가 ────────────────────────────────

    @Test
    fun `시각으로 그 자리의 행을 찾는다`() {
        val rows = List(4) { row(it, 0, -40.0 + it) }
        val m = meta()
        assertEquals(0, playbackValuesAt(m, rows, 0L)!!.rowIndex)
        assertEquals(0, playbackValuesAt(m, rows, 499L)!!.rowIndex)
        assertEquals(1, playbackValuesAt(m, rows, 500L)!!.rowIndex)
        assertEquals(3, playbackValuesAt(m, rows, 1_750L)!!.rowIndex)
    }

    @Test
    fun `음수 시각은 첫 행으로 본다`() {
        val rows = listOf(row(0, 0, -40.0))
        assertEquals(0, playbackValuesAt(meta(), rows, -100L)!!.rowIndex)
    }

    /** 소리가 표보다 길면 뒤쪽에는 잰 값이 없다. **짐작하지 않는다.** */
    @Test
    fun `표 끝을 넘어가면 없다고 한다`() {
        val rows = listOf(row(0, 0, -40.0))
        assertNull(playbackValuesAt(meta(), rows, 5_000L))
    }

    @Test
    fun `행이 없으면 없다고 한다`() {
        assertNull(playbackValuesAt(meta(), emptyList(), 0L))
    }

    // ── 보정을 행마다 거는가 ─────────────────────────────

    @Test
    fun `겉장 보정이 걸린다`() {
        val v = playbackValuesAt(meta(offset = 110.0), listOf(row(0, 0, -40.0)), 0L)!!
        assertEquals(70.0, v.currentDb!!, 1e-9)
        assertEquals(72.0, v.maxDb!!, 1e-9)
        assertEquals(75.0, v.peakDb!!, 1e-9)
    }

    /**
     * **이것이 이 파일의 한가운데다.** 겉장의 한 값을 모든 행에 걸면
     * 보정이 바뀐 뒤 구간이 10dB 어긋난 채 그럴듯하게 남는다.
     */
    @Test
    fun `보정이 바뀐 뒤에는 그 보정이 걸린다`() {
        val epochs = listOf(
            RecordingEpoch(0, 0L, 110.0, isReferenceOnly = false, leqWindowMs = 10_000L),
            RecordingEpoch(1, 48_000L, 120.0, isReferenceOnly = false, leqWindowMs = 10_000L),
        )
        val rows = listOf(row(0, 0, -40.0), row(1, 1, -40.0))
        val m = meta(epochs = epochs, offset = 110.0)
        assertEquals(70.0, playbackValuesAt(m, rows, 0L)!!.currentDb!!, 1e-9)
        assertEquals(80.0, playbackValuesAt(m, rows, 500L)!!.currentDb!!, 1e-9)
    }

    @Test
    fun `밴드도 그 행의 보정을 따른다`() {
        val epochs = listOf(
            RecordingEpoch(0, 0L, 110.0, isReferenceOnly = false, leqWindowMs = 10_000L),
            RecordingEpoch(1, 48_000L, 120.0, isReferenceOnly = false, leqWindowMs = 10_000L),
        )
        val rows = listOf(row(0, 0, -40.0), row(1, 1, -40.0))
        val m = meta(epochs = epochs)
        assertEquals(70.0, playbackValuesAt(m, rows, 0L)!!.bands!![0], 1e-9)
        assertEquals(80.0, playbackValuesAt(m, rows, 500L)!!.bands!![0], 1e-9)
    }

    /** **표에 없는 epoch 은 짐작하지 않는다.** 0번 것을 걸면 틀린 값이 뜬다. */
    @Test
    fun `표에 없는 epoch 이면 값을 비운다`() {
        val epochs = listOf(
            RecordingEpoch(0, 0L, 110.0, isReferenceOnly = false, leqWindowMs = 10_000L),
        )
        val v = playbackValuesAt(meta(epochs = epochs), listOf(row(0, 9, -40.0)), 0L)!!
        assertNull("모르는 epoch 에 보정을 걸었다", v.currentDb)
        assertNull(v.bands)
    }

    @Test
    fun `미보정 구간인지 그 행이 말한다`() {
        val epochs = listOf(
            RecordingEpoch(0, 0L, 120.0, isReferenceOnly = true, leqWindowMs = 10_000L),
            RecordingEpoch(1, 48_000L, 110.0, isReferenceOnly = false, leqWindowMs = 10_000L),
        )
        val rows = listOf(row(0, 0, -40.0), row(1, 1, -40.0))
        val m = meta(epochs = epochs)
        assertTrue(playbackValuesAt(m, rows, 0L)!!.referenceOnly)
        assertFalse(playbackValuesAt(m, rows, 500L)!!.referenceOnly)
    }

    // ── 놓친 자리 ─────────────────────────────────────────

    /** **0 으로 채우지 않는다.** 「아주 조용했다」가 된다. */
    @Test
    fun `놓친 자리는 값을 비운다`() {
        val v = playbackValuesAt(meta(), listOf(row(0, 0, -40.0, missing = true)), 0L)
        assertNotNull(v)
        assertNull(v!!.currentDb)
        assertNull(v.maxDb)
        assertNull(v.bands)
    }

    @Test
    fun `찌그러진 자리를 알려 준다`() {
        val rows = listOf(row(0, 0, -5.0).copy(clipped = true))
        assertTrue(playbackValuesAt(meta(), rows, 0L)!!.clipped)
    }
}
