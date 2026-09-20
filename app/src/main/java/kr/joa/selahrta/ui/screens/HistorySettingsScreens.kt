package kr.joa.selahrta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.domain.ChurchMode
import kr.joa.selahrta.domain.ReferenceRange
import kr.joa.selahrta.domain.ReferenceRanges
import kr.joa.selahrta.ui.components.InfoBar
import kr.joa.selahrta.ui.components.NotYet
import kr.joa.selahrta.ui.theme.SelahColors

/** 컨셉 화면 7번 — 예배 기록. Phase 10 에서 실제 저장이 붙는다. */
@Composable
fun HistoryScreen() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        InfoBar(
            "기본 측정은 소리를 저장하지 않습니다. 음압·주파수 요약만 남습니다.",
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        Column(
            Modifier.fillMaxWidth().padding(vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("아직 기록이 없습니다.", color = SelahColors.TextSecondary, fontSize = 14.sp)
            Text(
                "예배를 측정하면 여기에 남습니다.",
                color = SelahColors.TextMuted,
                fontSize = 12.sp,
            )
        }
        NotYet("세션 저장은 Phase 10, 리포트는 Phase 11 입니다.", "Phase 10 · 11")
    }
}

/**
 * 컨셉 화면 5·6·8 을 한 자리에 모은 설정.
 *
 * 화면마다 따로 두지 않고 목록으로 둔 이유는, 지금 **눌러서 들어갈 내용이
 * 아직 없기** 때문이다. 빈 화면 셋을 만들어 두면 만들어진 것처럼 보인다.
 */
@Composable
fun SettingsScreen() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        SectionTitle("마이크와 보정")
        SettingRow("입력 장치", "아직 열지 않음", phase = "Phase 2 · 6")
        SettingRow("샘플레이트 / 비트 깊이", "—", phase = "Phase 2")
        SettingRow("보정 상태", "미보정", phase = "Phase 3 · 7", warn = true)

        SectionTitle("측정 설정")
        SettingRow("가중치 (Weighting)", "dBA", phase = "Phase 4")
        SettingRow("응답 속도", "Fast (0.125s)", phase = "Phase 4")
        SettingRow("LAeq 시간", "1분", phase = "Phase 4")

        SectionTitle("모드별 권장 범위")
        RangeCard(ChurchMode.Sermon.labelKo, ReferenceRanges.sermon)
        RangeCard(ChurchMode.Worship.labelKo, ReferenceRanges.worship)
        RangeCard("기도 / 성경봉독", ReferenceRanges.prayer)

        InfoBar(
            "이 범위는 보편적 표준이 아니라 참고값입니다. " +
                "공식 청력 안전기준과 같게 보지 마십시오. 예배당마다 다릅니다.",
            tone = SelahColors.Warn,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        SectionTitle("앱 정보")
        SettingRow("SELAH RTA", "v0.1.0 (Phase 1)")
        Text(
            "Real-Time Worship Audio Analyzer · made by Jesus On Air (JOA)",
            color = SelahColors.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = SelahColors.TextMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
    phase: String? = null,
    warn: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(SelahColors.Surface, RoundedCornerShape(10.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(label, color = SelahColors.TextPrimary, fontSize = 13.sp)
            // 아직 못 바꾸는 항목임을 숨기지 않는다. 눌렀는데 아무 일도
            // 안 일어나면 고장으로 읽힌다.
            if (phase != null) {
                Text(phase, color = SelahColors.TextMuted, fontSize = 10.sp)
            }
        }
        Text(
            value,
            color = if (warn) SelahColors.Warn else SelahColors.TextSecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun RangeCard(title: String, r: ReferenceRange) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(SelahColors.Surface, RoundedCornerShape(10.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(title, color = SelahColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "권장 평균 ${r.avg.start.toInt()} ~ ${r.avg.endInclusive.toInt()} dBA",
            color = SelahColors.TextSecondary,
            fontSize = 12.sp,
        )
        Text(
            "순간 피크 ${r.peak.start.toInt()} ~ ${r.peak.endInclusive.toInt()} dBA",
            color = SelahColors.TextSecondary,
            fontSize = 12.sp,
        )
        Text(r.noteKo, color = SelahColors.TextMuted, fontSize = 11.sp)
    }
}
