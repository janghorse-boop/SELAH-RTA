package kr.joa.selahrta.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.dsp.CalibrationOutcome
import kr.joa.selahrta.dsp.QualityResult
import kr.joa.selahrta.dsp.QualityVerdict
import kr.joa.selahrta.dsp.ResponseCurve
import kr.joa.selahrta.dsp.validSegments
import kr.joa.selahrta.ui.theme.SelahColors
import kotlin.math.abs
import kotlin.math.log10

/**
 * **정규화 오프셋은 음압 차이가 아니다**(지시서 5장).
 *
 * 문구를 밖으로 뺀 까닭: 예전에 이 자리에 마크다운 별표를 쓰고
 * `.replace("**","")` 를 붙여 두었는데, `a + b.replace(...)` 는 **뒤
 * 문자열에만** 걸려서 앞부분의 별표가 그대로 화면에 나왔다(기기에서
 * 확인). 눈으로 훑는 대신 **시험이 잡게** 한다.
 */
const val LEVEL_IS_NOT_SPL_KO: String =
    "이 값은 「디지털 입력 레벨」의 차이입니다. 두 경로의 이득이 달라서 " +
        "생긴 것이라 음압(dB SPL) 차이가 아닙니다."

/** 차례로 재는 한계를 화면에 적는다(지시서 4장 마지막 줄). */
const val SEQUENTIAL_MEASURE_LIMIT_KO: String =
    "기준과 대상을 「같은 자리에 동시에」 놓을 수 없어 차례로 잽니다. " +
        "그 사이에 스피커·환경이 변하면 그만큼 오차입니다 — 기준을 앞뒤로 " +
        "두 번 재서 얼마나 변했는지 함께 봅니다."

/**
 * 교정 결과 비교(S23 개별 교정 지시서 4장).
 *
 * > EMM-6 CAL 적용 기준 곡선, 내장 마이크 원시 곡선, 레벨 정규화한 내장
 * > 마이크 곡선, 보정 적용 후 곡선을 **구분되는 색/범례로 같은 로그
 * > 주파수 그래프**에 표시한다. 사용자가 원시/정규화/보정 후 곡선을
 * > **각각 켜고 끌 수 있게** 한다.
 *
 * ## 무엇을 숨기지 않는가
 *
 * - **정규화 전 곡선을 버리지 않는다.** 정규화한 것만 보이면 두 경로의
 *   이득 차이가 얼마였는지 알 수 없고, 그 차이를 음압 차이로 오해한다.
 * - **오프셋과 기준 대역을 수치로 적는다.** 그래프만 보고는 무엇을
 *   얼마나 밀었는지 모른다.
 * - **믿을 수 없는 구간을 끊어 그린다.** 이어 그리면 잰 적 없는 대역이
 *   잰 것처럼 보인다.
 * - **절대 음압이 아니다**(지시서 5장). 레벨 차이는 디지털 입력 레벨의
 *   차이지 dB SPL 차이가 아니다.
 */
@Composable
fun CalibrationCompareCard(
    outcome: CalibrationOutcome,
    quality: QualityResult?,
    modifier: Modifier = Modifier,
) {
    /**
     * **원시 곡선은 기본으로 끈다.**
     *
     * 원시는 정규화 **전**이라 다른 셋과 높이가 수십 dB 다르다(두 경로의
     * 입력 이득이 다르니 당연하다 — 그래서 정규화를 한다). 한 축에 같이
     * 그리면 y 범위가 그 차이만큼 벌어져 **네 곡선이 모두 가운데 얇은
     * 띠로 눌린다**(기기에서 확인: 예시에서 70dB 범위가 잡혔다).
     *
     * 모양은 정규화 곡선과 같고 높이만 다르므로, 평소에는 끄고 **레벨
     * 차이를 보고 싶을 때** 켠다. 그 차이의 수치는 아래 「레벨 정규화
     * …dB」 로 늘 보인다.
     */
    var showRaw by remember { mutableStateOf(false) }
    var showNormalized by remember { mutableStateOf(true) }
    var showCorrected by remember { mutableStateOf(true) }

    Column(
        modifier
            .fillMaxWidth()
            .background(SelahColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "보정 전·후 비교",
            color = SelahColors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )

        quality?.let { VerdictBar(it) }

        CompareGraph(
            reference = outcome.reference,
            raw = outcome.internalRaw.takeIf { showRaw },
            normalized = outcome.internalNormalized.curve.takeIf { showNormalized },
            corrected = outcome.corrected.takeIf { showCorrected },
            modifier = Modifier.fillMaxWidth().height(150.dp),
        )

        // **범례가 곧 스위치다.** 눌러서 켜고 끈다.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LegendChip("기준(EMM-6)", REFERENCE, true) {}
            LegendChip("원시", RAW, showRaw) { showRaw = !showRaw }
            LegendChip("정규화", NORMALIZED, showNormalized) { showNormalized = !showNormalized }
            LegendChip("보정 후", CORRECTED, showCorrected) { showCorrected = !showCorrected }
        }

        // **오프셋을 수치로 적는다.** 그래프만으로는 알 수 없다.
        val n = outcome.internalNormalized
        Text(
            "레벨 정규화 ${"%+.1f".format(n.offsetDb)}dB · " +
                "기준 대역 ${n.bandLowHz.toInt()}~${n.bandHighHz.toInt()}Hz · " +
                "쓴 점 ${n.pointsUsed}개",
            color = SelahColors.TextSecondary,
            fontSize = 11.sp,
        )
        Text(
            LEVEL_IS_NOT_SPL_KO,
            color = SelahColors.TextMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )

        // --- 보정치 곡선 (지시서 4장 「보정치 확인」) ---
        Text(
            "보정치",
            color = SelahColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp),
        )
        CorrectionGraph(
            outcome.correction,
            outcome.settings.maxCorrectionDb,
            Modifier.fillMaxWidth().height(110.dp),
        )
        Text(
            "평활 1/${outcome.settings.smoothingFraction.toInt()}옥타브 · " +
                "상한 ±${outcome.settings.maxCorrectionDb.toInt()}dB · " +
                "믿을 수 있는 점 ${outcome.correction.validCount}/${outcome.correction.size}",
            color = SelahColors.TextSecondary,
            fontSize = 11.sp,
        )

        InfoBar(
            SEQUENTIAL_MEASURE_LIMIT_KO,
            tone = SelahColors.Warn,
        )
    }
}

@Composable
private fun VerdictBar(q: QualityResult) {
    val tone = when (q.verdict) {
        QualityVerdict.Pass -> SelahColors.InRange
        QualityVerdict.Degraded -> SelahColors.Warn
        QualityVerdict.Fail -> SelahColors.High
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (q.verdict) {
                    QualityVerdict.Pass -> "쓸 수 있습니다"
                    QualityVerdict.Degraded -> "제한적으로 쓸 수 있습니다"
                    QualityVerdict.Fail -> "다시 재야 합니다"
                },
                color = tone,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (!q.mayAutoApply) {
                Text(
                    "자동 적용 안 함",
                    color = SelahColors.TextMuted,
                    fontSize = 11.sp,
                )
            }
        }
        q.reasonsKo.forEach {
            Text("· $it", color = SelahColors.TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun LegendChip(label: String, color: Color, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(
                    if (on) color else SelahColors.SurfaceVariant,
                    RoundedCornerShape(2.dp),
                ),
        )
        Text(
            label,
            color = if (on) SelahColors.TextSecondary else SelahColors.TextMuted,
            fontSize = 11.sp,
        )
    }
}

private val REFERENCE = Color(0xFFB0BEC5)
private val RAW = Color(0xFFFFB74D)
private val NORMALIZED = Color(0xFF64B5F6)
private val CORRECTED = Color(0xFF66BB6A)

/**
 * 네 곡선을 한 로그 축에 그린다.
 *
 * **세로 범위를 그릴 곡선들에서 낸다.** 고정하면 작은 차이가 안 보이고,
 * 큰 차이는 잘린다.
 */
@Composable
private fun CompareGraph(
    reference: ResponseCurve,
    raw: ResponseCurve?,
    normalized: ResponseCurve?,
    corrected: ResponseCurve?,
    modifier: Modifier = Modifier,
) {
    val shown = listOfNotNull(reference, raw, normalized, corrected)
    var lo = Double.MAX_VALUE
    var hi = -Double.MAX_VALUE
    for (c in shown) {
        for (i in c.hz.indices) {
            if (!c.valid[i]) continue
            if (c.db[i] < lo) lo = c.db[i]
            if (c.db[i] > hi) hi = c.db[i]
        }
    }
    if (lo > hi) { lo = -5.0; hi = 5.0 }
    val pad = ((hi - lo) * 0.15).coerceAtLeast(1.0)
    val yLo = lo - pad
    val yHi = hi + pad

    Box(
        modifier
            .background(SelahColors.Background, RoundedCornerShape(8.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(8.dp))
            .padding(6.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().height(138.dp)) {
            fun x(hz: Double) =
                ((log10(hz) - log10(20.0)) / (log10(20000.0) - log10(20.0))).toFloat() * size.width
            fun y(db: Double) =
                (1f - ((db - yLo) / (yHi - yLo)).toFloat()) * size.height

            // 0dB 선
            if (0.0 in yLo..yHi) {
                drawLine(
                    SelahColors.Outline,
                    Offset(0f, y(0.0)),
                    Offset(size.width, y(0.0)),
                    strokeWidth = 1f,
                )
            }
            drawCurve(reference, REFERENCE, ::x, ::y)
            raw?.let { drawCurve(it, RAW, ::x, ::y) }
            normalized?.let { drawCurve(it, NORMALIZED, ::x, ::y) }
            corrected?.let { drawCurve(it, CORRECTED, ::x, ::y, width = 2.5f) }
        }
    }
}

/** 보정치만 따로. **상한 선을 함께 그린다** — 어디서 잘렸는지 보인다. */
@Composable
private fun CorrectionGraph(
    correction: ResponseCurve,
    maxAbsDb: Double,
    modifier: Modifier = Modifier,
) {
    val span = maxAbsDb * 1.15
    Box(
        modifier
            .background(SelahColors.Background, RoundedCornerShape(8.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(8.dp))
            .padding(6.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().height(98.dp)) {
            fun x(hz: Double) =
                ((log10(hz) - log10(20.0)) / (log10(20000.0) - log10(20.0))).toFloat() * size.width
            fun y(db: Double) = (1f - ((db + span) / (2 * span)).toFloat()) * size.height

            drawLine(
                SelahColors.Outline,
                Offset(0f, y(0.0)),
                Offset(size.width, y(0.0)),
                strokeWidth = 1f,
            )
            // 상한 선
            for (s in listOf(maxAbsDb, -maxAbsDb)) {
                drawLine(
                    SelahColors.High.copy(alpha = 0.35f),
                    Offset(0f, y(s)),
                    Offset(size.width, y(s)),
                    strokeWidth = 1f,
                )
            }
            drawCurve(correction, CORRECTED, ::x, ::y, width = 2.5f)
        }
    }
}

/**
 * **믿을 수 없는 구간에서 선을 끊는다.**
 *
 * 이어 그리면 잰 적 없는 대역이 잰 것처럼 보인다. 그 자리가 곧
 * 「보정이 걸리지 않는 곳」이다.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCurve(
    c: ResponseCurve,
    color: Color,
    x: (Double) -> Float,
    y: (Double) -> Float,
    width: Float = 1.8f,
) {
    // 토막을 나누는 일은 [validSegments] 가 한다 — 그쪽은 기기 없이
    // 돌려 볼 수 있다. 여기는 그리기만 한다.
    for (seg in validSegments(c.valid)) {
        var prev: Offset? = null
        for (i in seg) {
            val p = Offset(x(c.hz[i]), y(c.db[i]))
            prev?.let { drawLine(color, it, p, strokeWidth = width) }
            prev = p
        }
    }
}
