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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.domain.ChurchSegment
import kr.joa.selahrta.domain.SEGMENT_CAUTIONS
import kr.joa.selahrta.domain.SegmentRange
import kr.joa.selahrta.audio.DisconnectPolicy
import kr.joa.selahrta.audio.InputDeviceInfo
import kr.joa.selahrta.audio.SignalLevel
import kr.joa.selahrta.audio.TestSignal
import kr.joa.selahrta.domain.MeasureState
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.audio.builtInMicNoticeKo
import kr.joa.selahrta.ui.components.MicProbeCard
import kr.joa.selahrta.ui.components.SegmentRangeCard
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import kr.joa.selahrta.settings.LeqWindow
import kr.joa.selahrta.ui.CaptureUiState
import kr.joa.selahrta.ui.components.CalibrationCard
import kr.joa.selahrta.ui.components.CurveCard
import kr.joa.selahrta.ui.components.SignalGeneratorCard
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
    onSaveCalibration: (Double) -> Unit,
    onClearCalibration: () -> Unit,
    onDismissCalibrationNotice: () -> Unit,
    onWeighting: (Weighting) -> Unit,
    onTimeWeight: (TimeWeight) -> Unit,
    onLeqWindow: (LeqWindow) -> Unit,
    onPreferredInput: (String?) -> Unit,
    /** 기기별로 재는 채널을 고른다. */
    onInputChannel: (String, Int) -> Unit,
    /** 내장 마이크가 갈라지는지 기기에 물어본다. */
    onProbeMicrophones: () -> Unit,
    onAutoPreferExternal: (Boolean) -> Unit,
    onDisconnectPolicy: (DisconnectPolicy) -> Unit,
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
    onPlaySignal: (TestSignal) -> Unit,
    onStopSignal: () -> Unit,
    onSignalLevel: (SignalLevel) -> Unit,
    onDismissSignalNotice: () -> Unit,
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
            onPick = onPreferredInput,
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
        // **갈리는지부터 묻는다**(S23 개별 자동교정 지시서 2·7장).
        // 기기 목록 바로 아래에 둔다 — 어느 마이크를 고를 수 있는가의
        // 바로 다음 물음이 「그게 정말 갈라지는가」이기 때문이다.
        MicProbeCard(
            report = capture.micProbe,
            running = capture.measure is MeasureState.Running,
            onProbe = onProbeMicrophones,
            modifier = Modifier.padding(top = 10.dp),
        )

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
        ChoiceRow(
            "외부 기기 자동 사용",
            "USB·유선·블루투스 마이크가 꽂히면 그쪽을 먼저 씁니다. 재는 " +
                "도중에 꽂히면 그 자리에서 측정을 끊고 새 측정을 시작합니다 — " +
                "한 측정 안에서 마이크를 바꾸면 그 앞뒤 값이 서로 다른 마이크의 " +
                "값인데 평균은 하나로 합쳐지기 때문입니다. 기기를 직접 고르면 " +
                "이 설정보다 그쪽이 앞섭니다.",
            listOf(true, false),
            capture.meterSettings.autoPreferExternal,
            { if (it) "자동" else "끔" },
            onAutoPreferExternal,
        )
        ChoiceRow(
            "기기가 빠졌을 때",
            DisconnectPolicy.entries.first {
                it == capture.meterSettings.disconnectPolicy
            }.helpKo,
            DisconnectPolicy.entries,
            capture.meterSettings.disconnectPolicy,
            { it.labelKo },
            onDisconnectPolicy,
        )

        SectionTitle("보정")
        SettingRow(
            "샘플레이트 / 형식",
            capture.inputForDisplay?.let { "${it.sampleRate} Hz · ${it.encoding.bitsLabel}" } ?: "—",
        )
        SettingRow(
            "보정 상태",
            capture.calibration.state.labelKo,
            warn = capture.calibration.isReferenceOnly,
        )

        CalibrationCard(
            capture = capture,
            onSave = onSaveCalibration,
            onClear = onClearCalibration,
            onDismissNotice = onDismissCalibrationNotice,
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

        SectionTitle("시험 신호")
        SignalGeneratorCard(
            playing = capture.playingSignal,
            level = capture.signalLevel,
            noticeKo = capture.signalNoticeKo,
            onPlay = onPlaySignal,
            onStop = onStopSignal,
            onLevel = onSignalLevel,
            onDismissNotice = onDismissSignalNotice,
        )

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
                    onSave = { onSaveRange(seg, it) },
                    onReset = { onResetRange(seg) },
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
            // 만든 사람과 소속 팀은 다른 것이다. 팀만 적으면 누가 만들었는지가
            // 사라진다.
            "Real-Time Worship Audio Analyzer\n" +
                "개발 장훈 (JANGHUN) · Jesus On Air (JOA)",
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
    onPick: (String?) -> Unit,
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

        DeviceRow(
            "자동으로 고르기",
            null,
            selectedKey == null,
            "설정에 따라 알아서",
            onPick,
            // 「자동」은 기기가 아니라 규칙이라 「사용 중」이 될 수 없다.
            nextStart = running && selectedKey == null && openedKey != null,
        )
        inputs.forEach { d ->
            val open = openedKey != null && d.stableKey == openedKey
            DeviceRow(
                d.displayName,
                d.stableKey,
                selectedKey == d.stableKey,
                if (d.kind == MicKind.Usb) "외부 입력" else "내장",
                onPick,
                inUse = open,
                nextStart = running && !open && selectedKey == d.stableKey,
            )
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
        Column {
            Text(
                title,
                color = if (selected) SelahColors.Accent else SelahColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(subtitle, color = SelahColors.TextMuted, fontSize = 10.sp)
        }
        // 색만으로 알리지 않는다(명세 11장).
        when {
            inUse -> Text(
                "사용 중",
                color = SelahColors.Accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
            nextStart -> Text(
                "다음 시작에 사용",
                color = SelahColors.Warn,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
            selected -> Text(
                "선택함",
                color = SelahColors.Accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
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


