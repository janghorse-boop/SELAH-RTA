package kr.joa.selahrta.recording

import kr.joa.selahrta.domain.ChurchSegment
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSV 가 **받는 쪽에서 표로 열리는가**(Phase 11 · 명세 13장).
 *
 * 여기서 지키는 것은 셋이다 — **열 수가 줄마다 같을 것**(하나만 어긋나도
 * 그 뒤가 전부 밀린다), **없는 값을 지어내지 않을 것**, 그리고
 * **미보정을 숨기지 않을 것**.
 */
class SessionCsvTest {

    private val meta = SessionMeta(
        id = "s1",
        startedAtEpochMs = 1_790_000_000_000L,
        endedAtEpochMs = 1_790_000_060_000L,
        durationMs = 60_000L,
        deviceKey = "USB|UMC404HD",
        // 쉼표가 든 실제 이름.
        deviceLabel = "USB-Audio - UMC404HD 192k (card=1;device=0), 1번",
        micKind = MicKind.Usb,
        sampleRate = 48_000,
        encoding = "PCM_FLOAT",
        channelCount = 2,
        channelIndex = 0,
        calibrationOffsetDb = 100.0,
        referenceOnly = false,
        curveApplied = false,
        weighting = Weighting.A,
        timeWeight = TimeWeight.Fast,
        leqWindowMs = 60_000L,
        leqDb = 70.0,
        minDb = 50.0,
        maxDb = 85.0,
        peakDb = 95.0,
    )

    private fun row(index: Int, missing: Boolean = false) = TimelineRow(
        rowIndex = index,
        currentRaw = -30.0,
        currentEpoch = 0,
        maxRaw = -25.0,
        maxEpoch = 0,
        peakRaw = -12.0,
        peakEpoch = 0,
        clipped = false,
        missing = missing,
        bands = FloatArray(ThirdOctave.BAND_COUNT) { -40f },
    )

    private fun cellsOf(line: String): List<String> {
        // 따옴표 안의 쉼표는 세지 않는다 — 시험이 그 규칙을 직접 확인한다.
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    private val columnCount get() = cellsOf(SessionCsv.columns()).size

    @Test
    fun `열 이름이 명세의 순서다`() {
        val c = cellsOf(SessionCsv.columns())
        assertEquals("timestamp", c[0])
        assertEquals("segment", c[2])
        assertEquals("device", c[3])
        assertEquals("calibration", c[4])
        assertEquals("weighting", c[5])
        assertEquals("leq_db", c[7])
        assertEquals("밴드 31개가 뒤에 붙는다", 10 + ThirdOctave.BAND_COUNT, c.size)
        assertEquals("band_20", c[10])
        assertEquals("band_20k", c.last())
    }

    /**
     * **열 수가 줄마다 같아야 한다.**
     *
     * 하나만 어긋나면 그 줄부터 모든 값이 옆 열로 밀린다 — 엑셀은
     * 아무 말도 하지 않고, 받은 사람은 틀린 표를 본다.
     */
    @Test
    fun `모든 줄의 열 수가 같다`() {
        val lines = listOf(
            SessionCsv.row(meta, row(0), ChurchSegment.Sermon),
            SessionCsv.row(meta, row(1), ChurchSegment.Worship),
            SessionCsv.row(meta, row(2, missing = true), null),
            SessionCsv.row(meta, row(3), null),
        )
        lines.forEachIndexed { i, line ->
            assertEquals("${i}번째 줄", columnCount, cellsOf(line).size)
        }
    }

    /** 이름에 든 쉼표가 열을 밀지 않는다. */
    @Test
    fun `이름의 쉼표가 열을 밀지 않는다`() {
        val cells = cellsOf(SessionCsv.row(meta, row(0), ChurchSegment.Sermon))
        assertEquals(meta.deviceLabel, cells[3])
        assertEquals(columnCount, cells.size)
    }

    /**
     * **놓친 구간은 빈 값이다.**
     *
     * 0 으로 채우면 「아주 조용했다」로 읽힌다 — 소음 판정에서는 그것이
     * 가장 나쁜 거짓말이다.
     */
    @Test
    fun `놓친 행은 숫자를 지어내지 않는다`() {
        val cells = cellsOf(SessionCsv.row(meta, row(7, missing = true), null))
        // 앞의 여섯 칸(시각·경과·구간·기기·보정·가중)은 그대로 있다.
        assertTrue("시각은 있어야 한다", cells[0].isNotEmpty())
        assertEquals("경과 시각", "3500", cells[1])
        for (i in 6 until cells.size) {
            assertEquals("${i}번 칸이 비어 있어야 한다", "", cells[i])
        }
    }

    @Test
    fun `보정값을 더해 음압으로 낸다`() {
        val cells = cellsOf(SessionCsv.row(meta, row(0), ChurchSegment.Sermon))
        // currentRaw −30 + 오프셋 100 = 70.0
        assertEquals("70.0", cells[6])
        assertEquals("75.0", cells[8])
        assertEquals("88.0", cells[9])
        assertEquals("밴드도 같은 오프셋", "60.0", cells[10])
    }

    @Test
    fun `미보정은 표에도 머리말에도 적힌다`() {
        val raw = meta.copy(referenceOnly = true)
        assertTrue(SessionCsv.calibrationLabel(raw).contains("미보정"))
        assertTrue(
            "머리말이 경고해야 한다",
            SessionCsv.header(raw).any { it.contains("미보정") },
        )
        val cells = cellsOf(SessionCsv.row(raw, row(0), null))
        assertTrue("보정 열에도 남아야 한다", cells[4].contains("미보정"))
    }

    @Test
    fun `놓친 조각이 있으면 머리말이 말한다`() {
        val head = SessionCsv.header(meta.copy(droppedPackets = 12))
        assertTrue(head.any { it.contains("12") && it.contains("놓쳤") })
    }

    @Test
    fun `사건은 표 밖에 적는다`() {
        val m = meta.copy(
            events = listOf(
                SessionEvent(65_000L, SessionEventKind.SegmentChange, "찬양", ChurchSegment.Worship),
            ),
        )
        val lines = SessionCsv.events(m)
        assertTrue("표를 흐트러뜨리지 않는다", lines.all { it.startsWith("#") })
        assertTrue("시각을 분·초로", lines.any { it.contains("1:05") })
    }

    @Test
    fun `사건이 없으면 아무 줄도 없다`() {
        assertTrue(SessionCsv.events(meta).isEmpty())
    }

    /** 머리말은 `#` 로 시작해 엑셀이 데이터로 읽지 않는다. */
    @Test
    fun `머리말은 데이터가 아니다`() {
        assertTrue(SessionCsv.header(meta).all { it.startsWith("#") })
        assertFalse(SessionCsv.columns().startsWith("#"))
    }
}
