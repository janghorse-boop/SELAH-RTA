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
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.ui.components.SegmentRangeCard
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
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
    onSaveCalibration: (Double) -> Unit,
    onClearCalibration: () -> Unit,
    onDismissCalibrationNotice: () -> Unit,
    onWeighting: (Weighting) -> Unit,
    onTimeWeight: (TimeWeight) -> Unit,
    onLeqWindow: (LeqWindow) -> Unit,
    onPreferredInput: (String?) -> Unit,
    onAutoPreferExternal: (Boolean) -> Unit,
    onDisconnectPolicy: (DisconnectPolicy) -> Unit,
    onPickCurveFile: () -> Unit,
    onClearCurve: () -> Unit,
    onDismissCurveNotice: () -> Unit,
    onSaveRange: (ChurchSegment, SegmentRange) -> Unit,
    onResetRange: (ChurchSegment) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        SectionTitle("입력 기기")
        InputDevicePicker(
            inputs = capture.inputs,
            selectedKey = capture.meterSettings.preferredInputKey,
            openedLabel = capture.opened?.deviceLabel,
            onPick = onPreferredInput,
        )
        ChoiceRow(
            "외부 기기 자동 사용",
            "USB·유선·블루투스 마이크가 꽂히면 그쪽을 먼저 씁니다. " +
                "기기를 직접 고르면 이 설정보다 그쪽이 앞섭니다.",
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
            capture.opened?.let { "${it.sampleRate} Hz · ${it.encoding.bitsLabel}" } ?: "—",
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
            onDismissNotice = onDismissCurveNotice,
            modifier = Modifier.padding(top = 10.dp),
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
        ChurchSegment.entries.filter { it.judges }.forEach { seg ->
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
            "Real-Time Worship Audio Analyzer · made by Jesus On Air (JOA)",
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

        DeviceRow("자동으로 고르기", null, selectedKey == null, "설정에 따라 알아서", onPick)
        inputs.forEach { d ->
            DeviceRow(
                d.displayName,
                d.stableKey,
                selectedKey == d.stableKey,
                if (d.kind == MicKind.Usb) "외부 입력" else "내장",
                onPick,
            )
        }

        if (openedLabel != null) {
            Text(
                "지금 열려 있는 기기: $openedLabel",
                color = SelahColors.TextMuted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp),
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
        if (selected) {
            Text("사용 중", color = SelahColors.Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
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


