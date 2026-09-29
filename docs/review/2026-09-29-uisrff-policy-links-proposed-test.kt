// Proposal: place at app/src/androidTest/java/kr/joa/selahrta/ui/screens/PolicyLinksInteractionTest.kt.
// This is an instrumentation test. Independent review did not execute it on a device.
package kr.joa.selahrta.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PolicyLinksInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun privacyButtonUsesPrivacyUrl() {
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { PolicyLinks(openUrl = { opened.add(it); true }) }
        }
        compose.onNodeWithText("개인정보처리방침").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("https://joaworks.com/privacy/"), opened) }
    }

    @Test fun termsButtonUsesTermsUrl() {
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { PolicyLinks(openUrl = { opened.add(it); true }) }
        }
        compose.onNodeWithText("이용약관").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("https://joaworks.com/terms/"), opened) }
    }

    @Test fun launchFailureShowsTheRequestedAddress() {
        compose.setContent { MaterialTheme { PolicyLinks(openUrl = { false }) } }
        compose.onNodeWithText("개인정보처리방침").performClick()
        compose.onNodeWithText(
            "브라우저를 열 수 없습니다. 주소를 직접 여십시오: https://joaworks.com/privacy/"
        ).assertIsDisplayed()
    }
}
