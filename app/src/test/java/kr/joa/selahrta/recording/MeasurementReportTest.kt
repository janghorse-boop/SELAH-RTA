package kr.joa.selahrta.recording

import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.calibration.CalibrationSource
import kr.joa.selahrta.domain.CalibrationState
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.CurveReading
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **리포트는 조건과 측정값을 함께 적는다**(담당자 지시 2026-09-27).
 *
 * 여기서 지키는 두 줄:
 *
 * 1. **없는 것을 있는 것처럼 적지 않는다.** 옛 기록의 빈 칸은
 *    「기록 없음」이고, 안드로이드가 안 알려 준 것은 「확인 불가」다.
 *    둘 다 「없었다」가 아니다.
 * 2. **믿음 등급이 맨 앞에 온다.** 미보정·기종 기본값·찌그러짐은
 *    숫자보다 먼저 읽혀야 한다.
 */
class MeasurementReportTest {

    private fun meta(
        conditions: MeasurementConditions = MeasurementConditions(),
        referenceOnly: Boolean = false,
        offset: Double = 118.0,
        leq: Double = 72.3,
        curveApplied: Boolean = false,
        curveLabel: String = "",
        events: List<SessionEvent> = emptyList(),
        dropped: Int = 0,
        clippedRows: Int? = 0,
        leqWindowMs: Long = 10_000L,
    ) = SessionMeta(
        id = "s1",
        startedAtEpochMs = 1_700_000_000_000L,
        endedAtEpochMs = 1_700_000_060_000L,
        durationMs = 60_000L,
        deviceKey = "BuiltIn|SM-S918N",
        deviceLabel = "SM-S918N",
        micKind = MicKind.BuiltIn,
        sampleRate = 48_000,
        encoding = "Float",
        channelCount = 1,
        channelIndex = 0,
        calibrationOffsetDb = offset,
        referenceOnly = referenceOnly,
        curveApplied = curveApplied,
        curveLabel = curveLabel,
        weighting = Weighting.A,
        timeWeight = TimeWeight.Fast,
        leqWindowMs = leqWindowMs,
        leqDb = leq,
        minDb = 55.0,
        maxDb = 88.0,
        peakDb = 96.0,
        events = events,
        droppedPackets = dropped,
        clippedRows = clippedRows,
        conditions = conditions,
    )

    private fun value(m: SessionMeta, label: String): String =
        buildReport(m).flatMap { it.lines }.first { it.labelKo == label }.valueKo

    /** 실기기에서 실제로 나온 조건 그대로. */
    private val s23 = MeasurementConditions(
        audioSource = CaptureSource.VoiceRecognition,
        unprocessedSupported = false,
        agcDisabled = true,
        nsDisabled = true,
        aecDisabled = true,
        routedAddress = "bottom",
        activeMicCombo = "22",
        calibrationState = CalibrationState.GlobalCalibrated,
        calibrationSource = CalibrationSource.Calibrator,
    )

    // ── 조건을 적는가 ──────────────────────────────────────

    @Test
    fun `다섯 자리로 나눠 적는다`() {
        val titles = buildReport(meta()).map { it.titleKo }
        assertEquals(
            listOf("언제 쟀나", "무엇으로 쟀나", "어떤 잣대로 쟀나", "잰 값", "재는 동안"),
            titles,
        )
    }

    @Test
    fun `실기기 조건이 그대로 적힌다`() {
        val m = meta(conditions = s23)
        // 이름표가 「입력 기기」 → 「측정 기기」 로 바뀌었다(담당자 지시
        // 2026-09-28). 리포트를 읽는 사람이 보는 글자라 시험도 같이 옮긴다.
        assertEquals("SM-S918N", value(m, "측정 기기"))
        assertEquals("bottom", value(m, "마이크 자리"))
        assertEquals("22", value(m, "활성 마이크"))
        assertEquals(CaptureSource.VoiceRecognition.labelKo, value(m, "입력 경로"))
        assertEquals("아니요", value(m, "가공 없는 입력 지원(기기 알림)"))
        assertEquals("48000 Hz · Float", value(m, "격자"))
        assertEquals("1개(모노)", value(m, "채널"))
    }

    @Test
    fun `가공이 모두 꺼졌으면 그렇게 적는다`() {
        assertTrue(value(meta(conditions = s23), "신호 가공").contains("모두 꺼짐"))
    }

    @Test
    fun `살아 있는 가공만 이름을 적는다`() {
        val m = meta(
            conditions = s23.copy(agcDisabled = false, nsDisabled = true, aecDisabled = true),
        )
        val v = value(m, "신호 가공")
        assertTrue(v, v.contains("자동 게인"))
        assertFalse(v, v.contains("잡음 억제"))
    }

    // ── 모르는 것을 지어내지 않는가 ───────────────────────

    /**
     * **옛 기록(판 1)에는 조건 칸이 없다.** 그것을 「가공이 없었다」나
     * 「확인했다」로 읽으면, 잰 적 없는 것을 잰 것처럼 말하게 된다.
     */
    @Test
    fun `옛 기록의 빈 칸은 기록 없음이다`() {
        val m = meta()
        assertEquals(NOT_RECORDED_KO, value(m, "입력 경로"))
        assertEquals(NOT_RECORDED_KO, value(m, "가공 없는 입력 지원(기기 알림)"))
        assertEquals(NOT_RECORDED_KO, value(m, "신호 가공"))
        assertEquals(NOT_RECORDED_KO, value(m, "무엇에 맞췄나"))
    }

    /** 안드로이드가 안 알려 준 것은 「없다」가 아니라 「확인 불가」다. */
    @Test
    fun `안드로이드가 말해 주지 않은 것은 확인 불가다`() {
        val m = meta(conditions = s23.copy(routedAddress = "", activeMicCombo = ""))
        assertEquals(UNKNOWN_KO, value(m, "마이크 자리"))
        assertEquals(UNKNOWN_KO, value(m, "활성 마이크"))
    }

    /**
     * 셋 중 하나라도 모르면 「깨끗하다」고 말할 수 없다.
     */
    @Test
    fun `가공을 하나라도 모르면 기록 없음이다`() {
        val m = meta(conditions = s23.copy(nsDisabled = null))
        assertEquals(NOT_RECORDED_KO, value(m, "신호 가공"))
    }

    @Test
    fun `없는 측정값을 0으로 적지 않는다`() {
        val m = meta(leq = Double.NaN)
        assertEquals(NOT_RECORDED_KO, value(m, "Leq"))
        assertEquals("88.0 dB", value(m, "MAX"))
    }

    /**
     * **선택 칸을 없애도 옛 기록의 뜻은 그대로다**(독립 검토 UIS-06).
     *
     * 2026-09-28 까지 `leqWindowMs = -1` 은 「전체(측정 시작부터)」였다.
     * 그 칸을 뺐다고 -1 을 「기록 없음」으로 적으면, **그때 분명히 적어
     * 둔 조건을 적지 않은 것으로 바꿔** 버린다. 새 측정에서 고를 수 없게
     * 하는 일과 옛 기록을 읽는 일은 별개다.
     */
    @Test
    fun `옛 기록의 Leq 전체 구간을 그대로 읽는다`() {
        assertEquals("전체(측정 시작부터)", value(meta(leqWindowMs = -1L), "Leq 구간"))
    }

    /** -1 만 뜻이 있다. 0 과 그 밖의 음수는 여전히 알 수 없는 값이다. */
    @Test
    fun `뜻을 모르는 Leq 구간은 기록 없음이다`() {
        assertEquals(NOT_RECORDED_KO, value(meta(leqWindowMs = 0L), "Leq 구간"))
        assertEquals(NOT_RECORDED_KO, value(meta(leqWindowMs = -2L), "Leq 구간"))
        assertEquals(NOT_RECORDED_KO, value(meta(leqWindowMs = -1000L), "Leq 구간"))
    }

    /** 양수는 예전대로 초로 적는다. */
    @Test
    fun `Leq 구간은 초로 적는다`() {
        assertEquals("30초", value(meta(leqWindowMs = 30_000L), "Leq 구간"))
    }

    // ── 믿음 등급 ──────────────────────────────────────────

    @Test
    fun `미보정이면 맨 앞에 경고한다`() {
        val m = meta(
            conditions = s23.copy(calibrationState = CalibrationState.Uncalibrated),
            referenceOnly = true,
        )
        assertTrue(reportWarningsKo(m).first().contains("미보정"))
        assertEquals("보정 안 함", value(m, "SPL 보정"))
    }

    /** 기종 기본값은 미보정과도, 보정됨과도 다른 말을 해야 한다. */
    @Test
    fun `기종 기본값은 따로 말한다`() {
        val m = meta(
            conditions = s23.copy(calibrationState = CalibrationState.FactoryDefault),
            referenceOnly = true,
        )
        val first = reportWarningsKo(m).first()
        assertTrue(first, first.contains("기종의 기본값"))
        assertTrue(first, first.contains("이 기기를 잰 값은 아닙니다"))
        assertEquals("기종 기본값", value(m, "SPL 보정"))
    }

    @Test
    fun `가공 없는 입력이 아니면 그 사실을 적는다`() {
        val warn = reportWarningsKo(meta(conditions = s23))
        assertTrue("$warn", warn.any { it.contains("가공 없는 입력을 지원하지 않아") })
    }

    /**
     * **찌그러짐은 사건이 아니라 행에 적힌다.**
     *
     * 예전에는 `SessionEventKind.Clipped` 를 세었는데 그 사건은 **아무
     * 데서도 만들어지지 않는다** — 실기기에서 PEAK 이 만재(0dBFS)인
     * 기록에도 「없음」이라 적혔다(2026-09-27).
     */
    @Test
    fun `찌그러진 행이 있으면 말한다`() {
        val m = meta(clippedRows = 12)
        assertTrue(reportWarningsKo(m).any { it.contains("찌그러진") })
        assertEquals("12행", value(m, "찌그러짐"))
    }

    @Test
    fun `사건으로는 찌그러짐을 세지 않는다`() {
        // 이 사건은 만들어지지 않지만, 있더라도 행이 0이면 「없음」이다.
        val m = meta(clippedRows = 0, events = listOf(SessionEvent(100, SessionEventKind.Clipped)))
        assertEquals("없음", value(m, "찌그러짐"))
        assertFalse(reportWarningsKo(m).any { it.contains("찌그러진") })
    }

    /** **0 과 「모름」은 다르다.** 0 으로 때우면 찌그러진 기록을 안심시킨다. */
    @Test
    fun `세어 둔 적이 없으면 기록 없음이다`() {
        val m = meta(clippedRows = null)
        assertEquals(NOT_RECORDED_KO, value(m, "찌그러짐"))
        assertFalse(reportWarningsKo(m).any { it.contains("찌그러진") })
    }

    @Test
    fun `놓친 조각이 있으면 말한다`() {
        assertTrue(reportWarningsKo(meta(dropped = 3)).any { it.contains("3개") })
    }

    // ── 곡선 ───────────────────────────────────────────────

    /** 사람이 확인한 것과 앱이 관례로 정한 것은 다르다. */
    @Test
    fun `곡선은 규약과 누가 정했는지를 함께 적는다`() {
        val confirmed = meta(
            conditions = s23.copy(
                curveReading = CurveReading.Correction,
                curveReadingConfirmed = true,
            ),
            curveApplied = true,
            curveLabel = "umik.cal",
        )
        val v = value(confirmed, "주파수 응답 보정")
        assertTrue(v, v.contains("umik.cal"))
        assertTrue(v, v.contains(CurveReading.Correction.labelKo))
        assertTrue(v, v.contains("사람이 확인"))

        val auto = meta(
            conditions = s23.copy(curveReading = CurveReading.Response),
            curveApplied = true,
            curveLabel = "emm6.cal",
        )
        assertTrue(value(auto, "주파수 응답 보정").contains("관례로 읽음"))
    }

    @Test
    fun `곡선이 없으면 걸지 않았다고 적는다`() {
        assertEquals("걸지 않음", value(meta(), "주파수 응답 보정"))
    }

    // ── 화면에 그대로 나가는 말 ───────────────────────────

    @Test
    fun `문구에 마크다운이 없다`() {
        val m = meta(conditions = s23, events = listOf(SessionEvent(1, SessionEventKind.Clipped)))
        buildReport(m).flatMap { it.lines }.forEach {
            assertFalse(it.labelKo, it.labelKo.contains("**"))
            assertFalse(it.valueKo, it.valueKo.contains("**"))
        }
        reportWarningsKo(m).forEach { assertFalse(it, it.contains("**")) }
    }

    // ── 겉장에 실제로 남는가 ──────────────────────────────

    /**
     * **적는 것과 남는 것은 다르다.** 겉장에 못 쓰면 리포트는 다음에 열
     * 때 전부 「기록 없음」이 된다.
     */
    @Test
    fun `조건이 겉장을 왕복한다`() {
        val m = meta(
            conditions = s23.copy(
                curveReading = CurveReading.Correction,
                curveReadingConfirmed = true,
            ),
        )
        val back = decodeSessionMeta(encodeSessionMeta(m)).getOrThrow()
        assertEquals(m.conditions, back.conditions)
    }

    /**
     * **판 1 로 적힌 옛 기록도 그대로 읽힌다.** 조건 칸이 없을 뿐이다.
     */
    @Test
    fun `옛 판 기록도 읽힌다`() {
        val v2 = encodeSessionMeta(meta(conditions = s23))
        val v1 = v2.lineSequence()
            .filterNot { it.startsWith("cond.") }
            .joinToString("\n") {
                // **판 번호를 글자로 박지 않는다.** 예전에는 `2` 를 찾았는데,
                // 판이 3 으로 오르자 바꿔치기가 안 먹어 **판 3 을 넣고
                // 「1 이어야 한다」로 견주다** 터졌다.
                if (it.startsWith("schemaVersion=")) "schemaVersion=1" else it
            }
        val back = decodeSessionMeta(v1).getOrThrow()
        assertEquals(1, back.schemaVersion)
        assertFalse("옛 기록에 조건이 있다고 말한다", back.conditions.recorded)
        assertEquals(NOT_RECORDED_KO, value(back, "입력 경로"))
    }

    // ── 5회차 검토(R5-03): 시도와 지원을 가른다 ───────────

    /**
     * **「가공 없는 입력 지원」은 기기가 스스로 알린 값이다.**
     *
     * USB 에서는 폰이 지원한다고 알리지 않아도 시도한다. 그런데 그
     * **시도 여부**가 기록으로 흘러들어, 실기기에서 속성이 false 인데
     * 보고서가 **「예」**라고 적었다:
     *
     * ```
     * 기기 속성 SUPPORT_AUDIO_SOURCE_UNPROCESSED=false
     * source=9(UNPROCESSED) 상태=3 읽음=81920 실제기기=UMC404HD
     * ```
     *
     * 둘은 다른 사실이다 — **열렸다는 것이 가공이 없다는 뜻은 아니다.**
     */
    @Test
    fun `지원은 기기가 알린 값으로 적는다`() {
        val m = meta(
            conditions = s23.copy(
                audioSource = CaptureSource.Unprocessed,
                unprocessedSupported = false,
            ),
        )
        assertEquals("아니요", value(m, "가공 없는 입력 지원(기기 알림)"))
        assertEquals("가공 없음 (UNPROCESSED)", value(m, "입력 경로"))
    }

    /** **지원을 안 알렸는데 그 경로로 열렸으면 그 사실을 따로 적는다.** */
    @Test
    fun `확인되지 않은 무가공은 그렇게 적는다`() {
        val m = meta(
            conditions = s23.copy(
                audioSource = CaptureSource.Unprocessed,
                unprocessedSupported = false,
            ),
        )
        assertTrue(
            value(m, "무가공 근거").contains("확인되지 않음"),
        )
    }

    /** **아무 때나 붙이지 않는다.** 기기가 지원한다고 알렸으면 그 줄이 없다. */
    @Test
    fun `지원을 알린 경우에는 그 줄이 없다`() {
        val m = meta(
            conditions = s23.copy(
                audioSource = CaptureSource.Unprocessed,
                unprocessedSupported = true,
            ),
        )
        assertTrue(
            buildReport(m).flatMap { it.lines }.none { it.labelKo == "무가공 근거" },
        )
    }

    /** 가공 없는 경로가 아니면 **그 줄을 붙일 까닭이 없다.** */
    @Test
    fun `가공 없는 경로가 아니면 그 줄이 없다`() {
        assertTrue(
            buildReport(meta(conditions = s23)).flatMap { it.lines }
                .none { it.labelKo == "무가공 근거" },
        )
    }
}
