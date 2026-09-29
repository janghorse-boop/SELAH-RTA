package kr.joa.selahrta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.data.rta.RtaComparisonSet
import kr.joa.selahrta.data.rta.RtaConditions
import kr.joa.selahrta.data.rta.RtaDifference
import kr.joa.selahrta.data.rta.RtaMeasurement
import kr.joa.selahrta.ui.theme.SelahColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * **저장해 둔 측정을 골라 지금 그래프에 겹친다**(지시서 §7).
 *
 * > 저장 목적은 같은 위치에서 좌우와 양쪽 출력을 비교하고, 이후 조정 전후
 * > 결과까지 다시 확인하는 것이다.
 *
 * 세트로 묶여 나오고, 곡선마다 켜고 끌 수 있다. **세 채널이 다 없어도
 * 된다** — 한 채널만 잰 세트도 그대로 나온다.
 */
@Composable
fun SavedRtaSheet(
    sets: List<RtaComparisonSet>,
    items: List<RtaMeasurement>,
    shownIds: Set<String>,
    liveVisible: Boolean,
    /** 지금 재고 있는 조건. 저장한 것과 다르면 그 자리에 적는다. */
    currentConditions: RtaConditions?,
    onShown: (String, Boolean) -> Unit,
    onLiveVisible: (Boolean) -> Unit,
    onRenameSet: (String, String) -> Unit,
    onDeleteSet: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(SelahColors.Surface, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("저장된 측정", color = SelahColors.TextPrimary, fontSize = 13.sp)
            TextButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "닫기" }) {
                Text("닫기", color = SelahColors.Accent, fontSize = 12.sp)
            }
        }

        // **실시간 곡선도 끌 수 있다**(담당자 지시 3항). 저장한 둘만 견주고
        // 싶을 때 지금 소리가 위에 덮이면 못 본다.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = liveVisible,
                onCheckedChange = onLiveVisible,
                colors = CheckboxDefaults.colors(checkedColor = SelahColors.Accent),
                modifier = Modifier.semantics { contentDescription = "실시간 곡선 보기" },
            )
            Text("지금 곡선", color = SelahColors.TextPrimary, fontSize = 12.sp)
        }

        // **두 곡선을 켜 두면 차이를 셈해 적는다**(지시서 §7 후속).
        //
        // 31칸을 눈으로 훑어 「어디가 얼마나 벌어졌나」를 찾으라고 하면
        // 좌우를 견주려고 만든 기능이 절반만 된 것이다.
        val shown = items.filter { it.id in shownIds }
        if (shown.size == 2) {
            AutoDifference(shown[0], shown[1])
        }

        if (items.isEmpty()) {
            Text(
                "아직 저장한 측정이 없습니다. 테스트 신호를 틀고 「이 곡선 저장」을 " +
                    "누르면 10초를 평균 내어 남깁니다.",
                color = SelahColors.TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
            return@Column
        }

        LazyColumn(
            Modifier.heightIn(max = 280.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(sets, key = { it.id }) { set ->
                val inSet = items.filter { it.setId == set.id }
                SetBlock(
                    set = set,
                    items = inSet,
                    shownIds = shownIds,
                    currentConditions = currentConditions,
                    onShown = onShown,
                    onRenameSet = onRenameSet,
                    onDeleteSet = onDeleteSet,
                    onDelete = onDelete,
                )
            }
        }
    }
}

@Composable
private fun SetBlock(
    set: RtaComparisonSet,
    items: List<RtaMeasurement>,
    shownIds: Set<String>,
    currentConditions: RtaConditions?,
    onShown: (String, Boolean) -> Unit,
    onRenameSet: (String, String) -> Unit,
    onDeleteSet: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SelahColors.SurfaceVariant)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // 이름을 안 지은 세트도 나온다 — 저장은 됐기 때문이다.
                set.nameKo.ifBlank { "이름 없는 묶음" },
                color = SelahColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onRenameSet(set.id, set.nameKo) },
            )
            TextButton(onClick = { onDeleteSet(set.id) }) {
                Text("묶음 삭제", color = SelahColors.Warn, fontSize = 11.sp)
            }
        }

        for (m in items.sortedBy { it.measuredAtEpochMs }) {
            MeasurementRow(
                m = m,
                shown = m.id in shownIds,
                currentConditions = currentConditions,
                onShown = { onShown(m.id, it) },
                onDelete = { onDelete(m.id) },
            )
        }
    }
}

@Composable
private fun MeasurementRow(
    m: RtaMeasurement,
    shown: Boolean,
    currentConditions: RtaConditions?,
    onShown: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = shown,
                onCheckedChange = onShown,
                colors = CheckboxDefaults.colors(checkedColor = channelColor(m.channel)),
                modifier = Modifier.semantics { contentDescription = "${m.nameKo} 겹쳐 보기" },
            )
            // 색 표 — 그래프의 선과 같은 색이다.
            Column(Modifier.weight(1f)) {
                Text(
                    "${m.nameKo}  ·  ${channelKo(m.channel)}",
                    color = SelahColors.TextPrimary,
                    fontSize = 12.sp,
                )
                Text(
                    // **채널과 측정 시각을 적는다**(담당자 지시 3항).
                    "${timeText(m.measuredAtEpochMs)} · ${m.averagedFrames}장 평균",
                    color = SelahColors.TextMuted,
                    fontSize = 10.sp,
                )
            }
            TextButton(onClick = onDelete) {
                Text("삭제", color = SelahColors.Warn, fontSize = 11.sp)
            }
        }

        // **조건이 다르면 알린다. 막지는 않는다**(설계 7-3).
        //
        // 현장에서는 조건이 조금 다른 것을 알면서도 견줘 봐야 할 때가
        // 있다. 막으면 사람이 앱 밖에서 눈으로 견준다.
        val diff = conditionDiffKo(m.conditions, currentConditions)
        if (diff != null) {
            Text(
                diff,
                color = SelahColors.Warn,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
            )
        }
    }
}

/**
 * 켜 둔 두 곡선의 차이. **셈할 수 없으면 까닭을 적는다.**
 *
 * 숫자를 자동으로 내놓으면 사람은 그것을 믿는다. 그래서 조건을 모르거나
 * 서로 다르면 **셈하지 않고, 왜 안 하는지 적는다**(담당자 지시 기준 5).
 * 아무 말도 없으면 「차이가 없다」로 읽힌다.
 */
@Composable
private fun AutoDifference(a: RtaMeasurement, b: RtaMeasurement) {
    val d = RtaDifference.of(a, b)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SelahColors.SurfaceVariant)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("두 곡선의 차이", color = SelahColors.TextMuted, fontSize = 10.sp)
        if (d == null) {
            Text(
                "측정 조건이 다르거나 ${RtaConditions.UNKNOWN_KO}이라 차이를 셈하지 " +
                    "않았습니다. 겹쳐 보는 것은 그대로 됩니다.",
                color = SelahColors.Warn,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        } else {
            Text(
                d.summaryKo(),
                color = SelahColors.TextPrimary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

/** 채널마다 다른 색. 좌우를 견주려고 겹치는 것이므로 색으로 갈려야 한다. */
fun channelColor(channel: String): Color = when (channel) {
    "Left" -> Color(0xFF4FC3F7)
    "Right" -> Color(0xFFFFB74D)
    else -> Color(0xFF81C784)
}

fun channelKo(channel: String): String = when (channel) {
    "Left" -> "왼쪽"
    "Right" -> "오른쪽"
    "Both" -> "양쪽"
    else -> RtaConditions.UNKNOWN_KO
}

private fun timeText(epochMs: Long): String =
    SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(epochMs))

/**
 * 저장한 조건과 지금 조건이 다른가. **다른 것만 적는다.**
 *
 * **모르는 것(미확인)은 「다르다」고 하지 않는다**(담당자 지시 기준 5) —
 * 옛 판으로 저장해 조건이 안 적힌 것을 「조건이 다르다」고 하면, 사람은
 * 있지도 않은 차이를 찾게 된다. 대신 **모른다고 적는다.**
 */
fun conditionDiffKo(saved: RtaConditions, current: RtaConditions?): String? {
    if (saved.hasUnknown) {
        return "이 기록은 측정 조건이 ${RtaConditions.UNKNOWN_KO}입니다. 견줄 때 감안하십시오."
    }
    if (current == null) return null

    val diffs = buildList {
        if (current.inputKey != null && saved.inputKey != current.inputKey) add("마이크")
        if (current.calibrationState != null && saved.calibrationState != current.calibrationState) {
            add("보정 상태")
        }
        if (current.calibrationSource != null &&
            saved.calibrationSource != current.calibrationSource
        ) {
            add("보정 출처")
        }
        if (current.curveName != null && saved.curveName != current.curveName) add("보정 곡선")
        if (current.fftSize != null && saved.fftSize != current.fftSize) add("FFT 길이")
        if (current.sampleRate != null && saved.sampleRate != current.sampleRate) add("표본율")
    }
    if (diffs.isEmpty()) return null
    return "지금과 ${diffs.joinToString("·")}이(가) 다릅니다."
}
