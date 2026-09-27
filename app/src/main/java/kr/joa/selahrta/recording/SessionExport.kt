package kr.joa.selahrta.recording

import kr.joa.selahrta.domain.ChurchSegment
import java.io.InputStream
import java.io.Writer

/**
 * 기록 하나를 **CSV 한 장으로** 내보낸다(명세 13장).
 *
 * ## 조건이 머리말에 함께 나간다
 *
 * 파일을 받는 사람은 화면을 못 본다. [SessionCsv.header] 가
 * [buildReport] 를 그대로 찍으므로, 표만 보고도 **어떤 입력으로 어느
 * 마이크로 어떤 가공 상태에서 잰 값인지** 알 수 있다.
 *
 * ## 행마다 제 보정을 건다
 *
 * 보정은 녹음 도중에 바뀔 수 있고, 그때 새 epoch 이 시작한다. 행은
 * 값과 `epochId` 만 지니므로 **읽을 때 그 epoch 의 보정을 건다.**
 *
 * **표가 없으면 그렇다고 적는다.** 옛 기록(겉장 판 1·2 초기)에는 epoch
 * 표가 안 남아 있다. 그때는 겉장의 한 값으로 내보내되 머리말에 그
 * 한계를 적는다 — 조용히 한 가지 값으로 뭉뚱그리면, 보정이 바뀐 구간의
 * 숫자가 그럴듯하게 틀린다.
 */
object SessionExport {

    /**
     * @param timeline 타임라인 파일의 스트림. 부르는 쪽이 닫는다.
     * @return 내보낸 줄 수(표의 데이터 줄만).
     */
    fun writeCsv(meta: SessionMeta, timeline: InputStream, out: Writer): Int {
        val table = EpochTable(max = maxOf(1, meta.epochs.size)).also { t ->
            meta.epochs.forEach { t.add(it) }
        }
        val reader = TimelineReader(timeline, table)

        SessionCsv.header(meta).forEach { out.append(it).append('\n') }
        if (meta.epochs.isEmpty()) {
            // **없는 것을 있는 것처럼 쓰지 않는다.**
            out.append("# 주의: 이 기록에는 보정이 바뀐 자리(epoch)가 남아 있지 않습니다. ")
                .append("아래 값에는 겉장의 보정값 하나를 그대로 걸었습니다 — ")
                .append("재는 도중 보정을 바꿨다면 그 뒤 구간은 어긋납니다.\n")
        }
        out.append(SessionCsv.columns()).append('\n')

        var rows = 0
        var unresolved = 0
        val segments = segmentTimeline(meta)
        while (true) {
            val row = reader.next() ?: break
            val elapsedMs = row.rowIndex.toLong() * TimelineFormat.ROW_MILLIS
            val line = if (meta.epochs.isEmpty()) {
                SessionCsv.row(meta, row, segmentAt(segments, elapsedMs))
            } else {
                val fixed = calibratedRow(reader, row)
                if (fixed == null) {
                    unresolved++
                    // **풀 수 없는 행은 비운다.** 짐작한 보정을 걸면
                    // 그럴듯하게 틀린 값이 표에 남는다.
                    SessionCsv.row(meta, row.copy(missing = true), segmentAt(segments, elapsedMs))
                } else {
                    SessionCsv.row(zeroOffset(meta), fixed, segmentAt(segments, elapsedMs))
                }
            }
            out.append(line).append('\n')
            rows++
        }

        if (unresolved > 0) {
            out.append("# 주의: 보정을 풀 수 없는 행이 ${unresolved}개 있어 비워 두었습니다.\n")
        }
        SessionCsv.events(meta).forEach { out.append(it).append('\n') }
        out.flush()
        return rows
    }

    /**
     * 그 행의 세 값에 **제 epoch 의 보정**을 걸어 둔다.
     *
     * 셋이 서로 다른 epoch 을 가리킬 수 있다 — 한 행 안에서 보정이
     * 바뀌면 현재값과 최대값이 다른 잣대일 수 있기 때문이다.
     *
     * 하나라도 못 풀면 null 이다. **반쯤 보정한 행을 내지 않는다.**
     */
    private fun calibratedRow(reader: TimelineReader, row: TimelineRow): TimelineRow? {
        if (row.missing) return row
        val cur = reader.calibratedCurrent(row) as? RowValue.Calibrated ?: return null
        val max = reader.calibratedMax(row) as? RowValue.Calibrated ?: return null
        val peak = reader.calibratedPeak(row) as? RowValue.Calibrated ?: return null
        // 밴드는 현재값과 같은 epoch 을 쓴다 — 같은 FFT 한 장에서 나온다.
        val bandOffset = cur.db - row.currentRaw
        return row.copy(
            currentRaw = cur.db,
            maxRaw = max.db,
            peakRaw = peak.db,
            bands = FloatArray(row.bands.size) { (row.bands[it] + bandOffset).toFloat() },
        )
    }

    /**
     * 보정을 **이미 건** 행을 쓸 때의 겉장. 더하기를 두 번 하지 않는다.
     *
     * [SessionCsv.row] 는 `meta.calibrationOffsetDb` 를 더하도록 되어
     * 있다. 행에 이미 걸었으면 그 값은 0 이어야 한다.
     */
    private fun zeroOffset(meta: SessionMeta) = meta.copy(calibrationOffsetDb = 0.0)

    /** 구간 바뀜 사건만 뽑아 시각순으로. */
    private fun segmentTimeline(meta: SessionMeta): List<Pair<Long, ChurchSegment>> =
        meta.events
            .filter { it.kind == SessionEventKind.SegmentChange && it.segment != null }
            .sortedBy { it.atMs }
            .map { it.atMs to it.segment!! }

    /** 그 시각에 걸린 구간. 첫 사건 전이면 null. */
    private fun segmentAt(
        timeline: List<Pair<Long, ChurchSegment>>,
        atMs: Long,
    ): ChurchSegment? = timeline.lastOrNull { it.first <= atMs }?.second

    /** 내보낼 파일 이름. 시각으로 만들어 겹치지 않게 한다. */
    fun fileName(meta: SessionMeta): String {
        val t = java.time.Instant.ofEpochMilli(meta.startedAtEpochMs)
            .toString()
            .replace(':', '-')
            .substringBefore('.')
        return "selah-rta-$t.csv"
    }
}
