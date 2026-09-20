package kr.joa.selahrta.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.domain.MeasureState
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.ui.components.InfoBar
import kr.joa.selahrta.ui.components.NO_VALUE
import kr.joa.selahrta.ui.components.NotYet
import kr.joa.selahrta.ui.components.ValueTile
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * 컨셉 화면 2번 — 31밴드 1/3옥타브 RTA.
 *
 * 밴드 중심주파수는 이미 [ThirdOctave] 에 정의돼 있다. Phase 1 에서는
 * **눈금과 밴드 자리만 그린다** — 막대 높이는 값이 있어야 나오므로
 * 그리지 않는다. 가짜 막대를 흔들어 두면 Phase 5 에서 진짜가 붙었는지
 * 눈으로 구별할 수 없다.
 */
@Composable
fun RtaScreen(state: MeasureState) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        InfoBar(
            "1/3 옥타브 31밴드 · ${ThirdOctave.CENTERS_HZ.first().toInt()}Hz ~ " +
                "${(ThirdOctave.CENTERS_HZ.last() / 1000).toInt()}kHz",
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        BandFrame(Modifier.fillMaxWidth().height(220.dp))

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf(20, 50, 125, 315, 800, 2000, 5000, 12500, 20000).forEach {
                Text(
                    if (it >= 1000) "${it / 1000}k" else "$it",
                    color = SelahColors.TextMuted,
                    fontSize = 9.sp,
                )
            }
        }
        Text(
            "주파수 (Hz)",
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ValueTile("선택 주파수", NO_VALUE, "Hz", Modifier.weight(1f))
            ValueTile("레벨", NO_VALUE, "dB", Modifier.weight(1f))
        }

        NotYet("막대는 실제 FFT 가 붙으면 그립니다.", "Phase 5 — FFT · 31밴드", Modifier.padding(top = 12.dp))
    }
}

/** 값이 없을 때의 RTA 틀. 눈금선만 그린다. */
@Composable
private fun BandFrame(modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(SelahColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
            .padding(8.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // 가로 눈금 다섯 줄(+20 ~ -20 dB). 세로는 31밴드 자리.
            val rows = 5
            repeat(rows) { i ->
                val y = size.height * i / (rows - 1f)
                drawLine(
                    color = SelahColors.Outline,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
            }
            val n = ThirdOctave.CENTERS_HZ.size
            repeat(n) { i ->
                val x = size.width * (i + 0.5f) / n
                drawLine(
                    color = SelahColors.Outline.copy(alpha = 0.5f),
                    start = Offset(x, size.height - 4f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
            }
        }
    }
}

/**
 * 컨셉 화면 3번 — 피드백 후보.
 *
 * 명세 9장: **최대 FFT bin 하나를 하울링으로 단정하지 않는다.**
 * prominence · narrowness · level · persistence · 반복성을 조합하고,
 * 화면에는 「후보」라고 적는다. 확신하는 말투를 쓰면 담당자가 예배 중에
 * 멀쩡한 악기 소리를 깎게 된다.
 */
@Composable
fun FeedbackScreen(state: MeasureState) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        InfoBar(
            "지속·돌출·좁이·반복을 모두 만족할 때만 후보로 올립니다. " +
                "한 번 튄 소리는 올리지 않습니다.",
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        Column(
            Modifier
                .fillMaxWidth()
                .background(SelahColors.Surface, RoundedCornerShape(14.dp))
                .border(1.dp, SelahColors.Outline, RoundedCornerShape(14.dp))
                .padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("감지된 피드백 후보 없음", color = SelahColors.TextSecondary, fontSize = 14.sp)
            Text(NO_VALUE, color = SelahColors.TextMuted, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text("kHz", color = SelahColors.TextMuted, fontSize = 12.sp)
        }

        Text(
            "최근 감지 내역",
            color = SelahColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )
        Text("아직 없습니다.", color = SelahColors.TextMuted, fontSize = 12.sp)

        NotYet("탐지 알고리즘은 Phase 9 에서 붙입니다.", "Phase 9 — Feedback", Modifier.padding(top = 20.dp))
    }
}
