package kr.joa.selahrta.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import kr.joa.selahrta.recording.AudioFileFormat
import kr.joa.selahrta.recording.RecordedAudio
import kr.joa.selahrta.recording.SessionMeta
import kr.joa.selahrta.recording.TimelineFormat
import kr.joa.selahrta.recording.TimelineRow
import kr.joa.selahrta.recording.WavWriter
import kr.joa.selahrta.ui.CaptureUiState
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * **그래프를 눌러 그 시점의 소리로 간다**(명세 Recording-D
 * 「그래프 탭↔오디오 seek **양방향** 동기」).
 *
 * ## 왜 부품이 아니라 진짜 화면을 여는가
 *
 * 이 저장소는 같은 함정에 **네 번** 빠진 적이 있다 — 부품을 시험이
 * 직접 세워 놓고 「지켜진다」고 적었는데, **진짜 화면이 그 부품을 부르지
 * 않아도 다 통과했다.**
 *
 * 그래서 여기서는 [HistoryScreen] 을 연다. 앱이 실제로 쓰는 그 함수다.
 * 그래프를 누르면 → 재생기가 그 자리로 옮기고 → 「듣는 자리의 값」이
 * 바뀐다. **그 사슬 전체**를 본다.
 *
 * ## 이 시험이 못 보는 것
 *
 * **소리가 실제로 그 자리에서 나는지**는 못 본다. `MediaPlayer` 에
 * `seekTo` 를 시킬 뿐 귀로 확인한 것이 아니다. 여기서 보는 것은
 * **화면이 같은 시각을 가리키는가**이다.
 */
class TimelineSeekTest {

    @get:Rule
    val compose = createComposeRule()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext

    /** 20행 = 10초. **12번 행만 크게** 해 두고 그 자리를 찾아간다. */
    private fun rows() = (0 until 20).map { i ->
        val raw = if (i == 12) -20.0 else -60.0
        TimelineRow(
            rowIndex = i,
            // **셋을 갈라 둔다.** 같은 값이면 화면에 같은 글자가 셋이라
            // 「어느 칸을 보고 있는지」를 시험이 가릴 수 없다.
            currentRaw = raw, currentEpoch = 0,
            maxRaw = raw + 1.0, maxEpoch = 0,
            peakRaw = raw + 2.0, peakEpoch = 0,
            clipped = false, missing = false,
            bands = FloatArray(ThirdOctave.BAND_COUNT) { raw.toFloat() },
        )
    }

    /** 10초짜리 무음 WAV. **진짜 파일이어야** 재생기가 열린다. */
    private fun wav(): File {
        val f = File(app.cacheDir, "seek-test-${System.nanoTime()}.wav")
        val rate = 8_000
        val out = f.outputStream()
        val w = WavWriter(out, rate)
        val block = FloatArray(rate)
        repeat(10) { w.write(block, rate) }
        // **닫은 뒤에 머리를 적는다** — 버퍼에 남으면 길이가 어긋난다.
        out.close()
        w.finish(f)
        return f
    }

    private fun meta(file: File) = SessionMeta(
        id = "seek-1",
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
        peakDb = 90.0,
        audio = RecordedAudio(AudioFileFormat.Wav, file.name, file.length()),
    )

    @Test
    fun 그래프를_누르면_그_자리의_값이_보인다() {
        val file = wav()
        val m = meta(file)
        try {
            compose.setContent {
                HistoryScreen(
                    capture = CaptureUiState(openedSession = m),
                    onOpen = {}, onClose = {}, onExport = {}, onExportPdf = {},
                    onDelete = {}, onDismissNotice = {},
                    audioFileOf = { file },
                    onShareAudio = {}, onMemo = { _, _ -> },
                    onReanalyze = {}, reanalyzeProgress = null,
                    onRestoreOriginal = {},
                    rows = rows(),
                )
            }

            // 처음에는 0초 자리 — 조용한 행이다(-60 + 110 = 50.0).
            compose.onNodeWithText("50.0").assertIsDisplayed()

            // **그래프의 60% 지점을 누른다** = 6.0초 = 12번 행.
            val graph = compose.onNodeWithContentDescription(
                "시간에 따른 음압",
                substring = true,
            )
            graph.assertIsDisplayed()
            graph.performTouchInput {
                click(Offset(width * 0.6f, height * 0.5f))
            }
            compose.waitForIdle()

            // 12번 행은 -20 + 110 = 90.0. **누른 자리의 값이 나와야 한다.**
            compose.onNodeWithText("90.0").assertIsDisplayed()
        } finally {
            file.delete()
        }
    }
}
