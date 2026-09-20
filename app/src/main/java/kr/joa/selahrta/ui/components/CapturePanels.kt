package kr.joa.selahrta.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.audio.CaptureDiagnostics
import kr.joa.selahrta.audio.OpenedFormat
import kr.joa.selahrta.ui.theme.SelahColors
import kotlin.math.log10

/**
 * 입력 레벨 막대.
 *
 * **이것은 음압이 아니다.** 마이크에 들어온 신호가 풀스케일의 몇 퍼센트인지를
 * 보여줄 뿐이라 dBFS 조차 아니다. 소리를 내면 움직이는지 눈으로 확인하는
 * 용도이며, 그렇게 이름 붙인다. SPL 은 보정을 거쳐야 나온다(Phase 3).
 */
@Composable
fun InputLevelBar(peakAbs: Double, modifier: Modifier = Modifier) {
    // 선형 비율을 그대로 그리면 사람 말소리(0.01~0.1)가 거의 안 보인다.
    // 귀가 로그로 듣는 것과 같은 이유로 여기서도 로그 축을 쓴다.
    // -60dBFS 를 바닥으로 잡는다.
    val db = if (peakAbs > 0) 20.0 * log10(peakAbs) else -120.0
    val fraction = ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
    val clipping = peakAbs >= 0.999

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("입력 레벨", color = SelahColors.TextSecondary, fontSize = 11.sp)
            Text(
                if (clipping) "잘림!" else "${"%.1f".format(db)} dBFS",
                color = if (clipping) SelahColors.High else SelahColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = if (clipping) FontWeight.Bold else FontWeight.Normal,
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(SelahColors.SurfaceVariant, RoundedCornerShape(5.dp)),
        ) {
            if (fraction > 0f) {
                drawRoundRect(
                    color = if (clipping) SelahColors.High else SelahColors.InRange,
                    topLeft = Offset.Zero,
                    size = Size(size.width * fraction, size.height),
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            }
        }
        Text(
            "음압(dB SPL)이 아닙니다. 마이크에 소리가 들어오는지 보는 눈금입니다.",
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
        )
    }
}

/**
 * 캡처 진단(명세 16장).
 *
 * 측정값이 아니라 **캡처가 건강한지**를 말한다. 이것이 흔들리면 그 위에
 * 올린 숫자는 전부 못 믿으므로, 숨기지 않고 보여 준다.
 */
@Composable
fun DiagnosticsPanel(
    opened: OpenedFormat,
    diag: CaptureDiagnostics,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(SelahColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            "캡처 진단",
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )

        DiagRow("입력 기기", opened.deviceLabel)
        DiagRow("입력 경로", opened.audioSource.labelKo, warn = !opened.audioSource.trustworthy)
        DiagRow(
            "UNPROCESSED 지원",
            if (opened.unprocessedSupported) "예" else "아니요",
            warn = !opened.unprocessedSupported,
        )
        // 신호 가공이 살아 있으면 절대값을 믿을 수 없다. 셋을 각각 보여준다 —
        // 「대체로 괜찮다」가 아니라 무엇이 남았는지가 중요하다.
        DiagRow(
            "자동 게인(AGC)",
            opened.effects.agc.statusKo,
            warn = !opened.effects.agc.disabled,
        )
        DiagRow(
            "잡음 억제(NS)",
            opened.effects.ns.statusKo,
            warn = !opened.effects.ns.disabled,
        )
        DiagRow(
            "반향 제거(AEC)",
            opened.effects.aec.statusKo,
            warn = !opened.effects.aec.disabled,
        )
        DiagRow("샘플레이트", "${opened.sampleRate} Hz", warn = opened.sampleRate != 48_000)
        DiagRow("샘플 형식", opened.encoding.bitsLabel)
        DiagRow("버퍼", "${opened.bufferSizeBytes} 바이트")
        DiagRow("받은 덩어리 / 프레임", "${diag.blocks} / ${diag.frames}")
        DiagRow(
            "처리 시간 / 덩어리 길이",
            "%.2f ms / %.1f ms".format(diag.lastProcessMs, diag.blockDurationMs),
            warn = !diag.processingHeadroom,
        )
        DiagRow(
            "오디오 지연",
            "%.0f ms".format(diag.audioLagMs),
            warn = !diag.keepingUp,
        )
        DiagRow("읽기 오류", "${diag.readErrors}", warn = diag.readErrors > 0)
        DiagRow("잘린 덩어리", "${diag.clippedBlocks}", warn = diag.clippedBlocks > 0)
    }
}

@Composable
private fun DiagRow(label: String, value: String, warn: Boolean = false) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = SelahColors.TextMuted, fontSize = 11.sp)
        Text(
            value,
            color = if (warn) SelahColors.Warn else SelahColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = if (warn) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
