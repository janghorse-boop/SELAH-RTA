package kr.joa.selahrta.recording

import kr.joa.selahrta.domain.ChurchSegment
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 기록의 겉장이 **적힌 대로 돌아오는가**(Phase 10).
 *
 * 겉장이 틀리면 타임라인이 멀쩡해도 기록 전체가 못 쓰게 된다 — 어느
 * 마이크로 어떤 보정으로 잰 것인지 모르는 숫자는 숫자가 아니다.
 */
class SessionMetaTest {

    private fun sample(
        events: List<SessionEvent> = emptyList(),
        deviceLabel: String = "SM-S918N",
        memo: String = "",
        leqWindowMs: Long = 60_000L,
    ) = SessionMeta(
        id = "2026-09-26T09-00-00-abc",
        startedAtEpochMs = 1_790_000_000_000L,
        endedAtEpochMs = 1_790_000_600_000L,
        durationMs = 600_000L,
        deviceKey = "BuiltIn|SM-S918N",
        deviceLabel = deviceLabel,
        micKind = MicKind.BuiltIn,
        sampleRate = 48_000,
        encoding = "PCM_FLOAT",
        channelCount = 1,
        channelIndex = 0,
        calibrationOffsetDb = 118.4,
        referenceOnly = false,
        curveApplied = true,
        curveLabel = "EMM-6_17860",
        weighting = Weighting.A,
        timeWeight = TimeWeight.Fast,
        leqWindowMs = leqWindowMs,
        leqDb = 71.2,
        minDb = 48.0,
        maxDb = 88.5,
        peakDb = 101.3,
        events = events,
        droppedPackets = 0,
        memo = memo,
    )

    @Test
    fun `겉장이 그대로 왕복한다`() {
        val m = sample()
        val back = decodeSessionMeta(encodeSessionMeta(m)).getOrThrow()
        assertEquals(m, back)
    }

    /**
     * **옛 기록의 -1 이 살아서 돌아온다**(독립 검토 UIS-06, 2026-09-28).
     *
     * 2026-09-28 까지 -1 은 「전체(측정 시작부터)」였다. 그 선택 칸을
     * 없애면서 이 왕복 시험을 함께 지웠는데, **지울 것이 아니었다** —
     * 새 측정이 그 값을 만들지 않는 것과 이미 저장된 파일에서 그 값을
     * 읽어 내는 것은 다른 일이다. 여기서 뭉개지면 옛 기록의 조건이
     * 조용히 사라진다.
     */
    @Test
    fun `옛 기록의 Leq 전체 구간 값이 왕복한다`() {
        val m = sample(leqWindowMs = -1L)
        val back = decodeSessionMeta(encodeSessionMeta(m)).getOrThrow()
        assertEquals(-1L, back.leqWindowMs)
        assertEquals(m, back)
    }

    @Test
    fun `사건 목록도 함께 왕복한다`() {
        val m = sample(
            events = listOf(
                SessionEvent(0L, SessionEventKind.SegmentChange, "설교", ChurchSegment.Sermon),
                SessionEvent(300_000L, SessionEventKind.SegmentChange, "찬양", ChurchSegment.Worship),
                SessionEvent(420_000L, SessionEventKind.Dropout, "읽기 오류 1회"),
            ),
        )
        val back = decodeSessionMeta(encodeSessionMeta(m)).getOrThrow()
        assertEquals(3, back.events.size)
        assertEquals(ChurchSegment.Worship, back.events[1].segment)
        assertNull("끊김에는 구간이 없다", back.events[2].segment)
        assertEquals(m.events, back.events)
    }

    /**
     * **이름에 쉼표·등호가 들어도 깨지지 않는다.**
     *
     * 실제 기기 이름이 그렇다 — `USB-Audio - UMC404HD 192k (card=1;device=0)`.
     */
    @Test
    fun `이름에 특수문자가 들어도 왕복한다`() {
        val odd = "USB-Audio - UMC404HD 192k (card=1;device=0), 1번"
        val back = decodeSessionMeta(encodeSessionMeta(sample(deviceLabel = odd))).getOrThrow()
        assertEquals(odd, back.deviceLabel)
    }

    @Test
    fun `여러 줄 메모도 왕복한다`() {
        val memo = "설교 중 하울링 두 번\n두 번째는 무선마이크 쪽"
        val back = decodeSessionMeta(encodeSessionMeta(sample(memo = memo))).getOrThrow()
        assertEquals(memo, back.memo)
    }

    /**
     * **모르는 판은 읽지 않는다.**
     *
     * 모르는 칸을 0 으로 채워 읽으면 「보정 없음 · 길이 0」짜리 기록이
     * 되어, 화면은 멀쩡한데 숫자만 거짓이 된다.
     */
    @Test
    fun `더 새 판은 읽지 않는다`() {
        // **판 번호를 박아 두지 않는다.** 판이 올라가면 이 치환이 조용히
        // 아무 일도 안 하고, 그러면 이 시험은 「더 새 판을 막는가」가
        // 아니라 **정상 기록을 읽고 통과**하게 된다(v2 에서 겪었다).
        val text = encodeSessionMeta(sample())
            .replace("schemaVersion=$SESSION_SCHEMA_VERSION", "schemaVersion=99")
        assertTrue("치환이 아무 일도 안 했다", text.contains("schemaVersion=99"))
        val r = decodeSessionMeta(text)
        assertTrue("더 새 판을 읽었다", r.isFailure)
    }

    @Test
    fun `기록 파일이 아니면 막는다`() {
        assertTrue(decodeSessionMeta("hello\nworld").isFailure)
        assertTrue(decodeSessionMeta("").isFailure)
    }

    /**
     * **반쯤 읽지 않는다.** 칸이 빠진 파일을 「0 이 든 기록」으로 읽으면
     * 사람이 알아챌 방법이 없다.
     */
    @Test
    fun `칸이 빠지면 막는다`() {
        val text = encodeSessionMeta(sample())
            .lineSequence()
            .filterNot { it.startsWith("calibrationOffsetDb=") }
            .joinToString("\n")
        val r = decodeSessionMeta(text)
        assertTrue("빠진 칸을 그냥 읽었다", r.isFailure)
        assertTrue(
            "무엇이 빠졌는지 말해야 한다",
            r.exceptionOrNull()?.message?.contains("calibrationOffsetDb") == true,
        )
    }

    @Test
    fun `읽을 수 없는 숫자는 막는다`() {
        val text = encodeSessionMeta(sample()).replace("leqDb=71.2", "leqDb=NaN")
        assertTrue("NaN 을 숫자로 받았다", decodeSessionMeta(text).isFailure)
    }

    @Test
    fun `미보정도 그대로 남는다`() {
        val m = sample().copy(referenceOnly = true, calibrationOffsetDb = 120.0)
        val back = decodeSessionMeta(encodeSessionMeta(m)).getOrThrow()
        assertTrue("미보정 표시가 사라졌다", back.referenceOnly)
        assertNotNull(back)
    }

    // ── 세 가중 ─────────────────────────────────────────

    /** 셋이 따로 왕복해야 PEAK 이 어느 잣대인지 알 수 있다. */
    @Test
    fun `세 가중이 왕복한다`() {
        val m = sample().copy(
            weighting = Weighting.C,
            peakWeighting = Weighting.Z,
            analysisWeighting = Weighting.A,
        )
        val back = decodeSessionMeta(encodeSessionMeta(m)).getOrThrow()
        assertEquals(Weighting.C, back.weighting)
        assertEquals(Weighting.Z, back.peakWeighting)
        assertEquals(Weighting.A, back.analysisWeighting)
    }

    /**
     * **옛 기록을 짐작으로 채우지 않는다.**
     *
     * 없는 것을 Z 라고 적으면 「이 기록의 PEAK 은 Z 로 쟀다」는 거짓말이
     * 된다 — 실제로는 무엇이었는지 아무도 모른다.
     */
    @Test
    fun `옛 기록에는 새 가중이 없다`() {
        val full = encodeSessionMeta(
            sample().copy(peakWeighting = Weighting.C, analysisWeighting = Weighting.C),
        )
        // 두 줄이 실제로 있었는지 먼저 본다 — 없으면 아래 지우기가
        // 아무 일도 안 한 채 시험이 통과한다.
        assertTrue("peakWeighting 줄이 없다", full.contains("peakWeighting="))
        assertTrue("analysisWeighting 줄이 없다", full.contains("analysisWeighting="))

        val old = full.lineSequence()
            .filterNot { it.startsWith("peakWeighting=") }
            .filterNot { it.startsWith("analysisWeighting=") }
            .joinToString(System.lineSeparator())

        val back = decodeSessionMeta(old).getOrThrow()
        assertNull("없는 PEAK 가중을 짐작으로 채웠다", back.peakWeighting)
        assertNull("없는 분석 가중을 짐작으로 채웠다", back.analysisWeighting)
        // 나머지는 멀쩡히 읽혀야 한다 — 새 열쇠가 없다고 통째로 거절하면
        // 옛 기록이 전부 못 읽는 것이 된다.
        assertEquals(sample().id, back.id)
    }

}
