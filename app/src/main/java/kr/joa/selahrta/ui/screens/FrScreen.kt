package kr.joa.selahrta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.domain.MeasureState
import kr.joa.selahrta.dsp.ROOM_MIN_SNR_DB
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.ui.CaptureUiState
import kr.joa.selahrta.ui.ResponsePhase
import kr.joa.selahrta.ui.components.InfoBar
import kr.joa.selahrta.ui.components.ResponseChart
import kr.joa.selahrta.ui.components.ValueTile
import kr.joa.selahrta.ui.nav.ViewMode
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * FR — 방·PA 의 **크기 응답**을 핑크 잡음으로 본다.
 *
 * ## 이 화면이 맨 먼저 하는 말
 *
 * **전달함수가 아니다.** 그 말을 화면에 적는 것이 이 기능의 절반이다.
 * 전달함수는 내보낸 신호와 들어온 신호를 **함께** 알아야 구할 수 있고
 * 위상까지 준다. 여기서는 들어온 쪽만 안다.
 *
 * 그래서 이 곡선이 말하지 **않는** 것을 화면이 적어 둔다 — 위상·시간,
 * 무엇의 응답인지(소리원 × 방 × 마이크가 한 덩어리다), 다른 자리에서도
 * 같은지. 적지 않으면 담당자는 이것을 「우리 방의 응답」으로 읽고, 그
 * 곡선을 뒤집어 EQ 에 넣는다. 그렇게 하면 안 된다.
 *
 * ## 순서가 배경 → 소리인 까닭
 *
 * 어느 밴드를 믿어도 되는지는 **배경보다 얼마나 큰가**로 가른다. 배경
 * 없이 곡선을 내놓으면 공조기 소리를 방의 저역 부스트로 읽게 된다.
 */
@Composable
fun FrScreen(
    capture: CaptureUiState,
    onMeasure: () -> Unit,
    onMeasureQuiet: () -> Unit,
    onMeasureSignal: () -> Unit,
    onCancel: () -> Unit,
    onPlayHere: (Boolean) -> Unit,
    onDismissNotice: () -> Unit,
    /**
     * 분석 모드를 바꾼다. 분석 구역은 위 칩을 쓰지 않는다.
     *
     * **기본값을 두지 않는다**(2026-09-26). `= {}` 이 있었더니 부르는
     * 쪽에서 이 배선을 빠뜨린 것이 조용히 컴파일됐고, 그래서 이 화면의
     * 고르개가 **아무 일도 하지 않았다** — FR 에 한 번 들어오면
     * RTA·Spectrum·Spectrogram 으로 돌아갈 길이 없었다(기기에서 재현).
     *
     * 아래 주석이 「이것이 RTA 로 돌아가는 유일한 길」이라고 적어 두었는데,
     * 그 유일한 길이 끊겨 있었다. **주석은 배선을 지켜 주지 못한다.**
     */
    onMode: (ViewMode) -> Unit,
) {
    // **화면을 떠나면 그만둔다.** 재는 도중에 다른 탭으로 가 버리면
    // 핑크 잡음이 계속 나고, 예배당이면 회중이 듣는다.
    DisposableEffect(Unit) { onDispose { onCancel() } }

    val running = capture.measure is MeasureState.Running
    val busy = capture.responsePhase == ResponsePhase.Quiet ||
        capture.responsePhase == ResponsePhase.Signal

    Column(Modifier.fillMaxSize()) {
        // **고르개를 맨 위에 붙박이로 둔다.** 분석 구역은 위 칩을 없앴으므로
        // (2026-09-25) 이것이 RTA 로 돌아가는 **유일한 길**이다. 스크롤 안에
        // 넣었다가 아래로 내려가면 길이 사라진다 — 한 번 들어오면 못 나온다.
        AnalyzeModes(
            ViewMode.Fr,
            onMode,
            Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp),
        )

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            InfoBar(
                "핑크 잡음으로 이 자리에서 들리는 대역 균형을 봅니다. " +
                    "전달함수가 아닙니다 — 위상·시간은 알 수 없고, 잰 것은 " +
                    "소리원·방·마이크를 합친 결과입니다.",
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            if (!running) {
                InfoBar(
                    "먼저 「측정」 탭에서 마이크를 여십시오. 마이크가 열려 있어야 잽니다.",
                    Modifier.padding(bottom = 12.dp),
                    tone = SelahColors.Warn,
                )
            }

            capture.responseNoticeKo?.let {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InfoBar(it, Modifier.weight(1f), tone = SelahColors.Warn)
                    TextButton(onClick = onDismissNotice) {
                        Text("확인", color = SelahColors.Accent, fontSize = 12.sp)
                    }
                }
            }

            capture.responseResult?.let { r ->
                ResponseChart(r, Modifier.fillMaxWidth())

                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ValueTile("고른 정도", "%.0f".format(r.spanDb), "dB 폭", Modifier.weight(1f))
                    ValueTile("쓴 장", "${r.framesUsed}", "장", Modifier.weight(1f))
                    ValueTile(
                        "못 쓴 밴드",
                        "${r.usable.count { !it }}",
                        "개 · 배경에 묻힘",
                        Modifier.weight(1f),
                    )
                }

                // 어디가 솟고 어디가 묻혔는지는 **숫자로도** 적는다. 곡선을
                // 눈으로 읽는 것과, 그 값을 EQ 에 옮겨 적는 것은 다른 일이다.
                ExtremeRow(r.relativeDb, r.usable, high = true)
                ExtremeRow(r.relativeDb, r.usable, high = false)

                Text(
                    "이 곡선을 뒤집어 EQ 에 그대로 넣지 마십시오. 방의 저역은 " +
                        "자리마다 10dB 씩 다르고, 마이크를 한 걸음 옮기면 다른 " +
                        "곡선이 나옵니다. 여러 자리에서 재 보고 여러 자리에서 " +
                        "같이 나타나는 것만 손대십시오.",
                    color = SelahColors.Warn,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            if (busy) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .background(SelahColors.Surface, RoundedCornerShape(10.dp))
                        .border(1.dp, SelahColors.Accent, RoundedCornerShape(10.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        capture.responsePhase.labelKo,
                        color = SelahColors.Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    // 막대 하나로 진행을 그린다. 10초를 아무 표시 없이
                    // 기다리게 하면 멈춘 줄 알고 화면을 떠난다.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .background(SelahColors.SurfaceVariant, RoundedCornerShape(3.dp)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(capture.responseProgress.coerceIn(0f, 1f))
                                .height(6.dp)
                                .background(SelahColors.Accent, RoundedCornerShape(3.dp)),
                        )
                    }
                }
            }

            ToggleRow(
                label = "이 폰으로 핑크 잡음을 함께 냅니다",
                on = capture.responsePlayHere,
                enabled = !busy,
                onToggle = onPlayHere,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                if (capture.responsePlayHere) {
                    "폰 스피커의 기울기가 결과에 섞입니다. 방을 보려면 PA 로 " +
                        "핑크 잡음을 틀고 이 고르개를 끄십시오."
                } else {
                    "PA 나 다른 폰으로 핑크 잡음을 틀어 두십시오. 「재기」를 누르면 " +
                        "배경부터 잰 뒤 이어서 잽니다."
                },
                color = SelahColors.TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 6.dp),
            )

            if (busy) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SelahColors.SurfaceVariant,
                        contentColor = SelahColors.TextPrimary,
                    ),
                ) {
                    Text("그만두기", fontWeight = FontWeight.Bold)
                }
            } else if (capture.responsePlayHere) {
                // 이 폰이 소리를 내면 **앱이 순서를 쥔다** — 배경을 잰 뒤
                // 스스로 틀고 이어서 잰다. 사람이 맞출 것이 없다.
                Button(
                    onClick = onMeasure,
                    enabled = running,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SelahColors.Accent,
                        contentColor = Color(0xFF00201C),
                    ),
                ) {
                    Text("재기", fontWeight = FontWeight.Bold)
                }
            } else {
                // **밖에서 소리를 내면 한 번에 못 잰다.**
                //
                // PA 가 핑크 잡음을 계속 내고 있으면 「배경 3초」가 그 소리를
                // 배경으로 재어 버린다 — SNR 이 0 이 되어 모든 밴드가 「못 씀」이
                // 된다. 그래서 사람이 소리를 껐다 켜는 사이에 두 번 나눠 누른다.
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onMeasureQuiet,
                        enabled = running,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (capture.responseQuietReady) {
                                SelahColors.SurfaceVariant
                            } else {
                                SelahColors.Accent
                            },
                            contentColor = if (capture.responseQuietReady) {
                                SelahColors.TextPrimary
                            } else {
                                Color(0xFF00201C)
                            },
                        ),
                    ) {
                        Text(
                            if (capture.responseQuietReady) "배경 다시" else "1. 배경 재기",
                            fontWeight = FontWeight.Bold,
                            softWrap = false,
                        )
                    }
                    Button(
                        onClick = onMeasureSignal,
                        enabled = running && capture.responseQuietReady,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SelahColors.Accent,
                            contentColor = Color(0xFF00201C),
                            disabledContainerColor = SelahColors.SurfaceVariant,
                            disabledContentColor = SelahColors.TextMuted,
                        ),
                    ) {
                        Text("2. 응답 재기", fontWeight = FontWeight.Bold, softWrap = false)
                    }
                }
            }

            Text(
                if (capture.responsePlayHere) {
                    "배경 3초 → 응답 10초로 이어서 잽니다. 배경을 재는 동안에는 " +
                        "조용히 해 주십시오 — 배경보다 ${ROOM_MIN_SNR_DB.toInt()}dB 넘게 " +
                        "큰 밴드만 믿을 수 있는 값으로 셉니다."
                } else {
                    "1. 핑크 잡음을 잠시 꺼 두고 「배경 재기」(3초). " +
                        "2. 다시 틀고 「응답 재기」(10초). " +
                        "배경보다 ${ROOM_MIN_SNR_DB.toInt()}dB 넘게 큰 밴드만 믿을 수 있는 " +
                        "값으로 셉니다 — 소리를 켜 둔 채로 배경을 재면 그 소리가 " +
                        "배경이 되어 아무 밴드도 남지 않습니다."
                },
                color = SelahColors.TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 10.dp, bottom = 24.dp),
            )
        }
    }
}

/** 가장 솟은 / 가장 묻힌 세 밴드를 숫자로 적는다. */
@Composable
private fun ExtremeRow(relativeDb: DoubleArray, usable: BooleanArray, high: Boolean) {
    val picked = relativeDb.indices
        .filter { usable[it] }
        .sortedByDescending { if (high) relativeDb[it] else -relativeDb[it] }
        .take(3)
    if (picked.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            if (high) "가장 솟은 곳" else "가장 묻힌 곳",
            color = SelahColors.TextMuted,
            fontSize = 11.sp,
        )
        Text(
            picked.joinToString("  ") {
                "${ThirdOctave.label(it)}Hz %+.1f".format(relativeDb[it])
            },
            color = if (high) SelahColors.Warn else SelahColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    on: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(SelahColors.SurfaceVariant, RoundedCornerShape(10.dp))
            .border(
                1.dp,
                if (on) SelahColors.Accent else SelahColors.Outline,
                RoundedCornerShape(10.dp),
            )
            .clickable(enabled = enabled) { onToggle(!on) }
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .semantics { stateDescription = if (on) "켬" else "끔" },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (enabled) SelahColors.TextPrimary else SelahColors.TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(end = 10.dp),
        )
        // 색만으로 알리지 않는다(명세 11장).
        Text(
            if (on) "켬" else "끔",
            color = if (on) SelahColors.Accent else SelahColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            softWrap = false,
        )
    }
}
