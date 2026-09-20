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
import kr.joa.selahrta.domain.ChurchMode
import kr.joa.selahrta.domain.MeasureState
import kr.joa.selahrta.domain.RangeVerdict
import kr.joa.selahrta.domain.ReferenceRanges
import kr.joa.selahrta.domain.verdictFor
import kr.joa.selahrta.dsp.Weighting
import kr.joa.selahrta.ui.CaptureUiState
import kr.joa.selahrta.ui.components.DiagnosticsPanel
import kr.joa.selahrta.ui.components.InfoBar
import kr.joa.selahrta.ui.components.InputLevelBar
import kr.joa.selahrta.ui.components.NO_VALUE
import kr.joa.selahrta.ui.components.ValueTile
import kr.joa.selahrta.ui.components.VerdictBadge
import kr.joa.selahrta.ui.components.formatDb
import kr.joa.selahrta.ui.nav.ViewMode
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * 컨셉 화면 1번 — 큰 현재 음압, 판정, LAeq/MAX/PEAK.
 *
 * Phase 2 에서 실제 PCM 이 들어오기 시작했지만 **음압 숫자는 아직 없다.**
 * dBFS 를 dB SPL 로 옮기려면 보정이 필요하고, 보정 없이 그럴듯한 82.4 를
 * 띄우면 그게 거짓말이 된다(명세 0장·1장). 그래서 큰 숫자는 여전히 「—」이고,
 * 대신 **입력 레벨**이 움직인다 — 마이크가 실제로 소리를 받고 있다는 사실만
 * 보여 주는, 음압이라고 주장하지 않는 눈금이다.
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
    val church = if (mode == ViewMode.Worship) ChurchMode.Worship else ChurchMode.Sermon
    val range = ReferenceRanges.forMode(church)

    val running = capture.measure is MeasureState.Running
    val m = capture.meter
    val weighting = capture.meterSettings.weighting
    val uncalibrated = capture.calibration.isReferenceOnly

    // **참고 범위는 dBA 기준이다.** A 가중일 때만 견준다 — C 나 Z 값을
    // dBA 범위와 견주면 저음이 큰 찬양에서 늘 「높음」이 뜬다.
    // 판정에는 순간값이 아니라 Leq 를 쓴다(범위 자체가 평균 기준이다).
    val judged = if (weighting == Weighting.A) m.leqLong ?: m.leqShort else null
    val verdict = range.verdictFor(judged)

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
                InfoBar(
                    f?.trustNoteKo ?: "재고 있습니다.",
                    Modifier.padding(top = 4.dp, bottom = 12.dp),
                    tone = if (f?.trustIsWarning == true) SelahColors.Warn else SelahColors.InRange,
                )
            }

            else -> InfoBar(
                "아래 버튼을 눌러 마이크를 엽니다. 음압 숫자는 보정이 붙는 Phase 3 부터 나옵니다.",
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
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    formatDb(m.currentSpl),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    // 미보정 값은 흐리게 그린다. 보정된 값과 같은 밝기로 띄우면
                    // 둘의 무게가 같아 보인다 — 하나는 잰 값이고 하나는 짐작이다.
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

        VerdictBadge(
            verdict,
            Modifier.padding(top = 12.dp),
            unknownLabel = when {
                !running -> "측정 안 함"
                weighting != Weighting.A -> "판정 보류 — ${weighting.labelKo}"
                else -> "평균을 모으는 중"
            },
        )

        if (range != null) {
            Text(
                "${church.labelKo} 권장 범위 " +
                    "${range.avg.start.toInt()} ~ ${range.avg.endInclusive.toInt()} dBA",
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
            ValueTile(
                "PEAK",
                if (m.peakClipped && m.peakSpl != null) "≥${formatDb(m.peakSpl)}" else formatDb(m.peakSpl),
                if (m.peakClipped) "잘림" else weighting.unitSuffix,
                Modifier.weight(1f),
            )
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
                    running -> "측정 멈추기"
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

        capture.opened?.let {
            DiagnosticsPanel(it, capture.diagnostics, Modifier.padding(top = 16.dp, bottom = 24.dp))
        }
    }
}

/**
 * 반원 계기. [fraction] 이 null 이면 **눈금만 그리고 바늘을 그리지 않는다.**
 *
 * 값이 없을 때 바늘을 0 에 두면 「0 dB 을 재고 있다」로 읽힌다. 바늘이
 * 아예 없어야 재지 않는다는 뜻이 된다.
 */
@Composable
private fun GaugeArc(fraction: Float?, modifier: Modifier = Modifier) {
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
                color = SelahColors.InRange,
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
