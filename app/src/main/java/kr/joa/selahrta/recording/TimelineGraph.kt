package kr.joa.selahrta.recording

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * **녹음을 시간축 그림으로 줄인다**(명세 Recording-D).
 *
 * 명세의 완료 기준은 「그래프 탭↔오디오 seek **양방향** 동기」다. 소리를
 * 움직이면 값이 따라오는 쪽은 [playbackValuesAt] 이 이미 한다. 여기는
 * **반대쪽** — 화면의 어느 자리를 눌렀을 때 그것이 **몇 초인지**를 셈한다.
 *
 * ## 왜 화면이 아니라 여기서 셈하나
 *
 * 누른 자리를 시각으로 바꾸는 셈은 **틀려도 티가 안 난다.** 1초쯤
 * 어긋나면 그림은 멀쩡해 보이고, 소리도 그럴듯하게 난다. 화면 코드에
 * 섞어 두면 기기 없이는 확인할 수 없으므로, 순수한 함수로 떼어
 * **에뮬레이터 없이 시험**한다.
 *
 * ## 행 번호와 자리는 같다 — 그 위에 서 있다
 *
 * [RowAggregator] 가 **빠진 행을 `missing` 으로 채우므로**
 * `rowIndex == 목록에서의 자리` 다. [playbackValuesAt] 도 그 위에 서
 * 있다(자리로 찾는다). 여기서도 같은 가정을 쓴다 — 둘이 다른 가정을
 * 쓰면 **누른 자리와 들리는 소리가 어긋난다.** 그 가정은
 * `TimelineGraphTest` 가 지킨다.
 *
 * ## 여기서 하지 않는 것
 *
 * **소리와 타임라인의 시각이 같다고 가정한다.** 둘 다 세션 시작에서
 * 출발하지만, 그 둘이 실제로 얼마나 어긋나는지(drift)는 아직 재지
 * 않았다 — 명세가 독립 검증에 맡긴 항목이고
 * [docs/unverified.md] 에 남아 있다.
 */

/**
 * 그래프 한 칸. 여러 행을 하나로 줄인 것.
 *
 * **위·아래 두 값을 지닌다** — 한 칸이 여러 행을 덮으면 그 안에서 소리가
 * 오르내린 폭이 있다. 한 값으로 줄이면 그 폭이 사라져, 조용한 구간과
 * 들쭉날쭉한 구간이 같게 보인다.
 */
data class TimelineColumn(
    val startMs: Long,
    val endMs: Long,
    /** 이 칸의 **최대**(dB, 보정 걸림). 값이 하나도 없으면 null. */
    val topDb: Double?,
    /** 이 칸의 현재값 가운데 **가장 작은 것**(dB). 값이 없으면 null. */
    val bottomDb: Double?,
    /** 이 칸 안에서 입력이 찌그러진 적이 있는가. */
    val clipped: Boolean,
) {
    /** 그릴 값이 있는가. **없는 칸을 0 으로 그리지 않으려고 본다.** */
    val filled: Boolean get() = topDb != null && bottomDb != null
}

/**
 * 그림 한 벌과, **누른 자리를 시각으로 바꾸는 자**.
 *
 * @property totalMs 타임라인이 덮는 길이. **소리 파일의 길이가 아니다** —
 *   둘이 다르면 seek 하는 쪽에서 소리 길이에 맞춰 자른다.
 */
data class TimelineSeries(
    val columns: List<TimelineColumn>,
    val totalMs: Long,
    val floorDb: Double,
    val ceilDb: Double,
) {
    /** 그릴 것이 하나도 없는가. */
    val isEmpty: Boolean get() = columns.none { it.filled }

    /**
     * 그래프 가로 자리(0~1) → 소리의 시각(ms).
     *
     * **칸의 한가운데가 아니라 왼쪽 끝을 준다.** 누른 자리부터 들려야
     * 「여기서 무슨 소리가 났나」가 맞는다.
     */
    fun msAtFraction(fraction: Float): Long {
        if (totalMs <= 0L) return 0L
        val f = fraction.coerceIn(0f, 1f).toDouble()
        // 끝을 눌렀을 때 길이를 넘기지 않게 1ms 를 뺀다.
        return min((f * totalMs).toLong(), totalMs - 1)
    }

    /** 소리의 시각(ms) → 그래프 가로 자리(0~1). 재생 표시자가 쓴다. */
    fun fractionAtMs(ms: Long): Float {
        if (totalMs <= 0L) return 0f
        return (ms.toDouble() / totalMs).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * dB → 세로 자리(0=바닥, 1=천장).
     *
     * 눈금 밖의 값은 **자른다.** 눈금을 넘겨 그리면 칸 밖으로 삐져나간다.
     */
    fun heightOf(db: Double): Float {
        val span = ceilDb - floorDb
        if (span <= 0.0) return 0f
        return ((db - floorDb) / span).coerceIn(0.0, 1.0).toFloat()
    }
}

/**
 * 세로 눈금의 **최소 폭**(dB).
 *
 * 조용한 기록은 값이 2~3dB 안에서만 움직인다. 그 폭에 눈금을 꽉 맞추면
 * **숨소리가 산맥처럼 보인다.** 아래를 넉넉히 두어 「평평한 것은 평평하게」
 * 보이게 한다.
 */
private const val MIN_SPAN_DB = 20.0

/** 눈금을 5dB 자리에 맞춘다. 읽는 사람이 어림하기 쉽다. */
private const val GRID_DB = 5.0

/**
 * 행들을 [columns] 칸짜리 그림으로 줄인다.
 *
 * @param columns 그릴 칸 수(화면 폭에서 온다). 1 보다 작으면 빈 그림.
 *
 * **칸이 행보다 많아도 값을 지어내지 않는다** — 한 행이 여러 칸에 걸치면
 * 그 칸들이 **같은 값**을 받는다. 없는 자리를 잇대어 부드럽게 만들지 않는다.
 */
fun timelineSeries(
    meta: SessionMeta,
    rows: List<TimelineRow>,
    columns: Int,
): TimelineSeries {
    if (rows.isEmpty() || columns < 1) {
        return TimelineSeries(emptyList(), 0L, 0.0, 0.0)
    }
    val rowMs = TimelineFormat.ROW_MILLIS.toLong()
    val totalMs = rows.size.toLong() * rowMs

    // 행마다 보정을 걸어 둔다. **여기서 다시 셈하지 않고**
    // [playbackValuesAt] 과 같은 함수를 쓴다 — 보정 규칙이 두 벌이 되면
    // 그래프와 숫자가 조용히 갈린다.
    val values = rows.map { rowValues(meta, it) }

    val out = ArrayList<TimelineColumn>(columns)
    var lo = Double.POSITIVE_INFINITY
    var hi = Double.NEGATIVE_INFINITY

    for (c in 0 until columns) {
        val startMs = totalMs * c / columns
        val endMs = totalMs * (c + 1) / columns
        // **끝을 포함시킨다** — 칸이 행보다 촘촘하면 start 와 end 가 같은
        // 행 안에 들어가, 반열린 구간으로 세면 빈 칸이 된다.
        val first = (startMs / rowMs).toInt().coerceIn(0, rows.lastIndex)
        val last = ((endMs - 1).coerceAtLeast(startMs) / rowMs)
            .toInt().coerceIn(first, rows.lastIndex)

        var top: Double? = null
        var bottom: Double? = null
        var clipped = false
        for (i in first..last) {
            val v = values[i]
            if (v.clipped) clipped = true
            val maxDb = v.maxDb
            if (maxDb != null && maxDb.isFinite()) {
                top = if (top == null) maxDb else max(top, maxDb)
            }
            val curDb = v.currentDb
            if (curDb != null && curDb.isFinite()) {
                bottom = if (bottom == null) curDb else min(bottom, curDb)
            }
        }
        // 위가 아래보다 낮을 수는 없다 — 한 칸에 현재값만 있거나
        // 최대값만 있을 때 둘을 맞춘다.
        if (top == null && bottom != null) top = bottom
        if (bottom == null && top != null) bottom = top
        if (top != null && bottom != null && bottom > top) bottom = top

        top?.let { hi = max(hi, it) }
        bottom?.let { lo = min(lo, it) }
        out.add(TimelineColumn(startMs, endMs, top, bottom, clipped))
    }

    if (!lo.isFinite() || !hi.isFinite()) {
        return TimelineSeries(out, totalMs, 0.0, 0.0)
    }
    var floorDb = floor((lo - 2.0) / GRID_DB) * GRID_DB
    var ceilDb = ceil((hi + 2.0) / GRID_DB) * GRID_DB
    if (ceilDb - floorDb < MIN_SPAN_DB) {
        // 가운데를 지키며 벌린다. 위로만 벌리면 곡선이 바닥에 붙는다.
        val mid = (floorDb + ceilDb) / 2.0
        floorDb = floor((mid - MIN_SPAN_DB / 2.0) / GRID_DB) * GRID_DB
        ceilDb = floorDb + MIN_SPAN_DB
    }
    return TimelineSeries(out, totalMs, floorDb, ceilDb)
}

/**
 * 그래프에 세로줄로 찍을 **사건**(명세 13장 「이벤트 marker 에서 해당
 * 녹음 시점으로 이동할 수 있다」).
 *
 * **타임라인 밖의 사건은 뺀다 — 끝으로 당겨 붙이지 않는다.** 붙이면
 * 일어나지 않은 시각에 표가 선다.
 */
fun timelineMarkers(meta: SessionMeta, series: TimelineSeries): List<SessionEvent> {
    if (series.totalMs <= 0L) return emptyList()
    return meta.events.filter { it.atMs in 0L until series.totalMs }
}

/**
 * 세로 눈금에 적을 dB 값들. 바닥과 천장을 포함해 **넉넉잡아 다섯 줄**.
 *
 * 줄이 많으면 작은 화면에서 글자가 겹친다.
 */
fun timelineGridDb(series: TimelineSeries): List<Double> {
    val span = series.ceilDb - series.floorDb
    if (span <= 0.0) return emptyList()
    // 5dB 줄이 다섯을 넘으면 10·20… 으로 벌린다.
    var step = GRID_DB
    while (span / step > 4.0) step *= 2.0
    val out = ArrayList<Double>()
    var v = series.floorDb
    while (v <= series.ceilDb + 1e-9) {
        out.add(v)
        v += step
    }
    return out
}

/** `0:07` 꼴. 그래프 밑과 표시자에 함께 쓴다. */
fun timelineClock(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) / 1000.0).roundToInt()
    return "%d:%02d".format(s / 60, s % 60)
}
