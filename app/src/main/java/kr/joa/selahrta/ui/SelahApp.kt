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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import kr.joa.selahrta.calibration.DeviceBuildInfo
import kr.joa.selahrta.calibration.currentProfileEnvironment
import kr.joa.selahrta.domain.CalibrationState
import kr.joa.selahrta.domain.ChurchSegment
import kr.joa.selahrta.domain.MeasureState
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.ui.nav.NavSection
import kr.joa.selahrta.ui.nav.ViewMode
import kr.joa.selahrta.ui.nav.defaultMode
import kr.joa.selahrta.ui.nav.hasModeChips
import kr.joa.selahrta.ui.screens.CalibrationProfilesScreen
import kr.joa.selahrta.ui.screens.CalibrationWizardScreen
import kr.joa.selahrta.ui.instrument.InstrumentGuideScreen
import kr.joa.selahrta.ui.screens.FrScreen
import kr.joa.selahrta.ui.screens.HistoryScreen
import kr.joa.selahrta.ui.screens.MeasureScreen
import kr.joa.selahrta.ui.screens.RtaScreen
import kr.joa.selahrta.ui.screens.SpectrogramScreen
import kr.joa.selahrta.ui.screens.SpectrumScreen
import kr.joa.selahrta.ui.screens.SettingsScreen
import kr.joa.selahrta.ui.screens.ToolsScreen
import kr.joa.selahrta.ui.screens.exampleJudgement
import kr.joa.selahrta.ui.screens.exampleOutcome
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
    // 교정 마법사는 탭이 아니라 **위에 덮는 화면**이다. 탭으로 두면
    // 측정 중에 잘못 눌러 들어가게 된다.
    var wizardOpen by rememberSaveable { mutableStateOf(false) }
    var wizardExample by rememberSaveable { mutableStateOf(false) }
    var profilesOpen by rememberSaveable { mutableStateOf(false) }
    var mode by remember { mutableStateOf(ViewMode.Spl) }

    val vm: CaptureViewModel = viewModel()
    val capture by vm.state.collectAsStateWithLifecycle()

    val profiles: ProfilesViewModel = viewModel()
    val profilesState by profiles.state.collectAsStateWithLifecycle()
    // 기기 신원은 안 바뀐다. 한 번만 읽는다.
    val deviceBuild = remember { DeviceBuildInfo.current() }

    val wizard: CalibrationWizardViewModel = viewModel()
    val wizardState by wizard.state.collectAsStateWithLifecycle()
    val wizardNotice by wizard.noticeKo.collectAsStateWithLifecycle()
    val wizardBusy by wizard.busyKo.collectAsStateWithLifecycle()
    val wizardSaved by wizard.saved.collectAsStateWithLifecycle()
    val wizardCapture = remember(vm) { WizardCaptureBridge(vm) }

    // 마이크 탐색은 이미 설정 화면이 돌린다. 마법사는 **그 결과를 받아만**
    // 둔다 — 여기서 다시 판정하면 두 규칙이 갈라진다.
    LaunchedEffect(capture.micProbe) {
        wizard.noteSeparation(capture.micProbe?.verdict)
    }

    // 칩을 저장된 구간에 맞추던 자리였다. 설교·찬양이 SPL 칩 **안으로**
    // 들어가면서(2026-09-24) 칩과 구간이 더는 같은 것이 아니게 되어
    // 맞출 일이 없어졌다 — 구간은 화면 안의 고르개가 곧바로 보여 준다.

    val context = LocalContext.current
    // 닫기를 고르면 액티비티를 끝낸다. 컨텍스트가 액티비티가 아니면 null 이다.
    val activity = context as? android.app.Activity
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

    // 마법사의 기준 CAL 고르기. 위와 같은 문서 제공자를 쓴다.
    val pickWizardCal = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) wizard.importCal(uri) }

    // **알림 권한은 백그라운드 측정을 시작하는 그 자리에서 묻는다**(FS02).
    //
    // 안드로이드 13 이상 신규 설치는 알림이 기본 off 다. 묻지 않으면
    // 포그라운드 서비스는 떠도 알림 서랍의 「측정 종료」가 보이지 않아,
    // 끝내려면 앱으로 돌아와야 한다.
    //
    // **거부해도 측정은 시작한다.** 알림은 편의이지 측정의 조건이 아니다.
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        vm.start()
        // 대화상자를 취소한 경우도 여기로 온다(granted=false). 둘을 같게
        // 다룬다 — 어느 쪽이든 알림 버튼은 없다.
        if (!granted) vm.noteNotificationsBlocked()
    }

    /**
     * 재기 시작한다. 알림 권한이 필요하면 **먼저 묻고**, 답이 무엇이든 잰다.
     */
    val beginMeasure: () -> Unit = {
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (shouldAskNotifications(granted = notifGranted, alreadyAsked = askedNotifications)) {
            // **한 번만 묻는다.** 안드로이드도 거부 뒤에는 대화상자를 다시
            // 띄우지 않지만, 그 왕복을 매번 거칠 까닭이 없다.
            askedNotifications = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.start()
            if (notificationsBlocked(granted = notifGranted)) vm.noteNotificationsBlocked()
        }
    }

    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        // 허용을 누른 그 손으로 바로 재기 시작하는 것이 자연스럽다.
        // 거부했으면 시작하지 않는다 — 실패 메시지가 두 번 뜰 뿐이다.
        if (granted) beginMeasure()
    }

    // 화면이 뒤로 가면 **소리만** 멈춘다. 측정은 포그라운드 서비스가
    // 마이크를 붙들고 있어 이어진다 — 예배는 두 시간이고 그동안 담당자는
    // 다른 앱을 본다.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            // 신호는 멈춘다. 예배당에서 순음을 켜 놓고 앱을 나가면 멈출
            // 방법이 화면에 없다. 측정은 조용하지만 신호는 그렇지 않다.
            //
            // **FR 작업도 함께 끊는다**(독립 검토 UA-03) — 그 작업은 배경을
            // 다 재면 **스스로** 핑크 잡음을 튼다.
            when (event) {
                // **소리를 내는 주인이 둘이다**(독립 검토 CA-05). FR 만
                // 끊었더니 교정 마법사에 같은 결함이 그대로 남아 있었다 —
                // 배경을 재는 동안 나가면 마법사가 스스로 핑크 잡음을 틀었다.
                Lifecycle.Event.ON_STOP -> {
                    vm.onBackground()
                    // **여기서만 전경 상태를 내린다**(독립 재검토 CA-R04).
                    // 탭 이동·닫기는 `stopWork()` 로 끊기만 한다 — 그때
                    // 전경까지 내리면 다시 열어도 소리를 못 낸다.
                    wizard.onBackground()
                }
                Lifecycle.Event.ON_START -> {
                    vm.onForeground()
                    wizard.onForeground()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // **뒤로 가기.** 측정 화면에서는 바로 닫지 않고 묻는다 — 예배 중에
    // 손이 스쳐 닫히면 그때까지 잰 것이 사라진다. 다른 화면에서는 측정
    // 화면으로 돌아오기만 한다(안드로이드의 보통 방식이다).
    var askExit by rememberSaveable { mutableStateOf(false) }
    val onMeasureHome = section == NavSection.Measure
    BackHandler(enabled = true) {
        if (onMeasureHome) {
            askExit = true
        } else {
            section = NavSection.Measure
            mode = NavSection.Measure.defaultMode(mode)
        }
    }

    if (askExit) {
        val running = capture.measure is MeasureState.Running
        AlertDialog(
            onDismissRequest = { askExit = false },
            // **배경과 뚜렷이 갈라 놓는다.** 예전에는 Surface 를 썼는데 앱
            // 배경과 밝기가 거의 같아 창이 떠 있는지 구별되지 않았다.
            containerColor = SelahColors.DialogSurface,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.border(
                1.dp,
                SelahColors.Outline,
                RoundedCornerShape(20.dp),
            ),
            title = { Text("앱을 닫을까요?", color = SelahColors.TextPrimary) },
            // **재고 있을 때만 본문을 둔다.** 안 재고 있을 때 「SELAH RTA 를
            // 닫습니다」는 제목을 한 번 더 말하는 것뿐이라 지웠다. 재고 있을
            // 때는 잃는 것이 있으므로 그대로 둔다.
            text = if (running) {
                {
                    Text(
                        "지금 측정 중입니다. 닫으면 측정이 종료됩니다.",
                        color = SelahColors.TextSecondary,
                        fontSize = 13.sp,
                    )
                }
            } else {
                null
            },
            confirmButton = {
                TextButton(onClick = {
                    askExit = false
                    activity?.finish()
                }) {
                    Text("확인", color = SelahColors.High)
                }
            },
            dismissButton = {
                TextButton(onClick = { askExit = false }) {
                    Text("취소", color = SelahColors.Accent)
                }
            },
        )
    }

    // **지금 떠 있는 화면**을 한 번만 셈한다(`null` = 설정).
    //
    // 화면은 칩이 정하고 설정만 예외인데, 머리글·아래 탭·화면 방향처럼
    // 「어느 화면인가」에 달린 것들이 저마다 조건을 다시 적고 있었다. 그래서
    // RTA 를 보다 설정으로 가면 — 칩은 RTA 에 머물러 있으므로 — **설정
    // 화면에 머리글이 없었다.** 한 값을 함께 보면 어긋날 수 없다.
    //
    // 아래 탭(`bottomBar`)도 이 값을 보므로 Scaffold 보다 먼저 셈한다.
    val screen: ViewMode? = if (section == NavSection.Settings) null else mode

    Scaffold(
        containerColor = SelahColors.Background,
        bottomBar = {
            // **분석 화면에서는 아래 탭을 줄인다**(2026-09-25
            // 담당자 지시: 「RTA와 같이 수정해주세요. 그래프가 최대한 크게
            // 보이게 하기 위해서입니다」).
            //
            // 줄이면 글자가 빠지고 46dp 가 된다. 눕힌 화면의 세로가 380dp
            // 안팎이라 그 차이가 차트 높이의 한 자리를 좌우한다. RTA 만
            // 줄여 두었는데, 분석은 넷 다 눕는 화면이라 나머지 셋만 글자가
            // 붙어 있었다.
            BottomBar(section, compact = screen?.section == NavSection.Analyze) { picked ->
                section = picked
                // **탭을 옮기면 마법사를 닫는다.** 마법사는 탭 내용 위에
                // 덮여 있어서, 닫지 않으면 다른 탭으로 가도 그대로 얹혀
                // 있다(기기에서 확인).
                wizardOpen = false
                wizard.stopWork()
                profilesOpen = false
                // 측정·분석으로 오면 그 구역에서 마지막에 보던 칩으로 돌아간다.
                //
                // **칩이 보이는지와 무관하다.** 예전에는 `hasModeChips` 로
                // 감쌌는데, 분석에 RTA 하나만 남아 칩이 사라지자(2026-09-24)
                // 이 줄이 통째로 건너뛰어졌다 — 아래 탭은 「분석」인데 화면은
                // 측정이 그대로 떠 있었다(기기에서 확인). 칩은 **보여 주는**
                // 일이고 이것은 **어디로 가느냐**라, 애초에 같은 조건일 까닭이
                // 없었다. 도구·설정은 `defaultMode` 가 그대로 돌려준다.
                mode = picked.defaultMode(mode)
            }
        },
    ) { inner ->
        // **분석은 구역째 눕힌다**(2026-09-25 담당자 지시: 「RTA와 FR은 모두
        // 가로형으로만 보이면 좋을 것 같습니다」).
        //
        // 화면마다 걸지 않고 여기서 거는 까닭: RTA 와 FR 이 각자 잠그면
        // 오갈 때마다 앞 화면의 잠금이 풀렸다 걸려, 폰이 한 번 섰다가 다시
        // 눕는다. 구역에 걸어 두면 분석 안에서 움직이는 동안은 계속 걸려
        // 있다. 분석을 떠나면 `onDispose` 가 원래 방향으로 돌려놓는다.
        if (screen?.section == NavSection.Analyze) LockLandscape()

        Column(Modifier.fillMaxSize().padding(inner)) {
            // **RTA 에서는 머리글을 접는다**(2026-09-24 담당자 지시: 「SELAH RTA
            // 제목 포함, USB MIC·미보정 표시도 없어도 된다 — RTA 만 해당」).
            //
            // 눕힌 화면은 세로가 380dp 안팎뿐이라, 머리글 한 줄이 차트의
            // 가로축 주파수 눈금을 화면 밖으로 밀어냈다. RTA 는 「어느 대역이
            // 솟았나」를 보는 화면이라 기기·보정 배지 없이도 읽힌다.
            //
            // **Spectrum 도 같이 접는다**(2026-09-25). 지시를 받을 때는 없던
            // 화면이지만 사정이 똑같다 — 눕혀서 차트만 띄우는 화면이고, 접어
            // 사라지는 「미보정」 배지는 **차트 설명 줄이 대신 적는다**
            // (`세로 SPL(미보정 · 참고용)`). 배지가 그냥 없어지는 것이
            // 아니므로 접어도 된다.
            //
            // **FR 과 측정에서는 접지 않는다.** FR 은 단추가 있는 스크롤
            // 화면이라 머리글이 차트를 밀지 않고, 측정은 절대 음압을 읽는
            // 화면이라 배지를 숨기면 안 된다.
            if (screen !in CHART_ONLY_MODES) TopBrandBar(capture)

            if (section.hasModeChips) {
                // RTA 는 차트가 화면을 꽉 채우는 화면이라 칩도 낮게 그린다.
                // 이 줄은 측정(SPL·기록)에만 나온다. 분석은 고르개를 차트
                // 안에서 그린다([NavSection.hasOwnModeSwitch]).
                ModeChips(section, mode) { picked ->
                    mode = picked
                    // 칩을 누르면 아래 탭도 따라온다. 두 줄이 서로 다른 곳을
                    // 가리키면 지금 어디 있는지 알 수 없다.
                    section = picked.section
                }
            }

            Box(Modifier.weight(1f)) {
                // **칩이 화면을 정한다.** 구역은 칩을 고르는 자리일 뿐이고,
                // `defaultMode` 가 「이 구역에 맞는 칩」을 보장한다. 예전에는
                // 구역으로 먼저 갈랐는데, 칩이 하나뿐이라 칩 줄이 사라진
                // 구역에서 둘이 어긋났다.
                //
                // 설정만 칩이 없어 따로 둔다.
                when (screen) {
                    null -> SettingsScreen(
                        capture = capture,
                        onSaveCalibration = vm::saveSimpleCalibration,
                        onClearCalibration = vm::clearCalibration,
                        onDismissCalibrationNotice = vm::dismissCalibrationNotice,
                        onConfirmCalibrationRoute = vm::confirmCalibrationRoute,
                        onWeighting = vm::setWeighting,
                        onTimeWeight = vm::setTimeWeight,
                        onLeqWindow = vm::setLeqWindow,
                        onPreferredInput = vm::setPreferredInput,
                        onForgetDevice = vm::forgetDevice,
                        onInputChannel = vm::setInputChannel,
                        // 확장자를 못 믿는 제공자가 많아 형식을 넓게 받는다.
                        // 내용으로 판별하므로 잘못 고른 파일은 파서가 거른다.
                        onPickCurveFile = { pickCurve.launch(arrayOf("*/*")) },
                        onClearCurve = vm::clearCurve,
                        onToggleCurve = vm::setCurveEnabled,
                        onCurveMicName = vm::setCurveMicName,
                        onDismissCurveNotice = vm::dismissCurveNotice,
                        onOpenCalibrationWizard = { wizardOpen = true },
                        onOpenCalibrationProfiles = {
                            profiles.reload()
                            profilesOpen = true
                        },
                        onSaveRange = vm::setRange,
                        onResetRange = vm::resetRange,
                        onRenameSegment = vm::setSegmentName,
                    )

                    ViewMode.Spl -> MeasureScreen(
                        capture = capture,
                        onSegment = vm::setSegment,
                        hasPermission = hasPermission,
                        onRequestPermission = {
                            askPermission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onStart = beginMeasure,
                        onStop = vm::stop,
                        onDismissDeviceNotice = vm::dismissDeviceNotice,
                    )
                    ViewMode.Rta -> RtaScreen(capture, onMode = { mode = it })
                    ViewMode.Spectrogram -> SpectrogramScreen(
                        capture = capture,
                        onSpectrumEnabled = vm::setSpectrumEnabled,
                        onMode = { mode = it },
                    )
                    ViewMode.Spectrum -> SpectrumScreen(
                        capture = capture,
                        onSpectrumEnabled = vm::setSpectrumEnabled,
                        onMode = { mode = it },
                    )
                    ViewMode.Fr -> FrScreen(
                        capture = capture,
                        onMeasure = vm::measureResponse,
                        onMeasureQuiet = vm::measureResponseQuiet,
                        onMeasureSignal = vm::measureResponseSignal,
                        onCancel = vm::cancelResponse,
                        onPlayHere = vm::setResponsePlayHere,
                        onDismissNotice = vm::dismissResponseNotice,
                    )
                    // 지난 기록을 보는 화면이라 마이크가 필요 없다.
                    ViewMode.History -> HistoryScreen()
                    ViewMode.Signal -> ToolsScreen(
                        capture = capture,
                        onPlaySignal = vm::playSignal,
                        onStopSignal = vm::stopSignal,
                        onSignalLevel = vm::setSignalLevel,
                        onSignalToneHz = vm::setSignalToneHz,
                        onSignalChannels = vm::setSignalChannels,
                        onDismissSignalNotice = vm::dismissSignalNotice,
                    )
                    // 캡처를 쓰지 않는다. 권한이 없어도 그대로 열린다.
                    ViewMode.InstrumentEq -> InstrumentGuideScreen()
                }

                // **탭 내용 위에 덮는다.** 탭으로 두면 측정 중에 잘못
                // 눌러 들어간다. 뒤로가기로 닫힌다.
                if (wizardOpen) {
                    BackHandler { wizardOpen = false; wizard.stopWork() }
                    val example = if (wizardExample) remember { exampleOutcome() } else null
                    // **잰 것이 있으면 잰 것을 보인다.** 예전에는 예시만
                    // 넘기고 있어서, 세 번을 다 재고 5단계에 가도 화면이
                    // 「아직 잰 것이 없습니다」였다 — 저장은 진짜 값으로
                    // 되는데 **눈으로 볼 자리만 비어 있었다.** 5단계가 있는
                    // 까닭이 저장 전에 보는 것이므로, 이건 단계 하나가
                    // 통째로 없던 것과 같다(실기기 확인 2026-09-24).
                    val shownOutcome = example ?: wizardState.outcome
                    // 판정은 **저장 관문이 쓰는 것과 같은 함수**로 낸다
                    // (WizardFlow.saveGate). 화면과 관문이 다른 판정을
                    // 보이면 어느 쪽이 참인지 알 수 없다.
                    val shownJudged: kr.joa.selahrta.dsp.QualityResult? = when {
                        example != null -> exampleJudgement(example)
                        shownOutcome != null && wizardState.quality != null ->
                            kr.joa.selahrta.dsp.judgeCalibration(
                                wizardState.quality!!,
                                shownOutcome,
                            )
                        else -> null
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(SelahColors.Background),
                    ) {
                        CalibrationWizardScreen(
                            state = wizardState,
                            shape = wizard.shape,
                            noticeKo = wizardNotice,
                            busyKo = wizardBusy,
                            canMeasure = capture.opened != null,
                            outcome = shownOutcome,
                            judged = shownJudged,
                            showingExample = wizardExample,
                            // 확장자를 못 믿는 제공자가 많아 넓게 받는다.
                            // 내용으로 판별하므로 잘못 고른 파일은 파서가 거른다.
                            onPickCalFile = { pickWizardCal.launch(arrayOf("*/*")) },
                            onChooseReading = wizard::chooseReading,
                            onPhantom = wizard::acknowledgePhantom,
                            onChooseHookup = wizard::chooseHookup,
                            probeBlockedKo = if (capture.measure != MeasureState.Idle) {
                                "재는 동안에는 탐색할 수 없습니다. 「측정」 화면에서 " +
                                    "측정을 끝낸 뒤 돌아오십시오."
                            } else {
                                null
                            },
                            onProbeMics = vm::probeMicrophones,
                            onCaseRemoved = wizard::noteCaseRemoved,
                            openedDeviceKey = capture.opened?.deviceKey,
                            framesOf = wizard::framesFor,
                            onRestartMeasurement = wizard::restartMeasurement,
                            savedLabelKo = wizardSaved?.labelKo,
                            canSave = capture.opened != null && wizardSaved == null,
                            // **막힌 까닭은 마법사가 판단한다**(독립 검토 CA-01).
                            // 화면이 스스로 셈하면 저장 쪽 판정과 어긋난다.
                            // **수집 신원으로 견준다**(독립 재검토 CAR-01).
                            // 열쇠만 보면 자리가 바뀐 내장 마이크가 그대로
                            // 통과한다 — 열쇠에는 자리가 없다.
                            transferBlockedKo = wizard.transferBlockedKo(wizardCapture.identity),
                            onApplyLevelTransfer = { db ->
                                vm.saveOffsetDirect(
                                    db,
                                    kr.joa.selahrta.calibration.CalibrationSource.FromReferenceMic,
                                    expectedKey = wizard.transferTargetKey,
                                )
                            },
                            onSave = {
                                val opened = capture.opened
                                if (opened != null) {
                                    wizard.save(
                                        currentProfileEnvironment(
                                            opened, capture.inputs, deviceBuild,
                                        ),
                                        wizardCapture.identity,
                                    )
                                }
                            },
                            onMeasure = { step ->
                                val spec = vm.rtaSpec()
                                if (spec != null) {
                                    wizard.measureStep(
                                        step = step,
                                        capture = wizardCapture,
                                        fftSize = spec.first,
                                        sampleRate = spec.second,
                                        tick = { kotlinx.coroutines.delay(30) },
                                    )
                                }
                            },
                            onRunInputCheck = {
                                val spec = vm.rtaSpec()
                                if (spec != null) {
                                    wizard.runInputCheck(
                                        capture = wizardCapture,
                                        fftSize = spec.first,
                                        sampleRate = spec.second,
                                        // 한 틱은 FFT 한 장이 나올 만한 시간보다
                                        // 조금 짧게 둔다 — 길면 장을 건너뛰고,
                                        // 너무 짧으면 헛돈다.
                                        tick = { kotlinx.coroutines.delay(30) },
                                    )
                                }
                            },
                            onNext = wizard::goNext,
                            onBack = wizard::goBack,
                            onGoTo = wizard::goTo,
                            onToggleExample = { wizardExample = !wizardExample },
                            onDismissNotice = wizard::dismissNotice,
                            // **닫으면 재기도 끊는다.** 예전에는 화면 값만
                            // 바꿨고, 도는 작업은 그대로 남아 소리를 틀었다.
                            onClose = { wizardOpen = false; wizard.stopWork() },
                        )
                    }
                }

                if (profilesOpen) {
                    BackHandler { profilesOpen = false }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(SelahColors.Background),
                    ) {
                        CalibrationProfilesScreen(
                            state = profilesState,
                            // **열린 값으로 대조한다.** 요청한 값이 아니라
                            // 실제로 열린 경로여야 「지금 걸리는가」가 참이다.
                            now = capture.opened?.let {
                                currentProfileEnvironment(it, capture.inputs, deviceBuild)
                            },
                            onToggle = profiles::setEnabled,
                            onDelete = profiles::delete,
                            onOpen = profiles::open,
                            onCloseOpened = profiles::close,
                            onDismissNotice = profiles::dismissNotice,
                            onClose = { profilesOpen = false },
                        )
                    }
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

/**
 * 상단 칩. **지금 구역의 것만** 그린다.
 *
 * 예전에는 모든 모드를 한 줄에 그렸다. 칩이 넷일 때는 괜찮았는데,
 * 기록과 FR 이 붙으면서 여섯을 한 줄에 욱여넣게 됐다 — 폭을 똑같이
 * 나누므로 이름이 줄바꿈되어 칩이 세로로 길어진다.
 *
 * 아래 탭이 구역을 고르고, 칩이 그 안을 고른다. 칩을 누르면 아래 탭도
 * 따라오므로 두 줄이 어긋나지 않는다.
 */
@Composable
private fun ModeChips(
    section: NavSection,
    selected: ViewMode,
    onSelect: (ViewMode) -> Unit,
) {
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
        ViewMode.entries.filter { it.section == section }.forEach { m ->
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
private fun BottomBar(
    current: NavSection,
    /**
     * 낮게 그린다 — RTA 전용(2026-09-24 담당자 지시: 「아래 측정부터 설정까지
     * 버튼은 최소화해 달라. 클 필요가 없다」).
     *
     * 눕힌 화면은 세로가 380dp 안팎뿐인데 기본 탭 바가 80dp 를 가져간다.
     * 그 화면에서 탭은 **나가는 길**일 뿐 보고 있는 것이 아니라, 아이콘만
     * 남겨도 어디로 가는지 알 수 있다.
     *
     * **글자를 지우고 설명은 남긴다.** 읽어 주는 쪽에는 `contentDescription`
     * 으로 같은 말이 간다 — 좁히는 것과 안 알리는 것은 다른 일이다.
     */
    compact: Boolean,
    onSelect: (NavSection) -> Unit,
) {
    NavigationBar(
        containerColor = SelahColors.Surface,
        modifier = if (compact) Modifier.height(46.dp) else Modifier,
    ) {
        NavSection.entries.forEach { s ->
            NavigationBarItem(
                selected = s == current,
                onClick = { onSelect(s) },
                icon = {
                    Icon(
                        painterResource(s.iconRes),
                        contentDescription = if (compact) s.labelKo else null,
                        modifier = if (compact) Modifier.size(18.dp) else Modifier,
                    )
                },
                label = if (compact) null else ({ Text(s.labelKo, fontSize = 11.sp) }),
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

/**
 * 머리글을 접는 화면들 — **눕혀서 차트로 꽉 채우는** 화면이다.
 *
 * [ViewMode] 안에 두지 않고 여기 나열하는 까닭: 「차트로 꽉 채우는가」는
 * 칩이 스스로 아는 성질이 아니라 **그 화면을 어떻게 그렸는가**라서,
 * 그리는 코드 곁에 두어야 화면을 고칠 때 함께 눈에 들어온다.
 */
private val CHART_ONLY_MODES = setOf(ViewMode.Rta, ViewMode.Spectrum, ViewMode.Spectrogram)
