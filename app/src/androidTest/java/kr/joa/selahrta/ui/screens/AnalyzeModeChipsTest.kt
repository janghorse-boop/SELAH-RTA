package kr.joa.selahrta.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import kr.joa.selahrta.ui.CaptureUiState
import kr.joa.selahrta.ui.nav.NavSection
import kr.joa.selahrta.ui.nav.ViewMode
import org.junit.Rule
import org.junit.Test

/**
 * **분석 화면의 네 고르개가 실제로 보이는가**(2026-10-01 담당자 신고:
 * 「분석탭에 RTA만 있고 Spectrum과 Spectrogram이 사라졌습니다」).
 *
 * ## 왜 부품이 아니라 화면을 여는가
 *
 * [AnalyzeModes] 만 따로 세우면 **언제나 통과한다** — 네 칩을 그리는
 * 코드는 멀쩡했다. 사라진 까닭은 **옆자리가 폭을 다 가져갔기** 때문이고,
 * 그것은 화면을 열어야만 드러난다.
 *
 * `BandMeter` 의 머리줄은 `SpaceBetween` 인 `Row` 에 상자 둘을 둔다 —
 * 왼쪽이 `controls`, 오른쪽이 `modes`. 그런데 `RtaSaveControls` 가
 * `fillMaxWidth()` 라 **왼쪽 상자가 폭을 다 먹고**, 오른쪽에 남는 자리가
 * 없어 칩이 화면 밖으로 밀렸다.
 *
 * ## 이 시험이 **못 보는 것**
 *
 * 세로 화면은 안 본다 — 분석 구역은 늘 가로로 돈다(`LandscapeWhile`).
 * 그리고 **좁은 폭에서 네 칩이 다 들어가는가**는 이 시험이 말하지
 * 않는다. 여기서 보는 것은 **자리를 빼앗기지 않는가**뿐이다.
 */
class AnalyzeModeChipsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun rta(width: Int) {
        compose.setContent {
            // 눕힌 폰의 폭을 흉내 낸다. 분석 구역은 늘 가로다.
            Box(Modifier.size(width.dp, 360.dp)) {
                RtaScreen(capture = CaptureUiState(), onMode = {})
            }
        }
    }

    /**
     * **넷이 다 있어야 한다.** 하나라도 없으면 그 화면으로 가는 길이
     * 아예 없다 — 눌러서 돌아올 다른 문이 없다.
     */
    @Test
    fun 분석_고르개_넷이_모두_보인다() {
        rta(width = 800)
        for (m in ViewMode.entries.filter { it.section == NavSection.Analyze }) {
            compose.onNodeWithText(m.labelKo).assertIsDisplayed()
        }
    }

    /**
     * **좁아져도 먼저 사라지지 않는다.**
     *
     * 실기기(SM-S918N)를 눕히면 640dp 안팎이다. 저장 단추 줄이 폭을 다
     * 가져가던 때에 그 폭에서 셋이 밀려났다.
     */
    @Test
    fun 좁은_가로에서도_고르개가_남는다() {
        rta(width = 640)
        compose.onNodeWithText(ViewMode.Rta.labelKo).assertIsDisplayed()
        compose.onNodeWithText(ViewMode.Spectrum.labelKo).assertIsDisplayed()
        compose.onNodeWithText(ViewMode.Spectrogram.labelKo).assertIsDisplayed()
        compose.onNodeWithText(ViewMode.Fr.labelKo).assertIsDisplayed()
    }

    /** 저장 단추 줄은 **그대로 있어야 한다.** 고치면서 저쪽을 지우지 않는다. */
    @Test
    fun 저장_단추_줄도_그대로_있다() {
        rta(width = 800)
        compose.onNodeWithText("이 곡선 저장").assertIsDisplayed()
    }

    // ── 네 화면 모두에 있어야 한다 ───────────────────────
    //
    // **한 화면만 고치면 다음에 같은 자리에서 또 잃는다.** 실제로 덫은
    // 세 차트(`BandMeter`·`SpectrumChart`·`SpectrogramChart`)에 똑같이
    // 있었고, 지금 걸린 것은 RTA 하나뿐이었다 — 나머지는 아직 폭을
    // 채우는 줄이 없어서 멀쩡했을 뿐이다.

    private fun chipsVisible() {
        for (m in ViewMode.entries.filter { it.section == NavSection.Analyze }) {
            compose.onNodeWithText(m.labelKo).assertIsDisplayed()
        }
    }

    @Test
    fun Spectrum_화면에도_고르개_넷이_있다() {
        compose.setContent {
            Box(Modifier.size(640.dp, 360.dp)) {
                SpectrumScreen(capture = CaptureUiState(), onMode = {})
            }
        }
        chipsVisible()
    }

    @Test
    fun Spectrogram_화면에도_고르개_넷이_있다() {
        compose.setContent {
            Box(Modifier.size(640.dp, 360.dp)) {
                SpectrogramScreen(
                    capture = CaptureUiState(),
                    feed = rememberSpectrogramFeed(CaptureUiState(), running = false),
                    onMode = {},
                )
            }
        }
        chipsVisible()
    }

    @Test
    fun FR_화면에도_고르개_넷이_있다() {
        compose.setContent {
            Box(Modifier.size(640.dp, 360.dp)) {
                FrScreen(
                    capture = CaptureUiState(),
                    onMeasure = {}, onMeasureQuiet = {}, onMeasureSignal = {},
                    onCancel = {}, onPlayHere = {}, onDismissNotice = {},
                    onMode = {},
                )
            }
        }
        chipsVisible()
    }
}
