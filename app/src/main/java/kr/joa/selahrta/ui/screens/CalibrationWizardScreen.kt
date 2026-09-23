package kr.joa.selahrta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.calibration.CalInfo
import kr.joa.selahrta.calibration.StepGate
import kr.joa.selahrta.calibration.WizardState
import kr.joa.selahrta.calibration.WizardStep
import kr.joa.selahrta.calibration.gateFor
import kr.joa.selahrta.dsp.BandNoise
import kr.joa.selahrta.dsp.CalibrationOutcome
import kr.joa.selahrta.dsp.CurvePoint
import kr.joa.selahrta.dsp.CurveReading
import kr.joa.selahrta.dsp.CurveShape
import kr.joa.selahrta.dsp.QualityReport
import kr.joa.selahrta.dsp.QualityResult
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.dsp.calibrateResponse
import kr.joa.selahrta.dsp.judgeCalibration
import kr.joa.selahrta.ui.components.CalReadingCard
import kr.joa.selahrta.ui.components.CalibrationCompareCard
import kr.joa.selahrta.ui.components.InfoBar
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * 교정 마법사(지시서 7장).
 *
 * ## 지금 무엇이 되어 있는가
 *
 * **1단계와 5단계가 실제로 돈다.** 1단계는 기준 CAL 을 불러와 읽는 법을
 * 정하고(독립 검토 R04), 5단계는 잰 결과를 그린다. 2~4단계는 무엇을 하는
 * 단계인지 적어 두었고 아직 재지 않는다.
 *
 * 없는 것을 된 것처럼 그리지 않는다 — 단계 여섯이 늘어서 있으면 다 되는
 * 줄 알고, 눌러 보고 아무 일도 안 일어나는 쪽이 더 나쁘다.
 */
@Composable
fun CalibrationWizardScreen(
    state: WizardState,
    /** 불러온 곡선의 모양 단서. 없으면 null. */
    shape: CurveShape?,
    noticeKo: String?,
    /** 지금 무엇을 하는 중인가. null 이면 놀고 있다. */
    busyKo: String?,
    /** 재고 있는가. 마이크가 안 열려 있으면 점검을 시작할 수 없다. */
    canMeasure: Boolean,
    /** 잰 결과. 아직 없으면 null. */
    outcome: CalibrationOutcome?,
    judged: QualityResult?,
    showingExample: Boolean,
    onPickCalFile: () -> Unit,
    onChooseReading: (CurveReading) -> Unit,
    onPhantom: (Boolean) -> Unit,
    onRunInputCheck: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onGoTo: (WizardStep) -> Unit,
    onToggleExample: () -> Unit,
    onDismissNotice: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gate = gateFor(state, state.step)

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "교정 마법사",
                color = SelahColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = onClose) { Text("닫기") }
        }

        if (noticeKo != null) {
            InfoBar(
                noticeKo,
                tone = SelahColors.Warn,
                trailingKo = "닫기",
                modifier = Modifier.clickable { onDismissNotice() },
            )
        }

        WizardStep.entries.forEach { step ->
            StepLine(
                step = step,
                current = step == state.step,
                done = step.ordinal < state.step.ordinal,
                onClick = { onGoTo(step) },
            )
        }

        // 지금 단계의 알맹이.
        when (state.step) {
            WizardStep.Equipment -> EquipmentStep(
                cal = state.cal,
                shape = shape,
                phantom = state.phantomAcknowledged,
                onPickCalFile = onPickCalFile,
                onChooseReading = onChooseReading,
                onPhantom = onPhantom,
            )

            WizardStep.InputCheck -> InputCheckStep(
                state = state,
                busyKo = busyKo,
                canRun = canMeasure,
                onRun = onRunInputCheck,
            )

            WizardStep.Review -> ReviewStep(outcome, judged, showingExample, onToggleExample)

            else -> NotBuiltNotice(state.step)
        }

        GateLine(gate)

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.step.ordinal > 0) {
                TextButton(onClick = onBack) { Text("뒤로") }
            }
            if (state.step != WizardStep.entries.last()) {
                TextButton(onClick = onNext, enabled = gate.passable) {
                    Text(if (gate is StepGate.AllowedWithWarning) "그래도 다음" else "다음")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ----------------------------------------------------------------------
// 1단계
// ----------------------------------------------------------------------

@Composable
private fun EquipmentStep(
    cal: CalInfo?,
    shape: CurveShape?,
    phantom: Boolean,
    onPickCalFile: () -> Unit,
    onChooseReading: (CurveReading) -> Unit,
    onPhantom: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(SelahColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "기준 마이크 CAL",
                color = SelahColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (cal == null) {
                    "EMM-6 에 딸려 온 개별 CAL 파일을 불러옵니다. 이 파일이 교정의 기준입니다."
                } else {
                    "${cal.fileName} · 점 ${cal.pointCount}개 · " +
                        "${cal.lowestHz.toInt()}Hz ~ ${(cal.highestHz / 1000).toInt()}kHz"
                },
                color = SelahColors.TextSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                style = TextStyle(lineBreak = LineBreak.Paragraph),
            )
            TextButton(onClick = onPickCalFile) {
                Text(if (cal == null) "CAL 파일 불러오기" else "다른 파일로 바꾸기")
            }
        }

        if (cal != null) {
            CalReadingCard(
                reading = cal.reading,
                chosen = cal.readingChosenByPerson,
                questionKo = cal.readingQuestionKo,
                shape = shape,
                onChoose = onChooseReading,
            )
        }

        PhantomRow(phantom, onPhantom)
    }
}

/**
 * 팬텀전원은 **확인이 아니라 듣는 것이다.**
 *
 * 앱은 +48V 상태를 알 수 없다(지시서 3장: 「제어하거나 확정 표시하지
 * 않는다」). 그래도 묻는 까닭은, 안 켜면 신호가 아예 안 들어와 사람이
 * 한참을 헤매기 때문이다.
 */
@Composable
private fun PhantomRow(phantom: Boolean, onPhantom: (Boolean) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SelahColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "팬텀전원 (+48V)",
                color = SelahColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Switch(
                checked = phantom,
                onCheckedChange = onPhantom,
                colors = SwitchDefaults.colors(checkedTrackColor = SelahColors.InRange),
            )
        }
        Text(
            PHANTOM_NOTE_KO,
            color = SelahColors.TextMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            style = TextStyle(lineBreak = LineBreak.Paragraph),
        )
    }
}

const val PHANTOM_NOTE_KO: String =
    "UMC404HD 뒷면의 +48V 스위치를 직접 켜 주십시오. 앱은 팬텀전원이 켜졌는지 " +
        "알 수 없습니다 — 여기 켜 두는 것은 확인이 아니라 「켰다」고 적어 두는 " +
        "것입니다. 안 켜면 EMM-6 에서 신호가 아예 들어오지 않습니다."

// ----------------------------------------------------------------------
// 5단계
// ----------------------------------------------------------------------

@Composable
private fun ReviewStep(
    outcome: CalibrationOutcome?,
    judged: QualityResult?,
    showingExample: Boolean,
    onToggleExample: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (outcome != null && judged != null) {
            if (showingExample) ExampleBanner()
            CalibrationCompareCard(outcome = outcome, quality = judged)
        } else {
            InfoBar("아직 잰 것이 없습니다.", tone = SelahColors.TextSecondary)
        }
        TextButton(onClick = onToggleExample) {
            Text(if (showingExample) "예시 숨기기" else "예시 곡선으로 화면 확인")
        }
    }
}

/** 예시임을 **숨기지 않는다.** 실제 측정으로 오해하면 안 된다. */
@Composable
private fun ExampleBanner() {
    Text(
        "아래는 예시 곡선입니다 — 실제로 잰 값이 아닙니다.",
        color = SelahColors.Warn,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .background(SelahColors.Warn.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

// ----------------------------------------------------------------------
// 공통
// ----------------------------------------------------------------------

/** **관문이 무엇을 말하는지 그대로 보인다.** 막힌 채로 까닭이 없으면 헤맨다. */
@Composable
private fun GateLine(gate: StepGate) {
    if (gate.reasonsKo.isEmpty()) return
    val tone = if (gate.passable) SelahColors.Warn else SelahColors.Warn
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        gate.reasonsKo.forEach { InfoBar(it, tone = tone) }
    }
}

@Composable
private fun NotBuiltNotice(step: WizardStep) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SelahColors.Warn.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "이 단계는 아직 재지 않습니다.",
            color = SelahColors.Warn,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            step.whatKo,
            color = SelahColors.TextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            style = TextStyle(lineBreak = LineBreak.Paragraph),
        )
    }
}

@Composable
private fun StepLine(
    step: WizardStep,
    current: Boolean,
    done: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = done || current, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "${step.number}",
            color = when {
                done -> SelahColors.InRange
                current -> SelahColors.TextPrimary
                else -> SelahColors.TextMuted
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 1.dp),
        )
        Text(
            step.titleKo,
            color = if (current) SelahColors.TextPrimary else SelahColors.TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ----------------------------------------------------------------------
// 화면을 확인하려고 만드는 예시 — 실제 측정이 아니다
// ----------------------------------------------------------------------

/**
 * 모양은 흔한 내장 마이크에 가깝게 둔다: 중역은 평탄하고 저역이 조금
 * 모자라며 고역이 조금 솟는다. **양끝은 CAL 이 덮지 않아** 끊기게 한다 —
 * 끊긴 자리를 그리는 것이 이 카드의 요점 중 하나라 그것부터 봐야 한다.
 */
fun exampleOutcome(): CalibrationOutcome {
    val n = ThirdOctave.BAND_COUNT
    val reference = (0 until n).map { CurvePoint(ThirdOctave.exactCenter(it), 70.0) }
    val internal = (0 until n).map {
        val hz = ThirdOctave.exactCenter(it)
        val db = when {
            hz < 100.0 -> 70.0 - 5.0
            hz > 6_000.0 -> 70.0 + 3.0
            else -> 70.0
        }
        CurvePoint(hz, db)
    }
    val usable = BooleanArray(n) { ThirdOctave.exactCenter(it) in 50.0..14_000.0 }
    return calibrateResponse(
        referencePoints = reference,
        internalPoints = internal,
        referenceValid = usable,
        internalValid = usable,
    )
}

/** 예시 결과의 판정. 카드의 판정 막대를 보려면 필요하다. */
fun exampleJudgement(outcome: CalibrationOutcome): QualityResult {
    val n = ThirdOctave.BAND_COUNT
    val bands = (0 until n).map { BandNoise(ThirdOctave.exactCenter(it), 70.0, 45.0) }
    return judgeCalibration(
        QualityReport(
            bands = bands,
            repeatSpreadDb = 0.6,
            referenceDriftDb = 0.2,
            referenceBandDriftDb = 0.4,
            minFramesPerStep = 16,
            referenceBands = bands,
            referenceCalRangeHz = 50.0..14_000.0,
            dspVerifiedBySignal = true,
        ),
        outcome,
    )
}

// ----------------------------------------------------------------------
// 2단계
// ----------------------------------------------------------------------

@Composable
private fun InputCheckStep(
    state: WizardState,
    busyKo: String?,
    canRun: Boolean,
    onRun: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SelahColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "입력 · DSP 점검",
            color = SelahColors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            INPUT_CHECK_HOW_KO,
            color = SelahColors.TextSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            style = TextStyle(lineBreak = LineBreak.Paragraph),
        )

        if (!canRun) {
            InfoBar(NOT_MEASURING_KO, tone = SelahColors.Warn)
        }

        if (busyKo != null) {
            InfoBar(busyKo, tone = SelahColors.InRange)
        } else {
            TextButton(onClick = onRun, enabled = canRun) {
                Text(if (state.dsp == null) "점검하기" else "다시 점검하기")
            }
        }

        val dsp = state.dsp
        if (dsp != null) {
            Text(
                dspNumbersKo(dsp),
                color = SelahColors.TextMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                style = TextStyle(lineBreak = LineBreak.Paragraph),
            )
        }
        if (state.noiseFloorDb != null) {
            Text(
                "주변 잡음 광대역 %.1fdBFS 로 쟀습니다.".format(
                    kr.joa.selahrta.dsp.broadbandDb(state.noiseFloorDb.toDoubleArray()),
                ),
                color = SelahColors.TextMuted,
                fontSize = 11.sp,
            )
        }
    }
}

const val INPUT_CHECK_HOW_KO: String =
    "먼저 조용한 상태에서 주변 잡음을 재고, 이어서 핑크 노이즈를 틀어 " +
        "이득과 스펙트럼이 시간에 따라 변하는지 봅니다. 변하면 AGC·NS 가 " +
        "남아 있다는 뜻이라 교정이 흔들립니다."

const val NOT_MEASURING_KO: String =
    "재는 중이 아닙니다. 「측정」 화면에서 측정을 시작한 뒤 돌아오십시오. " +
        "마이크가 열려 있어야 점검할 수 있습니다."

/** 수치를 **그대로** 보인다. 「의심됨」만으로는 무엇을 고칠지 알 수 없다. */
fun dspNumbersKo(dsp: kr.joa.selahrta.dsp.DspProbeResult): String {
    val drift = dsp.broadbandDriftDb?.let { "%.1f".format(it) } ?: "모름"
    val shape = dsp.bandShapeDriftDb?.let { "%.1f".format(it) } ?: "모름"
    return "장 ${dsp.framesUsed}개 · 본 대역 ${dsp.bandsConsidered}개 · " +
        "이득 변화 ${drift}dB · 모양 변화 ${shape}dB"
}
