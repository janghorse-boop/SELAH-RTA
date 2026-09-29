package kr.joa.selahrta.dsp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.log10

/** Desired behavior: these assertions fail on the reviewed production revision. */
class IndependentExactWindowTest {
    private fun boundary(fs: Int) {
        // Sweep every 10 ms phase of a 100 ms bucket. Use Z to exclude IIR tails.
        for (phaseMs in 10..90 step 10) {
            val loud = fs * (5000 + phaseMs) / 1000
            val count = loud + fs * 3
            val pcm = FloatArray(count) { i ->
                ((if (i < loud) .9 else .009) * sin(2 * PI * 1000 * i / fs)).toFloat()
            }
            val w = CleanWindow(fs)
            var at = 0
            val sizes = intArrayOf(128, 960, 2048, 17)
            var part = 0
            while (at < count) {
                val n = minOf(sizes[part++ % sizes.size], count - at)
                val block = pcm.copyOfRange(at, at + n)
                val saved = block.copyOf()
                at += n
                w.observe(block, n, 1_000_000_000L + at * 1_000_000_000L / fs, false)
                assertArrayEquals(saved, block, 0f)
            }
            val expected = 10 * log10(pcm.asSequence().drop(loud).sumOf { it.toDouble() * it } / (fs * 3))
            assertEquals("fs=$fs phaseMs=$phaseMs", expected,
                w.value(1_000_000_000L + count * 1_000_000_000L / fs, Weighting.Z)!!, 1e-7)
        }
    }
    @Test fun `exact boundary at 48000 with mixed blocks`() = boundary(48000)
    @Test fun `exact boundary at 44100 with mixed blocks`() = boundary(44100)

    @Test fun `weighted window agrees with independent last frame sum`() {
        for (fs in listOf(44100, 48000)) {
            val count = fs * 8 + fs * 4 / 100
            val n = fs * 3
            val pcm = FloatArray(count) { i ->
                ((if (i < fs * 5 + fs / 50) .9 else .009) * sin(2 * PI * 1000 * i / fs)).toFloat()
            }
            val w = CleanWindow(fs)
            var at = 0
            while (at < count) {
                val size = minOf(613, count - at)
                val block = pcm.copyOfRange(at, at + size)
                at += size
                w.observe(block, size, 1_000_000_000L + at * 1_000_000_000L / fs, false)
            }
            for (weight in Weighting.entries) {
                val filter = weightingFilter(weight, fs)
                var sum = 0.0
                for (i in pcm.indices) {
                    val value = filter.process(pcm[i].toDouble())
                    if (i >= count - n) sum += value * value
                }
                val expected = 10 * log10(sum / n)
                assertEquals("fs=$fs weighting=$weight", expected,
                    w.value(1_000_000_000L + count * 1_000_000_000L / fs, weight)!!, 1e-7)
            }
        }
    }
}
