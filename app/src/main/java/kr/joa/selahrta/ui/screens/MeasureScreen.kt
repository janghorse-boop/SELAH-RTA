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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import kr.joa.selahrta.domain.ChurchSegment
import kr.joa.selahrta.domain.MeasureState
import kr.joa.selahrta.domain.SegmentRange
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
    capture: CaptureUiState,
    /**
     * 재는 구간을 바꾼다.
     *
     * 예전에는 위 칩(설교·찬양)이 하던 일이다. 둘은 **같은 화면의 다른
     * 권장 범위**일 뿐이라 칩 두 자리를 쓸 까닭이 없었다(2026-09-24).
     */
    onSegment: (ChurchSegment) -> Unit,
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
                // **소리를 실제로 건드리고 있을 때만 띄운다**(2026-09-25
                // 담당자 지시: 「가공 없는 입력이 아닙니다… 박스 삭제해
                // 주세요. 미보정 상태는 우측 상단에 표기가 되어 있어서」).
                //
                // 맞는 정리다. 그 문구가 하던 말 — 「절대 음압은 기준
                // 소음계와 맞춰 봐야 합니다」 — 는 **미보정 배지가 이미
                // 하고 있다.** 같은 말을 두 곳에서 하면서 예배 내내 두
                // 줄을 차지했다. 어느 입력 경로로 열렸는지는 아래 진단
                // 패널의 「입력 경로」에 그대로 남는다.
                //
                // **AGC·잡음억제·반향제거가 켜진 채인 것은 다른 이야기라
                // 남긴다.** 그건 「아직 안 맞췄다」가 아니라 **지금 소리를
                // 바꾸고 있다**는 뜻이라, 보정을 마친 경로에서도 숫자를
                // 틀리게 만든다. 미보정 배지가 대신 말해 주지 못한다.
                val effectsOn = f?.effects?.stillOn.orEmpty()
                val tone = when {
                    warming != null -> SelahColors.Warn
                    effectsOn.isNotEmpty() -> SelahColors.Warn
                    else -> SelahColors.InRange
                }
                when {
                    warming != null || f == null -> InfoBar(
                        warming ?: "재고 있습니다.",
                        Modifier.padding(top = 4.dp, bottom = 12.dp),
                        tone = tone,
                    )

                    effectsOn.isNotEmpty() -> {
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
                            trailingKo = if (!f.trustHasDetail) {
                                null
                            } else if (expanded) {
                                "접기"
                            } else {
                                "자세히"
                            },
                        )
                    }

                    // 그 밖에는 띄우지 않는다. 재고 있다는 것은 계기가
                    // 움직이는 것으로 이미 보인다.
                    else -> Unit
                }
            }

            // 「아래 버튼을 눌러 마이크를 엽니다」는 **뺐다**(2026-09-25
            // 담당자 지시). 버튼에 「측정 시작」이라 적혀 있고 그것 말고는
            // 누를 것도 없다 — 시킬 것이 없는 안내였다.
            else -> Unit
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

        // **범위 상자가 구간 고르개를 품는다**(2026-09-24 담당자 지시).
        //
        // 고르는 것은 **권장 범위 하나뿐**이다 — 측정 방식이 바뀌는 게
        // 아니라 무엇과 견줄지가 바뀐다. 그래서 고르개가 범위 상자 밖에서
        // 한 줄을 통째로 쓸 까닭이 없었다. 상자 안에 넣으니 「무엇을
        // 고르면 이 숫자가 바뀐다」가 붙어 읽히고, 그만큼 아래가 올라와
        // **세로 화면에서 측정 버튼이 보인다.**
        RangeCard(
            segment = segment,
            onSegment = onSegment,
            range = range,
            isCustom = capture.meterSettings.isCustom(segment),
            leqLabelKo = capture.meterSettings.leqWindow.labelKo,
            nameOf = { capture.meterSettings.nameFor(it) },
            // **아래를 넉넉히 띄운다**(2026-09-25 담당자 지시: 「간격이 너무
            // 좁아서 답답해 보입니다」). 상자와 계기가 붙어 있으면 둘이 한
            // 덩어리로 보여, 눈이 어디서 끊어 읽어야 할지 모른다.
            modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
        )

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
            GaugeArc(
                fraction = m.currentSpl?.let { gaugeFraction(it) },
                modifier = Modifier.size(248.dp, 132.dp),
                color = liveColor ?: SelahColors.InRange,
                // **견줄 수 없으면 띠도 없다.** C·Z 가중에서 dBA 범위를
                // 눈금에 그려 두면, 색을 안 칠하는 것과 달리 「이 안에
                // 들어오라」는 말로 읽힌다.
                band = if (canJudge && range != null) {
                    gaugeFraction(range.avgLowDb)..gaugeFraction(range.avgHighDb)
                } else {
                    null
                },
                maxMark = m.maxSpl?.let { gaugeFraction(it) },
            )
            // **숫자를 호의 그릇 쪽으로 내린다**(2026-09-25 담당자 지시:
            // 「값이 너무 위쪽에 있습니다」). 반원의 중심은 캔버스 **아래
            // 끝**이라, 상자 한가운데에 두면 호의 빈 위쪽에 떠 보인다.
            // 위쪽에 여백을 주어 가운데정렬의 기준을 아래로 민다.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 40.dp),
            ) {
                Text(
                    formatDb(m.currentSpl),
                    fontSize = 52.sp,
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

        // 「큰 숫자는 순간값입니다…」 안내는 **뺐다**(2026-09-25 담당자 지시).
        //
        // 그 말이 시키던 일 — 「범위 판정은 Leq 색으로 보라」 — 은 **Leq
        // 타일이 스스로 하고 있다.** 창이 차면 그 숫자에 색이 들어오고,
        // 안 찼으면 「모으는 중」이라 적힌다. 시킬 것이 없어졌는데 글만
        // 남아 계기와 타일 사이에서 한 줄을 먹고 있었다.
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

        // **세 지표를 눌러 뜻을 본다**(2026-09-25 담당자 지시).
        //
        // 「Leq」·「MAX」·「MIN」 은 음향 쪽 낱말이라, 적어 두는 것만으로는
        // 무엇을 재는지 알 수 없다. 누르면 그 자리에서 알려 준다 — 설명을
        // 화면에 늘 펼쳐 두면 정작 숫자가 밀린다.
        var shownMetric by rememberSaveable { mutableStateOf<Metric?>(null) }
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ValueTile(
                "MIN",
                formatDb(m.minSpl),
                // 자리를 잡기 전에는 값이 없다. 「모르는 값」을 0 으로 적지
                // 않는다(`SplFrame.minDbfs`).
                if (running && m.minSpl == null) "자리 잡는 중" else weighting.unitSuffix,
                Modifier.weight(1f),
                dim = uncalibrated,
                onClick = { shownMetric = Metric.Min },
            )
            ValueTile(
                "Leq (${capture.meterSettings.leqWindow.labelKo})",
                formatDb(m.leqLong),
                // 창이 아직 안 찼으면 그 사실을 적는다 — 「1분 평균」이라고
                // 적어 놓고 실제로는 10초치인 값을 보여 주면 안 된다.
                if (m.leqLong != null && !m.leqLongFull) "모으는 중" else weighting.unitSuffix,
                Modifier.weight(1f),
                // **권장 범위와 견줄 수 있는 것은 이 값이다.**
                //
                // 권장 범위는 시간평균(LAeq) 기준으로 정해져 있다
                // ([kr.joa.selahrta.domain.SegmentRange]). 창이 안 찼으면
                // 칠하지 않는다 — 10초치를 1분 평균인 양 판정하는 것이다.
                valueColor = if (canJudge && m.leqLongFull) {
                    levelColor(m.leqLong, range?.avgLowDb, range?.avgHighDb)
                } else {
                    null
                },
                dim = uncalibrated,
                onClick = { shownMetric = Metric.Leq },
                // **이 값이 판단의 기준이다.** 권장 범위가 시간평균 기준이라
                // 「지금 잠깐 컸다」가 아니라 「이만큼으로 이어지고 있다」를
                // 봐야 한다(2026-09-25 담당자 지시).
                highlight = true,
            )
            ValueTile(
                "MAX",
                formatDb(m.maxSpl),
                weighting.unitSuffix,
                Modifier.weight(1f),
                dim = uncalibrated,
                onClick = { shownMetric = Metric.Max },
            )
        }

        // **PEAK 는 눌러서 본다**(2026-09-25 담당자 지시).
        //
        // 예배 음량을 판단하는 데 늘 봐야 하는 값이 아니면서 타일 한 자리를
        // 차지했고, MAX 와 20~30dB 벌어져 있어 설명 없이는 오해를 샀다.
        //
        // **숨기는 것이 안전한 까닭**은 잘림을 따로 알리기 때문이다. 파형이
        // 잘리면 바로 아래 경고가 뜨고, 이 줄도 경고색이 된다 — PEAK 가
        // 화면에서 내려가도 「이 측정은 잘렸다」는 소식은 내려가지 않는다.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clickable { shownMetric = Metric.Peak }
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (m.peakClipped) "PEAK 순간최고 — 클리핑 · 눌러서 보기" else "PEAK 순간최고 보기",
                color = if (m.peakClipped) SelahColors.High else SelahColors.TextMuted,
                fontSize = 11.sp,
                fontWeight = if (m.peakClipped) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        shownMetric?.let { metric ->
            MetricDialog(
                metric = metric,
                meter = m,
                weighting = weighting,
                leqLabelKo = capture.meterSettings.leqWindow.labelKo,
                timeWeightKo = capture.meterSettings.timeWeight.labelKo,
                uncalibrated = uncalibrated,
                onClose = { shownMetric = null },
            )
        }

        // **잘림은 버튼보다 위에 둔다.** 그 구간의 숫자가 전부 하한이라는
        // 말이라, 화면에서 내려가면 안 되는 종류의 경고다.
        if (m.anyClipping) {
            InfoBar(
                "클리핑 — 소리가 너무 커서 파형이 잘린 구간이 있습니다. 그 구간의 음압은 " +
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
        //
        // **저역 비중 카드보다 위로 올렸다**(2026-09-24). 그 카드가 한
        // 줄을 차지하면서 재는 동안 「측정 종료」가 화면 밖으로 밀렸다 —
        // 기기에서 확인했다. 카드는 읽는 것이고 버튼은 누르는 것이다.
        // 누를 것이 먼저다.
        Button(
            onClick = {
                when {
                    !hasPermission -> onRequestPermission()
                    running -> onStop()
                    else -> onStart()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
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

        // 저역 비중(C−A) 카드는 **뺐다**(2026-09-25 담당자 지시).
        //
        // 저역이 얼마나 많은지는 RTA 가 대역별로 그대로 보여 준다 — 한
        // 숫자로 뭉쳐 놓은 것보다 그쪽이 자세하고, 측정 화면에서는 두 줄을
        // 늘 차지하고 있었다. 셈 자체는 남아 있어 되살리기 쉽다
        // (`MeterReading.cMinusA`).

        if (running) {
            // 입력 레벨 막대는 **뺐다**(2026-09-25 담당자 지시). 잘림은 위에서
            // 세 번 알리고(경고줄·PEAK 줄·진단의 「잘린 덩어리」), 소리가 안
            // 들어오는 것은 바로 아래 안내가 말한다. 게인을 맞출 때 보는
            // dBFS 숫자만 진단 패널로 옮겼다 — 인터페이스 노브는 그 값을
            // 보고 돌린다.

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
 * 눌러서 보는 지표 넷.
 *
 * **낱말만으로는 뜻이 안 통한다.** Leq·MAX·MIN·PEAK 는 음향 쪽 말이라,
 * 화면에 적어 두는 것만으로는 무엇을 재는지 알 수 없다. 설명을 늘 펼쳐
 * 두면 정작 숫자가 밀리므로, 누를 때만 나오게 한다.
 */
private enum class Metric { Min, Leq, Max, Peak }

/**
 * 지표 하나의 뜻을 값과 함께 적는 창.
 *
 * ## 가중 이름을 글에 박지 않는다
 *
 * 설명에 「A 가중」이라고 못박으면 설정에서 C·Z 로 바꾼 순간 **글이
 * 거짓말이 된다.** 지금 걸린 가중의 이름을 받아서 쓴다.
 *
 * ## PEAK 만 말이 긴 까닭
 *
 * PEAK 와 MAX 는 예배당에서 20~30dB 벌어진다(`PeakVersusMaxTest` 에서 잰
 * 값: 흉내 신호 25.5dB). 설명 없이 두 숫자만 나란히 두면 **둘 중 하나가
 * 고장 난 것으로 읽힌다** — 실제로 그런 검토 의견이 올라왔다.
 */
@Composable
private fun MetricDialog(
    metric: Metric,
    meter: kr.joa.selahrta.ui.MeterReading,
    weighting: Weighting,
    leqLabelKo: String,
    timeWeightKo: String,
    uncalibrated: Boolean,
    onClose: () -> Unit,
) {
    val clipped = metric == Metric.Peak && meter.peakClipped
    val value = when (metric) {
        Metric.Min -> formatDb(meter.minSpl)
        Metric.Leq -> formatDb(meter.leqLong)
        Metric.Max -> formatDb(meter.maxSpl)
        Metric.Peak ->
            if (clipped && meter.peakSpl != null) "≥${formatDb(meter.peakSpl)}" else formatDb(meter.peakSpl)
    }
    val unit = if (metric == Metric.Peak) "dB · 가중없음" else weighting.unitSuffix
    val title = when (metric) {
        Metric.Min -> "MIN — 가장 조용했던 값"
        Metric.Leq -> "Leq ($leqLabelKo) — 등가소음도"
        Metric.Max -> "MAX — 가장 컸던 값"
        Metric.Peak -> "PEAK — 순간 최고"
    }
    val body = when (metric) {
        Metric.Min ->
            "측정 중 ${weighting.labelKo} SPL 의 최소값입니다. " +
                "시간가중($timeWeightKo)을 거친 레벨이 가장 낮았던 순간입니다."
        Metric.Leq ->
            "$leqLabelKo 동안의 에너지를 평균한 등가소음도입니다. " +
                "큰 소리와 작은 소리를 에너지로 더해 평균하므로, 잠깐 튄 소리 " +
                "하나에 크게 흔들리지 않습니다."
        Metric.Max ->
            "측정 중 ${weighting.labelKo} SPL 의 최대값입니다. " +
                "시간가중($timeWeightKo)을 거친 레벨이 가장 높았던 순간입니다."
        Metric.Peak ->
            "측정을 시작한 뒤 파형이 닿은 가장 높은 순간입니다. " +
                "표본 하나만 커도 그 값이 그대로 남습니다 — 박수 한 번, " +
                "마이크를 스치는 소리, 드럼 타격이 여기 걸립니다."
    }

    AlertDialog(
        onDismissRequest = onClose,
        // 배경과 뚜렷이 갈라 놓는다 — 앱 배경과 밝기가 비슷하면 창이 떠
        // 있는지 구별되지 않는다(담당자 지적으로 정한 규칙).
        containerColor = SelahColors.DialogSurface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.border(1.dp, SelahColors.Outline, RoundedCornerShape(20.dp)),
        title = { Text(title, color = SelahColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        value,
                        color = if (clipped) SelahColors.High else SelahColors.TextPrimary,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "  $unit",
                        color = SelahColors.TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }

                Text(
                    body,
                    color = SelahColors.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )

                when (metric) {
                    Metric.Leq -> Text(
                        "권장 범위와 견주는 값이 이것입니다 — 범위 자체가 " +
                            "시간평균 기준으로 정해져 있습니다. 창이 다 차기 " +
                            "전에는 이름보다 짧은 구간의 평균이라 색을 칠하지 " +
                            "않습니다.",
                        color = SelahColors.TextMuted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )

                    Metric.Min -> Text(
                        "시간가중이 자리를 잡은 뒤부터 셉니다. 시작 직후의 " +
                            "값은 0 에서 올라오는 중이라, 그것까지 세면 MIN 이 " +
                            "늘 시작 구간으로 굳어 버립니다. 예배당에서는 대개 " +
                            "방의 배경 소음 수준으로 내려가 머뭅니다.",
                        color = SelahColors.TextMuted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )

                    Metric.Max -> Text(
                        "시간가중($timeWeightKo)을 거친 값이라 「이만큼이 " +
                            "이어졌다」를 말합니다. 짧은 충격은 평균에 눌려 " +
                            "여기 다 나타나지 않습니다 — 그것은 PEAK 가 봅니다.",
                        color = SelahColors.TextMuted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )

                    Metric.Peak -> {
                        Text(
                            "MAX 와 20~30dB 벌어지는 것이 정상입니다",
                            color = SelahColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "MAX 는 가중을 거치고 시간가중으로 평균한 뒤의 " +
                                "최대라 「이만큼이 이어졌다」를 말합니다. PEAK 는 " +
                                "가중 전 파형의 순간 최대라 「이만큼까지 닿았다」를 " +
                                "말합니다. 재는 것이 달라서 생기는 차이이지 " +
                                "고장이 아닙니다.",
                            color = SelahColors.TextMuted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                        if (clipped) {
                            Text(
                                "파형이 잘렸습니다 — 이 값은 측정값이 아니라 " +
                                    "하한입니다. 실제로는 더 높았고 얼마나 높았는지는 " +
                                    "알 수 없습니다. 그 구간의 Leq·MAX 도 실제보다 " +
                                    "낮습니다. 마이크를 소리원에서 떼어 놓고 다시 " +
                                    "재십시오.",
                                color = SelahColors.High,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            )
                        } else {
                            Text(
                                "이 값이 풀스케일에 닿으면 파형이 잘렸다는 뜻이고, " +
                                    "그때는 다른 숫자들도 모두 실제보다 낮아집니다. " +
                                    "PEAK 를 두는 까닭이 그것입니다.",
                                color = SelahColors.TextMuted,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                }

                if (uncalibrated) {
                    Text(
                        "지금은 미보정이라 이 숫자도 짐작입니다. 절대 음압은 " +
                            "기준 소음계나 1kHz 교정기로 맞춘 뒤에야 뜻이 있습니다.",
                        color = SelahColors.Warn,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text("닫기", color = SelahColors.Accent)
            }
        },
    )
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
    /**
     * 권장 범위를 호 위에 **띠**로 깔아 둔다. 눈금으로 환산한 0~1 이다.
     *
     * 숫자로만 적어 두면 「지금 바늘이 그 안인가」를 사람이 머리로 셈해야
     * 한다. 띠로 그리면 눈으로 바로 읽힌다.
     *
     * **A 가중일 때만 준다** — 범위가 dBA 기준이라 C·Z 에서는 견줄 수
     * 없다(부르는 쪽이 canJudge 로 가린다).
     */
    band: ClosedFloatingPointRange<Float>? = null,
    /** 이번 측정의 최대. 0~1. 지나간 자리를 눈금 위에 남긴다. */
    maxMark: Float? = null,
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
    // 눈금 숫자를 그리는 데 쓴다. Canvas 안에서는 Text 를 쓸 수 없다.
    val measurer = rememberTextMeasurer()
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

        // **눈금을 새긴다**(2026-09-25 담당자 지시: 「기본 가이드라인에
        // 눈금표시가 있는게 좋아보입니다」).
        //
        // 눈금이 없으면 호의 길이가 몇 dB 인지 알 수 없어, 바늘이 어디
        // 있는지는 보여도 **얼마인지는 숫자를 읽어야만** 알 수 있었다.
        //
        // **호 안쪽에 그린다.** 획 위에 겹쳐 그리면 색이 찬 구간에서 눈금이
        // 묻힌다. 안쪽이면 바늘이 지나가도 그대로 보인다.
        val r = d / 2f
        val cx = topLeft.x + r
        val cy = topLeft.y + r
        val tickOuter = r - stroke / 2f - 2.dp.toPx()
        val tickInner = tickOuter - 6.dp.toPx()
        var db = GAUGE_LOW_DB
        while (db <= GAUGE_LOW_DB + GAUGE_SPAN_DB + 1e-9) {
            val rad = Math.toRadians(180.0 + 180.0 * gaugeFraction(db))
            val ca = kotlin.math.cos(rad).toFloat()
            val sa = kotlin.math.sin(rad).toFloat()
            drawLine(
                // **흐리게 두지 않는다**(2026-09-25 담당자 지적: 「눈금표시와
                // 이름 글자색이 너무 어둡네요」). Outline 은 카드 테두리에
                // 쓰는 색이라 검은 배경에서 거의 안 보였다.
                color = SelahColors.TextSecondary,
                start = Offset(cx + tickInner * ca, cy + tickInner * sa),
                end = Offset(cx + tickOuter * ca, cy + tickOuter * sa),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            // **숫자는 20dB 마다만 적는다.** 10dB 마다 적으면 작은 글자가
            // 여덟 개나 붙어 정작 큰 숫자를 읽는 데 방해가 된다.
            if ((db - GAUGE_LOW_DB) % 20.0 < 1e-9) {
                val laid = measurer.measure(
                    AnnotatedString("%.0f".format(db)),
                    style = TextStyle(fontSize = 9.sp, color = SelahColors.TextSecondary),
                )
                // 눈금 바로 안쪽에 붙인다. 더 안으로 넣으면 가운데 큰
                // 숫자와 부딪힌다 — 60 이 「63.1」의 6 에 닿았다.
                val lr = tickInner - 3.dp.toPx()
                drawText(
                    laid,
                    topLeft = Offset(
                        cx + lr * ca - laid.size.width / 2f,
                        cy + lr * sa - laid.size.height / 2f,
                    ),
                )
            }
            db += GAUGE_TICK_DB
        }

        // **권장 범위 띠는 바늘 밑에 깔린다.** 위에 그리면 지금 값을 가린다.
        // 끝을 Butt 로 자르는 것은 일부러다 — Round 로 두면 띠가 양쪽으로
        // 반지름만큼 번져 실제 범위보다 넓어 보인다.
        band?.let {
            val lo = it.start.coerceIn(0f, 1f)
            val hi = it.endInclusive.coerceIn(0f, 1f)
            if (hi > lo) {
                drawArc(
                    color = SelahColors.InRange.copy(alpha = 0.30f),
                    startAngle = 180f + 180f * lo,
                    sweepAngle = 180f * (hi - lo),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
            }
        }

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

        // **지나간 최대를 눈금 위에 남긴다.** 숫자는 아래 타일에도 있지만,
        // 호 위에 있으면 「지금이 그때보다 얼마나 작은가」가 한눈에 들어온다.
        maxMark?.let {
            val rad = Math.toRadians((180.0 + 180.0 * it.coerceIn(0f, 1f)))
            val inner = r - stroke / 2f
            val outer = r + stroke / 2f
            drawLine(
                color = SelahColors.TextPrimary,
                start = Offset(
                    cx + (inner * kotlin.math.cos(rad)).toFloat(),
                    cy + (inner * kotlin.math.sin(rad)).toFloat(),
                ),
                end = Offset(
                    cx + (outer * kotlin.math.cos(rad)).toFloat(),
                    cy + (outer * kotlin.math.sin(rad)).toFloat(),
                ),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * dB 를 계기 눈금(0~1)으로 옮긴다. 눈금은 40~110dB 다 — 예배당에서
 * 실제로 오가는 범위.
 *
 * 한 자리에 모아 둔 까닭은 바늘·범위 띠·MAX 표식이 **같은 눈금**을 써야
 * 하기 때문이다. 각자 셈하면 하나만 고쳐도 서로 어긋난다.
 */
private fun gaugeFraction(db: Double): Float = ((db - GAUGE_LOW_DB) / GAUGE_SPAN_DB).toFloat()

/** 눈금을 몇 dB 마다 새길 것인가. 40~110 이면 여덟 개가 된다. */
private const val GAUGE_TICK_DB = 10.0

private const val GAUGE_LOW_DB = 40.0
private const val GAUGE_SPAN_DB = 70.0

/**
 * 계기 바가 다음 값으로 넘어가는 데 걸리는 시간(ms).
 *
 * 화면이 값을 받는 간격(66ms)보다 길면 바가 실제보다 뒤처진다.
 * 그보다 짧게 잡아 **끊김만 이어 준다.** 소리를 정말 느리게
 * 보고 싶으면 설정의 「응답 속도」를 Slow 로 두어야 한다.
 */
private const val GAUGE_GLIDE_MS = 90

/**
 * 권장 범위 상자 — **구간 고르개를 안에 품는다.**
 *
 * ## 왜 한 상자인가
 *
 * 구간을 고르는 일은 **이 상자의 숫자를 바꾸는 일**이다. 측정 방식이
 * 바뀌지 않는다 — 무엇과 견줄지가 바뀔 뿐이다. 고르개를 밖에 두면 그
 * 관계가 끊겨, 화면에 「고르는 것」이 둘(위 칩·아래 줄)인 것처럼 보인다.
 *
 * ## 무엇을 재서 견주는 범위인지 함께 적는다
 *
 * 범위는 **시간평균(LAeq) 기준**이고([kr.joa.selahrta.domain.SegmentRange])
 * 계기의 큰 숫자는 **순간값**이다. 어느 것과 견주라는 말이 없으면, 말
 * 한마디에 계기가 빨개지는 것을 「너무 크다」로 읽게 된다. 그래서 숫자
 * 옆에 평균시간을 붙여 둔다(가격·구독 전략 3장의 요구이기도 하다).
 */
@Composable
private fun RangeCard(
    segment: ChurchSegment,
    onSegment: (ChurchSegment) -> Unit,
    range: SegmentRange?,
    isCustom: Boolean,
    leqLabelKo: String,
    /** 구간 이름. 사용자가 고칠 수 있다(`MeterSettings.nameFor`). */
    nameOf: (ChurchSegment) -> String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(SelahColors.SurfaceVariant, RoundedCornerShape(12.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp, horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // **글자는 왼쪽에 쌓고 알약은 오른쪽에, 세로 가운데로 맞춘다**
        // (2026-09-25 담당자 지시). 예전에는 라벨과 알약이 한 줄, 숫자가
        // 그 아래 한 줄이라 상자가 두 줄 높이를 썼다. 알약이 숫자 옆으로
        // 오면 **상자가 한 줄만큼 낮아진다** — 그만큼 아래가 올라온다.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f).padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "권장 범위" + if (isCustom) " (고친 값)" else "",
                    color = SelahColors.TextMuted,
                    fontSize = 11.sp,
                )
                if (range == null) {
                    Text(
                        "이 구간에는 권장 범위가 없습니다.",
                        color = SelahColors.TextSecondary,
                        fontSize = 13.sp,
                    )
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "${range.avgLowDb.toInt()} ~ ${range.avgHighDb.toInt()} dBA",
                            color = SelahColors.TextPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            softWrap = false,
                        )
                        Text(
                            "  Leq($leqLabelKo) 기준",
                            color = SelahColors.TextMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                }
            }
            SegmentPills(segment, nameOf, onSegment)
        }
        // 구간 설명(「말이 또렷한지가 먼저입니다…」)은 **뺐다**
        // (2026-09-25 담당자 지시). 고를 것이 설교·찬양 둘뿐이라 알약만
        // 보고도 무엇을 고르는지 알고, 한 번 읽으면 그만인 글이 상자에
        // 늘 붙어 있었다.
    }
}

/**
 * 구간을 고르는 작은 알약 둘 — 설교인가 찬양인가.
 *
 * **작게 둔다**(2026-09-24 담당자 지시). 예전에는 화면 폭을 반씩 나눠 쓰는
 * 큰 버튼 둘이 한 줄을 통째로 차지했다. 고르는 일은 예배 한 번에 한
 * 번뿐인데 늘 그만한 자리를 쓰고 있었고, 그만큼 측정 버튼이 화면 밖으로
 * 밀렸다.
 */
@Composable
private fun SegmentPills(
    selected: ChurchSegment,
    /** 구간 이름. 사용자가 고친 이름이 있으면 그것이 온다. */
    nameOf: (ChurchSegment) -> String,
    onPick: (ChurchSegment) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChurchSegment.entries.forEach { s ->
            val on = s == selected
            Box(
                Modifier
                    .background(
                        if (on) SelahColors.Accent else SelahColors.Surface,
                        RoundedCornerShape(999.dp),
                    )
                    .clickable { onPick(s) }
                    // 눌리는 자리가 글자만 해지면 손가락이 빗나간다.
                    // 알약을 작게 두되 여백으로 누를 자리는 남긴다.
                    .padding(horizontal = 14.dp, vertical = 7.dp)
                    .semantics { stateDescription = if (on) "선택됨" else "선택 안 됨" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    nameOf(s),
                    color = if (on) Color(0xFF00201C) else SelahColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
