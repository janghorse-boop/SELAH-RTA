package kr.joa.selahrta.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kr.joa.selahrta.audio.DEFAULT_AMPLITUDE
import kr.joa.selahrta.audio.SignalChannels
import kr.joa.selahrta.audio.TestSignal
import kr.joa.selahrta.ui.CaptureUiState
import kr.joa.selahrta.ui.screens.ToolsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * **틀어 놓고 바로 재러 가는 한 걸음**(지시서 §1).
 *
 * ## 왜 필요한가
 *
 * 소리는 화면을 넘어도 이어진다 — 재생은 앱 전체가 함께 쓰는
 * `CaptureViewModel` 이 들고 있다. 그런데 도구 → 분석 → RTA 로 손가락을
 * 세 번 옮겨야 하고, **그 사이에 끊길까 봐 사람이 먼저 멈춘다.**
 *
 * ## 진짜 화면을 연다
 *
 * 부품([SignalGeneratorCard])이 아니라 [ToolsScreen] 을 연다. 부품만
 * 세워 보면 **진짜 화면이 그 부품을 부르지 않아도 통과한다** — 앞선
 * 검토에서 네 번 걸린 함정이다.
 *
 * ## 이 시험이 못 보는 것
 *
 * **누른 뒤에 소리가 실제로 이어지는지**는 여기서 못 본다. 콜백이
 * 불렸다는 것까지만 본다 — 화면을 바꾸는 일은 `SelahApp` 이 하고,
 * 그쪽은 ViewModel·권한·마이크를 물고 있어 시험이 열 수 없다.
 * **그것은 실기기에서 확인한다.**
 */
class MeasureInRtaButtonTest {
    @get:Rule
    val compose = createComposeRule()

    private fun tools(playing: TestSignal?, onMeasureInRta: () -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                ToolsScreen(
                    capture = CaptureUiState(
                        playingSignal = playing,
                        signalAmplitude = DEFAULT_AMPLITUDE,
                        signalToneHz = 1_000.0,
                        signalChannels = SignalChannels.Both,
                    ),
                    onPlaySignal = {},
                    onStopSignal = {},
                    onSignalLevel = {},
                    onSignalToneHz = {},
                    onSignalChannels = {},
                    onDismissSignalNotice = {},
                    onMeasureInRta = onMeasureInRta,
                )
            }
        }
    }

    /** **안 틀었으면 갈 까닭이 없다.** 조용한 RTA 로 보내면 헛걸음이다. */
    @Test
    fun 안_틀었으면_단추가_없다() {
        tools(playing = null)
        compose.onNodeWithContentDescription(DESC).assertDoesNotExist()
    }

    @Test
    fun 틀어_두면_단추가_보인다() {
        tools(playing = TestSignal.Pink)
        compose.onNodeWithContentDescription(DESC).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun 누르면_RTA_로_가자고_알린다() {
        var went = 0
        tools(playing = TestSignal.Pink, onMeasureInRta = { went++ })
        compose.onNodeWithContentDescription(DESC).performScrollTo().performClick()
        assertEquals(1, went)
    }

    private companion object {
        const val DESC = "RTA에서 재기"
    }
}
