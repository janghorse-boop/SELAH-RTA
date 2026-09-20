package kr.joa.selahrta.ui.screens

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
import androidx.compose.foundation.Canvas
import kr.joa.selahrta.domain.ChurchMode
import kr.joa.selahrta.domain.MeasureState
import kr.joa.selahrta.domain.RangeVerdict
import kr.joa.selahrta.domain.ReferenceRanges
import kr.joa.selahrta.domain.verdictFor
import kr.joa.selahrta.ui.components.InfoBar
import kr.joa.selahrta.ui.components.NO_VALUE
import kr.joa.selahrta.ui.components.ValueTile
import kr.joa.selahrta.ui.components.VerdictBadge
import kr.joa.selahrta.ui.components.formatDb
import kr.joa.selahrta.ui.nav.ViewMode
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * 컨셉 화면 1번 — 큰 현재 음압, 판정, LAeq/MAX/PEAK.
 *
 * Phase 1 에는 **값이 하나도 없다.** 82.4 같은 그럴듯한 숫자를 미리 띄우지
 * 않는다(명세 0장). 그래서 이 화면이 지금 보여주는 것은 「배치」와
 * 「값이 없을 때 어떻게 보이는가」 두 가지다. 후자는 나중에 실제로
 * 마이크가 죽었을 때 똑같이 나타날 화면이라 지금 확인해 두는 편이 낫다.
 */
@Composable
fun MeasureScreen(mode: ViewMode, state: MeasureState) {
    val church = if (mode == ViewMode.Worship) ChurchMode.Worship else ChurchMode.Sermon
    val range = ReferenceRanges.forMode(church)

    // 아직 측정하지 않으므로 값은 없다. null 이 곧 「없음」이다.
    val currentDba: Double? = null
    val verdict = range.verdictFor(currentDba)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        InfoBar(
            when (state) {
                MeasureState.Idle ->
                    "아직 측정하지 않습니다. 마이크 연결은 Phase 2 에서 붙습니다."
                MeasureState.Starting -> "마이크를 여는 중입니다."
                is MeasureState.Running -> "측정 중입니다."
                is MeasureState.Failed -> "측정이 멈췄습니다: ${state.reason}"
            },
            tone = SelahColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

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
                // 「참고값」이라는 사실을 숫자 바로 옆에 적는다. 명세 10장이
                // 보편 표준이 아니라고 못박았고, 설정에서 고칠 수 있다.
                "보편적 기준이 아니라 참고값입니다. 설정에서 바꿀 수 있습니다.",
                color = SelahColors.TextMuted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ValueTile("LAeq (1분)", NO_VALUE, "dBA", Modifier.weight(1f))
            ValueTile("MAX", NO_VALUE, "dBA", Modifier.weight(1f))
            ValueTile("PEAK", NO_VALUE, "dB", Modifier.weight(1f))
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
