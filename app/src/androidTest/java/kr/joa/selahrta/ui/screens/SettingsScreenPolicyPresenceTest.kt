package kr.joa.selahrta.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import kr.joa.selahrta.ui.CaptureUiState
import org.junit.Rule
import org.junit.Test

/**
 * **진짜 설정 화면을 열어 방침 줄을 찾는다.**
 *
 * ## 왜 필요한가 (독립 재검증 UIS6-03, 2026-09-29)
 *
 * 앞선 시험 넷은 부품만 만들어 본다 — `PolicyLinks(...)` 나
 * `AppInfoWithPolicyLinks(...)` 를 시험이 직접 세운다. 그래서
 * **진짜 설정 화면이 그 부품을 부르지 않아도** 넷 다 통과한다.
 *
 * 검토자가 실제 화면의 `AppInfoWithPolicyLinks()` 호출만 주석 처리하니
 * `PolicyLinkTest` **여섯 개가 다 통과**했다. 「둘이 함께 네 가지
 * 변이를 모두 막는다」고 적은 것은 **여전히 과한 말**이었다.
 *
 * 이 시험만이 **화면 → 앱 정보 → 링크**의 연결을 통째로 지난다.
 *
 * ## 하드웨어를 열지 않는다
 *
 * `SettingsScreen` 이 받는 것은 **상태와 콜백뿐**이다. 기본
 * [CaptureUiState] 와 빈 콜백을 넣으면 마이크도 DataStore 도 열지
 * 않는다 — 화면이 그려지기만 하면 된다.
 */
class SettingsScreenPolicyPresenceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 설정_화면을_끝까지_내리면_방침과_약관이_있다() {
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
                    onPortraitMode = {},
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
