package kr.joa.selahrta.ui.instrument

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kr.joa.selahrta.ui.CaptureUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * **악기 EQ 가이드 화면**(담당자 지시서 2026-09-30 §35).
 *
 * 여기서 보는 것은 **화면이 무엇을 말하고 무엇을 안 말하는가**다.
 * 대역과 RTA 칸의 짝짓기는 `EqBandHighlightTest` 가 기기 없이 본다.
 */
class InstrumentEqGuideTest {

    @get:Rule
    val rule = createComposeRule()

    private fun open(state: CaptureUiState = CaptureUiState()) {
        rule.setContent {
            InstrumentGuideScreen(capture = state, onStartMeasure = {})
        }
    }

    // ── 뺀 것이 정말 빠졌는가 (§34) ─────────────────────

    /**
     * **게이트·컴프레서·「무엇을 볼까요」·「전부 보기/증상만」이 없다.**
     *
     * 지운 줄 알았는데 남아 있는 것이 이 저장소가 거듭 데인 자리다.
     * 세어서 막는다.
     */
    @Test
    fun 뺀_것들이_화면에_없다() {
        open()
        listOf("게이트", "컴프레서", "무엇을 볼까요", "전부 보기", "증상만").forEach { gone ->
            assertEquals(
                "「$gone」가 아직 화면에 있다",
                0,
                rule.onAllNodesWithText(gone, substring = true).fetchSemanticsNodes().size,
            )
        }
    }

    // ── 측정이 꺼져 있을 때 (담당자 확인 2026-09-30) ────

    /**
     * **마이크가 없어도 표는 열린다.**
     *
     * 이 화면은 원래 권한 없이 열리는 표였다(명세 §1 원칙 2). 위에 RTA 를
     * 얹었다고 그 성질을 버리면, 마이크를 못 켠 사람은 **가이드까지 못
     * 본다.**
     */
    @Test
    fun 측정이_꺼져_있어도_가이드가_열린다() {
        open()
        rule.onNodeWithText("측정이 꺼져 있습니다").assertIsDisplayed()
        rule.onNodeWithText("EQ 포인트").performScrollTo().assertIsDisplayed()
    }

    /** **가짜 그래프를 그리지 않는다.** 0dB 막대는 「소리가 없다」로 읽힌다. */
    @Test
    fun 측정이_꺼져_있으면_시작_단추를_보인다() {
        open()
        rule.onNodeWithText("측정 시작").assertIsDisplayed()
    }

    // ── 고르기 (§8·§9·§14) ──────────────────────────────

    /** 악기를 바꾸면 그 악기의 세부 유형으로 갈아탄다. */
    @Test
    fun 악기를_바꾸면_세부유형도_따라간다() {
        open()
        rule.onNodeWithText("베이스기타").performScrollTo().performClick()
        rule.onNodeWithText("베이스기타 · ", substring = true).assertIsDisplayed()
    }

    /**
     * **고른 카드는 색이 아니라 말로도 표시된다**(지시서 §32).
     *
     * 색만으로 가르면 색을 못 가리는 사람에게는 **아무것도 안 고른 화면**과
     * 같다.
     */
    @Test
    fun 고른_카드에_보는_중이_적힌다() {
        open()
        assertTrue(
            "처음부터 한 장이 골라져 있어야 한다",
            rule.onAllNodesWithText("보는 중").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    /** 카드를 누르면 위쪽 요약이 그 대역으로 바뀐다. */
    @Test
    fun 카드를_누르면_위쪽_요약이_바뀐다() {
        open()
        val profile = kr.joa.selahrta.data.instrument.InstrumentCatalog
            .find("acoustic_guitar.strum")!!
        val second = profile.regions[1].range.labelKo()

        rule.onAllNodesWithText(second).onFirst().performScrollTo().performClick()
        // 요약 줄과 카드 제목 둘 다 같은 글이므로 **둘 이상** 보이면 된다.
        assertTrue(
            "위쪽 요약이 안 바뀌었다",
            rule.onAllNodesWithText(second).fetchSemanticsNodes().size >= 2,
        )
        rule.onNodeWithContentDescription("$second 보는 중").assertExists()
    }

    // ── 말하지 않아야 할 것 (§15·§23) ───────────────────

    /**
     * **올려라·내려라라고 말하지 않는다.**
     *
     * 이 화면의 존재 이유가 「RTA 가 높으니 깎아라」를 **안 만드는 것**이다
     * (지시서 §15·§39). 문구가 그쪽으로 새는지 화면에서 직접 본다.
     */
    @Test
    fun 올리라거나_내리라고_말하지_않는다() {
        open()
        listOf("올리세요", "내리세요", "부스트하세요", "컷하세요").forEach { banned ->
            assertEquals(
                "「$banned」 같은 명령이 화면에 있다",
                0,
                rule.onAllNodesWithText(banned, substring = true).fetchSemanticsNodes().size,
            )
        }
    }

    /** **최종 판단은 귀**라는 말이 화면에 있다(§16·§38). */
    @Test
    fun 최종_판단은_귀라고_적혀_있다() {
        open()
        rule.onNodeWithText("최종 판단은 귀로", substring = true).assertExists()
    }
}
