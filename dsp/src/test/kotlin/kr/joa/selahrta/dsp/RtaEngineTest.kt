package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtaEngineTest {

    private val fs = 48_000

    private fun feed(e: RtaEngine, freq: Double, amp: Double, ms: Int) {
        val n = fs * ms / 1000
        val d = SignalGenerator.sine(freq, fs, n, amp)
        e.process(FloatArray(n) { d[it].toFloat() }, n)
    }

    @Test
    fun `FFT 한 번 채워질 때까지는 결과가 없다`() {
        val e = RtaEngine(fs)
        assertNull(e.frame())
        // 4096 프레임 = 85ms. 그 전에는 값이 없는 것이 맞다.
        feed(e, 1000.0, 0.5, 50)
        assertNull("아직 안 찼다", e.frame())
        feed(e, 1000.0, 0.5, 50)
        assertNotNull("이제 찼다", e.frame())
    }

    @Test
    fun `순음이 제 밴드에서 가장 크다`() {
        for ((hz, band) in listOf(125.0 to 8, 1000.0 to 17, 4000.0 to 23)) {
            val e = RtaEngine(fs)
            feed(e, hz, 0.5, 500)
            val f = e.frame()!!
            val loudest = f.bandsDbfs.indices.maxByOrNull { f.bandsDbfs[it] }!!
            assertEquals("${hz}Hz → ${ThirdOctave.label(band)}", band, loudest)
        }
    }

    @Test
    fun `밴드 레벨이 실제 신호 레벨과 맞는다`() {
        // 1kHz 풀스케일 사인파의 전체 에너지는 -3.01dBFS. 거의 전부가
        // 1k 밴드에 들어가므로 그 밴드가 그 값에 가까워야 한다.
        val e = RtaEngine(fs, smoothingFactor = 0.0)
        feed(e, 1000.0, 1.0, 1000)
        assertEquals(-3.0103, e.frame()!!.bandsDbfs[17], 0.3)
    }

    @Test
    fun `A 가중을 걸지 않는다 — 저역이 깎이면 안 된다`() {
        // RTA 는 주파수 균형을 보는 도구다. 가중이 걸려 있으면 50Hz 가
        // 30dB 깎여 「저역이 부족하다」고 잘못 읽게 된다.
        val e = RtaEngine(fs, smoothingFactor = 0.0)
        feed(e, 50.0, 1.0, 1000)
        val f = e.frame()!!
        val loudest = f.bandsDbfs.indices.maxByOrNull { f.bandsDbfs[it] }!!
        assertEquals("50Hz 밴드", 4, loudest)
        // 가중 없이라면 -3dB 근처, A 가중이 걸렸다면 -33dB 근처다.
        assertTrue(
            "가중이 걸리면 안 된다 (지금 ${"%.1f".format(f.bandsDbfs[4])}dB)",
            f.bandsDbfs[4] > -10.0,
        )
    }

    @Test
    fun `Peak Hold 가 지나간 봉우리를 붙든다`() {
        val e = RtaEngine(fs, smoothingFactor = 0.0)
        feed(e, 1000.0, 1.0, 200)     // 큰 소리
        val duringDb = e.frame()!!.bandsDbfs[17]
        feed(e, 1000.0, 0.001, 300)   // 조용해짐
        val f = e.frame()!!
        assertTrue("지금 값은 내려가야 한다", f.bandsDbfs[17] < duringDb - 20.0)
        assertTrue("붙든 값은 남아야 한다", f.holdDbfs[17] > f.bandsDbfs[17] + 10.0)
    }

    @Test
    fun `분해되지 않는 저역 밴드를 알려준다`() {
        val e = RtaEngine(fs, fftSize = 4096)
        feed(e, 1000.0, 0.5, 200)
        val f = e.frame()!!
        assertTrue("20Hz 밴드는 분해 못 한다", !f.resolved[0])
        assertTrue("1kHz 는 된다", f.resolved[17])
        assertTrue(e.lowestResolvedBand > 0)
    }

    @Test
    fun `겹쳐서 분석하므로 짧은 소리도 잡힌다`() {
        // 4096 프레임(85ms)보다 짧게 스치는 소리. 겹치지 않으면 창의 가장자리에
        // 걸려 크게 깎이는데, 겹치면 어느 한 프레임에서는 가운데에 온다.
        val e = RtaEngine(fs, smoothingFactor = 0.0, overlap = 0.5)
        feed(e, 1000.0, 0.0, 200)     // 무음으로 채워 둔다
        feed(e, 3150.0, 1.0, 30)      // 30ms 짧은 소리
        feed(e, 1000.0, 0.0, 60)      // 다시 무음
        val f = e.frame()!!
        assertTrue(
            "짧은 소리가 Peak Hold 에 남아야 한다 (${"%.1f".format(f.holdDbfs[22])}dB)",
            f.holdDbfs[22] > -40.0,
        )
    }

    @Test
    fun `원형 버퍼를 시간 순서대로 편다`() {
        // 순서가 틀리면 파형이 가운데서 끊겨 없던 고역이 잔뜩 생긴다.
        // 순음을 오래 넣었을 때 고역 밴드가 조용한지로 확인한다.
        val e = RtaEngine(fs, smoothingFactor = 0.0)
        feed(e, 1000.0, 1.0, 1000)
        val f = e.frame()!!
        val high = (24..30).maxOf { f.bandsDbfs[it] }
        assertTrue(
            "고역이 조용해야 한다 (지금 ${"%.1f".format(high)}dB)",
            high < f.bandsDbfs[17] - 40.0,
        )
    }

    @Test
    fun `reset 하면 처음으로 돌아간다`() {
        val e = RtaEngine(fs)
        feed(e, 1000.0, 1.0, 500)
        e.reset()
        assertNull(e.frame())
    }

    @Test
    fun `잘못된 설정을 거부한다`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RtaEngine(0)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RtaEngine(fs, overlap = 0.95)
        }
    }

    @Test
    fun `긴 FFT 는 저역을 더 잘 본다`() {
        val short = RtaEngine(fs, fftSize = 2048)
        val long = RtaEngine(fs, fftSize = 16384)
        assertTrue(long.lowestResolvedBand < short.lowestResolvedBand)
    }
}
