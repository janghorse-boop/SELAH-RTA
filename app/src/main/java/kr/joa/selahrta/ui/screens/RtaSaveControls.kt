package kr.joa.selahrta.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.data.rta.RtaConditions
import kr.joa.selahrta.ui.CaptureUiState
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * **재고 저장하는 줄**(지시서 §7, 담당자 지시 기준 2).
 *
 * 재는 동안 「안정화 중」·「측정 중」과 **남은 시간**, 그리고 **취소**를
 * 보인다 — 10초는 화면을 보고 있기에 긴 시간이라, 무엇을 기다리는지
 * 모르면 사람이 앱이 멎은 줄 안다.
 */
@Composable
fun RtaSaveControls(
    capture: CaptureUiState,
    onSave: (String) -> Unit,
    onSaveNewSet: (String) -> Unit,
    /** L → R → L+R 을 이어서 잰다. */
    onStartSequence: (String) -> Unit,
    onCancel: () -> Unit,
    onOpenSaved: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    var naming by remember { mutableStateOf<Boolean?>(null) }
    var sequencing by remember { mutableStateOf(false) }

    val run = capture.rtaCapture
    // **한 줄만 낸다.** 이 자리는 `BandMeter` 가 `Box` 로 그리는 한 줄짜리
    // 슬롯이라, 덩어리를 여럿 내면 **전부 원점에 포개진다**(기기에서
    // 확인 — 칩 위에 글자가 겹쳤다).
    //
    // 칩은 왼쪽에서 자라므로 이쪽은 **오른쪽으로 붙인다.**
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (run != null) {
            // **차례대로 재는 중이면 몇 번째인지 먼저 적는다.**
            // 세 번을 잇는 동안 화면이 똑같아 보이면 사람이 끝난 줄 안다.
            capture.rtaSequenceKo?.let {
                Text(it, color = SelahColors.TextSecondary, fontSize = 11.sp)
            }
            Text(
                // **무엇을 하는 중인지와 얼마나 남았는지.**
                if (run.settling) "안정화 중" else "측정 중",
                color = if (run.settling) SelahColors.TextMuted else SelahColors.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "%.1f초".format(run.remainingMs / 1000.0),
                color = SelahColors.TextPrimary,
                fontSize = 11.sp,
            )
            if (!run.settling) {
                Text("· ${run.frames}장", color = SelahColors.TextMuted, fontSize = 10.sp)
            }
            TextButton(
                onClick = onCancel,
                modifier = Modifier.semantics { contentDescription = "측정 취소" },
            ) {
                Text("취소", color = SelahColors.Warn, fontSize = 11.sp)
            }
        } else {
            TextButton(
                onClick = { naming = false },
                modifier = Modifier.semantics { contentDescription = "이 곡선 저장" },
            ) {
                Text("이 곡선 저장", color = SelahColors.Accent, fontSize = 11.sp)
            }
            TextButton(
                onClick = { sequencing = true },
                modifier = Modifier.semantics { contentDescription = "순서대로 재기" },
            ) {
                Text("순서대로 재기", color = SelahColors.Accent, fontSize = 11.sp)
            }
            TextButton(
                onClick = onOpenSaved,
                modifier = Modifier.semantics { contentDescription = "저장된 측정" },
            ) {
                Text(
                    "저장된 측정 ${capture.savedRta.size}",
                    color = SelahColors.TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
    }

    // **알림은 대화상자로 낸다.** 차트 자리에 끼워 넣으면 한 줄짜리
    // 슬롯을 넘어 겹친다. 저장이 안 된 까닭은 놓치면 안 되는 말이라
    // 구석에 작게 적는 것보다 앞에 세우는 편이 맞다.
    capture.rtaSaveNoticeKo?.let { notice ->
        AlertDialog(
            onDismissRequest = onDismissNotice,
            title = { Text("저장하지 못했습니다", fontSize = 14.sp) },
            text = { Text(notice, fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = onDismissNotice) { Text("확인") }
            },
        )
    }

    if (sequencing) {
        NameCurveDialog(
            titleKo = "순서대로 재기",
            hintKo = "자리 이름 (예: 본당 중앙)",
            // 차례는 **늘 새 묶음**이다. 세 걸음이 한 벌이기 때문이다.
            hasSets = false,
            confirmKo = null,
            onDone = { name, _ -> onStartSequence(name); sequencing = false },
            onDismiss = { sequencing = false },
        )
    }

    if (naming != null) {
        NameCurveDialog(
            hasSets = capture.savedRtaSets.isNotEmpty(),
            onDone = { name, newSet ->
                if (newSet) onSaveNewSet(name) else onSave(name)
                naming = null
            },
            onDismiss = { naming = null },
        )
    }
}

/**
 * 이름을 받는다. **묶음에 더할지 새로 만들지도 여기서 고른다** — 첫 저장 때
 * 세트를 만들고 이어지는 채널은 같은 세트에 넣는다(담당자 지시 2항).
 */
@Composable
private fun NameCurveDialog(
    hasSets: Boolean,
    onDone: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    titleKo: String = "이 곡선 저장",
    hintKo: String = "이름 (예: 본당 중앙 · L)",
    /** null 이면 「지금 묶음에 더하기」를 아예 안 낸다(차례는 늘 새 묶음). */
    confirmKo: String? = "지금 묶음에 더하기",
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titleKo, fontSize = 14.sp) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(hintKo) },
                singleLine = true,
            )
        },
        confirmButton = {
            if (confirmKo != null) {
                TextButton(
                    onClick = { onDone(text.ifBlank { "이름 없음" }, false) },
                    enabled = hasSets,
                ) { Text(confirmKo) }
            }
        },
        dismissButton = {
            TextButton(onClick = { onDone(text.ifBlank { "이름 없음" }, true) }) {
                Text(if (confirmKo == null) "시작" else "새 묶음으로")
            }
        },
    )
}

@Composable
fun RenameSetDialog(initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("묶음 이름", fontSize = 14.sp) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("예: 본당 중앙 · 조정 전") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("그만") } },
    )
}

/**
 * 지금 재고 있는 조건. 저장한 것과 견줘 다르면 알린다.
 *
 * **모르는 것은 null 로 둔다**(담당자 지시 기준 5) — 지금 값을 모르면서
 * 「같다/다르다」를 말하지 않는다.
 */
fun CaptureUiState.currentRtaConditions(): RtaConditions? = RtaConditions(
    inputKey = opened?.deviceKey,
    calibrationState = calibration.state.name,
    calibrationSource = calibration.saved?.source?.name,
    curveName = curve?.takeIf { it.enabled }?.fileName ?: "",
    fftSize = null,
    sampleRate = opened?.sampleRate,
)
