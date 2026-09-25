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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kr.joa.selahrta.ui.nav.NavSection
import kr.joa.selahrta.ui.nav.ViewMode
import kr.joa.selahrta.ui.CaptureUiState
import androidx.compose.ui.platform.LocalConfiguration
import kr.joa.selahrta.ui.components.BAND_SLOT_WIDE
import kr.joa.selahrta.ui.components.formatHz
import kr.joa.selahrta.ui.components.hzUnit
import kr.joa.selahrta.ui.components.BandMeter
import kr.joa.selahrta.ui.components.InfoBar
import kr.joa.selahrta.ui.components.NO_VALUE
import kr.joa.selahrta.ui.components.NotYet
import kr.joa.selahrta.ui.components.ValueTile
import kr.joa.selahrta.ui.components.formatDb
import kr.joa.selahrta.ui.components.rtaRange
import kr.joa.selahrta.ui.components.SpectrumChart
import kr.joa.selahrta.ui.components.spectrumRange
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
fun RtaScreen(
    capture: CaptureUiState,
    /** 분석 모드를 바꾼다. 고르개는 차트 상자 안에 있다. */
    onMode: (ViewMode) -> Unit = {},
) {
    val rta = capture.rta
    val (floor, ceil) = rtaRange(rta)
    // 축을 SPL 이라 부를 수 있는가. 보정이 없으면 밴드 값은 dBFS 그대로다.
    val calibrated = !capture.calibration.isReferenceOnly
    val unresolved = rta?.resolved?.indexOfFirst { it }?.takeIf { it > 0 }

    val running = capture.measure is MeasureState.Running
    // 눕히는 일은 분석 **구역**이 한다(`SelahApp` 의 `LockLandscape`), 이 화면이
    // 아니다. 그래도 세로 배치를 남겨 두는 까닭은 **잠금이 듣지 않는 자리가
    // 있어서**다 — 화면 분할·접는 폰에서는 방향 요청이 무시돼 세로로 뜬다.
    // 그때 빈 화면을 보이는 것보다는 좁게라도 그리는 편이 낫다.
    val cfg = LocalConfiguration.current
    val landscape = cfg.screenWidthDp > cfg.screenHeightDp

    // **눕히면 차트만 남는다**(2026-09-24 담당자 지시: 「가로화면에서는 RTA
    // 그래프로 꽉 채우는 게 좋겠다」, 「피드백이 발생하는 부분은 RTA 그래프를
    // 보고 판단하면 될 것 같다」).
    //
    // 그래서 **차트가 숫자까지 말하게** 만들었다 — 후보가 앉은 막대는 색이
    // 다르고, 표식 옆에 정확한 주파수가 적힌다. 설명을 읽을 자리는 없어졌지만
    // 그림 하나로 판단할 수 있다.
    //
    // 높이를 숫자로 못박지 않고 모디파이어가 주는 만큼을 쓴다. 남는 높이는
    // 기기마다·표시줄 크기마다 달라 미리 셈할 수 없고, 못박아 두었더니 폰을
    // 눕혔을 때 가로축 주파수 눈금이 탭 바에 잘렸다(기기에서 확인).
    if (landscape) {
        BandMeter(
            rta,
            floor,
            ceil,
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
            chartHeight = null,
            modes = { AnalyzeModes(ViewMode.Rta, onMode) },
            // **눕히면 31칸이 다 들어온다 — 늘이지 않는다.**
            //
            // 늘여 두었더니(칸당 30dp = 930dp) 화면보다 넓어져 6.3k 위가
            // 오른쪽 밖으로 밀렸고, 담당자가 「6.3k·8k·10k·12.5k·16k·20k 가
            // 없다」고 했다(2026-09-24). 밀면 나오기는 하지만, **보이지 않는
            // 것은 없는 것이다.**
            //
            // 눕힌 화면의 폭이면 칸당 25dp 안팎이라 31칸에 글자를 다 넣고도
            // 남는다. 늘일 까닭이 없다.
            minSlotWidth = 0.dp,
            feedback = capture.feedback,
            calibrated = calibrated,
        )
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        InfoBar(
            "1/3 옥타브 31밴드 · 20Hz ~ 20kHz · 가중 없음(원음 그대로)",
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        // **차트만 길게 한다**(2026-09-24 담당자 지시). 세로 화면에서도 31칸이
        // 각자 자리를 갖도록 늘이고 옆으로 밀어 본다. 눕히면 위의 가로 전용
        // 배치로 간다.
        BandMeter(
            rta,
            floor,
            ceil,
            Modifier.fillMaxWidth(),
            chartHeight = 260.dp,
            modes = { AnalyzeModes(ViewMode.Rta, onMode) },
            minSlotWidth = BAND_SLOT_WIDE,
            feedback = capture.feedback,
            calibrated = calibrated,
        )

        FeedbackStrip(
            capture.feedback.firstOrNull(),
            running = running,
            compact = false,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )

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

        // **피드백 화면이 여기로 들어왔다**(2026-09-24 담당자 지시).
        // 「어느 대역이 솟았나」와 「그게 하울링인가」는 같은 그림에서 읽어야
        // 하는 한 가지 질문인데, 두 화면으로 나뉘어 있어 주파수를 머리로
        // 맞춰 봐야 했다.
        Text(
            "지금 잡고 있는 후보",
            color = SelahColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
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
            "솟음·좁이·레벨·지속·주파수 안정을 모두 만족할 때만 후보로 올립니다. " +
                "그래도 「후보」입니다 — 오래 끄는 오르간 저음도 여기까지 올 수 " +
                "있습니다. 소리를 듣고 판단하십시오.",
            color = SelahColors.TextMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
        )
    }
}

/**
 * 차트 바로 아래 한 줄 — **지금 가장 유력한 후보**.
 *
 * ## 왜 차트에 붙였나
 *
 * 피드백은 원래 따로 한 화면이었다. 담당자가 「RTA 에 피드백이 표기되면
 * 피드백 항목은 별도로 없어도 된다」고 정리했고(2026-09-24), 맞는 말이다 —
 * 「어느 대역이 솟았나」와 「그게 하울링인가」는 한 가지 질문이다.
 *
 * ## 그래도 숫자는 글자로 적는다
 *
 * 차트의 표식은 **어디**를 가리킬 뿐이다. 깎을 때 필요한 것은 정확한
 * 주파수인데, 1/3옥타브 밴드 하나는 23% 나 넓어서 「2.5k 칸」과 「2489Hz」는
 * 다른 말이다. 그래서 표식 옆이 아니라 여기에 숫자를 적는다.
 *
 * ## 말투를 지킨다(명세 9장)
 *
 * 「후보」라고 적고, **의심 단계에서는 EQ 를 만지라고 하지 않는다.** 아직
 * 짧게 스친 소리일 수 있는데 먼저 깎으라고 하면 예배 중에 멀쩡한 악기
 * 소리를 깎게 된다(독립 검증 P9 판단 2번).
 */
/**
 * Spectrum — FFT 한 장을 **칸 그대로** 본다(2026-09-25 검토안 3장).
 *
 * RTA 가 「어느 대역이 큰가」를 말하고 여기서 「그 안에서 정확히 몇 Hz 인가」
 * 가 나온다. 그래서 이 화면의 값어치는 그림보다 **봉우리 옆에 적히는
 * 숫자**에 있다.
 *
 * **떠날 때 끈다.** 켜져 있는 동안만 엔진이 칸 2049개를 곱하고 줄인다 —
 * RTA 만 보는 동안 그 일을 할 까닭이 없고, 예배 내내 켜 두면 배터리로
 * 돌아온다.
 */
@Composable
fun SpectrumScreen(
    capture: CaptureUiState,
    onSpectrumEnabled: (Boolean) -> Unit,
    onMode: (ViewMode) -> Unit = {},
) {
    DisposableEffect(Unit) {
        onSpectrumEnabled(true)
        onDispose { onSpectrumEnabled(false) }
    }

    val spectrum = capture.spectrum
    val (floor, ceil) = spectrumRange(spectrum)
    val calibrated = !capture.calibration.isReferenceOnly

    val cfg = LocalConfiguration.current
    val landscape = cfg.screenWidthDp > cfg.screenHeightDp

    // 눕히면 차트만 남긴다 — RTA 와 같은 규칙이다. 세로 배치는 화면 분할처럼
    // 방향 요청이 듣지 않는 자리를 위해 남겨 둔다.
    if (landscape) {
        SpectrumChart(
            spectrum,
            floor,
            ceil,
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
            chartHeight = null,
            feedback = capture.feedback,
            calibrated = calibrated,
            modes = { AnalyzeModes(ViewMode.Spectrum, onMode) },
        )
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        InfoBar(
            "FFT 한 장을 밴드로 묶지 않고 그대로 봅니다. " +
                "RTA 가 가리킨 대역 안에서 실제 봉우리가 몇 Hz 인지 찾는 화면입니다.",
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        SpectrumChart(
            spectrum,
            floor,
            ceil,
            Modifier.fillMaxWidth(),
            chartHeight = 260.dp,
            feedback = capture.feedback,
            calibrated = calibrated,
            modes = { AnalyzeModes(ViewMode.Spectrum, onMode) },
        )

        FeedbackStrip(
            capture.feedback.firstOrNull(),
            running = capture.measure is MeasureState.Running,
            compact = false,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
    }
}

@Composable
private fun FeedbackStrip(
    top: FeedbackCandidate?,
    running: Boolean,
    /** 눕힌 화면용. 한 줄로 줄이고 설명을 뺀다 — 차트에 높이를 내준다. */
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val tone = when {
        top == null -> SelahColors.Outline
        top.state == FeedbackState.Persistent -> SelahColors.High
        else -> SelahColors.Warn
    }
    Column(
        modifier
            .background(SelahColors.Surface, RoundedCornerShape(10.dp))
            .border(1.dp, tone, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = if (compact) 7.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (top == null) {
            Text(
                if (running) "피드백 후보 없음" else "측정을 시작하면 후보를 찾습니다",
                color = SelahColors.TextMuted,
                fontSize = 12.sp,
            )
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "피드백 후보 — ${top.state.labelKo}",
                color = tone,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "  ${formatHz(top.hz)}${hzUnit(top.hz)}",
                color = SelahColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "  ${(top.durationMs / 100) / 10.0}초 · 둘레보다 " +
                    "${"%.0f".format(top.prominenceDb)}dB 솟음",
                color = SelahColors.TextMuted,
                fontSize = 11.sp,
            )
        }
        if (!compact) {
            Text(
                buildString {
                    // **「의심」에는 EQ 를 만지라고 하지 않는다.** 먼저 귀로
                    // 확인하도록 안내한다 — 아직 짧게 스친 소리일 수 있다.
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
                color = SelahColors.TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
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



/**
 * 차트 상자 안에 얹는 작은 모드 고르개(2026-09-25 담당자 지시).
 *
 * ## 이름을 FFT 로 짓지 않는다
 *
 * 담당자는 「FFT 버튼」이라 했는데, 검토안
 * (`inbox/SELAH_RTA_RTA_Spectrum_Spectrogram_개발반영안-1.pdf`)이 그 자리를
 * 정확히 짚었다:
 *
 * > FFT 는 주파수 성분을 계산하는 분석 방법(알고리즘)이고, Spectrum 은 그
 * > 계산 결과를 주파수별로 보여주는 표시 방식이다.
 *
 * 맞는 지적이다. 화면 이름은 **보는 것**이어야 하고, FFT 는 그 화면이 쓰는
 * 셈이다 — FFT 크기·창 함수는 Spectrum 의 **설정**으로 들어갈 것이다.
 * 「RTA | FFT」로 나란히 두면 밴드 묶음과 계산법을 같은 층으로 놓는 셈이라,
 * 나중에 Spectrogram 이 붙을 때 어디에 둘지가 없어진다.
 */
@Composable
internal fun AnalyzeModes(
    current: ViewMode,
    onPick: (ViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ViewMode.entries.filter { it.section == NavSection.Analyze }.forEach { m ->
            val on = m == current
            Text(
                m.labelKo,
                color = if (on) Color(0xFF00201C) else SelahColors.TextSecondary,
                fontSize = 10.sp,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                softWrap = false,
                modifier = Modifier
                    .background(
                        if (on) SelahColors.Accent else SelahColors.SurfaceVariant,
                        RoundedCornerShape(999.dp),
                    )
                    .clickable { onPick(m) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .semantics { stateDescription = if (on) "선택됨" else "선택 안 됨" },
            )
        }
    }
}
