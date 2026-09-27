package kr.joa.selahrta.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.dsp.FeedbackCandidate
import kr.joa.selahrta.dsp.FeedbackState
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.ui.RtaView
import kr.joa.selahrta.ui.theme.SelahColors

/** 1kHz 아래는 Hz, 위는 kHz 로 적는다. 자릿수가 너무 길어지지 않게. */
fun formatHz(hz: Double): String =
    if (hz < 1000) "%.0f".format(hz) else "%.2f".format(hz / 1000)

/** [formatHz] 와 짝이 되는 단위. */
fun hzUnit(hz: Double): String = if (hz < 1000) "Hz" else "kHz"

/**
 * 후보의 상태에 따른 색 — 지속은 빨강, 의심은 주황.
 *
 * 한 자리에 모아 둔 까닭은 **막대·표식·글자가 같은 색이어야** 하기 때문이다.
 * 셋이 어긋나면 어느 것이 그 후보의 것인지 알 수 없다.
 */
fun feedbackTone(state: FeedbackState): Color =
    if (state == FeedbackState.Persistent) SelahColors.High else SelahColors.Warn

/**
 * 좁을 때 눈금으로 쓸 주파수. 옥타브마다 하나씩이면 폰 너비에서 겹치지 않는다.
 *
 * 차트를 길게 늘이면 31칸이 모두 자리를 얻으므로 이 추림은 쓰지 않는다
 * ([WIDE_LABEL_SLOT] 참고).
 */
private val LABEL_BANDS = listOf(0, 3, 6, 9, 12, 15, 18, 21, 24, 27, 30)

/**
 * 밴드 한 칸이 이만큼 넓어지면 **31칸에 모두 글자를 넣는다.**
 *
 * 옥타브마다 하나만 찍으면 「지금 솟은 막대가 몇 Hz 인가」를 옆 눈금에서
 * 세어 와야 한다. 칸이 넓어지면 셀 까닭이 없다.
 *
 * 가장 긴 글자는 「12.5k」다. 8sp 에서 21dp 쯤이라 20dp 칸에서도 이웃과
 * 부딪히지 않는다(칸 밖으로 넘치되 가운데 정렬이라 반씩 나눠 쓴다).
 *
 * **24dp 였던 것을 내렸다.** 눕힌 화면의 칸이 23.9dp 라 간발의 차로
 * 떨어졌고, 눈금이 옥타브 단위로 추려졌다(기기에서 확인). 문턱을 글자
 * 크기에서 끌어오지 않고 눈대중으로 잡으면 이렇게 된다.
 */
private val WIDE_LABEL_SLOT = 20.dp

/** 이보다 좁은 칸에서는 눈금 글자를 한 호 줄인다. */
private val ROOMY_LABEL_SLOT = 28.dp

/** 차트를 늘일 때 밴드 한 칸에 주는 너비. 31칸이면 약 930dp 가 된다. */
val BAND_SLOT_WIDE: Dp = 30.dp

/**
 * 눈금 글자가 차지하는 높이. 차트 높이에서 이만큼 빼고 막대를 그린다.
 *
 * 9sp 글자의 줄 높이(약 13dp)에 여유를 둔 값이다. 14dp 로 두었더니 숫자의
 * 아랫부분이 잘렸다(기기에서 확인) — 글자 크기만 보고 잡으면 모자란다.
 */
internal val LABEL_ROW_HEIGHT = 18.dp

/**
 * 31밴드 막대(컨셉 화면 2번).
 *
 * **값이 없으면 막대를 그리지 않는다.** 0 으로 그리면 「아주 조용하다」로
 * 읽히는데, 아직 첫 FFT 가 안 찬 것과 조용한 것은 다른 일이다.
 *
 * 분해되지 않는 저역 밴드는 흐리게 그린다. 그 값은 이웃에서 새어 온 것이라
 * 실제 그 대역의 에너지가 아니다 — 또렷하게 그리면 없는 저음이 있는 것처럼
 * 보이고, 담당자는 그걸 보고 EQ 를 만진다.
 */
@Composable
fun BandMeter(
    rta: RtaView?,
    floorDb: Double,
    ceilDb: Double,
    modifier: Modifier = Modifier,
    showHold: Boolean = true,
    /**
     * 차트 전체 높이(눈금 글자 포함).
     *
     * **null 이면 [modifier] 가 준 높이를 그대로 쓴다.** 가로로 눕힌 화면은
     * 세로가 얼마나 남는지 미리 못박을 수 없어 — 기기마다, 시스템 표시줄
     * 크기마다 다르다 — 남는 자리를 `weight(1f)` 로 받아 채운다. 높이를
     * 숫자로 정해 두었더니 폰을 눕혔을 때 차트 아래가 탭 바에 잘렸다
     * (기기에서 확인).
     */
    chartHeight: Dp? = 200.dp,
    /**
     * 밴드 한 칸의 최소 너비. 화면이 이보다 좁으면 **차트를 늘이고 옆으로
     * 밀어** 보게 한다.
     *
     * 0 이면 늘이지 않는다(화면 너비에 31칸을 그대로 욱여넣는다).
     *
     * ## 왜 늘이는가
     *
     * 폰 세로 화면은 400dp 안팎이라 31밴드가 한 칸에 13dp 씩밖에 못 가진다.
     * 막대가 실오라기 같아 어느 대역이 솟았는지 읽히지 않는다. 그래서
     * 처음에는 **화면 자체를 가로로 돌리자**고 했는데, 담당자가 「차트만
     * 길게」로 고쳤다(2026-09-24). 맞는 판단이다 — 화면을 돌리면 위 칩·아래
     * 탭·설정까지 전부 따라 돌아가고, 폰을 들고 재는 사람은 그때마다 자세를
     * 바꿔야 한다. 길어져야 하는 것은 차트뿐이다.
     */
    minSlotWidth: Dp = 0.dp,
    /**
     * 지금 잡고 있는 하울링 후보. 차트 위에 **세로 표식**으로 찍는다.
     *
     * ## 왜 여기에 찍는가
     *
     * 예전에는 피드백이 따로 한 화면이었다. 그런데 「어느 대역이 솟았나」와
     * 「그게 하울링인가」는 같은 그림에서 읽어야 하는 한 가지 질문이다 —
     * 따로 두면 두 화면을 번갈아 보며 주파수를 머리로 맞춰야 했다.
     * 담당자가 합치자고 했고(2026-09-24), 맞는 정리다.
     *
     * **막대가 아니라 선으로 찍는다.** 후보의 주파수는 칸 사이까지 되찾은
     * 값이라([FeedbackCandidate.hz]) 밴드 가운데가 아니다. 한 밴드는 23% 나
     * 넓어서, 칸 가운데에 찍으면 실제로 깎아야 할 자리와 눈에 띄게 어긋난다.
     *
     * 정확한 숫자는 **차트 아래 글자**가 맡는다. 선은 「저기」를 가리키고
     * 글자가 「2.49kHz」를 말한다 — 그림 위에 숫자를 겹쳐 쓰면 막대를 가린다.
     */
    feedback: List<FeedbackCandidate> = emptyList(),
    /**
     * 차트 **상자 안**에 얹을 모드 고르개(2026-09-25 담당자 지시).
     *
     * 위에 한 줄을 따로 쓰던 것을 여기로 넣었다. 고르개가 가리키는 것
     * 바로 위에 있어야 「이 차트를 무엇으로 볼까」로 읽히고, 눕힌 화면에서
     * 차트가 그만큼 넓어진다. null 이면 안 그린다.
     */
    modes: (@Composable () -> Unit)? = null,
    /**
     * 고르개 **왼쪽**에 놓을 단추(멈춤 등). null 이면 안 그린다.
     *
     * 차트 위에 겹쳐 얹지 않고 한 줄을 함께 쓴다 — 겹쳤더니 세로축 위쪽
     * 숫자를 가렸다(Spectrogram 에서 실제로 겪었다).
     */
    controls: (@Composable () -> Unit)? = null,
    /** 세로축을 누르면 부른다(고정↔자동). null 이면 누를 수 없다. */
    onAxisTap: (() -> Unit)? = null,
) {
    // **눈금 글자는 막대와 함께 밀려야 한다.** 따로 두면 밀고 난 뒤 막대와
    // 글자가 어긋나, 솟은 자리의 주파수를 잘못 읽는다.
    val scroll = rememberScrollState()
    // 후보의 주파수를 그림 위에 적는 데 쓴다. Canvas 안에서는 Text 를 쓸 수
    // 없어 글자를 미리 재 두어야 한다.
    val measurer = rememberTextMeasurer()
    Column(modifier) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                // null 이면 **남는 높이를 받는다.** `fillMaxHeight` 를 쓰면
                // 아래 설명 줄까지 밀어내 가로축 눈금이 화면 밖으로 나간다.
                .then(if (chartHeight != null) Modifier.height(chartHeight) else Modifier.weight(1f))
                .background(SelahColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
                .padding(8.dp),
        ) {
            // 세로축이 가져가는 폭을 빼고 그린다 — 안 빼면 오른쪽 끝
            // 밴드가 상자 밖으로 밀린다.
            val plotWidth = (maxWidth - Y_AXIS_WIDTH - 4.dp).coerceAtLeast(0.dp)
            val chartWidth = maxOf(plotWidth, minSlotWidth * ThirdOctave.BAND_COUNT)
            val slotWidth = chartWidth / ThirdOctave.BAND_COUNT
            val labelEvery = slotWidth >= WIDE_LABEL_SLOT
            val labelSize = if (slotWidth >= ROOMY_LABEL_SLOT) 9.sp else 8.sp
            // `maxHeight` 는 안쪽 여백을 이미 뺀 값이다. 여기서 눈금 글자
            // 자리만 더 빼면 막대가 쓸 높이가 된다 — 바깥에서 준 높이가
            // 고정이든 weight 든 똑같이 맞는다.
            val headerHeight =
                if (modes != null || controls != null) CONTROL_ROW_HEIGHT else 0.dp
            val barsHeight = (maxHeight - LABEL_ROW_HEIGHT - headerHeight).coerceAtLeast(0.dp)

            // **세로축은 밀리지 않는다**(2026-09-25 담당자 지적: 「Y축에는
            // SPL(dB) 표시가 있어야 하는게 아닌지?」).
            //
            // 맞는 지적이다. 가로눈금 다섯 줄만 있고 숫자가 없어, 막대가
            // 얼마인지는 아래 설명 줄의 「세로 30 ~ 80 dB」를 읽고 머리로
            // 나눠야 했다.
            //
            // **가로 스크롤 밖에 둔다.** 안에 넣으면 옆으로 민 순간 세로축이
            // 따라 밀려 화면에서 사라진다 — 세로축은 어디를 보든 그 자리에
            // 있어야 하는 것이다.
            Column(Modifier.fillMaxWidth()) {
            if (headerHeight > 0.dp) {
                Row(
                    Modifier.fillMaxWidth().height(headerHeight),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box { controls?.invoke() }
                    Box { modes?.invoke() }
                }
            }

            Row(Modifier.fillMaxWidth()) {
                YAxis(
                    floorDb = floorDb,
                    ceilDb = ceilDb,
                    height = barsHeight,
                    modifier = Modifier.padding(end = 4.dp),
                    onTap = onAxisTap,
                )
                Column(Modifier.horizontalScroll(scroll)) {
                Canvas(Modifier.width(chartWidth).height(barsHeight)) {
                val span = (ceilDb - floorDb).coerceAtLeast(1.0)

                // 가로 눈금 — **dB 에 매인다.**
                //
                // 예전에는 높이를 다섯 등분해 그렸다. 축이 움직이던 때에는
                // 같은 선이 매번 다른 dB 를 가리켰고, 그래서 선이 있어도
                // 「저 막대가 몇 dB 인가」에 답하지 못했다. 축을 고정했으니
                // 선도 dB 자리에 박는다.
                for (db in gridLinesDb(floorDb, ceilDb)) {
                    val y = (((ceilDb - db) / span) * size.height).toFloat()
                    drawLine(
                        SelahColors.Outline,
                        Offset(0f, y),
                        Offset(size.width, y),
                        strokeWidth = 1f,
                    )
                }

                if (rta == null) return@Canvas

                val n = ThirdOctave.BAND_COUNT
                val slot = size.width / n
                val barW = slot * 0.72f

                // **후보가 앉은 밴드는 막대 색을 바꾼다**(2026-09-24 담당자 지시:
                // 「피드백으로 의심이 되는 주파수를 색상을 달리해 달라」).
                //
                // 표식 선만으로는 「어느 막대가 그것인가」가 한눈에 안 들어온다 —
                // 선은 가늘고, 옆 막대와 겹쳐 보인다. 막대 자체가 물들면 찾을
                // 것이 없다.
                //
                // 한 밴드에 둘이 앉으면 **더 센 쪽**이 이긴다. 지속이 의심보다
                // 급한 소식이라 그쪽 색으로 칠한다.
                val bandTone = arrayOfNulls<FeedbackState>(n)
                for (c in feedback) {
                    val b = ThirdOctave.bandPosition(c.hz)
                    if (b < -0.5 || b > n - 0.5) continue
                    val i = Math.round(b).toInt().coerceIn(0, n - 1)
                    if (bandTone[i] != FeedbackState.Persistent) bandTone[i] = c.state
                }

                for (i in 0 until n) {
                    val x = slot * i + (slot - barW) / 2f
                    val v = rta.bandsSpl[i]
                    val frac = ((v - floorDb) / span).coerceIn(0.0, 1.0).toFloat()
                    val h = size.height * frac

                    if (h > 0.5f) {
                        val tone = bandTone[i]
                        drawRect(
                            color = when {
                                // 후보가 앉은 밴드. 흐린 밴드라도 물들인다 —
                                // 「못 믿는 값」과 「하울링 후보」는 다른 말이고,
                                // 둘 다 알려야 한다(알파로 흐림은 그대로 둔다).
                                // **밴드를 색으로 가르지 않는다**(2026-09-26
                                // 담당자 지시). 까닭은 [UNRESOLVED_ALPHA] 참고.
                                tone != null -> feedbackTone(tone)
                                else -> SelahColors.Accent
                            },
                            topLeft = Offset(x, size.height - h),
                            size = Size(barW, h),
                        )
                    }

                    if (showHold) {
                        val hv = rta.holdSpl[i]
                        val hf = ((hv - floorDb) / span).coerceIn(0.0, 1.0).toFloat()
                        if (hf > 0.01f) {
                            val hy = size.height - size.height * hf
                            drawRect(
                                color = SelahColors.TextSecondary,
                                topLeft = Offset(x, hy),
                                size = Size(barW, 2f),
                            )
                        }
                    }
                    }

                // **후보는 막대 위에 찍는다.** 밑에 깔면 솟은 막대가 가린다 —
                // 하필 후보가 있는 자리가 제일 높은 막대라 늘 가려진다.
                for (c in feedback) {
                    val pos = ThirdOctave.bandPosition(c.hz)
                    // 차트 밖의 소리는 찍지 않는다. 자르면 20Hz 아래 소리가
                    // 20Hz 자리에 붙어, 없는 대역에 후보가 있는 것처럼 보인다.
                    if (pos < 0.0 || pos > n - 1.0) continue
                    val cx = slot * (pos.toFloat() + 0.5f)
                    val tone = feedbackTone(c.state)
                    drawLine(
                        color = tone.copy(alpha = 0.85f),
                        start = Offset(cx, 0f),
                        end = Offset(cx, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                    // 위쪽에 작은 삼각형을 얹어 **선이 어디를 가리키는지**
                    // 분명히 한다. 가는 선 하나는 눈금선과 헷갈린다.
                    val t = 5.dp.toPx()
                    drawPath(
                        Path().apply {
                            moveTo(cx - t, 0f)
                            lineTo(cx + t, 0f)
                            lineTo(cx, t * 1.6f)
                            close()
                        },
                        color = tone,
                    )

                    // **정확한 주파수를 그림 위에 적는다.**
                    //
                    // 담당자가 「피드백이 발생하는 부분은 RTA 그래프를 보고
                    // 판단하면 된다」고 정리했다(2026-09-24). 그러려면 그래프가
                    // 숫자까지 말해야 한다 — 밴드 하나는 23% 나 넓어서
                    // 「800 칸 근처」와 「786Hz」는 EQ 에서 다른 자리다.
                    val laid = measurer.measure(
                        AnnotatedString("${formatHz(c.hz)}${hzUnit(c.hz)}"),
                        style = TextStyle(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = tone,
                        ),
                    )
                    // 표식 오른쪽에 두되, 오른쪽 끝에서는 왼쪽으로 넘긴다 —
                    // 안 그러면 마지막 밴드의 후보에서 글자가 잘린다.
                    val gap = 4.dp.toPx()
                    val lx = if (cx + gap + laid.size.width <= size.width) {
                        cx + gap
                    } else {
                        (cx - gap - laid.size.width).coerceAtLeast(0f)
                    }
                    drawText(laid, topLeft = Offset(lx, t * 1.6f))
                }
                }

                // 눈금을 막대와 같은 방식으로 나눈다. 막대도 31칸 균등이라
                // 여기서도 31칸을 만들고 그 중 몇 칸에만 글자를 넣으면 자리가 맞는다.
                Row(Modifier.width(chartWidth).height(LABEL_ROW_HEIGHT)) {
                    repeat(ThirdOctave.BAND_COUNT) { b ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (labelEvery || b in LABEL_BANDS) {
                                Text(
                                    ThirdOctave.label(b),
                                    // **눈금은 흐리게 두지 않는다**(2026-09-24
                                    // 담당자 지시: 「아래 주파수 표시 글자 색상이
                                    // 옅은 그레이라서 잘 안 보인다」).
                                    //
                                    // 다른 곳의 TextMuted 는 「읽어도 되고 안 읽어도
                                    // 되는 것」에 쓴다. 그런데 이 글자는 **막대를
                                    // 읽는 데 반드시 필요하다** — 몇 Hz 인지 모르면
                                    // 막대는 그냥 무늬다. 8sp 로 작기까지 해서 흐린
                                    // 회색으로는 예배당 조명에서 안 보인다.
                                    color = SelahColors.TextPrimary,
                                    fontSize = labelSize,
                                    maxLines = 1,
                                    softWrap = false,
                                    // 좁을 때 칸 하나는 31분의 1(약 28px)뿐이라
                                    // 「630」이 「63」으로 잘린다. 하필 630Hz 자리에
                                    // 63 이 찍혀 적극적으로 오해를 부른다. 칸 밖으로
                                    // 넘치게 두고 가운데 정렬한다.
                                    modifier = Modifier.wrapContentWidth(unbounded = true),
                                )
                            }
                        }
                    }
                }
                }
            }

            }

            // **안내는 밀리지 않는다.** 스크롤 안에 두면 옆으로 민 뒤 사라져,
            // 정작 막대가 아직 없는 까닭이 화면 밖으로 나간다.
            if (rta == null) {
                Text(
                    "측정을 시작하면 막대가 나타납니다.",
                    color = SelahColors.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/**
 * 막대의 세로 범위를 정한다.
 *
 * 보정 상태에 따라 절대 눈금이 달라지므로 값에서 끌어온다. 고정 눈금을 쓰면
 * 보정하는 순간 막대가 전부 천장에 붙거나 바닥에 깔린다.
 */
/** 지금 가장 큰 밴드. 잰 것이 없으면 null. */
fun rtaTopSpl(rta: RtaView?, resolvedOnly: Boolean = true): Double? {
    if (rta == null) return null
    var top = Double.NEGATIVE_INFINITY
    for (i in rta.bandsSpl.indices) {
        if (resolvedOnly && !rta.resolved[i]) continue
        if (rta.bandsSpl[i] > top) top = rta.bandsSpl[i]
    }
    return top.takeIf { it.isFinite() }
}

/**
 * **세로축은 움직이지 않는다 — 0 ~ 120 dB 고정**(2026-09-26 담당자 지시:
 * 「처음부터 0부터 120까지를 범위로 두면 안되는지」).
 *
 * ## 따라다니게 두면 안 되는 까닭
 *
 * 처음에는 장마다 최고 밴드에서 천장을 다시 셈했다. 최고 밴드는 쉬지 않고
 * 흔들리므로 축이 **초당 여러 번 5dB 씩 튀었다.** 그것은 RTA 로 보려던
 * 것을 정확히 가린다:
 *
 * - **전체가 커져도 그림이 그대로다.** 소리가 6dB 커지면 축도 따라 올라가
 *   막대 높이가 제자리다 — 「커졌다」가 화면에서 사라진다.
 * - **시간을 건너 견줄 수 없다.** 같은 높이가 아까와 다른 dB 를 뜻한다.
 *
 * 가운데 길(이력을 두고 가끔만 옮기기)도 만들어 봤는데, 기기에서 재 보니
 * 여전히 옮겨지는 순간이 있었다 — 그때마다 그림 전체가 한 번에 뛴다.
 * **가끔 튀는 것도 튀는 것이다.**
 *
 * ## 왜 0 ~ 120 인가
 *
 * 그것이 **이 기기가 낼 수 있는 값의 전부**다. 미보정 눈금은 0 dBFS 를
 * 120 dB SPL 로 놓으므로([ASSUMED_FULL_SCALE_SPL]) 120 위의 값은 나올 수
 * 없고, 음압에 음수도 없다. 축이 곧 기기의 범위라 **잘려 나가는 값이 없다.**
 *
 * ## 무엇을 내주는가
 *
 * 높이를 다 쓰지 못한다. 예배당에서 실제로 읽히는 폭은 30~100 dB 안팎이라,
 * 아래 4분의 1(0~30)은 어느 방에서도 비어 있고 위쪽도 큰 찬양에서만 닿는다.
 * 폭을 좁히면 그만큼 자세히 보이지만, **좁히는 순간 「그 밖의 값」이
 * 생긴다** — 그리고 그 값은 화면에서 사라진다. 지금은 사라지는 값이 없다.
 *
 * 좁히고 싶으면 [RTA_FLOOR_DB]·[RTA_CEIL_DB] 두 줄만 고치면 된다.
 */
val RTA_RANGE: Pair<Double, Double> = RTA_FLOOR_DB to RTA_CEIL_DB

/**
 * 세로축을 어떻게 잡을 것인가(2026-09-26 담당자 지시: 「세로축을 누르면
 * 자동으로도 되게 하면 좋겠네요. 사용자가 바꿀 수 있게요」).
 *
 * 기본은 [Fixed] 다 — 움직이지 않는 축이라야 시간을 건너 견줄 수 있다.
 * 다만 조용한 방에서는 막대가 아래 3분의 1에 눌려 모양이 안 보이므로,
 * **그때만** 눌러서 [Auto] 로 바꾼다.
 */
enum class AxisMode(val labelKo: String) {
    /** 0 ~ 120 dB 고정. 기기가 낼 수 있는 값의 전부다. */
    Fixed("고정"),

    /** 들어오는 값을 따라간다. **가끔만** 옮긴다 — 아래 머리말 참고. */
    Auto("자동"),
    ;

    fun next(): AxisMode = if (this == Fixed) Auto else Fixed
}

/**
 * 고른 방식대로 눈금을 낸다.
 *
 * ## 자동은 「따라다니기」가 아니다
 *
 * 처음 만든 자동은 장마다 천장을 다시 셈해 **초당 여러 번 튀었다.**
 * 그러면 전체가 커져도 그림이 그대로라, RTA 로 보려던 것이 가려진다.
 *
 * 그래서 자동도 **가끔만** 옮긴다: 값이 천장에 닿으려 하면 곧바로 올리고
 * (잘려 나간 값은 없는 값이다), 한참 아래에 깔려 있으면 [SETTLE_MS] 동안
 * 그대로일 때만 내린다. 말 사이의 빈틈에는 움직이지 않는다.
 *
 * ## 한 번만 띄우고 값 흐름을 받는다
 *
 * `LaunchedEffect(top)` 으로 적었다가 고쳤다. `top` 은 초당 열몇 번
 * 바뀌므로 효과가 그때마다 끊기고 다시 시작했고, 시작도 하기 전에 끊기는
 * 일이 잦아 갱신이 띄엄띄엄 들어갔다 — 기기에서 축이 100 → 50 → 100 으로
 * 뛰었다. `snapshotFlow` 로 받으면 빠뜨리는 장이 없다.
 */
@Composable
fun rememberAxisRange(mode: AxisMode, top: Double?): Pair<Double, Double> {
    var ceil by remember { mutableStateOf(Double.NaN) }
    val latest = rememberUpdatedState(top)

    LaunchedEffect(mode) {
        if (mode != AxisMode.Auto) return@LaunchedEffect
        var lowSinceMs = 0L
        snapshotFlow { latest.value }.collect { v ->
            if (v == null) return@collect
            if (ceil.isNaN()) {
                ceil = stepUp(v + HEADROOM_DB)
                return@collect
            }
            val now = System.currentTimeMillis()
            when {
                v > ceil - HEADROOM_DB -> {
                    ceil = stepUp(v + HEADROOM_DB)
                    lowSinceMs = 0L
                }

                v < ceil - AUTO_SPAN_DB + SLACK_DB -> {
                    if (lowSinceMs == 0L) {
                        lowSinceMs = now
                    } else if (now - lowSinceMs >= SETTLE_MS) {
                        ceil = stepUp(v + HEADROOM_DB)
                        lowSinceMs = 0L
                    }
                }

                else -> lowSinceMs = 0L
            }
        }
    }

    if (mode == AxisMode.Fixed || ceil.isNaN()) return RTA_RANGE
    return (ceil - AUTO_SPAN_DB).coerceAtLeast(RTA_FLOOR_DB) to ceil
}

/** [STEP_DB] 배수로 올려 맞춘다. */
private fun stepUp(db: Double): Double = kotlin.math.ceil(db / STEP_DB) * STEP_DB

/** 위쪽 여유. 값이 이 안으로 들어오면 천장을 올린다. */
private const val HEADROOM_DB = 6.0

/** 이만큼 아래에 깔려 있어야 「내려도 된다」로 본다. */
private const val SLACK_DB = 25.0

/**
 * 내리기 전에 그 상태가 이어져야 하는 시간.
 *
 * **말 사이의 빈틈보다 길어야 한다.** 3초로 두었더니 설교 중 문장
 * 사이에서 내려갔다 올라오기를 되풀이했다 — 천천히 튀는 것일 뿐 튀는
 * 것은 같다.
 */
private const val SETTLE_MS = 10_000L

/** 천장이 움직이는 단위. 5dB 로 하면 경계에서 자주 오간다. */
private const val STEP_DB = 10.0

/** 자동일 때 보여 주는 폭. 예배당에서 읽히는 폭이다. */
private const val AUTO_SPAN_DB = 50.0

/**
 * 바닥 — **음압에 음수는 없다.**
 *
 * 예전 자리표시는 `-60 ~ 0` 이었는데 그 숫자는 **dBFS 의 눈금**이다. 값은
 * 미보정일 때도 SPL 이라(짐작한 만재 음압을 더한다) 음수가 나올 수 없고,
 * 실제로 그 자리표시를 데이터로 잘못 읽은 일이 있었다.
 */
const val RTA_FLOOR_DB: Double = 0.0

/** 천장 — 미보정 눈금의 만재 음압([ASSUMED_FULL_SCALE_SPL])과 같다. */
const val RTA_CEIL_DB: Double = 120.0



/**
 * 세로축이 가져가는 폭. 「110」까지 들어가면 넉넉하다.
 *
 * **Spectrum 화면도 같은 것을 쓴다.** 두 차트를 번갈아 보는 화면이라
 * 세로축이 서로 다른 자리에 있으면 눈이 매번 다시 자리를 잡아야 한다.
 */
internal val Y_AXIS_WIDTH = 26.dp

/**
 * 모드 고르개·단추가 쓰는 줄의 높이.
 *
 * **얹지 않고 자리를 내준다.** 차트 위에 겹쳐 두었더니 세로축 맨 위 숫자를
 * 가렸고(2026-09-25), 자리를 오른쪽으로 옮겼더니 이번에는 봉우리 옆 숫자와
 * 겹쳤다. 겹치는 자리는 옮겨 봐야 다른 것을 가릴 뿐이다 — 30dp 를 내주고
 * 아무것도 가리지 않는 편이 낫다.
 */
internal val CONTROL_ROW_HEIGHT = 30.dp

/**
 * 가로선을 그을 dB 자리 — [GRID_STEP_DB] 마다 한 줄.
 *
 * ## 70·80·90 을 굵게 그었다가 뺐다 (2026-09-26)
 *
 * 설교 권장이 68~75dBA 라 그 자리에 선이 있으면 좋겠다고 보았는데,
 * 담당자가 지우라고 했다. 맞는 판단이다 — **RTA 막대는 가중 없는
 * 밴드별 값**이고 권장 범위는 **A 가중 Leq** 다. 같은 그림에 그어 두면
 * 서로 견줄 수 있는 두 값처럼 보이는데, 실제로는 다른 잣대다.
 *
 * 권장 범위와 견주는 자리는 측정 화면의 큰 숫자다. 거기서는 같은
 * 가중·같은 시간평균으로 잰 값을 견준다.
 */
internal fun gridLinesDb(floorDb: Double, ceilDb: Double): List<Double> {
    val out = ArrayList<Double>()
    var db = kotlin.math.ceil(floorDb / GRID_STEP_DB) * GRID_STEP_DB
    while (db <= ceilDb) {
        out.add(db)
        db += GRID_STEP_DB
    }
    return out
}

/** 보통 선의 간격. */
private const val GRID_STEP_DB = 20.0

/** 눈금 글자의 높이 어림. 선과 글자 가운데를 맞추는 데 쓴다. */
private val LABEL_HALF = 5.dp

/**
 * 세로축 — **막대가 얼마인지 눈으로 읽게 한다.**
 *
 * 가로눈금과 **같은 자리에** 숫자를 놓는다([gridLinesDb] 를 함께 쓴다) —
 * 둘이 서로 다른 셈으로 자리를 잡으면 선과 숫자가 어긋나고, 그러면 숫자가
 * 가리키는 선이 어느 것인지 알 수 없다.
 *
 * 예전에는 높이를 다섯 등분해 놓았다. 축이 움직이던 때에는 그 수밖에
 * 없었지만, 축을 고정한 지금은 **숫자가 dB 자리에 박힌다.**
 */
@Composable
internal fun YAxis(
    floorDb: Double,
    ceilDb: Double,
    height: Dp,
    modifier: Modifier = Modifier,
    /** 누르면 고정↔자동을 오간다. null 이면 누를 수 없다. */
    onTap: (() -> Unit)? = null,
) {
    val span = (ceilDb - floorDb).coerceAtLeast(1.0)
    Box(
        modifier
            .width(Y_AXIS_WIDTH)
            .height(height)
            .then(if (onTap != null) Modifier.clickable { onTap() } else Modifier),
    ) {
        for (db in gridLinesDb(floorDb, ceilDb)) {
            // 맨 위·맨 아래 글자가 상자 밖으로 나가지 않게 잡아 둔다.
            val y = (height * (((ceilDb - db) / span).toFloat()) - LABEL_HALF)
                .coerceIn(0.dp, (height - LABEL_HALF * 2).coerceAtLeast(0.dp))
            Text(
                "%.0f".format(db),
                color = SelahColors.TextMuted,
                fontSize = 8.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.align(Alignment.TopEnd).offset(y = y),
            )
        }
    }
}
