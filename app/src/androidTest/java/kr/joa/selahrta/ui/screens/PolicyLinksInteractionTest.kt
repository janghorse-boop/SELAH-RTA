package kr.joa.selahrta.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * **단추를 눌러 어디로 가는지 본다.**
 *
 * ## 왜 필요한가 (독립 재검증 2026-09-29, 5장)
 *
 * `PolicyLinkTest` 는 소스를 문자열로 읽는다. 그것으로 호출부 **삭제**는
 * 잡았지만, 검토자가 넣은 다른 변이 셋은 **여섯 개가 다 통과했다**:
 *
 * | 변이 | 소스 시험 |
 * |---|---|
 * | 호출 줄을 `//` 로 주석 처리 | 통과 ← 주석과 코드를 구별 못 한다 |
 * | 개인정보 단추를 `TERMS_URL` 로 연결 | 통과 |
 * | `failedUrl` 갱신 제거 | 통과 |
 *
 * 정규식 조건을 계속 늘리는 것보다 **누른 결과**를 보는 편이 낫다.
 *
 * ## 이 시험이 덮지 않는 것
 *
 * 여는 일을 가짜로 넣으므로 **진짜 `Intent` 도 브라우저도 돌지 않는다.**
 * 실제로 열리는지는 기기에서 봐야 한다(2026-09-29 확인함 — 방침 1.2 ·
 * 약관 1.1 이 브라우저로 떴다).
 *
 * 이 시험은 [PolicyLinks] 라는 **부품**을 본다. 설정 화면이 그 부품을
 * 부르는지는 `SettingsPolicyLinksTest` 가 본다.
 */
class PolicyLinksInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 개인정보처리방침_단추는_방침_주소로_간다() {
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { PolicyLinks(openUrl = { opened.add(it); true }) }
        }
        compose.onNodeWithText("개인정보처리방침").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("https://joaworks.com/privacy/"), opened) }
    }

    @Test
    fun 이용약관_단추는_약관_주소로_간다() {
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { PolicyLinks(openUrl = { opened.add(it); true }) }
        }
        compose.onNodeWithText("이용약관").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("https://joaworks.com/terms/"), opened) }
    }

    /**
     * **열리지 않으면 주소를 보여 준다.**
     *
     * 브라우저가 없거나 막힌 기기에서 조용히 아무 일도 없으면 사람은
     * 앱이 고장 난 줄 안다.
     */
    @Test
    fun 열리지_않으면_그_주소를_화면에_적는다() {
        compose.setContent { MaterialTheme { PolicyLinks(openUrl = { false }) } }
        compose.onNodeWithText("개인정보처리방침").performClick()
        compose.onNodeWithText(
            "브라우저를 열 수 없습니다. 주소를 직접 여십시오: https://joaworks.com/privacy/",
        ).assertIsDisplayed()
    }
}
