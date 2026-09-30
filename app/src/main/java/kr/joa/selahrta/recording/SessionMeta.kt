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
/**
 * 메모로 받아 줄 길이.
 *
 * 겉장이 한없이 커지지 않게 막는다. 예배 한 번에 적을 말은 이 안에
 * 들어간다 — 「찬양 2부 · 에어컨 켜짐 · 설교 중 마이크 교체」가
 * 30자 남짓이다.
 */
const val MEMO_MAX = 500

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
    /** 음압(SPL·Leq·MIN·MAX)을 잰 가중. */
    val weighting: Weighting,
    /**
     * PEAK 을 잰 가중. **옛 기록에는 없다**(null).
     *
     * 없는 것을 Z 라고 적으면 「이 기록의 PEAK 은 Z 로 쟀다」는 거짓말이
     * 된다 — 그때 무엇이었는지 아무도 모른다. 리포트가 null 을
     * 「기록 없음」으로 적는다.
     */
    val peakWeighting: Weighting? = null,
    /** 분석 화면을 그린 가중. 옛 기록에는 없다(null). */
    val analysisWeighting: Weighting? = null,
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

    /**
     * **찌그러진 행의 수.** 모르면 null(옛 기록).
     *
     * 찌그러짐은 사건이 아니라 **행**에 적힌다(`TimelineRow.clipped`).
     * 그런데 겉장에 세어 두지 않으면 목록과 리포트가 1.4MB 짜리
     * 타임라인을 열어야 알 수 있다 — 그래서 저장할 때 한 번 센다.
     *
     * **0 과 「모름」은 다르다.** 옛 기록은 세어 둔 적이 없으므로 null
     * 이고, 화면은 「기록 없음」이라 적는다. 0 으로 채우면 **찌그러진
     * 기록을 「없음」이라고 안심시킨다** — 그 구간의 숫자는 전부 하한이다.
     */
    val clippedRows: Int? = null,

    /**
     * **소리도 파일로 담았는가.** 안 담았으면 null.
     *
     * 담는 것은 기본이 아니다 — 기록을 시작할 때마다 묻고, 사람이
     * 그렇다고 해야 담긴다. 그 사실이 기록에 남아야 나중에 「이 예배는
     * 소리가 있나」를 열어 보지 않고 알 수 있다.
     */
    val audio: RecordedAudio? = null,

    /** 어느 셈으로 만든 기록인가. 셈이 바뀌면 옛 기록과 견줄 수 없다. */
    val analysisVersion: Int = ANALYSIS_VERSION,

    /**
     * **다시 분석한 시각.** 한 번도 안 했으면 null.
     *
     * 이 값이 있으면 **아래 숫자는 잰 그날의 것이 아니다** — 소리는
     * 그대로지만 잣대가 바뀌었다. 견줄 때 사람이 알아야 한다.
     */
    val reanalyzedAtEpochMs: Long? = null,

    /**
     * **원본 타임라인이 남아 있는 파일 이름.** 없으면 null.
     *
     * 명세 Recording-E 의 완료 기준이 「원본 보존」이다. 다시 분석해도
     * 처음 잰 것이 그대로 있다는 **증거**가 되도록 이름을 겉장에 적는다 —
     * 파일만 두면 다음 사람이 그것이 무엇인지 모른다.
     */
    val originalTimelineName: String? = null,

    /** 사람이 적는 메모(명세 12장). */
    val memo: String = "",

    /**
     * **이 측정을 어떤 조건에서 쟀는가**(겉장 판 2).
     *
     * 판 1 로 적힌 옛 기록에는 없다 — 그때는 전부 비어 있고, 화면은
     * 「기록 없음」이라 적는다.
     */
    val conditions: MeasurementConditions = MeasurementConditions(),

    /**
     * **보정이 바뀐 자리들**(겉장 판 2).
     *
     * 행은 값과 함께 `epochId` 만 지니고 **보정은 읽을 때 건다**. 그런데
     * 이 표를 디스크에 안 남기면 다시 열었을 때 그 id 를 풀 길이 없다 —
     * 보정이 도중에 바뀐 기록은 **내보낼 때 한 가지 값으로 뭉뚱그려져
     * 조용히 틀린다.**
     *
     * 비어 있으면 옛 기록이다. 그때는 겉장의 [calibrationOffsetDb] 하나로
     * 내보내고, **그 사실을 CSV 머리말에 적는다.**
     */
    val epochs: List<RecordingEpoch> = emptyList(),
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
 * 기록에 함께 담긴 소리 파일.
 *
 * **담는 것은 기본이 아니다.** 예배 소리를 담는 일은 dB 숫자를 남기는
 * 것과 성격이 다르다 — 설교와 성도들의 목소리가 그대로 들어간다.
 */
data class RecordedAudio(
    val format: AudioFileFormat,
    val fileName: String,
    val bytes: Long,
    /** 밀려 버린 덩어리 수. **0 이 아니면 소리에 빈 자리가 있다.** */
    val droppedBlocks: Int = 0,
) {
    /** 사람이 읽을 크기. */
    fun sizeKo(): String = when {
        bytes >= 1_000_000L -> "%.1fMB".format(bytes / 1_000_000.0)
        bytes > 0L -> "%dKB".format(bytes / 1000)
        else -> "0KB"
    }
}

/**
 * 겉장 판 번호.
 *
 * 읽는 쪽은 **더 새 판을 만나면 읽지 않는다.** 모르는 칸을 0 으로 채워
 * 읽으면 「보정 없음」이나 「길이 0」이 되어 조용히 틀린 기록이 된다.
 *
 * - **v1** — 첫 판.
 * - **v2** — 잰 조건([MeasurementConditions])이 붙었다. v1 기록도 그대로
 *   읽힌다. 없는 칸은 「기록 없음」이지 「가공이 없었다」가 아니다.
 * - **v3** — 다시 분석한 시각과 원본 타임라인 이름이 붙었다(명세
 *   Recording-E). 없으면 **한 번도 다시 분석하지 않은 것**이다 —
 *   그 뜻이 분명해서 옛 기록도 그대로 읽힌다.
 */
const val SESSION_SCHEMA_VERSION = 3

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
    // **없으면 적지 않는다.** 빈 줄을 남기면 읽는 쪽이 「빈 값」과
    // 「없음」을 가리지 못한다.
    m.peakWeighting?.let { put("peakWeighting", it.name) }
    m.analysisWeighting?.let { put("analysisWeighting", it.name) }
    put("timeWeight", m.timeWeight.name)
    put("leqWindowMs", m.leqWindowMs)

    put("leqDb", m.leqDb)
    put("minDb", m.minDb)
    put("maxDb", m.maxDb)
    put("peakDb", m.peakDb)

    put("droppedPackets", m.droppedPackets)
    // **모르면 적지 않는다.** 0 과 「모름」은 다르다.
    m.clippedRows?.let { put("clippedRows", it) }

    m.audio?.let { a ->
        put("audio.format", a.format.name)
        put("audio.fileName", a.fileName)
        put("audio.bytes", a.bytes)
        put("audio.droppedBlocks", a.droppedBlocks)
    }
    put("analysisVersion", m.analysisVersion)
    // **안 한 일은 적지 않는다**(판 3). 빈 값으로 적어 두면 다음에 읽을
    // 때 「다시 분석했는데 시각이 없다」로 읽힌다.
    m.reanalyzedAtEpochMs?.let { put("reanalyzedAtEpochMs", it) }
    m.originalTimelineName?.let { put("originalTimelineName", it) }
    put("memo", m.memo)

    // ---- 잰 조건(판 2) ----
    //
    // **모르면 적지 않는다.** 빈 값으로 적어 두면 다음에 읽을 때
    // 「확인했는데 비어 있었다」가 된다.
    val c = m.conditions
    c.audioSource?.let { put("cond.audioSource", it.name) }
    c.unprocessedSupported?.let { put("cond.unprocessedSupported", it) }
    c.agcDisabled?.let { put("cond.agcDisabled", it) }
    c.nsDisabled?.let { put("cond.nsDisabled", it) }
    c.aecDisabled?.let { put("cond.aecDisabled", it) }
    if (c.routedAddress.isNotEmpty()) put("cond.routedAddress", c.routedAddress)
    if (c.activeMicCombo.isNotEmpty()) put("cond.activeMicCombo", c.activeMicCombo)
    c.calibrationState?.let { put("cond.calibrationState", it.name) }
    c.calibrationSource?.let { put("cond.calibrationSource", it.name) }
    c.curveReading?.let { put("cond.curveReading", it.name) }
    put("cond.curveReadingConfirmed", c.curveReadingConfirmed)

    // ---- 보정이 바뀐 자리(판 2) ----
    put("epochs.count", m.epochs.size)
    m.epochs.forEachIndexed { i, e ->
        put("epochs.$i.id", e.id)
        put("epochs.$i.startFrame", e.startFrame)
        put("epochs.$i.calibrationOffsetDb", e.calibrationOffsetDb)
        put("epochs.$i.isReferenceOnly", e.isReferenceOnly)
        put("epochs.$i.leqWindowMs", e.leqWindowMs)
    }

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
    // **없는 것이 정상이다.** 옛 기록에는 이 두 줄이 없다 — 없다고
    // 통째로 거절하면 옛 기록을 하나도 못 읽는다.
    val peakWeighting = r.enumOrNull<Weighting>("peakWeighting")
    val analysisWeighting = r.enumOrNull<Weighting>("analysisWeighting")
    val timeWeight = r.enum<TimeWeight>("timeWeight")

    // **없어도 되는 칸이다**(판 2). 옛 기록에는 통째로 없다.
    val epochCount = r.intOrNull("epochs.count") ?: 0
    val epochs = (0 until epochCount).mapNotNull { i ->
        val id = r.intOrNull("epochs.$i.id") ?: return@mapNotNull null
        RecordingEpoch(
            id = id,
            startFrame = r.longOrNull("epochs.$i.startFrame") ?: return@mapNotNull null,
            calibrationOffsetDb = r.dblOrNull("epochs.$i.calibrationOffsetDb")
                ?: return@mapNotNull null,
            isReferenceOnly = r.boolOrNull("epochs.$i.isReferenceOnly") ?: return@mapNotNull null,
            leqWindowMs = r.longOrNull("epochs.$i.leqWindowMs") ?: return@mapNotNull null,
        )
    }

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
        peakWeighting = peakWeighting,
        analysisWeighting = analysisWeighting,
        timeWeight = timeWeight ?: TimeWeight.Fast,
        leqWindowMs = r.long("leqWindowMs"),
        leqDb = r.dbl("leqDb"),
        minDb = r.dbl("minDb"),
        maxDb = r.dbl("maxDb"),
        peakDb = r.dbl("peakDb"),
        events = events,
        droppedPackets = r.int("droppedPackets"),
        clippedRows = r.intOrNull("clippedRows"),
        // **없어도 되는 칸이다.** 소리를 안 담은 기록이 훨씬 많다.
        audio = r.enumOrNull<AudioFileFormat>("audio.format")?.let { fmt ->
            RecordedAudio(
                format = fmt,
                fileName = r.strOrNull("audio.fileName").orEmpty(),
                bytes = r.longOrNull("audio.bytes") ?: 0L,
                droppedBlocks = r.intOrNull("audio.droppedBlocks") ?: 0,
            )
        },
        analysisVersion = r.int("analysisVersion"),
        // **없는 것이 정상이다.** 한 번도 다시 분석하지 않은 기록이다.
        reanalyzedAtEpochMs = r.longOrNull("reanalyzedAtEpochMs"),
        originalTimelineName = r.strOrNull("originalTimelineName"),
        memo = r.str("memo"),
        // **조건 칸은 없어도 된다**(판 2에서 생겼다).
        //
        // 옛 기록에는 당연히 없고, 새 기록에도 「모르는 것은 적지
        // 않는다」는 규칙 때문에 없을 수 있다. 그래서 `missing` 으로
        // 세지 않는 읽기만 쓴다 — 세면 멀쩡한 기록이 통째로 안 읽힌다.
        conditions = MeasurementConditions(
            audioSource = r.enumOrNull<kr.joa.selahrta.audio.CaptureSource>("cond.audioSource"),
            unprocessedSupported = r.boolOrNull("cond.unprocessedSupported"),
            agcDisabled = r.boolOrNull("cond.agcDisabled"),
            nsDisabled = r.boolOrNull("cond.nsDisabled"),
            aecDisabled = r.boolOrNull("cond.aecDisabled"),
            routedAddress = r.strOrNull("cond.routedAddress").orEmpty(),
            activeMicCombo = r.strOrNull("cond.activeMicCombo").orEmpty(),
            calibrationState =
                r.enumOrNull<kr.joa.selahrta.domain.CalibrationState>("cond.calibrationState"),
            calibrationSource =
                r.enumOrNull<kr.joa.selahrta.calibration.CalibrationSource>("cond.calibrationSource"),
            curveReading = r.enumOrNull<kr.joa.selahrta.dsp.CurveReading>("cond.curveReading"),
            curveReadingConfirmed = r.boolOrNull("cond.curveReadingConfirmed") ?: false,
        ),
        epochs = epochs,
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
