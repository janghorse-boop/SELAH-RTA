package kr.joa.selahrta.ui.screens.transfer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.transfer.ClipVerdict
import kr.joa.selahrta.transfer.CoherenceSummary
import kr.joa.selahrta.transfer.GraphLine
import kr.joa.selahrta.transfer.MatchState
import kr.joa.selahrta.transfer.TransferController
import kr.joa.selahrta.transfer.TransferGraphs
import kr.joa.selahrta.transfer.TransferScreenState
import kr.joa.selahrta.ui.theme.SelahColors
import kotlin.math.log10

/**
 * Transfer Function 간편 화면 — **실험용 진단 표시**(TF 설계 1·8·9·10장).
 *
 * 1차 MVP 출시 완료가 아니다. 클럭 드리프트를 확인하지도 보정하지도 않는다 — 그 사실을 띠로 늘 적는다.
 * 지연은 숫자로 보이지 않는다(7장). 「측정 신뢰도」·색으로 매긴 등급을 쓰지 않는다(9장).
 */
@Composable
fun SimpleTransferScreen(
    state: TransferScreenState,
    /** 시작 전에 보일 조건 문장. 되면 null. */
    preconditionKo: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxSize()
            .background(SelahColors.Background)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LeftPanel(state, preconditionKo, onStart, onStop, onClose, Modifier.width(220.dp).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ExperimentalBand(state.timebaseVerified)
            Spacer(Modifier.height(6.dp))
            val g = state.graphs
            if (g == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(
                        state.statusKo ?: if (state.running) "재는 중입니다" else "시작을 누르면 잽니다",
                        color = SelahColors.TextSecondary,
                        fontSize = 14.sp,
                    )
                }
            } else {
                GraphsArea(g, state, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun LeftPanel(
    state: TransferScreenState,
    preconditionKo: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("‹ 닫기") }
            Spacer(Modifier.width(4.dp))
            Text("전달함수 · 간편 (실험용)", color = SelahColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        if (state.running) {
            Button(onClick = onStop) { Text("■ 멈춤") }
        } else {
            // 시작 전 레벨 안내(지시서 27장, TF 설계 4.3).
            Text(
                "스피커에서 핑크 잡음이 납니다. 스피커 볼륨을 낮춘 뒤 시작하고 천천히 올리십시오. " +
                    "세기 0.2 는 디지털 진폭이며 실제 음압의 안전을 보장하지 않습니다.",
                color = SelahColors.TextSecondary,
                fontSize = 11.sp,
            )
            Button(onClick = onStart, enabled = preconditionKo == null) { Text("▶ 시작") }
            preconditionKo?.let { Text(it, color = SelahColors.Warn, fontSize = 11.sp) }
        }
        Line("입력", "폰 마이크", state.inputOk)
        Line("출력", state.outputLabel ?: "—", state.outputOk)
        Text(
            when (val m = state.match) {
                MatchState.Unknown -> "기준 맞춤: —"
                is MatchState.Found -> "기준 맞춤: 찾음 · 또렷함 %.1f".format(m.sharpness)
                is MatchState.NotFound -> "기준 맞춤: 못 찾음 · 또렷함 %.1f".format(m.sharpness)
            },
            color = SelahColors.TextPrimary,
            fontSize = 12.sp,
        )
        state.graphs?.let { g ->
            Text(coherenceLine(g.coherenceSummary), color = SelahColors.TextPrimary, fontSize = 12.sp)
            Text(
                "숨긴 칸 ${g.hidden.invalid + g.hidden.nonFinite + g.hidden.belowAxis}/${g.total} " +
                    "(기준 약함 ${g.hidden.invalid} · 표시 불가 ${g.hidden.nonFinite} · 축 아래 ${g.hidden.belowAxis})",
                color = SelahColors.TextSecondary,
                fontSize = 11.sp,
            )
        }
        when (state.clip) {
            ClipVerdict.Clipped -> Text("⚠ 입력 넘침 — 이 결과에 넘친 블록이 있습니다", color = SelahColors.Warn, fontSize = 11.sp)
            ClipVerdict.Unknown -> Text("이 결과의 입력 넘침 여부를 모릅니다", color = SelahColors.TextSecondary, fontSize = 11.sp)
            else -> Unit
        }
        if (state.ticksSincePublish in 1..2) {
            Text("마지막 결과 ${state.ticksSincePublish}초 전", color = SelahColors.TextSecondary, fontSize = 11.sp)
        }
        if (state.graphs != null && state.statusKo != null) {
            Text(state.statusKo, color = SelahColors.Warn, fontSize = 11.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "「찾음」은 탐색 범위 안에서 상관 봉우리가 조건(또렷함 2.0 이상)을 넘었다는 뜻입니다. 직접음·음향 " +
                "전파 시간·클럭 고정을 확인한 것이 아닙니다. 또렷함은 확률이 아닙니다. 지연은 두 흐름의 시작 시각 " +
                "차이가 섞여 있어 숫자로 보이지 않습니다.",
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun Line(label: String, value: String, ok: Boolean) {
    Text(
        "$label  $value ${if (ok) "✓" else ""}",
        color = SelahColors.TextPrimary,
        fontSize = 12.sp,
    )
}

private fun coherenceLine(s: CoherenceSummary): String = when (s) {
    CoherenceSummary.NotEnoughAverages -> "코히런스 중앙값: — (평균이 모자람)"
    is CoherenceSummary.NoBins -> "코히런스 중앙값: 표시 가능한 칸 없음"
    is CoherenceSummary.Median ->
        "코히런스 중앙값 %.2f (표시 대역 유효 %,d/%,d칸)%s".format(
            s.median, s.bins, s.total, if (s.stabilizing) " · 안정화 중" else "",
        )
}

/** 지울 수 없는 띠(8장). 시간축이 검증돼도 「실험용·클럭 드리프트 보정 안 함」은 남는다. */
@Composable
private fun ExperimentalBand(timebaseVerified: Boolean) {
    Text(
        buildString {
            append("⚠ 실험용 진단 표시")
            if (!timebaseVerified) append(" · 시간축 미검증")
            append(" · 클럭 드리프트 미확인·보정 안 함 · 상대 비교 — 정상 측정 곡선이 아니며 상대 비교의 정확도도 보장하지 않습니다")
        },
        color = SelahColors.OnAccent,
        fontSize = 11.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(SelahColors.Warn, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun GraphsArea(g: TransferGraphs, state: TransferScreenState, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("크기 (dB, 상대)", color = SelahColors.TextSecondary, fontSize = 11.sp)
        Box(Modifier.weight(2f).fillMaxWidth()) {
            if (g.magnitudeEmpty) {
                Text(
                    "크기 표시 가능한 칸 없음 — 표시 불가 ${g.hidden.nonFinite} · 축 아래 ${g.hidden.belowAxis}",
                    color = SelahColors.TextSecondary,
                    fontSize = 11.sp,
                )
            } else {
                LineChart(g.magnitude, TransferController.AXIS_FLOOR_DB, MAG_TOP_DB, SelahColors.Accent)
            }
        }
        Text("코히런스 (0~1)", color = SelahColors.TextSecondary, fontSize = 11.sp)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val c = g.coherence
            when {
                c == null -> Text("평균이 모자라 숨깁니다", color = SelahColors.TextSecondary, fontSize = 11.sp)
                c.points == 0 -> Text("코히런스 표시 가능한 칸 없음", color = SelahColors.TextSecondary, fontSize = 11.sp)
                else -> LineChart(c, 0.0, 1.0, SelahColors.InRange)
            }
        }
        Text("20 Hz ─────────── 1 kHz ─────────── 20 kHz", color = SelahColors.TextMuted, fontSize = 10.sp)
    }
}

/**
 * 칸 번호를 지닌 **선분마다 따로** 그린다(10장) — 선분 사이를 잇지 않는다. 가로축은 20 Hz~20 kHz 로그.
 */
@Composable
private fun LineChart(line: GraphLine, yMin: Double, yMax: Double, color: Color) {
    val grid = SelahColors.Outline
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        fun x(bin: Int): Float {
            val f = bin * BIN_HZ
            return ((log10(f) - LOG_LO) / (LOG_HI - LOG_LO) * w).toFloat()
        }
        fun y(v: Double): Float = ((1.0 - (v - yMin) / (yMax - yMin)).coerceIn(0.0, 1.0) * h).toFloat()
        for (f in doubleArrayOf(100.0, 1_000.0, 10_000.0)) {
            val gx = ((log10(f) - LOG_LO) / (LOG_HI - LOG_LO) * w).toFloat()
            drawLine(grid, Offset(gx, 0f), Offset(gx, h))
        }
        for (seg in line.segments) {
            if (seg.size == 1) {
                drawCircle(color, radius = 1.5f, center = Offset(x(seg.bins[0]), y(seg.values[0])))
                continue
            }
            val p = Path()
            p.moveTo(x(seg.bins[0]), y(seg.values[0]))
            for (i in 1 until seg.size) p.lineTo(x(seg.bins[i]), y(seg.values[i]))
            drawPath(p, color, style = Stroke(width = 1.5f))
        }
    }
}

private const val BIN_HZ = 48_000.0 / TransferController.ENGINE_FFT
private val LOG_LO = log10(20.0)
private val LOG_HI = log10(20_000.0)
private const val MAG_TOP_DB = 20.0
