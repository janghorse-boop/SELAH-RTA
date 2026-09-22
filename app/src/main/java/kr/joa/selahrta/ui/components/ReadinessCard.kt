package kr.joa.selahrta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.audio.ReadinessItem
import kr.joa.selahrta.audio.ReadinessKind
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * 외부 마이크로 잴 때의 준비 상태(USB 오디오 지시서 13장).
 *
 * **앱이 확인한 것과 사람이 봐야 하는 것을 갈라 그린다.** 팬텀전원에
 * ✓ 를 붙이면 확인하지 않은 것을 확인했다고 말하는 셈이다 — 그 말을
 * 믿고 잰 값은 되돌릴 수 없다.
 */
@Composable
fun ReadinessCard(items: List<ReadinessItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return

    val asked = items.filter { it.kind == ReadinessKind.AskUser }
    val known = items.filter { it.kind != ReadinessKind.AskUser }

    Column(
        modifier
            .fillMaxWidth()
            .background(SelahColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "외부 측정 준비",
            color = SelahColors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )

        known.forEach { ReadinessRow(it) }

        if (asked.isNotEmpty()) {
            Text(
                "아래는 앱이 확인할 수 없습니다 — 직접 보십시오",
                color = SelahColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
            asked.forEach { ReadinessRow(it) }
        }
    }
}

@Composable
private fun ReadinessRow(item: ReadinessItem) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            when (item.kind) {
                // 확인한 것만 체크다.
                ReadinessKind.Verified -> "✓"
                ReadinessKind.NotYet -> "×"
                // **물음표다.** 모르는 것에 체크를 붙이지 않는다.
                ReadinessKind.AskUser -> "?"
            },
            color = when (item.kind) {
                ReadinessKind.Verified -> SelahColors.InRange
                ReadinessKind.NotYet -> SelahColors.High
                ReadinessKind.AskUser -> SelahColors.Warn
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(item.labelKo, color = SelahColors.TextPrimary, fontSize = 13.sp)
            item.detailKo?.let {
                Text(
                    it,
                    color = SelahColors.TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}
