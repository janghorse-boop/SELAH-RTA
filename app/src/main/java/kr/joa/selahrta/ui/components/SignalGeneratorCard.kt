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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.FlowRow
import kr.joa.selahrta.dsp.BandNoiseFilter

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
    /** 어디로 내보낼지 고른 것(독립 검토 R5-04). */
    output: kr.joa.selahrta.audio.SignalOutput,
    onOutput: (kr.joa.selahrta.audio.SignalOutput) -> Unit,
    /** **실제로** 어디로 나갔는가. 안 틀고 있으면 null. */
    routeKo: String?,
    /** 지금 꽂혀 있어 **나갈 수 있는** 자리들. 없는 것은 감추지 않고 적는다. */
    availableOutputs: Set<kr.joa.selahrta.audio.OutputKind>,
    /** 지금 재고 있는 입력의 종류. 같은 USB 로 넣고 빼는지 가리는 데 쓴다. */
    capturingFrom: kr.joa.selahrta.domain.MicKind?,
    onDismissNotice: () -> Unit,
    /** 소리를 켜 둔 채 RTA 화면으로 간다. 재생은 끊기지 않는다. */
    onMeasureInRta: () -> Unit,
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
        // **머리글 줄은 높이가 늘 같다**(2026-10-01 담당자 지적: 「재생
        // 버튼을 누르면 박스가 살짝 이동한다」).
        //
        // 「RTA에서 재기」는 **틀고 있을 때만** 뜬다. 그것을 제 줄에 두었더니
        // 나타나는 순간 아래가 통째로 밀렸다 — 폰에서 재 보니 **210px**
        // 이었다(`세기` 줄이 y 893 → 1103). 누른 단추가 손가락 아래에서
        // 움직이는 셈이라, 두 번 누르게 된다.
        //
        // 그래서 **제목 줄 오른쪽**에 두고 그 줄의 높이를 못박는다. 뜨든
        // 안 뜨든 줄 높이가 같으니 아래는 꿈쩍도 안 한다.
        Row(
            Modifier.fillMaxWidth().height(HEADER_ROW_HEIGHT),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("테스트 신호 송출", color = SelahColors.TextPrimary, fontSize = 13.sp)
            // **틀어 놓고 바로 재러 간다**(지시서 §1).
            //
            // 소리는 화면을 넘어도 이어진다 — 재생은 앱 전체가 함께 쓰는
            // `CaptureViewModel` 이 들고 있다. 그런데 도구 → 분석 → RTA 로
            // 손가락을 세 번 옮겨야 하고, **그 사이에 끊길까 봐 사람이 먼저
            // 멈춘다.** 한 걸음으로 만들면 그럴 까닭이 없어진다.
            if (playing != null) {
                TextButton(
                    onClick = onMeasureInRta,
                    modifier = Modifier.semantics { contentDescription = "RTA에서 재기" },
                ) {
                    Text(
                        "RTA에서 재기 →",
                        color = SelahColors.Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        softWrap = false,
                    )
                }
            }
        }
        Text(
            "폰이 두 대면 한 대가 내보내고 한 대가 잽니다. 한 대뿐이어도 " +
                "스피커 소리가 제 마이크로 돌아오므로 하울링 탐지를 확인할 수 " +
                "있습니다. 틀어 둔 소리는 다른 화면으로 가도 그대로 납니다.",
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
        OutputPicker(output, routeKo, availableOutputs, capturingFrom, onOutput)

        // 신호 목록 — **차례가 지시받은 그대로다**(2026-10-01):
        // 핑크 · 화이트 · 스윕 · **1/3 옥타브 대역** · **주파수 지정**.
        for (s in TestSignal.entries) {
            if (s.usesPickedHz) continue
            SignalRow(s, s == playing, onPlay, onStop)
        }

        // **대역은 한 줄로 둔다.** 고르개(슬라이더·입력칸)는 아래 상자에만
        // 있고, 이 줄은 **그 주파수를 따라간다** — 같은 고르개를 두 번
        // 그리면 두 값이 어긋날 자리가 생긴다.
        //
        // 실제로 나가는 것은 **가장 가까운 1/3 옥타브 호칭 중심**이므로
        // (EQ 눈금과 같은 자리라야 쓸모가 있다) 그 값을 적어 준다 —
        // 3147Hz 를 가리켜도 3150Hz 대역이 나간다.
        run {
            val center = BandNoiseFilter.snapToBandCenter(toneHz)
            SignalRow(
                signal = TestSignal.Band,
                playing = playing == TestSignal.Band,
                onPlay = onPlay,
                onStop = onStop,
                noteKo = "%s %s 대역을 냅니다. 그래픽 EQ 를 만질 때 그 대역을 귀로 확인합니다"
                    .format(formatHz(center), hzUnit(center)),
            )
        }

        PickedHzCard(
            playing = playing,
            toneHz = toneHz,
            onToneHz = onToneHz,
            onPlay = onPlay,
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
/**
 * **어디로 내보낼지 고르고, 실제로 어디로 나갔는지 본다**(독립 검토 R5-04).
 *
 * 예전에는 「USB 로 재면 폰 스피커」를 코드가 조용히 못박았다. 그러면
 * **PA 로 신호를 넣고 USB 마이크로 재려던** 사람에게는 고장으로 보인다.
 *
 * 그리고 `setPreferredDevice` 는 **요청**이라 거절될 수 있는데, 거절되면
 * 경고 로그 한 줄만 남았다 — 우회가 안 걸렸고 그때 입력은 무음이 되는데
 * **화면에는 아무 표시도 없었다.** 그래서 실제 경로를 여기 적는다.
 */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun OutputPicker(
    selected: kr.joa.selahrta.audio.SignalOutput,
    routeKo: String?,
    availableOutputs: Set<kr.joa.selahrta.audio.OutputKind>,
    capturingFrom: kr.joa.selahrta.domain.MicKind?,
    onPick: (kr.joa.selahrta.audio.SignalOutput) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("내보낼 곳", color = SelahColors.TextMuted, fontSize = 11.sp)
        // **다섯이 한 줄에 안 들어간다.** 좁은 폰에서 옆으로 밀리게 두면
        // 뒤의 것들이 안 보이고, **보이지 않는 것은 없는 것이다.** 접는다.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (o in kr.joa.selahrta.audio.SignalOutput.entries) {
                val on = o == selected
                Text(
                    o.shortLabelKo,
                    color = if (on) SelahColors.Accent else SelahColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    softWrap = false,
                    modifier = Modifier
                        .background(
                            if (on) {
                                SelahColors.Accent.copy(alpha = 0.16f)
                            } else {
                                SelahColors.SurfaceVariant
                            },
                            RoundedCornerShape(999.dp),
                        )
                        .border(
                            1.dp,
                            if (on) SelahColors.Accent else Color.Transparent,
                            RoundedCornerShape(999.dp),
                        )
                        .clickable { onPick(o) }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                        .semantics { stateDescription = if (on) "선택됨" else "선택 안 됨" },
                )
            }
        }
        Text(
            selected.helpKo,
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
        // **고른 자리가 안 꽂혀 있으면 그렇다고 적는다.**
        //
        // 감추지 않는 까닭은 위에 적었다. 대신 **고르는 순간** 없다는 것을
        // 알려야 한다 — 안 그러면 틀어 놓고 「왜 소리가 안 나지」를 한다.
        selected.kind.takeIf { it !in availableOutputs }?.let {
            Text(
                "${it.labelKo} 가 지금 안 꽂혀 있습니다 — 시스템이 고른 곳으로 나갑니다.",
                color = SelahColors.Warn,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
        // **같은 USB 로 넣고 빼려는가.** 막지는 않고 미리 알린다.
        kr.joa.selahrta.audio.SignalOutputChoice
            .usbDuplexRiskKo(selected, capturingFrom)
            ?.let {
                Text(it, color = SelahColors.Warn, fontSize = 10.sp, lineHeight = 14.sp)
            }
        // **요청이 아니라 결과를 적는다.** 고른 것과 다르면 그 말도 함께 온다.
        //
        // **자리는 늘 잡아 둔다**(2026-10-01 담당자 지적: 「재생 버튼을 누르면
        // 박스가 살짝 이동한다」). 이 줄은 틀어야 값이 오는데, 없다가 생기면
        // **아래가 통째로 밀린다** — 폰에서 재니 76px 이었다. 누른 단추가
        // 손가락 아래에서 움직이면 두 번 누르게 된다.
        //
        // 비어 있어도 `minLines` 가 한 줄을 붙들어 둔다.
        Text(
            routeKo ?: "",
            color = if (routeKo != null &&
                (routeKo.contains("다릅니다") || routeKo.contains("거절"))
            ) {
                SelahColors.Warn
            } else {
                SelahColors.TextSecondary
            },
            fontSize = 10.sp,
            lineHeight = 14.sp,
            minLines = 1,
        )
    }
}

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
private fun PickedHzCard(
    playing: TestSignal?,
    toneHz: Double,
    onToneHz: (Double) -> Unit,
    onPlay: (TestSignal) -> Unit,
    onStop: () -> Unit,
) {
    var range by remember { mutableStateOf(ToneRange.All) }
    // **이 상자는 순음만 맡는다.** 대역은 위의 제 줄이 밝힌다 —
    // 둘이 같은 주파수를 쓴다고 해서 같은 자리에서 빛나면, 무엇이 나고
    // 있는지 가려진다.
    val playingHere = playing == TestSignal.Custom

    Column(
        Modifier
            .fillMaxWidth()
            .background(
                if (playingHere) {
                    SelahColors.Accent.copy(alpha = 0.16f)
                } else {
                    SelahColors.SurfaceVariant
                },
                RoundedCornerShape(8.dp),
            )
            .border(
                1.dp,
                if (playingHere) SelahColors.Accent else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // **이 상자가 곧 순음이다**(2026-10-01 담당자 지시: 「주파수 지정이
        // 곧 순음이기 때문에 … 재생버튼을 추가하고 아래 순음 박스도 삭제」).
        //
        // 예전에는 고르개만 여기 두고, 실제로 내보내는 줄(순음·대역)을 상자
        // **아래**에 따로 뒀다. 그래서 주파수를 정해 놓고도 **어디를 눌러야
        // 소리가 나는지**가 한 걸음 떨어져 있었다.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "주파수 지정",
                    color = if (playingHere) SelahColors.Accent else SelahColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = if (playingHere) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    "20Hz ~ 20kHz 순음. RTA 가 알려 준 하울링 자리를 그대로 넣어 봅니다",
                    color = SelahColors.TextMuted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
            }
            // 누를 자리를 넉넉히 둔다 — 표식만 18dp 라 손가락이 빗나간다.
            Box(
                Modifier
                    .clickable {
                        if (playingHere) onStop() else onPlay(TestSignal.Custom)
                    }
                    .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
            ) {
                PlayStopMark(playingHere)
            }
        }
        ToneHzField(toneHz, onToneHz)
        // **구간을 좁혀 미세하게 맞춘다**(담당자 지시 2026-09-29).
        //
        // 20Hz~20kHz 를 한 슬라이더에 펴면 손가락 한 마디가 수백 Hz 다.
        // 3150Hz 를 짚으려면 화면 폭의 0.3% 를 눌러야 한다. 구간을 한
        // 옥타브 남짓으로 좁히면 같은 손가락이 몇 Hz 를 움직인다.
        //
        // 값은 **구간 밖으로 나가도 그대로 둔다** — 직접 적어 넣은
        // 주파수를 구간 때문에 조용히 바꾸면 안 된다. 슬라이더만 끝에
        // 붙는다.
        ToneRangeChips(range) { range = it }
        Slider(
            value = range.toSlider(toneHz),
            // **손가락을 바로 따라간다.** 소리도 다시 시작하지 않고 그
            // 자리에서 주파수만 바뀐다(`SignalPlayer.retune`).
            onValueChange = { onToneHz(range.fromSlider(it)) },
            colors = SliderDefaults.colors(
                thumbColor = SelahColors.Accent,
                activeTrackColor = SelahColors.Accent,
                inactiveTrackColor = SelahColors.Surface,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "%s %s".format(formatHz(range.minHz), hzUnit(range.minHz)),
                color = SelahColors.TextMuted,
                fontSize = 9.sp,
            )
            Text(
                "%s %s".format(formatHz(range.maxHz), hzUnit(range.maxHz)),
                color = SelahColors.TextMuted,
                fontSize = 9.sp,
            )
        }
    }
}

/**
 * 내보내는 중인가를 **심볼로** 알린다(담당자 지시 2026-09-29).
 *
 * 예전에는 「내보내기」·「멈추기」라고 글자로 적었다. 신호가 열 줄
 * 넘게 늘어서니 같은 글자가 반복돼 눈이 미끄러지고, 지금 무엇이 나고
 * 있는지 한눈에 안 들어왔다.
 *
 * **색만으로 알리지 않는다**(명세 11장). 심볼 모양 자체가 다르다 —
 * 멈춰 있으면 ▶, 나는 중이면 ■. 색맹인 사람도 모양으로 가른다.
 *
 * 그림 파일을 쓰지 않고 그린다. 두 모양뿐이라 리소스를 늘릴 까닭이 없고,
 * 테마 색을 그대로 따라간다.
 */
@Composable
private fun PlayStopMark(playing: Boolean, modifier: Modifier = Modifier) {
    val tint = if (playing) SelahColors.Accent else SelahColors.TextSecondary
    Canvas(
        modifier
            .size(18.dp)
            .semantics { contentDescription = if (playing) "멈추기" else "내보내기" },
    ) {
        if (playing) {
            // 정지 — 네모. 모서리를 살짝 둥글려 딱딱하지 않게.
            val side = size.minDimension * 0.62f
            val off = (size.minDimension - side) / 2f
            drawRoundRect(
                color = tint,
                topLeft = Offset(off, off),
                size = Size(side, side),
                cornerRadius = CornerRadius(side * 0.18f),
            )
        } else {
            // 재생 — 오른쪽을 보는 삼각형.
            val w = size.minDimension * 0.58f
            val h = size.minDimension * 0.64f
            val left = (size.width - w) / 2f + w * 0.12f
            val top = (size.height - h) / 2f
            drawPath(
                Path().apply {
                    moveTo(left, top)
                    lineTo(left + w, top + h / 2f)
                    lineTo(left, top + h)
                    close()
                },
                color = tint,
            )
        }
    }
}

/**
 * 슬라이더가 덮을 구간(담당자 지시 2026-09-29).
 *
 * **왜 나누는가**: 20Hz~20kHz 를 한 슬라이더에 펴면 3 십년(decade)이
 * 한 화면에 들어간다. 400px 폭이면 1px 이 약 1.8% — 3150Hz 옆의
 * 3100·3200 을 손가락으로 가를 수 없다.
 *
 * 구간을 한 옥타브 남짓으로 좁히면 같은 1px 이 몇 Hz 가 된다.
 *
 * 나눈 자리는 음향에서 흔히 쓰는 경계다 — 저역/중역/고역. [All] 은
 * 예전 그대로라, 넓게 훑다가 자리를 잡고 좁히는 쓰임이 된다.
 */
private enum class ToneRange(
    val labelKo: String,
    val minHz: Double,
    val maxHz: Double,
) {
    All("전체", MIN_TONE_HZ, MAX_TONE_HZ),
    Low("저역", 20.0, 200.0),
    Mid("중역", 200.0, 2_000.0),
    High("고역", 2_000.0, MAX_TONE_HZ),
    ;

    /**
     * 주파수를 슬라이더 자리(0~1)로. **로그 눈금이다.**
     *
     * 구간 밖의 값은 끝에 붙는다 — 직접 적어 넣은 주파수를 구간 때문에
     * 바꾸지는 않고, 슬라이더만 갈 수 있는 데까지 간다.
     */
    fun toSlider(hz: Double): Float {
        val lo = log10(minHz)
        val hi = log10(maxHz)
        return ((log10(hz.coerceIn(minHz, maxHz)) - lo) / (hi - lo)).toFloat()
    }

    fun fromSlider(pos: Float): Double =
        10.0.pow(log10(minHz) + (log10(maxHz) - log10(minHz)) * pos.coerceIn(0f, 1f))
}

/** 구간을 고르는 칩 넷. 고른 것은 색으로 드러난다. */
@Composable
private fun ToneRangeChips(selected: ToneRange, onPick: (ToneRange) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (r in ToneRange.entries) {
            val on = r == selected
            Text(
                r.labelKo,
                color = if (on) SelahColors.OnChipOn else SelahColors.TextSecondary,
                fontSize = 10.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                softWrap = false,
                modifier = Modifier
                    .background(
                        if (on) SelahColors.ChipOn else SelahColors.Surface,
                        RoundedCornerShape(6.dp),
                    )
                    .clickable { onPick(r) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/**
 * 주파수를 **직접 적는 칸**(담당자 지시 2026-09-29).
 *
 * **슬라이더만으로는 특정 주파수를 맞출 수 없다.** 20Hz~20kHz 를 로그로
 * 편 슬라이더에서 3150Hz 를 정확히 짚으려면 손가락 굵기보다 가는 자리를
 * 눌러야 한다. RTA 가 「3150Hz 가 솟았다」고 알려 주었는데 3100 이나
 * 3200 을 내면 다른 대역을 듣는 셈이다.
 *
 * ## 적는 동안은 건드리지 않는다
 *
 * 글자를 쓰는 중에 값을 곧바로 되돌리면 「3」을 치자마자 20 으로
 * 튄다(범위 밖이라). 그래서 **적는 동안은 글자 그대로 두고**, 자리를
 * 옮기거나 완료를 누를 때 숫자로 읽는다.
 *
 * 숫자가 아니거나 범위 밖이면 **마지막 성한 값으로 되돌린다** — 조용히
 * 엉뚱한 주파수를 내는 것보다 낫다.
 */
@Composable
private fun ToneHzField(toneHz: Double, onToneHz: (Double) -> Unit) {
    var text by remember(toneHz) { mutableStateOf(formatHzPlain(toneHz)) }
    var editing by remember { mutableStateOf(false) }

    fun commit() {
        val v = text.trim().replace(",", "").toDoubleOrNull()
        if (v != null && v >= MIN_TONE_HZ && v <= MAX_TONE_HZ) {
            onToneHz(v)
        } else {
            text = formatHzPlain(toneHz)
        }
        editing = false
    }

    Row(verticalAlignment = Alignment.Bottom) {
        BasicTextField(
            value = if (editing) text else formatHzPlain(toneHz),
            onValueChange = { new ->
                editing = true
                // 숫자와 소수점만 받는다. 붙여넣기로 글자가 들어오는 것도 막는다.
                text = new.filter { it.isDigit() || it == '.' }.take(7)
            },
            singleLine = true,
            textStyle = TextStyle(
                color = SelahColors.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            ),
            cursorBrush = SolidColor(SelahColors.Accent),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .width(96.dp)
                .onFocusChanged { if (!it.isFocused && editing) commit() }
                // **이름을 붙인다.** 화면에는 옆에 「Hz」가 적혀 있고 위에
                // 제목도 있지만, 그것들은 **다른 마디**다 — 읽개가 이 칸을
                // 짚으면 숫자만 읽고 **무엇을 적는 칸인지 말하지 못한다.**
                // 눈으로 보면 멀쩡해서 이런 자리는 눈으로 못 찾는다.
                .semantics { contentDescription = "순음 주파수(Hz)" },
        )
        Text(
            " Hz",
            color = SelahColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 3.dp),
        )
    }
}

/**
 * 적는 칸에 넣을 글자. **늘 Hz 단위의 온전한 숫자다.**
 *
 * 화면의 다른 자리는 1kHz 위를 「1.00 kHz」로 줄여 적지만, 여기서는
 * 그러면 안 된다 — 사람이 그 칸에 3150 을 쓰는데 3.15 가 보이면
 * **무슨 단위로 쓰는지**가 헷갈린다.
 */
private fun formatHzPlain(hz: Double): String =
    if (hz >= 100.0 || hz == kotlin.math.floor(hz)) "%.0f".format(hz) else "%.1f".format(hz)

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
    /** 설명을 갈아 끼운다. 대역 줄은 **지금 나갈 중심**을 적어야 한다. */
    noteKo: String = signal.noteKo,
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
            Text(noteKo, color = SelahColors.TextMuted, fontSize = 10.sp, lineHeight = 14.sp)
        }
        PlayStopMark(playing)
    }
}

/**
 * 제목 줄의 높이 — **늘 같아야 한다.**
 *
 * 「RTA에서 재기」가 뜨고 지면서 아래가 밀리던 자리다(2026-10-01). 단추가
 * 안 뜰 때도 이 높이를 지키므로, 무엇을 틀든 아래는 안 움직인다.
 * 40dp 는 Material 의 글자 단추 최소 높이다 — 그보다 낮추면 단추가 잘린다.
 */
private val HEADER_ROW_HEIGHT = 40.dp
