// Optional androidTest extension. Captures ACTION_VIEW without opening a browser.
// Place under app/src/androidTest/java/kr/joa/selahrta/ui/screens/.
package kr.joa.selahrta.ui.screens

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kr.joa.selahrta.ui.CaptureUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsScreenPolicyIntentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun screenUsesRealOpenerForBothPolicyUrls() {
        val opened = mutableListOf<Intent>()
        showSettings { opened.add(it) }
        compose.onNodeWithText("개인정보처리방침").performScrollTo().performClick()
        compose.onNodeWithText("이용약관").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(Intent.ACTION_VIEW, Intent.ACTION_VIEW), opened.map { it.action })
            assertEquals(
                listOf("https://joaworks.com/privacy/", "https://joaworks.com/terms/"),
                opened.map { it.dataString },
            )
        }
    }

    @Test fun missingHandlerBecomesVisibleFailureForBothUrls() {
        val attempted = mutableListOf<Intent>()
        showSettings {
            attempted.add(it)
            throw ActivityNotFoundException("independent test fixture")
        }
        for ((label, url) in listOf(
            "개인정보처리방침" to "https://joaworks.com/privacy/",
            "이용약관" to "https://joaworks.com/terms/",
        )) {
            compose.onNodeWithText(label).performScrollTo().performClick()
            compose.onNodeWithText("브라우저를 열 수 없습니다. 주소를 직접 여십시오: $url")
                .performScrollTo().assertIsDisplayed()
        }
        compose.runOnIdle { assertEquals(2, attempted.size) }
    }

    private fun showSettings(onStartActivity: (Intent) -> Unit) {
        compose.setContent {
            val base = LocalContext.current
            val context = remember(base) {
                object : ContextWrapper(base) {
                    override fun startActivity(intent: Intent) = onStartActivity(intent)
                }
            }
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    SettingsScreen(
                        capture = CaptureUiState(),
                        onSaveCalibration = { _, _ -> },
                        onClearCalibration = {},
                        onDismissCalibrationNotice = {},
                        onConfirmCalibrationRoute = {},
                        onConfirmPendingCalibration = {},
                        onDismissPendingCalibration = {},
                        onSplWeighting = {},
                        onPeakWeighting = {},
                        onAnalysisWeighting = {},
                        onResetSplWeighting = {},
                        onResetPeakWeighting = {},
                        onResetAnalysisWeighting = {},
                        onTimeWeight = {},
                        onLeqWindow = {},
                        onThemeMode = {},
                        onFftSize = {},
                        onPreferredInput = {},
                        onForgetDevice = {},
                        onInputChannel = { _, _ -> },
                        onPickCurveFile = {},
                        onClearCurve = {},
                        onToggleCurve = {},
                        onConfirmCurveReading = { _, _ -> },
                        onCurveMicName = {},
                        onDismissCurveNotice = {},
                        onOpenCalibrationWizard = {},
                        onOpenCalibrationProfiles = {},
                        onSaveRange = { _, _ -> },
                        onResetRange = {},
                        onRenameSegment = { _, _ -> },
                        onAddSegment = {},
                        onRemoveSegment = {},
                    )
                }
            }
        }
    }
}
