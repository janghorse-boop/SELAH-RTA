package kr.joa.selahrta.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.calibration.ActiveCurve
import kr.joa.selahrta.calibration.FREQUENCY_SCOPE_NOTE
import kr.joa.selahrta.dsp.CalibrationCurve
import kr.joa.selahrta.ui.theme.SelahColors
import kotlin.math.log10

/**
 * 주파수 보정(컨셉 화면 6번, 명세 8장).
 *
 * 마이크마다 주파수 응답이 다르다. 제조사가 주는 보정 파일을 넣으면 그
 * 차이를 되돌릴 수 있다 — 다만 **RTA 막대에만** 걸린다는 사실을 숨기지 않는다.
 */
@Composable
fun CurveCard(
    curve: ActiveCurve?,
    noticeKo: String?,
    canImport: Boolean,
    onPickFile: () -> Unit,
    onClear: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(SelahColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "주파수 보정 (전문)",
                color = SelahColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (curve != null) "적용됨" else "없음",
                color = if (curve != null) SelahColors.InRange else SelahColors.TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Text(
            "마이크마다 주파수 응답이 다릅니다. 제조사가 주는 보정 파일" +
                "(.cal · .frd · .txt)을 넣으면 그 차이를 되돌립니다.",
            color = SelahColors.TextMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        // 적용 범위를 숨기지 않는다. 「보정했으니 다 맞겠지」로 읽히면 안 된다.
        InfoBar(FREQUENCY_SCOPE_NOTE, tone = SelahColors.Warn)

        if (curve != null) {
            CurveGraph(curve.curve, Modifier.fillMaxWidth().height(120.dp))
            Text(
                "${curve.fileName} · 점 ${curve.pointCount}개 · " +
                    "${curve.curve.lowestHz.toInt()}Hz ~ " +
                    "${(curve.curve.highestHz / 1000).toInt()}kHz",
                color = SelahColors.TextSecondary,
                fontSize = 11.sp,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onPickFile,
                enabled = canImport,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SelahColors.Accent,
                    contentColor = Color(0xFF00201C),
                    disabledContainerColor = SelahColors.SurfaceVariant,
                    disabledContentColor = SelahColors.TextMuted,
                ),
            ) {
                Text(
                    if (curve != null) "파일 바꾸기" else "보정 파일 가져오기",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
            }
            TextButton(onClick = onClear, enabled = curve != null) {
                Text(
                    "초기화",
                    color = if (curve != null) SelahColors.TextSecondary else SelahColors.TextMuted,
                    fontSize = 13.sp,
                )
            }
        }

        if (!canImport) {
            Text(
                "측정을 한 번 시작하면 어느 기기의 보정인지 정해집니다.",
                color = SelahColors.TextMuted,
                fontSize = 11.sp,
            )
        }

        noticeKo?.let {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    it,
                    color = SelahColors.TextPrimary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismissNotice) {
                    Text("확인", color = SelahColors.Accent, fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * 보정 곡선 그래프.
 *
 * 가로는 **로그 주파수** 20Hz~20kHz — 선형으로 그리면 저역이 왼쪽 끝에
 * 뭉개져 무엇이 보정되는지 보이지 않는다. 세로는 곡선이 실제로 쓰는 범위에
 * 맞춘다(최소 ±5dB).
 */
@Composable
private fun CurveGraph(curve: CalibrationCurve, modifier: Modifier = Modifier) {
    val span = maxOf(5.0, kotlin.math.ceil(curve.maxAbsGainDb))
    Box(
        modifier
            .background(SelahColors.Background, RoundedCornerShape(8.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(8.dp))
            .padding(6.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().height(108.dp)) {
            val loHz = 20.0
            val hiHz = 20_000.0
            val logLo = log10(loHz)
            val logHi = log10(hiHz)

            fun x(hz: Double) = ((log10(hz) - logLo) / (logHi - logLo) * size.width).toFloat()
            fun y(db: Double) = (size.height * (0.5 - db / (2 * span))).toFloat()

            // 0dB 선. 여기가 「보정 없음」이다.
            drawLine(
                SelahColors.Outline,
                Offset(0f, y(0.0)),
                Offset(size.width, y(0.0)),
                strokeWidth = 1.5f,
            )
            // 옥타브 눈금
            listOf(100.0, 1000.0, 10000.0).forEach { hz ->
                drawLine(
                    SelahColors.Outline.copy(alpha = 0.5f),
                    Offset(x(hz), 0f),
                    Offset(x(hz), size.height),
                    strokeWidth = 1f,
                )
            }

            // 곡선. 화면 폭만큼 촘촘히 그린다.
            var prev: Offset? = null
            val steps = size.width.toInt().coerceIn(2, 400)
            for (i in 0..steps) {
                val t = i.toDouble() / steps
                val hz = Math.pow(10.0, logLo + t * (logHi - logLo))
                val p = Offset(x(hz), y(curve.gainDbAt(hz)))
                prev?.let { drawLine(SelahColors.Accent, it, p, strokeWidth = 2.5f) }
                prev = p
            }

            // 곡선이 실제로 덮는 구간을 밝게 표시한다. 그 밖은 끝점을
            // 늘여 쓴 것이라 근거가 약하다.
            listOf(curve.lowestHz, curve.highestHz).forEach { hz ->
                if (hz in loHz..hiHz) {
                    drawLine(
                        SelahColors.TextMuted,
                        Offset(x(hz), 0f),
                        Offset(x(hz), size.height),
                        strokeWidth = 1f,
                    )
                }
            }
        }

        Text(
            "±${span.toInt()}dB · 20Hz~20kHz · 세로선 밖은 끝점 값",
            color = SelahColors.TextMuted,
            fontSize = 8.sp,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}
