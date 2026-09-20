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
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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

    val vm: CaptureViewModel = viewModel()
    val capture by vm.state.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED,
        )
    }
    // 보정 파일 고르기. 문서 제공자를 통해 읽으므로 저장소 권한이 필요 없다.
    val pickCurve = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 파일 읽기는 잠깐이지만 주 스레드에서 하지 않는다 — 클라우드
        // 제공자를 거치면 네트워크를 타서 화면이 멈출 수 있다.
        vm.importCurveFrom(uri)
    }

    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        // 허용을 누른 그 손으로 바로 재기 시작하는 것이 자연스럽다.
        // 거부했으면 시작하지 않는다 — 실패 메시지가 두 번 뜰 뿐이다.
        if (granted) vm.start()
    }

    // 화면이 뒤로 가면 마이크를 놓는다. 안 놓으면 녹음 표시가 켜진 채로 남고
    // 다른 앱이 마이크를 못 쓴다. 예배 내내 재는 것은 포그라운드 서비스가
    // 필요한 별개 문제라 Phase 10 에서 다룬다.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.stop()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

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
            TopBrandBar(capture)

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
                        ViewMode.Sermon, ViewMode.Worship -> MeasureScreen(
                            mode = mode,
                            capture = capture,
                            hasPermission = hasPermission,
                            onRequestPermission = {
                                askPermission.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            onStart = vm::start,
                            onStop = vm::stop,
                            onDismissDeviceNotice = vm::dismissDeviceNotice,
                        )
                        ViewMode.Rta -> RtaScreen(capture)
                        ViewMode.Feedback -> FeedbackScreen(capture.measure)
                    }
                    NavSection.History -> HistoryScreen()
                    NavSection.Settings -> SettingsScreen(
                        capture = capture,
                        onSaveCalibration = vm::saveSimpleCalibration,
                        onClearCalibration = vm::clearCalibration,
                        onDismissCalibrationNotice = vm::dismissCalibrationNotice,
                        onWeighting = vm::setWeighting,
                        onTimeWeight = vm::setTimeWeight,
                        onLeqWindow = vm::setLeqWindow,
                        onPreferredInput = vm::setPreferredInput,
                        onAutoPreferExternal = vm::setAutoPreferExternal,
                        onDisconnectPolicy = vm::setDisconnectPolicy,
                        // 확장자를 못 믿는 제공자가 많아 형식을 넓게 받는다.
                        // 내용으로 판별하므로 잘못 고른 파일은 파서가 거른다.
                        onPickCurveFile = { pickCurve.launch(arrayOf("*/*")) },
                        onClearCurve = vm::clearCurve,
                        onDismissCurveNotice = vm::dismissCurveNotice,
                    )
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
private fun TopBrandBar(capture: CaptureUiState) {
    val openedDeviceLabel = capture.opened?.deviceLabel
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
            // 열리기 전에는 흐리게 둔다. 그럴듯한 PHONE MIC 를 미리 띄우면
            // 마이크가 실제로 열렸는지 아닌지 구별할 수 없게 된다.
            StatusPill(
                MicKind.BuiltIn.badgeKo,
                if (openedDeviceLabel != null) SelahColors.Accent else SelahColors.TextMuted,
                dim = openedDeviceLabel == null,
            )
            StatusPill(
                capture.calibration.state.shortKo,
                if (capture.calibration.isReferenceOnly) SelahColors.Warn else SelahColors.InRange,
            )
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
