package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10

/** 명세 15장 「1/3 octave band mapping」. */
class BandAnalyzerTest {

    private val fs = 48_000
    private val n = 4096

    private fun bandsOf(freq: Double, amp: Double): DoubleArray {
        val ps = PowerSpectrum(n)
        val ba = BandAnalyzer(n, fs)
        val x = SignalGenerator.sine(freq, fs, n, amp)
        val power = DoubleArray(ps.binCount)
        ps.compute(x, 0, power)
        val bands = DoubleArray(ThirdOctave.BAND_COUNT)
        ba.toBandPower(power, bands)
        return bands
    }

    @Test
    fun `순음은 그 주파수의 밴드에 들어간다`() {
        for ((hz, expectedBand) in listOf(
            125.0 to 8, 1000.0 to 17, 2500.0 to 21, 4000.0 to 23,
        )) {
            val bands = bandsOf(hz, 0.5)
            val loudest = bands.indices.maxByOrNull { bands[it] }!!
            assertEquals("${hz}Hz", expectedBand, loudest)
            assertEquals(
                "${hz}Hz 가 ${ThirdOctave.label(expectedBand)} 밴드여야 한다",
                hz, ThirdOctave.CENTERS_HZ[expectedBand], hz * 0.03,
            )
        }
    }

    @Test
    fun `밴드 전력을 모두 더하면 원신호의 에너지가 된다`() {
        // 에너지가 새거나 불어나면 안 된다. 20Hz~20kHz 안의 음으로 확인한다.
        for (hz in listOf(125.0, 1000.0, 5000.0)) {
            val total = bandsOf(hz, 0.5).sum()
            assertEquals("${hz}Hz", 0.125, total, 0.125 * 0.05)  // 0.5²/2
        }
    }

    @Test
    fun `dB 를 평균하지 않는다 — 한 칸이 튀어도 밴드가 그만큼 올라간다`() {
        val ba = BandAnalyzer(n, fs)
        val binCount = n / 2 + 1
        val power = DoubleArray(binCount)

        // 1kHz 밴드 안의 칸들에 고르게 약한 전력을 깔고,
        // 그 중 한 칸에만 아주 큰 전력을 넣는다(하울링이 이렇게 생긴다).
        val lo = (ThirdOctave.lowerEdge(17) / (fs.toDouble() / n)).toInt() + 1
        val hi = (ThirdOctave.upperEdge(17) / (fs.toDouble() / n)).toInt() - 1
        for (i in lo..hi) power[i] = 1e-8
        power[(lo + hi) / 2] = 1.0

        val bands = DoubleArray(ThirdOctave.BAND_COUNT)
        ba.toBandPower(power, bands)

        // 전력 합이므로 밴드는 그 큰 칸을 거의 그대로 담아야 한다.
        assertEquals("튀는 칸이 밴드를 지배해야 한다", 1.0, bands[17], 0.01)

        // dB 평균이었다면 훨씬 낮았을 것이다 — 그 값을 계산해 견준다.
        val dbAverage = (lo..hi).map { 10 * log10(power[it]) }.average()
        val energyDb = 10 * log10(bands[17])
        assertTrue(
            "전력합(${"%.1f".format(energyDb)}dB)이 dB평균(${"%.1f".format(dbAverage)}dB)보다 훨씬 높아야 한다",
            energyDb - dbAverage > 30.0,
        )
    }

    @Test
    fun `경계에 걸친 칸은 나눠 넣는다`() {
        val ba = BandAnalyzer(n, fs)
        val binWidth = fs.toDouble() / n
        val binCount = n / 2 + 1

        // 두 밴드 경계에 정확히 걸치는 칸 하나에만 전력을 넣는다.
        val edge = ThirdOctave.upperEdge(17)  // 1kHz 밴드의 위끝
        val bin = Math.round(edge / binWidth).toInt()
        val power = DoubleArray(binCount)
        power[bin] = 1.0

        val bands = DoubleArray(ThirdOctave.BAND_COUNT)
        ba.toBandPower(power, bands)

        // 두 밴드가 나눠 가져야 하고, 합은 1 이어야 한다(에너지 보존).
        assertTrue("아래 밴드도 받아야 한다", bands[17] > 0.0)
        assertTrue("위 밴드도 받아야 한다", bands[18] > 0.0)
        assertEquals("합이 보존돼야 한다", 1.0, bands[17] + bands[18], 0.02)
    }

    @Test
    fun `저역 밴드는 새는 양으로 분해 여부를 판정한다`() {
        val ba = BandAnalyzer(n, fs)
        assertFalse("20Hz 밴드는 분해 못 한다", ba.bandResolved[0])
        assertFalse("31.5Hz 밴드도 아직 못 한다", ba.bandResolved[2])
        assertTrue("1kHz 는 당연히 된다", ba.bandResolved[17])
        assertTrue(
            "분해 가능한 첫 밴드가 있어야 한다",
            ba.lowestResolvedBand in 1 until ThirdOctave.BAND_COUNT,
        )
    }

    /**
     * 63·80Hz 는 「충분히 분해됨」이 아니다(독립 검증 R08).
     *
     * 예전 기준은 「밴드가 칸 폭보다 넓은가」였다. 4096점·48kHz 에서
     * 63Hz 밴드의 폭(14.6Hz)은 칸 폭(11.7Hz)보다 넓어 통과했지만, 실제로는
     * 중심 부근 순음부터 2dB 넘게 빠진다. 폭은 창의 주파수 응답을 말해
     * 주지 않는다.
     */
    @Test
    fun `63과 80Hz 는 새는 양이 커서 분해됐다고 하지 않는다`() {
        val ba = BandAnalyzer(4096, 48_000)
        val width63 = ThirdOctave.upperEdge(5) - ThirdOctave.lowerEdge(5)
        assertTrue(
            "63Hz 밴드는 칸 폭보다 넓다 — 옛 기준은 여기서 통과했다",
            width63 > 48_000.0 / 4096,
        )

        assertTrue(
            "63Hz 는 2dB 넘게 빠진다 (${"%.2f".format(ba.bandLossDb[5])}dB)",
            ba.bandLossDb[5] > 2.0,
        )
        assertTrue(
            "80Hz 도 1dB 넘게 빠진다 (${"%.2f".format(ba.bandLossDb[6])}dB)",
            ba.bandLossDb[6] > 1.0,
        )
        assertFalse("63Hz 는 분해됐다고 하지 않는다", ba.bandResolved[5])
        assertFalse("80Hz 도 마찬가지다", ba.bandResolved[6])

        // 중고역은 새는 양이 무시할 수준이다.
        assertTrue(
            "1kHz 는 거의 안 샌다 (${"%.3f".format(ba.bandLossDb[17])}dB)",
            ba.bandLossDb[17] < 0.05,
        )
    }

    /** FFT 를 키우면 새는 양이 줄어든다 — 창이 길어지면 주엽이 좁아진다. */
    @Test
    fun `FFT 를 키우면 저역이 덜 샌다`() {
        val small = BandAnalyzer(4096, fs)
        val big = BandAnalyzer(16384, fs)
        for (b in 3..8) {
            assertTrue(
                "밴드 $b: ${"%.2f".format(small.bandLossDb[b])} → ${"%.2f".format(big.bandLossDb[b])}dB",
                big.bandLossDb[b] < small.bandLossDb[b],
            )
        }
    }

    @Test
    fun `FFT 를 키우면 더 낮은 밴드까지 분해된다`() {
        val small = BandAnalyzer(2048, fs)
        val big = BandAnalyzer(16384, fs)
        assertTrue(
            "긴 FFT 가 더 낮은 밴드까지 본다 (${small.lowestResolvedBand} → ${big.lowestResolvedBand})",
            big.lowestResolvedBand < small.lowestResolvedBand,
        )
    }

    @Test
    fun `나이퀴스트를 넘는 밴드는 분해되지 않는다`() {
        val ba = BandAnalyzer(n, 48_000)
        // 20kHz 밴드의 위끝은 22.4kHz 로 나이퀴스트(24kHz) 안이라 가능하다.
        assertTrue(ba.bandResolved[30])
        // 샘플레이트가 낮으면 고역이 잘린다.
        val low = BandAnalyzer(n, 16_000)
        assertFalse("16kHz 샘플레이트에서 20kHz 밴드는 못 본다", low.bandResolved[30])
    }

    @Test
    fun `길이가 맞지 않으면 거부한다`() {
        val ba = BandAnalyzer(n, fs)
        assertThrows(IllegalArgumentException::class.java) {
            ba.toBandPower(DoubleArray(10), DoubleArray(31))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ba.toBandPower(DoubleArray(n / 2 + 1), DoubleArray(10))
        }
    }

    @Test
    fun `전력이 0 인 밴드는 바닥값이 된다`() {
        val ba = BandAnalyzer(n, fs)
        val db = DoubleArray(31)
        ba.toBandDbfs(DoubleArray(31), db)
        for (v in db) assertEquals(SILENCE_DBFS, v, 0.0)
    }

    // ---- Peak Hold ----

    @Test
    fun `Peak Hold 는 올라갈 때 곧바로 따라간다`() {
        val ph = PeakHold(3, fallPerFrameDb = 0.4)
        val held = ph.update(doubleArrayOf(-30.0, -50.0, -70.0))
        assertEquals(-30.0, held[0], 0.0)
    }

    @Test
    fun `Peak Hold 는 내려올 때 천천히 내린다`() {
        val ph = PeakHold(1, fallPerFrameDb = 0.4)
        ph.update(doubleArrayOf(-20.0))
        val after = ph.update(doubleArrayOf(-60.0))
        assertEquals("한 프레임에 0.4dB 만 내려간다", -20.4, after[0], 1e-9)
    }

    @Test
    fun `Peak Hold 는 실제 값 아래로 내려가지 않는다`() {
        val ph = PeakHold(1, fallPerFrameDb = 10.0)
        ph.update(doubleArrayOf(-20.0))
        val after = ph.update(doubleArrayOf(-25.0))
        assertEquals("실제 값에서 멈춘다", -25.0, after[0], 1e-9)
    }

    // ---- Smoothing ----

    @Test
    fun `평활은 에너지 영역에서 한다`() {
        val s = BandSmoothing(1, factor = 0.5)
        s.update(doubleArrayOf(1.0))            // 첫 프레임은 그대로
        val after = s.update(doubleArrayOf(0.0))
        assertEquals("전력의 절반", 0.5, after[0], 1e-12)
        // dB 로 평활했다면 0dB 과 -무한의 중간이라 -무한이 되었을 것이다.
        assertTrue(after[0].isFinite())
    }

    @Test
    fun `평활 계수 0 이면 그대로 따라간다`() {
        val s = BandSmoothing(1, factor = 0.0)
        s.update(doubleArrayOf(1.0))
        assertEquals(0.25, s.update(doubleArrayOf(0.25))[0], 1e-12)
    }

    @Test
    fun `잘못된 평활 계수를 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) { BandSmoothing(1, factor = 1.0) }
        assertThrows(IllegalArgumentException::class.java) { BandSmoothing(1, factor = -0.1) }
    }

    @Test
    fun `평활해도 총 에너지가 크게 달라지지 않는다`() {
        // 같은 값이 계속 들어오면 평활 결과도 그 값이어야 한다.
        val s = BandSmoothing(2, factor = 0.8)
        repeat(100) { s.update(doubleArrayOf(0.3, 0.7)) }
        val r = s.update(doubleArrayOf(0.3, 0.7))
        assertTrue(abs(r[0] - 0.3) < 1e-6)
        assertTrue(abs(r[1] - 0.7) < 1e-6)
    }
}
