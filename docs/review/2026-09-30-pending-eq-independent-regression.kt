package kr.joa.selahrta.ui.instrument

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kr.joa.selahrta.ui.CaptureUiState
import kr.joa.selahrta.ui.RtaView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PendingEqUiTest {
    @get:Rule val rule = createComposeRule()
    @Test fun sameLocalRegionIdMustNotReuseAnotherRange() {
        var density = 1f
        rule.setContent {
            density = LocalDensity.current.density
            Box(Modifier.size(380.dp, 760.dp)) {
                InstrumentGuideScreen(
                    capture = CaptureUiState(rta = RtaView(DoubleArray(31), DoubleArray(31), BooleanArray(31) { true }, DoubleArray(31))),
                    onStartMeasure = {},
                )
            }
        }
        rule.onNodeWithText("신디사이저").performScrollTo().performClick()
        rule.waitForIdle()
        fun chartRow(): IntArray {
            val pixels = rule.onRoot().captureToImage().toPixelMap()
            val y = (80 * density).toInt()
            return IntArray(pixels.width) { x -> pixels[x, y].toArgb() }
        }
        val piano = chartRow()
        rule.onNodeWithText("리드 / 브라스").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("150~600 Hz").onFirst().assertExists()
        val lead = chartRow()
        val changed = piano.indices.count { piano[it] != lead[it] }
        println("PEND_EQ chartPixelsChanged=$changed piano=60..200 lead=150..600 bothId=body")
        assertTrue("The range changed but the rendered chart highlight stayed put", changed > 0)
    }
}
