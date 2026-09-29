package kr.joa.selahrta.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.joa.selahrta.audio.SignalChannels
import kr.joa.selahrta.audio.TestSignal
import kr.joa.selahrta.ui.CaptureUiState
import kr.joa.selahrta.ui.components.SignalGeneratorCard

/**
 * **도구** — 소리를 내보내는 것들(2026-09-24 담당자 지시로 설정에서 갈라냄).
 *
 * ## 이 화면이 답하는 질문
 *
 * 「지금 이 방(또는 PA)에 **소리를 넣어 보려면** 무엇을 누르나?」
 *
 * 설정은 **값을 바꿔 두는 곳**이고, 여기는 **하는 곳**이다. 둘을 한
 * 화면에 두었더니 설정이 길어져 정작 자주 쓰는 것이 묻혔다.
 *
 * ## 가장 잘못 읽히는 것
 *
 * **폰 스피커로 내보낸 소리는 측정의 기준이 되지 못한다.** 스피커가
 * 제 보호 회로로 레벨을 내리고, 마이크와 너무 가까워 방이 아니라
 * 스피커를 재게 된다. 이 도구가 쓸모 있는 자리는 **소리가 PA 로 나갈
 * 때**다(USB 인터페이스나 케이블). 카드가 그 사실을 적는다.
 *
 * 앞으로 주파수 발생기(자유 주파수)와 스테레오 L/R 테스트가 여기 붙는다.
 * 그때까지는 카드 하나라 칩을 두지 않는다 — 고를 것이 하나인 고르개는
 * 고르개가 아니다.
 */
@Composable
fun ToolsScreen(
    capture: CaptureUiState,
    onPlaySignal: (TestSignal) -> Unit,
    onStopSignal: () -> Unit,
    onSignalLevel: (Double) -> Unit,
    onSignalToneHz: (Double) -> Unit,
    onSignalChannels: (SignalChannels) -> Unit,
    onDismissSignalNotice: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        SignalGeneratorCard(
            playing = capture.playingSignal,
            amplitude = capture.signalAmplitude,
            toneHz = capture.signalToneHz,
            channels = capture.signalChannels,
            noticeKo = capture.signalNoticeKo,
            onPlay = onPlaySignal,
            onStop = onStopSignal,
            onLevel = onSignalLevel,
            onToneHz = onSignalToneHz,
            onChannels = onSignalChannels,
            onDismissNotice = onDismissSignalNotice,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
    }
}
