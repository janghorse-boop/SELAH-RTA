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

/**
 * **진짜 안드로이드 경계까지 내려가 본다**(독립 검토 UIS7 §5 제안,
 * 2026-09-29 — 검토자가 보낸 시험을 그대로 받았다).
 *
 * ## 무엇이 아직 안 지켜지고 있었나
 *
 * 앞선 시험들은 `openUrl` 을 **시험이 끼워 넣는다.** 그래서 화면이 링크를
 * 가지고 있고 눌리는 것까지는 보지만, **기본 여는 쪽이 실제로 `Intent` 를
 * 만들어 `startActivity` 를 부르는지**는 못 본다.
 *
 * 검토자가 기본 여는 쪽의 `startActivity` 만 건너뛰고 `true` 를 돌려주도록
 * 바꿔 보니 **기존 계측 시험 다섯이 모두 통과**했다. 방침 링크가 아무것도
 * 열지 않는데 말이다.
 *
 * 「시험이 있으니 지켜진다」가 이 저장소에서 틀린 것이 **네 번**이었고,
 * 이것이 그 다섯 번째가 될 자리였다.
 *
 * ## 어떻게 보나
 *
 * 제품에 시험용 구멍을 내지 않는다. `LocalContext` 를 감싼 것으로 바꿔
 * **안드로이드를 부르는 그 한 자리만** 들여다본다.
 *
 * - 하나는 두 링크의 `ACTION_VIEW` 와 주소가 맞는지 본다.
 * - 하나는 `ActivityNotFoundException` 을 넣어 **사람에게 보이는 안내**가
 *   두 주소 모두에 나오는지 본다.
 *
 * **이것으로도 입증되지 않는 것**: 실제 브라우저가 그 주소를 받는지,
 * 그 주소의 페이지에 무엇이 있는지, 다른 화면 크기·글자 배율에서 어떤지.
 */
class SettingsScreenPolicyIntentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun 두_방침_링크가_실제로_브라우저를_부른다() {
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

    @Test fun 열_수_없으면_두_주소_모두_안내가_보인다() {
        val attempted = mutableListOf<Intent>()
        showSettings {
            attempted.add(it)
            throw ActivityNotFoundException("시험이 만든 상황")
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
