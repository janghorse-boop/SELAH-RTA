package kr.joa.selahrta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.calibration.ActiveCalibration
import kr.joa.selahrta.calibration.ActiveCurve
import kr.joa.selahrta.calibration.CalibrationSource
import kr.joa.selahrta.domain.ChurchSegment
import kr.joa.selahrta.domain.SEGMENT_CAUTIONS
import kr.joa.selahrta.domain.SegmentRange
import kr.joa.selahrta.audio.InputDeviceInfo
import kr.joa.selahrta.domain.MeasureState
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.audio.builtInMicNoticeKo
import kr.joa.selahrta.ui.components.SegmentRangeCard
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import kr.joa.selahrta.settings.KnownDevice
import kr.joa.selahrta.settings.LeqWindow
import kr.joa.selahrta.ui.CaptureUiState
import kr.joa.selahrta.ui.components.CalibrationCard
import kr.joa.selahrta.ui.components.CurveCard
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
fun SettingsScreen(
    capture: CaptureUiState,
    onSaveCalibration: (Double, CalibrationSource) -> Unit,
    onClearCalibration: () -> Unit,
    onDismissCalibrationNotice: () -> Unit,
    /** 「이 자리에서 잰 것이 맞다」고 사람이 확인해 준다(독립 재검토 CAR-03). */
    onConfirmCalibrationRoute: () -> Unit,
    onWeighting: (Weighting) -> Unit,
    onTimeWeight: (TimeWeight) -> Unit,
    onLeqWindow: (LeqWindow) -> Unit,
    onPreferredInput: (String?) -> Unit,
    onForgetDevice: (String) -> Unit,
    /** 기기별로 재는 채널을 고른다. */
    onInputChannel: (String, Int) -> Unit,
    onPickCurveFile: () -> Unit,
    onClearCurve: () -> Unit,
    onToggleCurve: (Boolean) -> Unit,
    onCurveMicName: (String) -> Unit,
    onDismissCurveNotice: () -> Unit,
    /** 교정 마법사를 연다(S23 지시서 6장). */
    onOpenCalibrationWizard: () -> Unit,
    /** 저장된 프로파일 목록을 연다(지시서 7장). */
    onOpenCalibrationProfiles: () -> Unit,
    onSaveRange: (ChurchSegment, SegmentRange) -> Unit,
    onResetRange: (ChurchSegment) -> Unit,
    /** 구간 이름을 고친다. 빈 값이면 기본 이름으로 되돌린다. */
    onRenameSegment: (ChurchSegment, String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        SectionTitle("입력 기기")
        InputDevicePicker(
            inputs = capture.inputs,
            selectedKey = capture.meterSettings.preferredInputKey,
            openedLabel = capture.opened?.deviceLabel,
            lastLabel = capture.lastInput?.deviceLabel,
            // 경로가 확인됐고 **지금 열려 있는** 기기만 「사용 중」이다.
            // 멈춘 뒤에도 붙어 있으면 닫힌 기기로 재고 있다고 믿게 된다.
            openedKey = capture.opened?.takeIf { it.routeConfirmed }?.deviceKey,
            running = capture.measure is MeasureState.Running,
            known = capture.meterSettings.knownDevices,
            calibration = capture.calibration,
            curve = capture.curve,
            onPick = onPreferredInput,
            onForget = onForgetDevice,
        )

        // **케이스가 막은 것을 마이크의 응답으로 적지 않게 한다.**
        //
        // 폰 케이스는 후면 마이크 구멍을 막거나 좁힌다. 하단은 대개 트여
        // 있다. 그 차이를 모르고 재면 케이스가 만든 감쇠가 보정에 들어가고,
        // 케이스를 바꾸는 순간 그 보정이 틀린 값이 된다(S23 지시서 6장).
        builtInMicNoticeKo(
            capture.inputs.firstOrNull { it.stableKey == capture.meterSettings.preferredInputKey },
            capture.micProbe?.verdict?.state,
        )?.let {
            InfoBar(it, Modifier.padding(top = 10.dp), tone = SelahColors.Warn)
        }
        // **여러 채널을 주는 기기에서만 나온다.** 내장 마이크에서는 고를
        // 것이 없으므로 화면을 어지럽히지 않는다.
        //
        // **몇 개를 그릴지는 기기가 알린 값으로 정한다** — 4채널을
        // 하드코딩하지 않는다(USB 오디오 지시서 5장).
        val chosenDevice = capture.inputs.firstOrNull {
            it.stableKey == capture.meterSettings.preferredInputKey
        } ?: capture.inputs.firstOrNull { it.stableKey == capture.opened?.deviceKey }
        val maxChannels = chosenDevice?.channelCounts?.maxOrNull() ?: 1
        // **내장 마이크 탐색 카드는 여기서 뺐다**(2026-09-24 담당자 지시).
        //
        // 탐색 자체는 교정 마법사 3단계에 남아 있다. 거기서는 「이 폰이
        // 마이크를 갈라 주는가」가 **교정의 관문**이라 필요하다. 설정에도
        // 두면 같은 일을 두 곳에서 하게 되고, 여기서 돌린 결과는 어디에도
        // 쓰이지 않았다.

        // **내장 마이크에는 띄우지 않는다.** 갤럭시 S23 은 내장 마이크도
        // `ch=1,2` 를 알린다(실측). 그건 「입력이 둘」이 아니라 스테레오로도
        // 열 수 있다는 뜻인데, 거기에 「Input 1 / Input 2」를 띄우면 사람은
        // 마이크가 둘 달린 것으로 읽는다. 고르는 것은 외부 입력일 때뿐이다.
        val multiInput = chosenDevice != null &&
            chosenDevice.kind != MicKind.BuiltIn &&
            maxChannels > 1
        if (chosenDevice != null && multiInput) {
            val picked = capture.meterSettings.inputChannels[chosenDevice.stableKey] ?: 0
            ChoiceRow(
                "측정 입력 채널",
                "여러 입력을 주는 기기입니다. 잴 채널 하나를 고르십시오. " +
                    "섞지 않습니다 — 채널마다 다른 마이크가 꽂혀 있을 수 있고, " +
                    "보정값은 마이크마다 다릅니다." +
                    if (capture.opened != null && capture.opened.channelCount > 1) {
                        " 지금은 ${capture.opened.channelCount}채널로 열려 " +
                            "${capture.opened.channelIndex + 1}번을 재고 있습니다."
                    } else {
                        ""
                    },
                (0 until maxChannels).toList(),
                picked.coerceIn(0, maxChannels - 1),
                { "Input ${it + 1}" },
                { onInputChannel(chosenDevice.stableKey, it) },
            )
        }
        // 「외부 기기 자동 사용」도 뺐다. 이제 고른 기기가 없거나 빠졌으면
        // 내장으로 연다 — 그것이 당연한 동작이라는 담당자 판단이다.
        // 「기기가 빠졌을 때」 설정도 없앴다(2026-09-24 담당자 지시).
        // 빠지면 멈추고 알린다 — 고를 일이 아니다.

        SectionTitle("보정")
        SettingRow(
            "샘플레이트 / 형식",
            capture.inputForDisplay?.let { "${it.sampleRate} Hz · ${it.encoding.bitsLabel}" } ?: "—",
        )
        CalibrationCard(
            capture = capture,
            onSave = onSaveCalibration,
            onClear = onClearCalibration,
            onDismissNotice = onDismissCalibrationNotice,
            onConfirmRoute = onConfirmCalibrationRoute,
            modifier = Modifier.padding(top = 8.dp),
        )

        InfoBar(
            "보정하지 않은 값은 참고용입니다. 폰 마이크의 감도를 모르는 상태라 " +
                "실제 음압과 10dB 넘게 차이 날 수 있습니다.",
            tone = SelahColors.Warn,
            modifier = Modifier.padding(top = 10.dp),
        )

        CurveCard(
            curve = capture.curve,
            noticeKo = capture.curveNoticeKo,
            canImport = capture.opened != null,
            onPickFile = onPickCurveFile,
            onClear = onClearCurve,
            onToggleEnabled = onToggleCurve,
            onMicName = onCurveMicName,
            onDismissNotice = onDismissCurveNotice,
            modifier = Modifier.padding(top = 10.dp),
        )

        // 마법사는 아직 재는 기능이 붙지 않았다. 여는 자리를 먼저 둔
        // 까닭은 비교 화면을 기기에서 확인해야 하기 때문이다.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onOpenCalibrationWizard) { Text("교정 마법사 열기") }
            TextButton(onClick = onOpenCalibrationProfiles) { Text("프로파일 관리") }
        }

        // **시험 신호는 여기 없다**(2026-09-24 담당자 지시: 「도구와 설정이
        // 중복됩니다. 이 부분은 도구에만 남으면 될 것 같습니다」).
        //
        // 아래 탭에 「도구」가 생기면서 이쪽이 그대로 남아 같은 카드가 두
        // 곳에 떴다. 소리를 내보내는 일은 값을 바꿔 두는 일이 아니라
        // **하는 일**이라 도구가 제자리다 — 설정에 둘 까닭이 애초에
        // 없었다(`ToolsScreen` 참고).

        SectionTitle("측정 설정")
        ChoiceRow(
            "가중치 (Weighting)",
            "A 는 사람 귀에 맞춘 가중입니다. 권장 범위 판정은 A 에서만 합니다.",
            Weighting.entries,
            capture.meterSettings.weighting,
            { it.unitSuffix },
            onWeighting,
        )
        ChoiceRow(
            "응답 속도",
            "Fast 는 짧은 봉우리를 그대로, Slow 는 뭉개서 보여 줍니다.",
            TimeWeight.entries,
            capture.meterSettings.timeWeight,
            { if (it == TimeWeight.Fast) "Fast" else "Slow" },
            onTimeWeight,
        )
        ChoiceRow(
            "Leq 시간",
            "권장 범위와 견주는 평균 구간입니다.",
            LeqWindow.entries,
            capture.meterSettings.leqWindow,
            { it.labelKo },
            onLeqWindow,
        )

        SectionTitle("구간별 권장 범위")
        ChurchSegment.entries.forEach { seg ->
            capture.meterSettings.rangeFor(seg)?.let { r ->
                SegmentRangeCard(
                    segment = seg,
                    range = r,
                    isCustom = capture.meterSettings.isCustom(seg),
                    name = capture.meterSettings.nameFor(seg),
                    isCustomName = capture.meterSettings.isCustomName(seg),
                    onSave = { onSaveRange(seg, it) },
                    onReset = { onResetRange(seg) },
                    onRename = { onRenameSegment(seg, it) },
                )
            }
        }

        InfoBar(
            "이 범위는 보편적 표준이 아니라 참고값입니다. " +
                "공식 청력 안전기준과 같게 보지 마십시오. 예배당마다 다릅니다.",
            tone = SelahColors.Warn,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        SectionTitle("주의사항")
        Column(
            Modifier
                .fillMaxWidth()
                .background(SelahColors.Surface, RoundedCornerShape(10.dp))
                .border(1.dp, SelahColors.Outline, RoundedCornerShape(10.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SEGMENT_CAUTIONS.forEach {
                Row {
                    Text("· ", color = SelahColors.Warn, fontSize = 12.sp)
                    Text(it, color = SelahColors.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }

        SectionTitle("앱 정보")
        SettingRow("SELAH RTA", "v0.1.0 (Phase 8)")
        Text(
            // 만든 사람과 회사는 다른 것이다. 회사만 적으면 누가 만들었는지가
            // 사라진다.
            "Real-Time Worship Audio Analyzer\n" +
                "개발 장훈 (JANGHUN) · 조아웍스 | JOA Works",
            color = SelahColors.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )
    }
}

/**
 * 고르는 줄. 지금 고른 것이 색으로도 글자로도 드러나야 한다.
 *
 * 설정을 바꾸면 측정 엔진이 새로 만들어져 Leq 와 MAX 가 비워진다 —
 * 계수가 다른 필터의 값을 이어 붙이면 그 구간이 어느 쪽도 아닌 값이 된다.
 */
/**
 * 입력 기기 고르기(컨셉 화면 5번).
 *
 * **「자동」도 하나의 선택지로 둔다.** 목록에서 고르기만 하게 하면,
 * 나중에 그 기기를 안 쓸 때 되돌릴 방법이 없다.
 */
/**
 * 기기 카드 안에 적는 **그 기기의 보정 상태**(2026-09-25 담당자 지시).
 *
 * ## 왜 기기 카드 안인가
 *
 * 보정은 **기기마다** 따로 있다. 그런데 상태는 저 아래 「보정」 구역에
 * 따로 떠 있어서, 어느 기기의 이야기인지 이어지지 않았다 — 기기를 바꾸고도
 * 위쪽 숫자를 그 기기의 것으로 읽게 된다.
 *
 * ## 둘을 갈라서 적는다
 *
 * **절대 레벨 보정과 주파수 보정은 다른 일이다**(CLAUDE.md §6). 하나로
 * 뭉쳐 「보정됨」이라고 적으면, 곡선만 넣고 절대 음압까지 맞은 줄 안다.
 * 교정기는 1kHz 한 점의 크기만 맞추고, 곡선은 주파수마다 얼마나 더·덜
 * 잡는지를 되돌린다 — 둘 다 있어야 숫자를 믿을 수 있다.
 */
@Composable
private fun DeviceCalibration(
    deviceLabel: String,
    calibration: ActiveCalibration,
    curve: ActiveCurve?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(SelahColors.SurfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "이 기기의 보정 · $deviceLabel",
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
        )
        CalibRow(
            "절대 레벨",
            calibration.state.labelKo,
            warn = calibration.isReferenceOnly,
        )
        CalibRow(
            "주파수 곡선",
            when {
                curve == null -> "없음"
                // **파일이 있는 것과 걸려 있는 것은 다르다.** 꺼 두었으면
                // 「적용됨」이 아니다.
                !curve.enabled -> "${curve.fileName} · 꺼 둠"
                else -> "${curve.fileName} · 걸림"
            },
            warn = curve == null || !curve.enabled,
        )
    }
}

/** 이름과 값 한 줄. 색만으로 알리지 않으려고 값을 글자로 적는다(명세 11장). */
@Composable
private fun CalibRow(label: String, value: String, warn: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = SelahColors.TextMuted, fontSize = 11.sp, softWrap = false)
        Text(
            value,
            color = if (warn) SelahColors.Warn else SelahColors.InRange,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun InputDevicePicker(
    inputs: List<InputDeviceInfo>,
    selectedKey: String?,
    openedLabel: String?,
    /** 마지막으로 썼던 기기 이름. 멈춘 뒤에 적는다. */
    lastLabel: String?,
    /** 지금 실제로 열려 있는 기기의 열쇠. 확인되기 전에는 null 이다. */
    openedKey: String?,
    /** 재는 중인가. 재는 중에 고른 기기는 다음 시작에야 쓰인다. */
    running: Boolean,
    /** 한 번이라도 연결됐던 기기들. 지금 없는 것은 흐리게 보여 준다. */
    known: List<KnownDevice>,
    /** 지금 숫자가 나오는 경로의 절대 레벨 보정. */
    calibration: ActiveCalibration,
    /** 그 경로에 걸린 주파수 보정 곡선. 없으면 null. */
    curve: ActiveCurve?,
    onPick: (String?) -> Unit,
    /** 기억에서 지운다. */
    onForget: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(SelahColors.Surface, RoundedCornerShape(10.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("입력 기기", color = SelahColors.TextPrimary, fontSize = 13.sp)

        if (inputs.isEmpty()) {
            Text(
                "쓸 수 있는 입력 기기를 찾지 못했습니다. 마이크 권한을 허용하면 목록이 나타납니다.",
                color = SelahColors.Warn,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            return@Column
        }

        // **드롭다운으로 접는다**(2026-09-24 담당자 지시).
        //
        // 기억한 기기가 쌓이면 줄이 계속 늘어난다. 평소에는 고른 것 하나만
        // 보이고, 바꿀 때만 펼친다.
        //
        // 「자동으로 고르기」 줄은 없앴다. 고른 것이 없거나 빠졌으면
        // 내장으로 연다 — 규칙을 기기인 척 줄에 띄워 고르게 할 일이 아니다.
        //
        // **전에 썼던 기기는 꽂혀 있지 않아도 목록에 둔다.** 인터페이스를
        // 늘 꽂아 두지는 않는데, 뺄 때마다 사라지면 「그 기기로 잴 수 있다」는
        // 사실 자체가 화면에서 없어지고 그 기기의 보정이 있다는 것도 안 보인다.
        val absent = known.filter { k -> inputs.none { it.stableKey == k.key } }
        var open by remember { mutableStateOf(false) }
        val picked = inputs.firstOrNull { it.stableKey == selectedKey }
        val pickedAbsent = absent.firstOrNull { it.key == selectedKey }

        Box {
            DeviceRow(
                title = picked?.displayName
                    ?: pickedAbsent?.name
                    ?: inputs.firstOrNull { it.kind == MicKind.BuiltIn }?.displayName
                    ?: "고른 기기 없음",
                key = selectedKey,
                selected = true,
                subtitle = when {
                    picked != null && openedKey == picked.stableKey -> "사용 중 · 눌러서 바꾸기"
                    pickedAbsent != null -> "연결 안 됨 · 눌러서 바꾸기"
                    selectedKey == null -> "고른 것이 없어 내장으로 잽니다 · 눌러서 바꾸기"
                    else -> "눌러서 바꾸기"
                },
                onPick = { open = true },
                inUse = picked != null && openedKey == picked.stableKey,
                nextStart = running && picked != null && openedKey != picked.stableKey,
            )
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                modifier = Modifier.background(SelahColors.DialogSurface),
            ) {
                inputs.forEach { d ->
                    DeviceMenuItem(
                        name = d.displayName,
                        note = if (d.kind == MicKind.Usb) "외부 입력 · 연결됨" else "내장 · 연결됨",
                        selected = selectedKey == d.stableKey,
                        onClick = { onPick(d.stableKey); open = false },
                    )
                }
                absent.forEach { k ->
                    DeviceMenuItem(
                        name = k.name,
                        note = if (k.kind == MicKind.Usb) {
                            "외부 입력 · 연결 안 됨"
                        } else {
                            "내장 · 연결 안 됨"
                        },
                        selected = selectedKey == k.key,
                        onClick = { onPick(k.key); open = false },
                        onForget = { onForget(k.key); open = false },
                    )
                }
            }
        }

        if (openedLabel != null || lastLabel != null) {
            Text(
                if (openedLabel != null) {
                    "지금 열려 있는 기기: $openedLabel"
                } else {
                    "마지막으로 쓴 기기: $lastLabel (지금은 열려 있지 않습니다)"
                },
                color = SelahColors.TextMuted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            // **보정 상태를 기기 카드 안에서 말한다**(2026-09-25 담당자 지시).
            //
            // 보정은 **기기마다** 따로 있는데, 상태는 저 아래 「보정」 구역에
            // 따로 떠 있었다. 어느 기기의 이야기인지 이어지지 않아, 기기를
            // 바꾸고도 위쪽 숫자를 그 기기의 것으로 읽게 된다.
            //
            // **고른 기기가 아니라 열린 기기의 것이다.** 재는 도중에 다른
            // 기기를 고르면 그것은 다음 시작에야 쓰이므로(바로 위 경고),
            // 여기 적는 값은 지금 숫자가 나오고 있는 경로의 것이다. 그래서
            // 제목에 기기 이름을 함께 적는다.
            DeviceCalibration(
                deviceLabel = openedLabel ?: lastLabel.orEmpty(),
                calibration = calibration,
                curve = curve,
            )
        }
        if (running) {
            // 재는 도중에 고른 기기가 곧바로 쓰이지 않는다는 사실을 적는다.
            // 안 적으면 고른 마이크로 재고 있다고 믿는다(독립 검증 R11).
            Text(
                "여기서 고른 기기는 다음 시작에 씁니다. 재는 도중에 바꾸면 그 " +
                    "앞뒤 값이 서로 다른 마이크의 값이 되기 때문입니다.",
                color = SelahColors.Warn,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
        Text(
            "기기마다 보정값을 따로 둡니다. 바꾸면 그 기기의 보정이 적용됩니다.",
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
    }
}

@Composable
private fun DeviceRow(
    title: String,
    key: String?,
    selected: Boolean,
    subtitle: String,
    onPick: (String?) -> Unit,
    /**
     * 지금 **실제로 이 기기로 열려 있는가.**
     *
     * 고른 것과 열린 것은 다르다. 재는 도중에 기기를 고르면 설정만 바뀌고
     * 입력은 그대로다 — 예배 중에 입력이 바뀌면 그 앞뒤 값이 서로 다른
     * 마이크의 값이 되기 때문이다. 그런데 화면은 고른 것에 「사용 중」을
     * 붙여, 고른 마이크로 재고 있다고 믿게 했다(독립 검증 R11).
     */
    inUse: Boolean = false,
    /** 골라 두었지만 다음 시작에야 쓰이는가. */
    nextStart: Boolean = false,
    /**
     * 지금 **연결돼 있는가.** 전에 쓴 기기는 false 다.
     *
     * 꺼진 줄도 **고를 수는 있다** — 다시 꽂을 기기를 미리 골라 두는
     * 것이 자연스럽고, 고른 것이 없으면 어차피 내장으로 열린다.
     */
    enabled: Boolean = true,
    /** 기억에서 지우기. 전에 쓴 기기에만 붙는다. */
    onForget: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) SelahColors.Accent.copy(alpha = 0.16f) else SelahColors.SurfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .border(
                1.dp,
                if (selected) SelahColors.Accent else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable { onPick(key) }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // **남는 폭은 기기 이름이 가져간다.** 무게를 주지 않았더니 긴 USB
        // 이름이 줄을 다 먹고, 오른쪽 배지가 한 글자씩 세로로 쪼개졌다
        // ―「선/택/함」(기기에서 확인). 한국어는 기본값에서 글자 단위로
        // 끊기기 때문이다.
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = when {
                    selected -> SelahColors.Accent
                    !enabled -> SelahColors.TextMuted
                    else -> SelahColors.TextPrimary
                },
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(subtitle, color = SelahColors.TextMuted, fontSize = 10.sp)
        }
        if (onForget != null) {
            TextButton(onClick = onForget) {
                Text("지우기", color = SelahColors.TextMuted, fontSize = 11.sp, softWrap = false)
            }
        }
        // 색만으로 알리지 않는다(명세 11장).
        // **배지는 끊지 않는다.** 짧은 라벨이라 줄바꿈할 자리가 없다.
        when {
            inUse -> Text(
                "사용 중",
                color = SelahColors.Accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                softWrap = false,
            )
            nextStart -> Text(
                "다음 시작에 사용",
                color = SelahColors.Warn,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                softWrap = false,
            )
            selected -> Text(
                "선택함",
                color = SelahColors.Accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    helpKo: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onPick: (T) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(SelahColors.Surface, RoundedCornerShape(10.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = SelahColors.TextPrimary, fontSize = 13.sp)
        Text(helpKo, color = SelahColors.TextMuted, fontSize = 10.sp, lineHeight = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { o ->
                val on = o == selected
                Box(
                    Modifier
                        .weight(1f)
                        .background(
                            if (on) SelahColors.Accent else SelahColors.SurfaceVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { onPick(o) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        labelOf(o),
                        color = if (on) Color(0xFF00201C) else SelahColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
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



/**
 * 드롭다운 안의 기기 한 줄.
 *
 * **연결 여부를 글자로 적는다.** 흐리게만 그리면 색을 못 보는 사람에게
 * 아무 말도 하지 않는다(명세 11장). 꺼진 기기도 **고를 수는 있다** —
 * 다시 꽂을 기기를 미리 골라 두는 것이 자연스럽고, 그때까지는 어차피
 * 내장으로 열린다.
 */
@Composable
private fun DeviceMenuItem(
    name: String,
    note: String,
    selected: Boolean,
    onClick: () -> Unit,
    /** 기억에서 지우기. 연결 안 된 기기에만 붙는다. */
    onForget: (() -> Unit)? = null,
) {
    DropdownMenuItem(
        onClick = onClick,
        text = {
            Column {
                Text(
                    name,
                    color = if (selected) SelahColors.Accent else SelahColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(note, color = SelahColors.TextMuted, fontSize = 10.sp)
            }
        },
        trailingIcon = if (onForget == null) {
            null
        } else {
            {
                TextButton(onClick = onForget) {
                    Text("지우기", color = SelahColors.TextMuted, fontSize = 11.sp)
                }
            }
        },
    )
}
