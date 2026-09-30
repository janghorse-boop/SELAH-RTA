package kr.joa.selahrta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

/**
 * **머리줄의 왼쪽이 고르개의 자리를 못 뺏는다**(2026-10-01).
 *
 * ## 무슨 일이 있었나
 *
 * 세 차트의 머리줄은 `SpaceBetween` 인 `Row` 에 상자 둘이다 — 왼쪽이
 * `controls`, 오른쪽이 `modes`. 그런데 RTA 의 저장 단추 줄이
 * `fillMaxWidth()` 라 **왼쪽이 폭을 다 먹었고**, 고르개가 통째로 화면
 * 밖으로 밀렸다. 담당자에게는 이렇게 보였다:
 *
 * > 분석탭에 RTA만 있고 Spectrum과 Spectrogram이 사라졌습니다
 *
 * **칩이 지워진 것이 아니라 갈 길이 닫힌 것**이다 — 다른 화면으로
 * 돌아올 문이 그 칩뿐이라, 한 번 밀리면 되돌아올 수가 없다.
 *
 * ## 왜 화면이 아니라 **부품**을 거는가
 *
 * 걸린 것은 RTA 하나였지만 덫은 **셋 다**에 있었다. 나머지 둘은 아직
 * 폭을 채우는 줄이 없어서 멀쩡했을 뿐이다. 화면으로만 시험하면
 * **그 둘의 고침은 아무것도 증명하지 못한다**(실제로 그랬다 — 고침을
 * 되돌려도 화면 시험은 통과했다).
 *
 * 그래서 여기서는 **일부러 폭을 다 먹는 왼쪽**을 넣고 고르개가 남는지
 * 묻는다. 고침을 되돌리면 이 시험이 깨진다.
 */
class ChartHeaderSlotTest {

    @get:Rule
    val compose = createComposeRule()

    /** RTA 의 저장 단추 줄과 같은 모양 — **폭을 다 먹고 오른쪽에 붙는다.** */
    private val greedyControls: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text("저장 단추 줄")
        }
    }

    private val modes: @Composable () -> Unit = { Text("고르개") }

    @Test
    fun BandMeter_는_고르개를_지킨다() {
        compose.setContent {
            Box(Modifier.size(640.dp, 360.dp)) {
                BandMeter(
                    rta = null,
                    floorDb = 30.0,
                    ceilDb = 120.0,
                    modes = modes,
                    controls = greedyControls,
                )
            }
        }
        compose.onNodeWithText("고르개").assertIsDisplayed()
        compose.onNodeWithText("저장 단추 줄").assertIsDisplayed()
    }

    @Test
    fun SpectrumChart_는_고르개를_지킨다() {
        compose.setContent {
            Box(Modifier.size(640.dp, 360.dp)) {
                SpectrumChart(
                    spectrum = null,
                    floorDb = 30.0,
                    ceilDb = 120.0,
                    modes = modes,
                    controls = greedyControls,
                )
            }
        }
        compose.onNodeWithText("고르개").assertIsDisplayed()
        compose.onNodeWithText("저장 단추 줄").assertIsDisplayed()
    }

    @Test
    fun SpectrogramChart_는_고르개를_지킨다() {
        compose.setContent {
            Box(Modifier.size(640.dp, 360.dp)) {
                SpectrogramChart(
                    state = SpectrogramState(columns = 31, capacity = 64),
                    modes = modes,
                    controls = greedyControls,
                )
            }
        }
        compose.onNodeWithText("고르개").assertIsDisplayed()
        compose.onNodeWithText("저장 단추 줄").assertIsDisplayed()
    }
}
