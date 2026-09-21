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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.domain.ChurchSegment
import kr.joa.selahrta.domain.DefaultSegmentRanges
import kr.joa.selahrta.domain.SegmentRange
import kr.joa.selahrta.domain.focusKo
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * 구간별 참고 범위를 보여 주고 고치게 한다(명세 10장, 컨셉 화면 4번).
 *
 * **고칠 수 있어야 한다는 것이 명세의 요구다.** 예배당 크기·잔향·회중 수에
 * 따라 알맞은 값이 달라지는데, 고정값이면 어느 예배당에서는 늘 「높음」이
 * 뜨고 담당자는 곧 그 표시를 무시하게 된다.
 */
@Composable
fun SegmentRangeCard(
    segment: ChurchSegment,
    range: SegmentRange,
    isCustom: Boolean,
    onSave: (SegmentRange) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember(segment) { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(SelahColors.Surface, RoundedCornerShape(10.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    segment.labelKo,
                    color = SelahColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (isCustom) {
                    Text(
                        "  고친 값",
                        color = SelahColors.Accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            TextButton(onClick = { editing = !editing }) {
                Text(
                    if (editing) "접기" else "고치기",
                    color = SelahColors.Accent,
                    fontSize = 12.sp,
                )
            }
        }

        Text(
            "권장 평균 ${range.avgLowDb.toInt()} ~ ${range.avgHighDb.toInt()} dBA",
            color = SelahColors.TextSecondary,
            fontSize = 12.sp,
        )
        Text(
            // 「피크」라고만 적으면 PEAK 타일과 견주게 되는데, 그쪽은 가중 전
            // 파형의 최대라 dBA 가 아니다(독립 검증 R10). 어느 숫자와 견줄
            // 값인지 이름으로 못박는다.
            "짧은 최대(MAX) ${range.peakLowDb.toInt()} ~ ${range.peakHighDb.toInt()} dBA",
            color = SelahColors.TextSecondary,
            fontSize = 12.sp,
        )
        Text(segment.focusKo, color = SelahColors.TextMuted, fontSize = 11.sp, lineHeight = 15.sp)

        if (editing) {
            RangeEditor(
                range = range,
                canReset = isCustom,
                onSave = { onSave(it); editing = false },
                onReset = { onReset(); editing = false },
                onDefault = { DefaultSegmentRanges.of(segment) },
            )
        }
    }
}

@Composable
private fun RangeEditor(
    range: SegmentRange,
    canReset: Boolean,
    onSave: (SegmentRange) -> Unit,
    onReset: () -> Unit,
    onDefault: () -> SegmentRange?,
) {
    var avgLow by remember { mutableStateOf(range.avgLowDb.toInt().toString()) }
    var avgHigh by remember { mutableStateOf(range.avgHighDb.toInt().toString()) }
    var peakLow by remember { mutableStateOf(range.peakLowDb.toInt().toString()) }
    var peakHigh by remember { mutableStateOf(range.peakHighDb.toInt().toString()) }

    val edited = listOf(avgLow, avgHigh, peakLow, peakHigh).map { it.trim().toDoubleOrNull() }
    val candidate = if (edited.none { it == null }) {
        SegmentRange(edited[0]!!, edited[1]!!, edited[2]!!, edited[3]!!)
    } else {
        null
    }
    // 저장하기 **전에** 말이 되는지 보여 준다. 저장 뒤에 거절당하면
    // 무엇이 잘못됐는지 알기 어렵다.
    val sane = candidate?.isSane == true

    Column(
        Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("평균 아래", avgLow, { avgLow = it }, Modifier.weight(1f))
            NumberField("평균 위", avgHigh, { avgHigh = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("피크 아래", peakLow, { peakLow = it }, Modifier.weight(1f))
            NumberField("피크 위", peakHigh, { peakHigh = it }, Modifier.weight(1f))
        }

        if (candidate != null && !sane) {
            Text(
                "값이 서로 맞지 않습니다. 아래값 < 위값 이어야 하고, " +
                    "피크 위값이 평균 위값보다 커야 합니다.",
                color = SelahColors.Warn,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { candidate?.let(onSave) }, enabled = sane) {
                Text(
                    "저장",
                    color = if (sane) SelahColors.Accent else SelahColors.TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TextButton(
                onClick = {
                    onDefault()?.let {
                        avgLow = it.avgLowDb.toInt().toString()
                        avgHigh = it.avgHighDb.toInt().toString()
                        peakLow = it.peakLowDb.toInt().toString()
                        peakHigh = it.peakHighDb.toInt().toString()
                    }
                    onReset()
                },
                enabled = canReset,
            ) {
                Text(
                    "기본값으로",
                    color = if (canReset) SelahColors.TextSecondary else SelahColors.TextMuted,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 10.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = SelahColors.TextPrimary,
            unfocusedTextColor = SelahColors.TextPrimary,
            focusedBorderColor = SelahColors.Accent,
            unfocusedBorderColor = SelahColors.Outline,
            focusedLabelColor = SelahColors.Accent,
            unfocusedLabelColor = SelahColors.TextMuted,
            cursorColor = SelahColors.Accent,
        ),
        modifier = modifier,
    )
}
