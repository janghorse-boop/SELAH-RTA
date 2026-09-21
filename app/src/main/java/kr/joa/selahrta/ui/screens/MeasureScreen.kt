package kr.joa.selahrta.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import kr.joa.selahrta.domain.MeasureState
import kr.joa.selahrta.domain.RangeVerdict
import kr.joa.selahrta.domain.focusKo
import kr.joa.selahrta.dsp.Weighting
import kr.joa.selahrta.ui.CaptureUiState
import kr.joa.selahrta.ui.components.DiagnosticsPanel
import kr.joa.selahrta.ui.components.InfoBar
import kr.joa.selahrta.ui.components.InputLevelBar
import kr.joa.selahrta.ui.components.NO_VALUE
import kr.joa.selahrta.ui.components.ValueTile
import kr.joa.selahrta.ui.components.VerdictBadge
import kr.joa.selahrta.ui.components.formatDb
import kr.joa.selahrta.ui.components.levelColor
import kr.joa.selahrta.ui.nav.ViewMode
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * 컨셉 화면 1번 — 큰 현재 음압, 구간 판정, Leq/MAX/PEAK.
 *
 * 보정 전에는 숫자를 흐리게 그리고 단위에 「참고용」을 붙인다. 폰 마이크의
 * 감도를 모르는 상태라 ±10dB 넘게 틀릴 수 있기 때문이다(명세 1장).
 *
 * 판정은 **A 가중이고 판정하는 구간일 때만** 한다. C·Z 값을 dBA 기준
 * 범위와 견주면 저음이 큰 찬양에서 늘 「높음」이 뜬다.
 */
@Composable
fun MeasureScreen(
    mode: ViewMode,
    capture: CaptureUiState,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismissDeviceNotice: () -> Unit = {},
) {
    val segment = capture.meterSettings.segment
    val range = capture.meterSettings.rangeFor(segment)

    val running = capture.measure is MeasureState.Running
    val m = capture.meter
    val weighting = capture.meterSettings.weighting
    val uncalibrated = capture.calibration.isReferenceOnly

    // **참고 범위는 dBA 기준이다.** A 가중일 때만 견준다 — C 나 Z 값을
    // dBA 범위와 견주면 저음이 큰 찬양에서 늘 「높음」이 뜬다.
    // 판정에는 순간값이 아니라 Leq 를 쓴다(범위 자체가 평균 기준이다).
    // 이제 구간은 설교·찬양 둘뿐이고 둘 다 판정한다.
    val judged = if (weighting == Weighting.A) m.leqLong ?: m.leqShort else null
    val verdict = when {
        judged == null || range == null -> RangeVerdict.Unknown
        judged < range.avgLowDb -> RangeVerdict.Low
        judged > range.avgHighDb -> RangeVerdict.High
        else -> RangeVerdict.InRange
    }

    // **큰 숫자 자체도 물들인다.** 색이 곧 「지금 볼륨이 어디쯤인가」다.
    // 다만 **판정 배지와는 다른 양**이다 — 배지는 평균(Leq)을, 숫자는
    // 지금 값을 견준다. 그래서 둘의 색이 잠깐 어긋날 수 있고, 그게 맞다.
    // A 가중일 때만 칠한다(같은 까닭으로 판정도 그렇다).
    val liveColor = levelColor(
        if (weighting == Weighting.A) m.currentSpl else null,
        range?.avgLowDb,
        range?.avgHighDb,
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            !hasPermission -> InfoBar(
                "소리의 크기를 재려면 마이크 권한이 필요합니다. " +
                    "이 앱은 소리를 저장하지 않고 음압과 주파수만 계산합니다.",
                Modifier.padding(top = 4.dp, bottom = 12.dp),
                tone = SelahColors.Warn,
            )

            capture.errorKo != null -> InfoBar(
                capture.errorKo,
                Modifier.padding(top = 4.dp, bottom = 12.dp),
                tone = SelahColors.High,
            )

            running -> {
                val f = capture.opened
                // 시작 직후에는 두 가지가 아직 정해지지 않았다: 어느 마이크로
                // 붙었는지(R01)와 바늘이 자리를 잡았는지(R07). 둘 다 그 사이의
                // 숫자를 측정값이라 부르면 안 되는 상태라 먼저 알린다.
                val warming = when {
                    f != null && !f.routeConfirmed ->
                        "어느 마이크로 열렸는지 확인하는 중입니다. 확인되기 전에는 " +
                            "그 기기의 보정값을 걸지 않습니다."
                    !capture.meter.settled && capture.meter.currentSpl != null ->
                        "레벨이 자리를 잡는 중입니다. 시작 직후 잠깐은 실제보다 " +
                            "낮게 나옵니다."
                    else -> null
                }
                val tone = when {
                    warming != null -> SelahColors.Warn
                    f?.trustIsWarning == true -> SelahColors.Warn
                    else -> SelahColors.InRange
                }
                if (warming != null || f == null) {
                    InfoBar(warming ?: "재고 있습니다.", Modifier.padding(top = 4.dp, bottom = 12.dp), tone = tone)
                } else {
                    // **한 줄로 접어 둔다.** 세 줄짜리 안내가 예배 내내
                    // 자리를 차지해 숫자와 버튼을 아래로 밀었다(기기에서
                    // 확인). 눌러서 펴면 원래 문구가 그대로 나온다 —
                    // 줄이되 지우지 않는다.
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    InfoBar(
                        if (expanded) f.trustNoteKo else f.trustShortKo,
                        Modifier
                            .padding(top = 4.dp, bottom = 12.dp)
                            .then(
                                if (f.trustHasDetail) {
                                    Modifier.clickable { expanded = !expanded }
                                } else {
                                    Modifier
                                }
                            ),
                        tone = tone,
                        trailingKo = if (!f.trustHasDetail) null else if (expanded) "접기" else "자세히",
                    )
                }
            }

            else -> InfoBar(
                "아래 버튼을 눌러 마이크를 엽니다.",
                Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
        }

        // 기기가 바뀌거나 빠진 일은 숫자보다 먼저 알려야 한다 —
        // 그 뒤의 값이 다른 마이크의 값일 수 있기 때문이다.
        capture.deviceNoticeKo?.let {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InfoBar(it, Modifier.weight(1f), tone = SelahColors.Warn)
                TextButton(onClick = onDismissDeviceNotice) {
                    Text("확인", color = SelahColors.Accent, fontSize = 12.sp)
                }
            }
        }

        Box(contentAlignment = Alignment.Center) {
            // 눈금은 40~110 dB. 예배당에서 실제로 오가는 범위다.
            GaugeArc(
                fraction = m.currentSpl?.let { ((it - 40.0) / 70.0).toFloat() },
                modifier = Modifier.size(260.dp, 150.dp),
                color = liveColor ?: SelahColors.InRange,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    formatDb(m.currentSpl),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    // 미보정 값은 흐리게 그린다. 보정된 값과 같은 밝기로 띄우면
                    // 둘의 무게가 같아 보인다 — 하나는 잰 값이고 하나는 짐작이다.
                    //
                    // **색은 바가 맡는다.** 숫자까지 물들이면 화면에 색이 두 번
                    // 나와 어느 쪽을 읽어야 할지 흩어진다. 숫자는 값을, 바는
                    // 그 값이 범위의 어디쯤인지를 말한다.
                    color = when {
                        m.currentSpl == null -> SelahColors.TextMuted
                        uncalibrated -> SelahColors.TextSecondary
                        else -> SelahColors.TextPrimary
                    },
                )
                Text(
                    if (uncalibrated) "${weighting.unitSuffix} · 참고용" else weighting.unitSuffix,
                    fontSize = 14.sp,
                    color = if (uncalibrated) SelahColors.Warn else SelahColors.TextSecondary,
                )
            }
        }

        // **범위 안이면 배지를 띄우지 않는다.** 초록 바가 이미 그 말을 하고
        // 있어 「적정」은 같은 말을 두 번 하는 것이다.
        //
        // 명세 11장은 「색만으로 알리지 않고 늘 글자를 함께 쓴다」고 한다.
        // 그래서 **알려야 할 것에는 글자를 남긴다** — 낮음·높음, 그리고
        // 판정할 수 없는 까닭. 지우는 것은 「괜찮다」 하나뿐이고, 그것은
        // 경고의 부재로도 읽힌다.
        if (verdict != RangeVerdict.InRange) {
            VerdictBadge(
                verdict,
                Modifier.padding(top = 12.dp),
                unknownLabel = when {
                    !running -> "측정 안 함"
                    weighting != Weighting.A -> "판정 보류 — ${weighting.labelKo}"
                    else -> "평균을 모으는 중"
                },
            )
        }

        if (range != null) {
            Text(
                "${segment.shortKo} 권장 범위 " +
                    "${range.avgLowDb.toInt()} ~ ${range.avgHighDb.toInt()} dBA" +
                    if (capture.meterSettings.isCustom(segment)) " (고친 값)" else "",
                color = SelahColors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                "보편적 기준이 아니라 참고값입니다. 설정에서 바꿀 수 있습니다.",
                color = SelahColors.TextMuted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        // 구간을 고르는 줄은 **없앴다.** 화면 위쪽 칩(설교·찬양)이 곧
        // 구간이라, 같은 것을 고르는 줄이 둘이면 어느 쪽이 진짜인지
        // 알 수 없다. 기도·자유 측정은 쓰는 자리가 없어 걷어냈다.
        Text(
            segment.focusKo,
            color = SelahColors.TextMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ValueTile(
                "Leq (${capture.meterSettings.leqWindow.labelKo})",
                formatDb(m.leqLong),
                // 창이 아직 안 찼으면 그 사실을 적는다 — 「1분 평균」이라고
                // 적어 놓고 실제로는 10초치인 값을 보여 주면 안 된다.
                if (m.leqLong != null && !m.leqLongFull) "모으는 중" else weighting.unitSuffix,
                Modifier.weight(1f),
            )
            ValueTile("MAX", formatDb(m.maxSpl), weighting.unitSuffix, Modifier.weight(1f))
            // 잘린 피크는 측정값이 아니라 하한이다. 「≥」를 붙여 그 사실을
            // 숫자 옆에 적는다 — 각주로 미루면 아무도 안 읽는다.
            //
            // **단위는 가중과 무관하다.** PEAK 는 가중 전 파형의 최대라
            // A 로 바꿔도 숫자가 그대로인데, 거기에 dBA 를 붙이면 MAX·Leq 와
            // 같은 가중의 값처럼 읽힌다. 125Hz 순음에서 A 가중은 16dB 을
            // 깎지만 PEAK 는 꿈쩍도 안 한다(독립 검증 R10). 클리핑은 입력단의
            // 사건이라 가중 전에서 재는 것이고, 그래서 표기도 고정이다.
            ValueTile(
                "PEAK",
                if (m.peakClipped && m.peakSpl != null) "≥${formatDb(m.peakSpl)}" else formatDb(m.peakSpl),
                if (m.peakClipped) "잘림 · 가중없음" else "dB 가중없음",
                Modifier.weight(1f),
            )
        }

        // 저역이 얼마나 많은가(명세 10장). 찬양에서 특히 중요하다 —
        // A 가중 숫자만 보면 저음이 많은지 전혀 드러나지 않는다.
        if (running && m.cMinusA != null && m.lowEnergyHint != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .background(SelahColors.Surface, RoundedCornerShape(10.dp))
                    .border(1.dp, SelahColors.Outline, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("저역 비중 (C-A)", color = SelahColors.TextMuted, fontSize = 11.sp)
                    Text(
                        "%+.1f dB · %s".format(m.cMinusA, m.lowEnergyHint.labelKo),
                        color = SelahColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    m.lowEnergyHint.noteKo,
                    color = SelahColors.TextMuted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
            }
        }

        if (m.anyClipping) {
            InfoBar(
                "소리가 너무 커서 파형이 잘린 구간이 있습니다. 그 구간의 음압은 " +
                    "화면 값보다 높으며 얼마나 높은지는 알 수 없습니다. " +
                    "마이크를 소리원에서 떼어 놓으십시오.",
                Modifier.padding(top = 12.dp),
                tone = SelahColors.High,
            )
        }

        // **버튼 하나로 여닫는다.** 둘을 나란히 두었더니, 재는 동안에는
        // 안내가 길어져 두 버튼이 화면 밖으로 밀렸다 — 「한 화면에
        // 보인다」는 목적을 오히려 못 지키고, 누를 수 없는 버튼이 자리만
        // 차지했다. 사용자가 토글 하나로 돌리라고 정했다.
        Button(
            onClick = {
                when {
                    !hasPermission -> onRequestPermission()
                    running -> onStop()
                    else -> onStart()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) SelahColors.SurfaceVariant else SelahColors.Accent,
                contentColor = if (running) SelahColors.TextPrimary else Color(0xFF00201C),
            ),
        ) {
            Text(
                when {
                    !hasPermission -> "마이크 권한 허용하기"
                    running -> "측정 종료"
                    else -> "측정 시작"
                },
                fontWeight = FontWeight.Bold,
            )
        }

        if (running) {
            InputLevelBar(
                capture.diagnostics.lastPeakAbs,
                Modifier.fillMaxWidth().padding(top = 18.dp),
            )
        }

        capture.inputForDisplay?.let {
            DiagnosticsPanel(it, capture.diagnostics, Modifier.padding(top = 16.dp, bottom = 24.dp))
        }
    }
}

/**
 * 반원 계기. [fraction] 이 null 이면 **눈금만 그리고 바늘을 그리지 않는다.**
 *
 * 값이 없을 때 바늘을 0 에 두면 「0 dB 을 재고 있다」로 읽힌다. 바늘이
 * 아예 없어야 재지 않는다는 뜻이 된다.
 *
 * [color] 는 **지금 값이 권장 범위의 어디쯤인가**를 나타낸다. 바가 길어
 * 지면서 색도 함께 건너간다 — 눈이 먼저 잡는 것은 길이와 색이지 숫자가
 * 아니다. 판정할 수 없으면(가중치가 A 가 아니거나 범위가 없으면) 기본
 * 색으로 둔다.
 */
@Composable
private fun GaugeArc(
    fraction: Float?,
    modifier: Modifier = Modifier,
    color: Color = SelahColors.InRange,
) {
    Canvas(modifier) {
        val stroke = 14.dp.toPx()
        val w = size.width
        val h = size.height
        val d = minOf(w, h * 2f) - stroke
        val topLeft = Offset((w - d) / 2f, stroke / 2f)
        val arcSize = Size(d, d)

        drawArc(
            color = SelahColors.SurfaceVariant,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )

        if (fraction != null) {
            drawArc(
                color = color,
                startAngle = 180f,
                sweepAngle = 180f * fraction.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}
