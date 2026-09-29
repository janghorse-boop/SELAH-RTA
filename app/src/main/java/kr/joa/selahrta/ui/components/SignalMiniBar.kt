package kr.joa.selahrta.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.audio.SignalChannels
import kr.joa.selahrta.audio.TestSignal
import kr.joa.selahrta.dsp.BandNoiseFilter
import kr.joa.selahrta.ui.nav.ViewMode
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * **틀어 둔 테스트 신호를 어느 화면에서나 알리고 멈춘다**(지시서 §1).
 *
 * ## 왜 필요한가
 *
 * 재생은 이미 화면을 넘어 이어진다 — `CaptureViewModel` 하나가 앱
 * 전체를 덮으므로, 테스트 신호 화면을 떠나도 소리는 계속 나간다.
 * 그것 자체는 **필요한 동작**이다. 핑크 노이즈를 틀어 놓고 RTA 로
 * 건너가 재는 것이 이 앱을 쓰는 방식이기 때문이다.
 *
 * 그런데 **알리는 곳도 멈추는 곳도 없었다.** 넘어가고 나면 화면 어디에도
 * 표시가 없고, 멈추려면 도구 탭으로 되돌아가야 한다.
 * `SelahApp` 의 백그라운드 처리에 적힌 걱정이 —「예배당에서 순음을 켜
 * 놓고 앱을 나가면 멈출 방법이 화면에 없다」— **앱 안에서** 그대로
 * 재현되고 있었다.
 *
 * ## 무엇을 적는가
 *
 * 이름만으로는 모자란다. **주파수와 좌우를 함께 적는다** — 왼쪽만
 * 틀어 둔 줄 모르면 RTA 를 보고 「오른쪽 앰프가 죽었다」는 잘못된
 * 결론에 이른다.
 */
@Composable
fun SignalMiniBar(
    playing: TestSignal,
    toneHz: Double,
    channels: SignalChannels,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(SelahColors.SurfaceVariant)
            .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 소리가 나가는 중이라는 표. 색만으로 말하지 않으므로 글이 옆에 있다.
        Canvas(Modifier.size(7.dp)) { drawCircle(SelahColors.Accent) }
        Text(
            signalMiniBarLabel(playing, toneHz, channels),
            Modifier.weight(1f),
            color = SelahColors.TextPrimary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(
            onClick = onStop,
            // **읽어 주는 쪽에는 무엇을 멈추는지 말한다.** 「정지」만으로는
            // 측정을 멈추는지 소리를 멈추는지 갈리지 않는다.
            modifier = Modifier
                .height(36.dp)
                .semantics { contentDescription = "테스트 신호 멈추기" },
        ) {
            Canvas(Modifier.size(11.dp)) {
                drawRoundRect(
                    color = SelahColors.Accent,
                    cornerRadius = CornerRadius(size.minDimension * 0.18f),
                )
            }
            Text(
                "  정지",
                color = SelahColors.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * **소리가 멎은 까닭을 어느 화면에서나 알린다**(독립 검토 SRL-03).
 *
 * ## 왜 필요한가
 *
 * 출력이 죽으면 ViewModel 은 `playingSignal = null` 과 까닭을 함께
 * 적는데, 그 까닭을 그리는 곳이 **테스트 신호 화면 하나뿐**이었다.
 * RTA 에서는 미니 바가 사라질 뿐이라 **사람이 멈춘 것과 장치가 죽은
 * 것이 화면에서 똑같아 보였다.**
 *
 * 그러면 테스트 입력이 끊긴 줄 모르고 RTA 의 레벨이 내려간 것을 방이나
 * PA 의 응답으로 읽는다. 미니 바를 둔 까닭이 「무슨 소리가 나가는지
 * 알린다」인데, **소리가 안 나가게 된 것을 안 알리면 반쪽이다.**
 *
 * 이어폰이 빠져 멈춘 경우도 여기로 온다.
 */
@Composable
fun SignalNoticeBar(
    noticeKo: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(SelahColors.SurfaceVariant)
            .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            noticeKo,
            Modifier.weight(1f),
            color = SelahColors.Warn,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            // **줄여 적지 않는다.** 까닭이 잘리면 무엇이 일어났는지 모른다 —
            // 미니 바는 이름 한 줄이라 줄여도 되지만 이쪽은 아니다.
            maxLines = 3,
        )
        TextButton(
            onClick = onDismiss,
            modifier = Modifier
                .height(36.dp)
                .semantics { contentDescription = "알림 확인" },
        ) {
            Text("확인", color = SelahColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * 미니 바를 보일 것인가.
 *
 * **테스트 신호 화면에는 띄우지 않는다** — 그 화면엔 본체가 있어서,
 * 같은 말이 두 번 나오면 어느 쪽을 눌러야 하는지 헷갈린다.
 *
 * 설정 탭은 [ViewMode] 가 없어 `screen` 이 null 이다. 거기서도 보인다.
 */
fun signalMiniBarVisible(playing: TestSignal?, screen: ViewMode?): Boolean =
    playing != null && screen != ViewMode.Signal

/**
 * 바에 적는 한 줄 — 무엇이, 어느 쪽으로.
 *
 * **나가는 것을 적는다.** 대역 잡음은 슬라이더가 3147Hz 를 가리켜도
 * 실제로는 3150Hz 대역이 나간다([BandNoiseFilter.snapToBandCenter]).
 * 가리키는 값을 적으면 바가 거짓말을 한다.
 */
internal fun signalMiniBarLabel(
    playing: TestSignal,
    toneHz: Double,
    channels: SignalChannels,
): String {
    // 화면의 다른 자리와 같은 표기를 쓴다(`formatHz`·`hzUnit`) — 같은
    // 주파수가 자리마다 다르게 적히면 같은 것인지 알 수 없다.
    fun hz(v: Double) = "${formatHz(v)} ${hzUnit(v)}"
    val what = when (playing) {
        TestSignal.Custom -> "${hz(toneHz)} 순음"
        TestSignal.Band -> "${hz(BandNoiseFilter.snapToBandCenter(toneHz))} 대역"
        else -> playing.labelKo
    }
    return "$what · ${channels.labelKo}"
}
