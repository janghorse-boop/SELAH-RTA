package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10

/** 명세 15장 「FFT 125 Hz/1k/2.5k/4k peak」. */
class FftTest {

    private val fs = 48_000
    private val n = 4096

    private fun spectrumOf(freq: Double, amp: Double): Pair<PowerSpectrum, DoubleArray> {
        val ps = PowerSpectrum(n)
        val x = SignalGenerator.sine(freq, fs, n, amp)
        val out = DoubleArray(ps.binCount)
        ps.compute(x, 0, out)
        return ps to out
    }

    @Test
    fun `명세가 정한 네 주파수에서 봉우리가 제자리에 선다`() {
        for (f in listOf(125.0, 1000.0, 2500.0, 4000.0)) {
            val (ps, out) = spectrumOf(f, 0.5)
            val peakBin = out.indices.maxByOrNull { out[it] }!!
            val peakHz = ps.binFrequency(peakBin, fs)
            // 칸 폭이 11.7Hz 이므로 그 안에 들어야 한다.
            assertEquals("${f}Hz 봉우리 위치", f, peakHz, ps.binWidth(fs))
        }
    }

    @Test
    fun `전력을 모두 더하면 원신호의 평균 제곱이 된다 — 파스발 정리`() {
        // 이것이 어긋나면 1/3 옥타브로 묶을 때 에너지가 새거나 불어난다.
        for (amp in listOf(1.0, 0.5, 0.1)) {
            val (_, out) = spectrumOf(1000.0, amp)
            val total = out.sum()
            val expected = amp * amp / 2.0  // 사인파의 평균 제곱
            assertEquals("진폭 $amp", expected, total, expected * 0.02)
        }
    }

    @Test
    fun `창의 에너지 보정이 실제로 들어 있다`() {
        // 보정을 빼먹으면 Hann 창의 제곱평균(0.375)만큼 낮게 나온다 → -4.26dB.
        val (_, out) = spectrumOf(1000.0, 1.0)
        val total = out.sum()
        val withoutCorrection = total * 0.375
        assertTrue(
            "보정 없이 계산한 값(${"%.4f".format(withoutCorrection)})과 달라야 한다",
            abs(total - 0.5) < abs(withoutCorrection - 0.5),
        )
        assertEquals(0.5, total, 0.01)
    }

    @Test
    fun `진폭 보정과 에너지 보정은 1_76dB 다르다`() {
        // 둘을 섞어 쓰면 그만큼 조용히 틀린다. 값이 실제로 다른지 못박는다.
        val w = HannWindow.create(1024)
        val a = HannWindow.amplitudeCorrection(w)
        val e = HannWindow.energyCorrection(w)
        val diffDb = 20.0 * log10(a / e)
        assertEquals("두 보정의 차이", 1.76, diffDb, 0.02)
    }

    @Test
    fun `레벨이 절반이면 전력은 4분의 1이다`() {
        val (_, full) = spectrumOf(1000.0, 1.0)
        val (_, half) = spectrumOf(1000.0, 0.5)
        assertEquals(4.0, full.sum() / half.sum(), 0.05)
    }

    @Test
    fun `두 음이 섞이면 봉우리도 둘이다`() {
        val ps = PowerSpectrum(n)
        val a = SignalGenerator.sine(500.0, fs, n, 0.5)
        val b = SignalGenerator.sine(3000.0, fs, n, 0.5)
        val mix = DoubleArray(n) { a[it] + b[it] }
        val out = DoubleArray(ps.binCount)
        ps.compute(mix, 0, out)

        val bin500 = (500.0 / ps.binWidth(fs)).toInt()
        val bin3000 = (3000.0 / ps.binWidth(fs)).toInt()

        // **한 칸만 읽어 견주면 안 된다.** 3000Hz 는 칸 256 에 정확히
        // 떨어지지만 500Hz 는 42.67 에 떨어져 에너지가 두 칸에 나뉜다
        // (scalloping). 한 칸씩 읽으면 같은 크기의 음인데도 다르게 보인다.
        // 실제로 앱이 하는 일도 대역의 에너지를 합치는 것이므로 그렇게 잰다.
        fun around(center: Int, halfWidth: Int = 3) =
            (center - halfWidth..center + halfWidth).sumOf { out[it] }

        val p500 = around(bin500)
        val p3000 = around(bin3000)
        val between = around((bin500 + bin3000) / 2)

        assertEquals("두 봉우리 에너지", 1.0, p500 / p3000, 0.05)
        assertTrue(
            "사이는 훨씬 낮아야 한다 (지금 ${"%.1f".format(10 * log10(between / p500))}dB)",
            between < p500 / 1000.0,
        )
    }

    @Test
    fun `무음은 전력이 0 이다`() {
        val ps = PowerSpectrum(n)
        val out = DoubleArray(ps.binCount)
        ps.compute(DoubleArray(n), 0, out)
        assertEquals(0.0, out.sum(), 1e-20)
    }

    @Test
    fun `칸 개수와 폭이 규격대로다`() {
        val ps = PowerSpectrum(4096)
        assertEquals(2049, ps.binCount)
        assertEquals(48_000.0 / 4096, ps.binWidth(48_000), 1e-12)
        assertEquals(0.0, ps.binFrequency(0, 48_000), 0.0)
        assertEquals(24_000.0, ps.binFrequency(2048, 48_000), 1e-9)
    }

    @Test
    fun `2의 거듭제곱이 아닌 길이를 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) { Fft(1000) }
        assertThrows(IllegalArgumentException::class.java) { Fft(0) }
    }

    @Test
    fun `범위를 벗어난 읽기를 거부한다`() {
        val ps = PowerSpectrum(1024)
        val out = DoubleArray(ps.binCount)
        assertThrows(IllegalArgumentException::class.java) {
            ps.compute(DoubleArray(512), 0, out)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ps.compute(DoubleArray(2048), 1500, out)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ps.compute(DoubleArray(2048), 0, DoubleArray(10))
        }
    }

    @Test
    fun `FFT 자체가 알려진 답과 맞는다`() {
        // 직류 1.0 을 넣으면 0번 칸에만 N 이 모인다.
        val f = Fft(8)
        val re = DoubleArray(8) { 1.0 }
        val im = DoubleArray(8)
        f.transform(re, im)
        assertEquals(8.0, re[0], 1e-12)
        assertEquals(0.0, im[0], 1e-12)
        for (i in 1 until 8) {
            assertEquals("칸 $i 실수", 0.0, re[i], 1e-12)
            assertEquals("칸 $i 허수", 0.0, im[i], 1e-12)
        }
    }
}
