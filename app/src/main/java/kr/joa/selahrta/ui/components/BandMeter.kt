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
import androidx.compose.runtime.Composable
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
private val LABEL_ROW_HEIGHT = 18.dp

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
     * 세로축 숫자가 **dB SPL 인가**(2026-09-25).
     *
     * 값은 `bandsDbfs + offsetDb` 다. 보정이 없으면 오프셋이 0 이라 그대로
     * **dBFS**(0 이 만재, 음수)가 나온다 — 담당자 지시대로 축에 「SPL」이라
     * 적어 놓고 보니, 미보정 기기에서 `0 · -15 · -30` 위에 SPL 이라 적혀
     * 있었다(기기에서 확인).
     *
     * 어느 쪽인지는 밴드 값만 봐서는 알 수 없고, RTA 는 머리글을 접는
     * 화면이라 「미보정」 배지도 없다. 그래서 축 이름을 바깥에서 받는다.
     */
    calibrated: Boolean,
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
            val barsHeight = (maxHeight - LABEL_ROW_HEIGHT).coerceAtLeast(0.dp)

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
            Row(Modifier.fillMaxWidth()) {
                YAxis(
                    floorDb = floorDb,
                    ceilDb = ceilDb,
                    height = barsHeight,
                    modifier = Modifier.padding(end = 4.dp),
                )
                Column(Modifier.horizontalScroll(scroll)) {
                Canvas(Modifier.width(chartWidth).height(barsHeight)) {
                val span = (ceilDb - floorDb).coerceAtLeast(1.0)

                // 가로 눈금 다섯 줄
                repeat(5) { i ->
                    val y = size.height * i / 4f
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
                                tone != null && rta.resolved[i] -> feedbackTone(tone)
                                tone != null -> feedbackTone(tone).copy(alpha = 0.35f)
                                rta.resolved[i] -> SelahColors.Accent
                                // 분해 못 하는 밴드는 흐리게. 값이 아니라 새어 온 것이다.
                                else -> SelahColors.Accent.copy(alpha = 0.25f)
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

            // 모드 고르개는 차트 **위에 얹는다**. 자리를 따로 내주면 그만큼
            // 막대가 줄어드는데, 위쪽은 대개 비어 있다.
            //
            // **오른쪽에 둔다.** 왼쪽에 두었더니 세로축 맨 위 숫자를 가렸다
            // (기기에서 확인) — 눈금 숫자는 가려지면 축이 반쪽이 된다.
            modes?.let {
                Box(Modifier.align(Alignment.TopEnd)) { it() }
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
        Text(
            buildString {
                // 세로축이 무엇인지 적는다 — 숫자만으로는 dBFS 인지
                // dB SPL 인지 알 수 없다(담당자 지적, 2026-09-25).
                //
                // **미보정이면 SPL 이라 부르지 않는다.** 그 숫자는 아직 이
                // 기기의 dBFS 이고, SPL 이라 적는 순간 「85」가 음압으로
                // 읽힌다 — 그렇게 읽으면 소음 판정이 통째로 틀린다.
                append("가로 주파수(Hz) · 세로 ")
                append(if (calibrated) "SPL" else "dBFS(미보정)")
                append(" ${floorDb.toInt()} ~ ${ceilDb.toInt()} dB")
                // **밀 수 있을 때만 밀라고 한다.** 태블릿처럼 넓은 화면에서는
                // 31칸이 다 들어와 밀 것이 없다. maxValue 가 그 사실을 안다.
                if (scroll.maxValue > 0) append(" · 옆으로 밀면 나머지 대역")
            },
            // 눈금 글자를 밝게 한 김에 이 줄도 한 호 올린다. 아주 흐리면
            // 「무슨 단위로 보는 그림인가」가 화면에서 사라진다.
            color = SelahColors.TextSecondary,
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * 막대의 세로 범위를 정한다.
 *
 * 보정 상태에 따라 절대 눈금이 달라지므로 값에서 끌어온다. 고정 눈금을 쓰면
 * 보정하는 순간 막대가 전부 천장에 붙거나 바닥에 깔린다.
 */
fun rtaRange(rta: RtaView?, resolvedOnly: Boolean = true): Pair<Double, Double> {
    if (rta == null) return -60.0 to 0.0
    var top = Double.NEGATIVE_INFINITY
    for (i in rta.bandsSpl.indices) {
        if (resolvedOnly && !rta.resolved[i]) continue
        if (rta.bandsSpl[i] > top) top = rta.bandsSpl[i]
    }
    if (!top.isFinite()) return -60.0 to 0.0
    // 위로 6dB 여유를 두고 아래로 50dB. 예배당에서 읽히는 폭이다.
    val ceil = kotlin.math.ceil((top + 6.0) / 5.0) * 5.0
    return (ceil - 50.0) to ceil
}



/** 세로축이 가져가는 폭. 「110」까지 들어가면 넉넉하다. */
private val Y_AXIS_WIDTH = 26.dp

/**
 * 세로축 — **막대가 얼마인지 눈으로 읽게 한다.**
 *
 * 가로눈금과 **같은 자리에** 숫자를 놓는다. 눈금은 다섯 줄이고, 위가
 * 천장(`ceilDb`) 아래가 바닥(`floorDb`)이다.
 *
 * 눈금 자체는 캔버스가 그리므로 여기서는 숫자만 맞춰 놓는다 — 둘이 서로
 * 다른 셈으로 자리를 잡으면 반올림 때문에 어긋난다. 같은 다섯 등분을 쓴다.
 */
@Composable
private fun YAxis(floorDb: Double, ceilDb: Double, height: Dp, modifier: Modifier = Modifier) {
    Column(
        modifier.width(Y_AXIS_WIDTH).height(height),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // 위에서 아래로 — 천장부터 바닥까지 다섯 칸.
        repeat(5) { i ->
            val db = ceilDb - (ceilDb - floorDb) * i / 4.0
            Text(
                "%.0f".format(db),
                color = SelahColors.TextSecondary,
                fontSize = 8.sp,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
