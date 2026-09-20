package kr.joa.selahrta.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.ui.RtaView
import kr.joa.selahrta.ui.theme.SelahColors

/** 눈금으로 쓸 주파수. 옥타브마다 하나씩이면 폰 너비에서 겹치지 않는다. */
private val LABEL_BANDS = listOf(0, 3, 6, 9, 12, 15, 18, 21, 24, 27, 30)

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
) {
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(SelahColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
                .padding(8.dp),
        ) {
            Canvas(Modifier.fillMaxWidth().height(184.dp)) {
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

                for (i in 0 until n) {
                    val x = slot * i + (slot - barW) / 2f
                    val v = rta.bandsSpl[i]
                    val frac = ((v - floorDb) / span).coerceIn(0.0, 1.0).toFloat()
                    val h = size.height * frac

                    if (h > 0.5f) {
                        drawRect(
                            color = if (rta.resolved[i]) {
                                SelahColors.Accent
                            } else {
                                // 분해 못 하는 밴드는 흐리게. 값이 아니라 새어 온 것이다.
                                SelahColors.Accent.copy(alpha = 0.25f)
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
            }

            if (rta == null) {
                Text(
                    "측정을 시작하면 막대가 나타납니다.",
                    color = SelahColors.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 80.dp).fillMaxWidth(),
                )
            }
        }

        // 눈금을 막대와 같은 방식으로 나눈다. 막대도 31칸 균등이라
        // 여기서도 31칸을 만들고 그 중 몇 칸에만 글자를 넣으면 자리가 맞는다.
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            repeat(ThirdOctave.BAND_COUNT) { b ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (b in LABEL_BANDS) {
                        Text(
                            ThirdOctave.label(b),
                            color = SelahColors.TextMuted,
                            fontSize = 8.sp,
                            maxLines = 1,
                            softWrap = false,
                            // 칸 하나는 31분의 1(약 28px)뿐이라 「630」이 「63」으로
                            // 잘린다. 하필 630Hz 자리에 63 이 찍혀 적극적으로
                            // 오해를 부른다. 칸 밖으로 넘치게 두고 가운데 정렬한다.
                            modifier = Modifier.wrapContentWidth(unbounded = true),
                        )
                    }
                }
            }
        }
        Text(
            "주파수 (Hz) · 세로 ${floorDb.toInt()} ~ ${ceilDb.toInt()} dB",
            color = SelahColors.TextMuted,
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

