package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplEngineTest {

    private val fs = 48_000

    private fun engine(w: Weighting = Weighting.Z, t: TimeWeight = TimeWeight.Fast) =
        SplEngine(fs, w, t)

    private fun tone(f: Double, amp: Double, ms: Int): FloatArray {
        val n = fs * ms / 1000
        val d = SignalGenerator.sine(f, fs, n, amp)
        return FloatArray(n) { d[it].toFloat() }
    }

    @Test
    fun `Z 가중 1kHz 풀스케일은 -3_01 dBFS 다`() {
        val e = engine()
        val x = tone(1000.0, 1.0, 2000)
        val f = e.process(x, x.size)
        assertEquals(-3.0103, f.currentDbfs.value, 0.05)
        assertEquals(-3.0103, f.leqSessionDbfs!!.value, 0.05)
    }

    @Test
    fun `A 가중이 실제로 걸린다 — 125Hz 는 Z 보다 16dB 낮다`() {
        // 규격의 A 가중 125Hz 값은 -16.1dB. 가중이 안 걸렸다면 차이가 0 이다.
        val z = engine(Weighting.Z)
        val a = engine(Weighting.A)
        val x = tone(125.0, 0.5, 3000)
        val zDb = z.process(x, x.size).currentDbfs.value
        val aDb = a.process(x, x.size).currentDbfs.value
        assertEquals("A 가중 125Hz", -16.1, aDb - zDb, 0.3)
    }

    @Test
    fun `A 가중은 2_5kHz 를 오히려 올린다`() {
        val z = engine(Weighting.Z)
        val a = engine(Weighting.A)
        val x = tone(2500.0, 0.5, 3000)
        val diff = a.process(x, x.size).currentDbfs.value - z.process(x, x.size).currentDbfs.value
        assertEquals("A 가중 2.5kHz", 1.3, diff, 0.2)
    }

    @Test
    fun `C 가중은 125Hz 를 거의 깎지 않는다`() {
        val z = engine(Weighting.Z)
        val c = engine(Weighting.C)
        val x = tone(125.0, 0.5, 3000)
        val diff = c.process(x, x.size).currentDbfs.value - z.process(x, x.size).currentDbfs.value
        assertEquals("C 가중 125Hz", -0.2, diff, 0.2)
    }

    @Test
    fun `가중을 먼저 걸고 에너지를 센다 — 순서가 바뀌면 저역에서 드러난다`() {
        // 저역만 있는 신호. 가중을 나중에 걸면(=에너지를 먼저 세면)
        // A 가중이 깎기 전의 큰 에너지가 그대로 남아 훨씬 높게 나온다.
        val a = engine(Weighting.A)
        val x = tone(50.0, 1.0, 3000)
        val got = a.process(x, x.size).currentDbfs.value
        // 풀스케일 50Hz 는 -3.01dBFS, A 가중 50Hz 는 -30.2dB → 약 -33.2dBFS
        assertEquals(-33.2, got, 0.5)
        assertTrue("가중 뒤 값이 원신호보다 한참 낮아야 한다", got < -25.0)
    }

    @Test
    fun `MAX 와 PEAK 는 다른 값이다`() {
        val e = engine()
        val x = tone(1000.0, 1.0, 1000)
        val f = e.process(x, x.size)
        // 사인파의 RMS 는 피크보다 3dB 낮다. 둘이 같으면 한쪽이 잘못된 것이다.
        assertEquals(-3.0103, f.maxDbfs.value, 0.05)
        assertEquals(0.0, f.peakDbfs.value, 0.05)
        assertTrue("PEAK 가 MAX 보다 높아야 한다", f.peakDbfs.value > f.maxDbfs.value + 2.5)
    }

    @Test
    fun `PEAK 는 가중 전 파형에서 잰다`() {
        // A 가중은 50Hz 를 30dB 깎는다. PEAK 를 가중 뒤에서 재면
        // 입력이 풀스케일인데도 잘리지 않은 것처럼 보인다.
        val a = engine(Weighting.A)
        val x = tone(50.0, 1.0, 500)
        val f = a.process(x, x.size)
        assertEquals("PEAK 는 원신호의 풀스케일이어야 한다", 0.0, f.peakDbfs.value, 0.05)
        assertTrue("클리핑을 잡아야 한다", f.peakClipped)
    }

    @Test
    fun `MAX 는 조용해져도 내려가지 않는다`() {
        val e = engine()
        val loud = tone(1000.0, 1.0, 500)
        e.process(loud, loud.size)
        val quiet = tone(1000.0, 0.001, 2000)
        val f = e.process(quiet, quiet.size)
        assertEquals("MAX 는 큰 소리를 기억해야 한다", -3.0103, f.maxDbfs.value, 0.1)
        assertTrue("지금 값은 내려가야 한다", f.currentDbfs.value < -50.0)
    }

    @Test
    fun `세션 Leq 는 전체의 에너지 평균이다`() {
        val e = engine()
        e.process(tone(1000.0, 1.0, 1000), fs)          // -3.01 dBFS 1초
        val f = e.process(tone(1000.0, 0.1, 1000), fs)  // -23.01 dBFS 1초
        // 에너지 평균: (0.5 + 0.005)/2 = 0.2525 → 10log10 = -5.97dB
        assertEquals(-5.97, f.leqSessionDbfs!!.value, 0.1)
    }

    @Test
    fun `긴 Leq 는 창이 찰 때까지 그 사실을 알린다`() {
        val e = engine()
        val f = e.process(tone(1000.0, 0.5, 1000), fs)
        assertFalse("1초로는 1분 창이 안 찬다", f.leqLongFull)
        assertNotNull("그래도 값은 있어야 한다", f.leqLongDbfs)
    }

    @Test
    fun `입력이 없으면 없다고 말한다`() {
        val e = engine()
        assertFalse(e.hasInput)
        e.process(FloatArray(0), 0)
        assertFalse(e.hasInput)
        e.process(tone(1000.0, 0.5, 100), fs / 10)
        assertTrue(e.hasInput)
    }

    @Test
    fun `원본 버퍼를 건드리지 않는다`() {
        // 녹음 쪽이 같은 버퍼를 쓴다. 여기서 제자리에 필터를 걸면
        // 저장되는 소리가 A 가중된 소리가 되어 버린다.
        val e = engine(Weighting.A)
        val x = tone(100.0, 0.8, 100)
        val before = x.copyOf()
        e.process(x, x.size)
        for (i in x.indices) assertEquals("샘플 $i", before[i], x[i], 0.0f)
    }

    @Test
    fun `reset 하면 전부 처음으로 돌아간다`() {
        val e = engine()
        e.process(tone(1000.0, 1.0, 500), fs / 2)
        e.reset()
        assertFalse(e.hasInput)
        val f = e.process(tone(1000.0, 0.01, 500), fs / 2)
        assertTrue("옛 MAX 가 남으면 안 된다", f.maxDbfs.value < -30.0)
    }

    @Test
    fun `resetPeaks 는 MAX 만 지우고 Leq 는 남긴다`() {
        val e = engine()
        e.process(tone(1000.0, 1.0, 500), fs / 2)
        e.resetPeaks()
        // 2초는 Fast 시간상수(125ms)의 16배라 앞선 큰 소리가 다 빠진다.
        val f = e.process(tone(1000.0, 0.01, 2000), fs * 2)
        assertEquals("MAX 는 지워지고 지금 소리만 남아야 한다", -43.0, f.maxDbfs.value, 0.5)
        // 세션 Leq 는 큰 소리를 기억한다.
        assertTrue("세션 Leq 는 남아야 한다", f.leqSessionDbfs!!.value > -20.0)
    }

    @Test
    fun `MAX 를 지워도 시간가중에 남은 꼬리는 다시 잡힌다`() {
        // 실제 소음계와 같은 동작이다. 큰 소리 직후에 MAX 를 지우면
        // 바늘이 아직 내려오는 중이라 그 값이 곧바로 새 MAX 가 된다.
        // 「지웠는데 왜 높지」로 읽히지 않도록 여기에 못박아 둔다.
        val e = engine()
        e.process(tone(1000.0, 1.0, 500), fs / 2)
        e.resetPeaks()
        val f = e.process(tone(1000.0, 0.01, 500), fs / 2)  // 4τ 만 지남
        assertTrue(
            "감쇠 중인 꼬리가 잡혀 조용한 레벨보다 높다 (${"%.1f".format(f.maxDbfs.value)}dB)",
            f.maxDbfs.value > -30.0,
        )
        assertTrue("그래도 원래 큰 소리보다는 낮다", f.maxDbfs.value < -10.0)
    }

    @Test
    fun `잘못된 frames 를 거부한다`() {
        val e = engine()
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            e.process(FloatArray(10), 11)
        }
    }
}
