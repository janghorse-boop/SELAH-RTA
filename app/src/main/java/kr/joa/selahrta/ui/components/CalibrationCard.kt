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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import kr.joa.selahrta.calibration.CalibrationSource
import kr.joa.selahrta.calibration.CalibratorLevel
import kr.joa.selahrta.calibration.DeviceBuildInfo
import kr.joa.selahrta.calibration.FactoryCalibration
import kr.joa.selahrta.calibration.PLAUSIBLE_REFERENCE_RANGE
import kr.joa.selahrta.calibration.computeOffset
import kr.joa.selahrta.calibration.factoryEntrySnippet
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.CalibratorToneCheck
import kr.joa.selahrta.dsp.checkCalibratorTone
import kr.joa.selahrta.ui.CaptureUiState
import kr.joa.selahrta.ui.theme.SelahColors
import kr.joa.selahrta.ui.theme.calibrationTone

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
    /** 「이 자리에서 잰 것이 맞다」고 사람이 확인해 준다(독립 재검토 CAR-03). */
    onConfirmRoute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }
    val measured = capture.meter.currentDbfs

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
                color = calibrationTone(capture.calibration.state),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // **기종 기본값이 걸려 있으면 먼저 말한다.**
        //
        // 이 카드는 「보정하는 자리」다. 값이 이미 걸려 있는데 어디서 온
        // 것인지 적지 않으면, 사람은 제가 언젠가 보정한 줄 알고 그냥
        // 넘어간다 — 그러면 개체 차이가 영영 남는다.
        capture.calibration.factory?.takeIf { capture.calibration.usingFactory }?.let { f ->
            InfoBar(
                buildString {
                    append("지금은 이 기종의 기본값 ")
                    append("%+.1f dB".format(f.offsetDb))
                    append(" 이 걸려 있습니다. 개발자가 같은 기종에서 재어 앱에 실어 둔 ")
                    append("값이라 짐작보다는 가깝지만, 이 기기를 잰 값은 아닙니다.")
                    appendLine()
                    appendLine()
                    append("잰 내력: ").append(f.originKo())
                    appendLine()
                    appendLine()
                    append("아래에서 기준 소음계나 교정기로 맞추면 그 값이 대신 걸립니다. ")
                    append("기본값은 지워지지 않으니, 잰 값을 초기화하면 여기로 돌아옵니다.")
                },
                tone = SelahColors.TextSecondary,
            )
        }

        // **저장된 값이 있는데 걸지 않은 자리**(독립 재검토 CAR-03).
        //
        // 값은 그대로 있다 — 자리를 확인하지 못했을 뿐이다. 지우게 하지
        // 않고, 사람이 「이 자리에서 잰 것이 맞다」고 말할 수 있게 한다.
        // 앱이 저절로 채우면 확인하지 않은 것을 확인했다고 적는 꼴이다.
        capture.calibration.holdNoticeKo?.let { hold ->
            InfoBar(hold, tone = SelahColors.Warn)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onConfirmRoute) {
                    Text("이 자리에서 계속 쓰기", fontSize = 12.sp, color = SelahColors.Accent)
                }
            }
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

        // **교정기로만 맞춘다**(담당자 지시 2026-09-28: 「94 dB 와 114dB 만
        // 사용해서 교정을 진행합니다」). 기준 소음계 값을 손으로 적어 넣던
        // 자리는 아래에서 걷어냈다.
        //
        // 잃는 것이 없지는 않다 — 교정기가 없는 사람은 이 카드로 맞출 길이
        // 없어진다. 다만 손으로 적는 쪽은 **그 소음계가 맞다는 가정** 위에
        // 서고 두 기기의 가중이 다르면 그 차이까지 섞여 들어간다. 1kHz
        // 순음은 A·C·Z 가 모두 0dB 이라 그 문제가 없다.
        Text(
            "음압 교정기를 폰 마이크에 끼우고 켠 뒤, 기구에 적힌 값을 " +
                "아래에서 고르십시오. 1kHz 순음이라 가중치와 무관합니다.",
            color = SelahColors.TextMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("지금 읽는 값", color = SelahColors.TextMuted, fontSize = 11.sp)
            // **음압을 앞에 적는다**(담당자 물음 2026-09-28: 「94 dB 를
            // 누르고 지금 읽는 값이 94 dB 와 같아지면 교정이 완료되는 게
            // 아닌지?」).
            //
            // 맞다 — 보정은 `94 − 지금 dBFS` 를 오프셋으로 걸어 두는 일이라,
            // 걸고 나면 같은 소리를 94 로 읽는다. 그런데 이 줄은 **dBFS**
            // 만 적고 있어서 **그 완료를 눈으로 볼 수가 없었다.**
            // 보정된 음압을 함께 적어, 누른 뒤 이 숫자가 94 가 되는 것으로
            // 확인하게 한다.
            Text(
                buildString {
                    capture.meter.currentSpl?.let {
                        append("%.1f %s".format(it, capture.meterSettings.splWeighting.unitSuffix))
                        append("  ·  ")
                    }
                    append("%.1f dBFS".format(measured))
                },
                color = SelahColors.TextSecondary,
                fontSize = 11.sp,
            )
        }

        // **음압 교정기가 있으면 그쪽이 낫다.**
        //
        // 교정기는 제 소리를 내는 기구라 절대값의 근거가 된다. 소음계에
        // 맞추는 것은 그 소음계가 맞다는 가정 위에 서고, 두 기기의 가중이
        // 다르면 그 차이까지 섞여 들어간다. 게다가 1kHz 에서는 A·C 가중이
        // 0dB 이라 교정기로 맞춘 값은 가중치와 무관하다.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CalibratorLevel.entries.forEach { level ->
                CalibratorButton(
                    level = level,
                    // **소리를 읽고 있으면 누를 수 있다**(담당자 지시
                    // 2026-09-28: 「음압에 대한 간편교정은 쉬워야 합니다」).
                    //
                    // 예전에는 순음 확인을 통과해야만 열렸다. 그런데 그
                    // 확인이 재는 내내 오락가락하는 자리에서는 **아예
                    // 보정을 할 수가 없었다** — 눌러야 할 단추가 회색인
                    // 채로 있으니 사람은 무엇을 더 해야 하는지 모른다.
                    //
                    // 확인을 버리지는 않는다. 아래 한 줄이 순음이 보이는지
                    // 그대로 적고, 맞춘 결과는 바로 위 「지금 읽는 값」이
                    // 음압으로 보여 준다 — **틀리게 맞췄으면 그 숫자가
                    // 곧바로 이상하다.** 막는 대신 보이게 한다.
                    enabled = measured != null,
                    highlight = tone?.ok == true,
                    onClick = { onSave(level.db, CalibrationSource.Calibrator) },
                )
            }
        }

        // **한 줄로 고정한다**(담당자 지적 2026-09-28: 「박스가 계속
        // 나왔다 들어갔다 합니다」).
        //
        // 예전에는 순음이 안 잡히면 글자 한 줄, 잡혔는데 1kHz 가 아니면
        // **주황 상자**였다. 재는 동안 그 둘을 오가니 상자가 떴다 사라졌다
        // 하며 카드 높이가 계속 들썩였다. 셋 다 같은 꼴의 한 줄로 적는다 —
        // 알릴 내용은 그대로 두되 **화면이 흔들리지 않게** 한다.
        Text(
            when {
                tone == null -> "소리를 읽기 시작하면 순음을 확인합니다."
                tone.ok ->
                    "1kHz 순음이 다른 대역보다 %.0fdB 솟아 있습니다 — 교정기가 제대로 물렸습니다."
                        .format(tone.prominenceDb)
                else -> tone.reasonKo ?: ""
            },
            color = if (tone?.ok == true) SelahColors.InRange else SelahColors.TextMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

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

        // **「또는 다른 소음계에 맞추기」를 걷어냈다**(담당자 지시
        // 2026-09-28: 「94 dB 와 114dB 만 사용해서 교정을 진행합니다」).
        //
        // 기준 소음계 값을 손으로 적어 넣고 저장하던 자리다. 교정기로만
        // 맞추기로 했으므로 입력칸·계산값·저장 단추가 함께 빠졌다 —
        // 교정기 단추가 누르는 즉시 저장하기 때문에 따로 저장할 것이 없다.
        //
        // `CalibrationSource.Meter` 와 `computeOffset` 은 교정 마법사가
        // 그대로 쓰므로 지우지 않았다.

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    factory = capture.calibration.factory,
                    onConfirm = { onClear(); confirmClear = false },
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

        // **재는 것과 앱에 싣는 것 사이를 사람 손이 잇는다.**
        //
        // 기종 기본값은 개발자가 재서 `FACTORY_CALIBRATIONS` 에 적어야
        // 생긴다. 그 사이를 눈으로 옮겨 적게 두면 숫자 하나가 틀려도
        // 아무도 모른 채 그 기종 전체에 걸린다. 그래서 화면이 붙여 넣을
        // 줄을 그대로 준다.
        //
        // 접어 둔다 — 쓰는 사람은 개발자 하나뿐이고, 늘 펴 두면 보정
        // 화면이 코드로 어수선해진다.
        FactorySnippet(capture)

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
 * **개발자용** — 이 기기에서 잰 값을 기종 기본값 표에 붙일 코드로 적는다.
 *
 * 내장 마이크에서 실제로 잰 값이 있을 때만 나온다. USB 마이크는 폰의
 * 일부가 아니라 기종으로 값을 정할 수 없고([findFactoryCalibration]),
 * 잰 값이 없으면 실을 것도 없다.
 */
@Composable
private fun FactorySnippet(capture: CaptureUiState) {
    val saved = capture.calibration.saved ?: return
    val opened = capture.opened ?: return
    if (opened.micKind != MicKind.BuiltIn) return

    var open by remember { mutableStateOf(false) }
    val build = remember { DeviceBuildInfo.current() }
    val day = remember(saved.savedAtEpochMs) {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA)
            .format(java.util.Date(saved.savedAtEpochMs))
    }

    TextButton(onClick = { open = !open }) {
        Text(
            if (open) "기종 기본값으로 실을 코드 접기" else "개발자용 · 기종 기본값으로 실을 코드",
            color = SelahColors.TextMuted,
            fontSize = 11.sp,
        )
    }
    if (!open) return

    Text(
        "${build.manufacturer} / ${build.model} 를 쓰는 사람 모두에게 걸립니다. " +
            "한 대만 재고 싣지 마십시오 — 개체 차이를 확인한 뒤 noteKo 에 몇 대를 " +
            "쟀는지 적으십시오.",
        color = SelahColors.Warn,
        fontSize = 10.sp,
        lineHeight = 15.sp,
    )
    SelectionContainer {
        Text(
            factoryEntrySnippet(
                build = build,
                source = opened.audioSource,
                routeAddress = opened.routedAddress,
                cal = saved,
                measuredOn = day,
            ),
            color = SelahColors.TextSecondary,
            fontSize = 10.sp,
            lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .background(SelahColors.SurfaceVariant, RoundedCornerShape(8.dp))
                .padding(10.dp),
        )
    }
    Text(
        "길게 눌러 복사한 뒤 FactoryCalibration.kt 의 FACTORY_CALIBRATIONS 에 붙이십시오.",
        color = SelahColors.TextMuted,
        fontSize = 10.sp,
        lineHeight = 15.sp,
    )
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
    /** 누를 수 있는가. **소리를 읽고 있으면 누를 수 있다**(2026-09-28). */
    enabled: Boolean,
    /** 순음까지 확인된 상태인가. 막지는 않고 **테두리로만** 알린다. */
    highlight: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            if (highlight) 2.dp else 1.dp,
            if (highlight) SelahColors.InRange else SelahColors.Outline,
        ),
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
    /** 지우고 나면 돌아갈 기종 기본값. 없으면 null — 그때는 짐작으로 내려간다. */
    factory: FactoryCalibration?,
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
                // **지운 뒤에 무엇이 걸리는지가 다르다.**
                //
                // 기종 기본값이 있으면 「미보정」으로 떨어지지 않는다 —
                // 그쪽으로 되돌아간다. 없는데 있다고 하거나, 있는데 없다고
                // 하면 사람이 지울지 말지를 잘못 정한다.
                if (factory != null) {
                    Text(
                        "지운 뒤에는 이 기종의 기본값 %+.1f dB 이 대신 걸립니다. ".format(
                            factory.offsetDb,
                        ) +
                            "기본값은 앱에 실려 있어 지워지지 않습니다. " +
                            "같은 기종을 잰 값이라 짐작보다는 가깝지만, 이 기기를 잰 값은 " +
                            "아니므로 그만큼 차이가 남습니다.",
                        color = SelahColors.TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                } else {
                    Text(
                        "지운 뒤에는 이 기기의 음압이 다시 「미보정」이 되어, " +
                            "화면의 숫자가 실제와 10dB 넘게 차이 날 수 있습니다.",
                        color = SelahColors.Warn,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
                Text(
                    "주파수 보정 곡선은 그대로 남습니다 — 다른 값입니다.",
                    color = SelahColors.TextMuted,
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
