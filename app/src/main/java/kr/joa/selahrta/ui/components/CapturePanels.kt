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
import kr.joa.selahrta.audio.activeMicComboKo
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

/*
 * **캡처 진단 상자(`DiagnosticsPanel`)를 지웠다**(독립 검토 UIS-03,
 * 2026-09-28).
 *
 * 2026-09-28 에 담당자 지시로 측정 화면에서 호출을 뺐는데, 선언은
 * 「필요하면 되살리면 된다」며 남겨 두었다. 그 뒤로 **아무도 부르지
 * 않는 180줄**이 되었고, 컴파일러도 시험도 그 사실을 말하지 않았다.
 * 되살릴 것은 git 에 그대로 있다(이 파일의 이력).
 *
 * 그 상자 안에만 있던 **경고**는 버리지 않았다. 지연·읽기 오류는
 * `kr.joa.selahrta.audio.captureWarningKo` 가 문제가 있을 때만 한 줄로
 * 내놓고, 측정 화면이 그린다. 늘 떠 있는 숫자판만 없앤 것이다.
 */
