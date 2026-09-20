package kr.joa.selahrta.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.domain.ChurchMode
import kr.joa.selahrta.domain.MeasureState
import kr.joa.selahrta.domain.ReferenceRanges
import kr.joa.selahrta.domain.verdictFor
import kr.joa.selahrta.ui.CaptureUiState
import kr.joa.selahrta.ui.components.DiagnosticsPanel
import kr.joa.selahrta.ui.components.InfoBar
import kr.joa.selahrta.ui.components.InputLevelBar
import kr.joa.selahrta.ui.components.NO_VALUE
import kr.joa.selahrta.ui.components.ValueTile
import kr.joa.selahrta.ui.components.VerdictBadge
import kr.joa.selahrta.ui.components.formatDb
import kr.joa.selahrta.ui.nav.ViewMode
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * 컨셉 화면 1번 — 큰 현재 음압, 판정, LAeq/MAX/PEAK.
 *
 * Phase 2 에서 실제 PCM 이 들어오기 시작했지만 **음압 숫자는 아직 없다.**
 * dBFS 를 dB SPL 로 옮기려면 보정이 필요하고, 보정 없이 그럴듯한 82.4 를
 * 띄우면 그게 거짓말이 된다(명세 0장·1장). 그래서 큰 숫자는 여전히 「—」이고,
 * 대신 **입력 레벨**이 움직인다 — 마이크가 실제로 소리를 받고 있다는 사실만
 * 보여 주는, 음압이라고 주장하지 않는 눈금이다.
 */
@Composable
fun MeasureScreen(
    mode: ViewMode,
    capture: CaptureUiState,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val church = if (mode == ViewMode.Worship) ChurchMode.Worship else ChurchMode.Sermon
    val range = ReferenceRanges.forMode(church)

    // 음압은 보정을 거쳐야 나온다. Phase 3 까지는 없는 것이 맞다.
    val currentDba: Double? = null
    val verdict = range.verdictFor(currentDba)
    val running = capture.measure is MeasureState.Running

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            !hasPermission -> InfoBar(
                "소리의 크기를 재려면 마이크 권한이 필요합니다. " +
                    "이 앱은 소리를 저장하지 않고 음압과 주파수만 계산합니다.",
                Modifier.padding(top = 4.dp, bottom = 12.dp),
                tone = SelahColors.Warn,
            )

            capture.errorKo != null -> InfoBar(
                capture.errorKo,
                Modifier.padding(top = 4.dp, bottom = 12.dp),
                tone = SelahColors.High,
            )

            running -> {
                val f = capture.opened
                InfoBar(
                    f?.trustNoteKo ?: "재고 있습니다.",
                    Modifier.padding(top = 4.dp, bottom = 12.dp),
                    tone = if (f?.trustIsWarning == true) SelahColors.Warn else SelahColors.InRange,
                )
            }

            else -> InfoBar(
                "아래 버튼을 눌러 마이크를 엽니다. 음압 숫자는 보정이 붙는 Phase 3 부터 나옵니다.",
                Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
        }

        Box(contentAlignment = Alignment.Center) {
            GaugeArc(fraction = null, modifier = Modifier.size(260.dp, 150.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    formatDb(currentDba),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (currentDba != null) SelahColors.TextPrimary else SelahColors.TextMuted,
                )
                Text("dBA", fontSize = 16.sp, color = SelahColors.TextSecondary)
            }
        }

        VerdictBadge(verdict, Modifier.padding(top = 12.dp))

        if (range != null) {
            Text(
                "${church.labelKo} 권장 범위 " +
                    "${range.avg.start.toInt()} ~ ${range.avg.endInclusive.toInt()} dBA",
                color = SelahColors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                "보편적 기준이 아니라 참고값입니다. 설정에서 바꿀 수 있습니다.",
                color = SelahColors.TextMuted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ValueTile("LAeq (1분)", NO_VALUE, "dBA", Modifier.weight(1f))
            ValueTile("MAX", NO_VALUE, "dBA", Modifier.weight(1f))
            ValueTile("PEAK", NO_VALUE, "dB", Modifier.weight(1f))
        }

        Button(
            onClick = {
                when {
                    !hasPermission -> onRequestPermission()
                    running -> onStop()
                    else -> onStart()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) SelahColors.SurfaceVariant else SelahColors.Accent,
                contentColor = if (running) SelahColors.TextPrimary else Color(0xFF00201C),
            ),
        ) {
            Text(
                when {
                    !hasPermission -> "마이크 권한 허용하기"
                    running -> "측정 멈추기"
                    else -> "측정 시작"
                },
                fontWeight = FontWeight.Bold,
            )
        }

        if (running) {
            InputLevelBar(
                capture.diagnostics.lastPeakAbs,
                Modifier.fillMaxWidth().padding(top = 18.dp),
            )
        }

        capture.opened?.let {
            DiagnosticsPanel(it, capture.diagnostics, Modifier.padding(top = 16.dp, bottom = 24.dp))
        }
    }
}

/**
 * 반원 계기. [fraction] 이 null 이면 **눈금만 그리고 바늘을 그리지 않는다.**
 *
 * 값이 없을 때 바늘을 0 에 두면 「0 dB 을 재고 있다」로 읽힌다. 바늘이
 * 아예 없어야 재지 않는다는 뜻이 된다.
 */
@Composable
private fun GaugeArc(fraction: Float?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 14.dp.toPx()
        val w = size.width
        val h = size.height
        val d = minOf(w, h * 2f) - stroke
        val topLeft = Offset((w - d) / 2f, stroke / 2f)
        val arcSize = Size(d, d)

        drawArc(
            color = SelahColors.SurfaceVariant,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )

        if (fraction != null) {
            drawArc(
                color = SelahColors.InRange,
                startAngle = 180f,
                sweepAngle = 180f * fraction.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}
