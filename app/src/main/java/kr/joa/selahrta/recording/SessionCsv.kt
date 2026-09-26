package kr.joa.selahrta.recording

import kr.joa.selahrta.domain.ChurchSegment
import kr.joa.selahrta.dsp.ThirdOctave

/**
 * 기록을 CSV 로 내보낸다(명세 13장).
 *
 * ## 열은 명세가 정한 그대로다
 *
 * `timestamp, segment, device, calibration, weighting, current, leq, max,
 * peak, band values` — 이름을 바꾸지 않는다. 받는 쪽(엑셀·다른 도구)이
 * 열 이름으로 찾기 때문이다.
 *
 * ## 미보정을 숨기지 않는다
 *
 * `calibration` 열에 **「미보정」이 그대로 들어간다.** 파일을 받은 사람은
 * 화면의 배지를 못 본다 — 숫자만 보고 음압이라 여기면 판정이 통째로
 * 틀린다. 머리말에도 한 번 더 적는다.
 *
 * ## 이 판이 담지 **못하는** 것
 *
 * `leq` 는 **세션 전체의 값**을 모든 줄에 똑같이 적는다. 행 형식
 * ([TimelineFormat]) 판 1 에 시간별 Leq 칸이 없기 때문이다 — 행이 담는
 * 것은 시간가중 레벨의 마지막·최대값이고, 그것을 평균해도 등가소음도가
 * 되지 않는다. **없는 값을 셈해 넣지 않는다.** 시간별 Leq 가 필요하면
 * 행 형식의 판을 올려야 한다.
 */
object SessionCsv {

    /** 명세 13장의 열. 밴드는 뒤에 31개가 붙는다. */
    private val FIXED_COLUMNS = listOf(
        "timestamp", "elapsed_ms", "segment", "device", "calibration",
        "weighting", "current_db", "leq_db", "max_db", "peak_db",
    )

    /**
     * 머리말 — **파일만 보고도 무엇인지 알 수 있게.**
     *
     * `#` 로 시작하는 줄은 엑셀이 데이터로 읽지 않는다. 사람이 읽을
     * 말은 여기 두고, 표는 깨끗하게 남긴다.
     */
    fun header(meta: SessionMeta): List<String> = buildList {
        add("# SELAH RTA 측정 기록")
        add("# 시작: ${isoUtc(meta.startedAtEpochMs)}")
        add("# 길이: ${meta.durationMs / 1000}초")
        add("# 입력: ${meta.deviceLabel} (${meta.sampleRate}Hz, ${meta.encoding})")
        add("# 보정: ${calibrationLabel(meta)}")
        if (meta.referenceOnly) {
            add("# 주의: 미보정입니다. 아래 dB 값은 참고용이며 실제 음압과 10dB 넘게 다를 수 있습니다.")
        }
        if (meta.droppedPackets > 0) {
            add("# 주의: 소리 조각 ${meta.droppedPackets}개를 놓쳤습니다. 그 구간은 빈 줄로 나옵니다.")
        }
        if (meta.memo.isNotBlank()) add("# 메모: ${meta.memo}")
    }

    /** 열 이름 줄. */
    fun columns(): String =
        (FIXED_COLUMNS + (0 until ThirdOctave.BAND_COUNT).map { "band_${ThirdOctave.label(it)}" })
            .joinToString(",")

    /**
     * 행 하나를 줄 하나로.
     *
     * [segmentAt] 은 그 시각의 구간이다 — 구간은 사건으로 적히므로
     * ([SessionEvent]) 부르는 쪽이 시각으로 찾아 넘긴다.
     *
     * **빈 행은 빈 값으로 낸다.** 0 으로 채우면 「아주 조용했다」로 읽힌다.
     */
    fun row(meta: SessionMeta, row: TimelineRow, segmentAt: ChurchSegment?): String {
        val elapsedMs = row.rowIndex.toLong() * TimelineFormat.ROW_MILLIS
        val cells = ArrayList<String>(FIXED_COLUMNS.size + ThirdOctave.BAND_COUNT)
        cells += isoUtc(meta.startedAtEpochMs + elapsedMs)
        cells += elapsedMs.toString()
        cells += (segmentAt?.shortKo ?: "")
        cells += esc(meta.deviceLabel)
        cells += esc(calibrationLabel(meta))
        cells += meta.weighting.name
        if (row.missing) {
            // 놓친 구간이다. 숫자를 지어내지 않는다.
            repeat(4 + ThirdOctave.BAND_COUNT) { cells += "" }
            return cells.joinToString(",")
        }
        cells += db(row.currentRaw + meta.calibrationOffsetDb)
        cells += db(meta.leqDb)
        cells += db(row.maxRaw + meta.calibrationOffsetDb)
        cells += db(row.peakRaw + meta.calibrationOffsetDb)
        for (b in 0 until ThirdOctave.BAND_COUNT) {
            cells += db(row.bands[b] + meta.calibrationOffsetDb)
        }
        return cells.joinToString(",")
    }

    /** 사건 목록. 표 뒤에 `#` 줄로 붙인다 — 표의 열 수를 흐트러뜨리지 않는다. */
    fun events(meta: SessionMeta): List<String> = buildList {
        if (meta.events.isEmpty()) return@buildList
        add("# --- 사건 ---")
        meta.events.forEach { e ->
            val at = "%d:%02d".format(e.atMs / 60_000, (e.atMs / 1000) % 60)
            add("# $at ${e.kind.labelKo}${if (e.detailKo.isEmpty()) "" else " — ${e.detailKo}"}")
        }
    }

    /** 보정 상태를 한 마디로. **미보정을 먼저 적는다.** */
    fun calibrationLabel(meta: SessionMeta): String = buildString {
        append(if (meta.referenceOnly) "미보정(참고용)" else "보정됨")
        append(" %+.1fdB".format(meta.calibrationOffsetDb))
        if (meta.curveApplied) {
            append(" · 주파수 보정")
            if (meta.curveLabel.isNotEmpty()) append("(${meta.curveLabel})")
        }
    }

    /**
     * dB 를 소수 첫째 자리까지. **`NaN`·`무한`은 빈 값으로.**
     *
     * 그대로 적으면 엑셀이 글자로 읽어 열 하나가 통째로 숫자가 아니게
     * 된다 — 그 열의 평균·그래프가 조용히 비어 버린다.
     */
    private fun db(v: Double): String = if (v.isFinite()) "%.1f".format(v) else ""

    private fun db(v: Float): String = db(v.toDouble())

    /**
     * 쉼표·따옴표가 든 값을 감싼다.
     *
     * 기기 이름에 쉼표가 드는 일이 실제로 있다(`USB-Audio - UMC404HD 192k
     * (card=1;device=0)`). 안 감싸면 열이 밀려 그 뒤가 전부 어긋난다.
     */
    private fun esc(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }

    /**
     * 시각을 ISO-8601(UTC)로.
     *
     * **현지 시각으로 적지 않는다.** 파일을 다른 표준시로 여는 일이 있고,
     * 그때 「오후 2시 예배」가 「오전 5시」가 된다. 표시는 화면이 맡는다.
     */
    private fun isoUtc(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs).toString()
}
