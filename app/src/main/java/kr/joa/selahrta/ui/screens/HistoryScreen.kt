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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.LocalTextStyle
import kr.joa.selahrta.recording.MEMO_MAX
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
    /** 리포트를 PDF 한 장으로 보낸다. CSV 와 **쓰임이 다르다.** */
    onExportPdf: (SessionMeta) -> Unit,
    onDelete: (SessionMeta) -> Unit,
    onDismissNotice: () -> Unit,
    /** 그 기록의 소리 파일이 어디 있는지. 없으면 없는 파일을 준다. */
    audioFileOf: (SessionMeta) -> java.io.File,
    onShareAudio: (SessionMeta) -> Unit,
    /** 기록에 메모를 적는다(명세 12장). */
    onMemo: (String, String) -> Unit,
    /** 열어 본 기록의 행들. 아직 못 읽었으면 비어 있다. */
    rows: List<kr.joa.selahrta.recording.TimelineRow> = emptyList(),
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
            SessionDetail(
                m = opened,
                onClose = onClose,
                onExport = onExport,
                onExportPdf = onExportPdf,
                onDelete = onDelete,
                audioFileOf = audioFileOf,
                onShareAudio = onShareAudio,
                onMemo = onMemo,
                rows = rows,
            )
        }
    }
}

@Composable
private fun SessionList(capture: CaptureUiState, onOpen: (SessionMeta) -> Unit) {
    InfoBar(
        // **약속을 정확히 적는다.** 예전에는 「저장하지 않습니다」였는데,
        // 이제는 물어서 담을 수 있다 — 그 말을 그대로 두면 거짓이 된다.
        "소리는 기본으로 담지 않습니다. 기록을 시작할 때마다 물어보고, " +
            "「소리도 담기」를 고른 기록에만 소리가 남습니다.",
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
        // **적어 둔 메모를 목록에서도 보인다.** 기록을 구별하는 데
        // 숫자보다 이 한 줄이 낫다 — 「Leq 73.9」 가 셋이면 어느 것이
        // 찬양이었는지 알 수 없다.
        if (m.memo.isNotBlank()) {
            Text(
                m.memo,
                color = SelahColors.TextMuted,
                fontSize = 11.sp,
                // 목록에서는 한 줄만. 여러 줄 메모가 칸을 밀어내면
                // 아래 기록이 화면 밖으로 나간다.
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SessionDetail(
    m: SessionMeta,
    onClose: () -> Unit,
    onExport: (SessionMeta) -> Unit,
    onExportPdf: (SessionMeta) -> Unit,
    onDelete: (SessionMeta) -> Unit,
    audioFileOf: (SessionMeta) -> java.io.File,
    onShareAudio: (SessionMeta) -> Unit,
    onMemo: (String, String) -> Unit,
    rows: List<kr.joa.selahrta.recording.TimelineRow>,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var playMs by remember(m.id) { mutableStateOf(0) }

    TextButton(onClick = onClose) {
        Text("← 목록으로", color = SelahColors.Accent, fontSize = 13.sp)
    }

    // **경고가 맨 위에 온다.** 아래로 밀리면 숫자를 먼저 읽고 넘어간다.
    reportWarningsKo(m).forEach {
        InfoBar(it, tone = SelahColors.Warn, modifier = Modifier.padding(bottom = 8.dp))
    }

    // **소리를 위에 둔다.** 숫자를 보다가 「이때 무슨 소리였지」가
    // 궁금해지는 것이라, 표 아래에 묻어 두면 안 찾는다.
    m.audio?.let { a ->
        kr.joa.selahrta.ui.components.AudioPlayerCard(
            audio = a,
            file = audioFileOf(m),
            onShare = { onShareAudio(m) },
            onPosition = { playMs = it },
        )
        // **듣는 자리의 값을 바로 아래 붙인다.** 숫자와 소리를 같은
        // 시각으로 묶어야 「이 자리가 그 자리」라고 말할 수 있다.
        PlaybackReadout(m, rows, playMs)
    }

    // **리포트 위에 둔다.** 적으려고 들어왔다가 표를 다 지나쳐야
    // 나오면 안 적게 된다.
    MemoCard(m, onMemo)

    buildReport(m).forEach { section -> Section(section) }

    // **바로 위에 적힌 것을 그대로 한 장으로 보낸다.** 단추가 그 글 밑에
    // 있어야 「이것이 나간다」가 보인다.
    OutlinedButton(
        onClick = { onExportPdf(m) },
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Text("이 리포트를 PDF 한 장으로 보내기", fontSize = 13.sp)
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { onExport(m) },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = SelahColors.Accent,
                contentColor = SelahColors.OnAccent,
            ),
        ) {
            // **둘을 한 번에 보낸다.** 표와 소리가 따로 가면 받는 쪽에서
            // 어느 소리가 어느 표의 것인지 알 수 없다.
            Text(
                if (m.audio != null) "CSV·소리 보내기" else "CSV 로 내보내기",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
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

/**
 * **지금 듣는 자리의 값.**
 *
 * 행은 0.5초짜리다. 그 자리에 행이 없으면 **없다고 적는다** — 옆 행을
 * 가져다 놓으면 소리와 숫자가 어긋난 채 그럴듯해 보인다.
 */
/**
 * 기록에 메모를 적는다(명세 12장).
 *
 * ## 왜 필요했나
 *
 * 겉장에는 `memo` 자리가 있었고 왕복 시험까지 있었는데 **적을 길이
 * 없었다** — 리포트의 「메모」 줄이 늘 「없음」이었다.
 *
 * 숫자만으로는 두 달 뒤에 그 기록이 무엇이었는지 알 수 없다.
 * 「찬양 2부 · 에어컨 켜짐」 한 줄이 그 자리를 메운다.
 *
 * ## 고치는 중에는 원래 값을 건드리지 않는다
 *
 * 적는 동안에는 화면 안에만 두고, **저장을 눌러야** 겉장에 쓴다.
 * 쓰다 말고 나가면 아무 일도 없다.
 */
@Composable
private fun MemoCard(m: SessionMeta, onMemo: (String, String) -> Unit) {
    // **기록이 바뀌면 처음부터.** `remember(m.id)` 를 빼면 다른 기록을
    // 열었을 때 앞 기록의 메모가 칸에 남는다.
    var text by remember(m.id) { mutableStateOf(m.memo) }
    val changed = text.trim() != m.memo

    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .background(SelahColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "메모",
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= MEMO_MAX) text = it },
            placeholder = {
                Text(
                    "무엇을 잰 자리인지 적어 두십시오. 예: 찬양 2부 · 에어컨 켜짐",
                    color = SelahColors.TextMuted,
                    fontSize = 12.sp,
                )
            },
            textStyle = LocalTextStyle.current.copy(
                color = SelahColors.TextPrimary,
                fontSize = 13.sp,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SelahColors.Accent,
                unfocusedBorderColor = SelahColors.Outline,
                cursorColor = SelahColors.Accent,
            ),
            minLines = 2,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${text.length} / $MEMO_MAX",
                color = SelahColors.TextMuted,
                fontSize = 10.sp,
            )
            // **바뀐 것이 있을 때만 띄운다.** 늘 띄우면 눌러야 하는지
            // 아닌지가 흐려진다.
            if (changed) {
                TextButton(onClick = { onMemo(m.id, text) }) {
                    Text("저장", color = SelahColors.Accent, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun PlaybackReadout(
    m: SessionMeta,
    rows: List<kr.joa.selahrta.recording.TimelineRow>,
    atMs: Int,
) {
    if (rows.isEmpty()) {
        Text(
            "이 기록의 자세한 값은 아직 읽는 중입니다.",
            color = SelahColors.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        return
    }
    val v = kr.joa.selahrta.recording.playbackValuesAt(m, rows, atMs.toLong())
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .background(SelahColors.SurfaceVariant, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "듣는 자리의 값",
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        if (v == null) {
            Text(
                "이 자리에는 잰 값이 없습니다.",
                color = SelahColors.TextSecondary,
                fontSize = 12.sp,
            )
            return@Column
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ReadoutValue("현재", v.currentDb, m)
            ReadoutValue("최대", v.maxDb, m)
            ReadoutValue("순간최고", v.peakDb, m)
        }
        // **찌그러진 자리는 값이 전부 하한이다.** 들으면서 알아야 한다.
        if (v.clipped) {
            Text(
                "이 자리는 입력이 찌그러졌습니다 — 실제는 이보다 높습니다.",
                color = SelahColors.Warn,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun ReadoutValue(label: String, db: Double?, m: SessionMeta) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = SelahColors.TextMuted, fontSize = 10.sp)
        Text(
            // **없는 값을 0 으로 적지 않는다.** 놓친 자리다.
            if (db != null && db.isFinite()) "%.1f".format(db) else "—",
            color = SelahColors.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(m.weighting.unitSuffix, color = SelahColors.TextMuted, fontSize = 10.sp)
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
