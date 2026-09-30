package kr.joa.selahrta.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import kr.joa.selahrta.recording.AudioFileFormat
import kr.joa.selahrta.recording.RecordedAudio
import kr.joa.selahrta.recording.SessionMeta
import kr.joa.selahrta.ui.CaptureUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * **다시 분석 단추가 진짜 화면에 있고, 눌리는가**(명세 Recording-E).
 *
 * ## 왜 부품이 아니라 화면을 여는가
 *
 * 이 저장소는 부품만 세워 놓고 「지켜진다」고 적었다가 **진짜 화면이
 * 그 부품을 부르지 않아도 통과한 일이 네 번** 있었다. 그래서
 * [HistoryScreen] 을 연다.
 *
 * ## 이 시험이 못 보는 것
 *
 * **실제로 다시 셈하는 일**은 안 본다 — 콜백이 오는 데까지다.
 * 셈하는 쪽은 `ReanalysisTest`·`SessionReanalyzerTest` 가 JVM 에서 본다.
 */
class ReanalyzeUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun meta(reanalyzedAt: Long? = null, withAudio: Boolean = true) = SessionMeta(
        id = "re-1",
        startedAtEpochMs = 1_700_000_000_000L,
        endedAtEpochMs = 1_700_000_010_000L,
        durationMs = 10_000L,
        deviceKey = "k",
        deviceLabel = "시험기기",
        micKind = MicKind.BuiltIn,
        sampleRate = 48_000,
        encoding = "Float",
        channelCount = 1,
        channelIndex = 0,
        calibrationOffsetDb = 110.0,
        referenceOnly = false,
        curveApplied = false,
        weighting = Weighting.A,
        timeWeight = TimeWeight.Fast,
        leqWindowMs = 10_000L,
        leqDb = 60.0,
        minDb = 50.0,
        maxDb = 90.0,
        peakDb = 92.0,
        reanalyzedAtEpochMs = reanalyzedAt,
        audio = if (withAudio) {
            RecordedAudio(AudioFileFormat.Wav, "sound.wav", 1_000L)
        } else {
            null
        },
    )

    private var restored: SessionMeta? = null

    private fun screen(
        m: SessionMeta,
        progress: Float? = null,
        onReanalyze: (SessionMeta) -> Unit = {},
    ) {
        compose.setContent {
            HistoryScreen(
                capture = CaptureUiState(openedSession = m),
                onOpen = {}, onClose = {}, onExport = {}, onExportPdf = {},
                onDelete = {}, onDismissNotice = {},
                // 파일은 없어도 된다 — 재생기는 「없습니다」로 적고 넘어간다.
                audioFileOf = { File("없는파일.wav") },
                onShareAudio = {}, onMemo = { _, _ -> },
                onReanalyze = onReanalyze,
                reanalyzeProgress = progress,
                onRestoreOriginal = { restored = it },
            )
        }
    }

    @Test
    fun 단추를_누르면_그_기록으로_불린다() {
        var got: SessionMeta? = null
        screen(meta()) { got = it }

        compose.onNodeWithText("지금 설정으로 다시 분석").assertIsDisplayed()
        compose.onNodeWithText("지금 설정으로 다시 분석").performClick()
        compose.waitForIdle()

        assertEquals("re-1", got?.id)
    }

    /**
     * **되돌릴 수 없다고 생각하면 아무도 안 누른다.**
     *
     * 그래서 「소리와 처음 잰 값은 그대로 남는다」를 단추 옆에 적어 둔다.
     */
    @Test
    fun 무엇이_남는지_적혀_있다() {
        screen(meta())
        compose.onNodeWithText("처음 잰 값은 그대로 남습니다", substring = true)
            .assertIsDisplayed()
    }

    /**
     * **이미 다시 분석한 기록이면 그렇게 적는다.**
     *
     * 아래 숫자가 **잰 그날의 것이 아니라는** 뜻이라, 다른 기록과 견줄
     * 때 사람이 알아야 한다.
     */
    @Test
    fun 다시_분석한_기록이면_그렇게_적는다() {
        screen(meta(reanalyzedAt = 1_700_000_500_000L))
        compose.onNodeWithText("다시 분석했습니다", substring = true).assertIsDisplayed()
    }

    @Test
    fun 아직_안_했으면_그_줄이_없다() {
        // 위 시험이 「늘 적는 코드」로도 통과하지 않게 짝을 둔다.
        screen(meta())
        assertEquals(
            0,
            compose.onAllNodesWithTextContaining("다시 분석했습니다").fetchSemanticsNodes().size,
        )
    }

    /**
     * **도는 중에는 단추가 사라지고 진행이 뜬다.**
     *
     * 긴 녹음은 오래 걸린다. 아무 표시가 없으면 사람은 앱이 멈춘 줄
     * 알고 나가거나 **다시 누른다.**
     */
    @Test
    fun 도는_중에는_진행이_뜬다() {
        screen(meta(), progress = 0.42f)
        compose.onNodeWithText("42%", substring = true).assertIsDisplayed()
        assertEquals(
            "도는 중인데 단추가 아직 있다",
            0,
            compose.onAllNodesWithTextContaining("지금 설정으로 다시 분석")
                .fetchSemanticsNodes().size,
        )
    }

    /** 소리가 없는 기록에는 **단추 자체가 없다.** 다시 셈할 것이 없다. */
    @Test
    fun 소리가_없으면_단추도_없다() {
        screen(meta(withAudio = false))
        assertEquals(
            0,
            compose.onAllNodesWithTextContaining("지금 설정으로 다시 분석")
                .fetchSemanticsNodes().size,
        )
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule
        .onAllNodesWithTextContaining(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text, substring = true))

    // ── 남겨 둔 원본을 꺼낼 수 있는가 ───────────────────

    /**
     * **되돌릴 수 없으면 「그대로 남습니다」는 확인할 수 없는 말이다.**
     *
     * 원본을 파일로 남겨 두어도 꺼낼 길이 없으면, 사용자에게는 그
     * 약속이 **지켜졌는지 알 수 없는 말**로만 남는다.
     */
    @Test
    fun 다시_분석한_기록은_되돌릴_수_있다() {
        screen(meta(reanalyzedAt = 1_700_000_500_000L))
        compose.onNodeWithText("처음 잰 값으로 되돌리기").assertIsDisplayed()
        compose.onNodeWithText("처음 잰 값으로 되돌리기").performClick()
        compose.waitForIdle()
        assertEquals("re-1", restored?.id)
    }

    /** 아직 안 한 기록에는 **되돌릴 것이 없다.** 단추도 없다. */
    @Test
    fun 아직_안_했으면_되돌리기도_없다() {
        screen(meta())
        assertEquals(
            0,
            compose.onAllNodesWithTextContaining("되돌리기").fetchSemanticsNodes().size,
        )
    }

    /** 도는 중에는 **되돌리기도 감춘다.** 둘이 같은 파일을 만진다. */
    @Test
    fun 도는_중에는_되돌리기도_없다() {
        screen(meta(reanalyzedAt = 1L), progress = 0.5f)
        assertEquals(
            0,
            compose.onAllNodesWithTextContaining("되돌리기").fetchSemanticsNodes().size,
        )
    }
}
