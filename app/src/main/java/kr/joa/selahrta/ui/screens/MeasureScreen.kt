package kr.joa.selahrta.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import kr.joa.selahrta.domain.focusKo
import kr.joa.selahrta.dsp.Weighting
import kr.joa.selahrta.ui.CaptureUiState
import kr.joa.selahrta.audio.InputSignalState
import kr.joa.selahrta.audio.NO_SIGNAL_HOLD_MS
import kr.joa.selahrta.audio.inputSignalNoticeKo
import kr.joa.selahrta.audio.inputSignalState
import kr.joa.selahrta.dsp.CLIP_THRESHOLD
import kr.joa.selahrta.audio.externalReadiness
import kr.joa.selahrta.ui.components.ReadinessCard
import kr.joa.selahrta.ui.components.DiagnosticsPanel
import kr.joa.selahrta.ui.components.InfoBar
import kr.joa.selahrta.ui.components.InputLevelBar
import kr.joa.selahrta.ui.components.NO_VALUE
import kr.joa.selahrta.ui.components.ValueTile
import kr.joa.selahrta.ui.components.formatDb
import kr.joa.selahrta.ui.components.levelColor
import kr.joa.selahrta.ui.components.levelSpeechKo
import kr.joa.selahrta.ui.components.levelStateKo
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

    // **판정은 계기 바의 색 하나로 말한다.** 예전에는 「낮음/적정/높음」
    // 배지를 함께 띄웠는데, 바가 이미 같은 말을 하고 있어 지웠다.
    //
    // **참고 범위는 dBA 기준이다.** A 가중일 때만 견준다 — C 나 Z 값을
    // dBA 범위와 견주면 저음이 큰 찬양에서 늘 빨강이 된다. 그때는 색을
    // 칠하지 않고 **그 까닭을 글자로 적는다**(명세 11장: 색만으로 알리지
    // 않는다).
    val canJudge = weighting == Weighting.A && range != null
    val liveColor = levelColor(
        if (canJudge) m.currentSpl else null,
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

        // **색이 말하는 것을 읽어 주는 쪽에도 남긴다.** 화면의
        // 「낮음/적정/높음」 배지는 지웠지만, 배지를 지우는 것과 뜻을 지우는
        // 것은 다르다 — 색을 못 보는 사람에게는 바가 아무 말도 하지 않게 된다
        // (독립 검증 지적).
        val speech = levelSpeechKo(
            m.currentSpl,
            if (canJudge) range?.avgLowDb else null,
            if (canJudge) range?.avgHighDb else null,
            weighting.unitSuffix,
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = speech
                levelStateKo(
                    m.currentSpl,
                    if (canJudge) range?.avgLowDb else null,
                    if (canJudge) range?.avgHighDb else null,
                )?.let { stateDescription = it }
            },
        ) {
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
                // **「참고용」 글자는 뺐다.** 미보정 상태는 화면 오른쪽 위의
                // 「미보정」 배지가 말하고 있어 같은 말이 두 번 나왔다.
                // 다만 **색은 남긴다** — 단위가 주황이면 그 숫자가 아직
                // 짐작임을 배지와 같은 색으로 잇는다(명세 1장).
                Text(
                    weighting.unitSuffix,
                    fontSize = 14.sp,
                    color = if (uncalibrated) SelahColors.Warn else SelahColors.TextSecondary,
                )
            }
        }

        if (range != null) {
            // **무엇을 재서 견주는 범위인지 적는다.**
            //
            // 예전에는 「설교 권장 범위 68 ~ 75 dBA」까지만 적었다. 그런데
            // 이 범위는 **시간평균(LAeq) 기준**이고
            // ([kr.joa.selahrta.domain.ReferenceRange]), 계기가 그리는 큰
            // 숫자는 **순간값**이다. 어느 것과 견주라는 말이 없으니 말
            // 한마디에 계기가 빨개지는 것을 「너무 크다」로 읽게 된다.
            //
            // 가격·구독 전략 3장이 「권장범위의 근거·측정 조건·가중치·
            // **평균시간** 표시」를 요구한 자리가 바로 여기다.
            Text(
                "${segment.shortKo} 권장 범위 " +
                    "${range.avgLowDb.toInt()} ~ ${range.avgHighDb.toInt()} dBA" +
                    " · Leq(${capture.meterSettings.leqWindow.labelKo}) 기준" +
                    if (capture.meterSettings.isCustom(segment)) " (고친 값)" else "",
                color = SelahColors.TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (running && canJudge) {
                Text(
                    "계기의 큰 숫자는 지금 값이라 더 크게 출렁입니다. " +
                        "범위에 드는지는 아래 Leq 의 색으로 보십시오.",
                    color = SelahColors.TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // **색을 칠하지 않는 까닭은 글자로 적는다.** 배지를 없앤 뒤로
            // 판정은 바의 색 하나로만 말하는데, 색이 안 들어오는 상태를
            // 설명하지 않으면 고장처럼 보인다(명세 11장).
            if (running && !canJudge) {
                Text(
                    "지금은 ${weighting.labelKo} 라 범위와 견주지 않습니다. " +
                        "설정에서 dBA 로 바꾸면 계기에 색이 들어옵니다.",
                    color = SelahColors.Warn,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // 「보편적 기준이 아니라 참고값입니다」는 뺐다. 같은 말이
            // 설정 화면의 「구간별 권장 범위」 아래에 그대로 있다.
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
                "Leq ()",
                formatDb(m.leqLong),
                // 창이 아직 안 찼으면 그 사실을 적는다 — 「1분 평균」이라고
                // 적어 놓고 실제로는 10초치인 값을 보여 주면 안 된다.
                if (m.leqLong != null && !m.leqLongFull) "모으는 중" else weighting.unitSuffix,
                Modifier.weight(1f),
                // **권장 범위와 견줄 수 있는 것은 이 값이다.**
                //
                // 권장 범위는 시간평균(LAeq) 기준으로 정해져 있다
                // ([kr.joa.selahrta.domain.ReferenceRange]). 그런데 색은
                // 계기에만 있었고 계기는 **순간값**을 그린다 — 말 한마디에
                // 크게 튀는 값이라, 실제 Leq 가 범위 안에 얌전히 있어도
                // 계기는 빨개졌다 나왔다 한다.
                //
                // 창이 안 찼으면 칠하지 않는다. 10초치를 1분 평균인 양
                // 판정하는 것이기 때문이다.
                valueColor = if (canJudge && m.leqLongFull) {
                    levelColor(m.leqLong, range?.avgLowDb, range?.avgHighDb)
                } else {
                    null
                },
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
                capture.diagnostics.lastRmsAbs,
                Modifier.fillMaxWidth().padding(top = 18.dp),
            )

            // **입력 자체가 이상하면 여기서 말한다.** 값이 아니라 입력의
            // 상태다 — 잘리고 있으면 그 구간 값이 실제보다 낮고, 아무것도
            // 안 들어오면 그 뒤 숫자는 전부 뜻이 없다.
            //
            // 할 말은 기기 종류에 따라 다르다. 내장 마이크에는 GAIN 노브도
            // 팬텀전원도 없다(USB 오디오 지시서 6·7·14장).
            val opened = capture.opened
            if (opened != null) {
                val state = inputSignalState(
                    clipping = capture.diagnostics.lastPeakAbs >= CLIP_THRESHOLD,
                    noSignal = capture.diagnostics.quietMs >= NO_SIGNAL_HOLD_MS,
                )
                inputSignalNoticeKo(state, opened.micKind, opened.deviceLabel)?.let {
                    InfoBar(
                        it,
                        Modifier.padding(top = 10.dp),
                        tone = if (state == InputSignalState.Clipping) {
                            SelahColors.High
                        } else {
                            SelahColors.Warn
                        },
                    )
                }
            }
        }

        // **외부 마이크일 때만 나온다.** 내장 마이크에는 팬텀전원도
        // GAIN 도 없어 빈 목록이 오고, 카드는 아무것도 그리지 않는다.
        ReadinessCard(
            externalReadiness(
                opened = capture.opened,
                signalSeen = capture.diagnostics.quietMs < NO_SIGNAL_HOLD_MS &&
                    capture.diagnostics.blocks > 0,
                clipping = capture.diagnostics.lastPeakAbs >= CLIP_THRESHOLD,
                // **파일이 있는 것과 걸려 있는 것은 다르다.** 꺼 두었으면
                // 「보정 적용됨」이 아니다.
                curveApplied = capture.curve?.enabled == true,
            ),
            Modifier.padding(top = 16.dp),
        )

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
    // **미끄러지게 한다.** 화면은 66ms 마다 한 번 새 값을 받는데, 그때마다
    // 바가 툭툭 건너뛰면 눈이 따라가기 어렵다. 그 사이를 이어 그린다.
    //
    // **이것은 보기용이지 측정이 아니다.** 실제로 소리를 느리게 보고 싶다면
    // 설정의 「응답 속도」를 Slow 로 두어야 한다 — 그쪽은 시간가중 자체를
    // 바꾸고, 이쪽은 이미 정해진 값 사이를 이을 뿐이다. 그래서 이 시간을
    // 길게 잡지 않는다. 길면 화면이 실제보다 뒤처진다.
    val target = fraction?.coerceIn(0f, 1f) ?: 0f
    val shown by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = GAUGE_GLIDE_MS, easing = LinearEasing),
        label = "gauge",
    )
    val shownColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(durationMillis = GAUGE_GLIDE_MS, easing = LinearEasing),
        label = "gaugeColor",
    )
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
                color = shownColor,
                startAngle = 180f,
                sweepAngle = 180f * shown,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * 계기 바가 다음 값으로 넘어가는 데 걸리는 시간(ms).
 *
 * 화면이 값을 받는 간격(66ms)보다 길면 바가 실제보다 뒤처진다.
 * 그보다 짧게 잡아 **끊김만 이어 준다.** 소리를 정말 느리게
 * 보고 싶으면 설정의 「응답 속도」를 Slow 로 두어야 한다.
 */
private const val GAUGE_GLIDE_MS = 90
