package kr.joa.selahrta.ui.components

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.recording.RecordedAudio
import kr.joa.selahrta.ui.theme.SelahColors
import java.io.File

/**
 * 기록에 담긴 **소리를 앱 안에서 들어 본다**(담당자 지시 2026-09-27).
 *
 * ## 왜 앱 안인가
 *
 * 다른 앱으로 넘기면 「이 소리가 그 기록의 것인가」가 흐려진다. 여기서
 * 바로 들으면 위의 숫자와 같은 화면에 있다 — 95dB 이 찍힌 자리에서
 * 무슨 소리가 났는지 그 자리에서 확인할 수 있다.
 *
 * ## 나가면 멈춘다
 *
 * [DisposableEffect] 로 놓는다. 안 놓으면 화면을 떠나도 소리가 계속
 * 나고, 다음에 들어와 또 틀면 **두 개가 겹쳐 난다.**
 *
 * ## 파일이 없으면 그렇게 적는다
 *
 * 겉장에는 소리가 있다고 적혀 있는데 파일이 없을 수 있다 — 지우다
 * 말았거나 옮기다 깨진 경우다. 빈 재생기를 띄우지 않고 말한다.
 */
@Composable
fun AudioPlayerCard(
    audio: RecordedAudio,
    file: File,
    onShare: () -> Unit,
    /**
     * 지금 어디를 듣고 있는가(ms). **밖으로 내보낸다.**
     *
     * 이 값으로 화면이 **그 순간의 측정값**을 함께 보여 준다 — 숫자와
     * 소리를 같은 시각으로 묶어야 「이 자리가 그 자리」라고 말할 수 있다.
     */
    onPosition: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .background(SelahColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "담긴 소리",
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        Text(
            "${audio.format.labelKo} · ${audio.sizeKo()}",
            color = SelahColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )

        // **밀려 빠진 자리가 있으면 말한다.** 들어 보고 「왜 끊기지」
        // 하기 전에 알려 주는 편이 낫다.
        if (audio.droppedBlocks > 0) {
            InfoBar(
                "소리 조각 ${audio.droppedBlocks}개를 미처 담지 못했습니다. " +
                    "그만큼 짧게 끊긴 자리가 있습니다.",
                tone = SelahColors.Warn,
            )
        }

        if (!file.isFile || file.length() <= 0L) {
            InfoBar(
                "소리 파일을 찾지 못했습니다. 기록에는 담겼다고 적혀 있지만 " +
                    "파일이 없습니다.",
                tone = SelahColors.Warn,
            )
            return@Column
        }

        Player(file, onPosition)

        TextButton(onClick = onShare) {
            Text("소리 파일 보내기", color = SelahColors.Accent, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Player(file: File, onPosition: (Int) -> Unit) {
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }
    var failedKo by remember { mutableStateOf<String?>(null) }

    // **화면을 떠나면 놓는다.** 안 놓으면 소리가 계속 나고, 다시
    // 들어와 또 틀면 두 개가 겹친다.
    val player = remember(file.path) {
        runCatching {
            MediaPlayer().apply {
                setDataSource(file.path)
                prepare()
                setOnCompletionListener { playing = false }
            }
        }.onFailure { failedKo = "소리를 열지 못했습니다: ${it.message}" }.getOrNull()
    }
    DisposableEffect(player) {
        durationMs = player?.duration ?: 0
        onDispose {
            runCatching { player?.stop() }
            runCatching { player?.release() }
        }
    }

    // 돌아가는 동안만 자리를 읽는다. 멈춰 있으면 읽을 까닭이 없다.
    LaunchedEffect(playing) {
        while (playing) {
            positionMs = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
            onPosition(positionMs)
            // **0.2초마다 본다.** 행이 0.5초짜리라 이보다 자주 볼 까닭이
            // 없고, 이보다 드물면 행을 건너뛴다.
            kotlinx.coroutines.delay(200)
        }
    }

    failedKo?.let {
        InfoBar(it, tone = SelahColors.Warn)
        return
    }
    if (player == null) return

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = {
                if (playing) {
                    runCatching { player.pause() }
                    playing = false
                } else {
                    runCatching { player.start() }
                    playing = true
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = SelahColors.Accent,
                contentColor = SelahColors.OnAccent,
            ),
        ) {
            Text(if (playing) "멈춤" else "재생", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Text(
            "${clock(positionMs)} / ${clock(durationMs)}",
            color = SelahColors.TextSecondary,
            fontSize = 12.sp,
        )
    }

    Slider(
        value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
        onValueChange = { f ->
            val to = (f * durationMs).toInt()
            positionMs = to
            onPosition(to)
            runCatching { player.seekTo(to) }
        },
        colors = SliderDefaults.colors(
            thumbColor = SelahColors.Accent,
            activeTrackColor = SelahColors.Accent,
            inactiveTrackColor = SelahColors.Outline,
        ),
        modifier = Modifier.fillMaxWidth().height(24.dp),
    )
}

private fun clock(ms: Int): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
