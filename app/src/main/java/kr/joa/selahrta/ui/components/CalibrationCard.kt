package kr.joa.selahrta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.calibration.PLAUSIBLE_REFERENCE_RANGE
import kr.joa.selahrta.calibration.computeOffset
import kr.joa.selahrta.domain.CalibrationState
import kr.joa.selahrta.ui.CaptureUiState
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * 간편 보정(명세 8장, 컨셉 화면 5번).
 *
 * 기준 소음계가 가리키는 값을 적으면 그 차이를 보정값으로 삼는다.
 * **먼저 계산해 보여 주고, 사람이 확인한 뒤에 저장한다** — 잘못 적은 값이
 * 조용히 저장되면 그 뒤의 모든 숫자가 틀린 채로 그럴듯해 보인다.
 */
@Composable
fun CalibrationCard(
    capture: CaptureUiState,
    onSave: (Double) -> Unit,
    onClear: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    val measured = capture.meter.currentDbfs
    val reference = input.trim().toDoubleOrNull()
    val offset = if (measured != null && reference != null) {
        computeOffset(reference, measured)
    } else {
        null
    }
    val referenceLooksOk = reference != null && reference in PLAUSIBLE_REFERENCE_RANGE

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
            Text("간편 보정", color = SelahColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                capture.calibration.state.labelKo,
                color = when (capture.calibration.state) {
                    CalibrationState.Uncalibrated -> SelahColors.Warn
                    else -> SelahColors.InRange
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (measured == null) {
            Text(
                "측정을 시작하면 보정할 수 있습니다. 「측정」 화면에서 측정 시작을 누르십시오.",
                color = SelahColors.TextMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            return@Column
        }

        Text(
            "기준 소음계를 이 폰 마이크 바로 옆에 두고, 소리가 안정된 상태에서 " +
                "소음계가 가리키는 값을 적으십시오.",
            color = SelahColors.TextMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        // **가중치가 맞아야 한다.** 여기서 읽는 값에는 지금 고른 가중이
        // 이미 걸려 있다. 소음계가 dBC 인데 앱이 dBA 면, 그 소리의
        // A-C 차이가 보정값에 통째로 섞여 들어가 이후 모든 값이 그만큼
        // 틀어진다. 1kHz 순음이면 세 가중이 모두 0dB 이라 안전하다.
        InfoBar(
            "소음계를 ${capture.meterSettings.weighting.unitSuffix} 로 맞추고 재십시오. " +
                "가중치가 다르면 그 차이가 보정값에 섞여 들어갑니다. " +
                "1kHz 순음(교정기)으로 하면 가중치와 무관합니다.",
            tone = SelahColors.Warn,
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("지금 읽는 값", color = SelahColors.TextMuted, fontSize = 11.sp)
            Text("%.1f dBFS".format(measured), color = SelahColors.TextSecondary, fontSize = 11.sp)
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("기준 소음계 값 (dB)", fontSize = 12.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = SelahColors.TextPrimary,
                unfocusedTextColor = SelahColors.TextPrimary,
                focusedBorderColor = SelahColors.Accent,
                unfocusedBorderColor = SelahColors.Outline,
                focusedLabelColor = SelahColors.Accent,
                unfocusedLabelColor = SelahColors.TextMuted,
                cursorColor = SelahColors.Accent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        // 저장하기 **전에** 계산 결과를 보여 준다. 숫자를 보고 나서
        // 저장할지 정하는 것과, 저장한 뒤에 확인하는 것은 다르다.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("계산된 보정값", color = SelahColors.TextMuted, fontSize = 11.sp)
            Text(
                offset?.let { "%+.1f dB".format(it) } ?: NO_VALUE,
                color = if (offset != null) SelahColors.Accent else SelahColors.TextMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        if (input.isNotBlank() && !referenceLooksOk) {
            Text(
                "${PLAUSIBLE_REFERENCE_RANGE.start.toInt()} ~ " +
                    "${PLAUSIBLE_REFERENCE_RANGE.endInclusive.toInt()} dB 사이의 숫자를 적으십시오.",
                color = SelahColors.Warn,
                fontSize = 11.sp,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { reference?.let(onSave) },
                enabled = referenceLooksOk,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SelahColors.Accent,
                    contentColor = Color(0xFF00201C),
                    disabledContainerColor = SelahColors.SurfaceVariant,
                    disabledContentColor = SelahColors.TextMuted,
                ),
            ) {
                Text("보정값 저장", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            TextButton(
                onClick = { input = ""; onClear() },
                enabled = capture.calibration.saved != null,
            ) {
                Text(
                    "초기화",
                    color = if (capture.calibration.saved != null) {
                        SelahColors.TextSecondary
                    } else {
                        SelahColors.TextMuted
                    },
                    fontSize = 13.sp,
                )
            }
        }

        capture.calibration.saved?.let {
            Text(
                "저장됨: %+.1f dB (기준 %.1f dB 에 맞춤)".format(it.offsetDb, it.referenceDb),
                color = SelahColors.TextMuted,
                fontSize = 11.sp,
            )
        }

        capture.calibrationNoticeKo?.let {
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
