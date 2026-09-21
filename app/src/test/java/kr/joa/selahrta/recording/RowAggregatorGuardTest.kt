package kr.joa.selahrta.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 독립 검증 **RA01~RA04** 의 회귀 시험.
 *
 * 검증자가 `RowEqIndependentProbe.kt` 로 재현한 여섯 가지를 그대로
 * 붙들어 둔다. 고치기 전에 그 probe 로 **같은 출력을 먼저 확인**했다
 * ([ReviewerProbeTest] 의 주석에 원문 로그가 있다).
 */
class RowAggregatorGuardTest {

    private val fs = 48_000
    private val bands = FloatArray(31)

    private fun s(v: Double) = RowSample(v, v, v, false, bands)

    /** epoch 0 은 0프레임/+100, epoch 1 은 12000프레임/+120. 검증자의 fixture 다. */
    private fun twoEpochs() = EpochTable().apply {
        add(RecordingEpoch(0, 0, 100.0, false, 60_000))
        add(RecordingEpoch(1, 12_000, 120.0, false, 60_000))
    }

    private fun roundTrip(rows: List<TimelineRow>, e: EpochTable): TimelineReader {
        val out = ByteArrayOutputStream()
        val w = TimelineWriter(out, TimelineHeader(fs, 500))
        rows.forEach { w.write(it) }
        w.flush()
        return TimelineReader(ByteArrayInputStream(out.toByteArray()), e)
    }

    // ---------------- RA01 ----------------

    /**
     * **EPOCH_CROSS** — 검증자가 찾은 것. 고치기 전에는 통과하며 80dB 였다.
     *
     * `add(0, 24000, epochId = 0)` 은 12000 에서 바뀌는 epoch 을 가로지른다.
     * 그런데 전체가 epoch 0 으로 처리돼, 실제 90dB 인 구간이 80dB 로 나왔다.
     */
    @Test
    fun `epoch 경계를 가로지르는 조각은 거절한다`() {
        val agg = RowAggregator(fs, twoEpochs())
        val e = runCatching { agg.add(0, 24_000, epochId = 0, s = s(-20.0)) }.exceptionOrNull()
        assertNotNull("가로지르면 거절해야 한다", e)
        assertTrue("까닭을 적는다: ${e?.message}", e!!.message!!.contains("epoch"))
    }

    /** 제대로 쪼개서 넣으면 **90dB** 가 나온다 — 거절이 정상 경로를 막지 않는다. */
    @Test
    fun `epoch 경계에서 쪼개 넣으면 90dB 가 나온다`() {
        val epochs = twoEpochs()
        val agg = RowAggregator(fs, epochs)
        agg.add(0, 12_000, 0, s(-20.0))
        agg.add(12_000, 12_000, 1, s(-30.0))
        val r = roundTrip(agg.finish(), epochs)
        assertEquals(90.0, r.calibratedMax(r.all().single()).dbOrNull!!, 1e-6)
    }

    /** **REVERSE** — 한 행 안에서 시간이 뒤로 가면 `current` 가 과거 값으로 덮였다. */
    @Test
    fun `같은 행에서 시간이 역행하면 거절한다`() {
        val agg = RowAggregator(fs, twoEpochs())
        agg.add(12_000, 12_000, 1, s(-30.0))
        val e = runCatching { agg.add(0, 12_000, 0, s(-20.0)) }.exceptionOrNull()
        assertNotNull("역행은 거절해야 한다", e)
        // 거절한 뒤에도 앞서 넣은 값이 살아 있어야 한다.
        val row = agg.finish().single()
        assertEquals("current 는 그대로", -30.0, row.currentRaw, 1e-9)
        assertEquals(1, row.currentEpoch)
    }

    /** 같은 구간을 두 번 넣는 것도 거절한다. */
    @Test
    fun `겹치는 조각은 거절한다`() {
        val agg = RowAggregator(fs, twoEpochs())
        agg.add(0, 12_000, 0, s(-20.0))
        val e = runCatching { agg.add(6_000, 6_000, 0, s(-10.0)) }.exceptionOrNull()
        assertNotNull("겹치면 거절해야 한다", e)
    }

    /** 실제와 다른 epochId 를 대면 거절한다. */
    @Test
    fun `틀린 epochId 는 거절한다`() {
        val agg = RowAggregator(fs, twoEpochs())
        val e = runCatching { agg.add(0, 12_000, epochId = 1, s = s(-20.0)) }.exceptionOrNull()
        assertNotNull("실제 epoch 은 0 이다", e)
    }

    /**
     * **거절이 결과를 오염시키지 않는다.**
     *
     * 예전에는 검사보다 먼저 열린 행을 닫아, 거절 뒤에 정상 조각을 넣으면
     * 행이 쪼개지거나 값이 사라졌다.
     */
    @Test
    fun `거절한 뒤 정상 조각을 이어도 결과가 온전하다`() {
        val epochs = twoEpochs()
        val agg = RowAggregator(fs, epochs)
        agg.add(0, 12_000, 0, s(-20.0))
        runCatching { agg.add(0, 24_000, 0, s(-5.0)) }        // 역행 + 가로지름
        runCatching { agg.add(12_000, 12_000, 0, s(-5.0)) }   // 틀린 epochId
        agg.add(12_000, 12_000, 1, s(-30.0))                  // 정상

        val rows = agg.finish()
        assertEquals("한 행이어야 한다", 1, rows.size)
        val r = roundTrip(rows, epochs)
        assertEquals("거절된 -5.0 이 섞이면 안 된다", 90.0, r.calibratedMax(r.all().single()).dbOrNull!!, 1e-6)
    }

    /** 음수 프레임은 받지 않는다. */
    @Test
    fun `음수 프레임은 거절한다`() {
        val agg = RowAggregator(fs, twoEpochs())
        assertNotNull(runCatching { agg.add(-1, 10, 0, s(-20.0)) }.exceptionOrNull())
    }

    /** 더하다 넘치는 길이도 받지 않는다. */
    @Test
    fun `프레임이 넘치면 거절한다`() {
        val agg = RowAggregator(fs, twoEpochs())
        assertNotNull(runCatching { agg.add(Long.MAX_VALUE, 2, 0, s(-20.0)) }.exceptionOrNull())
    }

    // ---------------- RA02 ----------------

    /** **FINISH** — 예전에는 부를 때마다 행이 하나씩 늘었다(1 → 2). */
    @Test
    fun `finish 를 두 번 불러도 같다`() {
        val agg = RowAggregator(fs, twoEpochs())
        agg.add(0, 1_000, 0, s(-20.0))
        val first = agg.finish()
        val second = agg.finish()
        assertEquals(1, first.size)
        assertEquals("두 번째도 같아야 한다", first, second)
        assertEquals("행 번호가 겹치면 안 된다", 1, second.map { it.rowIndex }.distinct().size)
    }

    @Test
    fun `빈 집계기도 두 번 부를 수 있다`() {
        val agg = RowAggregator(fs, twoEpochs())
        assertTrue(agg.finish().isEmpty())
        assertTrue(agg.finish().isEmpty())
    }

    @Test
    fun `finish 뒤에는 넣을 수 없다`() {
        val agg = RowAggregator(fs, twoEpochs())
        agg.add(0, 1_000, 0, s(-20.0))
        agg.finish()
        assertNotNull(runCatching { agg.add(1_000, 1_000, 0, s(-20.0)) }.exceptionOrNull())
    }

    /** 행 번호와 레코드 수가 어긋나지 않는다. */
    @Test
    fun `행 번호와 레코드 수가 맞는다`() {
        val epochs = EpochTable().apply { add(RecordingEpoch(0, 0, 100.0, false, 60_000)) }
        val agg = RowAggregator(fs, epochs)
        agg.add(0, 24_000, 0, s(-20.0))
        agg.add(24_000L * 3, 24_000, 0, s(-30.0))
        val rows = agg.finish()
        val read = roundTrip(rows, epochs).all()
        assertEquals(rows.size, read.size)
        assertEquals(List(rows.size) { it }, read.map { it.rowIndex })
    }

    /** 끝자락의 빈 구간도 `missing` 으로 채울 수 있어야 한다. */
    @Test
    fun `녹음의 끝을 주면 끝자락 missing 을 채운다`() {
        val epochs = EpochTable().apply { add(RecordingEpoch(0, 0, 100.0, false, 60_000)) }
        val agg = RowAggregator(fs, epochs)
        agg.add(0, 24_000, 0, s(-20.0))
        // 3번 행 끝까지 녹음했지만 1~3번 행에는 측정이 없었다.
        val rows = agg.finish(endFrameExclusive = 24_000L * 4)
        assertEquals(4, rows.size)
        assertTrue(rows.drop(1).all { it.missing })
        assertFalse(rows[0].missing)
    }

    @Test
    fun `끝이 마지막 조각보다 앞이면 거절한다`() {
        val agg = RowAggregator(fs, twoEpochs())
        agg.add(0, 12_000, 0, s(-20.0))
        assertNotNull(runCatching { agg.finish(endFrameExclusive = 100) }.exceptionOrNull())
    }

    // ---------------- RA03 ----------------

    /**
     * **ID_TRUNCATE** — 검증자가 찾은 것.
     *
     * 예전에는 id 65536 이 파일에서 `.toShort()` 로 0 이 되어, 재생이
     * epoch 0 의 보정을 걸어 **90dB 이어야 할 값이 70dB** 로 나왔다.
     * 상한 256 으로는 막히지 않는다 — epoch 이 둘뿐이어도 재현된다.
     */
    @Test
    fun `범위 밖 epoch id 는 표가 받지 않는다`() {
        for (bad in listOf(-1, -2, 256, 32_768, 65_536, Int.MAX_VALUE)) {
            val t = EpochTable()
            t.add(RecordingEpoch(0, 0, 100.0, false, 60_000))
            val e = runCatching { t.add(RecordingEpoch(bad, 12_000, 120.0, false, 60_000)) }
                .exceptionOrNull()
            assertNotNull("id=$bad 는 거절해야 한다", e)
        }
    }

    @Test
    fun `범위 안 epoch id 는 받는다`() {
        val t = EpochTable()
        assertTrue(t.add(RecordingEpoch(0, 0, 100.0, false, 60_000)))
        assertTrue(t.add(RecordingEpoch(255, 10, 100.0, false, 60_000)))
    }

    /** writer 도 막는다 — 표를 거치지 않고 만든 행이 있을 수 있다. */
    @Test
    fun `writer 는 범위 밖 epoch 을 쓰지 않는다`() {
        val out = ByteArrayOutputStream()
        val w = TimelineWriter(out, TimelineHeader(fs, 500))
        val row = TimelineRow(0, -30.0, 65_536, -30.0, 65_536, -30.0, 65_536, false, false, bands)
        val e = runCatching { w.write(row) }.exceptionOrNull()
        assertNotNull("잘라서 쓰면 안 된다", e)
    }

    /** 측정이 있는 행에 `-1` 을 쓰는 것도 막는다. */
    @Test
    fun `측정이 있는 행에 epoch 없음을 쓰지 않는다`() {
        val out = ByteArrayOutputStream()
        val w = TimelineWriter(out, TimelineHeader(fs, 500))
        val row = TimelineRow(0, -30.0, -1, -30.0, -1, -30.0, -1, false, false, bands)
        assertNotNull(runCatching { w.write(row) }.exceptionOrNull())
    }

    /** 표에 없는 epoch 을 가리키는 행은 **값을 짐작하지 않는다.** */
    @Test
    fun `표에 없는 epoch 은 되살릴 수 없다고 말한다`() {
        val writeTable = EpochTable().apply {
            add(RecordingEpoch(0, 0, 100.0, false, 60_000))
            add(RecordingEpoch(7, 12_000, 120.0, false, 60_000))
        }
        val agg = RowAggregator(fs, writeTable)
        agg.add(12_000, 12_000, 7, s(-30.0))
        val out = ByteArrayOutputStream()
        val w = TimelineWriter(out, TimelineHeader(fs, 500))
        agg.finish().forEach { w.write(it) }
        w.flush()

        // 7번을 모르는 표로 읽는다.
        val poor = EpochTable().apply { add(RecordingEpoch(0, 0, 100.0, false, 60_000)) }
        val r = TimelineReader(ByteArrayInputStream(out.toByteArray()), poor)
        val v = r.calibratedMax(r.all().single())
        assertTrue("짐작한 숫자가 아니라 사실을 준다: $v", v is RowValue.Unresolvable)
        assertEquals(7, (v as RowValue.Unresolvable).epochId)
    }

    /** `missing` 행은 그대로 왕복한다. */
    @Test
    fun `missing 행은 그대로 왕복한다`() {
        val epochs = EpochTable().apply { add(RecordingEpoch(0, 0, 100.0, false, 60_000)) }
        val agg = RowAggregator(fs, epochs)
        agg.add(0, 24_000, 0, s(-20.0))
        agg.add(24_000L * 2, 24_000, 0, s(-30.0))
        val r = roundTrip(agg.finish(), epochs)
        val read = r.all()
        assertEquals(3, read.size)
        assertTrue(read[1].missing)
        assertEquals(RowValue.Missing, r.calibratedMax(read[1]))
    }

    // ---------------- RA04 ----------------

    /** **BAD_HEADER** — 예전에는 `TimelineHeader(0, 0)` 로 쓴 파일을 그대로 열었다. */
    @Test
    fun `말이 안 되는 머리는 쓰지 않는다`() {
        for (h in listOf(
            TimelineHeader(0, 500),
            TimelineHeader(-1, 500),
            TimelineHeader(fs, 0),
            TimelineHeader(fs, -1),
            TimelineHeader(fs, 250),
            TimelineHeader(fs, 70_000),
            TimelineHeader(0, 0),
        )) {
            val e = runCatching { TimelineWriter(ByteArrayOutputStream(), h) }.exceptionOrNull()
            assertNotNull("$h 는 거절해야 한다", e)
        }
    }

    /** 읽는 쪽도 스스로 본다 — 다른 writer 가 만든 파일일 수 있다. */
    @Test
    fun `머리의 시간축이 이상하면 열지 않는다`() {
        val out = ByteArrayOutputStream()
        TimelineWriter(out, TimelineHeader(fs, 500))
        val bytes = out.toByteArray()
        // 샘플레이트 자리(12..15)를 0 으로 만든다.
        for (i in 12..15) bytes[i] = 0
        assertNotNull(
            runCatching { TimelineReader(ByteArrayInputStream(bytes), EpochTable()) }.exceptionOrNull(),
        )
    }

    /** 한 행이 0프레임이 되는 조합은 만들지 못한다. */
    @Test
    fun `한 행이 0프레임이면 만들지 않는다`() {
        val epochs = EpochTable().apply { add(RecordingEpoch(0, 0, 100.0, false, 60_000)) }
        assertNotNull(runCatching { RowAggregator(1_000, epochs, rowMillis = 0) }.exceptionOrNull())
        assertNotNull(runCatching { RowAggregator(0, epochs) }.exceptionOrNull())
        assertNotNull(runCatching { RowAggregator(-1, epochs) }.exceptionOrNull())
    }

    /** 44.1kHz 도 그대로 왕복한다. */
    @Test
    fun `44100 도 왕복한다`() {
        val epochs = EpochTable().apply { add(RecordingEpoch(0, 0, 100.0, false, 60_000)) }
        val agg = RowAggregator(44_100, epochs)
        assertEquals(22_050L, agg.framesPerRow)
        agg.add(0, 22_050, 0, s(-20.0))
        val out = ByteArrayOutputStream()
        val w = TimelineWriter(out, TimelineHeader(44_100, 500))
        agg.finish().forEach { w.write(it) }
        w.flush()
        val r = TimelineReader(ByteArrayInputStream(out.toByteArray()), epochs)
        assertEquals(44_100, r.header.nominalSampleRate)
        assertEquals(80.0, r.calibratedMax(r.all().single()).dbOrNull!!, 1e-6)
    }
}
