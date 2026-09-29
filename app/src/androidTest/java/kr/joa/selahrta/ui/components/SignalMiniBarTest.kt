package kr.joa.selahrta.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kr.joa.selahrta.audio.SignalChannels
import kr.joa.selahrta.audio.TestSignal
import kr.joa.selahrta.ui.AppBottomArea
import kr.joa.selahrta.ui.nav.NavSection
import kr.joa.selahrta.ui.nav.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * **진짜 아래 영역을 열어 미니 바를 찾는다**(지시서 §1).
 *
 * ## 왜 부품만 보지 않는가
 *
 * 앞선 검토에서 **네 번** 같은 함정에 빠졌다 — 부품을 시험이 직접
 * 세워 놓고 「지켜진다」고 적었는데, 진짜 화면이 그 부품을 부르지
 * 않아도 다 통과했다(누적 요청서 5-1 의 6번).
 *
 * 그래서 여기서는 [AppBottomArea] 를 연다. 앱이 실제로 쓰는 그 함수다.
 * `SelahApp` 이 이것을 부르는 **한 줄**만 시험 밖에 남는다 — 그 한 줄은
 * 실기기에서 눈으로 본다.
 *
 * ## 하드웨어를 열지 않는다
 *
 * [AppBottomArea] 가 받는 것은 값과 콜백뿐이다. 마이크도 DataStore 도
 * 열지 않는다.
 */
class SignalMiniBarTest {
    @get:Rule
    val compose = createComposeRule()

    private fun area(
        playing: TestSignal?,
        screen: ViewMode? = ViewMode.Rta,
        toneHz: Double = 1_000.0,
        channels: SignalChannels = SignalChannels.Both,
        onStop: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                AppBottomArea(
                    section = screen?.section ?: NavSection.Settings,
                    screen = screen,
                    playingSignal = playing,
                    signalToneHz = toneHz,
                    signalChannels = channels,
                    onStopSignal = onStop,
                    onSelect = {},
                )
            }
        }
    }

    // ── 있고 없고 ────────────────────────────────────────

    @Test
    fun 안_틀었으면_미니_바가_없다() {
        area(playing = null)
        compose.onNodeWithContentDescription(STOP_DESC).assertDoesNotExist()
    }

    @Test
    fun 다른_화면에서_틀려_있으면_미니_바가_보인다() {
        area(playing = TestSignal.Pink)
        compose.onNodeWithText("핑크 노이즈", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(STOP_DESC).assertIsDisplayed()
    }

    @Test
    fun 테스트_신호_화면에서는_미니_바가_없다() {
        area(playing = TestSignal.Pink, screen = ViewMode.Signal)
        compose.onNodeWithContentDescription(STOP_DESC).assertDoesNotExist()
    }

    // ── 무엇이 나가는지 적는다 ───────────────────────────

    /**
     * **왼쪽만 틀어 둔 것을 모르면 「오른쪽이 죽었다」고 잘못 읽는다.**
     * 좌우 고르개는 PA 에 물렸을 때 쓰는 것이라 더 그렇다.
     */
    @Test
    fun 어느_쪽으로_나가는지_적는다() {
        area(playing = TestSignal.Pink, channels = SignalChannels.Left)
        compose.onNodeWithText("왼쪽만", substring = true).assertIsDisplayed()
    }

    /** 고른 주파수를 쓰는 신호는 **그 주파수**를 적는다. 이름만으로는 모른다. */
    @Test
    fun 고른_주파수를_적는다() {
        area(playing = TestSignal.Custom, toneHz = 762.0)
        compose.onNodeWithText("762", substring = true).assertIsDisplayed()
    }

    /**
     * **대역은 맞춰진 중심을 적는다.** 슬라이더가 3147Hz 를 가리켜도
     * 실제로 나가는 것은 3150Hz 대역이다 — 나가는 것을 적어야 한다.
     *
     * 표기는 화면의 다른 자리와 같다(`formatHz`): 1kHz 위는 「3.15 kHz」.
     */
    @Test
    fun 대역은_맞춰진_중심을_적는다() {
        area(playing = TestSignal.Band, toneHz = 3_147.0)
        compose.onNodeWithText("3.15 kHz", substring = true).assertIsDisplayed()
    }

    // ── 멈춘다 ───────────────────────────────────────────

    @Test
    fun 미니_바에서_멈출_수_있다() {
        var stops = 0
        area(playing = TestSignal.Pink, onStop = { stops++ })
        compose.onNodeWithContentDescription(STOP_DESC).performClick()
        assertEquals(1, stops)
    }

    private companion object {
        const val STOP_DESC = "테스트 신호 멈추기"
    }
}
