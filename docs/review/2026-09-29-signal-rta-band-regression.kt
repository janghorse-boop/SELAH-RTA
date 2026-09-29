package kr.joa.selahrta.dsp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Measure the actual filter by injecting a sine; do not assert only its label. */
class BandCenterIndependentProbeTest {
    private fun gain(center: Double, input: Double): Double {
        val f = BandNoiseFilter(center, 48000)
        var power = 0.0
        repeat(48000) { i ->
            val v = f.process(sin(2 * PI * input * i / 48000))
            if (i >= 24000) power += v * v
        }
        return 10 * log10(power / 24000 * 2)
    }
    private fun checkCenter(center: Double) {
        val warped = 48000 / PI * atan(PI * min(center, 19200.0) / 48000)
        val g = gain(center, center)
        val elsewhere = gain(center, warped)
        println("BAND center=$center gainDb=$g warped=$warped warpedGainDb=$elsewhere")
        assertTrue("The labeled center $center should have >= -1 dB gain; was $g", g >= -1.0)
    }
    @Test fun center3150() = checkCenter(3150.0)
    @Test fun center8000() = checkCenter(8000.0)
    @Test fun center16000() = checkCenter(16000.0)
    @Test fun center20000() = checkCenter(20000.0)
    @Test fun all31BandsHaveTheirLabeledPassband() {
        val ratio = 2.0.pow(1.0 / 6)
        for (center in ThirdOctave.CENTERS_HZ) {
            assertTrue("center=$center", gain(center, center) >= -1.0)
            assertEquals("lower edge of $center", -3.01029995664, gain(center, center / ratio), 0.10)
            assertEquals("upper edge of $center", -3.01029995664, gain(center, center * ratio), 0.10)
        }
    }
}
