package kr.joa.selahrta.ui.components

import androidx.compose.material3.Switch
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.style.TextAlign
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
 * 차이를 되돌릴 수 있다 — 다만 **주파수를 나눠 보는 화면에만**(RTA·Spectrum·
 * Spectrogram) 걸린다는 사실을 숨기지 않는다.
 */
@Composable
fun CurveCard(
    curve: ActiveCurve?,
    noticeKo: String?,
    canImport: Boolean,
    onPickFile: () -> Unit,
    onClear: () -> Unit,
    /** 걸기를 켜고 끈다. **파일은 건드리지 않는다.** */
    onToggleEnabled: (Boolean) -> Unit,
    /** 어느 마이크의 보정인지 사람이 적은 것을 저장한다. */
    onMicName: (String) -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }
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
                when {
                    curve == null -> "없음"
                    // **「꺼 둠」과 「없음」은 다른 상태다.** 파일은 그대로 있고,
                    // 다시 켤 때 가져올 필요가 없다.
                    !curve.enabled -> "꺼 둠"
                    else -> "적용됨"
                },
                color = when {
                    curve == null -> SelahColors.TextMuted
                    !curve.enabled -> SelahColors.Warn
                    else -> SelahColors.InRange
                },
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

            // **어느 마이크의 보정인지는 사람이 적는다**(USB 오디오 지시서 9.2).
            //
            // 파일 머리글에서 이름을 뽑아 「이 마이크다」라고 말하지 않는다 —
            // 오디오 인터페이스는 채널마다 다른 마이크가 꽂힐 수 있고, 무엇이
            // 꽂혀 있는지는 꽂은 사람만 안다. 짚어낸 이름이 틀리면 그게 더 나쁘다.
            var micName by remember(curve.fileName) { mutableStateOf(curve.micName) }
            OutlinedTextField(
                value = micName,
                onValueChange = { micName = it },
                label = { Text("마이크 (직접 적기)", fontSize = 12.sp) },
                placeholder = {
                    Text("예: Dayton EMM-6 #123456 · iMM-6C #123456", fontSize = 12.sp)
                },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    // 적고 나서 다른 데를 누르면 저장한다. 글자마다 저장하면
                    // 누를 때마다 파일을 쓴다.
                    .onFocusChanged { if (!it.isFocused) onMicName(micName) },
            )

            // **파일이 스스로 밝힌 것을 그대로 보인다**(USB 오디오 지시서 9.3).
            //
            // 둘째 열이 「응답」인지 「보정값」인지는 여기 적힌 열 이름으로만
            // 가릴 수 있고, 어느 마이크의 것인지도 보통 여기 있다. 우리가
            // 해석해서 「이 마이크다」라고 말하지 않는다 — 짚어낸 이름이
            // 틀리면 그게 더 나쁘다.
            if (curve.headerLines.isNotEmpty()) {
                var showHeader by remember(curve.fileName) { mutableStateOf(false) }
                TextButton(
                    onClick = { showHeader = !showHeader },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        if (showHeader) "파일 정보 접기" else "파일 정보",
                        color = SelahColors.Accent,
                        fontSize = 12.sp,
                    )
                }
                if (showHeader) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(SelahColors.Background, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        curve.headerLines.forEach {
                            Text(
                                it,
                                color = SelahColors.TextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            )
                        }
                    }
                }
            }

            // **끄는 것과 지우는 것을 가른다.** 보정 전·후를 견주려면
            // 껐다 켜야 하는데, 그때마다 파일을 다시 가져오게 하면
            // 아무도 견주지 않는다(USB 오디오 지시서 11장).
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "이 보정을 적용",
                        color = SelahColors.TextPrimary,
                        fontSize = 13.sp,
                    )
                    Text(
                        if (curve.enabled) {
                            "끄면 파일은 그대로 두고 원본 값만 봅니다."
                        } else {
                            "지금은 원본 값을 보고 있습니다. 파일은 그대로 있습니다."
                        },
                        color = SelahColors.TextMuted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                }
                Switch(
                    checked = curve.enabled,
                    onCheckedChange = onToggleEnabled,
                )
            }
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
            // **묻고 나서 지운다.** 보정값과 같은 까닭이다 — 이 파일은
            // 마이크 한 개에 딸려 온 개별 보정이라, 지우면 그 파일을 다시
            // 찾아와야 한다. 기기에서 만든 곡선이면 다시 재야 한다.
            TextButton(onClick = { confirmClear = true }, enabled = curve != null) {
                Text(
                    "초기화",
                    color = if (curve != null) SelahColors.TextSecondary else SelahColors.TextMuted,
                    fontSize = 13.sp,
                )
            }
        }

        if (confirmClear && curve != null) {
            ClearCurveDialog(
                curve = curve,
                onConfirm = { onClear(); confirmClear = false },
                onCancel = { confirmClear = false },
            )
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


/**
 * 보정 곡선을 지우기 전에 **어느 파일이 사라지는지 적는다.**
 *
 * 파일 이름이 곧 그 마이크의 신원이다 — `EMM-6_17860.txt` 는 시리얼
 * 17860 한 개의 것이고, 다시 얻으려면 제조사 페이지에서 그 시리얼로
 * 내려받거나 기준 마이크로 다시 재야 한다.
 *
 * **끄는 것과 지우는 것은 다르다는 것도 적는다.** 잠시 안 걸고 싶은
 * 것이라면 위의 켜고 끄는 고르개로 충분한데, 그것을 모르면 지우게 된다.
 */
@Composable
private fun ClearCurveDialog(
    curve: ActiveCurve,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = SelahColors.DialogSurface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.border(1.dp, SelahColors.Outline, RoundedCornerShape(20.dp)),
        title = { Text("보정 곡선을 지울까요?", color = SelahColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("지워지는 파일", color = SelahColors.TextMuted, fontSize = 11.sp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(SelahColors.SurfaceVariant, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CurveClearRow("파일", curve.fileName)
                    CurveClearRow("점 개수", "${curve.pointCount}개")
                    if (curve.micName.isNotBlank()) {
                        CurveClearRow("마이크", curve.micName)
                    }
                    CurveClearRow("지금", if (curve.enabled) "걸려 있음" else "꺼 둠")
                }
                Text(
                    "이 파일은 마이크 한 개에 딸린 개별 보정입니다. 지우면 " +
                        "되돌릴 수 없고, 다시 얻으려면 제조사에서 그 시리얼의 " +
                        "파일을 내려받거나 기준 마이크로 다시 재야 합니다.",
                    color = SelahColors.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                if (curve.enabled) {
                    Text(
                        "잠시 안 걸고 싶은 것뿐이라면 지우지 말고 위의 " +
                            "고르개를 끄십시오 — 파일은 그대로 두고 적용만 " +
                            "멈춥니다.",
                        color = SelahColors.Warn,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
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
private fun CurveClearRow(label: String, value: String) {
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
