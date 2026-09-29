// Place in app/src/androidTest/java/kr/joa/selahrta/ui/screens/SettingsScreenPolicyPresenceTest.kt.
// This tests the actual screen -> app-info -> links connection. No hardware is opened.
package kr.joa.selahrta.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import kr.joa.selahrta.ui.CaptureUiState
import org.junit.Rule
import org.junit.Test

class SettingsScreenPolicyPresenceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actualSettingsScreenContainsPolicyLinks() {
        compose.setContent {
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
        compose.onNodeWithText("개인정보처리방침").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("이용약관").performScrollTo().assertIsDisplayed()
    }
}
