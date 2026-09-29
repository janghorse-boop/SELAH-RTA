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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.calibration.ActiveCalibration
import kr.joa.selahrta.calibration.ActiveCurve
import kr.joa.selahrta.calibration.CalibrationSource
import kr.joa.selahrta.domain.ChurchSegment
import kr.joa.selahrta.domain.MAX_SEGMENTS
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
import kr.joa.selahrta.dsp.ChannelLevelSnapshot
import kr.joa.selahrta.dsp.SILENCE_FLOOR_DBFS
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import kr.joa.selahrta.settings.LeqWindow
import kr.joa.selahrta.settings.FFT_SIZES
import kr.joa.selahrta.ui.CaptureUiState
import kr.joa.selahrta.ui.components.CalibrationCard
import kr.joa.selahrta.ui.components.CurveCard
import kr.joa.selahrta.ui.components.InfoBar
import kr.joa.selahrta.ui.components.NotYet
import kr.joa.selahrta.ui.theme.SelahColors
import kr.joa.selahrta.ui.theme.ThemeMode

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
    onConfirmPendingCalibration: () -> Unit,
    onDismissPendingCalibration: () -> Unit,
    /** 음압(SPL·Leq·MIN·MAX)의 가중. */
    onSplWeighting: (Weighting) -> Unit,
    /** 순간최고(PEAK)의 가중. 음압과 따로 둔다(지시서 §16). */
    onPeakWeighting: (Weighting) -> Unit,
    /** RTA·Spectrum·Spectrogram 의 가중. */
    onAnalysisWeighting: (Weighting) -> Unit,
    onResetSplWeighting: () -> Unit,
    onResetPeakWeighting: () -> Unit,
    onResetAnalysisWeighting: () -> Unit,
    onTimeWeight: (TimeWeight) -> Unit,
    onLeqWindow: (LeqWindow) -> Unit,
    /** 화면 밝기 한 벌. **기기 설정이 아니라 여기서 고른 것을 쓴다.** */
    onThemeMode: (ThemeMode) -> Unit,
    /** FFT 길이. **다음 측정부터 적용된다**(측정 중 바꾸면 싱크가 끊긴다). */
    onFftSize: (Int) -> Unit,
    onPreferredInput: (String?) -> Unit,
    onForgetDevice: (String) -> Unit,
    /** 기기별로 재는 채널을 고른다. */
    onInputChannel: (String, Int) -> Unit,
    onPickCurveFile: () -> Unit,
    onClearCurve: () -> Unit,
    onToggleCurve: (Boolean) -> Unit,
    /** 가져온 곡선의 읽는 법을 사람이 정해 준다(독립 재검토 CFRF-01). */
    onConfirmCurveReading: (
        kr.joa.selahrta.calibration.CurveConfirmationToken,
        kr.joa.selahrta.dsp.CurveReading,
    ) -> Unit,
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
    /** 구간을 더한다(최대 5). */
    onAddSegment: (ChurchSegment) -> Unit,
    /** 구간을 뺀다. 설교·찬양은 오지 않는다. */
    onRemoveSegment: (ChurchSegment) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        SectionTitle("측정 기기")
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
                // 담당자 문안(2026-09-28). **뒤에 붙는 한 줄은 그대로
                // 둔다** — 「고른 것」과 「실제로 열린 것」이 다를 수 있어,
                // 지금 몇 채널로 열려 몇 번을 재는지는 이 자리에서만 안다.
                "측정에 사용할 입력 채널 하나를 선택하세요. " +
                    "각 채널에 연결된 마이크와 보정값이 다를 수 있으므로 " +
                    "여러 채널을 합쳐 측정하지 않습니다." +
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

            // **어느 입력에 소리가 들어오는가.**
            //
            // 이것이 없으면 채널을 하나씩 골라 가며 레벨이 움직이는지
            // 봐야 했다. 마이크에 대고 말하면 여기서 바로 갈린다.
            val bars = capture.diagnostics.channelLevels
            if (bars != null) {
                ChannelLevelBars(bars, picked)
            } else if (capture.measure is MeasureState.Running && maxChannels > 1) {
                // **Input 1 을 고르면 한 채널만 열린다.**
                //
                // 채널은 「고른 번호가 들어가는 가장 작은 수」로 연다
                // (`planChannels`) — 버스 대역과 버퍼를 아끼려는
                // 설계다. 그래서 1번을 고르면 견줄 채널이 아예 없고,
                // 위의 막대도 뜰 수 없다.
                //
                // **길을 적는다.** 이 사실을 모르면 「막대가 왜 안 뜨지」로
                // 끝난다.
                InfoBar(
                    "지금은 한 채널만 열려 있어 입력끼리 견줄 수 없습니다. " +
                        "마이크가 몇 번에 꽂혔는지 찾으려면 " +
                        "Input ${maxChannels} 를 고르십시오 — 그러면 모든 입력이 " +
                        "함께 열려 어디에 소리가 들어오는지 보입니다. " +
                        "찾은 뒤 그 번호로 되돌리십시오.",
                )
            }
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
            onConfirmPending = onConfirmPendingCalibration,
            onDismissPending = onDismissPendingCalibration,
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
            onConfirmReading = onConfirmCurveReading,
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
        WeightingRow(
            label = "음압 가중",
            whereKo = "적용 대상: 현재 SPL · Leq · 최소값(MIN) · 최대값(MAX)",
            helpKo = ::splWeightingHelpKo,
            selected = capture.meterSettings.splWeighting,
            isCustom = capture.meterSettings.splWeighting != Weighting.A,
            onPick = onSplWeighting,
            onReset = onResetSplWeighting,
        )
        WeightingRow(
            label = "순간최고(PEAK) 가중",
            whereKo = "PEAK 값에 적용할 주파수 가중 방식을 선택합니다.",
            // **PEAK 은 제 설명을 쓴다.** 순간 최대값이라 같은 가중이라도
            // 보는 뜻이 음압 때와 다르다(담당자 문안 2026-09-28).
            helpKo = ::peakWeightingHelpKo,
            selected = capture.meterSettings.peakWeighting,
            isCustom = capture.meterSettings.peakWeighting != Weighting.C,
            onPick = onPeakWeighting,
            onReset = onResetPeakWeighting,
        )
        WeightingRow(
            label = "주파수 분석 가중",
            whereKo = "적용 대상: RTA · Spectrum · Spectrogram",
            helpKo = ::analysisWeightingHelpKo,
            selected = capture.meterSettings.analysisWeighting,
            isCustom = capture.meterSettings.analysisWeighting != Weighting.Z,
            onPick = onAnalysisWeighting,
            onReset = onResetAnalysisWeighting,
        )

        // FR 은 고를 까닭이 없어 목록에 없다. **숨기지 않고 그 사실을
        // 적는다** — 없는 것과 못 고르는 것은 다르다.
        InfoBar(
            "주파수 응답(FR)은 항상 dB(Z)로 표시됩니다. " +
                "공간과 시스템의 주파수별 특성을 왜곡 없이 확인하기 위한 " +
                "것으로, 위 가중치 설정은 FR에 적용되지 않습니다.",
        )
        ChoiceRow(
            "응답 속도",
            // 담당자 문안 그대로다(2026-09-28).
            //
            // **시간상수를 적어 둔다.** 예전에는 이 자리에 **자리 잡는
            // 시간**(τ×3)을 「Fast 0.4초 · Slow 3초」로 적었는데, 외부
            // 검토가 그것을 시간상수로 읽고 「IEC 와 다르다」고 보았다.
            // τ 는 처음부터 규격값이었다 — 규격값을 그대로 보이면 그
            // 오해가 생기지 않는다.
            //
            // 자리 잡는 3초(Slow)는 이제 이 줄이 말하지 않는다. 시작
            // 직후 잠깐 값이 낮은 것은 MIN 타일의 「자리 잡는 중」이
            // 그때그때 보여 준다.
            "측정값의 시간 응답 속도를 설정합니다. " +
                "Fast는 빠른 변화를 추적하고, Slow는 변동을 완화해 " +
                "안정적으로 표시합니다. " +
                "시간상수는 Fast 125 ms, Slow 1초를 적용합니다.",
            TimeWeight.entries,
            capture.meterSettings.timeWeight,
            // 칸이 좁아 괄호 안(125ms 같은 것)까지는 못 적는다. 자세한
            // 것은 바로 위 설명이 말한다.
            {
                when (it) {
                    TimeWeight.Fast -> "Fast"
                    TimeWeight.Slow -> "Slow"
                }
            },
            onTimeWeight,
        )
        ChoiceRow(
            "Leq 시간",
            "선택한 시간 동안 측정된 음압의 평균 레벨(Leq)을 표시합니다.",
            LeqWindow.entries,
            capture.meterSettings.leqWindow,
            { it.labelKo },
            onLeqWindow,
        )
        ChoiceRow(
            "FFT 크기",
            // 앞 문장은 담당자 문안 그대로다(2026-09-28).
            //
            // **「다음 측정부터」는 남겼다.** 재는 도중에 바꾸면 화면이
            // 그대로라 고장으로 읽는다 — 엔진을 새로 만들면 하울링
            // 탐지와 FR 수집이 끊기므로 일부러 다음 시작에 거는 것이다.
            "FFT 크기가 클수록 주파수를 더 세밀하게 분석하고, " +
                "작을수록 변화에 빠르게 반응합니다. 다음 측정부터 적용됩니다.",
            FFT_SIZES,
            capture.meterSettings.fftSize,
            { it.toString() },
            onFftSize,
        )

        SectionTitle("구간별 권장 범위") {
            // **다 찼으면 눌리지 않는다.** 숨기면 왜 못 더하는지 알 길이
            // 없어, 회색으로 남겨 둔다.
            val next = capture.meterSettings.nextFreeSegment
            TextButton(onClick = { next?.let(onAddSegment) }, enabled = next != null) {
                Text(
                    "추가",
                    color = if (next != null) SelahColors.Accent else SelahColors.TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        // **쓰는 구간만 그린다**(담당자 지시 2026-09-28: 최대 5개).
        // enum 차례를 따르므로 더한 차례와 무관하게 늘 같은 줄에 온다.
        capture.meterSettings.orderedSegments.forEach { seg ->
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
                    // **무엇이든 뺄 수 있다**(담당자 지시 2026-09-28:
                    // 0개에서 시작해 사람이 더하고 뺀다). 이 구간으로
                    // 남긴 기록은 그대로 있다.
                    onRemove = { onRemoveSegment(seg) },
                )
            }
        }

        // 하나도 없을 때는 무엇을 하는 자리인지부터 적는다.
        if (capture.meterSettings.segments.isEmpty()) {
            InfoBar(
                "구간이 없습니다. 더하면 측정 화면의 큰 숫자를 그 범위와 " +
                    "견주어 색으로 알려 줍니다. 공간마다 알맞은 값이 " +
                    "다르므로, 더한 뒤 「범위」에서 고쳐 쓰십시오.",
            )
        }
        // **「구간 더하기」 링크를 뺐다**(담당자 지시 2026-09-28).
        // 같은 일을 위 머리글의 「추가」가 한다 — 목록이 길어져도 자리가
        // 밀리지 않는 쪽만 남겼다.

        // **참고값 경고 상자를 뺐다**(담당자 지시 2026-09-27).
        // 바로 아래 「주의사항」이 같은 말을 하고 있어 두 번 적혀 있었다.

        SectionTitle("화면")
        ChoiceRow(
            // 구역 머리글이 「화면」이라 줄 이름은 「테마」 하나로 족하다
            // (담당자 지시 2026-09-28).
            "테마",
            // 설명은 담당자 문안 그대로다.
            //
            // 전 문안에는 「폰 설정을 따라가지 않고 여기서 고른 것을
            // 씁니다」가 있었다. **그 동작은 그대로다**(`SelahRtaTheme` 가
            // 저장된 값만 본다) — 화면에 적지 않을 뿐이다. 「폰을 다크로
            // 바꿨는데 앱이 그대로」라는 물음이 들어오면 이 줄을 되살리는
            // 것이 가장 싼 답이다.
            "앱의 화면 테마를 선택합니다. " +
                "다크 모드와 라이트 모드 중 원하는 화면을 사용할 수 있습니다.",
            ThemeMode.entries,
            capture.meterSettings.themeMode,
            { it.labelKo },
            onThemeMode,
        )

        // **주의사항 상자를 뺐다**(담당자 지시 2026-09-28). 한 번 읽으면
        // 그만인 글 셋이 설정 아래에 늘 펼쳐져 있었다. 앞서 같은 자리의
        // 참고값 경고 상자를 뺀 것과 같은 까닭이다(2026-09-27).
        //
        // 문구 자체는 `SEGMENT_CAUTIONS` 에 그대로 있다 — 지우지 않았으니
        // 다시 보이고 싶으면 되살리면 된다.

        SectionTitle("앱 정보")
        SettingRow("SELAH RTA", "v0.1.0 (Phase 8)")
        Text(
            // 만든 곳만 적는다(담당자 지시 2026-09-28). 영문 확장명
            // (Real-Time Audio Analyzer)은 **바로 위 머리글에 이미 있어**
            // 같은 말이 한 화면에 두 번 나왔다.
            "조아웍스 | JOAWORKS",
            color = SelahColors.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )

        // **앱 안에서 방침에 닿을 수 있어야 한다.**
        //
        // 스토어 목록에만 링크를 두는 것으로는 모자란다 — Play 정책은
        // **앱 안에서도** 개인정보처리방침에 닿을 것을 요구한다. 지금까지
        // 코드에 그 주소가 한 군데도 없었다.
        //
        // 마이크를 쓰고 소리를 파일로 남기는 앱이라 더 그렇다. 무엇이
        // 담기고 어디에 남는지를 **담기 전에** 읽을 수 있어야 한다.
        //
        // **인터넷 권한은 필요 없다.** 여는 것은 브라우저이지 이 앱이
        // 아니다 — `ACTION_VIEW` 는 권한 없이 쓸 수 있고, 그래서
        // 「서버로 보내지 않는다」는 방침이 그대로 유지된다
        // (`ManifestPromisesTest` 가 권한 목록을 못박고 있다).
        PolicyLinks(Modifier.padding(top = 12.dp, bottom = 24.dp))
    }
}

/**
 * 개인정보처리방침·이용약관으로 가는 두 줄.
 *
 * 열리지 않을 수 있다 — 브라우저가 없는 기기이거나 막혀 있으면
 * [android.content.ActivityNotFoundException] 이 난다. 그때 **조용히
 * 아무 일도 없으면** 사람은 앱이 고장 난 줄 안다. 주소를 그대로
 * 보여 주어 옮겨 적을 수 있게 한다.
 */
@Composable
internal fun PolicyLinks(
    modifier: Modifier = Modifier,
    /**
     * 주소를 여는 일. **성공했으면 true.**
     *
     * 밖에서 받는 까닭(독립 재검증 UISRFF-02 회신): 진짜 `Intent` 를
     * 안에서 만들면 이 화면은 기기 없이 시험할 수 없다. 여는 일만
     * 빼 두면 가짜를 넣어 「무슨 주소로 가려 했나」를 볼 수 있다.
     */
    openUrl: (String) -> Boolean = rememberUrlOpener(),
) {
    var failedUrl by remember { mutableStateOf<String?>(null) }

    fun go(url: String) {
        if (!openUrl(url)) failedUrl = url
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(
                onClick = { go(PRIVACY_URL) },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) {
                Text("개인정보처리방침", color = SelahColors.Accent, fontSize = 12.sp)
            }
            TextButton(
                onClick = { go(TERMS_URL) },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) {
                Text("이용약관", color = SelahColors.Accent, fontSize = 12.sp)
            }
        }
        failedUrl?.let {
            Text(
                "브라우저를 열 수 없습니다. 주소를 직접 여십시오: $it",
                color = SelahColors.Warn,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

/**
 * 브라우저로 여는 기본 방식.
 *
 * **인터넷 권한이 필요 없다.** 여는 것은 브라우저이지 이 앱이 아니다 —
 * `ACTION_VIEW` 는 권한 없이 쓸 수 있고, 그래서 「서버로 보내지
 * 않는다」는 방침의 뼈대가 그대로다.
 */
@Composable
private fun rememberUrlOpener(): (String) -> Boolean {
    val context = LocalContext.current
    return remember(context) {
        { url ->
            try {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url),
                    ),
                )
                true
            } catch (e: android.content.ActivityNotFoundException) {
                false
            }
        }
    }
}

private const val PRIVACY_URL = "https://joaworks.com/privacy/"
private const val TERMS_URL = "https://joaworks.com/terms/"

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
            "SPL 보정",
            calibration.state.labelKo,
            tone = kr.joa.selahrta.ui.theme.calibrationTone(calibration.state),
        )
        // **어디서 온 값인지 적는다.** 「기본값」 석 자만 보면 누가 언제
        // 무엇으로 잰 것인지 알 길이 없고, 그러면 그 숫자를 얼마나 믿을지
        // 정할 수 없다.
        calibration.factory?.let { f ->
            Text(
                "%+.1f dB · %s".format(f.offsetDb, f.originKo()),
                color = SelahColors.TextMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
            Text(
                "이 기기를 잰 값이 아닙니다 — 기준 소음계나 교정기로 직접 맞추면 " +
                    "그 값이 대신 걸립니다.",
                color = SelahColors.TextMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
        CalibRow(
            "주파수 응답 보정",
            when {
                curve == null -> "없음"
                // **파일이 있는 것과 걸려 있는 것은 다르다.** 꺼 두었으면
                // 「적용됨」이 아니다.
                !curve.enabled -> "${curve.fileName} · 꺼 둠"
                else -> "${curve.fileName} · 걸림"
            },
            tone = if (curve == null || !curve.enabled) SelahColors.Warn else SelahColors.InRange,
        )
    }
}

/** 이름과 값 한 줄. 색만으로 알리지 않으려고 값을 글자로 적는다(명세 11장). */
@Composable
private fun CalibRow(label: String, value: String, tone: androidx.compose.ui.graphics.Color) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = SelahColors.TextMuted, fontSize = 11.sp, softWrap = false)
        Text(
            value,
            color = tone,
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
        // **상자 안의 「측정 기기」 제목을 뺐다**(담당자 지시 2026-09-28).
        // 바로 위 구역 머리글이 같은 말을 하고 있어 한 화면에 두 번
        // 나왔다.

        if (inputs.isEmpty()) {
            Text(
                "쓸 수 있는 측정 기기를 찾지 못했습니다. 마이크 권한을 허용하면 목록이 나타납니다.",
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
                // **평소에는 아무 말도 붙이지 않는다**(담당자 지시
                // 2026-09-28). 「고른 것이 없어 내장으로 잽니다 · 눌러서
                // 바꾸기」가 늘 붙어 있었는데, 누를 수 있다는 것은 오른쪽
                // 「선택함」과 상자 모양이 이미 말한다.
                //
                // **「연결 안 됨」만 남긴다.** 그건 평소에 안 뜨는 말이고,
                // 안 뜨면 **고른 USB 마이크가 빠진 줄 모른 채** 내장으로
                // 재게 된다 — 그 세션의 숫자가 전부 다른 잣대가 된다.
                subtitle = if (pickedAbsent != null) "연결 안 됨" else null,
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
            // **「지금 열려 있는 기기」 줄을 뺐다**(담당자 지시 2026-09-28).
            //
            // 고른 것과 열린 것이 다를 수 있어 여기에 적어 두었는데,
            // 이제 **측정 화면의 「측정 기기」 칸이 재는 동안 실제로 열린
            // 것을 적는다.** 같은 말이 두 군데가 됐다.
            //
            // 열려 있지 않을 때의 줄도 함께 뺐다 — 바로 위 기기 줄이
            // 「연결 안 됨」으로 이미 말한다.
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
            // 담당자 문안(2026-09-28).
            "기기별로 보정값을 따로 저장합니다. " +
                "기기를 변경하면 선택한 기기의 보정값이 적용됩니다.",
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
    /** 이름 아래 한 줄. **null 이면 아무 말도 붙이지 않는다.** */
    subtitle: String?,
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
            // 붙일 말이 없으면 줄 자체를 두지 않는다 — 빈 줄이 남으면
            // 상자 높이만 먹는다.
            subtitle?.let { Text(it, color = SelahColors.TextMuted, fontSize = 10.sp) }
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
                            // **테마가 고른 색을 쓴다.** 예전에는 강조색을
                            // 그대로 깔고 글자색을 여기 박아 두었는데,
                            // 밝은 바탕에서는 그 진한 파랑 면이 화면에서
                            // 혼자 튀었다(담당자 지적 2026-09-28).
                            if (on) SelahColors.ChipOn else SelahColors.SurfaceVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { onPick(o) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        labelOf(o),
                        color = if (on) SelahColors.OnChipOn else SelahColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/**
 * 가중을 고를 때 그 자리에 나오는 설명 — **줄마다 다르다.**
 *
 * 2026-09-27 까지는 셋이 같은 문구를 돌려 썼다. 「A 가 여기서는 이 뜻이고
 * 저기서는 저 뜻인가」로 읽히지 않게 하려던 것이었다. **2026-09-28 에
 * 갈랐다**(담당자 문안) — 같은 A 라도 보는 것이 실제로 다르기 때문이다:
 *
 * | 줄 | A 가 하는 일 |
 * |---|---|
 * | 음압 | 청취 레벨의 잣대. **권장 범위 판정이 이 값을 본다** |
 * | PEAK | 순간 최대 레벨을 참고하는 값 |
 * | 분석 | 대역별 분포를 청감에 맞춰 보는 것 |
 *
 * **평탄하다는 사실은 여기서 말한다.** 이름 자리에 「무가중」을 끼워
 * 넣으면 같은 것을 두 가지로 부르게 된다 — 그래서 실제로 「고정된 Z 와
 * 무가중이 다른 것인가」라는 물음이 나왔다(2026-09-27).
 */
private fun analysisWeightingHelpKo(w: Weighting): String = when (w) {
    // 담당자 문안(2026-09-28). 음압·PEAK 과 또 다르다 — 여기는 값 하나가
    // 아니라 **대역별 분포**를 보는 화면이다.
    //
    // 「권장 범위 판정은 A 에서만」은 여기 없다. 그 판정은 **음압 가중**을
    // 보므로(`canJudge = splWeighting == A`) 이 줄에 적으면 틀린 말이
    // 된다 — 셋이 같은 글을 쓰던 동안에는 실제로 여기에도 떠 있었다.
    Weighting.A ->
        "사람의 청감 특성을 반영해 저주파와 매우 높은 주파수를 줄여 " +
            "표시합니다. 일반적인 소음과 사람이 느끼는 청취 레벨을 " +
            "확인할 때 적합합니다."
    Weighting.C ->
        "저주파를 A보다 덜 줄여 넓은 대역의 소리를 표시합니다. " +
            "음악·PA 시스템이나 킥·베이스처럼 저음 에너지가 많은 소리를 " +
            "확인할 때 유용합니다."
    Weighting.Z ->
        "주파수 가중을 적용하지 않고 입력된 소리를 평탄하게 표시합니다. " +
            "전체 대역의 주파수 분포와 각 대역의 에너지를 그대로 확인할 " +
            "때 적합합니다."
}

private fun peakWeightingHelpKo(w: Weighting): String = when (w) {
    // 담당자 문안(2026-09-28). 음압 쪽과 **일부러 다르다** — PEAK 은
    // 순간 최대값이라 같은 가중이라도 보는 뜻이 다르다.
    Weighting.A ->
        "사람의 청감 특성을 반영해 저주파와 매우 높은 주파수를 줄인 " +
            "PEAK 값입니다. 일반적인 소음의 순간 최대 레벨을 참고할 때 " +
            "사용합니다."
    Weighting.C ->
        "저주파를 A보다 덜 줄여 순간적인 큰 소리를 측정합니다. " +
            "킥·베이스·충격음처럼 저음 에너지가 큰 PEAK를 확인할 때 " +
            "유용합니다."
    Weighting.Z ->
        "주파수 가중을 적용하지 않고 넓은 대역의 순간 최대 음압을 그대로 " +
            "측정합니다. 신호 자체의 PEAK를 확인할 때 적합합니다."
}

private fun splWeightingHelpKo(w: Weighting): String = when (w) {
    // 담당자 문안(2026-09-28).
    //
    // **A 의 마지막 문장은 남겼다.** 권장 범위 판정은 `canJudge =
    // splWeighting == A` 라, C·Z 로 바꾸면 **계기의 색과 범위 띠가 조용히
    // 사라진다.** 그 까닭을 말하는 자리가 화면에 여기 하나뿐이다 —
    // 없으면 「색이 왜 안 뜨지」가 된다.
    Weighting.A ->
        "사람의 청감 특성을 반영해 저주파와 매우 높은 주파수를 줄여 " +
            "평가합니다. 일반적인 소음과 청취 레벨을 확인할 때 가장 널리 " +
            "사용됩니다. 권장 범위 판정은 A 에서만 합니다."
    Weighting.C ->
        "저주파를 A보다 덜 줄여 넓은 대역의 소리를 평가합니다. " +
            "저음이 많은 소리, 피크 레벨, 음악·PA 시스템을 확인할 때 " +
            "유용합니다."
    Weighting.Z ->
        "주파수 가중을 적용하지 않은 평탄한 특성으로 측정합니다. " +
            "입력된 소리의 전체 에너지와 주파수 특성을 그대로 확인할 때 " +
            "적합합니다."
}

/**
 * 가중 한 줄. 고른 칸 **바로 아래** 설명이 바뀐다.
 *
 * 창을 띄우지 않는다 — 고르면서 읽어야 뜻이 있다. 창을 띄우면 읽고
 * 닫은 뒤에 고르게 되어, 무엇을 고르는지와 그 뜻이 떨어진다.
 */
@Composable
private fun WeightingRow(
    label: String,
    whereKo: String,
    /**
     * 고른 칸 아래에 적을 설명. **줄마다 다르다**(담당자 지시
     * 2026-09-28).
     *
     * 예전에는 셋이 같은 문구를 썼다 — 「A 가 여기서는 이 뜻이고
     * 저기서는 저 뜻인가」로 읽히지 않게 하려던 것이었다. 그런데 PEAK 은
     * **순간 최대값**이라 A·C·Z 가 하는 일이 음압 때와 실제로 다르다.
     * 같은 글을 돌려 쓰면 그 다름이 가려진다.
     */
    helpKo: (Weighting) -> String,
    selected: Weighting,
    isCustom: Boolean,
    onPick: (Weighting) -> Unit,
    onReset: () -> Unit,
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
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = SelahColors.TextPrimary, fontSize = 13.sp)
                Text(whereKo, color = SelahColors.TextMuted, fontSize = 10.sp)
            }
            // **고친 줄에만 띄운다.** 늘 띄우면 「기본값인데 되돌리기가
            // 있네」로 읽혀 무엇이 바뀐 상태인지 흐려진다.
            if (isCustom) {
                TextButton(onClick = onReset) {
                    Text("기본값으로", color = SelahColors.TextMuted, fontSize = 11.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Weighting.entries.forEach { w ->
                val on = w == selected
                Box(
                    Modifier
                        .weight(1f)
                        .background(
                            if (on) SelahColors.ChipOn else SelahColors.SurfaceVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { onPick(w) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        w.unitSuffix,
                        color = if (on) SelahColors.OnChipOn else SelahColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        Text(
            helpKo(selected),
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
    }
}

/**
 * 채널마다 지금 들어오는 소리의 크기(dBFS).
 *
 * ## 왜 있는가
 *
 * 4채널 인터페이스를 꽂으면 마이크가 몇 번에 꽂혀 있는지 앱이 알 수
 * 없다. 이것이 없으면 채널을 하나씩 골라 가며 레벨이 움직이는지 봐야
 * 했다 — 마이크에 대고 말하면 여기서 바로 갈린다(USB 오디오 지시서 6장).
 *
 * ## 측정값이 아니다
 *
 * dBFS 이고 보정을 걸지 않는다. 「어디에 꽂혀 있나」를 찾는 데만 쓴다.
 *
 * ## 막대 길이
 *
 * −60dBFS 를 왼쪽 끝, 0dBFS 를 오른쪽 끝으로 본다. 그 아래는 사실상
 * 소리가 없는 자리라 더 늘여 봐야 읽을 것이 없다.
 */
@Composable
private fun ChannelLevelBars(levels: ChannelLevelSnapshot, picked: Int) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(SelahColors.Surface, RoundedCornerShape(10.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("입력마다 들어오는 소리", color = SelahColors.TextPrimary, fontSize = 13.sp)
        Text(
            "마이크에 대고 말해 보십시오. 움직이는 줄이 마이크가 꽂힌 입력입니다. " +
                "음압이 아니라 신호 세기(dBFS)입니다.",
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
        levels.peakDbfs.forEachIndexed { i, peak ->
            val on = i == picked
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Input ${i + 1}",
                    color = if (on) SelahColors.Accent else SelahColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.width(62.dp),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(10.dp)
                        .background(SelahColors.SurfaceVariant, RoundedCornerShape(5.dp)),
                ) {
                    // −60dBFS 를 0, 0dBFS 를 1 로 본다.
                    val f = ((peak + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
                    if (f > 0f) {
                        Box(
                            Modifier
                                .fillMaxWidth(f)
                                .height(10.dp)
                                .background(
                                    if (on) SelahColors.Accent else SelahColors.TextMuted,
                                    RoundedCornerShape(5.dp),
                                ),
                        )
                    }
                }
                Text(
                    // **바닥이면 숫자를 적지 않는다.** −120 을 적어 두면
                    // 「아주 조용하다」로 읽히는데, 실은 아무것도 안 들어온
                    // 것이다.
                    if (peak <= SILENCE_FLOOR_DBFS) "—" else "%.0f".format(peak),
                    color = SelahColors.TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.width(38.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    // **오른쪽 끝에 단추를 하나 달 수 있다**(담당자 지시 2026-09-28).
    //
    // 구역에 거는 일(구간 더하기 같은 것)을 목록 **아래**에 두면, 목록이
    // 길어질수록 그 단추가 화면 밖으로 밀린다. 머리글 옆이면 늘 같은
    // 자리다.
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        trailing?.invoke()
    }
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
