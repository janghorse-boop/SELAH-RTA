package kr.joa.selahrta.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.dsp.BandNoise
import kr.joa.selahrta.dsp.CalibrationOutcome
import kr.joa.selahrta.dsp.CurvePoint
import kr.joa.selahrta.dsp.QualityReport
import kr.joa.selahrta.dsp.QualityResult
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.dsp.calibrateResponse
import kr.joa.selahrta.dsp.judgeCalibration
import kr.joa.selahrta.ui.components.CalibrationCompareCard
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * 교정 마법사(S23 개별 교정 지시서 6장 마지막 줄).
 *
 * > `장비/CAL/+48V 안내 → 입력·DSP 점검 → 물리 마이크 판정 →
 * > 기준/대상/기준 측정 → 레벨·응답·보정치 비교 → 저장`
 *
 * ## 지금 무엇이 되어 있는가
 *
 * **비교 단계만 실제로 그린다.** 앞의 네 단계는 무엇을 하는 단계인지
 * 적어 두고, 아직 재는 기능은 붙지 않았다 — 붙지 않은 것을 「된 것처럼」
 * 그리지 않으려고 그렇게 두었다(이 저장소가 설정 화면에서도 같은
 * 판단을 했다: 「빈 화면 셋을 만들어 두면 만들어진 것처럼 보인다」).
 *
 * 비교 단계를 먼저 올린 까닭은, [CalibrationCompareCard] 가 **한 번도
 * 기기 화면에서 보인 적이 없기 때문**이다. 재는 기능을 다 붙인 뒤에
 * 처음 켜 보면 고칠 것이 한꺼번에 몰려 나온다.
 */
@Composable
fun CalibrationWizardScreen(
    /** 잰 결과. 아직 없으면 null. */
    outcome: CalibrationOutcome?,
    /** 그 측정의 판정. 결과가 없으면 null. */
    judged: QualityResult?,
    /** 예시 곡선을 보여 주는 중인가. **실제 측정과 반드시 구별해 적는다.** */
    showingExample: Boolean,
    onToggleExample: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
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

        StepLine(1, "장비 · CAL · +48V", "기준 마이크를 꽂고 CAL 파일을 불러옵니다.", done = false)
        StepLine(2, "입력 · DSP 점검", "레벨과 잔여 DSP 를 신호로 확인합니다.", done = false)
        StepLine(3, "물리 마이크 판정", "내장 마이크가 정말 갈라지는지 기기에 물어봅니다.", done = false)
        StepLine(4, "기준 → 대상 → 기준", "핑크 노이즈를 세 번에 나눠 잽니다.", done = false)
        StepLine(5, "레벨 · 응답 · 보정치 비교", "아래에서 봅니다.", done = outcome != null)
        StepLine(6, "저장", "판정을 통과해야 저장됩니다.", done = false)

        NotBuiltNotice()

        if (outcome != null && judged != null) {
            if (showingExample) ExampleBanner()
            CalibrationCompareCard(
                outcome = outcome,
                quality = judged,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Text(
                "아직 잰 것이 없습니다.",
                color = SelahColors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }

        TextButton(onClick = onToggleExample, modifier = Modifier.padding(bottom = 24.dp)) {
            Text(if (showingExample) "예시 숨기기" else "예시 곡선으로 화면 확인")
        }
    }
}

/**
 * **아직 만들지 않은 단계**임을 적는다.
 *
 * 마법사를 열었는데 단계 여섯이 늘어서 있으면 다 되는 줄 안다. 되는
 * 것과 안 되는 것을 갈라 적는 편이, 눌러 보고 아무 일도 안 일어나는
 * 것보다 낫다.
 */
@Composable
private fun NotBuiltNotice() {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SelahColors.Warn.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "아직 재는 기능이 붙지 않았습니다.",
            color = SelahColors.Warn,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "1~4 단계는 무엇을 하는 단계인지만 적어 두었습니다. 계산과 판정은 " +
                "만들어져 있고, 여기서는 그 결과를 그리는 화면을 먼저 확인합니다.",
            color = SelahColors.TextSecondary,
            fontSize = 12.sp,
            style = androidx.compose.ui.text.TextStyle(lineBreak = LineBreak.Paragraph),
        )
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

/**
 * 화면을 확인하려고 만드는 **예시** 결과.
 *
 * 실제 측정이 아니다 — 부르는 쪽이 그 사실을 화면에 적는다
 * ([ExampleBanner]).
 *
 * 모양은 흔한 내장 마이크에 가깝게 둔다: 중역은 평탄하고 저역이 조금
 * 모자라며 고역이 조금 솟는다. 그리고 **양끝은 CAL 이 덮지 않아**
 * 끊기게 한다 — 끊긴 자리를 그리는 것이 이 카드의 요점 중 하나라
 * 그것부터 눈으로 봐야 한다.
 */
fun exampleOutcome(): CalibrationOutcome {
    val n = ThirdOctave.BAND_COUNT
    val reference = (0 until n).map { CurvePoint(ThirdOctave.exactCenter(it), 70.0) }
    val internal = (0 until n).map {
        val hz = ThirdOctave.exactCenter(it)
        val db = when {
            hz < 100.0 -> 70.0 - 5.0 // 저역이 모자라다
            hz > 6_000.0 -> 70.0 + 3.0 // 고역이 솟는다
            else -> 70.0
        }
        CurvePoint(hz, db)
    }
    // SNR 과 CAL 범위가 양끝을 잘라 내는 모습까지 보이게 한다.
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

@Composable
private fun StepLine(no: Int, title: String, whatKo: String, done: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "$no",
            color = if (done) SelahColors.InRange else SelahColors.TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 1.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (done) SelahColors.TextPrimary else SelahColors.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                overflow = TextOverflow.Visible,
            )
            Text(
                whatKo,
                color = SelahColors.TextSecondary,
                fontSize = 12.sp,
                style = androidx.compose.ui.text.TextStyle(lineBreak = LineBreak.Paragraph),
            )
        }
    }
}
