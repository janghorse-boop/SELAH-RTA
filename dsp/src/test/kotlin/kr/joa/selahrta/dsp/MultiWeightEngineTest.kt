package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiWeightEngineTest {

    private val fs = 48_000

    private fun tone(f: Double, amp: Double, ms: Int): FloatArray {
        val n = fs * ms / 1000
        val d = SignalGenerator.sine(f, fs, n, amp)
        return FloatArray(n) { d[it].toFloat() }
    }

    private fun mix(vararg parts: Pair<Double, Double>): FloatArray {
        val n = fs
        val out = DoubleArray(n)
        for ((f, amp) in parts) {
            val d = SignalGenerator.sine(f, fs, n, amp)
            for (i in 0 until n) out[i] += d[i]
        }
        return FloatArray(n) { out[it].toFloat() }
    }

    @Test
    fun `세 가중치가 각자 제 값을 낸다`() {
        val e = MultiWeightEngine(fs, TimeWeight.Fast)
        val f = e.process(tone(125.0, 0.5, 3000), fs * 3)
        // 125Hz 에서 A 는 -16.1dB, C 는 -0.2dB, Z 는 0dB.
        assertEquals("A", -16.1, f.a.currentDbfs.value - f.z.currentDbfs.value, 0.3)
        assertEquals("C", -0.2, f.c.currentDbfs.value - f.z.currentDbfs.value, 0.3)
    }

    @Test
    fun `저역만 있으면 C-A 가 크다`() {
        val e = MultiWeightEngine(fs, TimeWeight.Fast)
        val f = e.process(tone(63.0, 0.5, 3000), fs * 3)
        // 63Hz: A=-26.2, C=-0.8 → 차이 25.4dB
        assertEquals(25.4, f.cMinusA, 0.5)
        assertEquals(LowEnergyHint.VeryBassHeavy, LowEnergyHint.of(f.cMinusA))
    }

    @Test
    fun `말소리 대역만 있으면 C-A 가 작다`() {
        val e = MultiWeightEngine(fs, TimeWeight.Fast)
        // 1kHz 에서는 A 도 C 도 0dB 이라 차이가 없다.
        val f = e.process(tone(1000.0, 0.5, 3000), fs * 3)
        assertEquals(0.0, f.cMinusA, 0.2)
        assertEquals(LowEnergyHint.Speechlike, LowEnergyHint.of(f.cMinusA))
    }

    @Test
    fun `저역과 중역을 섞으면 그 사이 값이 나온다`() {
        val e = MultiWeightEngine(fs, TimeWeight.Fast)
        // 같은 크기의 63Hz 와 1kHz. A 가중은 63Hz 를 26dB 깎으므로
        // A 에서는 1kHz 가 지배하고 C 에서는 둘이 비슷하게 남는다.
        val e2 = MultiWeightEngine(fs, TimeWeight.Fast)
        repeat(3) { e2.process(mix(63.0 to 0.4, 1000.0 to 0.4), fs) }
        val f = e2.process(mix(63.0 to 0.4, 1000.0 to 0.4), fs)
        assertTrue("저역이 섞였으니 0 보다 커야 한다 (${"%.1f".format(f.cMinusA)}dB)", f.cMinusA > 1.0)
        assertTrue("저역만 있을 때(25dB)보다는 작아야 한다", f.cMinusA < 15.0)
    }

    @Test
    fun `Leq 로 본 차이도 낸다`() {
        val e = MultiWeightEngine(fs, TimeWeight.Fast)
        val f = e.process(tone(63.0, 0.5, 2000), fs * 2)
        assertNotNull(f.cMinusALeq)
        assertEquals(25.4, f.cMinusALeq!!, 0.5)
    }

    @Test
    fun `가중치를 골라 꺼내 쓸 수 있다`() {
        val e = MultiWeightEngine(fs, TimeWeight.Fast)
        val f = e.process(tone(1000.0, 1.0, 1000), fs)
        assertEquals(f.a.currentDbfs.value, f.of(Weighting.A).currentDbfs.value, 0.0)
        assertEquals(f.c.currentDbfs.value, f.of(Weighting.C).currentDbfs.value, 0.0)
        assertEquals(f.z.currentDbfs.value, f.of(Weighting.Z).currentDbfs.value, 0.0)
    }

    @Test
    fun `세 가중치가 같은 Leq 이력을 따로 쌓는다`() {
        // 가중치를 바꿔도 평균이 비워지지 않는 것이 이 엔진의 목적이다.
        val e = MultiWeightEngine(fs, TimeWeight.Fast)
        e.process(tone(1000.0, 1.0, 1000), fs)
        val f = e.process(tone(1000.0, 0.01, 1000), fs)
        // 세 가중치 모두 앞선 큰 소리를 기억해야 한다.
        for (w in Weighting.entries) {
            assertTrue("$w 의 세션 Leq 가 남아야 한다", f.of(w).leqSessionDbfs!!.value > -25.0)
        }
    }

    @Test
    fun `저역 힌트의 경계가 서로 겹치지 않는다`() {
        assertEquals(LowEnergyHint.Speechlike, LowEnergyHint.of(0.0))
        assertEquals(LowEnergyHint.Speechlike, LowEnergyHint.of(3.9))
        assertEquals(LowEnergyHint.Balanced, LowEnergyHint.of(4.0))
        assertEquals(LowEnergyHint.Balanced, LowEnergyHint.of(9.9))
        assertEquals(LowEnergyHint.BassHeavy, LowEnergyHint.of(10.0))
        assertEquals(LowEnergyHint.BassHeavy, LowEnergyHint.of(15.9))
        assertEquals(LowEnergyHint.VeryBassHeavy, LowEnergyHint.of(16.0))
    }

    @Test
    fun `힌트는 판정이 아니다 — 설명에 그렇게 적혀 있다`() {
        // 저음이 많은 것 자체는 잘못이 아니다. 찬양은 원래 그렇다.
        assertTrue(LowEnergyHint.BassHeavy.noteKo.contains("흔하지만"))
    }

    @Test
    fun `reset 하면 셋 다 처음으로 돌아간다`() {
        val e = MultiWeightEngine(fs, TimeWeight.Fast)
        e.process(tone(1000.0, 1.0, 500), fs / 2)
        assertTrue(e.hasInput)
        e.reset()
        org.junit.Assert.assertFalse(e.hasInput)
    }
}
