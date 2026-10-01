package kr.joa.selahrta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import kr.joa.selahrta.audio.SignalChannels
import kr.joa.selahrta.audio.SignalOutput
import kr.joa.selahrta.audio.TestSignal
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * **틀어도 화면이 안 밀린다**(2026-10-01 담당자 지적).
 *
 * > 125Hz 재생 버튼을 누르면 박스가 살짝 이동하는데 다른 버튼도
 * > 마찬가지입니다.
 *
 * ## 왜 밀렸나
 *
 * 틀어야 생기는 것이 **둘** 있었다 — 머리글의 「RTA에서 재기」와 출력
 * 고르개 아래의 「실제로 어디로 나갔나」 줄. 둘 다 없다가 생기므로 그
 * 아래가 통째로 내려갔다. 폰에서 재니 **210px + 76px** 이었다.
 *
 * 누른 단추가 손가락 아래에서 움직이면 **두 번 누르게 된다** — 그리고
 * 두 번째 누름은 방금 켠 것을 끈다.
 *
 * ## 이 시험이 보는 것
 *
 * 한 화면 안에서 「틀고 있다」만 바꾸고 **아래 줄의 자리가 그대로인지**
 * 묻는다. 자리를 잡아 두지 않으면 곧바로 깨진다.
 *
 * **못 보는 것**: 소리가 실제로 나는지는 안 본다. 여기는 **배치**만 본다.
 */
class SignalCardStableLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    /** 아래쪽에 있어 위가 늘어나면 곧바로 밀리는 줄. */
    private val anchor = "스윕 20Hz~20kHz"

    @Test
    fun 틀어도_아래가_안_밀린다() {
        var playing by mutableStateOf<TestSignal?>(null)
        var route by mutableStateOf<String?>(null)
        compose.setContent {
            Box(Modifier.size(400.dp, 900.dp)) {
                SignalGeneratorCard(
                    playing = playing,
                    amplitude = 0.05,
                    toneHz = 1_000.0,
                    channels = SignalChannels.Both,
                    noticeKo = null,
                    onPlay = {}, onStop = {}, onLevel = {}, onToneHz = {}, onChannels = {},
                    output = SignalOutput.BuiltInSpeaker,
                    onOutput = {},
                    routeKo = route,
                    availableOutputs = emptySet(),
                    capturingFrom = null,
                    onDismissNotice = {},
                    onMeasureInRta = {},
                )
            }
        }

        val before = compose.onNodeWithText(anchor).getBoundsInRoot().top

        // 실제로 틀면 **둘이 함께** 온다 — 단추와 출력 줄.
        playing = TestSignal.Pink
        route = "출력 SM-S918N"
        compose.waitForIdle()

        // 먼저 정말 둘이 떴는지 못박는다. 안 떴으면 이 시험은 아무것도 안 본다.
        compose.onNodeWithText("RTA에서 재기 →").assertIsDisplayed()
        compose.onNodeWithText("출력 SM-S918N").assertIsDisplayed()

        assertEquals(
            "틀었더니 아래가 밀렸다",
            before,
            compose.onNodeWithText(anchor).getBoundsInRoot().top,
        )
    }

    /**
     * **못박아 둔 순음 여덟은 없앴다**(담당자 지시: 「125Hz부터 8kHz까지
     * 송출 버튼은 삭제」). 「주파수 지정」이 그 여덟을 다 덮는다.
     */
    @Test
    fun 못박은_순음_단추는_없다() {
        card()
        for (gone in listOf("125Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz", "8kHz")) {
            assertEquals(
                "$gone 단추가 아직 있다",
                0,
                compose.onAllNodesWithExactText(gone).fetchSemanticsNodes().size,
            )
        }
    }

    /** 차례가 지시받은 그대로인가 — 스윕 → 1/3 옥타브 대역 → 주파수 지정. */
    @Test
    fun 스윕_대역_지정_차례로_온다() {
        card()
        val sweep = compose.onNodeWithText(anchor).getBoundsInRoot().top
        val band = compose.onNodeWithText("1/3 옥타브 대역").getBoundsInRoot().top
        val custom = compose.onNodeWithText("주파수 지정").getBoundsInRoot().top
        assertEquals("스윕이 대역보다 아래다", true, sweep < band)
        assertEquals("대역이 지정보다 아래다", true, band < custom)
    }

    /**
     * **주파수 지정 상자가 곧 순음이다.** 범위를 적고, 제 재생 단추를 갖는다.
     *
     * 예전에는 고르개만 상자에 있고 내보내는 줄은 상자 **밖**에 있어,
     * 주파수를 정해 놓고도 어디를 눌러야 소리가 나는지 한 걸음 떨어져 있었다.
     */
    @Test
    fun 지정_상자가_범위와_재생_단추를_갖는다() {
        card()
        compose.onNodeWithText("20Hz ~ 20kHz", substring = true).assertIsDisplayed()
        // 상자 안(주파수 지정 아래)에 「내보내기」가 있어야 한다.
        val customTop = compose.onNodeWithText("주파수 지정").getBoundsInRoot().top
        val marks = compose.onAllNodesWithContentDescription("내보내기")
            .fetchSemanticsNodes()
            .count { it.boundsInRoot.top >= customTop.value * 0 }
        assertEquals("재생 단추가 하나도 없다", true, marks > 0)
    }

    /** 상자 **밖**의 「순음」 줄은 지웠다 — 상자 자체가 순음이다. */
    @Test
    fun 상자_밖의_순음_줄은_없다() {
        card()
        assertEquals(
            0,
            compose.onAllNodesWithExactText("순음").fetchSemanticsNodes().size,
        )
    }

    private fun card() {
        compose.setContent {
            Box(Modifier.size(400.dp, 900.dp)) {
                SignalGeneratorCard(
                    playing = null,
                    amplitude = 0.05,
                    toneHz = 1_000.0,
                    channels = SignalChannels.Both,
                    noticeKo = null,
                    onPlay = {}, onStop = {}, onLevel = {}, onToneHz = {}, onChannels = {},
                    output = SignalOutput.BuiltInSpeaker,
                    onOutput = {},
                    routeKo = null,
                    availableOutputs = emptySet(),
                    capturingFrom = null,
                    onDismissNotice = {},
                    onMeasureInRta = {},
                )
            }
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule
        .onAllNodesWithExactText(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text, substring = false))

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule
        .onAllNodesWithContentDescription(desc: String) =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(desc))
}
