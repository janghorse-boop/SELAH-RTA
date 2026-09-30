package kr.joa.selahrta.ui.screens

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import kr.joa.selahrta.recording.*
import kr.joa.selahrta.ui.components.SplTimelineGraph
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class Round3GraphIndependentTest {
    @get:Rule val rule = createComposeRule()
    @Test fun sameSessionNewCalibrationMustRedraw() {
        val before = SessionMeta(id="graph", startedAtEpochMs=0, endedAtEpochMs=1000,
            durationMs=1000, deviceKey="k", deviceLabel="k", micKind=MicKind.BuiltIn,
            sampleRate=48000, encoding="Float", channelCount=1, channelIndex=0,
            calibrationOffsetDb=100.0, referenceOnly=false, curveApplied=false,
            weighting=Weighting.A, timeWeight=TimeWeight.Fast, leqWindowMs=10000,
            leqDb=70.0, minDb=70.0, maxDb=71.0, peakDb=72.0)
        val rows = listOf(TimelineRow(0,-30.0,0,-29.0,0,-28.0,0,false,false,FloatArray(31){-30f}))
        val state = mutableStateOf(before)
        rule.setContent { SplTimelineGraph(state.value, rows, 0, {}, Modifier.width(360.dp)) }
        rule.waitForIdle()
        val a = rule.onRoot().captureToImage().toPixelMap()
        rule.runOnIdle { state.value = before.copy(calibrationOffsetDb=103.0) }
        rule.waitForIdle()
        val b = rule.onRoot().captureToImage().toPixelMap()
        var changed = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (a[x,y] != b[x,y]) changed++
        val first = timelineSeries(before,rows,120)
        val second = timelineSeries(state.value,rows,120)
        println("R3_GRAPH changedPixels=$changed oldTop=${first.columns[0].topDb} newTop=${second.columns[0].topDb}")
        assertTrue("A 3 dB metadata change must invalidate the graph cache", changed>0)
    }
}
