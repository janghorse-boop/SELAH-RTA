package kr.joa.selahrta.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.recording.AudioFileFormat
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * **소리도 함께 담을지 묻는다**(담당자 결정 2026-09-27).
 *
 * ## 왜 매번 묻는가
 *
 * 설정 스위치로 둘 수도 있었다. 그런데 한 번 켜 두면 **켜 둔 것을 잊은
 * 채 다음 예배까지 담긴다.** 예배 소리를 담는 일은 dB 숫자를 남기는
 * 것과 성격이 다르다 — 설교와 성도들의 목소리가 그대로 들어간다.
 * 매번 묻는 쪽이 번거롭지만, 사람이 **알고 시작**한다.
 *
 * ## 크기를 먼저 적는다
 *
 * 2시간 예배면 m4a 가 100MB 대, WAV 는 700MB 에 가깝다. 다 찬 뒤에
 * 알면 늦으므로 고르는 자리에 그 숫자를 함께 둔다.
 */
@Composable
fun AudioAskDialog(
    format: AudioFileFormat,
    onFormat: (AudioFileFormat) -> Unit,
    onWithAudio: () -> Unit,
    onWithoutAudio: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = SelahColors.DialogSurface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.border(1.dp, SelahColors.Outline, RoundedCornerShape(20.dp)),
        title = { Text("소리도 함께 담을까요?", color = SelahColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "음압·주파수 요약은 늘 남습니다. 여기서 「예」를 고르면 " +
                        "예배 소리까지 파일로 남습니다.",
                    color = SelahColors.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                // **무엇이 담기는지 분명히 적는다.** 「소리」라고만 하면
                // 음악만 떠올리기 쉽다.
                // **지울 수 있는 것과 없는 것을 가른다**(독립 검토 UIS-01,
                // 2026-09-28). 「지우면 함께 사라집니다」는 앱이 들고 있는
                // 사본에만 해당한다 — 카톡으로 보내거나 파일로 내보낸
                // 것까지 앱이 회수하지는 못한다. 그것까지 사라지는 것처럼
                // 읽히면 **지웠다고 믿고 넘어간다.**
                InfoBar(
                    "설교와 성도들의 목소리가 그대로 담깁니다. 이 폰 안에만 두고 " +
                        "서버로 보내지 않으며, 기록을 지우면 앱에 있던 파일도 함께 " +
                        "사라집니다. 다만 따로 내보내거나 보낸 사본은 받는 쪽에 남습니다.",
                    tone = SelahColors.Warn,
                )

                Text("어떤 꼴로 담을까요", color = SelahColors.TextMuted, fontSize = 11.sp)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AudioFileFormat.entries.forEach { f ->
                        FormatButton(f, f == format) { onFormat(f) }
                    }
                }
                Text(
                    "${format.labelKo} · ${format.twoHourEstimateKo()}",
                    color = SelahColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    format.noteKo,
                    color = SelahColors.TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onWithAudio) {
                Text("소리도 담기", color = SelahColors.Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            // **「아니요」가 기본에 가깝게 보이도록 둔다.** 담지 않는 것이
            // 이 앱의 기본이다.
            TextButton(onClick = onWithoutAudio) {
                Text("숫자만 기록", color = SelahColors.TextSecondary)
            }
        },
    )
}

@Composable
private fun RowScope.FormatButton(f: AudioFileFormat, picked: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (picked) SelahColors.Accent else SelahColors.Outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (picked) SelahColors.Accent else SelahColors.TextSecondary,
        ),
    ) {
        Text(f.labelKo, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
