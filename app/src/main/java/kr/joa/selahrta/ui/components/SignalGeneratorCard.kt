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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.log10
import kotlin.math.pow
import kr.joa.selahrta.audio.MAX_AMPLITUDE
import kr.joa.selahrta.audio.MAX_TONE_HZ
import kr.joa.selahrta.audio.MIN_AMPLITUDE
import kr.joa.selahrta.audio.MIN_TONE_HZ
import kr.joa.selahrta.audio.SignalChannels
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
    amplitude: Double,
    toneHz: Double,
    channels: SignalChannels,
    noticeKo: String?,
    onPlay: (TestSignal) -> Unit,
    onStop: () -> Unit,
    onLevel: (Double) -> Unit,
    onToneHz: (Double) -> Unit,
    onChannels: (SignalChannels) -> Unit,
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

        // **세기는 이어진 고르개다**(2026-09-24 담당자 지시). 작게·보통·크게
        // 셋뿐이던 때는 PA 에 물렸을 때 「보통은 크고 작게는 안 들리는」
        // 자리에서 맞출 것이 없었다.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("세기", color = SelahColors.TextMuted, fontSize = 11.sp)
            Text(
                // **dBFS 로 적는다.** 퍼센트는 귀에 들리는 크기와 맞지 않아
                // 「절반」이 절반으로 안 들린다. 이 앱은 다른 곳에서도 소리를
                // dB 로 말한다.
                "%.0f dBFS".format(20.0 * log10(amplitude.coerceAtLeast(1e-6))),
                color = SelahColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Slider(
            value = amplitudeToSlider(amplitude),
            onValueChange = { onLevel(sliderToAmplitude(it)) },
            colors = SliderDefaults.colors(
                thumbColor = SelahColors.Accent,
                activeTrackColor = SelahColors.Accent,
                inactiveTrackColor = SelahColors.SurfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            // 안전은 색이 아니라 글로 적는다.
            "순음은 같은 크기의 음악보다 날카롭게 들립니다. 작게 시작해 " +
                "올려 쓰시고, PA 에 물린 채로 크게 틀지 마십시오 — 고역 " +
                "유닛이 상할 수 있습니다. 가장 크게 해도 ${"%.0f".format(
                    20.0 * log10(MAX_AMPLITUDE),
                )} dBFS 에서 막습니다.",
            color = SelahColors.Warn,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )

        ChannelPicker(channels, onChannels)

        // 신호 목록. 「주파수 지정」은 고르개를 함께 그려야 해서 따로 뺀다.
        for (s in TestSignal.entries) {
            if (s == TestSignal.Custom) continue
            SignalRow(s, s == playing, onPlay, onStop)
        }
        CustomToneRow(
            playing = playing == TestSignal.Custom,
            toneHz = toneHz,
            onToneHz = onToneHz,
            onPlay = { onPlay(TestSignal.Custom) },
            onStop = onStop,
        )

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

/**
 * 좌우 고르개.
 *
 * **폰 스피커로는 뜻이 없다고 적어 둔다.** 폰의 스피커는 하나거나 둘이어도
 * 몇 센티미터 떨어져 있어, 「왼쪽만」을 틀어도 방에서는 갈리지 않는다.
 * 적지 않으면 폰으로 시험하고 「좌우가 같다」는 잘못된 결론을 얻는다 —
 * 그리고 그 결론으로 케이블을 안 본다.
 */
@Composable
private fun ChannelPicker(
    selected: SignalChannels,
    onPick: (SignalChannels) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("보낼 쪽", color = SelahColors.TextMuted, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in SignalChannels.entries) {
                val on = c == selected
                Text(
                    c.labelKo,
                    color = if (on) SelahColors.Accent else SelahColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    softWrap = false,
                    modifier = Modifier
                        .background(
                            if (on) SelahColors.Accent.copy(alpha = 0.16f) else SelahColors.SurfaceVariant,
                            RoundedCornerShape(999.dp),
                        )
                        .border(
                            1.dp,
                            if (on) SelahColors.Accent else Color.Transparent,
                            RoundedCornerShape(999.dp),
                        )
                        .clickable { onPick(c) }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                        .semantics { stateDescription = if (on) "선택됨" else "선택 안 됨" },
                )
            }
        }
    }
    if (selected != SignalChannels.Both) {
        Text(
            "폰 스피커로는 좌우가 갈리지 않습니다 — 스피커가 하나거나 몇 " +
                "센티미터 떨어져 있을 뿐입니다. 이 고르개는 폰을 PA 에 " +
                "물렸을 때 쓰십시오. 케이블이 바뀌어 꽂혔는지, 한쪽 앰프가 " +
                "죽었는지를 그때 가릅니다.",
            color = SelahColors.Warn,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
    }
}

/**
 * 주파수를 직접 고르는 줄(2026-09-24 담당자 지시).
 *
 * **로그 눈금이다.** 20Hz~20kHz 를 선형으로 펴면 슬라이더의 앞쪽 1%
 * 안에 저역 전체가 들어간다 — 사람이 듣는 방식도, 방이 울리는 방식도
 * 로그다.
 *
 * 내보내는 중에도 끌 수 있다. 슬라이더를 끌면서 하울링 자리를 귀로 찾는
 * 쓰임이라, 멈췄다 다시 눌러야 하면 못 찾는다.
 */
@Composable
private fun CustomToneRow(
    playing: Boolean,
    toneHz: Double,
    onToneHz: (Double) -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
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
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    TestSignal.Custom.labelKo,
                    color = if (playing) SelahColors.Accent else SelahColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    "%s %s".format(formatHz(toneHz), hzUnit(toneHz)),
                    color = SelahColors.TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                if (playing) "멈추기" else "내보내기",
                color = if (playing) SelahColors.Accent else SelahColors.TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                softWrap = false,
                modifier = Modifier
                    .clickable { if (playing) onStop() else onPlay() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        Slider(
            value = hzToSlider(toneHz),
            onValueChange = { onToneHz(sliderToHz(it)) },
            colors = SliderDefaults.colors(
                thumbColor = SelahColors.Accent,
                activeTrackColor = SelahColors.Accent,
                inactiveTrackColor = SelahColors.Surface,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            TestSignal.Custom.noteKo,
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
    }
}

/**
 * 진폭과 슬라이더 자리를 오간다. **dB 로 편다.**
 *
 * 진폭을 그대로 슬라이더에 올리면 작은 쪽이 다 뭉친다 — 0.01 과 0.05 는
 * 14dB 차이인데 슬라이더에서는 손가락 한 마디도 안 된다.
 */
private fun amplitudeToSlider(amplitude: Double): Float {
    val lo = 20.0 * log10(MIN_AMPLITUDE)
    val hi = 20.0 * log10(MAX_AMPLITUDE)
    val db = 20.0 * log10(amplitude.coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE))
    return ((db - lo) / (hi - lo)).toFloat()
}

private fun sliderToAmplitude(pos: Float): Double {
    val lo = 20.0 * log10(MIN_AMPLITUDE)
    val hi = 20.0 * log10(MAX_AMPLITUDE)
    return 10.0.pow((lo + (hi - lo) * pos.coerceIn(0f, 1f)) / 20.0)
}

/** 주파수와 슬라이더 자리를 오간다. 로그 눈금이다. */
private fun hzToSlider(hz: Double): Float {
    val lo = log10(MIN_TONE_HZ)
    val hi = log10(MAX_TONE_HZ)
    return ((log10(hz.coerceIn(MIN_TONE_HZ, MAX_TONE_HZ)) - lo) / (hi - lo)).toFloat()
}

private fun sliderToHz(pos: Float): Double =
    10.0.pow(log10(MIN_TONE_HZ) + (log10(MAX_TONE_HZ) - log10(MIN_TONE_HZ)) * pos.coerceIn(0f, 1f))

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
