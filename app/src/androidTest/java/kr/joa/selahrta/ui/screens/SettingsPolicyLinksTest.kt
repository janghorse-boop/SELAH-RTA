package kr.joa.selahrta.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

/**
 * **설정 화면이 방침 줄을 실제로 그리는가.**
 *
 * `PolicyLinksInteractionTest` 는 [PolicyLinks] 라는 **부품**이 제대로
 * 도는지를 본다. 그런데 그 부품을 **아무도 부르지 않으면** 부품 시험은
 * 그대로 통과하고 화면에서만 링크가 사라진다.
 *
 * 그 틈은 `PolicyLinkTest`(소스 문자열)가 메우려 했는데, 호출 줄을
 * **주석 처리**하면 통과했다(독립 재검증 2026-09-29). 문자열 검색으로는
 * 주석과 코드를 구별할 수 없다.
 *
 * 그래서 **그려진 화면에서** 두 단추를 찾는다.
 *
 * ## 왜 설정 화면 전체가 아니라 「앱 정보」 조각인가
 *
 * `SettingsScreen` 은 캡처 상태·설정 저장소·기기 목록을 다 받는다.
 * 그것을 모두 꾸며 띄우면 시험이 **링크가 아닌 것들 때문에** 깨진다.
 *
 * 여기서 지키려는 계약은 하나다 — **「앱 정보」 아래에 방침·약관 줄이
 * 있다.** 그 조각만 띄운다. 조각을 화면에서 떼어 내는 변이는 이 시험이
 * 아니라 `PolicyLinkTest` 의 호출부 검사가 잡는다. 둘이 함께 있어야
 * 네 가지 변이가 모두 막힌다.
 */
class SettingsPolicyLinksTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 앱_정보_아래에_방침과_약관이_있다() {
        compose.setContent {
            MaterialTheme {
                Column { AppInfoWithPolicyLinks(openUrl = { true }) }
            }
        }
        compose.onNodeWithText("개인정보처리방침").assertIsDisplayed()
        compose.onNodeWithText("이용약관").assertIsDisplayed()
    }
}
