package kr.joa.selahrta.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.dsp.FeedbackCandidate
import kr.joa.selahrta.ui.SpectrumView
import kr.joa.selahrta.ui.theme.SelahColors
import kotlin.math.ln

/**
 * 연속 스펙트럼 차트(검토안 3장의 Spectrum).
 *
 * ## RTA 차트와 무엇이 다른가
 *
 * [BandMeter] 는 31칸을 **균등하게** 놓는다 — 밴드가 곧 칸이라 그래도 된다.
 * 여기서는 가로가 **로그 주파수 축**이라 칸마다 주파수 폭이 다르다. 그래서
 * 눈금도 표식도 자리를 [logPosition] 으로 셈한다.
 *
 * **옆으로 밀지 않는다.** 20Hz~20kHz 가 로그 축에서는 한 화면에 다 들어온다.
 * RTA 는 세로 화면에서 31칸에 글자를 넣으려고 밀어야 했지만, 여기는 선
 * 하나라 좁아도 모양이 읽힌다.
 *
 * ## 세로축은 [BandMeter] 의 것을 그대로 쓴다
 *
 * 두 화면을 번갈아 보는 자리라, 축이 서로 다른 폭·다른 자리에 있으면 눈이
 * 매번 다시 자리를 잡아야 한다. 같은 [YAxis] 를 같은 [Y_AXIS_WIDTH] 로 쓴다.
 */
@Composable
fun SpectrumChart(
    spectrum: SpectrumView?,
    floorDb: Double,
    ceilDb: Double,
    modifier: Modifier = Modifier,
    /** null 이면 남는 높이를 받는다([BandMeter] 와 같은 규칙). */
    chartHeight: Dp? = null,
    feedback: List<FeedbackCandidate> = emptyList(),
    /** 차트 상자 안에 얹을 모드 고르개. */
    modes: (@Composable () -> Unit)? = null,
    /** 고르개 **왼쪽**에 놓을 단추(멈춤 등). null 이면 안 그린다. */
    controls: (@Composable () -> Unit)? = null,
    /** 세로축을 누르면 부른다(고정↔자동). */
    onAxisTap: (() -> Unit)? = null,
) {
    val measurer = rememberTextMeasurer()
    Column(modifier) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .then(
                    if (chartHeight != null) {
                        Modifier.height(chartHeight)
                    } else {
                        Modifier.weight(1f)
                    },
                )
                .background(SelahColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
                .padding(8.dp),
        ) {
            val headerHeight =
                if (modes != null || controls != null) CONTROL_ROW_HEIGHT else 0.dp
            val plotHeight = (maxHeight - LABEL_ROW_HEIGHT - headerHeight).coerceAtLeast(0.dp)
            val plotWidth = (maxWidth - Y_AXIS_WIDTH - 4.dp).coerceAtLeast(0.dp)

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
                YAxis(floorDb, ceilDb, plotHeight, Modifier.padding(end = 4.dp), onAxisTap)
                Column {
                    Canvas(Modifier.width(plotWidth).height(plotHeight)) {
                        val span = (ceilDb - floorDb).coerceAtLeast(1.0)

                        // 가로 눈금 — RTA 와 **같은 dB 자리**에 긋는다.
                        for (db in gridLinesDb(floorDb, ceilDb)) {
                            val y = (((ceilDb - db) / span) * size.height).toFloat()
                            drawLine(
                                SelahColors.Outline,
                                Offset(0f, y),
                                Offset(size.width, y),
                                strokeWidth = 1f,
                            )
                        }

                        // 세로 눈금 — 로그 축이라 **자리가 고르지 않다.** 이것이
                        // 없으면 봉우리가 몇 Hz 쯤인지 가늠할 기준이 없다.
                        for (hz in AXIS_HZ) {
                            val x = (logPosition(hz) * size.width).toFloat()
                            drawLine(
                                SelahColors.Outline,
                                Offset(x, 0f),
                                Offset(x, size.height),
                                strokeWidth = 1f,
                            )
                        }

                        if (spectrum == null) return@Canvas

                        fun yOf(db: Double): Float {
                            val frac = ((db - floorDb) / span).coerceIn(0.0, 1.0)
                            return (size.height * (1.0 - frac)).toFloat()
                        }

                        val last = (spectrum.columnsSpl.size - 1).coerceAtLeast(1)
                        fun xOf(i: Int): Float = size.width * i / last.toFloat()

                        // **Peak Hold 를 먼저 그린다.** 지금 값 위에 그리면
                        // 정작 지금 소리가 가려진다.
                        val hold = Path()
                        for (i in spectrum.holdSpl.indices) {
                            val x = xOf(i)
                            val y = yOf(spectrum.holdSpl[i])
                            if (i == 0) hold.moveTo(x, y) else hold.lineTo(x, y)
                        }
                        drawPath(hold, SelahColors.TextMuted, style = Stroke(width = 1.5f))

                        // 지금 값. 선 아래를 옅게 채운다 — 면적으로 보면
                        // 대역 균형이 눈에 먼저 들어온다.
                        val line = Path()
                        for (i in spectrum.columnsSpl.indices) {
                            val x = xOf(i)
                            val y = yOf(spectrum.columnsSpl[i])
                            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
                        }
                        val fill = Path().apply {
                            addPath(line)
                            lineTo(size.width, size.height)
                            lineTo(0f, size.height)
                            close()
                        }
                        drawPath(fill, SelahColors.Accent.copy(alpha = 0.18f))
                        drawPath(line, SelahColors.Accent, style = Stroke(width = 2f))

                        // **하울링 후보는 RTA 와 같은 색으로** 찍는다. 두 화면이
                        // 같은 것을 다른 색으로 가리키면 옮겨 볼 때마다 다시
                        // 읽어야 한다.
                        val t = 5.dp.toPx()
                        val gap = 4.dp.toPx()
                        // 선과 삼각형은 **모두** 긋는다. 어디인지는 다 보여야 한다.
                        val marks = feedback
                            .map { it to logPosition(it.hz) }
                            .filter { it.second in 0.0..1.0 }
                            .sortedBy { it.second }
                        for ((c, p) in marks) {
                            val x = (p * size.width).toFloat()
                            val tone = feedbackTone(c.state)
                            drawLine(
                                tone,
                                Offset(x, 0f),
                                Offset(x, size.height),
                                strokeWidth = 2.dp.toPx(),
                            )
                            drawPath(
                                Path().apply {
                                    moveTo(x - t, 0f)
                                    lineTo(x + t, 0f)
                                    lineTo(x, t * 1.6f)
                                    close()
                                },
                                color = tone,
                            )
                        }

                        // **글자는 자리가 남을 때만 적는다.**
                        //
                        // 로그 축에서는 고역 후보들이 바짝 붙는다. 다 적었더니
                        // 「1.56kHz」와 「1.88kHz」가 겹쳐 `1.5k1.88kHz` 로 보였다
                        // (기기에서 확인) — **겹친 숫자는 없는 숫자보다 나쁘다.**
                        // 읽히지 않으면서 틀린 값으로 읽힐 수 있기 때문이다.
                        //
                        // 왼쪽부터 놓으며 앞 글자의 오른쪽 끝을 넘겨야만 적는다.
                        // 못 적은 것도 선은 그대로 있으니 자리는 보인다.
                        var lastRight = Float.NEGATIVE_INFINITY
                        for ((c, p) in marks) {
                            val x = (p * size.width).toFloat()
                            val laid = measurer.measure(
                                AnnotatedString(formatHz(c.hz) + hzUnit(c.hz)),
                                style = TextStyle(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = feedbackTone(c.state),
                                ),
                            )
                            val lx = if (x + gap + laid.size.width <= size.width) {
                                x + gap
                            } else {
                                (x - gap - laid.size.width).coerceAtLeast(0f)
                            }
                            if (lx < lastRight + gap) continue
                            drawText(laid, topLeft = Offset(lx, t * 1.6f))
                            lastRight = lx + laid.size.width
                        }

                        // **가장 큰 봉우리를 그림 위에 짚는다.**
                        //
                        // 이 화면의 값어치가 「그 대역 안에서 정확히 몇 Hz 인가」
                        // 이므로, 숫자를 아래에만 적어 두면 어느 봉우리를 말하는
                        // 것인지 눈으로 이어야 한다.
                        val topHz = spectrum.topHz
                        val topSpl = spectrum.topSpl
                        if (topHz != null && topSpl != null) {
                            val p = logPosition(topHz)
                            if (p in 0.0..1.0) {
                                val x = (p * size.width).toFloat()
                                val y = yOf(topSpl)
                                drawCircle(SelahColors.TextPrimary, 3.dp.toPx(), Offset(x, y))
                                // **숫자를 봉우리 옆에 붙인다.** 아래 설명 줄에
                                // 적어 두면 어느 봉우리를 말하는지 눈으로
                                // 이어야 하는데, 봉우리는 여러 개다.
                                val laid = measurer.measure(
                                    AnnotatedString(
                                        formatHz(topHz) + hzUnit(topHz) +
                                            "  " + "%.0f".format(topSpl) + "dB",
                                    ),
                                    style = TextStyle(
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SelahColors.TextPrimary,
                                    ),
                                )
                                val gap = 6.dp.toPx()
                                val lx = if (x + gap + laid.size.width <= size.width) {
                                    x + gap
                                } else {
                                    (x - gap - laid.size.width).coerceAtLeast(0f)
                                }
                                // **되도록 점 아래에 적는다.** 위쪽 띠는
                                // 하울링 후보의 주파수 글자가 쓰는 자리라,
                                // 봉우리가 높을 때 둘이 겹쳤다(기기에서 확인).
                                // 봉우리 아래는 곡선이 가파르게 떨어져 대개
                                // 비어 있다.
                                val ly = if (y + gap + laid.size.height <= size.height) {
                                    y + gap
                                } else {
                                    (y - laid.size.height - gap).coerceAtLeast(0f)
                                }
                                drawText(laid, topLeft = Offset(lx, ly))
                            }
                        }
                    }

                    // 가로 눈금 글자.
                    //
                    // **캔버스로 그린다.** 로그 축이라 자리가 고르지 않아,
                    // 칸을 나눠 넣는 방식([BandMeter] 가 쓰는 방식)으로는 선과
                    // 글자가 어긋난다. 같은 [logPosition] 을 쓰면 어긋날 수 없다.
                    Canvas(Modifier.width(plotWidth).height(LABEL_ROW_HEIGHT)) {
                        for (hz in AXIS_HZ) {
                            val laid = measurer.measure(
                                AnnotatedString(axisLabel(hz)),
                                style = TextStyle(
                                    fontSize = 8.sp,
                                    color = SelahColors.TextPrimary,
                                ),
                            )
                            // 눈금선 가운데에 놓되, 양 끝은 상자 안으로 당긴다.
                            val right = (size.width - laid.size.width).coerceAtLeast(0f)
                            val x = ((logPosition(hz) * size.width).toFloat() -
                                laid.size.width / 2f).coerceIn(0f, right)
                            drawText(laid, topLeft = Offset(x, 2.dp.toPx()))
                        }
                    }
                }
            }

            }

            if (spectrum == null) {
                Text(
                    "측정을 시작하면 스펙트럼이 나타납니다.",
                    color = SelahColors.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

    }
}

/**
 * 가로 눈금을 놓을 주파수.
 *
 * 1·2·5 로 끊는다 — 로그 축에서 고르게 벌어지면서 귀에 익은 숫자다.
 * 더 촘촘히 넣으면 8sp 글자가 서로 겹친다.
 *
 * **[formatHz] 를 쓰지 않는다.** 그것은 단위를 따로 붙이는 짝
 * ([hzUnit])이 있어서, 혼자 쓰면 1kHz 가 `1.00` 으로 찍힌다 — 눈금에
 * `1.00 · 2.00 · 5.00` 이 늘어서면 무슨 단위인지 알 수 없다(기기에서 확인).
 */
private fun axisLabel(hz: Double): String =
    if (hz < 1000.0) hz.toInt().toString() else "${(hz / 1000.0).toInt()}k"

private val AXIS_HZ = doubleArrayOf(
    20.0, 50.0, 100.0, 200.0, 500.0,
    1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0,
)

/**
 * 주파수를 화면 가로 자리(0..1)로.
 * [kr.joa.selahrta.dsp.SpectrumAxis.position] 과 **같은 셈**이다.
 *
 * 축 객체를 들고 다니지 않으려고 여기 한 번 더 적었다. 끝 주파수는 양쪽
 * 모두 20Hz·20kHz 로 못박혀 있고, 그 값이 바뀔 일은 없다.
 */
private fun logPosition(hz: Double): Double {
    if (hz <= 0.0) return -1.0
    return (ln(hz) - LOG_LOW) / (LOG_HIGH - LOG_LOW)
}

private val LOG_LOW = ln(20.0)
private val LOG_HIGH = ln(20_000.0)

/**
 * **Spectrum 도 RTA 와 같은 고정 축을 쓴다**(2026-09-26 담당자 지시).
 *
 * 두 차트를 번갈아 보는 화면이라 축이 다르면 눈이 매번 자리를 다시 잡아야
 * 한다. 고정하는 까닭은 [RTA_RANGE] 의 머리말 참고.
 *
 * Spectrum 은 칸마다 최대 하나만 집어 바닥이 RTA 보다 낮게 깔리므로 아래쪽이
 * 더 비어 보인다. 그래도 **이 화면의 값어치는 봉우리 옆에 적히는 숫자**에
 * 있지 바닥 모양에 있지 않다.
 */

/** 지금 가장 큰 칸. 잰 것이 없으면 null. */
fun spectrumTopSpl(s: SpectrumView?): Double? {
    if (s == null) return null
    var top = Double.NEGATIVE_INFINITY
    for (v in s.columnsSpl) if (v > top) top = v
    return top.takeIf { it.isFinite() }
}
