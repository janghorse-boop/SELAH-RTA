package kr.joa.selahrta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.audio.MicSeparation
import kr.joa.selahrta.audio.MicrophoneProbe
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * 내장 마이크 탐색(S23 개별 자동교정 지시서 7장).
 *
 * > 물리 마이크 판정이 불가하면 **두 마이크 선택 버튼을 제공하지 않는다.**
 *
 * 그래서 이 카드는 「무엇을 할 수 있다」가 아니라 **「무엇이 확인됐는가」**
 * 를 먼저 말한다. 확인되지 않은 것으로 버튼을 만들면, 그 버튼을 누른
 * 사람은 고른 대로 열렸다고 믿는다.
 */
@Composable
fun MicProbeCard(
    report: MicrophoneProbe.Report?,
    running: Boolean,
    onProbe: () -> Unit,
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
                "내장 마이크 탐색",
                color = SelahColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            report?.let {
                Text(
                    when (it.verdict.state) {
                        MicSeparation.Separable -> "갈린다"
                        MicSeparation.LogicalOnly -> "입력만 갈린다"
                        MicSeparation.Indistinguishable -> "가릴 수 없다"
                    },
                    color = when (it.verdict.state) {
                        MicSeparation.Separable -> SelahColors.InRange
                        MicSeparation.LogicalOnly -> SelahColors.Warn
                        MicSeparation.Indistinguishable -> SelahColors.TextMuted
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Text(
            "이 폰이 내장 마이크를 하나하나 따로 열어 주는지 실제로 녹음해 봅니다. " +
                "위 목록에는 내장이 한 줄로 묶여 있습니다 — 이 폰(S23 Ultra)에서는 " +
                "어느 쪽을 골라도 하단이 함께 켜진다는 것이 실측으로 확인됐기 " +
                "때문입니다. 다른 폰이라면 이 탐색이 다르게 말할 수 있습니다.",
            color = SelahColors.TextMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        if (report != null) {
            InfoBar(
                report.verdict.reasonKo,
                tone = when (report.verdict.state) {
                    MicSeparation.Separable -> SelahColors.InRange
                    else -> SelahColors.Warn
                },
            )

            if (!report.verdict.mayNamePhysicalPosition) {
                Text(
                    "그래서 「후면 마이크 보정」·「하단 마이크 보정」을 따로 만들지 " +
                        "않습니다. 확인되지 않은 것을 확인한 것처럼 적으면, 그 이름을 " +
                        "믿고 잰 값은 되돌릴 수 없습니다.",
                    color = SelahColors.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }

            var showDetail by remember(report) { mutableStateOf(false) }
            TextButton(
                onClick = { showDetail = !showDetail },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    if (showDetail) "자세히 접기" else "자세히",
                    color = SelahColors.Accent,
                    fontSize = 12.sp,
                )
            }
            if (showDetail) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(SelahColors.Background, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        "기기가 알린 마이크 ${report.catalogue.size}개",
                        color = SelahColors.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    report.catalogue.forEach {
                        Text(
                            "id=${it.id} addr='${it.address}' 위치=${it.location} " +
                                "방향=${it.directionality} 그룹=${it.group}/${it.indexInTheGroup}",
                            color = SelahColors.TextMuted,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                        )
                    }
                    report.repeats.forEachIndexed { n, rounds ->
                        Text(
                            "${n + 1}회차",
                            color = SelahColors.TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        rounds.forEach { r ->
                            Text(
                                "${r.requestedLabel} → 열린곳=${r.routedKey ?: "모름"} · " +
                                    "활성=${r.activeMics.map { it.id }}" +
                                    (r.failureKo?.let { " · $it" } ?: ""),
                                color = SelahColors.TextMuted,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = onProbe,
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = SelahColors.Accent,
                contentColor = Color(0xFF00201C),
                disabledContainerColor = SelahColors.SurfaceVariant,
                disabledContentColor = SelahColors.TextMuted,
            ),
        ) {
            Text(
                if (report == null) "탐색하기" else "다시 탐색하기",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }

        if (running) {
            Text(
                "측정 중에는 탐색하지 않습니다. 탐색이 마이크를 여러 번 열었다 " +
                    "닫으므로 재고 있던 측정이 끊깁니다.",
                color = SelahColors.TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}
