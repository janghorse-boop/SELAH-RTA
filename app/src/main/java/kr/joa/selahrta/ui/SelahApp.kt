package kr.joa.selahrta.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.R
import kr.joa.selahrta.domain.CalibrationState
import kr.joa.selahrta.domain.MeasureState
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.ui.nav.NavSection
import kr.joa.selahrta.ui.nav.ViewMode
import kr.joa.selahrta.ui.nav.defaultMode
import kr.joa.selahrta.ui.nav.hasModeChips
import kr.joa.selahrta.ui.screens.FeedbackScreen
import kr.joa.selahrta.ui.screens.HistoryScreen
import kr.joa.selahrta.ui.screens.MeasureScreen
import kr.joa.selahrta.ui.screens.RtaScreen
import kr.joa.selahrta.ui.screens.SettingsScreen
import kr.joa.selahrta.ui.theme.SelahColors

@Composable
fun SelahApp() {
    // Phase 1 은 화면 뼈대까지다. 상태는 아직 화면 안에 둔다 —
    // 실제 측정이 붙는 Phase 2 에서 ViewModel 로 끌어올린다.
    //
    // 구역과 칩을 **따로** 둔다. 구역을 칩에서 파생시키면 칩이 가리키지 않는
    // 기록·설정에는 갈 방법이 아예 없어진다. 대신 둘이 어긋나지 않도록
    // 한쪽을 바꿀 때 다른 쪽을 맞춘다.
    var section by remember { mutableStateOf(NavSection.Measure) }
    var mode by remember { mutableStateOf(ViewMode.Sermon) }

    // 아직 아무것도 재지 않는다. Idle 을 못박아 둬야 화면들이 「값 없음」
    // 경로를 실제로 그리고, 나중에 값이 들어왔을 때 비교할 것이 생긴다.
    val measure: MeasureState = MeasureState.Idle

    Scaffold(
        containerColor = SelahColors.Background,
        bottomBar = {
            BottomBar(section) { picked ->
                section = picked
                // 측정·분석으로 오면 그 구역에서 마지막에 보던 칩으로 돌아간다.
                if (picked.hasModeChips) mode = picked.defaultMode(mode)
            }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            TopBrandBar()

            if (section.hasModeChips) {
                ModeChips(mode) { picked ->
                    mode = picked
                    // 칩을 누르면 아래 탭도 따라온다. 두 줄이 서로 다른 곳을
                    // 가리키면 지금 어디 있는지 알 수 없다.
                    section = picked.section
                }
            }

            Box(Modifier.weight(1f)) {
                when (section) {
                    NavSection.Measure, NavSection.Analyze -> when (mode) {
                        ViewMode.Sermon, ViewMode.Worship -> MeasureScreen(mode, measure)
                        ViewMode.Rta -> RtaScreen(measure)
                        ViewMode.Feedback -> FeedbackScreen(measure)
                    }
                    NavSection.History -> HistoryScreen()
                    NavSection.Settings -> SettingsScreen()
                }
            }
        }
    }
}

/**
 * 상단 줄: 앱 이름 · 지금 쓰는 마이크 · 보정 상태(명세 11장).
 *
 * 이 세 가지가 늘 보여야 하는 이유는 같다 — **숫자만 보면 그 숫자가 무엇으로
 * 어떤 상태에서 나온 것인지 알 수 없다.** 미보정 내장 마이크의 82 dBA 와
 * 보정된 측정 마이크의 82 dBA 는 다른 값이다.
 */
@Composable
private fun TopBrandBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                "SELAH RTA",
                color = SelahColors.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Text(
                stringResource(R.string.app_subtitle),
                color = SelahColors.TextMuted,
                fontSize = 9.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Phase 1 에는 열린 기기가 없다. 그래서 기기 이름이 아니라
            // 「아직 없음」을 적는다. 그럴듯한 PHONE MIC 를 미리 띄우면
            // 마이크가 실제로 열렸는지 아닌지 구별할 수 없게 된다.
            StatusPill(MicKind.BuiltIn.badgeKo, SelahColors.TextMuted, dim = true)
            StatusPill(CalibrationState.Uncalibrated.shortKo, SelahColors.Warn)
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color, dim: Boolean = false) {
    Box(
        Modifier
            .background(color.copy(alpha = if (dim) 0.08f else 0.14f), RoundedCornerShape(999.dp))
            .border(1.dp, color.copy(alpha = if (dim) 0.25f else 0.45f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

/** 컨셉 화면의 상단 칩 네 개. 설교·찬양·RTA·피드백. */
@Composable
private fun ModeChips(selected: ViewMode, onSelect: (ViewMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ViewMode.entries.forEach { m ->
            val on = m == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (on) SelahColors.Accent else SelahColors.SurfaceVariant,
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onSelect(m) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    m.labelKo,
                    color = if (on) Color(0xFF00201C) else SelahColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun BottomBar(current: NavSection, onSelect: (NavSection) -> Unit) {
    NavigationBar(containerColor = SelahColors.Surface) {
        NavSection.entries.forEach { s ->
            NavigationBarItem(
                selected = s == current,
                onClick = { onSelect(s) },
                icon = { Icon(painterResource(s.iconRes), contentDescription = null) },
                label = { Text(s.labelKo, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = SelahColors.Accent,
                    selectedTextColor = SelahColors.Accent,
                    unselectedIconColor = SelahColors.TextMuted,
                    unselectedTextColor = SelahColors.TextMuted,
                    indicatorColor = SelahColors.Accent.copy(alpha = 0.14f),
                ),
            )
        }
    }
}
