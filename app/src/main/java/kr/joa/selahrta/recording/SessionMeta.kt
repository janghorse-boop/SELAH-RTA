package kr.joa.selahrta.recording

import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.calibration.Reader
import kr.joa.selahrta.calibration.parseLines
import kr.joa.selahrta.calibration.put
import kr.joa.selahrta.domain.ChurchSegment
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting

/**
 * 한 번의 측정이 남긴 **기록의 겉장**(명세 12장 · 녹음 설계 §5.3).
 *
 * ## 왜 타임라인과 따로 두는가
 *
 * 타임라인(`.bin`)은 **고정 길이 행**이라 빠르게 건너뛸 수 있는 대신
 * 「무엇으로 잰 것인가」를 담을 자리가 없다. 그 답이 여기 있다 —
 * 어느 마이크로, 어떤 보정으로, 어떤 설정으로 쟀는지.
 *
 * ## 이 파일 하나로 목록을 그린다
 *
 * 기록 화면은 세션이 수십 개여도 **겉장만 읽어** 목록을 만든다.
 * 타임라인은 하나를 열어 볼 때만 읽는다 — 2시간이면 1.4MB 라, 목록을
 * 그리려고 전부 읽으면 화면이 멈춘다.
 *
 * ## 보정 상태를 반드시 남긴다
 *
 * [referenceOnly] 가 이 기록 전체의 **믿음 등급**이다. 미보정으로 잰
 * 세션을 나중에 열어 보고 그 숫자를 음압이라 부르면 안 된다 — 화면은
 * 물론 CSV 에도 그 사실이 함께 나간다.
 */
data class SessionMeta(
    val id: String,
    val schemaVersion: Int = SESSION_SCHEMA_VERSION,

    /** 사람이 보는 시각. **표시 전용이다** — 벽시계는 뒤로 갈 수 있다. */
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,

    /**
     * 잰 길이(ms). **벽시계 뺄셈이 아니다.**
     *
     * 시계를 맞추거나 서머타임이 바뀌면 벽시계 뺄셈은 음수가 되거나
     * 한 시간 뛴다. 길이는 단조 시계로 센 값을 그대로 적는다.
     */
    val durationMs: Long,

    // ---- 무엇으로 쟀는가 ----
    val deviceKey: String,
    val deviceLabel: String,
    val micKind: MicKind,
    val sampleRate: Int,
    val encoding: String,
    val channelCount: Int,
    val channelIndex: Int,

    // ---- 어떤 잣대로 쟀는가 ----
    /** 전대역 보정값(dB). 미보정이면 짐작한 만재 음압이 들어 있다. */
    val calibrationOffsetDb: Double,
    /** **이 숫자를 음압이라 불러도 되는가.** false 여야 부를 수 있다. */
    val referenceOnly: Boolean,
    /** 주파수 보정 곡선이 걸려 있었는가. 걸렸으면 어디서 온 것인지. */
    val curveApplied: Boolean,
    val curveLabel: String = "",

    // ---- 설정 ----
    val weighting: Weighting,
    val timeWeight: TimeWeight,
    val leqWindowMs: Long,

    // ---- 결과 요약 ----
    /**
     * 세션 전체의 등가소음도(dB). **행에서 셈하지 않는다.**
     *
     * 행이 담는 것은 시간가중 레벨의 마지막·최대값이라, 그것을 평균해도
     * 등가소음도가 되지 않는다. 엔진이 에너지로 누적한 값을 그대로 적는다.
     */
    val leqDb: Double,
    val minDb: Double,
    val maxDb: Double,
    val peakDb: Double,

    /** 세션 안에서 일어난 일들. 구간 바뀜·기기 전환·끊김. */
    val events: List<SessionEvent> = emptyList(),

    /** 큐가 밀려 버린 조각 수. **0 이 아니면 화면이 말한다.** */
    val droppedPackets: Int = 0,

    /** 어느 셈으로 만든 기록인가. 셈이 바뀌면 옛 기록과 견줄 수 없다. */
    val analysisVersion: Int = ANALYSIS_VERSION,

    /** 사람이 적는 메모(명세 12장). */
    val memo: String = "",
) {
    /** 목록에 적을 이름. 시작 시각과 구간으로 만든다. */
    val hasTimeline: Boolean get() = durationMs > 0
}

/**
 * 세션 안에서 일어난 일 하나.
 *
 * **구간(설교/찬양)도 사건으로 적는다.** 행마다 구간 바이트를 넣는 쪽도
 * 있었지만, 그러면 판 1 의 행 형식([TimelineFormat])을 바꿔야 한다 —
 * 이미 독립 검증을 받은 형식이다. 구간은 예배 중 몇 번 바뀔 뿐이라
 * 사건으로 적는 편이 작고, 바뀐 **시각**이 그대로 남는다.
 */
data class SessionEvent(
    /** 세션 시작부터 몇 ms 뒤인가. 벽시계가 아니다. */
    val atMs: Long,
    val kind: SessionEventKind,
    /** 사람이 읽을 한 줄. 구간 이름·기기 이름 같은 것. */
    val detailKo: String = "",
    /** 구간 바뀜일 때 그 구간. 아니면 null. */
    val segment: ChurchSegment? = null,
)

enum class SessionEventKind(val labelKo: String) {
    /** 설교↔찬양처럼 보는 구간이 바뀌었다. */
    SegmentChange("구간 바뀜"),

    /** 다른 마이크로 넘어갔다. **여기서 세션이 끊긴다.** */
    DeviceChange("기기 바뀜"),

    /** 소리가 끊겼다(읽기 오류·큐 밀림). */
    Dropout("끊김"),

    /** 입력이 찌그러졌다. */
    Clipped("찌그러짐"),

    /** 보정이 바뀌었다. 그 뒤 숫자는 다른 잣대다. */
    CalibrationChange("보정 바뀜"),
}

/**
 * 겉장 판 번호.
 *
 * 읽는 쪽은 **더 새 판을 만나면 읽지 않는다.** 모르는 칸을 0 으로 채워
 * 읽으면 「보정 없음」이나 「길이 0」이 되어 조용히 틀린 기록이 된다.
 */
const val SESSION_SCHEMA_VERSION = 1

/**
 * 셈의 판 번호. DSP 가 바뀌면 올린다.
 *
 * 옛 기록을 지우지 않는다 — 대신 **어느 셈으로 잰 것인지** 남겨,
 * 나중에 견줄 때 사람이 알 수 있게 한다.
 */
const val ANALYSIS_VERSION = 1

private const val SESSION_HEADER = "# SELAH RTA session"

/** 겉장을 글자로. [decodeSessionMeta] 와 짝이다. */
fun encodeSessionMeta(m: SessionMeta): String = buildString {
    append(SESSION_HEADER).append(" v").append(m.schemaVersion).append('\n')
    put("schemaVersion", m.schemaVersion)
    put("id", m.id)
    put("startedAtEpochMs", m.startedAtEpochMs)
    put("endedAtEpochMs", m.endedAtEpochMs)
    put("durationMs", m.durationMs)

    put("deviceKey", m.deviceKey)
    put("deviceLabel", m.deviceLabel)
    put("micKind", m.micKind.name)
    put("sampleRate", m.sampleRate)
    put("encoding", m.encoding)
    put("channelCount", m.channelCount)
    put("channelIndex", m.channelIndex)

    put("calibrationOffsetDb", m.calibrationOffsetDb)
    put("referenceOnly", m.referenceOnly)
    put("curveApplied", m.curveApplied)
    put("curveLabel", m.curveLabel)

    put("weighting", m.weighting.name)
    put("timeWeight", m.timeWeight.name)
    put("leqWindowMs", m.leqWindowMs)

    put("leqDb", m.leqDb)
    put("minDb", m.minDb)
    put("maxDb", m.maxDb)
    put("peakDb", m.peakDb)

    put("droppedPackets", m.droppedPackets)
    put("analysisVersion", m.analysisVersion)
    put("memo", m.memo)

    put("events.count", m.events.size)
    m.events.forEachIndexed { i, e ->
        put("events.$i.atMs", e.atMs)
        put("events.$i.kind", e.kind.name)
        put("events.$i.detailKo", e.detailKo)
        put("events.$i.segment", e.segment?.name ?: "")
    }
}

/**
 * 글자를 겉장으로.
 *
 * **모르는 판은 읽지 않는다.** 앞으로 칸이 늘어날 텐데, 모르는 채로
 * 0 을 채워 읽으면 「보정 없음 · 길이 0」짜리 기록이 되어 화면이
 * 조용히 거짓말을 한다.
 */
fun decodeSessionMeta(text: String): Result<SessionMeta> {
    val r = Reader(parseLines(text))

    val schema = r.int("schemaVersion")
    if (r.missing.contains("schemaVersion")) {
        return Result.failure(IllegalArgumentException("기록 파일이 아닙니다(판 번호가 없습니다)."))
    }
    if (schema > SESSION_SCHEMA_VERSION) {
        return Result.failure(
            IllegalArgumentException("더 새 판(v$schema)의 기록입니다. 앱을 올린 뒤 여십시오."),
        )
    }

    val micKind = r.enum<MicKind>("micKind")
    val weighting = r.enum<Weighting>("weighting")
    val timeWeight = r.enum<TimeWeight>("timeWeight")

    val count = r.int("events.count")
    val events = (0 until count).map { i ->
        SessionEvent(
            atMs = r.long("events.$i.atMs"),
            kind = r.enum<SessionEventKind>("events.$i.kind") ?: SessionEventKind.Dropout,
            detailKo = r.str("events.$i.detailKo"),
            segment = r.strOrNull("events.$i.segment")
                ?.takeIf { it.isNotEmpty() }
                ?.let { name -> ChurchSegment.entries.firstOrNull { it.name == name } },
        )
    }

    val meta = SessionMeta(
        id = r.str("id"),
        schemaVersion = schema,
        startedAtEpochMs = r.long("startedAtEpochMs"),
        endedAtEpochMs = r.long("endedAtEpochMs"),
        durationMs = r.long("durationMs"),
        deviceKey = r.str("deviceKey"),
        deviceLabel = r.str("deviceLabel"),
        micKind = micKind ?: MicKind.BuiltIn,
        sampleRate = r.int("sampleRate"),
        encoding = r.str("encoding"),
        channelCount = r.int("channelCount"),
        channelIndex = r.int("channelIndex"),
        calibrationOffsetDb = r.dbl("calibrationOffsetDb"),
        referenceOnly = r.bool("referenceOnly"),
        curveApplied = r.bool("curveApplied"),
        curveLabel = r.str("curveLabel"),
        weighting = weighting ?: Weighting.A,
        timeWeight = timeWeight ?: TimeWeight.Fast,
        leqWindowMs = r.long("leqWindowMs"),
        leqDb = r.dbl("leqDb"),
        minDb = r.dbl("minDb"),
        maxDb = r.dbl("maxDb"),
        peakDb = r.dbl("peakDb"),
        events = events,
        droppedPackets = r.int("droppedPackets"),
        analysisVersion = r.int("analysisVersion"),
        memo = r.str("memo"),
    )

    // **빠진 칸이 있으면 읽지 않는다.** 반쯤 읽은 기록은 화면에서
    // 정상처럼 보이는데 숫자만 0 이라, 사람이 알아채지 못한다.
    if (r.missing.isNotEmpty()) {
        return Result.failure(
            IllegalArgumentException("기록이 온전하지 않습니다(빠진 값: ${r.missing.take(3)})."),
        )
    }
    if (r.malformed.isNotEmpty()) {
        return Result.failure(
            IllegalArgumentException("기록에 읽을 수 없는 값이 있습니다(${r.malformed.take(3)})."),
        )
    }
    return Result.success(meta)
}
