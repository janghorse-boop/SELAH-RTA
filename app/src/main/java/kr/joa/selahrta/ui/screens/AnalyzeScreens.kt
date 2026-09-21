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
import kr.joa.selahrta.ui.CaptureUiState
import kr.joa.selahrta.ui.components.BandMeter
import kr.joa.selahrta.ui.components.InfoBar
import kr.joa.selahrta.ui.components.NO_VALUE
import kr.joa.selahrta.ui.components.NotYet
import kr.joa.selahrta.ui.components.ValueTile
import kr.joa.selahrta.ui.components.formatDb
import kr.joa.selahrta.ui.components.rtaRange
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
fun RtaScreen(capture: CaptureUiState) {
    val rta = capture.rta
    val (floor, ceil) = rtaRange(rta)
    val unresolved = rta?.resolved?.indexOfFirst { it }?.takeIf { it > 0 }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        InfoBar(
            "1/3 옥타브 31밴드 · 20Hz ~ 20kHz · 가중 없음(원음 그대로)",
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        BandMeter(rta, floor, ceil, Modifier.fillMaxWidth())

        if (unresolved != null && rta != null) {
            // 흐린 막대가 무슨 뜻인지, **얼마나** 못 믿을지를 함께 적는다.
            // 참·거짓만 적으면 2dB 과 7dB 을 같은 것으로 읽게 된다.
            //
            // **「최대」라고 적지 않는다.** 이 값은 밴드 가운데 구간에 순음을
            // 놓고 잰 표본이다. 경계에 바싹 붙은 소리는 이보다 더 샌다 —
            // 1kHz 밴드조차 경계에서는 2.4dB 이 빠진다. 유한한 창을 쓰는 한
            // 피할 수 없는 일이라, 모든 밴드의 진짜 상한을 적으면 31개가 전부
            // 「못 믿음」이 되어 아무 정보도 주지 못한다(독립 재검증 F05).
            val sampled = (0 until unresolved).maxOf { rta.lossDb[it] }
            InfoBar(
                "${ThirdOctave.label(unresolved)}Hz 아래 밴드는 흐리게 그립니다. " +
                    "FFT 창이 낮은 주파수를 한 밴드 안에 다 담지 못해 에너지가 " +
                    "이웃으로 샙니다. 밴드 가운데에 순음을 놓고 재면 " +
                    "${"%.1f".format(sampled)}dB 까지 낮게 나왔습니다 — 밴드 경계 " +
                    "가까이에서는 더 샙니다.",
                Modifier.padding(top = 10.dp),
                tone = SelahColors.TextMuted,
            )
        }

        val top = rta?.let { r ->
            r.bandsSpl.indices
                .filter { r.resolved[it] }
                .maxByOrNull { r.bandsSpl[it] }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ValueTile(
                "가장 큰 대역",
                top?.let { ThirdOctave.label(it) } ?: NO_VALUE,
                "Hz",
                Modifier.weight(1f),
            )
            ValueTile(
                "그 레벨",
                top?.let { formatDb(rta.bandsSpl[it]) } ?: NO_VALUE,
                // RTA 는 늘 가중 없이 본다. 위의 큰 숫자(dBA 등)와 다른 값이므로
                // 단위에 그 사실을 적는다 — 안 적으면 두 숫자가 안 맞는다고 읽힌다.
                "dB · 가중 없음",
                Modifier.weight(1f),
            )
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
