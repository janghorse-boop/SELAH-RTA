package kr.joa.selahrta.ui.instrument

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.data.instrument.InstrumentCatalog
import kr.joa.selahrta.data.instrument.Sources
import kr.joa.selahrta.domain.instrument.EqBandHighlight
import kr.joa.selahrta.domain.instrument.FilterGuidance
import kr.joa.selahrta.domain.instrument.FrequencyRegion
import kr.joa.selahrta.domain.instrument.InstrumentProfile
import kr.joa.selahrta.domain.instrument.InstrumentType
import kr.joa.selahrta.ui.CaptureUiState
import kr.joa.selahrta.ui.components.AxisMode
import kr.joa.selahrta.ui.components.BandMeter
import kr.joa.selahrta.ui.components.rememberAxisRange
import kr.joa.selahrta.ui.components.rtaTopSpl
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * **악기 EQ 가이드** — 한 악기·한 대역씩 믹서에서 직접 조정하며 익히는
 * 실습 화면(담당자 지시서 2026-09-30).
 *
 * ## 이 화면이 하는 일과 안 하는 일
 *
 * **EQ 값을 대신 정해 주는 도구가 아니다.** 사용자가 **믹서에서 직접**
 * EQ 를 돌리면서 위쪽 RTA 와 실제 귀의 변화를 함께 익히도록 돕는다.
 * 앱은 **분석과 안내만** 한다 — 믹서를 원격으로 만지지 않는다.
 *
 * **그래프가 높거나 낮다는 것만으로 올리거나 내리라고 말하지 않는다**
 * (지시서 §15). 같은 대역이 바디를 만들기도 하고 먹먹함을 만들기도 하며,
 * 악기·연주·마이크·PA·룸·다른 악기·자리·장르가 모두 관여한다.
 *
 * ## 위는 고정, 아래만 구른다
 *
 * RTA 를 위에 **붙박아** 두는 것이 이 개편의 핵심이다. 카드를 읽으려고
 * 내리는 동안 그래프가 사라지면 **「조정하면서 본다」가 성립하지 않는다.**
 *
 * ## 측정이 꺼져 있어도 표는 그대로 열린다
 *
 * 이 화면은 원래 **마이크 없이도 열리는 표**였다(명세 §1 원칙 2). 위에
 * RTA 를 얹는다고 그 성질을 버리지 않는다 — 측정이 꺼져 있으면 **가짜
 * 그래프를 그리는 대신** 꺼져 있다고 적고 시작 단추를 둔다. 아래 가이드는
 * 평소대로 읽힌다(담당자 확인 2026-09-30).
 *
 * ## 게이트·컴프레서는 이 화면에서 뺐다
 *
 * 지시서 §34 대로 **이 화면에서만** 뺀다. 카탈로그의 게이트·컴프레서
 * 자료는 **그대로 둔다** — 명세 §15 가 6종 전부에 그 안내를 요구하고,
 * 지우면 다른 곳에서 되살릴 수 없다.
 */
@Composable
fun InstrumentGuideScreen(
    capture: CaptureUiState,
    /** 측정이 꺼져 있을 때 누르는 단추. */
    onStartMeasure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var type by rememberSaveable { mutableStateOf(InstrumentType.ACOUSTIC_GUITAR) }
    var stableId by rememberSaveable { mutableStateOf("acoustic_guitar.strum") }
    var regionId by rememberSaveable { mutableStateOf<String?>(null) }

    val profiles = InstrumentCatalog.byType(type)
    val profile = profiles.firstOrNull { it.stableId == stableId } ?: profiles.first()
    // **고른 것이 이 악기에 없으면 첫 칸으로 돌아간다.** 악기를 바꾸면
    // 영역 id 도 함께 바뀌므로, 안 그러면 아무 카드도 안 고른 상태가 된다.
    val region = profile.regions.firstOrNull { it.id == regionId } ?: profile.regions.first()
    // **열쇠는 `id` 가 아니라 `range` 다**(독립 검토 PND-04).
    //
    // `id` 는 **한 프로필 안에서만** 유일하다. 신디사이저의 피아노/EP 와
    // 리드/브라스는 둘 다 `body` 라는 이름을 쓰는데 범위가 60~200Hz 와
    // 150~600Hz 로 다르다. `id` 로 기억해 두면 프로필을 바꿔도 **띠가 그
    // 자리에 그대로 있고**, 사람은 **새 설명을 읽으며 옛 주파수를 본다.**
    //
    // 글은 바뀌고 그림만 안 바뀌므로 **눈으로는 알아채기 어렵다** —
    // 검토자가 차트 한 행의 픽셀을 견주어 찾았다.
    val highlight = remember(region.range) { EqBandHighlight.bandsOf(region.range) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // **화면 높이의 3할 안팎**(지시서 §6). 고정 높이로 못박으면 작은
        // 기기에서 카드가 한 장도 안 보인다.
        val chartHeight = (maxHeight * 0.26f).coerceIn(110.dp, 190.dp)

        Column(Modifier.fillMaxSize()) {
            // ── 위: 붙박이 ──────────────────────────────
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(SelahColors.Background)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // **보정 상태는 여기서 다시 적지 않는다**(지시서 §28).
                //
                // 앱 머리말에 이미 붙박이로 붙어 있다. 한 화면에 「미보정」이
                // 두 번 보이면 **서로 다른 것을 말하는 줄 알게 된다** — 기기에서
                // 찍어 보고 알았다. §28 이 요구하는 「상단에 계속 표시」는 그
                // 붙박이가 이미 충족한다.
                Text(
                    "${profile.type.nameKo} · ${profile.nameKo}",
                    color = SelahColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp),
                )

                if (capture.rta == null) {
                    MeasurementOffPanel(onStartMeasure)
                } else {
                    // 축은 분석 화면과 **같은 방식**으로 잡는다 — 여기서
                    // 따로 정하면 같은 소리가 화면마다 다른 높이로 보인다.
                    val (floorDb, ceilDb) = rememberAxisRange(AxisMode.Auto, rtaTopSpl(capture.rta))
                    BandMeter(
                        rta = capture.rta,
                        floorDb = floorDb,
                        ceilDb = ceilDb,
                        chartHeight = chartHeight,
                        showHold = false,
                        highlightBands = highlight,
                    )
                }

                ActiveBandSummary(region)
            }

            // ── 아래: 구르는 곳 ─────────────────────────
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 안내와 실습 팁을 **한 칸에** 둔다. 두 칸으로 나누었더니
                // 카드가 한 장도 안 보이는 높이까지 밀렸다(기기에서 확인).
                item {
                    Notice(
                        "악기별 주요 EQ 포인트를 확인하면서 믹서 EQ를 직접 조정해 보세요. " +
                            "상단 RTA와 실제 청감의 변화를 함께 비교하되, 최종 판단은 귀로 하시기 바랍니다. " +
                            "한 번에 한 대역씩 작은 폭으로 조정하고, 조정 전·후를 반복해서 들어보세요.",
                    )
                }

                item {
                    Label("악기")
                    ChipRow(
                        items = InstrumentType.entries.map { it to it.nameKo },
                        selected = type,
                        onSelect = {
                            type = it
                            stableId = InstrumentCatalog.byType(it).first().stableId
                            regionId = null
                        },
                    )
                }

                // **고를 것이 하나뿐이면 고르개를 안 그린다**(지시서 §9).
                // 칩 한 개가 덩그러니 떠서 눌러도 아무 일이 없으면 고장처럼
                // 보인다 — 이 저장소가 위 칩에서 이미 배운 것이다.
                if (profiles.size > 1) {
                    item {
                        Label(
                            if (type == InstrumentType.ELECTRONIC_DRUMS ||
                                type == InstrumentType.ACOUSTIC_DRUMS
                            ) {
                                "파트"
                            } else {
                                "세부 유형"
                            },
                        )
                        ChipRow(
                            items = profiles.map { it.stableId to it.nameKo },
                            selected = profile.stableId,
                            onSelect = { stableId = it; regionId = null },
                        )
                    }
                }

                item { Label("EQ 포인트") }
                items(profile.regions, key = { it.id }) { r ->
                    EqPointCard(r, r.id == region.id) { regionId = r.id }
                }

                item {
                    Label("필터 청취 시작점")
                    Notice(
                        "HPF·LPF 의 숫자는 보조 마커입니다. 활성 필터도, 실제 적용된 EQ 곡선도 " +
                            "아닙니다. 차단주파수는 완전 제거 경계가 아니며 기본 기울기 안내는 12 dB/oct 입니다.",
                    )
                }
                item { FilterCard(profile.hpf) }
                item { FilterCard(profile.lpf) }

                item {
                    Label("이 악기에서 EQ 보다 먼저 볼 것")
                    Card { profile.cautions.forEach { Bullet(it) } }
                }
                item { SourceCard(profile) }
                item {
                    Text(
                        "카탈로그 ${InstrumentCatalog.VERSION}",
                        color = SelahColors.TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 20.dp),
                    )
                }
            }
        }
    }
}

/**
 * 측정이 꺼져 있을 때 RTA 자리에 놓는 것.
 *
 * **빈 그래프를 그리지 않는다.** 0dB 막대가 스물한 개 늘어서 있으면 그것을
 * 「지금 소리가 없다」로 읽는다 — 실제로는 **재고 있지 않다**는 뜻인데
 * 둘은 아주 다른 말이다.
 */
@Composable
private fun MeasurementOffPanel(onStartMeasure: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SelahColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("측정이 꺼져 있습니다", color = SelahColors.TextPrimary, fontSize = 14.sp)
        Text(
            "아래 EQ 포인트는 그대로 읽을 수 있습니다. 지금 소리와 함께 보려면 측정을 시작하세요.",
            color = SelahColors.TextSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        TextButton(onClick = onStartMeasure) {
            Text("측정 시작", color = SelahColors.Accent, fontSize = 13.sp)
        }
    }
}

/**
 * 지금 고른 대역의 요약(지시서 §4.1).
 *
 * **숫자와 역할만 적는다.** 「이 구간 에너지가 높습니다」 같은 판정은
 * 넣지 않는다 — 문턱 하나로 단정하면 그것이 곧 처방으로 읽힌다(§14·§15).
 */
@Composable
private fun ActiveBandSummary(r: FrequencyRegion) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(SelahColors.SurfaceVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("현재 포인트", color = SelahColors.TextMuted, fontSize = 11.sp)
        Text(
            r.range.labelKo(),
            color = SelahColors.Accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (r.characterTags.isNotEmpty()) {
            Text(
                r.characterTags.joinToString(" · "),
                color = SelahColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * EQ 포인트 카드 하나.
 *
 * 역할·많을 때·조정 팁·주의를 **한 장에 모은다**(지시서 §11) — 예전에는
 * 「전부 보기 / 증상만」으로 갈라 두었는데, 실습 중에 필터를 오가게 만드는
 * 것보다 한 장에 있는 편이 낫다.
 *
 * **「많을 때」는 있는 자료에서 온다**(담당자 확인 2026-09-30). 카탈로그의
 * **증상 태그**가 곧 그 말이다 — 먹먹함·붕붕거림·날카로움은 그 대역이
 * 과할 때 나오는 말이다. 없던 문장을 새로 지어 붙이지 않는다.
 */
@Composable
private fun EqPointCard(r: FrequencyRegion, selected: Boolean, onSelect: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SelahColors.Surface, RoundedCornerShape(12.dp))
            // **고른 것을 색으로만 말하지 않는다**(지시서 §32). 테두리가
            // 굵어지고 「보는 중」이라고 적힌다.
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) SelahColors.Accent else SelahColors.Outline,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onSelect)
            .padding(12.dp)
            .semantics {
                contentDescription =
                    if (selected) "${r.range.labelKo()} 보는 중" else r.range.labelKo()
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                r.range.labelKo(),
                color = SelahColors.Accent,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (selected) TagBadge("보는 중", SelahColors.Accent)
        }
        if (r.characterTags.isNotEmpty()) Field("역할", r.characterTags.joinToString(" · "))
        Body(r.explanationKo)
        if (r.symptomTags.isNotEmpty()) {
            Field(
                "많을 때 나오는 말",
                r.symptomTags.joinToString(" · ") + " — 이 구간이 과할 때 흔히 쓰는 표현입니다.",
            )
        }
        Field("조정 팁", r.listenConditionKo)
        Field("바꿀 때의 부작용", r.cautionKo)
    }
}

@Composable
private fun FilterCard(f: FilterGuidance) {
    Card {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                f.filterType.labelKo,
                color = SelahColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (f.bypassDefault) TagBadge("기본: bypass", SelahColors.TextMuted)
        }
        val range = f.rangeHz
        if (range == null) {
            Body("시작점 숫자가 없습니다.")
        } else {
            Text(
                buildString {
                    append(range.labelKo())
                    f.altRangeHz?.let { append("  ·  ").append(it.labelKo()) }
                },
                color = SelahColors.Accent,
                fontSize = 15.sp,
            )
        }
        Field("언제", f.conditionKo)
        Field("부작용", f.cautionKo)
        Body("기울기: " + f.slopeOptionsDbOct.joinToString(" / ") { "$it dB/oct" } + " (기본 12)")
    }
}

@Composable
private fun SourceCard(p: InstrumentProfile) {
    Card {
        Text("근거 자료", color = SelahColors.TextSecondary, fontSize = 12.sp)
        p.sourceIds.forEach { id ->
            Sources.titles[id]?.let { (title, url) ->
                Text(title, color = SelahColors.TextSecondary, fontSize = 12.sp)
                Text(url, color = SelahColors.TextMuted, fontSize = 10.sp)
            }
        }
        Body(
            "위 자료는 실무 교육 자료이며 이 앱의 수치 조합을 보증하지 않습니다. " +
                "여기의 범위는 제품의 청취 탐색용 제안입니다.",
        )
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SelahColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = SelahColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Notice(text: String) {
    Text(
        text.replace("**", ""),
        color = SelahColors.TextSecondary,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(SelahColors.SurfaceVariant, RoundedCornerShape(10.dp))
            .padding(12.dp),
    )
}

@Composable
private fun Body(text: String) {
    Text(text.replace("**", ""), color = SelahColors.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("·", color = SelahColors.TextMuted, fontSize = 13.sp)
        Text(text, color = SelahColors.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = SelahColors.TextMuted, fontSize = 11.sp)
        Text(value.replace("**", ""), color = SelahColors.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun ValueRow(label: String, value: String?) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = SelahColors.TextMuted, fontSize = 12.sp)
        Text(
            value ?: "사용 안 함",
            color = if (value == null) SelahColors.TextMuted else SelahColors.TextPrimary,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun TagLine(label: String, tags: List<String>, color: androidx.compose.ui.graphics.Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = SelahColors.TextMuted, fontSize = 11.sp)
        Text(tags.joinToString(" · "), color = color, fontSize = 12.sp)
    }
}

@Composable
private fun TagBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        modifier = Modifier
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(items: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    // **폭에 맞춰 접힌다.** 예전에는 `chunked(3)` 으로 줄을
    // 고정해 두고 주석에만 「줄바꿈된다」고 적어 두었다 — 글자가
    // 커지거나 화면이 좁으면 칩이 밖으로 밀린다(독립 검증 EQB 마무리).
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { (value, label) ->
            Chip(label, value == selected) { onSelect(value) }
        }
    }
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) SelahColors.Background else SelahColors.TextSecondary,
        fontSize = 12.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .widthIn(min = 56.dp)
            .background(
                if (active) SelahColors.Accent else SelahColors.SurfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .semantics { contentDescription = if (active) "$label 선택됨" else label },
    )
}
