package kr.joa.selahrta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.dsp.CurveReading
import kr.joa.selahrta.dsp.CurveShape
import kr.joa.selahrta.dsp.describeShapeKo
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * CAL 파일의 **둘째 열을 무엇으로 읽을지 사람이 고르는 자리**
 * (독립 검토 R04).
 *
 * ## 왜 앱이 정하지 않는가
 *
 * 정할 수가 없다. 파일 머리글에 단서가 없는 경우가 대부분이고, 잘못
 * 읽으면 보정이 **반대로 두 배** 걸린다 — +2dB 여야 할 자리가 −2dB 가
 * 되어 4dB 가 어긋난다. 그러고도 곡선은 멀쩡해 보이고, 기존 ±30dB 경고에
 * 걸리지도 않는다(측정 마이크 파일은 대개 ±5dB 안쪽이다).
 *
 * ## 무엇을 보여 주는가
 *
 * 고르라고만 하면 고를 수가 없다. 그래서 **단서를 곁에 둔다**:
 * 머리글에서 찾은 것, 그리고 곡선의 모양(저·중·고 세 점). 다만 숫자를
 * 먼저 적고 뜻은 뒤에 붙인다 — 순서를 바꾸면 앱이 정해 준 것처럼 읽힌다.
 */
@Composable
fun CalReadingCard(
    /** 지금 고른 값. 아직 안 골랐으면 제안값이 들어온다. */
    reading: CurveReading,
    /** 사람이 골랐는가. 아니면 제안일 뿐이다. */
    chosen: Boolean,
    /** 왜 물어보는지. [kr.joa.selahrta.dsp.decideReading] 이 준 말. */
    questionKo: String,
    /** 곡선 모양 단서. 곡선이 없으면 null. */
    shape: CurveShape?,
    onChoose: (CurveReading) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(SelahColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "CAL 파일을 어떻게 읽을까요",
                color = SelahColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (chosen) "정했습니다" else "확인 필요",
                color = if (chosen) SelahColors.InRange else SelahColors.Warn,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        InfoBar(questionKo, tone = if (chosen) SelahColors.TextSecondary else SelahColors.Warn)

        if (shape != null) {
            Text(
                describeShapeKo(shape),
                color = SelahColors.TextMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                style = TextStyle(lineBreak = LineBreak.Paragraph),
            )
        }

        CurveReading.entries.forEach { option ->
            ReadingOption(
                option = option,
                selected = option == reading && chosen,
                suggested = option == reading && !chosen,
                onClick = { onChoose(option) },
            )
        }
    }
}

@Composable
private fun ReadingOption(
    option: CurveReading,
    selected: Boolean,
    suggested: Boolean,
    onClick: () -> Unit,
) {
    val tone = when {
        selected -> SelahColors.InRange
        suggested -> SelahColors.Warn
        else -> SelahColors.Outline
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(tone.copy(alpha = if (selected) 0.14f else 0.06f), RoundedCornerShape(10.dp))
            .border(1.dp, tone.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                option.labelKo,
                color = SelahColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (selected || suggested) {
                Text(
                    if (selected) "고름" else "제안",
                    color = tone,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(
            option.explainKo,
            color = SelahColors.TextSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            style = TextStyle(lineBreak = LineBreak.Paragraph),
        )
    }
}
