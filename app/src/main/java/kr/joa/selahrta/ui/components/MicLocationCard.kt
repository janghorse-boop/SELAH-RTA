package kr.joa.selahrta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.micdb.MicDeviceCategory
import kr.joa.selahrta.micdb.MicLocationDb
import kr.joa.selahrta.micdb.MicLocationMatch
import kr.joa.selahrta.micdb.MicModel
import kr.joa.selahrta.micdb.manualMicLocations
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * **내장 마이크 위치 안내**(`docs/data/galaxy-mic-location-db/`).
 *
 * 폰을 어디에 놓아야 하는지 알려 주는 것이 전부다. 마이크 구멍이 벽을
 * 보고 있으면 그 측정은 시작부터 틀린다.
 *
 * ## 세 가지를 섞지 않는다
 *
 * 자료 제공자가 못박은 경계다(README 5A·5E). 이 카드는 **도면에 적힌
 * 자리**만 말한다.
 *
 * - 「지금 녹음에 쓰이는 마이크」는 **캡처 진단**이 말한다. 안드로이드가
 *   실행 중에 알려 주는 것이고, 이 DB 와 이어 붙이지 않는다.
 * - 「이 기기의 감도」는 **보정**이 말한다. 자리 수나 자리가 달라졌다고
 *   음압 보정값을 만들지 않는다.
 *
 * ## 쓰지 않는 말
 *
 * 「이 기기의 전체 마이크: N개」라고 적지 않는다. 도면에 자리가 둘
 * 보인다고 안쪽 부품이 둘이라는 뜻이 아니다 — 원본이 그 둘을 갈라
 * 두었으므로 화면도 가른다.
 */
@Composable
fun MicLocationCard(
    db: MicLocationDb?,
    match: MicLocationMatch?,
    modifier: Modifier = Modifier,
) {
    // **자료를 못 읽었으면 조용히 빈 카드를 띄우지 않는다.** 없는 것과
    // 못 읽은 것은 다르고, 둘 다 아무 말도 안 하면 구별되지 않는다.
    if (db == null) return
    var manual by remember { mutableStateOf<MicLocationMatch?>(null) }
    val shown = manual ?: match

    Column(
        modifier
            .fillMaxWidth()
            .background(SelahColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "내장 마이크 위치 안내",
            color = SelahColors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "삼성 공식 도면에서 마이크라고 적힌 자리를 옮겨 적은 것입니다. " +
                "재기 전에 그 구멍이 소리 쪽을 보게 두십시오 — 손이나 책상으로 " +
                "가리면 그 측정은 시작부터 틀립니다.",
            color = SelahColors.TextMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        when (shown) {
            is MicLocationMatch.Documented -> ModelBlock(
                model = shown.model,
                db = db,
                matchKo = "지금 기기의 모델코드(${shown.matchedCode})와 문서의 코드가 같습니다.",
                warn = false,
            )

            is MicLocationMatch.ManualReference -> ModelBlock(
                model = shown.model,
                db = db,
                // **고른 것이 확인이 되지는 않는다**(README 5B).
                matchKo = "제품명을 직접 고르셨습니다. 참고 도면이며, 지금 기기와 " +
                    "같은지는 확인된 것이 아닙니다.",
                warn = true,
            )

            is MicLocationMatch.Unknown -> InfoBar(shown.reasonKo, tone = SelahColors.Warn)

            null -> Text(
                "측정을 시작하면 이 기기의 자리를 찾아봅니다.",
                color = SelahColors.TextMuted,
                fontSize = 11.sp,
            )
        }

        ManualPicker(db) { manual = manualMicLocations(db, it) }

        if (manual != null) {
            TextButton(onClick = { manual = null }) {
                Text("이 기기 자동 안내로 돌아가기", color = SelahColors.Accent, fontSize = 12.sp)
            }
        }

        Text(
            "자료 확인 기준일 ${db.checkedOn} · 실기기로 확인한 것이 아니라 문서를 읽은 것입니다. " +
                "지금 녹음에 어느 마이크가 쓰이는지는 「캡처 진단」의 「활성 마이크」가 말합니다.",
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun ModelBlock(
    model: MicModel,
    db: MicLocationDb,
    matchKo: String,
    warn: Boolean,
) {
    Text(
        model.marketingName,
        color = SelahColors.TextPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
    )
    // **「공식 도면에서 확인된 마이크 위치: N곳」** — 원본이 정한 문구다.
    // 「전체 마이크 N개」라고 적으면 안쪽 부품 수를 말하는 것이 된다.
    Text(
        "공식 도면에서 확인된 마이크 위치: ${model.documentedLocationCount}곳",
        color = SelahColors.TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
    )
    InfoBar(matchKo, tone = if (warn) SelahColors.Warn else SelahColors.TextSecondary)

    // **어느 방향으로 놓고 본 기준인지 먼저 적는다.** 「상단」은 기기를
    // 어떻게 들었느냐에 따라 딴 곳이 된다 — 태블릿은 가로가 기준이고
    // S6 Lite 만 세로다.
    db.orientations[model.orientationId]?.let {
        Text(
            "보는 기준: $it",
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
            lineHeight = 15.sp,
        )
    }

    model.locations.forEach { loc ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                loc.surface.labelKo,
                color = SelahColors.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                softWrap = false,
            )
            Text(
                loc.descriptionKo,
                color = SelahColors.TextSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.weight(1f),
            )
        }
    }

    // **에어벤트를 마이크로 읽지 않게 한다.** 도면에 함께 있는 구멍이고,
    // 세대마다 자리가 다르다.
    Text(
        "기기에 보이는 구멍이 모두 마이크는 아닙니다 — 공기구멍·스피커·유심 " +
            "트레이 구멍이 함께 있습니다. 위에 적힌 자리만 마이크로 확인된 것입니다.",
        color = SelahColors.TextMuted,
        fontSize = 10.sp,
        lineHeight = 15.sp,
    )

    val srcs = model.sourceIds.mapNotNull { db.sourceById(it) }
    if (srcs.isNotEmpty()) {
        Text(
            "자료: " + srcs.joinToString(" · ") { "${it.author} ${it.title} (${it.hostingKo})" },
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
            lineHeight = 15.sp,
        )
    }
}

/**
 * 제품명을 손으로 고른다.
 *
 * **자동 조회가 안 되는 기종이 많다.** S23 세 기종은 국내 제품명별 자리는
 * 확인됐지만 모델코드를 대조하지 못했고, 그런 기종을 억지로 맞추면 다른
 * 세대의 자리를 이 기기의 것이라고 말하게 된다. 그래서 자동으로는 모른다고
 * 하고, **사람이 고를 길만** 열어 둔다.
 */
@Composable
private fun ManualPicker(db: MicLocationDb, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { open = true }) {
            Text("제품명으로 찾아보기", color = SelahColors.Accent, fontSize = 12.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MicDeviceCategory.entries.forEach { cat ->
                val ms = db.models.filter { it.category == cat }
                if (ms.isEmpty()) return@forEach
                DropdownMenuItem(
                    enabled = false,
                    text = { Text(cat.labelKo, color = SelahColors.TextMuted, fontSize = 11.sp) },
                    onClick = {},
                )
                ms.forEach { m ->
                    DropdownMenuItem(
                        text = {
                            Text(m.marketingName, color = SelahColors.TextPrimary, fontSize = 13.sp)
                        },
                        onClick = { open = false; onPick(m.modelId) },
                    )
                }
            }
        }
    }
}
