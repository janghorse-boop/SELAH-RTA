package kr.joa.selahrta.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.dsp.SPECTROGRAM_CEIL_DB
import kr.joa.selahrta.dsp.SPECTROGRAM_FLOOR_DB
import kr.joa.selahrta.dsp.SpectrogramTimeline
import kr.joa.selahrta.dsp.spectrogramColor
import kr.joa.selahrta.ui.theme.SelahColors
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 스펙트로그램 — 시간(가로) × 주파수(세로) × 레벨(색).
 *
 * ## 왜 비트맵인가
 *
 * 화면에 든 칸은 720장 × 256칸 = 18만 개다. 칸마다 사각형을 그리면 초당
 * 열몇 번 18만 번을 그리는 셈이라 화면이 멈춘다. **비트맵에 픽셀로 써
 * 넣고 통째로 한 번 그린다.**
 *
 * ## 왜 고리로 쓰고 두 번 그리는가
 *
 * 매 장마다 비트맵 전체를 한 칸씩 밀면 18만 픽셀을 옮기게 된다. 대신
 * **쓸 자리만 앞으로 가게** 두고(고리), 그릴 때 「머리 뒤쪽」과 「머리
 * 앞쪽」을 두 번에 나눠 이어 붙인다. 옮기는 일이 없다.
 *
 * ## 세로축이 로그인 까닭
 *
 * 칸 자체가 이미 로그 등간격이다([kr.joa.selahrta.dsp.SpectrumAxis]).
 * 그것을 세로로 고르게 놓기만 하면 로그 축이 된다. 담당자가 보여 준 그림은
 * 선형(0~5kHz)이었는데, 그렇게 하면 예배당에서 볼 것(목소리의 기본
 * 주파수와 배음)이 아래쪽 5분의 1에 눌린다. RTA·Spectrum 도 로그라 셋의
 * 세로축이 같아야 눈이 자리를 다시 잡지 않는다.
 */
@Composable
fun SpectrogramChart(
    state: SpectrogramState,
    modifier: Modifier = Modifier,
    /** null 이면 남는 높이를 받는다. */
    chartHeight: Dp? = null,
    /** 차트 상자 안에 얹을 모드 고르개. */
    modes: (@Composable () -> Unit)? = null,
    /** 멈춤 단추 등, 왼쪽 위에 얹을 것. */
    controls: (@Composable () -> Unit)? = null,
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
            // **단추 줄은 그림 위에 얹지 않는다.**
            //
            // RTA·Spectrum 에서는 얹어도 됐다 — 왼쪽 위는 대개 빈 자리다.
            // 여기서는 아니다. 왼쪽 위가 **고역의 지금**이라 소리가 살아
            // 움직이는 자리이고, 실제로 「멈춤」 단추가 세로축의 10k·20k 를
            // 통째로 가렸다(기기에서 확인). 30dp 를 내주고 아무것도 가리지
            // 않는 편이 낫다.
            val headerHeight = if (controls != null || modes != null) CONTROL_ROW_HEIGHT else 0.dp
            val plotHeight = (maxHeight - LABEL_ROW_HEIGHT - headerHeight).coerceAtLeast(0.dp)
            val plotWidth = (maxWidth - Y_AXIS_WIDTH - 4.dp - SCALE_WIDTH).coerceAtLeast(0.dp)

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
                FrequencyAxis(plotHeight, Modifier.padding(end = 4.dp))

                Column {
                    Canvas(Modifier.width(plotWidth).height(plotHeight)) {
                        drawRect(SelahColors.Background)
                        if (state.frames > 0) drawSpectrogram(state)
                    }
                    // 가로축 — 흘러간 시간. 「0」이 지금이다.
                    Canvas(Modifier.width(plotWidth).height(LABEL_ROW_HEIGHT)) {
                        val spanMs = state.spanMs
                        if (spanMs <= 0L) return@Canvas
                        for (f in TIME_TICKS) {
                            val secondsAgo = spanMs * (1.0 - f) / 1000.0
                            val label = if (secondsAgo < 0.5) "지금" else "-${secondsAgo.roundToInt()}초"
                            val laid = measurer.measure(
                                AnnotatedString(label),
                                style = TextStyle(fontSize = 8.sp, color = SelahColors.TextPrimary),
                            )
                            val right = (size.width - laid.size.width).coerceAtLeast(0f)
                            val x = (f * size.width - laid.size.width / 2f)
                                .toFloat()
                                .coerceIn(0f, right)
                            drawText(laid, topLeft = Offset(x, 2.dp.toPx()))
                        }
                    }
                }

                ColorScale(plotHeight)
            }
            }

            if (state.frames == 0) {
                Text(
                    "측정을 시작하면 시간에 따라 쌓입니다.",
                    color = SelahColors.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

    }
}

/**
 * 고리 비트맵을 시간 순서로 이어 그린다.
 *
 * 머리(다음에 쓸 자리)의 **뒤쪽**이 오래된 것, **앞쪽**이 더 오래된 것이다.
 * 다 차기 전에는 0..head 만 쓰였으므로 한 번에 그린다.
 */
private fun DrawScope.drawSpectrogram(state: SpectrogramState) {
    val image = state.image
    val cap = state.capacity
    val head = state.head
    val n = state.frames

    if (n < cap) {
        // 아직 한 바퀴를 못 돌았다. **쓴 만큼만** 오른쪽에 붙여 그린다 —
        // 왼쪽을 비워 두어야 「아직 이만큼밖에 안 쌓였다」가 보인다.
        val w = size.width * n / cap
        drawImage(
            image = image,
            srcOffset = IntOffset(0, 0),
            srcSize = IntSize(n, image.height),
            dstOffset = IntOffset((size.width - w).roundToInt(), 0),
            dstSize = IntSize(w.roundToInt().coerceAtLeast(1), size.height.roundToInt()),
        )
        return
    }

    // 가득 찼다. head 부터가 가장 오래된 것이다.
    val older = cap - head
    if (older > 0) {
        val w = size.width * older / cap
        drawImage(
            image = image,
            srcOffset = IntOffset(head, 0),
            srcSize = IntSize(older, image.height),
            dstOffset = IntOffset(0, 0),
            dstSize = IntSize(w.roundToInt().coerceAtLeast(1), size.height.roundToInt()),
        )
    }
    if (head > 0) {
        val x = size.width * older / cap
        val w = size.width * head / cap
        drawImage(
            image = image,
            srcOffset = IntOffset(0, 0),
            srcSize = IntSize(head, image.height),
            dstOffset = IntOffset(x.roundToInt(), 0),
            dstSize = IntSize(w.roundToInt().coerceAtLeast(1), size.height.roundToInt()),
        )
    }
}

/**
 * 세로 주파수 축.
 *
 * [YAxis] 를 쓰지 않는 까닭: 저쪽은 위가 큰 값이고 다섯 등분이다. 여기서는
 * 주파수라 눈금 자리가 로그로 고르지 않다.
 */
@Composable
private fun FrequencyAxis(height: Dp, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.width(Y_AXIS_WIDTH).height(height)) {
        for (hz in FREQ_TICKS) {
            val laid = measurer.measure(
                AnnotatedString(freqLabel(hz)),
                style = TextStyle(fontSize = 8.sp, color = SelahColors.TextSecondary),
            )
            // 위가 고역이다 — 그림과 같은 방향이어야 한다.
            val y = ((1.0 - logPos(hz)) * size.height - laid.size.height / 2f)
                .toFloat()
                .coerceIn(0f, (size.height - laid.size.height).coerceAtLeast(0f))
            drawText(laid, topLeft = Offset(size.width - laid.size.width, y))
        }
    }
}

/**
 * 색 눈금 막대 — **이 그림의 유일한 범례**다.
 *
 * 색이 곧 숫자인 그림에서 눈금이 없으면, 빨간 자리가 「큰 것」인 줄은
 * 알아도 얼마나 큰지는 알 수 없다.
 */
@Composable
private fun ColorScale(height: Dp) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.width(SCALE_WIDTH).height(height)) {
        val barWidth = 8.dp.toPx()
        val steps = size.height.roundToInt().coerceAtLeast(1)
        for (i in 0 until steps) {
            // 위가 큰 값이다.
            val t = 1.0 - i.toDouble() / steps
            val db = SPECTROGRAM_FLOOR_DB + (SPECTROGRAM_CEIL_DB - SPECTROGRAM_FLOOR_DB) * t
            drawRect(
                color = Color(spectrogramColor(db, SPECTROGRAM_FLOOR_DB, SPECTROGRAM_CEIL_DB)),
                topLeft = Offset(4.dp.toPx(), i.toFloat()),
                size = Size(barWidth, 1.2f),
            )
        }
        // 눈금 숫자는 20dB 마다.
        var db = SPECTROGRAM_FLOOR_DB
        while (db <= SPECTROGRAM_CEIL_DB) {
            val t = (db - SPECTROGRAM_FLOOR_DB) / (SPECTROGRAM_CEIL_DB - SPECTROGRAM_FLOOR_DB)
            val laid = measurer.measure(
                AnnotatedString(db.toInt().toString()),
                style = TextStyle(fontSize = 8.sp, color = SelahColors.TextSecondary),
            )
            val y = ((1.0 - t) * size.height - laid.size.height / 2f)
                .toFloat()
                .coerceIn(0f, (size.height - laid.size.height).coerceAtLeast(0f))
            drawText(laid, topLeft = Offset(4.dp.toPx() + barWidth + 3.dp.toPx(), y))
            db += 20.0
        }
    }
}

/**
 * 화면이 들고 있는 스펙트로그램 — 그림과 시각.
 *
 * **비트맵을 화면이 들고 있는다.** 엔진이 들면 장마다 그림 전체를 옮겨야
 * 하고, 그릴 때 만들면 초당 열몇 번 18만 픽셀을 새로 칠하게 된다. 한 번
 * 만들어 두고 **새 장이 올 때 한 줄씩만** 고친다.
 *
 * 시각은 [SpectrogramTimeline] 이 맡는다 — 자리를 미는 셈이 그림과 같아야
 * 해서 둘이 같은 `head` 를 쓴다.
 */
class SpectrogramState(
    val columns: Int,
    val capacity: Int,
) {
    private val bitmap: Bitmap =
        Bitmap.createBitmap(capacity, columns, Bitmap.Config.ARGB_8888)

    /** 한 줄을 담아 두는 작업 배열. 장마다 새로 만들지 않는다. */
    private val line = IntArray(columns)

    private val timeline = SpectrogramTimeline(capacity)

    val image: ImageBitmap = bitmap.asImageBitmap()

    /**
     * 그림을 그리는 데 쓰는 값들. **Compose 가 보는 상태로 들고 있는다.**
     *
     * 처음에는 [timeline] 을 그대로 읽는 `get()` 이었다. 그랬더니 값은
     * 쌓이는데 **화면이 빈 채로 있었다**(기기에서 확인) — 이 클래스가 전부
     * `val` 이라 Compose 가 「안정」으로 보고, 같은 객체가 다시 들어오면
     * 차트 호출을 통째로 건너뛰기 때문이다. 밀어 넣는 것은 되는데 다시
     * 그릴 까닭이 없었던 것이다.
     *
     * 상태로 들고 있으면 그리는 쪽의 읽기가 추적되어, 새 장이 들어올 때
     * 저절로 다시 그린다.
     */
    var head: Int by mutableIntStateOf(0)
        private set

    var frames: Int by mutableIntStateOf(0)
        private set

    var spanMs: Long by mutableLongStateOf(0L)
        private set

    /**
     * 장 하나를 밀어 넣는다. [columnsDb] 는 **낮은 주파수부터**다.
     *
     * 비트맵의 세로는 **위가 고역**이라 뒤집어 쓴다 — 그림과 세로축이
     * 반대로 가면 읽는 사람이 매번 뒤집어 생각해야 한다.
     */
    fun push(columnsDb: DoubleArray, atMs: Long) {
        require(columnsDb.size == columns) {
            "장의 칸 수가 $columns 가 아니다: ${columnsDb.size}"
        }
        for (i in 0 until columns) {
            line[columns - 1 - i] =
                spectrogramColor(columnsDb[i], SPECTROGRAM_FLOOR_DB, SPECTROGRAM_CEIL_DB)
        }
        bitmap.setPixels(line, 0, 1, timeline.head, 0, 1, columns)
        timeline.push(atMs)
        publish()
    }

    fun clear() {
        bitmap.eraseColor(0)
        timeline.clear()
        publish()
    }

    private fun publish() {
        head = timeline.head
        frames = timeline.size
        spanMs = timeline.spanMs
    }
}

private val SCALE_WIDTH = 34.dp

/** 세로축에 적을 주파수. Spectrum 의 가로축과 같은 숫자다. */
private val FREQ_TICKS = doubleArrayOf(
    20.0, 50.0, 100.0, 200.0, 500.0,
    1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0,
)

/** 가로축에 눈금을 놓을 자리(0 = 가장 오래된 쪽). */
private val TIME_TICKS = doubleArrayOf(0.0, 0.25, 0.5, 0.75, 1.0)

private fun freqLabel(hz: Double): String =
    if (hz < 1000.0) hz.toInt().toString() else "${(hz / 1000.0).toInt()}k"

private val LOG_LOW_HZ = ln(20.0)
private val LOG_HIGH_HZ = ln(20_000.0)

private fun logPos(hz: Double): Double = (ln(hz) - LOG_LOW_HZ) / (LOG_HIGH_HZ - LOG_LOW_HZ)
