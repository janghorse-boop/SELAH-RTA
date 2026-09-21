package kr.joa.selahrta.ui.instrument

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import kr.joa.selahrta.domain.instrument.CompressorGuidance
import kr.joa.selahrta.domain.instrument.DynamicsGuidance
import kr.joa.selahrta.domain.instrument.FilterGuidance
import kr.joa.selahrta.domain.instrument.FrequencyRegion
import kr.joa.selahrta.domain.instrument.GateGuidance
import kr.joa.selahrta.domain.instrument.InstrumentProfile
import kr.joa.selahrta.domain.instrument.InstrumentType
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * 악기 EQ·게이트·컴프레서 가이드(명세 §6·§18.5). **EQ-B 정적 부분.**
 *
 * **마이크가 없어도 열린다.** 권한도, 캡처도, DSP 도 건드리지 않는다 —
 * 이 화면은 카탈로그만 읽는다(명세 §1 원칙 2, §18.1).
 *
 * **측정·RTA 오버레이·Before/After 는 여기 없다.** EQ 탭의 오버레이는
 * EQ-C, 비교는 EQ-D 다. 게이트·컴프레서 탭에는 **끝까지 넣지 않는다**
 * (명세 §18.5).
 */
@Composable
fun InstrumentGuideScreen(modifier: Modifier = Modifier) {
    var type by rememberSaveable { mutableStateOf(InstrumentType.ACOUSTIC_GUITAR) }
    var stableId by rememberSaveable { mutableStateOf("acoustic_guitar.strum") }
    var tab by rememberSaveable { mutableStateOf(GuideTab.Eq) }
    var symptomOnly by rememberSaveable { mutableStateOf(false) }

    val profiles = InstrumentCatalog.byType(type)
    val profile = profiles.firstOrNull { it.stableId == stableId } ?: profiles.first()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "악기 EQ 가이드",
            color = SelahColors.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp),
        )

        // 명세 §3 의 고정 문구. 첫 진입과 도움말에 늘 같은 말이 나온다.
        Notice(
            "이 가이드는 들어볼 주파수 영역을 안내합니다. 표시된 영역이 높거나 낮다고 " +
                "반드시 문제가 있는 것은 아닙니다. 같은 위치·입력으로 비교하고, 마지막에는 " +
                "전체 합주를 들으며 판단하세요. EQ는 믹서에서 직접 조정합니다.",
        )

        Label("악기")
        ChipRow(
            items = InstrumentType.entries.map { it to it.nameKo },
            selected = type,
            onSelect = {
                type = it
                stableId = InstrumentCatalog.byType(it).first().stableId
            },
        )

        Label(if (type == InstrumentType.ELECTRONIC_DRUMS || type == InstrumentType.ACOUSTIC_DRUMS) "파트" else "세부 유형")
        ChipRow(
            items = profiles.map { it.stableId to it.nameKo },
            selected = profile.stableId,
            onSelect = { stableId = it },
        )

        // 이름표가 없으면 바로 위의 「세부 유형」 줄과 한 덩어리로 읽힌다.
        // 기기에서 보고 알았다.
        Label("무엇을 볼까요")
        ChipRow(
            items = GuideTab.entries.map { it to it.labelKo },
            selected = tab,
            onSelect = { tab = it },
        )

        when (tab) {
            GuideTab.Eq -> EqTab(profile, symptomOnly) { symptomOnly = it }
            GuideTab.Gate -> DynamicsTab(profile.gate)
            GuideTab.Compressor -> DynamicsTab(profile.compressor)
        }

        SourceCard(profile)
        Text(
            "카탈로그 ${InstrumentCatalog.VERSION}",
            color = SelahColors.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 20.dp),
        )
    }
}

enum class GuideTab(val labelKo: String) {
    Eq("EQ"), Gate("게이트"), Compressor("컴프레서")
}

// ----------------------------------------------------------------------
// EQ 탭
// ----------------------------------------------------------------------

@Composable
private fun EqTab(profile: InstrumentProfile, symptomOnly: Boolean, onSymptomOnly: (Boolean) -> Unit) {
    val regions = if (symptomOnly) profile.regions.filter { it.symptomTags.isNotEmpty() } else profile.regions

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("전부 보기", !symptomOnly) { onSymptomOnly(false) }
        Chip("증상만", symptomOnly) { onSymptomOnly(true) }
    }

    if (regions.isEmpty()) {
        // 0 dB 막대나 빈 카드 대신 왜 없는지 적는다(명세 §6.3).
        Notice("이 항목에는 증상으로 분류한 영역이 없습니다. 「전부 보기」로 성격 영역을 확인하세요.")
    }
    regions.forEach { RegionCard(it) }

    Label("필터 청취 시작점")
    Notice(
        "HPF·LPF 의 숫자는 **보조 마커**입니다. 활성 필터도, 실제 적용된 EQ 곡선도 " +
            "아닙니다. 차단주파수는 완전 제거 경계가 아니며 기본 기울기 안내는 12 dB/oct 입니다.",
    )
    FilterCard(profile.hpf)
    FilterCard(profile.lpf)

    Label("이 악기에서 EQ 보다 먼저 볼 것")
    Card { profile.cautions.forEach { Bullet(it) } }
}

@Composable
private fun RegionCard(r: FrequencyRegion) {
    // 성격은 청록, 증상은 황색(명세 §6.1). 초록·빨강으로 좋고 나쁨을
    // 판정하지 않는다.
    val accent = if (r.symptomTags.isNotEmpty()) SelahColors.Warn else SelahColors.Accent
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(r.range.labelKo(), color = accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (r.symptomTags.isNotEmpty()) TagBadge("확인 후보", SelahColors.Warn)
        }
        if (r.characterTags.isNotEmpty()) {
            TagLine("성격", r.characterTags, SelahColors.Accent)
        }
        if (r.symptomTags.isNotEmpty()) {
            TagLine("증상", r.symptomTags, SelahColors.Warn)
        }
        Body(r.explanationKo)
        Field("들어볼 조건", r.listenConditionKo)
        Field("바꿀 때의 부작용", r.cautionKo)
    }
}

@Composable
private fun FilterCard(f: FilterGuidance) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(f.filterType.labelKo, color = SelahColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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

// ----------------------------------------------------------------------
// 게이트·컴프레서 탭 (§18.5) — 측정도 비교도 없다
// ----------------------------------------------------------------------

@Composable
private fun DynamicsTab(d: DynamicsGuidance) {
    Notice(
        "추천값에서 시작해 믹서에서 직접 조정하고 들어보세요. 장비와 연주에 따라 적절한 값이 달라집니다. " +
            "평균 RTA 로는 attack·release·threshold·실제 gain reduction 을 정할 수 없습니다.",
    )
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(d.processorType.labelKo, color = SelahColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            TagBadge(
                if (d.bypassDefault) "기본: 사용 안 함" else "기본: 사용",
                if (d.bypassDefault) SelahColors.TextMuted else SelahColors.Accent,
            )
        }
        Body(d.usageConditionKo)
    }

    d.gate?.let { GateCard(it) }
    d.compressor?.let { CompressorCard(it) }

    Card {
        Text("주의", color = SelahColors.TextSecondary, fontSize = 12.sp)
        d.cautionsKo.forEach { Bullet(it) }
    }
}

@Composable
private fun GateCard(g: GateGuidance) {
    Card {
        if (!g.hasNumbers) {
            Body("이 항목에는 숫자 프리셋을 주지 않습니다. bypass 에서 출발하세요.")
        } else {
            ValueRow("Attack", g.attackMs?.labelKo())
            ValueRow("Hold", g.holdMs?.labelKo())
            ValueRow("Release", g.releaseMs?.labelKo())
            ValueRow("Range(감쇠량)", g.attenuationDb?.labelKo())
            ValueRow("Hysteresis", g.hysteresisDb?.labelKo())
            Body("Range 는 닫혔을 때의 **감쇠량**이며 양수로 적습니다. 믹서의 음수 표기와 다를 수 있습니다.")
        }
        Field("Threshold 맞추는 방법", g.thresholdProcedureKo)
    }
}

@Composable
private fun CompressorCard(c: CompressorGuidance) {
    Card {
        if (!c.hasNumbers) {
            Body("이 항목에는 숫자 프리셋을 주지 않습니다. bypass 에서 출발하세요.")
        } else {
            ValueRow("Ratio", c.ratio?.labelKo())
            ValueRow("Attack", c.attackMs?.labelKo())
            ValueRow("Release", c.releaseMs?.labelKo())
            ValueRow("Knee", c.kneeHintKo)
            ValueRow("큰 음에서 GR 참고", c.grReferenceDb?.labelKo())
        }
        Field("Threshold 맞추는 방법", c.thresholdProcedureKo)
        Field("Makeup", c.makeupProcedureKo)
    }
}

// ----------------------------------------------------------------------
// 작은 부품
// ----------------------------------------------------------------------

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
