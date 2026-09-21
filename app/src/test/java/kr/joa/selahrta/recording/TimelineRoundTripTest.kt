package kr.joa.selahrta.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * **행 집계기와 타임라인 파일** — 검증자가 요구한 회귀를 그대로 만든다.
 *
 * M32 의 반례가 이 시험의 중심이다:
 *
 * ```
 * 한 행 안에서 보정이 100 → 120 으로 바뀌고
 *   옛 구간 -20dBFS + 100 =  80dB
 *   새 구간 -30dBFS + 120 =  90dB   ← 실제 최대
 * raw 최대(-20)만 남기고 epoch 하나를 고르면 80 또는 100 이 된다
 * ```
 *
 * 그래서 **극값마다 제 epoch 를 지니게** 했고, 여기서 **writer 에서
 * reader 까지 왕복시켜 90 이 나오는지** 본다.
 */
class TimelineRoundTripTest {

    private val fs = 48_000
    private val bands = FloatArray(31) { it * 0.5f }

    private fun table(vararg e: RecordingEpoch) = EpochTable().apply { e.forEach { add(it) } }

    private fun sample(current: Double, max: Double, peak: Double, clipped: Boolean = false) =
        RowSample(current, max, peak, clipped, bands)

    private fun roundTrip(rows: List<TimelineRow>, epochs: EpochTable): TimelineReader {
        val out = ByteArrayOutputStream()
        val w = TimelineWriter(out, TimelineHeader(fs, 500))
        rows.forEach { w.write(it) }
        w.flush()
        return TimelineReader(ByteArrayInputStream(out.toByteArray()), epochs)
    }

    /**
     * **M32 의 반례** — 한 행 한가운데에서 보정이 바뀐다.
     *
     * 실제 최대는 **90dB** 다. writer→reader 를 지나도 90 이어야 한다.
     */
    @Test
    fun `한 행 안에서 보정이 바뀌어도 최대가 90dB 로 나온다`() {
        val epochs = table(
            RecordingEpoch(0, 0, calibrationOffsetDb = 100.0, isReferenceOnly = false, leqWindowMs = 60_000),
            RecordingEpoch(1, 12_000, calibrationOffsetDb = 120.0, isReferenceOnly = false, leqWindowMs = 60_000),
        )
        val agg = RowAggregator(fs, epochs)

        // 0번 행은 0..23999. 그 한가운데(12000)에서 epoch 이 바뀐다.
        agg.add(0, 12_000, epochId = 0, s = sample(current = -20.0, max = -20.0, peak = -20.0))
        agg.add(12_000, 12_000, epochId = 1, s = sample(current = -30.0, max = -30.0, peak = -30.0))

        val rows = agg.finish()
        assertEquals("한 행이어야 한다", 1, rows.size)

        val r = roundTrip(rows, epochs)
        val read = r.all()
        assertEquals(1, read.size)

        val max = r.calibratedMax(read[0])!!
        println("[M32] raw=${read[0].maxRaw} epoch=${read[0].maxEpoch} → ${max.db}dB")
        assertEquals("실제 최대는 90dB 다", 90.0, max.db, 1e-6)
        assertEquals("이긴 쪽은 새 epoch", 1, max.epochId)
        assertEquals("저장된 raw 는 -30", -30.0, read[0].maxRaw, 1e-6)
    }

    /**
     * **`current` 와 `max` 가 서로 다른 epoch 에서 나오는 경우.**
     *
     * `current` 는 **마지막** 조각의 값이고, 극값은 보정한 값으로 고른다.
     * 둘이 다른 epoch 일 수 있어야 한다.
     */
    @Test
    fun `current 와 max 가 서로 다른 epoch 일 수 있다`() {
        val epochs = table(
            RecordingEpoch(0, 0, 100.0, false, 60_000),
            RecordingEpoch(1, 12_000, 60.0, false, 60_000),
        )
        val agg = RowAggregator(fs, epochs)
        // 앞 구간이 보정까지 얹으면 더 크다(-20+100=80). 뒤는 -20+60=40.
        agg.add(0, 12_000, 0, sample(current = -20.0, max = -20.0, peak = -20.0))
        agg.add(12_000, 12_000, 1, sample(current = -25.0, max = -25.0, peak = -25.0))

        val rows = agg.finish()
        val r = roundTrip(rows, epochs)
        val read = r.all().single()

        assertEquals("최대는 앞 epoch", 0, read.maxEpoch)
        assertEquals("current 는 마지막 조각", 1, read.currentEpoch)
        assertEquals(80.0, r.calibratedMax(read)!!.db, 1e-6)
        assertEquals(35.0, r.calibratedCurrent(read)!!.db, 1e-6)
        println("[다른 epoch] max=${read.maxEpoch} current=${read.currentEpoch}")
    }

    /** 한 행에 **여러 epoch** 이 있어도 가장 큰 것이 이긴다. */
    @Test
    fun `한 행에 epoch 이 셋이어도 가장 큰 것이 이긴다`() {
        val epochs = table(
            RecordingEpoch(0, 0, 100.0, false, 60_000),
            RecordingEpoch(1, 8_000, 110.0, false, 60_000),
            RecordingEpoch(2, 16_000, 90.0, false, 60_000),
        )
        val agg = RowAggregator(fs, epochs)
        agg.add(0, 8_000, 0, sample(-20.0, -20.0, -20.0))      // 80
        agg.add(8_000, 8_000, 1, sample(-25.0, -25.0, -25.0))  // 85  ← 최대
        agg.add(16_000, 8_000, 2, sample(-15.0, -15.0, -15.0)) // 75

        val r = roundTrip(agg.finish(), epochs)
        val read = r.all().single()
        assertEquals(1, read.maxEpoch)
        assertEquals(85.0, r.calibratedMax(read)!!.db, 1e-6)
    }

    /** **마지막 부분 행**도 나와야 한다 — 500ms 를 다 못 채워도. */
    @Test
    fun `마지막 부분 행도 나온다`() {
        val epochs = table(RecordingEpoch(0, 0, 100.0, false, 60_000))
        val agg = RowAggregator(fs, epochs)
        agg.add(0, 24_000, 0, sample(-20.0, -20.0, -20.0))
        // 1번 행을 5000프레임(약 104ms)만 채운다.
        agg.add(24_000, 5_000, 0, sample(-40.0, -40.0, -40.0))

        val rows = agg.finish()
        assertEquals("두 행이 나와야 한다", 2, rows.size)
        assertEquals(1, rows[1].rowIndex)
        assertEquals(60.0, roundTrip(rows, epochs).let { r -> r.all()[1].let { r.calibratedMax(it)!!.db } }, 1e-6)
    }

    /** **빠진 행을 채운다** — 안 채우면 `위치/500ms` 가 깨진다. */
    @Test
    fun `측정이 없던 구간은 missing 행으로 채운다`() {
        val epochs = table(RecordingEpoch(0, 0, 100.0, false, 60_000))
        val agg = RowAggregator(fs, epochs)
        agg.add(0, 24_000, 0, sample(-20.0, -20.0, -20.0))          // 0번 행
        agg.add(24_000L * 4, 24_000, 0, sample(-30.0, -30.0, -30.0)) // 4번 행

        val rows = agg.finish()
        assertEquals("0..4 다섯 행", 5, rows.size)
        assertEquals(listOf(0, 1, 2, 3, 4), rows.map { it.rowIndex })
        assertTrue("사이는 missing", rows.subList(1, 4).all { it.missing })
        assertFalse(rows[0].missing)

        val r = roundTrip(rows, epochs)
        val read = r.all()
        assertNull("missing 행은 값을 주지 않는다", r.calibratedMax(read[2]))
        assertNotNull(r.calibratedMax(read[0]))
    }

    /** 미보정 구간은 **그 사실이 따라다녀야** 한다. */
    @Test
    fun `미보정 구간의 값에는 그 사실이 붙는다`() {
        val epochs = table(
            RecordingEpoch(0, 0, 120.0, isReferenceOnly = true, leqWindowMs = 10_000),
            RecordingEpoch(1, 24_000, 95.0, isReferenceOnly = false, leqWindowMs = 10_000),
        )
        val agg = RowAggregator(fs, epochs)
        agg.add(0, 24_000, 0, sample(-30.0, -30.0, -30.0))
        agg.add(24_000, 24_000, 1, sample(-30.0, -30.0, -30.0))

        val r = roundTrip(agg.finish(), epochs)
        val read = r.all()
        assertTrue("첫 행은 미보정", r.calibratedMax(read[0])!!.isReferenceOnly)
        assertFalse("둘째 행은 보정됨", r.calibratedMax(read[1])!!.isReferenceOnly)
    }

    /** 클리핑은 **그 구간의 것**이어야 한다. 세션 누적이 아니다. */
    @Test
    fun `클리핑은 그 행의 것만 표시한다`() {
        val epochs = table(RecordingEpoch(0, 0, 100.0, false, 60_000))
        val agg = RowAggregator(fs, epochs)
        agg.add(0, 24_000, 0, sample(-3.0, -3.0, -0.1, clipped = true))
        agg.add(24_000, 24_000, 0, sample(-30.0, -30.0, -30.0, clipped = false))

        val read = roundTrip(agg.finish(), epochs).all()
        assertTrue("첫 행은 잘렸다", read[0].clipped)
        assertFalse("둘째 행은 안 잘렸다 — 누적이면 여기도 켜진다", read[1].clipped)
    }

    /** 행 경계를 걸친 조각은 **받지 않는다.** 부르는 쪽이 쪼개야 한다. */
    @Test
    fun `행 경계를 걸친 조각은 거절한다`() {
        val epochs = table(RecordingEpoch(0, 0, 100.0, false, 60_000))
        val agg = RowAggregator(fs, epochs)
        val e = runCatching {
            agg.add(23_552, 1_024, 0, sample(-20.0, -20.0, -20.0)) // 0번과 1번에 걸친다
        }.exceptionOrNull()
        assertNotNull("걸치면 거절해야 한다", e)
        assertTrue("까닭을 적어야 한다: ${e?.message}", e!!.message!!.contains("쪼개"))
    }

    /** 파일 머리를 못 알아보면 **열지 않는다.** */
    @Test
    fun `모르는 판이면 열지 않는다`() {
        val epochs = table(RecordingEpoch(0, 0, 100.0, false, 60_000))
        val out = ByteArrayOutputStream()
        TimelineWriter(out, TimelineHeader(fs, 500, version = TimelineFormat.VERSION))
        val bytes = out.toByteArray()
        // 판 번호를 망가뜨린다.
        bytes[4] = 99
        val e = runCatching { TimelineReader(ByteArrayInputStream(bytes), epochs) }.exceptionOrNull()
        assertNotNull("모르는 판은 열면 안 된다", e)
    }

    /** 반 토막 행은 **없는 것으로 본다.** */
    @Test
    fun `끊긴 꼬리는 버린다`() {
        val epochs = table(RecordingEpoch(0, 0, 100.0, false, 60_000))
        val agg = RowAggregator(fs, epochs)
        agg.add(0, 24_000, 0, sample(-20.0, -20.0, -20.0))
        agg.add(24_000, 24_000, 0, sample(-30.0, -30.0, -30.0))

        val out = ByteArrayOutputStream()
        val w = TimelineWriter(out, TimelineHeader(fs, 500))
        agg.finish().forEach { w.write(it) }
        w.flush()

        // 마지막 행의 절반을 잘라 낸다.
        val full = out.toByteArray()
        val cut = full.copyOf(full.size - TimelineFormat.ROW_BYTES / 2)

        val read = TimelineReader(ByteArrayInputStream(cut), epochs).all()
        assertEquals("온전한 행만 읽는다", 1, read.size)
    }

    /** epoch 를 끝없이 담지 않는다 — 넘치면 알려야 한다. */
    @Test
    fun `epoch 가 상한을 넘으면 알린다`() {
        val t = EpochTable(max = 3)
        assertTrue(t.add(RecordingEpoch(0, 0, 100.0, false, 60_000)))
        assertTrue(t.add(RecordingEpoch(1, 10, 100.0, false, 60_000)))
        assertTrue(t.add(RecordingEpoch(2, 20, 100.0, false, 60_000)))
        assertFalse("넘치면 false — 부르는 쪽이 녹음을 끝낸다", t.add(RecordingEpoch(3, 30, 100.0, false, 60_000)))
        assertEquals("덮어쓰지 않는다", 3, t.size)
    }
}
