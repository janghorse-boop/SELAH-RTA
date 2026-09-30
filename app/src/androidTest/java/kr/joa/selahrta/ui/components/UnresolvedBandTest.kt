package kr.joa.selahrta.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.ui.RtaView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * **못 가르는 밴드는 흐리게 그린다**(화면이 그렇게 말한다).
 *
 * 분석 화면은 「○○Hz 아래 밴드는 **흐리게 그립니다**」라고 적는다.
 * 그 말이 사실인지 **픽셀로** 본다 — KDoc 에도 「흐리게 그린다」고
 * 적혀 있었지만 **실제로는 안 흐렸다**(2026-09-30에 찾음).
 *
 * ## 왜 픽셀까지 보나
 *
 * 색을 고르는 코드를 읽는 것으로는 **그려진 것**을 알 수 없다. 이
 * 저장소가 「화면만 보고 고쳤다가 세 번 헛돈」 자리이기도 하다.
 */
class UnresolvedBandTest {

    @get:Rule
    val compose = createComposeRule()

    /** 못 가르는 밴드 [unresolvedBelow] 개, 나머지는 같은 높이. */
    private fun view(unresolvedBelow: Int) = RtaView(
        bandsSpl = DoubleArray(ThirdOctave.BAND_COUNT) { 80.0 },
        holdSpl = DoubleArray(ThirdOctave.BAND_COUNT) { 80.0 },
        resolved = BooleanArray(ThirdOctave.BAND_COUNT) { it >= unresolvedBelow },
        lossDb = DoubleArray(ThirdOctave.BAND_COUNT),
    )

    private fun render(unresolvedBelow: Int) {
        compose.setContent {
            BandMeter(
                view(unresolvedBelow),
                floorDb = 40.0,
                ceilDb = 100.0,
                modifier = Modifier.width(620.dp),
                showHold = false,
                chartHeight = 200.dp,
                minSlotWidth = 0.dp,
            )
        }
        compose.waitForIdle()
    }

    /**
     * **못 가르는 밴드와 가르는 밴드는 다르게 보여야 한다.**
     *
     * 같은 높이·같은 값인데 **색까지 같으면** 「이 값은 못 믿는다」가
     * 화면에 전혀 안 나타난다. 글로 적어 둔 안내는 차트를 스크롤해 지나가면
     * 안 보인다.
     */
    /**
     * 한 가로줄에 **몇 가지 색의 막대**가 있는가.
     *
     * ## 왜 자리를 세지 않나
     *
     * 처음에는 `폭 / 31` 로 막대 자리를 어림했다가 **빈틈을 집어**
     * 시험이 엉뚱한 이유로 통과했다. 다음에는 「바탕색이 아닌 덩어리」로
     * 셌는데, 차트 안쪽 바탕이 화면 맨 왼쪽과 달라 **31개가 하나로**
     * 잡혔다.
     *
     * 기하에 기대지 말고 **색의 가짓수**를 묻는다. 막대가 흐린 것과
     * 진한 것 두 가지면 색도 두 가지다.
     */
    private fun barColorCount(): Int {
        val img = compose.onRoot().captureToImage().toPixelMap()
        val y = img.height * 3 / 4
        val tally = HashMap<androidx.compose.ui.graphics.Color, Int>()
        for (x in 0 until img.width) {
            val c = img[x, y]
            tally[c] = (tally[c] ?: 0) + 1
        }
        // 가장 넓은 색이 바탕이다. 나머지 가운데 **눈에 띄게 넓은 것**만
        // 막대로 본다 — 가장자리 안티에일리어싱은 몇 픽셀뿐이다.
        val background = tally.maxByOrNull { it.value }!!.key
        val minRun = img.width / (ThirdOctave.BAND_COUNT * 4)
        return tally.filterKeys { it != background }.count { it.value >= minRun }
    }

    /**
     * **못 가르는 밴드와 가르는 밴드는 다르게 보여야 한다.**
     *
     * 같은 높이·같은 값인데 **색까지 같으면** 「이 값은 못 믿는다」가
     * 화면에 전혀 안 나타난다. 글로 적어 둔 안내는 차트를 스크롤해
     * 지나가면 안 보인다.
     */
    @Test
    fun 못_가르는_밴드는_흐리게_그린다() {
        render(unresolvedBelow = 8)
        assertEquals(
            "막대 색이 한 가지다 — 화면은 「흐리게 그립니다」라고 적어 두었다",
            2,
            barColorCount(),
        )
    }

    /**
     * **다 가르면 한 가지다.** 위 시험이 아무 차이나 잡지 않게 못박는다.
     */
    @Test
    fun 다_가르면_막대_색이_한_가지다() {
        render(unresolvedBelow = 0)
        assertEquals(1, barColorCount())
    }
}
