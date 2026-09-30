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
import org.junit.Assert.assertNotEquals
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
     * 그려진 막대들을 **가로줄을 훑어** 찾는다.
     *
     * 처음에는 `폭 / 31` 로 자리를 어림했다가 **막대 사이 빈틈을 집어**
     * 시험이 엉뚱한 이유로 통과했다(밴드 2 는 빈틈, 20 은 막대). 차트에는
     * 눈금·여백이 있어 그 나눗셈이 맞지 않는다.
     *
     * 바탕색이 아닌 픽셀이 이어진 덩어리를 **막대 하나**로 본다.
     */
    private fun barColors(): List<androidx.compose.ui.graphics.Color> {
        val img = compose.onRoot().captureToImage().toPixelMap()
        val y = img.height * 3 / 4
        val background = img[0, y]
        val out = ArrayList<androidx.compose.ui.graphics.Color>()
        var x = 0
        while (x < img.width) {
            if (img[x, y] != background) {
                val start = x
                while (x < img.width && img[x, y] != background) x++
                // 덩어리 한가운데를 집는다 — 가장자리는 안티에일리어싱이다.
                out += img[(start + x) / 2, y]
            } else {
                x++
            }
        }
        return out
    }

    /**
     * **못 가르는 밴드와 가르는 밴드는 다르게 보여야 한다.**
     *
     * 같은 높이·같은 값인데 **색까지 같으면** 「이 값은 못 믿는다」가
     * 화면에 전혀 안 나타난다. 글로 적어 둔 안내는 차트를 스크롤해
     * 지나가면 안 보인다.
     */
    @Test
    fun 못_가르는_밴드는_다르게_그린다() {
        render(unresolvedBelow = 8)
        val bars = barColors()

        assertEquals("막대가 31개로 안 잡혔다", ThirdOctave.BAND_COUNT, bars.size)
        assertNotEquals(
            "못 가르는 밴드(2)와 가르는 밴드(20)가 같은 색이다 — " +
                "화면은 「흐리게 그립니다」라고 적어 두었다",
            bars[20],
            bars[2],
        )
    }

    /** **가르는 밴드끼리는 같다.** 위 시험이 아무 차이나 잡지 않게 못박는다. */
    @Test
    fun 가르는_밴드끼리는_같은_색이다() {
        render(unresolvedBelow = 8)
        val bars = barColors()
        assertEquals(bars[12], bars[20])
        assertEquals(bars[12], bars[30])
    }

    /** **못 가르는 밴드끼리도 같다.** 흐림은 한 가지 값이어야 한다. */
    @Test
    fun 못_가르는_밴드끼리는_같은_색이다() {
        render(unresolvedBelow = 8)
        val bars = barColors()
        assertEquals(bars[2], bars[6])
    }
}
