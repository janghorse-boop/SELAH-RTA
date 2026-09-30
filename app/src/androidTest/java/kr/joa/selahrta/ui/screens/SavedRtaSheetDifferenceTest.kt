package kr.joa.selahrta.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kr.joa.selahrta.data.rta.RtaComparisonSet
import kr.joa.selahrta.data.rta.RtaConditions
import kr.joa.selahrta.data.rta.RtaMeasurement
import kr.joa.selahrta.dsp.ThirdOctave
import org.junit.Rule
import org.junit.Test

/**
 * **차이가 진짜 시트에 적히는가**(지시서 §7 후속).
 *
 * 셈하는 쪽은 `RtaDifferenceTest` 가 본다. 이 시험은 **그 셈이 화면에
 * 닿는지**를 본다 — 앞선 검토에서 여섯 번 걸린 자리가 「부품은 맞는데
 * 아무도 안 부른다」였다.
 *
 * 진짜 [SavedRtaSheet] 를 연다. 값과 콜백만 받으므로 마이크도 저장소도
 * 열지 않는다.
 */
class SavedRtaSheetDifferenceTest {
    @get:Rule
    val compose = createComposeRule()

    private val n = ThirdOctave.BAND_COUNT

    private fun cond(fft: Int? = 4096) = RtaConditions(
        inputKey = "builtin:0",
        calibrationState = "Calibrated",
        calibrationSource = "Reference",
        curveName = "",
        fftSize = fft,
        sampleRate = 48_000,
        // 독립 검토 RMS-03 으로 늘어난 칸들. **하나라도 없으면 「미확인」**
        // 이라 자동 차이에서 빠진다 — 여기서는 다 적힌 기록을 흉내 낸다.
        analysisWeighting = "Z",
        offsetDb = 118.0,
        curveHash = "",
        inputSource = "Unprocessed",
        inputChannel = 0,
        signalSpec = "fixed",
    )

    private fun m(id: String, name: String, channel: String, bands: DoubleArray, c: RtaConditions) =
        RtaMeasurement(
            id = id,
            setId = "s",
            nameKo = name,
            method = "rta",
            bandsSpl = bands,
            signal = "Pink",
            channel = channel,
            outputDbfs = -20.0,
            averagedFrames = 125,
            conditions = c,
            measuredAtEpochMs = 1_700_000_000_000L,
        )

    private fun sheet(items: List<RtaMeasurement>, shown: Set<String>) {
        compose.setContent {
            MaterialTheme {
                SavedRtaSheet(
                    sets = listOf(RtaComparisonSet("s", "본당 중앙", 1_000L)),
                    items = items,
                    shownIds = shown,
                    liveVisible = true,
                    currentConditions = cond(),
                    onShown = { _, _ -> },
                    onLiveVisible = {},
                    onRenameSet = { _, _ -> },
                    onDeleteSet = {},
                    onDelete = {},
                    onClose = {},
                )
            }
        }
    }

    private fun pair(c: RtaConditions = cond()): List<RtaMeasurement> {
        val left = DoubleArray(n) { 60.0 }
        left[12] = 66.3
        return listOf(
            m("a", "본당 중앙 · L", "Left", left, c),
            m("b", "본당 중앙 · R", "Right", DoubleArray(n) { 60.0 }, c),
        )
    }

    /** **두 곡선을 켜면 차이가 적힌다.** 이것이 이 장치의 전부다. */
    @Test
    fun 두_곡선을_켜면_차이가_적힌다() {
        sheet(pair(), setOf("a", "b"))
        compose.onNodeWithText("두 곡선의 차이").assertIsDisplayed()
        // **요약에만 있는 말로 본다.** 이름은 목록 줄에도 나오므로
        // 이름으로 찾으면 둘이 잡혀 단언이 헐거워진다(실기기에서 확인).
        compose.onNodeWithText("큽니다", substring = true).assertIsDisplayed()
        compose.onNodeWithText("6.3 dB", substring = true).assertIsDisplayed()
    }

    /** 하나만 켜면 견줄 것이 없다. 적지 않는다. */
    @Test
    fun 하나만_켜면_차이를_안_적는다() {
        sheet(pair(), setOf("a"))
        compose.onNodeWithText("두 곡선의 차이").assertDoesNotExist()
    }

    @Test
    fun 아무것도_안_켜면_차이를_안_적는다() {
        sheet(pair(), emptySet())
        compose.onNodeWithText("두 곡선의 차이").assertDoesNotExist()
    }

    /**
     * **셈하지 않을 때는 까닭을 적는다.**
     *
     * 아무 말도 없으면 「차이가 없다」로 읽힌다 — 실제로는 셈하지 않은
     * 것인데 좌우가 같다고 믿게 된다.
     */
    @Test
    fun 조건을_모르면_까닭을_적는다() {
        sheet(pair(cond(fft = null)), setOf("a", "b"))
        compose.onNodeWithText("두 곡선의 차이").assertIsDisplayed()
        compose.onNodeWithText("셈하지 않았습니다", substring = true).assertIsDisplayed()
    }
}
