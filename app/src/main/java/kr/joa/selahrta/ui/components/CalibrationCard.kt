package kr.joa.selahrta.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.style.TextAlign
import kr.joa.selahrta.calibration.GlobalCalibration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.calibration.CalibrationSource
import kr.joa.selahrta.calibration.CalibratorLevel
import kr.joa.selahrta.calibration.PLAUSIBLE_REFERENCE_RANGE
import kr.joa.selahrta.calibration.computeOffset
import kr.joa.selahrta.domain.CalibrationState
import kr.joa.selahrta.dsp.CalibratorToneCheck
import kr.joa.selahrta.dsp.checkCalibratorTone
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
    onSave: (Double, CalibrationSource) -> Unit,
    onClear: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val measured = capture.meter.currentDbfs
    val reference = input.trim().toDoubleOrNull()
    val offset = if (measured != null && reference != null) {
        computeOffset(reference, measured)
    } else {
        null
    }
    val referenceLooksOk = reference != null && reference in PLAUSIBLE_REFERENCE_RANGE

    // **교정기 순음이 실제로 들어오는가.** 밴드 레벨의 모양만 보므로
    // 보정값이 걸렸든 아니든 결과는 같다(솟은 정도는 차이라서 오프셋이
    // 상쇄된다). 아직 첫 FFT 가 안 찼으면 판단하지 않는다 — 모르는 것을
    // 「아니다」로 말하지 않는다.
    val tone: CalibratorToneCheck? = capture.rta?.let { checkCalibratorTone(it.bandsSpl) }

    // 엔진이 **실제로** 곡선으로 계산하고 있는가. 「파일을 넣었는가」가
    // 아니다 — 꺼 두었거나 아직 안 걸렸으면 숫자에 안 들어 있다.
    val curveOn = capture.rta?.curveApplied == true

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

        // **음압 교정기가 있으면 그쪽이 낫다.**
        //
        // 교정기는 제 소리를 내는 기구라 절대값의 근거가 된다. 소음계에
        // 맞추는 것은 그 소음계가 맞다는 가정 위에 서고, 두 기기의 가중이
        // 다르면 그 차이까지 섞여 들어간다. 게다가 1kHz 에서는 A·C 가중이
        // 0dB 이라 교정기로 맞춘 값은 가중치와 무관하다.
        Text(
            "음압 교정기가 있으면 아래에서 고르십시오. 마이크에 끼우고 켠 뒤 " +
                "누르면 됩니다 — 기구에 적힌 값(보통 94 또는 114dB)을 고르십시오.",
            color = SelahColors.TextMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CalibratorLevel.entries.forEach { level ->
                CalibratorButton(
                    level = level,
                    tone = tone,
                    onClick = { onSave(level.db, CalibrationSource.Calibrator) },
                )
            }
        }

        when {
            tone == null -> Text(
                "소리를 읽기 시작하면 교정기 버튼이 열립니다.",
                color = SelahColors.TextMuted,
                fontSize = 11.sp,
            )

            // **순음이 없을 때는 경고로 띄우지 않는다**(2026-09-25 담당자
            // 지적: 「가장 큰 소리가 … 텍스트가 계속 변경됩니다」).
            //
            // 교정기를 안 끼운 상태는 **잘못된 상태가 아니라 아직 시작하지
            // 않은 상태**다. 그것을 주황 경고 상자로 띄우면 뭔가 고장 난
            // 것처럼 보이고, 예전에는 그때그때 가장 큰 잡음 대역 이름까지
            // 적어 화면이 쉴 새 없이 흔들렸다.
            !tone.hasTone -> Text(
                tone.reasonKo ?: "",
                color = SelahColors.TextMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )

            !tone.ok -> InfoBar(tone.reasonKo ?: "", tone = SelahColors.Warn)

            else -> Text(
                "1kHz 순음이 다른 대역보다 %.0fdB 솟아 있습니다 — 교정기가 제대로 물렸습니다."
                    .format(tone.prominenceDb),
                color = SelahColors.InRange,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        // **교정기만으로는 절반이다.**
        //
        // 교정기가 주는 것은 1kHz 한 점의 절대 레벨뿐이다. 그 폰 마이크가
        // 63Hz 나 8kHz 에서 얼마나 더/덜 잡는지는 아무 말도 하지 않는다.
        // 모양은 응답곡선이 맡는다 — 둘은 짝이지 대안이 아니다.
        //
        // 그래서 여기서 바로 말한다. 교정기를 산 사람이 「이제 보정이
        // 끝났다」고 여기는 것이 이 화면이 만들 수 있는 가장 나쁜 오해다.
        InfoBar(
            buildString {
                append("교정기는 1kHz 한 점의 크기만 맞춥니다. ")
                append("주파수마다 얼마나 더·덜 잡는지(응답곡선)는 따로 재야 합니다 — ")
                append("아래 「마이크 보정 곡선」에서 파일을 넣거나 기준 마이크로 재십시오.")
                appendLine()
                appendLine()
                if (curveOn) {
                    append("지금 곡선이 걸려 있습니다. ")
                    append("곡선은 1kHz 를 0dB 으로 맞춰 걸리므로 ")
                    append("교정기로 잡은 크기는 곡선을 바꿔도 흔들리지 않습니다.")
                } else {
                    append("지금 걸린 곡선이 없습니다 — 크기만 맞고 모양은 폰 마이크 그대로입니다.")
                }
            },
            tone = if (curveOn) SelahColors.InRange else SelahColors.TextMuted,
        )

        Text(
            "또는 다른 소음계에 맞추기",
            color = SelahColors.TextMuted,
            fontSize = 11.sp,
        )

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
                onClick = { reference?.let { onSave(it, CalibrationSource.Meter) } },
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
            // **묻고 나서 지운다**(2026-09-25 담당자 지적: 「보정값 초기화를
            // 눌렀더니 물어보지도 않고 그냥 지워지네요」).
            //
            // 맞는 지적이다. 이 값은 **기준 소음계나 교정기를 들고 재서**
            // 얻은 것이라 한 번 지우면 장비를 다시 꺼내야 되찾는다. 저장할
            // 때는 계산을 먼저 보여 주고 사람이 확인하게 해 놓고, 지울 때는
            // 한 번에 사라지게 두었던 것이 앞뒤가 안 맞았다.
            TextButton(
                onClick = { confirmClear = true },
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

        if (confirmClear) {
            capture.calibration.saved?.let { saved ->
                ClearCalibrationDialog(
                    saved = saved,
                    deviceLabel = capture.inputForDisplay?.deviceLabel ?: "이 기기",
                    onConfirm = { input = ""; onClear(); confirmClear = false },
                    onCancel = { confirmClear = false },
                )
            } ?: run { confirmClear = false }
        }

        capture.calibration.saved?.let { saved ->
            Text(
                "저장됨: %+.1f dB (%s · 기준 %.1f dB 에 맞춤)".format(
                    saved.offsetDb,
                    saved.source.labelKo,
                    saved.referenceDb,
                ),
                color = SelahColors.TextMuted,
                fontSize = 11.sp,
            )
            // **무엇에 맞춘 보정인지가 그 값을 얼마나 믿을지를 정한다.**
            // 숫자만 적어 두면 교정기로 맞춘 것과 옆 소음계를 베낀 것이
            // 화면에서 똑같아 보인다.
            Text(
                saved.source.trustKo,
                color = SelahColors.TextMuted,
                fontSize = 10.sp,
                lineHeight = 15.sp,
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

/**
 * 교정기 한 대 분의 버튼.
 *
 * **순음이 들어오지 않으면 눌리지 않는다.** 교정기를 마이크에 끼우지
 * 않은 채 눌러도 화면에는 그럴듯한 숫자가 뜨고, 그 값이 저장되면 그 뒤
 * 모든 측정이 조용히 틀린다. 막을 수 있는 자리에서 막는다.
 *
 * **묻지 않고 바로 저장한다.** 소음계 쪽과 다른 점이다 — 소음계 값은
 * 사람이 옮겨 적는 것이라 오타가 나므로 계산 결과를 먼저 보여 준다.
 * 교정기 값은 기구에 적힌 두 숫자 중 하나라 고를 자리가 둘뿐이고,
 * 대신 「순음이 맞는가」를 기계가 확인한다.
 */
@Composable
private fun RowScope.CalibratorButton(
    level: CalibratorLevel,
    tone: CalibratorToneCheck?,
    onClick: () -> Unit,
) {
    val ready = tone?.ok == true
    OutlinedButton(
        onClick = onClick,
        enabled = ready,
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (ready) SelahColors.Accent else SelahColors.Outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = SelahColors.Accent,
            disabledContentColor = SelahColors.TextMuted,
        ),
    ) {
        Text("교정기 ${level.labelKo}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}


/**
 * 보정값을 지우기 전에 **무엇이 사라지는지 낱낱이 적는다.**
 *
 * 「정말 지울까요?」만으로는 모자라다. 지워지는 것이 무엇으로 언제 어떻게
 * 얻은 값인지 보여 줘야, 사람이 「그건 아까워서 안 되겠다」거나 「그건
 * 잘못 잰 거라 지워도 된다」를 스스로 가를 수 있다.
 *
 * **되돌릴 수 없다는 것도 적는다.** 되돌리기가 없는 일에서 그 사실을 안
 * 적으면, 사람은 되돌릴 수 있다고 가정한다.
 */
@Composable
private fun ClearCalibrationDialog(
    saved: GlobalCalibration,
    deviceLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val savedAt = remember(saved.savedAtEpochMs) {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.KOREA)
            .format(java.util.Date(saved.savedAtEpochMs))
    }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = SelahColors.DialogSurface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.border(1.dp, SelahColors.Outline, RoundedCornerShape(20.dp)),
        title = { Text("보정값을 지울까요?", color = SelahColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "지워지는 값",
                    color = SelahColors.TextMuted,
                    fontSize = 11.sp,
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(SelahColors.SurfaceVariant, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ClearRow("기기", deviceLabel)
                    ClearRow("보정값", "%+.1f dB".format(saved.offsetDb))
                    ClearRow("무엇에 맞췄나", saved.source.labelKo)
                    ClearRow("기준값", "%.1f dB".format(saved.referenceDb))
                    ClearRow("맞춘 때", savedAt)
                }
                Text(
                    "이 값은 기준 소음계나 1kHz 교정기로 재서 얻은 것입니다. " +
                        "지우면 되돌릴 수 없고, 다시 얻으려면 장비를 들고 처음부터 " +
                        "재야 합니다.",
                    color = SelahColors.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Text(
                    "지운 뒤에는 이 기기의 음압이 다시 「미보정」이 되어, " +
                        "화면의 숫자가 실제와 10dB 넘게 차이 날 수 있습니다. " +
                        "주파수 보정 곡선은 그대로 남습니다 — 다른 값입니다.",
                    color = SelahColors.Warn,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("지웁니다", color = SelahColors.High, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("그대로 둡니다", color = SelahColors.Accent)
            }
        },
    )
}

@Composable
private fun ClearRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = SelahColors.TextMuted, fontSize = 11.sp, softWrap = false)
        Text(
            value,
            color = SelahColors.TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}
