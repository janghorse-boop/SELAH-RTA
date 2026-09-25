package kr.joa.selahrta.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.audio.CaptureDiagnostics
import kr.joa.selahrta.audio.OpenedFormat
import kr.joa.selahrta.dsp.CLIP_THRESHOLD
import kr.joa.selahrta.dsp.SILENCE_FLOOR_DBFS
import kr.joa.selahrta.dsp.dbfs
import kr.joa.selahrta.dsp.amplitudeToDbfs
import kr.joa.selahrta.ui.theme.SelahColors
import kotlin.math.log10

/**
 * 입력 레벨(dBFS). **음압이 아니다** — 마이크에 소리가 들어오는지 본다.
 *
 * **Peak 와 RMS 를 함께 적는다**(USB 오디오 지시서 6장). Peak 만 보면
 * 툭 튄 소리 하나로 「크다」고 읽히고, RMS 만 보면 잘리고 있는데도
 * 「여유 있다」고 읽힌다. 둘의 차이가 곧 헤드룸이다.
 */
@Composable
fun InputLevelBar(peakAbs: Double, rmsAbs: Double, modifier: Modifier = Modifier) {
    // 선형 비율을 그대로 그리면 사람 말소리(0.01~0.1)가 거의 안 보인다.
    // 귀가 로그로 듣는 것과 같은 이유로 여기서도 로그 축을 쓴다.
    // -60dBFS 를 바닥으로 잡는다.
    val db = dbfs(peakAbs)
    val rmsDb = dbfs(rmsAbs)
    val fraction = ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
    val rmsFraction = ((rmsDb + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
    val clipping = peakAbs >= CLIP_THRESHOLD

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("입력 레벨", color = SelahColors.TextSecondary, fontSize = 11.sp)
            Text(
                if (clipping) {
                    "잘림!"
                } else {
                    "Peak ${fmtDbfs(db)} · RMS ${fmtDbfs(rmsDb)}"
                },
                color = if (clipping) SelahColors.High else SelahColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = if (clipping) FontWeight.Bold else FontWeight.Normal,
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(SelahColors.SurfaceVariant, RoundedCornerShape(5.dp)),
        ) {
            // **RMS 를 먼저, 그 위에 Peak 를 옅게 얹는다.** 두 값을 막대
            // 하나에 겹쳐 두면 평소 레벨과 순간 최대가 한눈에 갈린다.
            if (fraction > 0f) {
                drawRoundRect(
                    color = (if (clipping) SelahColors.High else SelahColors.InRange)
                        .copy(alpha = 0.35f),
                    topLeft = Offset.Zero,
                    size = Size(size.width * fraction, size.height),
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            }
            if (rmsFraction > 0f) {
                drawRoundRect(
                    color = if (clipping) SelahColors.High else SelahColors.InRange,
                    topLeft = Offset.Zero,
                    size = Size(size.width * rmsFraction, size.height),
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            }
        }
        Text(
            "음압(dB SPL)이 아닙니다. 마이크에 소리가 들어오는지 보는 눈금입니다. " +
                "짙은 쪽이 RMS, 옅은 쪽이 Peak 입니다.",
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
    }
}

/** −120dBFS 는 「사실상 없음」이다. 숫자로 적으면 자리만 먹는다. */
private fun fmtDbfs(db: Double): String =
    if (db <= SILENCE_FLOOR_DBFS) "—" else "%.1f dBFS".format(db)

/**
 * 캡처 진단(명세 16장).
 *
 * 측정값이 아니라 **캡처가 건강한지**를 말한다. 이것이 흔들리면 그 위에
 * 올린 숫자는 전부 못 믿으므로, 숨기지 않고 보여 준다.
 */
@Composable
fun DiagnosticsPanel(
    opened: OpenedFormat,
    diag: CaptureDiagnostics,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(SelahColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            "캡처 진단",
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )

        DiagRow("입력 기기", opened.deviceLabel, helpKo = "지금 소리를 받고 있는 마이크·오디오 인터페이스의 이름입니다. 안드로이드가 알려 준 그대로 적습니다.")
        DiagRow("입력 경로", opened.audioSource.labelKo, warn = !opened.audioSource.trustworthy, helpKo = "안드로이드에 어떤 입력으로 열어 달라고 했는지입니다. 「가공 없는 입력(UNPROCESSED)」이면 자동 게인·잡음 억제 같은 손질이 걸리지 않습니다. 그 입력을 지원하지 않는 기기는 덜 가공된 쪽으로 내려가 열립니다.")
        DiagRow(
            "UNPROCESSED 지원",
            if (opened.unprocessedSupported) "예" else "아니요",
            warn = !opened.unprocessedSupported,
        )
        // 신호 가공이 살아 있으면 절대값을 믿을 수 없다. 셋을 각각 보여준다 —
        // 「대체로 괜찮다」가 아니라 무엇이 남았는지가 중요하다.
        DiagRow(
            "자동 게인(AGC)",
            opened.effects.agc.statusKo,
            warn = !opened.effects.agc.disabled,
        )
        DiagRow(
            "잡음 억제(NS)",
            opened.effects.ns.statusKo,
            warn = !opened.effects.ns.disabled,
        )
        DiagRow(
            "반향 제거(AEC)",
            opened.effects.aec.statusKo,
            warn = !opened.effects.aec.disabled,
        )
        DiagRow(
            "샘플레이트",
            "${opened.sampleRate} Hz",
            warn = opened.sampleRate != 48_000,
            helpKo = "1초에 소리를 몇 번 재는지입니다. 48000Hz 면 24kHz 까지 " +
                "볼 수 있어 사람이 듣는 범위를 다 덮습니다. 이보다 낮으면 " +
                "고역이 잘립니다.",
        )
        // **몇 채널로 열렸고 어느 것을 재는가.** 오디오 인터페이스를
        // 꼽았을 때 이 줄이 없으면 **어느 마이크를 재고 있는지 문서로 남지
        // 않는다**(USB 오디오 지시서 8장 진단 화면).
        DiagRow(
            "채널",
            if (opened.channelCount <= 1) {
                "1 (모노)"
            } else {
                "${opened.channelCount} 중 ${opened.channelIndex + 1}번"
            },
        )
        DiagRow(
            "샘플 형식",
            opened.encoding.bitsLabel,
            helpKo = "한 표본을 어떤 숫자로 담는지입니다. 32bit float 는 " +
                "표현 범위가 넓어 작은 소리에서도 계단이 생기지 않습니다.",
        )
        DiagRow(
            "버퍼",
            "${opened.bufferSizeBytes} 바이트",
            helpKo = "안드로이드가 소리를 모아 두었다가 건네주는 그릇의 " +
                "크기입니다. 작으면 빨리 오지만 놓칠 위험이 커지고, 크면 " +
                "안전하지만 늦게 옵니다.",
        )
        DiagRow(
            "받은 블록 / 프레임",
            "${diag.blocks} / ${diag.frames}",
            helpKo = "블록은 한 번에 건네받은 소리 덩이이고, 프레임은 그 안에 " +
                "든 표본의 수입니다. 측정을 시작한 뒤로 쌓인 값이라 계속 " +
                "늘어납니다 — 멈춰 있으면 소리가 안 들어오고 있는 것입니다.",
        )
        DiagRow(
            "처리 시간 / 블록 길이",
            "%.2f ms / %.1f ms".format(diag.lastProcessMs, diag.blockDurationMs),
            warn = !diag.processingHeadroom,
            helpKo = "블록 하나를 셈하는 데 걸린 시간과, 그 블록이 담고 있는 " +
                "소리의 길이입니다. 앞 숫자가 뒤 숫자보다 커지면 셈이 소리를 " +
                "따라가지 못한다는 뜻이라 값이 밀립니다.",
        )
        DiagRow(
            "오디오 지연",
            "%.0f ms".format(diag.audioLagMs),
            warn = !diag.keepingUp,
            helpKo = "소리가 마이크에 닿은 때와 화면에 나오는 때의 차이입니다. " +
                "커지면 화면의 숫자가 지금이 아니라 조금 전의 소리입니다.",
        )
        // **게인을 맞출 때 보는 값**(2026-09-25 측정 화면의 입력 레벨 막대를
        // 걷어내며 이리로 옮겼다). 인터페이스의 노브는 SPL 이 아니라 이
        // dBFS 를 보고 돌린다 — 피크가 −12dBFS 언저리면 넉넉하다.
        DiagRow(
            "입력 레벨 (피크 / RMS)",
            "%.1f / %.1f dBFS".format(
                amplitudeToDbfs(diag.lastPeakAbs).value,
                amplitudeToDbfs(diag.lastRmsAbs).value,
            ),
            warn = diag.lastPeakAbs >= CLIP_THRESHOLD,
            helpKo = "디지털 입력이 얼마나 차 있는지입니다(0 dBFS 가 가득). " +
                "음압(dB SPL)이 아니라 녹음 레벨이라, 인터페이스의 게인 노브는 " +
                "이 값을 보고 돌립니다 — 피크가 −12 dBFS 언저리면 여유가 " +
                "넉넉합니다. 0 에 닿으면 클리핑입니다.",
        )
        DiagRow(
            "읽기 오류",
            "${diag.readErrors}",
            warn = diag.readErrors > 0,
            helpKo = "소리를 받아 오다가 실패한 횟수입니다. 0 이 아니면 그만큼 " +
                "소리를 놓쳤고, 그 구간의 값은 빠져 있습니다.",
        )
        // **용어를 바로잡았다**(2026-09-25 담당자 지적: 「잘린 덩어리란
        // 용어가 맞는지 체크해주세요. 전문 용어가 있을 것 같네요」).
        //
        // 맞는 지적이다. 이 현상의 이름은 **클리핑(clipping)** 이다 — 신호가
        // 풀스케일을 넘어 파형의 꼭대기가 잘려 나가는 것이고, 믹서·DAW 를
        // 만져 본 사람은 다 이 말로 안다. 「덩어리」는 이 저장소가 buffer 를
        // 부르는 제 나름의 말이라 밖에서는 통하지 않는다.
        //
        // 소음계 규격(IEC 61672)은 같은 표시를 **과부하(overload)** 라
        // 부른다. 둘 다 맞지만 예배당에서 믹서를 만지는 사람에게는 클리핑이
        // 먼저 와 닿아 그쪽을 골랐고, 잘린다는 설명은 괄호로 남겼다.
        DiagRow(
            "클리핑(잘림) 블록",
            "${diag.clippedBlocks}",
            warn = diag.clippedBlocks > 0,
            helpKo = "소리가 너무 커서 파형의 꼭대기가 잘려 나간 블록의 " +
                "수입니다(클리핑). 잘린 구간은 실제 음압이 화면 값보다 높고 " +
                "얼마나 높은지는 알 수 없으며, 없던 고조파가 생겨 RTA 도 " +
                "흐트러집니다. 0 이 아니면 마이크를 소리원에서 떼어 놓거나 " +
                "인터페이스의 게인을 낮추십시오. 소음계 규격은 같은 표시를 " +
                "「과부하(overload)」라고 부릅니다.",
        )
    }
}

@Composable
private fun DiagRow(label: String, value: String, warn: Boolean = false, helpKo: String? = null) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (helpKo != null) Modifier.clickable { open = true } else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            // **누를 수 있는 이름은 밝게 둔다**(2026-09-25 담당자 지시로
            // 설명을 붙이며). 눌러도 된다는 것이 안 보이면 없는 기능이다.
            color = if (helpKo != null) SelahColors.TextSecondary else SelahColors.TextMuted,
            fontSize = 11.sp,
        )
        Text(
            value,
            color = if (warn) SelahColors.Warn else SelahColors.TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
    if (open && helpKo != null) {
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = SelahColors.DialogSurface,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.border(1.dp, SelahColors.Outline, RoundedCornerShape(20.dp)),
            title = { Text(label, color = SelahColors.TextPrimary, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        value,
                        color = if (warn) SelahColors.Warn else SelahColors.TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        helpKo,
                        color = SelahColors.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text("닫기", color = SelahColors.Accent)
                }
            },
        )
    }
}
