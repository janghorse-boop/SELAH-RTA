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
        Text("테스트 신호 송출", color = SelahColors.TextPrimary, fontSize = 13.sp)
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

        // 신호 목록. **주파수를 쓰는 둘은 따로 뺀다** — 슬라이더와
        // 입력칸을 함께 그려야 하고, 둘이 같은 주파수를 쓰므로 고르개를
        // 두 번 그릴 까닭이 없다.
        for (s in TestSignal.entries) {
            if (s.usesPickedHz) continue
            SignalRow(s, s == playing, onPlay, onStop)
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
    val playingHere = playing?.usesPickedHz == true

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
        Text(
            "주파수 지정",
            color = if (playingHere) SelahColors.Accent else SelahColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = if (playingHere) FontWeight.SemiBold else FontWeight.Normal,
        )
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
        // **같은 주파수를 두 가지로 낸다**(담당자 지시 2026-09-29).
        //
        // 순음은 그 한 점만 울린다 — 그 자리에 방의 공진이 있으면
        // 엉뚱하게 크게 들린다. 1/3 옥타브 대역은 EQ 슬라이더 하나가
        // 덮는 폭을 고르게 채워, **그 슬라이더를 만지며 듣는** 데 맞다.
        //
        // 고르개를 두 번 그리지 않는다 — 같은 주파수를 쓰므로.
        for (s in TestSignal.entries.filter { it.usesPickedHz }) {
            val on = playing == s
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { if (on) onStop() else onPlay(s) }
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (s == TestSignal.Custom) "순음" else s.labelKo,
                        color = if (on) SelahColors.Accent else SelahColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        s.noteKo,
                        color = SelahColors.TextMuted,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                }
                PlayStopMark(on, Modifier.padding(start = 8.dp))
            }
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
                .onFocusChanged { if (!it.isFocused && editing) commit() },
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
        PlayStopMark(playing)
    }
}
