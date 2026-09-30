package kr.joa.selahrta.recording

import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.cos

/**
 * **다시 분석해 갈아 끼운다 — 그러나 원본은 남긴다**(명세 Recording-E).
 *
 * 명세의 완료 기준이 「**원본 보존** + 새 분석 결과 생성」이라, 여기서
 * 지키는 것도 그 둘이다.
 *
 * 1. **처음 잰 타임라인이 그대로 남는다.** 두 번, 세 번 다시 분석해도
 *    **진짜 원본**이 남아야 한다 — 두 번째가 첫 번째를 「원본」으로
 *    만들면 처음 잰 것이 사라진다.
 * 2. **소리 파일은 손대지 않는다.**
 * 3. **잰 사실은 그대로 두고, 셈한 값만 바꾼다.**
 */
class SessionReanalyzerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val rate = 48_000
    private lateinit var store: SessionStore

    private fun settings(offset: Double = 110.0) = ReanalysisSettings(
        offsetDb = offset,
        referenceOnly = false,
        leqWindowMs = 10_000L,
        weighting = Weighting.A,
        timeWeight = TimeWeight.Fast,
        fftSize = 4096,
    )

    private fun pcm(seconds: Int = 2, amp: Float = 0.2f) =
        FloatArray(rate * seconds) { i ->
            (amp * cos(2.0 * Math.PI * 1_000.0 * i / rate)).toFloat()
        }

    /** 기록 하나를 **진짜로** 만들어 둔다 — 겉장·타임라인·소리 셋 다. */
    private fun seed(id: String = "s1", offset: Double = 100.0): Pair<SessionMeta, File> {
        store = SessionStore(tmp.newFolder("root-${System.nanoTime()}"))
        store.create(id).getOrThrow()

        val audio = File(store.dirOf(id), "sound.wav")
        val out = audio.outputStream()
        val w = WavWriter(out, rate)
        val samples = pcm()
        w.write(samples, samples.size)
        out.close()
        w.finish(audio)

        // 처음 분석 — 라이브가 하는 일과 같다.
        val first = Reanalysis.run(audio, id, settings(offset)).getOrThrow()
        store.timelineFile(id).outputStream().buffered().use { o ->
            val tw = TimelineWriter(o, TimelineHeader(rate, TimelineFormat.ROW_MILLIS))
            first.rows.forEach { tw.write(it) }
        }
        val meta = SessionMeta(
            id = id,
            startedAtEpochMs = 1_700_000_000_000L,
            endedAtEpochMs = 1_700_000_002_000L,
            durationMs = first.durationMs,
            deviceKey = "k",
            deviceLabel = "시험기기",
            micKind = MicKind.BuiltIn,
            sampleRate = rate,
            encoding = "Float",
            channelCount = 1,
            channelIndex = 0,
            calibrationOffsetDb = offset,
            referenceOnly = false,
            curveApplied = false,
            weighting = Weighting.A,
            timeWeight = TimeWeight.Fast,
            leqWindowMs = 10_000L,
            leqDb = first.summary.leqDb ?: 0.0,
            minDb = first.summary.minDb ?: 0.0,
            maxDb = first.summary.maxDb ?: 0.0,
            peakDb = first.summary.peakDb ?: 0.0,
            epochs = first.epochs,
            droppedPackets = 7,
            audio = RecordedAudio(AudioFileFormat.Wav, audio.name, audio.length()),
        )
        store.writeMeta(meta).getOrThrow()
        return meta to audio
    }

    private fun rowsOf(id: String): List<TimelineRow> =
        store.timelineFile(id).inputStream().use {
            TimelineReader(it, EpochTable()).all()
        }

    // ── 1. 원본이 남는다 ─────────────────────────────────

    @Test
    fun `처음 잰 타임라인이 남는다`() {
        val (meta, audio) = seed()
        val before = store.timelineFile(meta.id).readBytes()

        SessionReanalyzer(store).run(meta.id, audio, settings(120.0), 1_700_000_100_000L)
            .getOrThrow()

        val original = store.originalTimelineFile(meta.id)
        assertTrue("원본이 없다", original.isFile)
        assertTrue("원본이 처음 것과 다르다", before.contentEquals(original.readBytes()))
    }

    /**
     * **두 번 다시 분석해도 진짜 원본이 남는다.**
     *
     * 여기를 놓치면 두 번째가 첫 번째 결과를 「원본」으로 만들어,
     * **처음 잰 것이 조용히 사라진다.**
     */
    @Test
    fun `두 번 다시 분석해도 첫 것이 원본이다`() {
        val (meta, audio) = seed()
        val first = store.timelineFile(meta.id).readBytes()
        val r = SessionReanalyzer(store)

        r.run(meta.id, audio, settings(120.0), 1L).getOrThrow()
        r.run(meta.id, audio, settings(130.0), 2L).getOrThrow()

        val original = store.originalTimelineFile(meta.id)
        assertTrue("두 번째가 원본을 덮었다", first.contentEquals(original.readBytes()))
    }

    @Test
    fun `겉장이 원본 파일 이름을 적어 둔다`() {
        val (meta, audio) = seed()
        assertNull("아직 안 했는데 적혀 있다", meta.originalTimelineName)
        assertNull(meta.reanalyzedAtEpochMs)

        val next = SessionReanalyzer(store)
            .run(meta.id, audio, settings(120.0), 1_700_000_100_000L).getOrThrow()

        assertEquals(SessionReanalyzer.ORIGINAL_DIR, next.originalTimelineName)
        assertEquals(1_700_000_100_000L, next.reanalyzedAtEpochMs)
        // 다시 읽어도 남아 있어야 한다 — 겉장에 실제로 적혔는가.
        val reopened = store.readMeta(meta.id).getOrThrow()
        assertEquals(SessionReanalyzer.ORIGINAL_DIR, reopened.originalTimelineName)
        assertEquals(1_700_000_100_000L, reopened.reanalyzedAtEpochMs)
    }

    // ── 2. 소리는 손대지 않는다 ──────────────────────────

    @Test
    fun `소리 파일이 그대로다`() {
        val (meta, audio) = seed()
        val before = audio.readBytes()
        SessionReanalyzer(store).run(meta.id, audio, settings(120.0), 1L).getOrThrow()
        assertTrue("소리가 바뀌었다", before.contentEquals(audio.readBytes()))
    }

    // ── 3. 셈한 값은 바뀌고, 잰 사실은 그대로 ────────────

    @Test
    fun `보정을 바꾸면 요약이 그만큼 오른다`() {
        val (meta, audio) = seed(offset = 100.0)
        val next = SessionReanalyzer(store)
            .run(meta.id, audio, settings(120.0), 1L).getOrThrow()

        assertEquals(120.0, next.calibrationOffsetDb, 1e-9)
        assertEquals("20dB 올렸는데 요약이 안 따라왔다", 20.0, next.maxDb - meta.maxDb, 0.01)
    }

    @Test
    fun `타임라인이 실제로 갈아 끼워진다`() {
        val (meta, audio) = seed(offset = 100.0)
        val before = rowsOf(meta.id)
        SessionReanalyzer(store).run(meta.id, audio, settings(120.0), 1L).getOrThrow()
        val after = rowsOf(meta.id)

        assertEquals(before.size, after.size)
        // raw 는 보정 전이라 같다. 바뀐 것은 **epoch 에 실린 보정**이다.
        assertEquals(before[1].currentRaw, after[1].currentRaw, 1e-9)
        val e = store.readMeta(meta.id).getOrThrow().epochs
        assertEquals(120.0, e.first().calibrationOffsetDb, 1e-9)
    }

    /**
     * **놓친 조각 수는 건드리지 않는다.**
     *
     * 녹음이 놓친 조각은 **파일에 아예 없다.** 다시 읽어서는 몇 개를
     * 놓쳤는지 알 길이 없고, 0 으로 적으면 **「안 놓쳤다」는 거짓**이 된다.
     */
    @Test
    fun `놓친 조각 수는 그대로 둔다`() {
        val (meta, audio) = seed()
        val next = SessionReanalyzer(store).run(meta.id, audio, settings(120.0), 1L).getOrThrow()
        assertEquals(7, next.droppedPackets)
    }

    @Test
    fun `잰 사실은 그대로 둔다`() {
        val (meta, audio) = seed()
        val next = SessionReanalyzer(store).run(meta.id, audio, settings(120.0), 1L).getOrThrow()

        assertEquals(meta.startedAtEpochMs, next.startedAtEpochMs)
        assertEquals(meta.deviceLabel, next.deviceLabel)
        assertEquals(meta.micKind, next.micKind)
        assertEquals(meta.sampleRate, next.sampleRate)
        assertEquals(meta.audio?.fileName, next.audio?.fileName)
    }

    // ── 실패하면 아무것도 안 바뀐다 ──────────────────────

    @Test
    fun `소리가 깨졌으면 기록을 건드리지 않는다`() {
        val (meta, _) = seed()
        val broken = File(store.dirOf(meta.id), "broken.wav").apply { writeText("소리가 아니다") }
        val before = store.timelineFile(meta.id).readBytes()

        val r = SessionReanalyzer(store).run(meta.id, broken, settings(120.0), 1L)

        assertTrue("실패해야 한다", r.isFailure)
        assertTrue("실패했는데 타임라인이 바뀌었다", before.contentEquals(store.timelineFile(meta.id).readBytes()))
        assertNull("실패했는데 겉장이 바뀌었다", store.readMeta(meta.id).getOrThrow().reanalyzedAtEpochMs)
        assertTrue(
            "실패했는데 원본 사본이 생겼다",
            !store.originalTimelineFile(meta.id).isFile,
        )
    }

    // ── 겉장 판 ──────────────────────────────────────────

    @Test
    fun `옛 판 기록도 그대로 읽힌다`() {
        // 판 3 이 되기 전 기록에는 두 줄이 없다. **없는 것이 정상**이고,
        // 그 뜻은 「한 번도 다시 분석하지 않았다」로 분명하다.
        val (meta, _) = seed()
        val text = encodeSessionMeta(meta.copy(schemaVersion = 2))
        val back = decodeSessionMeta(text).getOrThrow()
        assertNull(back.reanalyzedAtEpochMs)
        assertNull(back.originalTimelineName)
    }

    @Test
    fun `더 새 판은 거절한다`() {
        val (meta, _) = seed()
        val text = encodeSessionMeta(meta.copy(schemaVersion = SESSION_SCHEMA_VERSION + 1))
        assertTrue(decodeSessionMeta(text).isFailure)
    }

    @Test
    fun `다시 분석한 기록은 처음 것과 다른 숫자를 남긴다`() {
        val (meta, audio) = seed(offset = 100.0)
        val next = SessionReanalyzer(store).run(meta.id, audio, settings(120.0), 1L).getOrThrow()
        assertNotEquals(meta.maxDb, next.maxDb, 1e-9)
    }

    // ── 3회차 검토(R3-01·02·04)로 더한 것 ──────────────

    /**
     * **게시 도중에 죽어도 반쪽으로 남지 않는다**(독립 검토 R3-02).
     *
     * 표시 파일이 생긴 뒤에 죽은 상황을 손으로 만든다. 다음에 열 때
     * **타임라인과 겉장이 함께** 활성이 되어야 한다 — 하나만 옮겨지면
     * 그래프·CSV·PDF 가 서로 맞지 않는 설정으로 값을 해석한다.
     */
    @Test
    fun `게시 도중 죽어도 다음에 열 때 마저 옮긴다`() {
        val (meta, _) = seed(offset = 100.0)
        val dir = store.dirOf(meta.id)

        // 새 판을 옆에 만들어 둔 상태(= 게시 직전에 죽음).
        val staged = File(dir, SessionStore.STAGING_DIR).apply { mkdirs() }
        val newMeta = meta.copy(calibrationOffsetDb = 150.0, maxDb = 130.0)
        File(staged, SessionStore.META_NAME).writeText(encodeSessionMeta(newMeta))
        val marker = byteArrayOf(1, 2, 3)
        File(staged, SessionStore.TIMELINE_NAME).writeBytes(marker)
        File(dir, SessionStore.READY_NAME).writeText(meta.id)

        // 다음에 열면 마저 옮긴다.
        val after = store.readMeta(meta.id).getOrThrow()
        assertEquals(150.0, after.calibrationOffsetDb, 1e-9)
        assertTrue(
            "겉장만 옮기고 타임라인은 두었다",
            marker.contentEquals(store.timelineFile(meta.id).readBytes()),
        )
        assertTrue("치우지 않았다", !File(dir, SessionStore.READY_NAME).exists())
    }

    /**
     * **반쪽만 놓였으면 밀어 넣지 않는다.**
     *
     * 겉장만 있고 타임라인이 없는데 옮기면 **새 잣대로 옛 행을 읽는다** —
     * 이번 회차가 지적한 바로 그 어긋남이다. 표시 파일이 있어도
     * **둘 다 있어야** 옮긴다.
     */
    @Test
    fun `겉장만 놓였으면 옮기지 않는다`() {
        val (meta, _) = seed(offset = 100.0)
        val dir = store.dirOf(meta.id)
        val staged = File(dir, SessionStore.STAGING_DIR).apply { mkdirs() }
        File(staged, SessionStore.META_NAME)
            .writeText(encodeSessionMeta(meta.copy(calibrationOffsetDb = 999.0)))
        // 타임라인은 일부러 안 놓는다.
        File(dir, SessionStore.READY_NAME).writeText(meta.id)

        assertEquals(
            "반쪽을 밀어 넣었다",
            100.0,
            store.readMeta(meta.id).getOrThrow().calibrationOffsetDb,
            1e-9,
        )
        // **그리고 치운다.** 남겨 두면 열 때마다 같은 반쪽을 다시 보려 든다.
        assertTrue("표시 파일이 남았다", !File(dir, SessionStore.READY_NAME).exists())
        assertTrue("반쪽 판이 남았다", !staged.exists())
    }

    /** 표시 파일이 없으면 **아무 일도 안 한다.** 만들다 만 것을 밀어 넣지 않는다. */
    @Test
    fun `표시 파일이 없으면 옆의 것을 밀어 넣지 않는다`() {
        val (meta, _) = seed(offset = 100.0)
        val dir = store.dirOf(meta.id)
        File(dir, SessionStore.STAGING_DIR).apply { mkdirs() }
            .let { File(it, SessionStore.META_NAME).writeText(encodeSessionMeta(meta.copy(calibrationOffsetDb = 999.0))) }

        assertEquals(100.0, store.readMeta(meta.id).getOrThrow().calibrationOffsetDb, 1e-9)
    }

    /**
     * **분석 가중이 실제로 걸리고, 겉장이 그것을 말한다**(독립 검토 R3-04).
     *
     * 예전에는 겉장에 옛 값을 남기면서 엔진은 기본값(Z)으로 돌았다 —
     * **적힌 것과 셈한 것이 달랐다.**
     */
    @Test
    fun `분석 가중이 겉장을 따라간다`() {
        val (meta, audio) = seed()
        val next = SessionReanalyzer(store).run(
            meta.id,
            audio,
            settings(120.0).copy(analysisWeighting = Weighting.C),
            1L,
        ).getOrThrow()
        assertEquals(Weighting.C, next.analysisWeighting)
    }

    /** 그리고 **숫자도 따라간다** — 라벨만 바뀌면 고친 것이 아니다. */
    @Test
    fun `분석 가중을 바꾸면 대역 값이 달라진다`() {
        val (meta, audio) = seed()
        val r = SessionReanalyzer(store)
        r.run(meta.id, audio, settings(120.0).copy(analysisWeighting = Weighting.Z), 1L).getOrThrow()
        val z = rowsOf(meta.id)[1].bands.copyOf()
        r.run(meta.id, audio, settings(120.0).copy(analysisWeighting = Weighting.A), 2L).getOrThrow()
        val a = rowsOf(meta.id)[1].bands

        val gaps = z.indices.map { kotlin.math.abs(z[it] - a[it]).toDouble() }
        assertTrue("가중을 바꿔도 대역이 그대로다 — 엔진에 안 걸린 것 같다", gaps.max() > 1.0)
    }
}
