package kr.joa.selahrta.recording

import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.domain.CalibrationState
import kr.joa.selahrta.domain.ChurchSegment
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringWriter

/**
 * **내보낸 CSV 가 조건과 값을 함께 담는가.**
 *
 * 여기서 지키는 두 줄:
 *
 * 1. **행마다 제 보정을 건다.** 보정은 녹음 도중 바뀔 수 있고, 행은
 *    `epochId` 만 지닌다. 겉장의 한 값으로 뭉뚱그리면 보정이 바뀐 구간의
 *    숫자가 **그럴듯하게 틀린다.**
 * 2. **못 하는 것은 못 한다고 적는다.** epoch 표가 없는 옛 기록은 한 값으로
 *    내보내되 머리말에 그 한계를 적는다.
 */
class SessionExportTest {

    private val offsetA = 110.0
    private val offsetB = 120.0

    private fun timeline(rows: List<TimelineRow>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        val w = TimelineWriter(out, TimelineHeader(48_000, TimelineFormat.ROW_MILLIS))
        rows.forEach { w.write(it) }
        return ByteArrayInputStream(out.toByteArray())
    }

    private fun row(index: Int, epoch: Int, raw: Double) = TimelineRow(
        rowIndex = index,
        currentRaw = raw,
        currentEpoch = epoch,
        maxRaw = raw + 2.0,
        maxEpoch = epoch,
        peakRaw = raw + 5.0,
        peakEpoch = epoch,
        clipped = false,
        missing = false,
        bands = FloatArray(ThirdOctave.BAND_COUNT) { raw.toFloat() },
    )

    private fun meta(
        epochs: List<RecordingEpoch> = emptyList(),
        offset: Double = offsetA,
        events: List<SessionEvent> = emptyList(),
    ) = SessionMeta(
        id = "s1",
        startedAtEpochMs = 1_700_000_000_000L,
        endedAtEpochMs = 1_700_000_002_000L,
        durationMs = 2_000L,
        deviceKey = "BuiltIn|SM-S918N",
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
        conditions = MeasurementConditions(
            audioSource = CaptureSource.VoiceRecognition,
            unprocessedSupported = false,
            agcDisabled = true,
            nsDisabled = true,
            aecDisabled = true,
            routedAddress = "bottom",
            activeMicCombo = "22",
            calibrationState = CalibrationState.GlobalCalibrated,
        ),
        epochs = epochs,
    )

    private fun export(m: SessionMeta, rows: List<TimelineRow>): String {
        val w = StringWriter()
        SessionExport.writeCsv(m, timeline(rows), w)
        return w.toString()
    }

    /**
     * 표의 데이터 줄만.
     *
     * **BOM 을 먼저 뗀다.** 안 떼면 첫 줄이 `#` 로 시작하지 않는 것으로
     * 읽혀 주석이 데이터로 섞인다 — 실제 CSV 읽는 쪽도 `utf-8-sig` 로
     * 여는 까닭이 이것이다.
     */
    private fun dataLines(csv: String) = csv.removePrefix("﻿")
        .lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .drop(1) // 열 이름 줄
        .toList()

    // ── 조건이 함께 나가는가 ───────────────────────────────

    @Test
    fun `머리말에 잰 조건이 들어간다`() {
        val csv = export(meta(), listOf(row(0, 0, -40.0)))
        assertTrue(csv, csv.contains("# [무엇으로 쟀나]"))
        assertTrue(csv, csv.contains("활성 마이크: 22"))
        assertTrue(csv, csv.contains("마이크 자리: bottom"))
        assertTrue(csv, csv.contains(CaptureSource.VoiceRecognition.labelKo))
    }

    @Test
    fun `가공 없는 입력이 아니면 머리말이 말한다`() {
        val csv = export(meta(), listOf(row(0, 0, -40.0)))
        assertTrue(csv, csv.contains("# 주의:") && csv.contains("가공 없는 입력을 지원하지 않아"))
    }

    /** `#` 줄은 엑셀이 데이터로 읽지 않는다. 표는 깨끗해야 한다. */
    @Test
    fun `표는 열 수가 흐트러지지 않는다`() {
        val csv = export(meta(), List(3) { row(it, 0, -40.0) })
        val expected = 10 + ThirdOctave.BAND_COUNT
        dataLines(csv).forEach { assertEquals(it, expected, it.split(',').size) }
    }

    // ── 행마다 제 보정을 거는가 ───────────────────────────

    /**
     * **이것이 이 파일의 한가운데다.**
     *
     * 두 epoch 의 보정이 10dB 다른데, 겉장의 한 값으로 뭉뚱그리면 뒤
     * 구간이 10dB 어긋난 채 그럴듯하게 남는다.
     */
    @Test
    fun `보정이 바뀐 구간은 제 보정으로 나간다`() {
        val epochs = listOf(
            RecordingEpoch(0, 0L, offsetA, isReferenceOnly = false, leqWindowMs = 10_000L),
            RecordingEpoch(1, 48_000L, offsetB, isReferenceOnly = false, leqWindowMs = 10_000L),
        )
        val csv = export(
            meta(epochs = epochs),
            listOf(row(0, 0, -40.0), row(1, 1, -40.0)),
        )
        val lines = dataLines(csv)
        assertEquals(2, lines.size)
        // current_db 는 일곱째 열(0부터 6).
        assertEquals("70.0", lines[0].split(',')[6])
        assertEquals("80.0", lines[1].split(',')[6])
    }

    @Test
    fun `밴드도 그 행의 보정을 따른다`() {
        val epochs = listOf(
            RecordingEpoch(0, 0L, offsetA, isReferenceOnly = false, leqWindowMs = 10_000L),
            RecordingEpoch(1, 48_000L, offsetB, isReferenceOnly = false, leqWindowMs = 10_000L),
        )
        val csv = export(
            meta(epochs = epochs),
            listOf(row(0, 0, -40.0), row(1, 1, -40.0)),
        )
        val lines = dataLines(csv)
        assertEquals("70.0", lines[0].split(',')[10])
        assertEquals("80.0", lines[1].split(',')[10])
    }

    /**
     * **표에 없는 epoch 은 짐작하지 않는다.** 0번 보정을 걸면 그럴듯하게
     * 틀린 값이 표에 남는다.
     */
    @Test
    fun `풀 수 없는 행은 비우고 말한다`() {
        val epochs = listOf(
            RecordingEpoch(0, 0L, offsetA, isReferenceOnly = false, leqWindowMs = 10_000L),
        )
        val csv = export(
            meta(epochs = epochs),
            listOf(row(0, 0, -40.0), row(1, 9, -40.0)),
        )
        val lines = dataLines(csv)
        assertEquals("70.0", lines[0].split(',')[6])
        assertEquals("빈 값이 아니다: ${lines[1]}", "", lines[1].split(',')[6])
        assertTrue(csv, csv.contains("보정을 풀 수 없는 행이 1개"))
    }

    // ── 못 하는 것은 못 한다고 적는가 ────────────────────

    /**
     * 옛 기록에는 epoch 표가 없다. 한 값으로 내보내되 **그 한계를 적는다** —
     * 조용히 뭉뚱그리면 보정이 바뀐 구간이 그럴듯하게 틀린다.
     */
    @Test
    fun `epoch 표가 없으면 한계를 적는다`() {
        val csv = export(meta(epochs = emptyList()), listOf(row(0, 0, -40.0)))
        assertTrue(csv, csv.contains("보정이 바뀐 자리(epoch)가 남아 있지 않습니다"))
        assertEquals("70.0", dataLines(csv)[0].split(',')[6])
    }

    @Test
    fun `epoch 표가 있으면 그 한계를 말하지 않는다`() {
        val epochs = listOf(
            RecordingEpoch(0, 0L, offsetA, isReferenceOnly = false, leqWindowMs = 10_000L),
        )
        val csv = export(meta(epochs = epochs), listOf(row(0, 0, -40.0)))
        assertFalse(csv, csv.contains("남아 있지 않습니다"))
    }

    // ── 구간과 사건 ───────────────────────────────────────

    @Test
    fun `구간이 바뀐 시각부터 그 구간으로 적힌다`() {
        val events = listOf(
            SessionEvent(0L, SessionEventKind.SegmentChange, segment = ChurchSegment.Sermon),
            SessionEvent(1_000L, SessionEventKind.SegmentChange, segment = ChurchSegment.Worship),
        )
        val csv = export(meta(events = events), List(3) { row(it, 0, -40.0) })
        val lines = dataLines(csv)
        assertEquals(ChurchSegment.Sermon.shortKo, lines[0].split(',')[2])
        assertEquals(ChurchSegment.Worship.shortKo, lines[2].split(',')[2])
    }

    /**
     * **구간을 지운 뒤부터는 빈칸이어야 한다**(독립 검토 UIS-04).
     *
     * 마지막 구간을 지우면 화면은 구간 없이 돌아간다. 예전에는 그
     * 「구간 없음」 사건을 `segment != null` 로 걸러 내, 표가 **그 뒤로도
     * 지워진 구간으로 계속 분류했다.** 화면에서 견준 구간과 표에 적힌
     * 구간이 달라지는 것이다 — 비어 있는 것과 틀린 것은 다른 일이다.
     */
    @Test
    fun `구간이 없어진 뒤로는 구간 칸이 빈다`() {
        val events = listOf(
            SessionEvent(0L, SessionEventKind.SegmentChange, segment = ChurchSegment.Worship),
            SessionEvent(1_000L, SessionEventKind.SegmentChange, "구간 없음", segment = null),
        )
        val csv = export(meta(events = events), List(3) { row(it, 0, -40.0) })
        val lines = dataLines(csv)
        assertEquals(ChurchSegment.Worship.shortKo, lines[0].split(',')[2])
        assertEquals("", lines[2].split(',')[2])
    }

    @Test
    fun `사건은 표 뒤에 주석으로 붙는다`() {
        val events = listOf(SessionEvent(1_000L, SessionEventKind.Clipped))
        val csv = export(meta(events = events), listOf(row(0, 0, -40.0)))
        val at = csv.lineSequence().indexOfFirst { it.contains("--- 사건 ---") }
        val lastData = csv.lineSequence().indexOfLast { it.isNotBlank() && !it.startsWith("#") }
        assertTrue("사건이 표보다 앞에 있다", at > lastData)
    }

    // ── 겉장 왕복 ──────────────────────────────────────────

    /** epoch 표가 겉장에 남지 않으면 다시 열었을 때 한 값으로 뭉개진다. */
    @Test
    fun `epoch 표가 겉장을 왕복한다`() {
        val epochs = listOf(
            RecordingEpoch(0, 0L, offsetA, isReferenceOnly = true, leqWindowMs = 10_000L),
            RecordingEpoch(1, 48_000L, offsetB, isReferenceOnly = false, leqWindowMs = 60_000L),
        )
        val back = decodeSessionMeta(encodeSessionMeta(meta(epochs = epochs))).getOrThrow()
        assertEquals(epochs, back.epochs)
    }

    @Test
    fun `옛 판에는 epoch 표가 없어도 읽힌다`() {
        val text = encodeSessionMeta(meta(epochs = emptyList()))
        val back = decodeSessionMeta(text).getOrThrow()
        assertTrue(back.epochs.isEmpty())
    }

    // ── 엑셀이 한글을 읽는가 ───────────────────────────────

    /**
     * **엑셀은 한국어 윈도에서 CSV 를 CP949 로 짐작한다.**
     *
     * 파일이 UTF-8 이어도 BOM 이 없으면 「미보정」이 「誘몄낫??」 이
     * 된다 — 담당자가 실제로 겪었다(2026-09-27). BOM 세 바이트가 그
     * 짐작을 막는다.
     */
    @Test
    fun `파일이 BOM 으로 시작한다`() {
        val csv = export(meta(), listOf(row(0, 0, -40.0)))
        assertTrue("BOM 이 없다", csv.startsWith("﻿"))
    }

    /** UTF-8 로 쓰면 BOM 이 정확히 세 바이트(EF BB BF)다. */
    @Test
    fun `UTF-8 로 쓰면 세 바이트가 앞에 붙는다`() {
        val csv = export(meta(), listOf(row(0, 0, -40.0)))
        val bytes = csv.toByteArray(Charsets.UTF_8)
        assertEquals(0xEF.toByte(), bytes[0])
        assertEquals(0xBB.toByte(), bytes[1])
        assertEquals(0xBF.toByte(), bytes[2])
    }

    /**
     * **BOM 이 열 이름에 붙으면 안 된다.**
     *
     * 첫 줄이 `#` 주석이라 괜찮지만, 나중에 머리말을 없애면 `timestamp`
     * 앞에 보이지 않는 글자가 붙어 **그 열을 이름으로 못 찾게 된다.**
     */
    @Test
    fun `BOM 이 열 이름 줄에 붙지 않는다`() {
        val csv = export(meta(), listOf(row(0, 0, -40.0)))
        val header = csv.lineSequence().first { it.startsWith("timestamp") }
        assertTrue(header, header.startsWith("timestamp,"))
        assertTrue("BOM 이 주석이 아닌 줄에 있다", csv.substringAfter("﻿").none { it == '﻿' })
    }

    // ── 이름 ────────────────────────────────────────────

    /**
     * **표와 소리가 같은 몸통을 쓴다.**
     *
     * 받는 쪽(카톡·메일)에는 파일 이름만 남는다. 소리가 `audio.m4a` 로
     * 가면 표 여러 장 옆에서 어느 기록의 소리인지 알 수 없다 — 실제로
     * 그랬다.
     */
    @Test
    fun `표와 소리가 같은 이름 몸통을 쓴다`() {
        val started = 1_759_000_000_000L
        val stem = SessionExport.stem(started)
        val csv = SessionExport.fileName(meta().copy(startedAtEpochMs = started))
        assertEquals("$stem.csv", csv)
        assertEquals("$stem.m4a", audioFileName(AudioFileFormat.M4a, started))
        assertEquals("$stem.wav", audioFileName(AudioFileFormat.Wav, started))
    }

    /** 시각이 다르면 이름도 다르다 — 한 폴더에 모아도 겹치지 않는다. */
    @Test
    fun `시각이 다르면 소리 이름도 다르다`() {
        val a = audioFileName(AudioFileFormat.M4a, 1_759_000_000_000L)
        val b = audioFileName(AudioFileFormat.M4a, 1_759_000_060_000L)
        assertFalse("$a 와 $b 가 같다", a == b)
    }

    /** 한글이 그대로 왕복하는가. */
    @Test
    fun `한글이 UTF-8 로 왕복한다`() {
        val csv = export(meta(), listOf(row(0, 0, -40.0)))
        val back = String(csv.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
        assertTrue(back, back.contains("무엇으로 쟀나"))
        assertTrue(back, back.contains("음성인식 경로"))
        assertTrue(back, back.contains("마이크 자리"))
    }
}
