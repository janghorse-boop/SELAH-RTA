package kr.joa.selahrta.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kr.joa.selahrta.dsp.RoomResponse
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * 응답 곡선(FR).
 *
 * ## 왜 막대가 아니라 선인가
 *
 * RTA 는 **지금 얼마나 큰가**라 막대가 맞다 — 바닥에서 올라온 높이가 곧
 * 레벨이다. 응답은 **0dB 에서 얼마나 벗어났는가**라 0 이 가운데에 있고
 * 위아래로 간다. 그걸 막대로 그리면 「0dB 인 밴드는 소리가 없다」로
 * 읽힌다.
 *
 * ## 못 믿는 밴드는 **선을 끊는다**
 *
 * 배경에 묻힌 밴드를 이어 그리면, 잡음이 만든 자리가 방의 응답처럼
 * 보인다. 이어 그리지 않고 점만 흐리게 찍어 「여기는 모른다」를 눈에
 * 보이게 한다 — 흐린 색 하나로 알리지 않는 까닭은 색을 못 보는 사람에게
 * 아무 말도 안 되기 때문이다(명세 11장).
 */
@Composable
fun ResponseChart(
    response: RoomResponse,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 220.dp,
    /** 밴드 한 칸의 최소 너비. 화면이 좁으면 늘이고 옆으로 민다. */
    minSlotWidth: Dp = 0.dp,
) {
    val measurer = rememberTextMeasurer()
    Column(modifier) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .then(if (chartHeight > 0.dp) Modifier.height(chartHeight) else Modifier)
                .background(SelahColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
                .padding(8.dp),
        ) {
            val n = ThirdOctave.BAND_COUNT
            val chartWidth = maxOf(maxWidth, minSlotWidth * n)
            val slotWidth = chartWidth / n
            val labelEvery = slotWidth >= 20.dp
            val barsHeight = (maxHeight - 18.dp).coerceAtLeast(0.dp)

            // **눈금은 값에서 끌어온다.** ±12dB 로 못박아 두면 더 심하게
            // 기운 방에서 곡선이 천장에 붙어 모양이 사라진다.
            val span = chartSpanDb(response)

            Column {
                Canvas(Modifier.width(chartWidth).height(barsHeight)) {
                    val h = size.height
                    fun y(db: Double): Float =
                        (h / 2f - (db / span * (h / 2f))).toFloat().coerceIn(0f, h)

                    // 가로 눈금 — 0dB 과 ±span/2.
                    for (g in listOf(-span, -span / 2, 0.0, span / 2, span)) {
                        val gy = y(g)
                        drawLine(
                            color = if (g == 0.0) SelahColors.TextSecondary else SelahColors.Outline,
                            start = Offset(0f, gy),
                            end = Offset(size.width, gy),
                            strokeWidth = if (g == 0.0) 2f else 1f,
                            pathEffect = if (g == 0.0) {
                                null
                            } else {
                                PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                            },
                        )
                        val laid = measurer.measure(
                            AnnotatedString("%+.0f".format(g)),
                            style = TextStyle(fontSize = 8.sp, color = SelahColors.TextMuted),
                        )
                        drawText(laid, topLeft = Offset(2f, gy - laid.size.height))
                    }

                    val slot = size.width / n
                    // **이어진 구간마다 따로 그린다.** 못 믿는 밴드를 건너
                    // 이으면 없는 응답을 그려 넣는 셈이 된다.
                    var path: Path? = null
                    for (i in 0 until n) {
                        val cx = slot * (i + 0.5f)
                        val cy = y(response.relativeDb[i])
                        if (!response.usable[i]) {
                            path?.let {
                                drawPath(it, SelahColors.Accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                            }
                            path = null
                            // 모른다는 표시 — 작은 빈 동그라미.
                            drawCircle(
                                color = SelahColors.TextMuted,
                                radius = 2.5.dp.toPx(),
                                center = Offset(cx, cy),
                                style = Stroke(width = 1.dp.toPx()),
                            )
                            continue
                        }
                        if (path == null) {
                            path = Path().apply { moveTo(cx, cy) }
                        } else {
                            path.lineTo(cx, cy)
                        }
                        drawCircle(SelahColors.Accent, 2.5.dp.toPx(), Offset(cx, cy))
                    }
                    path?.let {
                        drawPath(it, SelahColors.Accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                    }
                }

                Row(Modifier.width(chartWidth).height(18.dp)) {
                    repeat(n) { b ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (labelEvery || b in LABEL_BANDS) {
                                Text(
                                    ThirdOctave.label(b),
                                    // 눈금은 흐리게 두지 않는다 — 몇 Hz 인지
                                    // 모르면 곡선은 그냥 무늬다.
                                    color = SelahColors.TextPrimary,
                                    fontSize = 8.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.wrapContentWidth(unbounded = true),
                                )
                            }
                        }
                    }
                }
            }
        }
        Text(
            "주파수 (Hz) · 세로 ±${"%.0f".format(chartSpanDb(response))} dB · " +
                "${response.referenceLowHz.toInt()}~${response.referenceHighHz.toInt()}Hz 를 0dB 으로 맞춤" +
                if (response.usable.any { !it }) " · 빈 동그라미는 배경에 묻혀 모르는 자리" else "",
            color = SelahColors.TextSecondary,
            fontSize = 9.sp,
            lineHeight = 13.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * 세로 눈금의 반폭(dB).
 *
 * 쓸 만한 밴드의 가장 큰 벗어남에 맞추되, **6dB 아래로는 안 좁힌다** —
 * 아주 평평한 방에서 눈금을 ±1dB 로 좁히면 잡음 수준의 요철이 큰 산처럼
 * 보인다. 「평평하다」는 것도 보여야 하는 사실이다.
 */
private fun chartSpanDb(r: RoomResponse): Double {
    val worst = r.relativeDb.indices
        .filter { r.usable[it] }
        .maxOfOrNull { abs(r.relativeDb[it]) } ?: 6.0
    return (ceil(worst / 3.0) * 3.0).coerceIn(6.0, 30.0)
}

/** 좁을 때 쓸 눈금. 옥타브마다 하나씩. */
private val LABEL_BANDS = listOf(0, 3, 6, 9, 12, 15, 18, 21, 24, 27, 30)
