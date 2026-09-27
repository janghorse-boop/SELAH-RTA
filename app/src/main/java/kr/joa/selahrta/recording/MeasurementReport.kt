package kr.joa.selahrta.recording

import kr.joa.selahrta.domain.CalibrationState

/**
 * **한 번의 측정을 한 장으로 적는다**(담당자 지시 2026-09-27).
 *
 * ## 조건과 측정값을 함께 적는다
 *
 * 숫자만 남기면 **나중에 그 숫자를 해석할 수 없다.** 담당자가 정한
 * 자리다 — 「조건 줄을 넣고 측정값도 같이 넣는다」.
 *
 * 이 폰(S23 Ultra)만 해도 UNPROCESSED 를 못 열어 음성인식 경로로 재고,
 * 활성 마이크는 하나다. 반년 뒤 같은 자리에서 잰 값과 달라도, 조건이
 * 안 적혀 있으면 **왜 다른지 말할 길이 없다.**
 *
 * ## 여기서 셈하지 않는다
 *
 * 리포트는 **있는 것을 적는 일**만 한다. 없는 값을 채워 넣거나 평균을
 * 새로 내지 않는다 — 이 저장소가 거듭 데인 자리다. Leq 는 엔진이
 * 에너지로 누적한 값 그대로이고, 없으면 없다고 적는다.
 *
 * ## 화면·CSV·리포트가 같은 문장을 쓴다
 *
 * 문구를 자리마다 따로 적으면 같은 기록이 자리마다 다른 말을 한다.
 * 믿음 등급은 [MeasurementConditions.trustLineKo] 하나에서 나온다.
 */
data class ReportLine(val labelKo: String, val valueKo: String)

data class ReportSection(val titleKo: String, val lines: List<ReportLine>)

/** 값이 없을 때 적는 말. **빈칸으로 두지 않는다.** */
const val NOT_RECORDED_KO = "기록 없음"

/** 안드로이드가 알려 주지 않았을 때. 「없다」와 다르다. */
const val UNKNOWN_KO = "확인 불가"

/**
 * 겉장 하나를 리포트로 옮긴다.
 *
 * **순수 함수다.** 화면도 파일도 만지지 않으므로 기기 없이 돌려 볼 수
 * 있고, 「무엇을 적는가」가 시험으로 고정된다.
 */
fun buildReport(meta: SessionMeta): List<ReportSection> = listOf(
    whenSection(meta),
    inputSection(meta),
    calibrationSection(meta),
    resultSection(meta),
    healthSection(meta),
)

private fun whenSection(m: SessionMeta) = ReportSection(
    "언제 쟀나",
    listOf(
        // **UTC 로 적는다.** 파일을 다른 표준시로 여는 일이 있고, 그때
        // 「오후 2시 예배」가 「오전 5시」가 된다(CSV 와 같은 규칙).
        ReportLine("시작", java.time.Instant.ofEpochMilli(m.startedAtEpochMs).toString()),
        // **벽시계 뺄셈이 아니다.** 단조 시계로 센 값을 그대로 적는다.
        ReportLine("길이", durationKo(m.durationMs)),
        ReportLine("메모", m.memo.ifBlank { "없음" }),
    ),
)

private fun inputSection(m: SessionMeta): ReportSection {
    val c = m.conditions
    return ReportSection(
        "무엇으로 쟀나",
        buildList {
            add(ReportLine("입력 기기", m.deviceLabel.ifBlank { NOT_RECORDED_KO }))
            // **자리는 이름표일 뿐 물리 위치가 아니다.** 그래도 보정이
            // 여기 매이므로 적는다.
            add(ReportLine("마이크 자리", c.routedAddress.ifEmpty { UNKNOWN_KO }))
            // **「확인 불가」는 「마이크가 없다」가 아니다.**
            add(ReportLine("활성 마이크", c.activeMicCombo.ifEmpty { UNKNOWN_KO }))
            add(ReportLine("입력 경로", c.audioSource?.labelKo ?: NOT_RECORDED_KO))
            add(
                ReportLine(
                    "가공 없는 입력 지원",
                    when (c.unprocessedSupported) {
                        true -> "예"
                        false -> "아니요"
                        null -> NOT_RECORDED_KO
                    },
                ),
            )
            add(ReportLine("신호 가공", processingKo(c)))
            add(
                ReportLine(
                    "격자",
                    if (m.sampleRate > 0) "${m.sampleRate} Hz · ${m.encoding}" else NOT_RECORDED_KO,
                ),
            )
            add(
                ReportLine(
                    "채널",
                    when {
                        m.channelCount <= 0 -> NOT_RECORDED_KO
                        m.channelCount > 1 -> "${m.channelCount}개 중 ${m.channelIndex + 1}번"
                        else -> "1개(모노)"
                    },
                ),
            )
        },
    )
}

private fun calibrationSection(m: SessionMeta): ReportSection {
    val c = m.conditions
    return ReportSection(
        "어떤 잣대로 쟀나",
        buildList {
            // **셋을 가려 적는다.** 「미보정」과 「기종 기본값」과 「보정됨」은
            // 믿음 등급이 다르다 — 한 칸으로 뭉치면 그 차이가 사라진다.
            add(ReportLine("절대 레벨", calibrationStateKo(m)))
            add(
                ReportLine(
                    "보정값",
                    if (m.calibrationOffsetDb.isFinite()) {
                        "%+.1f dB".format(m.calibrationOffsetDb)
                    } else {
                        NOT_RECORDED_KO
                    },
                ),
            )
            add(ReportLine("무엇에 맞췄나", c.calibrationSource?.labelKo ?: NOT_RECORDED_KO))
            add(ReportLine("주파수 곡선", curveKo(m)))
            add(ReportLine("가중치", m.weighting.name))
            add(ReportLine("응답 속도", m.timeWeight.name))
            add(ReportLine("Leq 구간", "${m.leqWindowMs / 1000}초"))
        },
    )
}

private fun resultSection(m: SessionMeta) = ReportSection(
    "잰 값",
    listOf(
        // **없는 값을 셈해 넣지 않는다.** 한 구간도 못 채웠으면 없다.
        ReportLine("Leq", db(m.leqDb)),
        ReportLine("MIN", db(m.minDb)),
        ReportLine("MAX", db(m.maxDb)),
        ReportLine("PEAK", db(m.peakDb)),
    ),
)

private fun healthSection(m: SessionMeta) = ReportSection(
    "재는 동안",
    listOf(
        ReportLine("구간 바뀜", "${m.events.count { it.kind == SessionEventKind.SegmentChange }}회"),
        // **찌그러짐은 사건이 아니라 행에 적힌다.**
        //
        // 예전에는 여기서 `SessionEventKind.Clipped` 를 세었다. 그런데
        // 그 사건은 **아무 데서도 만들어지지 않는다** — 그래서 실제로
        // 0dBFS 까지 찌그러진 기록에도 「없음」이라 적혔다(실기기에서
        // 확인: PEAK 120.0dB = 만재인데 「찌그러짐 없음」).
        //
        // 겉장이 세어 둔 값을 쓴다. **모르면 모른다고 적는다** — 0 으로
        // 때우면 찌그러진 기록을 안심시킨다.
        ReportLine("찌그러짐", clippedKo(m)),
        // 놓친 조각이 곧 끊김이다. 따로 적던 「끊김」 줄은 없앴다 —
        // 만들어지지 않는 사건을 세어 늘 「없음」이었다.
        ReportLine(
            "놓친 조각",
            if (m.droppedPackets > 0) "${m.droppedPackets}개" else "없음",
        ),
        ReportLine(
            "보정 바뀜",
            "${m.events.count { it.kind == SessionEventKind.CalibrationChange }}회",
        ),
        // **소리를 담았는가.** 담는 것은 기본이 아니므로, 담긴 기록은
        // 그렇다고 적어야 나중에 열어 보지 않고 안다.
        ReportLine("담긴 소리", audioKo(m)),
        ReportLine("셈 판", "v${m.analysisVersion}"),
    ),
)

private fun audioKo(m: SessionMeta): String {
    val a = m.audio ?: return "담지 않음"
    val dropped = if (a.droppedBlocks > 0) " · 못 담은 조각 ${a.droppedBlocks}개" else ""
    return "${a.format.labelKo} · ${a.sizeKo()}$dropped"
}

private fun clippedKo(m: SessionMeta): String = when (val n = m.clippedRows) {
    null -> NOT_RECORDED_KO
    0 -> "없음"
    else -> "${n}행"
}

// ── 사람이 읽을 말로 ────────────────────────────────────────

/**
 * 이 기록을 얼마나 믿을 수 있는가. 리포트 맨 위에 적는다.
 *
 * **경고가 있으면 맨 앞에 둔다.** 아래로 밀려 내려가면 숫자를 먼저
 * 읽고 넘어간다.
 */
fun reportWarningsKo(m: SessionMeta): List<String> = buildList {
    when (m.conditions.calibrationState) {
        CalibrationState.Uncalibrated, null ->
            if (m.referenceOnly) {
                add(
                    "미보정으로 쟀습니다. 아래 dB 값은 참고용이며 실제 음압과 " +
                        "10dB 넘게 다를 수 있습니다.",
                )
            }

        CalibrationState.FactoryDefault ->
            add(
                "이 기종의 기본값으로 쟀습니다. 개발자가 같은 기종에서 재어 앱에 " +
                    "실어 둔 값이라 짐작보다는 가깝지만, 이 기기를 잰 값은 아닙니다.",
            )

        else -> Unit
    }
    add(m.conditions.trustLineKo())
    if (m.droppedPackets > 0) {
        add("소리 조각 ${m.droppedPackets}개를 놓쳤습니다. 그 구간의 값은 비어 있습니다.")
    }
    val clipped = m.clippedRows ?: 0
    if (clipped > 0) {
        add(
            "입력이 찌그러진 구간이 ${clipped}행 있습니다. 그 구간의 숫자는 " +
                "실제보다 낮습니다 — 얼마나 낮은지는 알 수 없습니다.",
        )
    }
}

private fun calibrationStateKo(m: SessionMeta): String = when (m.conditions.calibrationState) {
    null -> if (m.referenceOnly) "미보정" else "보정됨"
    else -> m.conditions.calibrationState.labelKo
}

private fun curveKo(m: SessionMeta): String {
    if (!m.curveApplied) return "걸지 않음"
    val c = m.conditions
    val name = m.curveLabel.ifBlank { "이름 없음" }
    val reading = c.curveReading?.labelKo ?: NOT_RECORDED_KO
    // **사람이 확인한 것과 앱이 관례로 정한 것은 다르다.**
    val who = if (c.curveReadingConfirmed) "사람이 확인" else "관례로 읽음"
    return "$name · $reading($who)"
}

private fun processingKo(c: MeasurementConditions): String = when (c.processingClean) {
    // 셋 중 하나라도 「모름」이면 답할 수 없다.
    null -> NOT_RECORDED_KO
    true -> "자동 게인·잡음 억제·반향 제거 모두 꺼짐"
    false -> buildList {
        if (c.agcDisabled == false) add("자동 게인")
        if (c.nsDisabled == false) add("잡음 억제")
        if (c.aecDisabled == false) add("반향 제거")
    }.joinToString(" · ") + " 켜진 채로 쟀습니다"
}

/** 없는 값은 없다고 적는다. NaN 을 0 으로 적으면 「아주 조용했다」가 된다. */
private fun db(v: Double): String = if (v.isFinite()) "%.1f dB".format(v) else NOT_RECORDED_KO

private fun durationKo(ms: Long): String {
    if (ms <= 0) return NOT_RECORDED_KO
    val s = ms / 1000
    return when {
        s < 60 -> "${s}초"
        s < 3600 -> "${s / 60}분 ${s % 60}초"
        else -> "${s / 3600}시간 ${(s % 3600) / 60}분"
    }
}
