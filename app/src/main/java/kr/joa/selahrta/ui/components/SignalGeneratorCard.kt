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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.audio.SignalLevel
import kr.joa.selahrta.audio.TestSignal
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * 시험 신호 발생기(명세 16장).
 *
 * **쓰는 법** — 폰이 두 대면 한 대가 내보내고 한 대가 잰다. 한 대뿐이면
 * 스피커에서 나온 소리가 제 마이크로 돌아오므로 하울링 탐지를 확인할 수
 * 있다.
 *
 * 세기는 **단계로만** 고르게 한다. 순음은 같은 크기의 음악보다 훨씬
 * 날카롭게 들리고, 예배당 PA 에 물린 채로 크게 틀면 트위터가 상할 수 있다.
 */
@Composable
fun SignalGeneratorCard(
    playing: TestSignal?,
    level: SignalLevel,
    noticeKo: String?,
    onPlay: (TestSignal) -> Unit,
    onStop: () -> Unit,
    onLevel: (SignalLevel) -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(SelahColors.Surface, RoundedCornerShape(10.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("시험 신호 내보내기", color = SelahColors.TextPrimary, fontSize = 13.sp)
        Text(
            "폰이 두 대면 한 대가 내보내고 한 대가 잽니다. 한 대뿐이어도 " +
                "스피커 소리가 제 마이크로 돌아오므로 하울링 탐지를 확인할 수 있습니다.",
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )

        // 세기.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (l in SignalLevel.entries) {
                val on = l == level
                Text(
                    l.labelKo,
                    color = if (on) SelahColors.Accent else SelahColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .background(
                            if (on) SelahColors.Accent.copy(alpha = 0.16f) else SelahColors.SurfaceVariant,
                            RoundedCornerShape(7.dp),
                        )
                        .border(
                            1.dp,
                            if (on) SelahColors.Accent else Color.Transparent,
                            RoundedCornerShape(7.dp),
                        )
                        .clickable { onLevel(l) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        Text(
            // 안전은 색이 아니라 글로 적는다.
            "순음은 같은 크기의 음악보다 날카롭게 들립니다. 「작게」부터 " +
                "올려 쓰시고, PA 에 물린 채로 크게 틀지 마십시오 — 고역 " +
                "유닛이 상할 수 있습니다.",
            color = SelahColors.Warn,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )

        // 신호 목록.
        for (s in TestSignal.entries) {
            SignalRow(s, s == playing, onPlay, onStop)
        }

        noticeKo?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InfoBar(it, Modifier.weight(1f), tone = SelahColors.Warn)
                TextButton(onClick = onDismissNotice) {
                    Text("확인", color = SelahColors.Accent, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SignalRow(
    signal: TestSignal,
    playing: Boolean,
    onPlay: (TestSignal) -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (playing) SelahColors.Accent.copy(alpha = 0.16f) else SelahColors.SurfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .border(
                1.dp,
                if (playing) SelahColors.Accent else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable { if (playing) onStop() else onPlay(signal) }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                signal.labelKo,
                color = if (playing) SelahColors.Accent else SelahColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(signal.noteKo, color = SelahColors.TextMuted, fontSize = 10.sp, lineHeight = 14.sp)
        }
        // 색만으로 알리지 않는다(명세 11장).
        Text(
            if (playing) "멈추기" else "내보내기",
            color = if (playing) SelahColors.Accent else SelahColors.TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
