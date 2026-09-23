package kr.joa.selahrta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.audio.micPositionKo
import kr.joa.selahrta.calibration.MeasuredProfile
import kr.joa.selahrta.calibration.ProfileApply
import kr.joa.selahrta.calibration.ProfileEnvironment
import kr.joa.selahrta.calibration.StoredProfile
import kr.joa.selahrta.calibration.judgeProfileApply
import kr.joa.selahrta.dsp.QualityVerdict
import kr.joa.selahrta.ui.ProfilesUiState
import kr.joa.selahrta.ui.components.CalibrationCompareCard
import kr.joa.selahrta.ui.components.InfoBar
import kr.joa.selahrta.ui.theme.SelahColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 프로파일 관리(지시서 7장).
 *
 * > 마이크별 프로파일 목록, 보정 전/후 그래프, 품질·유효 범위, ON/OFF,
 * > 재검증·재교정, 삭제를 제공한다.
 *
 * ## 「켜 둠」과 「지금 걸린다」는 다르다
 *
 * 이 화면이 하는 일 중 제일 중요한 것이 그 둘을 **갈라 보여 주는**
 * 것이다. 사람이 켜 두었어도 경로가 달라졌으면 걸리지 않는다
 * ([judgeProfileApply]). 스위치만 보여 주면 「켜 뒀는데 왜 안 걸리지」가
 * 되고, 그 물음에 답할 데가 화면에 없다. 그래서 스위치 아래에 **지금
 * 걸리는지와 그 까닭**을 함께 적는다.
 */
@Composable
fun CalibrationProfilesScreen(
    state: ProfilesUiState,
    /** 지금 실제로 열려 있는 경로. 없으면(안 재는 중) 대조하지 않는다. */
    now: ProfileEnvironment?,
    onToggle: (MeasuredProfile, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onOpen: (MeasuredProfile) -> Unit,
    onCloseOpened: () -> Unit,
    onDismissNotice: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "프로파일 관리",
                color = SelahColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = onClose) { Text("닫기") }
        }

        if (state.noticeKo != null) {
            InfoBar(
                state.noticeKo,
                tone = SelahColors.Warn,
                trailingKo = "닫기",
                modifier = Modifier.clickable { onDismissNotice() },
            )
        }

        Text(
            if (now == null) NO_OPEN_PATH_KO else "지금 경로: ${pathSummaryKo(now)}",
            color = SelahColors.TextMuted,
            fontSize = 11.sp,
            style = TextStyle(lineBreak = LineBreak.Paragraph),
        )

        when {
            state.loading -> Text("읽는 중…", color = SelahColors.TextSecondary, fontSize = 13.sp)
            state.isEmpty -> InfoBar(NO_PROFILES_KO, tone = SelahColors.TextSecondary)
            else -> state.items.forEach { item ->
                when (item) {
                    is StoredProfile.Ok -> ProfileRow(
                        stored = item,
                        now = now,
                        opened = state.openedId == item.profile.id,
                        state = state,
                        onToggle = onToggle,
                        onDelete = onDelete,
                        onOpen = onOpen,
                        onCloseOpened = onCloseOpened,
                    )

                    is StoredProfile.Damaged -> DamagedRow(item, onDelete)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** 열린 경로가 없을 때. **「프로파일이 없다」와 다른 말이다.** */
const val NO_OPEN_PATH_KO: String =
    "지금 열린 입력이 없습니다. 재는 중이 아니면 프로파일이 지금 걸리는지 " +
        "대조할 수 없습니다."

const val NO_PROFILES_KO: String =
    "저장된 프로파일이 없습니다. 교정 마법사로 재고 저장하면 여기 쌓입니다."

@Composable
private fun ProfileRow(
    stored: StoredProfile.Ok,
    now: ProfileEnvironment?,
    opened: Boolean,
    state: ProfilesUiState,
    onToggle: (MeasuredProfile, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onOpen: (MeasuredProfile) -> Unit,
    onCloseOpened: () -> Unit,
) {
    val p = stored.profile
    var confirmingDelete by remember { mutableStateOf(false) }
    val match = now?.let { judgeProfileApply(p, it) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(SelahColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    p.labelKo,
                    color = SelahColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${profileTimeKo(p.createdAtEpochMs)} · ${validRangeKo(p)}",
                    color = SelahColors.TextSecondary,
                    fontSize = 11.sp,
                )
            }
            Switch(
                checked = p.enabled,
                onCheckedChange = { onToggle(p, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = SelahColors.InRange),
            )
        }

        Text(
            qualityLineKo(p),
            color = if (p.quality.verdict == QualityVerdict.Pass) {
                SelahColors.TextSecondary
            } else {
                SelahColors.Warn
            },
            fontSize = 11.sp,
            style = TextStyle(lineBreak = LineBreak.Paragraph),
        )

        // **켜 둔 것과 지금 걸리는 것을 갈라 적는다.**
        if (match != null) {
            val tone = when (match.apply) {
                ProfileApply.Apply -> SelahColors.InRange
                ProfileApply.ApplyWithWarning -> SelahColors.Warn
                ProfileApply.Block -> SelahColors.Warn
            }
            InfoBar(applyLineKo(match.apply, match.reasonsKo), tone = tone)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { if (opened) onCloseOpened() else onOpen(p) }) {
                Text(if (opened) "곡선 접기" else "보정 전·후 보기")
            }
            TextButton(onClick = { confirmingDelete = !confirmingDelete }) {
                Text(if (confirmingDelete) "취소" else "삭제")
            }
        }

        if (confirmingDelete) {
            InfoBar(DELETE_WARNING_KO, tone = SelahColors.Warn)
            TextButton(onClick = { confirmingDelete = false; onDelete(stored.fileName) }) {
                Text("정말 지웁니다")
            }
        }

        if (opened) {
            when {
                state.openedErrorKo != null ->
                    InfoBar("곡선을 읽지 못했습니다: ${state.openedErrorKo}", tone = SelahColors.Warn)

                state.openedCurves == null ->
                    Text("곡선을 읽는 중…", color = SelahColors.TextSecondary, fontSize = 12.sp)

                else -> CalibrationCompareCard(
                    outcome = state.openedCurves,
                    // 저장된 판정은 프로파일에 적혀 있고, 카드가 받는
                    // 판정 꼴과 다르다. 여기서는 곡선만 보여 준다.
                    quality = null,
                )
            }
        }
    }
}

const val DELETE_WARNING_KO: String =
    "지우면 곡선까지 함께 사라집니다. 되돌릴 수 없고, 다시 쓰려면 다시 재야 합니다."

@Composable
private fun DamagedRow(item: StoredProfile.Damaged, onDelete: (String) -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(SelahColors.Warn.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .border(1.dp, SelahColors.Warn.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "읽지 못한 파일",
            color = SelahColors.Warn,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(item.fileName, color = SelahColors.TextSecondary, fontSize = 11.sp)
        Text(
            if (expanded) item.reasonKo else shortReasonKo(item.reasonKo),
            color = SelahColors.TextSecondary,
            fontSize = 11.sp,
            style = TextStyle(lineBreak = LineBreak.Paragraph),
        )
        Text(
            DAMAGED_KEPT_KO,
            color = SelahColors.TextMuted,
            fontSize = 11.sp,
            style = TextStyle(lineBreak = LineBreak.Paragraph),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // **까닭이 길면 접어 둔다.** 빠진 항목이 스물셋이면 그대로
            // 펼쳤을 때 삭제 버튼이 화면 밖으로 밀려난다(기기에서 확인).
            if (isLongReason(item.reasonKo)) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "까닭 접기" else "까닭 자세히")
                }
            }
            TextButton(onClick = { confirming = !confirming }) {
                Text(if (confirming) "취소" else "삭제")
            }
            if (confirming) {
                TextButton(onClick = { confirming = false; onDelete(item.fileName) }) {
                    Text("정말 지웁니다")
                }
            }
        }
    }
}

/** 왜 지우지 않고 두었는지 **적어 둔다.** 그러지 않으면 버그로 보인다. */
const val DAMAGED_KEPT_KO: String =
    "앱이 올라가면 읽힐 수도 있어 지우지 않고 둡니다. 필요 없으면 여기서 지웁니다."

/** 이 길이를 넘으면 접는다. */
const val REASON_FOLD_AT: Int = 110

fun isLongReason(reason: String): Boolean = reason.length > REASON_FOLD_AT

/**
 * 긴 까닭을 줄인다.
 *
 * **다 지우지 않는다.** 빈 프로파일 파일 하나가 스물세 항목을 한꺼번에
 * 게워내는데(기기에서 확인), 그대로 그리면 「읽지 못한 파일」 칸이 화면을
 * 다 먹고 **삭제 버튼이 화면 밖으로 밀려난다** — 지울 수도 없는 파일이
 * 목록에 영영 남는다. 그렇다고 버리면 고칠 실마리가 사라지므로, 앞머리만
 * 보이고 나머지는 「자세히」로 펼친다.
 *
 * 자르는 자리는 **쉼표나 빈칸**에서 고른다. 항목 이름 한가운데서 끊기면
 * 없는 이름처럼 읽힌다.
 */
fun shortReasonKo(reason: String, max: Int = REASON_FOLD_AT): String {
    if (reason.length <= max) return reason
    val head = reason.take(max)
    val cut = maxOf(head.lastIndexOf(','), head.lastIndexOf(' '))
    // 자를 자리가 너무 앞이면(긴 낱말 하나뿐) 그냥 길이로 자른다.
    val body = if (cut > max / 2) head.take(cut) else head
    return body.trimEnd(',', ' ') + "…"
}

// ----------------------------------------------------------------------
// 글로 옮기기 — 화면 밖에서 시험할 수 있게 둔다
// ----------------------------------------------------------------------

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

fun profileTimeKo(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    TIME_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(zone))

/**
 * 유효 범위. **모르면 모른다고 적는다** — 「0Hz ~ 0Hz」로 적으면 잰 적
 * 없는 범위가 잰 것처럼 보인다.
 */
fun validRangeKo(p: MeasuredProfile): String {
    val from = p.validFromHz
    val to = p.validToHz
    if (from == null || to == null) return "유효 범위 모름"
    return "${from.toInt()}Hz ~ ${(to / 1000).toInt()}kHz"
}

fun qualityLineKo(p: MeasuredProfile): String {
    val q = p.quality
    val snr = q.worstSnrDb?.let { "최저 SNR ${"%.1f".format(it)}dB" } ?: "SNR 모름"
    val dsp = if (q.dspVerifiedBySignal) "DSP 신호 확인" else "DSP 확인 못 함"
    val ratio = (q.usableBandRatio * 100).toInt()
    return "${q.verdict.labelKo} · 쓸 수 있는 대역 $ratio% · $snr · $dsp"
}

/**
 * 지금 걸리는지 한 줄로.
 *
 * 까닭을 **전부** 적는다. 하나만 적으면 그것을 고쳐도 여전히 안 걸리고,
 * 그때 다음 까닭이 나타나 「고쳐도 소용없다」로 읽힌다.
 */
fun applyLineKo(apply: ProfileApply, reasonsKo: List<String>): String = when (apply) {
    ProfileApply.Apply -> "지금 걸립니다."
    ProfileApply.ApplyWithWarning ->
        "지금 걸립니다. 다만: " + reasonsKo.joinToString(" / ")
    ProfileApply.Block ->
        "지금은 걸리지 않습니다. " + reasonsKo.joinToString(" / ")
}

/** 「지금 경로」 한 줄. 사람이 무엇과 무엇을 견주는지 알아야 한다. */
fun pathSummaryKo(now: ProfileEnvironment): String {
    val where = micPositionKo(now.deviceAddress)
    val name = if (where != null) "${now.micKind.labelKo} ($where)" else now.micKind.labelKo
    val ch = if (now.channelCount > 1) " · ${now.channelIndex + 1}번 채널" else ""
    return "$name · ${now.audioSource.labelKo} · ${now.sampleRate}Hz$ch"
}
