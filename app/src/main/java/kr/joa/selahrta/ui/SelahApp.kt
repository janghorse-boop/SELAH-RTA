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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.R
import kr.joa.selahrta.domain.CalibrationState
import kr.joa.selahrta.domain.ChurchSegment
import kr.joa.selahrta.domain.MeasureState
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.ui.nav.NavSection
import kr.joa.selahrta.ui.instrument.InstrumentGuideScreen
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
            // 측정과 **소리**를 함께 멈춘다. 소리만 남으면 끌 방법이 없다.
            if (event == Lifecycle.Event.ON_STOP) vm.onBackground()
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
                    // 설교·찬양 칩은 구간도 함께 바꾼다. 칩이 「설교」인데
                    // 판정은 찬양 범위로 하고 있으면 아무도 이해할 수 없다.
                    when (picked) {
                        ViewMode.Sermon -> vm.setSegment(ChurchSegment.Sermon)
                        ViewMode.Worship -> vm.setSegment(ChurchSegment.Worship)
                        else -> Unit
                    }
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
                        ViewMode.Feedback -> FeedbackScreen(capture)
                        // 캡처를 쓰지 않는다. 권한이 없어도 그대로 열린다.
                        ViewMode.InstrumentEq -> InstrumentGuideScreen()
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
                        onSaveRange = vm::setRange,
                        onResetRange = vm::resetRange,
                        onPlaySignal = vm::playSignal,
                        onStopSignal = vm::stopSignal,
                        onSignalLevel = vm::setSignalLevel,
                        onDismissSignalNotice = vm::dismissSignalNotice,
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
        // 제목이 배지를 밀어내지 않게 한다. 글꼴 200% 에서
        // 「미보정」이 화면 밖으로 사라졌다 — 보정 상태는 숨기면
        // 안 되는 것이다.
        Column(Modifier.weight(1f, fill = false)) {
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
            // **실제로 열린 기기의 종류를 그린다.** 예전에는 USB 로 열어도
            // PHONE MIC 가 나왔다(독립 검증 R12).
            //
            // 상태가 셋이다: 안 열림(흐린 PHONE MIC — 그럴듯한 배지를 미리
            // 띄우면 열렸는지 구별할 수 없다), 열렸지만 어느 마이크인지
            // 아직 확인 못 함(「확인 중」 — 그때의 종류는 요청한 값일 뿐이다),
            // 확인됨(그 기기의 배지).
            val opened = capture.opened
            val shown = capture.inputForDisplay
            StatusPill(
                when {
                    shown == null -> MicKind.BuiltIn.badgeKo
                    opened != null && !opened.routeConfirmed -> "입력 확인 중"
                    else -> shown.micKind.badgeKo
                },
                when {
                    opened == null -> SelahColors.TextMuted
                    !opened.routeConfirmed -> SelahColors.Warn
                    else -> SelahColors.Accent
                },
                // 멈췄으면 흐리게. 마지막에 쓴 기기를 적되 「지금 열려 있다」로
                // 보이면 안 된다.
                dim = opened == null,
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
    // **양쪽을 채운다.** 칩은 가는 길이라 가지런히 놀아 놓으면
    // 한쪽이 비어 보인다. 폭을 똑같이 나눠 갖는다.
    //
    // 글꼴을 200% 로 키우면 글자가 칩 폭을 넘어서는데, 그때는
    // **잘리는 대신 줄을 바꿈다**(maxLines 를 걸지 않는다). 칩은
    // 높아지지만 글자가 사라지지는 않는다 — 이름이 잘리면 어디로
    // 가는 칩인지 알 수 없다.
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
                    .padding(horizontal = 4.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    m.labelKo,
                    color = if (on) Color(0xFF00201C) else SelahColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
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
