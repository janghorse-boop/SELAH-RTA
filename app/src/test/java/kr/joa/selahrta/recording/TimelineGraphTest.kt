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
 * **누른 자리가 그 자리인가**(명세 Recording-D 「그래프 탭↔오디오 seek
 * 양방향 동기」).
 *
 * 여기서 지키는 것:
 *
 * 1. **누른 자리 → 시각**이 맞는가. 1초쯤 어긋나도 화면은 멀쩡해
 *    보이므로, 기기가 아니라 여기서 잡는다.
 * 2. **그래프와 읽는 값이 같은 보정을 쓰는가.** 규칙이 두 벌이면
 *    보정이 바뀐 뒤로 곡선과 숫자가 갈린다.
 * 3. **없는 자리를 지어내지 않는가.** 빈 행을 0dB 로 그리면 「아주
 *    조용했다」가 된다.
 *
 * ## 이 시험이 못 보는 것
 *
 * **소리와 타임라인이 실제로 같은 시각에서 출발하는가**는 여기서 알 수
 * 없다. 둘 다 세션 시작에서 출발한다고 **가정**할 뿐이고, 그 어긋남
 * (drift)은 실기기 장시간 녹음으로만 잡힌다.
 */
class TimelineGraphTest {

    private fun row(
        index: Int,
        raw: Double,
        epoch: Int = 0,
        missing: Boolean = false,
        clipped: Boolean = false,
    ) = TimelineRow(
        rowIndex = index,
        currentRaw = raw,
        currentEpoch = if (missing) TimelineFormat.EPOCH_NONE else epoch,
        maxRaw = raw + 2.0,
        maxEpoch = if (missing) TimelineFormat.EPOCH_NONE else epoch,
        peakRaw = raw + 5.0,
        peakEpoch = if (missing) TimelineFormat.EPOCH_NONE else epoch,
        clipped = clipped,
        missing = missing,
        bands = FloatArray(ThirdOctave.BAND_COUNT) { raw.toFloat() },
    )

    private fun meta(
        epochs: List<RecordingEpoch> = emptyList(),
        offset: Double = 110.0,
        events: List<SessionEvent> = emptyList(),
    ) = SessionMeta(
        id = "s1",
        startedAtEpochMs = 0L,
        endedAtEpochMs = 0L,
        durationMs = 10_000L,
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
        events = events,
        epochs = epochs,
    )

    // ── 1. 누른 자리 → 시각 ──────────────────────────────

    @Test
    fun 가로_자리를_시각으로_바꾼다() {
        // 20행 × 500ms = 10초.
        val rows = (0 until 20).map { row(it, -40.0) }
        val s = timelineSeries(meta(), rows, columns = 100)

        assertEquals(10_000L, s.totalMs)
        assertEquals(0L, s.msAtFraction(0f))
        assertEquals(5_000L, s.msAtFraction(0.5f))
        // **끝을 눌러도 길이를 넘지 않는다.** 넘으면 seek 가 튕긴다.
        assertEquals(9_999L, s.msAtFraction(1f))
        assertEquals(9_999L, s.msAtFraction(1.7f))
        assertEquals(0L, s.msAtFraction(-0.3f))
    }

    @Test
    fun 시각을_가로_자리로_되돌린다() {
        val rows = (0 until 20).map { row(it, -40.0) }
        val s = timelineSeries(meta(), rows, columns = 100)

        assertEquals(0f, s.fractionAtMs(0L), 1e-6f)
        assertEquals(0.5f, s.fractionAtMs(5_000L), 1e-6f)
        assertEquals(1f, s.fractionAtMs(10_000L), 1e-6f)
        // 소리가 타임라인보다 길어도 표시자가 칸 밖으로 나가지 않는다.
        assertEquals(1f, s.fractionAtMs(99_000L), 1e-6f)
    }

    @Test
    fun 누른_자리로_seek_하면_그_자리의_값이_나온다() {
        // **양방향이 맞물리는지**를 본다. 여기가 어긋나면 그래프에서
        // 고른 봉우리와 들리는 소리가 다른 자리가 된다.
        val rows = (0 until 20).map { row(it, if (it == 12) -20.0 else -60.0) }
        val m = meta()
        val s = timelineSeries(m, rows, columns = 20)

        // 12번 행은 6.0~6.5초. 그 칸의 왼쪽 자리를 누른 셈이다.
        val f = s.fractionAtMs(6_000L)
        val ms = s.msAtFraction(f)
        val v = playbackValuesAt(m, rows, ms)
        assertNotNull(v)
        assertEquals(12, v!!.rowIndex)
        assertEquals(90.0, v.currentDb!!, 1e-9)
    }

    // ── 2. 그래프와 읽는 값이 같은 보정을 쓴다 ───────────

    @Test
    fun 칸의_값은_행마다_제_보정을_건다() {
        // 앞 절반은 보정 100, 뒤 절반은 120. raw 는 같다.
        val epochs = listOf(
            RecordingEpoch(0, 0L, 100.0, isReferenceOnly = false, leqWindowMs = 10_000L),
            RecordingEpoch(1, 48_000L, 120.0, isReferenceOnly = false, leqWindowMs = 10_000L),
        )
        val rows = listOf(row(0, -40.0, epoch = 0), row(1, -40.0, epoch = 0)) +
            listOf(row(2, -40.0, epoch = 1), row(3, -40.0, epoch = 1))
        val s = timelineSeries(meta(epochs = epochs), rows, columns = 4)

        // 최대는 raw + 2 이므로 각각 62 / 82.
        assertEquals(62.0, s.columns[0].topDb!!, 1e-9)
        assertEquals(82.0, s.columns[3].topDb!!, 1e-9)
        // **겉장 한 값(110)을 모든 행에 걸면 둘 다 72 가 된다.**
        assertFalse(s.columns[0].topDb == s.columns[3].topDb)
    }

    @Test
    fun 읽는_값과_그래프가_같은_숫자를_낸다() {
        val rows = (0 until 4).map { row(it, -40.0 - it) }
        val m = meta()
        val s = timelineSeries(m, rows, columns = 4)

        for (i in 0 until 4) {
            val v = playbackValuesAt(m, rows, i * 500L)!!
            assertEquals(
                "$i 번째 칸의 최대가 읽는 값과 다르다",
                v.maxDb!!,
                s.columns[i].topDb!!,
                1e-9,
            )
        }
    }

    // ── 3. 없는 자리를 지어내지 않는다 ───────────────────

    @Test
    fun 빈_행은_그리지_않는다() {
        val rows = listOf(
            row(0, -40.0),
            row(1, 0.0, missing = true),
            row(2, -40.0),
        )
        val s = timelineSeries(meta(), rows, columns = 3)

        assertTrue(s.columns[0].filled)
        assertFalse("빈 행이 0dB 로 그려졌다", s.columns[1].filled)
        assertNull(s.columns[1].topDb)
        assertTrue(s.columns[2].filled)
    }

    @Test
    fun 행이_하나도_없으면_빈_그림이다() {
        val s = timelineSeries(meta(), emptyList(), columns = 40)
        assertTrue(s.isEmpty)
        assertEquals(0L, s.totalMs)
        // 빈 그림에서 눌러도 튕기지 않는다.
        assertEquals(0L, s.msAtFraction(0.7f))
        assertEquals(0f, s.fractionAtMs(500L), 1e-6f)
    }

    @Test
    fun 모든_행이_비었으면_그릴_것이_없다고_말한다() {
        val rows = (0 until 4).map { row(it, 0.0, missing = true) }
        val s = timelineSeries(meta(), rows, columns = 4)
        assertTrue(s.isEmpty)
        // 길이는 남는다 — 「소리는 있는데 잰 값이 없다」이기 때문이다.
        assertEquals(2_000L, s.totalMs)
    }

    // ── 칸이 행보다 촘촘해도 빈 칸이 생기지 않는다 ───────

    @Test
    fun 칸이_행보다_많으면_같은_값을_나눠_받는다() {
        // 2행(1초)을 100칸에 그린다. 반열린 구간으로 세면 대부분이
        // 빈 칸이 된다 — 실제로 그렇게 샐 수 있는 자리다.
        val rows = listOf(row(0, -40.0), row(1, -30.0))
        val s = timelineSeries(meta(), rows, columns = 100)

        assertEquals(100, s.columns.size)
        assertTrue("촘촘하게 그리면 빈 칸이 생긴다", s.columns.all { it.filled })
        assertEquals(72.0, s.columns.first().topDb!!, 1e-9)
        assertEquals(82.0, s.columns.last().topDb!!, 1e-9)
    }

    @Test
    fun 칸이_행보다_적으면_그_구간의_최대를_살린다() {
        // 봉우리 하나를 넓은 칸에 담는다. 평균을 내면 사라지는 값이다.
        val rows = (0 until 20).map { row(it, if (it == 7) -10.0 else -60.0) }
        val s = timelineSeries(meta(), rows, columns = 4)

        // 7번 행은 3.5~4.0초 → 둘째 칸(2.5~5.0초) 안에 있다.
        assertEquals(102.0, s.columns[1].topDb!!, 1e-9)
        // 바닥은 그 칸에서 가장 조용한 현재값이다.
        assertEquals(50.0, s.columns[1].bottomDb!!, 1e-9)
        assertEquals(52.0, s.columns[0].topDb!!, 1e-9)
    }

    @Test
    fun 찌그러진_자리는_그_칸에_남는다() {
        val rows = (0 until 8).map { row(it, -40.0, clipped = it == 5) }
        val s = timelineSeries(meta(), rows, columns = 4)
        // 8행 = 4초. 한 칸이 1초다. 5번 행은 2.5~3.0초 → 셋째 칸.
        assertFalse(s.columns[1].clipped)
        assertTrue(s.columns[2].clipped)
        assertFalse(s.columns[3].clipped)
    }

    // ── 눈금 ─────────────────────────────────────────────

    @Test
    fun 조용한_기록을_산맥으로_그리지_않는다() {
        // 1dB 안에서만 움직이는 기록. 눈금을 꽉 맞추면 숨소리가
        // 봉우리가 된다.
        val rows = (0 until 10).map { row(it, -40.0 - it * 0.1) }
        val s = timelineSeries(meta(), rows, columns = 10)

        assertTrue(
            "세로 눈금이 ${s.ceilDb - s.floorDb}dB 밖에 안 된다",
            s.ceilDb - s.floorDb >= 20.0,
        )
        // 값들이 눈금 한가운데쯤 온다 — 바닥이나 천장에 붙지 않는다.
        val h = s.heightOf(70.0)
        assertTrue("곡선이 눈금 가장자리에 붙었다: $h", h in 0.2f..0.8f)
    }

    @Test
    fun 눈금_밖의_값은_잘라_그린다() {
        val rows = (0 until 4).map { row(it, -40.0) }
        val s = timelineSeries(meta(), rows, columns = 4)
        assertEquals(0f, s.heightOf(s.floorDb - 50.0), 1e-6f)
        assertEquals(1f, s.heightOf(s.ceilDb + 50.0), 1e-6f)
    }

    @Test
    fun 가로줄은_다섯을_넘지_않는다() {
        // 폭이 넓어도 줄이 늘지 않아야 한다 — 작은 화면에서 글자가 겹친다.
        val rows = (0 until 40).map { row(it, -80.0 + it * 2.0) }
        val s = timelineSeries(meta(), rows, columns = 40)
        val grid = timelineGridDb(s)
        assertTrue("가로줄이 ${grid.size}개다", grid.size in 2..5)
        assertEquals(s.floorDb, grid.first(), 1e-9)
    }

    // ── 사건 표 ──────────────────────────────────────────

    @Test
    fun 타임라인_밖의_사건은_찍지_않는다() {
        val events = listOf(
            SessionEvent(atMs = 1_000L, kind = SessionEventKind.Clipped),
            SessionEvent(atMs = 9_999L, kind = SessionEventKind.Dropout),
            // 타임라인 길이(10초) 밖 — 끝으로 당겨 붙이면 일어나지
            // 않은 시각에 표가 선다.
            SessionEvent(atMs = 20_000L, kind = SessionEventKind.DeviceChange),
            SessionEvent(atMs = -5L, kind = SessionEventKind.SegmentChange),
        )
        val rows = (0 until 20).map { row(it, -40.0) }
        val m = meta(events = events)
        val s = timelineSeries(m, rows, columns = 20)

        val marks = timelineMarkers(m, s)
        assertEquals(2, marks.size)
        assertEquals(1_000L, marks[0].atMs)
        assertEquals(9_999L, marks[1].atMs)
    }

    @Test
    fun 빈_그림에는_사건을_찍지_않는다() {
        val m = meta(events = listOf(SessionEvent(atMs = 0L, kind = SessionEventKind.Clipped)))
        val s = timelineSeries(m, emptyList(), columns = 10)
        assertTrue(timelineMarkers(m, s).isEmpty())
    }

    // ── 시계 표기 ────────────────────────────────────────

    @Test
    fun 시각을_분초로_적는다() {
        assertEquals("0:00", timelineClock(0L))
        assertEquals("0:07", timelineClock(7_000L))
        assertEquals("1:05", timelineClock(65_000L))
        assertEquals("0:00", timelineClock(-100L))
    }
}
