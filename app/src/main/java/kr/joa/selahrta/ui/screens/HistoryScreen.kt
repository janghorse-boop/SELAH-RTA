package kr.joa.selahrta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.recording.ReportSection
import kr.joa.selahrta.recording.SessionMeta
import kr.joa.selahrta.recording.buildReport
import kr.joa.selahrta.recording.reportWarningsKo
import kr.joa.selahrta.ui.CaptureUiState
import kr.joa.selahrta.ui.components.InfoBar
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * 예배 기록(컨셉 화면 7번).
 *
 * ## 목록은 겉장만 읽는다
 *
 * 타임라인은 2시간이면 1.4MB 다. 목록을 그리려고 전부 읽으면 화면이
 * 멈춘다 — 내보낼 때만 연다.
 *
 * ## 리포트는 조건과 측정값을 함께 적는다
 *
 * 담당자가 정한 뼈대다. 숫자만 보여 주면 반년 뒤 같은 자리에서 잰 값과
 * 달라도 **왜 다른지 말할 길이 없다** — 어떤 입력으로, 어느 마이크로,
 * 어떤 가공 상태에서 잰 것인지가 함께 있어야 한다.
 *
 * 화면과 CSV 가 **같은 문장**을 쓴다([buildReport]). 따로 적으면 같은
 * 기록이 자리마다 다른 말을 한다.
 */
@Composable
fun HistoryScreen(
    capture: CaptureUiState,
    onOpen: (SessionMeta) -> Unit,
    onClose: () -> Unit,
    onExport: (SessionMeta) -> Unit,
    onDelete: (SessionMeta) -> Unit,
    onDismissNotice: () -> Unit,
) {
    val opened = capture.openedSession
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        capture.historyNoticeKo?.let {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(it, color = SelahColors.TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissNotice) {
                    Text("확인", color = SelahColors.Accent, fontSize = 12.sp)
                }
            }
        }

        if (opened == null) {
            SessionList(capture, onOpen)
        } else {
            SessionDetail(opened, onClose, onExport, onDelete)
        }
    }
}

@Composable
private fun SessionList(capture: CaptureUiState, onOpen: (SessionMeta) -> Unit) {
    InfoBar(
        "기본 측정은 소리를 저장하지 않습니다. 음압·주파수 요약만 남습니다.",
        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
    )

    // **읽지 못한 기록을 조용히 빼지 않는다.** 「분명히 쟀는데 없다」가
    // 되면 사람이 앱을 못 믿는다.
    if (capture.brokenSessions > 0) {
        InfoBar(
            "읽지 못한 기록이 ${capture.brokenSessions}개 있습니다. 파일이 깨졌거나 " +
                "더 새 판으로 적힌 것입니다.",
            tone = SelahColors.Warn,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }

    if (capture.sessions.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("아직 기록이 없습니다.", color = SelahColors.TextSecondary, fontSize = 14.sp)
            Text(
                "측정 화면에서 기록을 켜고 재면 여기에 남습니다.",
                color = SelahColors.TextMuted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        capture.sessions.forEach { m -> SessionRow(m, onOpen) }
    }
}

@Composable
private fun SessionRow(m: SessionMeta, onOpen: (SessionMeta) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SelahColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
            .clickable { onOpen(m) }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                localTime(m.startedAtEpochMs),
                color = SelahColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            // **믿음 등급을 목록에서부터 적는다.** 열어 봐야 아는 것이면
            // 늦다 — 숫자를 먼저 읽고 넘어간다.
            Text(
                m.conditions.calibrationState?.shortKo
                    ?: if (m.referenceOnly) "미보정" else "보정됨",
                color = kr.joa.selahrta.ui.theme.calibrationTone(
                    m.conditions.calibrationState
                        ?: if (m.referenceOnly) {
                            kr.joa.selahrta.domain.CalibrationState.Uncalibrated
                        } else {
                            kr.joa.selahrta.domain.CalibrationState.GlobalCalibrated
                        },
                ),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            "${durationKo(m.durationMs)} · ${m.deviceLabel.ifBlank { "기기 기록 없음" }}",
            color = SelahColors.TextMuted,
            fontSize = 11.sp,
        )
        Text(
            if (m.leqDb.isFinite()) {
                "Leq %.1f · MAX %.1f %s".format(m.leqDb, m.maxDb, m.weighting.unitSuffix)
            } else {
                "요약 없음"
            },
            color = SelahColors.TextSecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun SessionDetail(
    m: SessionMeta,
    onClose: () -> Unit,
    onExport: (SessionMeta) -> Unit,
    onDelete: (SessionMeta) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    TextButton(onClick = onClose) {
        Text("← 목록으로", color = SelahColors.Accent, fontSize = 13.sp)
    }

    // **경고가 맨 위에 온다.** 아래로 밀리면 숫자를 먼저 읽고 넘어간다.
    reportWarningsKo(m).forEach {
        InfoBar(it, tone = SelahColors.Warn, modifier = Modifier.padding(bottom = 8.dp))
    }

    buildReport(m).forEach { section -> Section(section) }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { onExport(m) },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = SelahColors.Accent,
                contentColor = Color(0xFF00201C),
            ),
        ) {
            Text("CSV 로 내보내기", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        TextButton(onClick = { confirmDelete = true }) {
            Text("삭제", color = SelahColors.TextSecondary, fontSize = 13.sp)
        }
    }

    if (confirmDelete) {
        DeleteDialog(
            m = m,
            onConfirm = { confirmDelete = false; onDelete(m) },
            onCancel = { confirmDelete = false },
        )
    }
}

@Composable
private fun Section(section: ReportSection) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .background(SelahColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            section.titleKo,
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        section.lines.forEach { line ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    line.labelKo,
                    color = SelahColors.TextMuted,
                    fontSize = 11.sp,
                    softWrap = false,
                )
                Text(
                    line.valueKo,
                    color = SelahColors.TextPrimary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false),
                )
            }
        }
    }
}

/**
 * 지우기 전에 **무엇이 사라지는지 적는다.**
 *
 * 되돌릴 수 없다. 「정말 지울까요?」만으로는 무엇을 잃는지 알 수 없다.
 */
@Composable
private fun DeleteDialog(m: SessionMeta, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = SelahColors.DialogSurface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.border(1.dp, SelahColors.Outline, RoundedCornerShape(20.dp)),
        title = { Text("이 기록을 지울까요?", color = SelahColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${localTime(m.startedAtEpochMs)} · ${durationKo(m.durationMs)}",
                    color = SelahColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "예배 한 번을 다시 잴 수는 없습니다. 지우면 되돌릴 수 없으니, " +
                        "남겨 둘 것이 있으면 먼저 CSV 로 내보내십시오.",
                    color = SelahColors.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
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

/**
 * 목록에는 **현지 시각**으로 적는다.
 *
 * CSV 는 UTC 다 — 파일을 다른 표준시로 여는 일이 있기 때문이다. 화면은
 * 지금 그 자리에서 보는 사람이 읽으므로 현지 시각이 맞다.
 */
private fun localTime(epochMs: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd (E) HH:mm", java.util.Locale.KOREA)
        .format(java.util.Date(epochMs))

private fun durationKo(ms: Long): String {
    if (ms <= 0) return "길이 없음"
    val s = ms / 1000
    return when {
        s < 60 -> "${s}초"
        s < 3600 -> "${s / 60}분 ${s % 60}초"
        else -> "${s / 3600}시간 ${(s % 3600) / 60}분"
    }
}
