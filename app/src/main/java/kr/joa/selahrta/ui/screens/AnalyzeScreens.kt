package kr.joa.selahrta.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.domain.MeasureState
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.dsp.FeedbackCandidate
import kr.joa.selahrta.dsp.FeedbackEvent
import kr.joa.selahrta.dsp.FeedbackState
import kr.joa.selahrta.ui.CaptureUiState
import kr.joa.selahrta.ui.components.BandMeter
import kr.joa.selahrta.ui.components.InfoBar
import kr.joa.selahrta.ui.components.NO_VALUE
import kr.joa.selahrta.ui.components.NotYet
import kr.joa.selahrta.ui.components.ValueTile
import kr.joa.selahrta.ui.components.formatDb
import kr.joa.selahrta.ui.components.rtaRange
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * 컨셉 화면 2번 — 31밴드 1/3옥타브 RTA.
 *
 * 밴드 중심주파수는 이미 [ThirdOctave] 에 정의돼 있다. Phase 1 에서는
 * **눈금과 밴드 자리만 그린다** — 막대 높이는 값이 있어야 나오므로
 * 그리지 않는다. 가짜 막대를 흔들어 두면 Phase 5 에서 진짜가 붙었는지
 * 눈으로 구별할 수 없다.
 */
@Composable
fun RtaScreen(capture: CaptureUiState) {
    val rta = capture.rta
    val (floor, ceil) = rtaRange(rta)
    val unresolved = rta?.resolved?.indexOfFirst { it }?.takeIf { it > 0 }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        InfoBar(
            "1/3 옥타브 31밴드 · 20Hz ~ 20kHz · 가중 없음(원음 그대로)",
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        BandMeter(rta, floor, ceil, Modifier.fillMaxWidth())

        if (unresolved != null && rta != null) {
            // 흐린 막대가 무슨 뜻인지, **얼마나** 못 믿을지를 함께 적는다.
            // 참·거짓만 적으면 2dB 과 7dB 을 같은 것으로 읽게 된다.
            //
            // **「최대」라고 적지 않는다.** 이 값은 밴드 가운데 구간에 순음을
            // 놓고 잰 표본이다. 경계에 바싹 붙은 소리는 이보다 더 샌다 —
            // 1kHz 밴드조차 경계에서는 2.4dB 이 빠진다. 유한한 창을 쓰는 한
            // 피할 수 없는 일이라, 모든 밴드의 진짜 상한을 적으면 31개가 전부
            // 「못 믿음」이 되어 아무 정보도 주지 못한다(독립 재검증 F05).
            val sampled = (0 until unresolved).maxOf { rta.lossDb[it] }
            InfoBar(
                "${ThirdOctave.label(unresolved)}Hz 아래 밴드는 흐리게 그립니다. " +
                    "FFT 창이 낮은 주파수를 한 밴드 안에 다 담지 못해 에너지가 " +
                    "이웃으로 샙니다. 밴드 가운데에 순음을 놓고 재면 " +
                    "${"%.1f".format(sampled)}dB 까지 낮게 나왔습니다 — 밴드 경계 " +
                    "가까이에서는 더 샙니다.",
                Modifier.padding(top = 10.dp),
                tone = SelahColors.TextMuted,
            )
        }

        val top = rta?.let { r ->
            r.bandsSpl.indices
                .filter { r.resolved[it] }
                .maxByOrNull { r.bandsSpl[it] }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ValueTile(
                "가장 큰 대역",
                top?.let { ThirdOctave.label(it) } ?: NO_VALUE,
                "Hz",
                Modifier.weight(1f),
            )
            ValueTile(
                "그 레벨",
                top?.let { formatDb(rta.bandsSpl[it]) } ?: NO_VALUE,
                // RTA 는 늘 가중 없이 본다. 위의 큰 숫자(dBA 등)와 다른 값이므로
                // 단위에 그 사실을 적는다 — 안 적으면 두 숫자가 안 맞는다고 읽힌다.
                "dB · 가중 없음",
                Modifier.weight(1f),
            )
        }
    }
}

/**
 * 컨셉 화면 3번 — 피드백 후보.
 *
 * 명세 9장: **최대 FFT bin 하나를 하울링으로 단정하지 않는다.**
 * prominence · narrowness · level · persistence · 반복성을 조합하고,
 * 화면에는 「후보」라고 적는다. 확신하는 말투를 쓰면 담당자가 예배 중에
 * 멀쩡한 악기 소리를 깎게 된다.
 */
@Composable
fun FeedbackScreen(capture: CaptureUiState) {
    val running = capture.measure is MeasureState.Running
    val top = capture.feedback.firstOrNull()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        InfoBar(
            "솟음·좁이·레벨·지속·주파수 안정을 모두 만족할 때만 후보로 올립니다. " +
                "한 번 튄 소리는 올리지 않습니다.",
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        Column(
            Modifier
                .fillMaxWidth()
                .background(SelahColors.Surface, RoundedCornerShape(14.dp))
                .border(
                    1.dp,
                    if (top?.state == FeedbackState.Persistent) SelahColors.High else SelahColors.Outline,
                    RoundedCornerShape(14.dp),
                )
                .padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                when {
                    !running -> "측정 중이 아닙니다"
                    top == null -> "감지된 피드백 후보 없음"
                    top.state == FeedbackState.Persistent -> "피드백 후보 — 지속"
                    else -> "피드백 후보 — 의심"
                },
                color = when {
                    top?.state == FeedbackState.Persistent -> SelahColors.High
                    top != null -> SelahColors.Warn
                    else -> SelahColors.TextSecondary
                },
                fontSize = 14.sp,
            )
            Text(
                top?.let { formatHz(it.hz) } ?: NO_VALUE,
                color = if (top != null) SelahColors.TextPrimary else SelahColors.TextMuted,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                top?.let { hzUnit(it.hz) } ?: "Hz",
                color = SelahColors.TextMuted,
                fontSize = 12.sp,
            )
            if (top != null) {
                Text(
                    "${(top.durationMs / 100) / 10.0}초 이어짐 · 둘레보다 " +
                        "${"%.0f".format(top.prominenceDb)}dB 솟음",
                    color = SelahColors.TextMuted,
                    fontSize = 11.sp,
                )
            }
        }

        // 명세 9장: 화면에는 「후보」라고 적는다. 확신하는 말투를 쓰면
        // 담당자가 예배 중에 멀쩡한 악기 소리를 깎게 된다.
        if (top != null) {
            InfoBar(
                buildString {
                    // **「의심」에는 EQ 를 만지라고 하지 않는다.** 아직 짧게
                    // 스친 소리일 수 있는데 먼저 깎으라고 하면, 예배 중에
                    // 멀쩡한 악기 소리를 깎게 된다(독립 검증 P9 판단 2번).
                    // 먼저 귀로 확인하도록 안내한다.
                    if (top.state == FeedbackState.Persistent) {
                        append("소리를 들어 확인하신 뒤, 하울링이 맞으면 이 대역을 조금 내려 보십시오. ")
                    } else {
                        append("아직 짧습니다. 먼저 소리를 들어 보십시오. ")
                    }
                    if (top.hasHarmonics) {
                        append(
                            "2·3배 주파수가 함께 서 있어 악기나 목소리일 수 있습니다 — " +
                                "하울링은 대개 홀로 섭니다.",
                        )
                    } else {
                        append("배음 없이 홀로 선 소리라 하울링에 가깝습니다.")
                    }
                },
                Modifier.padding(top = 12.dp),
                tone = if (top.hasHarmonics) SelahColors.Warn else SelahColors.TextMuted,
            )
        }

        Text(
            "지금 잡고 있는 후보",
            color = SelahColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )
        if (capture.feedback.isEmpty()) {
            Text(
                if (running) "아직 없습니다." else "측정을 시작하면 여기에 나옵니다.",
                color = SelahColors.TextMuted,
                fontSize = 12.sp,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in capture.feedback) CandidateRow(c)
            }
        }

        // 명세 9장: frequency·level·prominence·duration·timestamp 를 기록한다.
        // 후보는 소리가 그치면 사라지지만 기록은 남는다 — 예배가 끝난 뒤
        // 「아까 그게 몇 Hz 였지」에 답할 수 있어야 EQ 를 만질 수 있다.
        Text(
            "이번 측정의 기록",
            color = SelahColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )
        if (capture.feedbackLog.isEmpty()) {
            Text(
                "아직 없습니다. 「지속」까지 간 것만 남깁니다.",
                color = SelahColors.TextMuted,
                fontSize = 12.sp,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (e in capture.feedbackLog) EventRow(e)
            }
        }

        Text(
            "「후보」입니다 — 오래 끄는 오르간 저음도 여기까지 올 수 있습니다. " +
                "소리를 듣고 판단하십시오.",
            color = SelahColors.TextMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
        )
    }
}

/** 기록 한 줄. 언제·어디서·얼마나였는지를 적는다. */
@Composable
private fun EventRow(e: FeedbackEvent) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(SelahColors.SurfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${formatHz(e.hz)} ${hzUnit(e.hz)}",
                color = SelahColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                // 측정을 시작한 뒤 몇 분 몇 초에 있었던 일인지 적는다.
                "${formatElapsed(e.startMs)} · ${(e.durationMs / 100) / 10.0}초 · " +
                    "솟음 ${"%.0f".format(e.maxProminenceDb)}dB" +
                    if (e.hasHarmonics) " · 배음 있음" else "",
                color = SelahColors.TextMuted,
                fontSize = 10.sp,
            )
        }
        if (e.ongoing) {
            Text(
                "울리는 중",
                color = SelahColors.High,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 측정을 시작한 뒤 흐른 시간을 분:초로 적는다. */
private fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

/** 후보 한 줄. 무엇을 근거로 올렸는지 숫자로 함께 보인다. */
@Composable
private fun CandidateRow(c: FeedbackCandidate) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(SelahColors.SurfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                "${formatHz(c.hz)} ${hzUnit(c.hz)}",
                color = SelahColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "솟음 ${"%.0f".format(c.prominenceDb)}dB · " +
                    "흔들림 ${"%.0f".format(c.driftCents)}cent · " +
                    "${(c.durationMs / 100) / 10.0}초" +
                    if (c.hasHarmonics) " · 배음 있음" else "",
                color = SelahColors.TextMuted,
                fontSize = 10.sp,
            )
        }
        // 색만으로 알리지 않는다(명세 11장).
        Text(
            c.state.labelKo,
            color = if (c.state == FeedbackState.Persistent) SelahColors.High else SelahColors.Warn,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 1kHz 아래는 Hz, 위는 kHz 로 적는다. 자릿수가 너무 길어지지 않게. */
private fun formatHz(hz: Double): String =
    if (hz < 1000) "%.0f".format(hz) else "%.2f".format(hz / 1000)

private fun hzUnit(hz: Double): String = if (hz < 1000) "Hz" else "kHz"
