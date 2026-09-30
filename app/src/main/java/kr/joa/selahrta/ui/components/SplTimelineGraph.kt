package kr.joa.selahrta.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kr.joa.selahrta.recording.SessionEvent
import kr.joa.selahrta.recording.SessionMeta
import kr.joa.selahrta.recording.TimelineRow
import kr.joa.selahrta.recording.timelineClock
import kr.joa.selahrta.recording.timelineGridDb
import kr.joa.selahrta.recording.timelineMarkers
import kr.joa.selahrta.recording.timelineSeries
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * **녹음을 시간축으로 그리고, 눌러서 그 시점으로 간다**(명세 Recording-D).
 *
 * ## 왜 있어야 하나
 *
 * 명세의 완료 기준은 「그래프 탭↔오디오 seek **양방향** 동기」다.
 * 소리를 움직이면 값이 따라오는 쪽은 이미 됐지만, **그 반대가 없었다** —
 * 「95dB 이 찍힌 자리」를 눈으로 찾아 놓고도 **그 소리로 갈 길이 없었다.**
 * 슬라이더를 더듬어 찾아야 했고, 그것은 「찾을 수 있다」가 아니다.
 *
 * ## 무엇을 그리나
 *
 * 칸마다 **그 구간의 현재값 최소 ~ 최대**를 띠로 그린다. 한 값으로
 * 줄이면 **오르내린 폭이 사라져** 조용한 구간과 들쭉날쭉한 구간이 같게
 * 보인다.
 *
 * 셈은 전부 [timelineSeries] 가 한다 — 누른 자리를 시각으로 바꾸는 일은
 * **1초쯤 틀려도 화면이 멀쩡해 보여서**, 기기 없이 시험할 수 있는 자리에
 * 두었다.
 *
 * ## 색으로만 말하지 않는다
 *
 * 찌그러진 칸은 색을 바꾸는 데 그치지 않고 **위에 짧은 눈금**을 덧긋는다.
 * 색만으로 가르면 색을 못 가리는 사람에게는 아무 표시도 없는 것과 같다.
 */
@Composable
fun SplTimelineGraph(
    meta: SessionMeta,
    rows: List<TimelineRow>,
    /** 지금 듣고 있는 자리(ms). 세로 선으로 선다. */
    playMs: Int,
    /** 누른 자리의 시각(ms). 소리를 그리로 옮기라는 뜻이다. */
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) return

    // **칸 수는 화면 폭에서 온다.** 한 칸이 1px 보다 좁으면 그리나 마나다.
    var widthPx by remember { mutableIntStateOf(0) }
    val columns = (widthPx / 3).coerceIn(1, 400)
    val series = remember(meta.id, rows, columns) { timelineSeries(meta, rows, columns) }
    val markers = remember(meta.id, series) { timelineMarkers(meta, series) }
    val grid = remember(series) { timelineGridDb(series) }

    Column(
        modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .background(SelahColors.SurfaceVariant, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                "시간에 따른 음압",
                color = SelahColors.TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f),
            )
            // **눌러서 갈 수 있다는 것을 글로 적는다.** 그림만 보고
            // 「눌러도 된다」를 알아내게 두지 않는다.
            Text("눌러서 그 자리로", color = SelahColors.TextMuted, fontSize = 10.sp)
        }

        if (series.isEmpty) {
            Text(
                "이 기록에는 그릴 값이 없습니다.",
                color = SelahColors.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Column
        }

        val density = LocalDensity.current
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(top = 8.dp)
                .onSizeChanged { widthPx = it.width }
                // **읽개에게도 누를 수 있다고 알린다.** `pointerInput` 만
                // 쓰면 합쳐진 트리에 아무 동작도 안 남는다.
                .semantics {
                    contentDescription =
                        "시간에 따른 음압 그래프. 길이 ${timelineClock(series.totalMs)}. " +
                            "누르면 그 자리의 소리로 갑니다."
                    onClick(label = "그 자리의 소리로 이동") { false }
                }
                .pointerInput(series) {
                    detectTapGestures { offset ->
                        val f = if (size.width > 0) offset.x / size.width else 0f
                        onSeek(series.msAtFraction(f).toInt())
                    }
                },
        ) {
            val w = size.width
            val h = size.height

            // ── 가로 눈금 ─────────────────────────────────
            grid.forEach { db ->
                val y = h - series.heightOf(db) * h
                drawLine(
                    color = SelahColors.Outline.copy(alpha = 0.5f),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f,
                )
            }

            // ── 값 띠 ─────────────────────────────────────
            val colW = if (series.columns.isNotEmpty()) w / series.columns.size else w
            series.columns.forEachIndexed { i, c ->
                val top = c.topDb ?: return@forEachIndexed
                val bottom = c.bottomDb ?: return@forEachIndexed
                val x = i * colW
                val yTop = h - series.heightOf(top) * h
                val yBottom = h - series.heightOf(bottom) * h
                drawRect(
                    color = if (c.clipped) SelahColors.Warn else SelahColors.Accent,
                    topLeft = Offset(x, yTop),
                    // 띠가 0 높이가 되면 안 보인다 — 최소 1px.
                    size = Size(colW.coerceAtLeast(1f), (yBottom - yTop).coerceAtLeast(1f)),
                )
                // **찌그러진 칸은 색 말고 눈금으로도 적는다.**
                if (c.clipped) {
                    drawRect(
                        color = SelahColors.Warn,
                        topLeft = Offset(x, 0f),
                        size = Size(colW.coerceAtLeast(1f), with(density) { 3.dp.toPx() }),
                    )
                }
            }

            // ── 사건 표 ───────────────────────────────────
            markers.forEach { e ->
                val x = series.fractionAtMs(e.atMs) * w
                drawLine(
                    color = SelahColors.TextMuted,
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = with(density) { 1.dp.toPx() },
                )
            }

            // ── 지금 듣는 자리 ────────────────────────────
            //
            // **맨 위에 그린다.** 띠에 가리면 어디를 듣고 있는지 안 보인다.
            val px = series.fractionAtMs(playMs.toLong()) * w
            drawLine(
                color = SelahColors.TextPrimary,
                start = Offset(px, 0f),
                end = Offset(px, h),
                strokeWidth = with(density) { 2.dp.toPx() },
            )
        }

        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("0:00", color = SelahColors.TextMuted, fontSize = 10.sp)
            Text(
                timelineClock(playMs.toLong()),
                color = SelahColors.TextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(
                timelineClock(series.totalMs),
                color = SelahColors.TextMuted,
                fontSize = 10.sp,
            )
        }

        // **표가 무엇인지 적는다.** 세로줄만 그어 두면 무슨 뜻인지 모른다.
        if (markers.isNotEmpty()) {
            Text(
                markerLegendKo(markers),
                color = SelahColors.TextMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 「0:12 끊김 · 1:04 보정 바뀜」처럼. 많으면 앞의 넷만. */
private fun markerLegendKo(markers: List<SessionEvent>): String {
    val shown = markers.take(4).joinToString(" · ") {
        "${timelineClock(it.atMs)} ${it.kind.labelKo}"
    }
    return if (markers.size > 4) "$shown … (${markers.size}건)" else shown
}
