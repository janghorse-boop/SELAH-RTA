package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

/** 명세 15장 「Fast/Slow step response」와 「Leq energy average」. */
class TimeWeightingTest {

    private val fs = 48_000

    /** 진폭 [amp] 의 1kHz 사인파를 [ms] 밀리초만큼 넣는다. */
    private fun feed(w: ExponentialTimeWeighting, amp: Double, ms: Int) {
        val n = fs * ms / 1000
        val x = SignalGenerator.sine(1000.0, fs, n, amp)
        w.pushBlock(x, n)
    }

    @Test
    fun `시간상수만큼 지나면 에너지의 63퍼센트에 이른다`() {
        // 1차 저역통과의 정의다. τ 뒤에 1 - 1/e = 63.2% → dB 로 -1.99dB.
        for (tw in TimeWeight.entries) {
            val w = ExponentialTimeWeighting(tw, fs)
            w.reset()
            // 0 에서 출발시키려고 무음을 먼저 조금 넣는다.
            feed(w, 0.0, 200)
            val tauMs = (tw.tauSeconds * 1000).toInt()
            feed(w, 1.0, tauMs)
            val finalMs = w.let { 0.5 }  // 풀스케일 사인파의 평균제곱 = 0.5
            val reached = 10.0 * log10(w.levelDbfs()!!.value.let { 10.0.pow(it / 10.0) } / finalMs)
            assertEquals("${tw.labelKo}: τ 뒤에 -1.99dB", -1.99, reached, 0.15)
        }
    }

    @Test
    fun `Fast 가 Slow 보다 빨리 따라간다`() {
        val fast = ExponentialTimeWeighting(TimeWeight.Fast, fs)
        val slow = ExponentialTimeWeighting(TimeWeight.Slow, fs)
        feed(fast, 0.0, 200); feed(slow, 0.0, 200)
        feed(fast, 1.0, 125); feed(slow, 1.0, 125)
        // 125ms 뒤: Fast 는 거의 다 올라왔고 Slow 는 한참 아래다.
        val f = fast.levelDbfs()!!.value
        val s = slow.levelDbfs()!!.value
        assertTrue("Fast($f) 가 Slow($s) 보다 높아야 한다", f > s + 4.0)
    }

    @Test
    fun `충분히 오래 넣으면 실제 레벨에 닿는다`() {
        // 풀스케일 사인파의 RMS 는 1/√2 → -3.01 dBFS.
        val w = ExponentialTimeWeighting(TimeWeight.Fast, fs)
        feed(w, 1.0, 2000)
        assertEquals(-3.0103, w.levelDbfs()!!.value, 0.05)
    }

    @Test
    fun `dB 를 평활한 것과 다르다 — 짧고 큰 소리에서 갈린다`() {
        // 조용한 구간에 짧은 큰 소리가 섞이면, 에너지로 다루는 쪽이
        // 훨씬 높게 나온다. 박수·드럼이 바로 이 경우다.
        val w = ExponentialTimeWeighting(TimeWeight.Fast, fs)
        feed(w, 0.001, 500)   // 아주 조용함 (-60dBFS 상당)
        feed(w, 1.0, 20)      // 20ms 짧은 풀스케일
        val energyDb = w.levelDbfs()!!.value
        // 20ms 는 τ(125ms)의 1/6 이라 아직 다 안 올라왔지만, 조용한 쪽보다
        // 훨씬 높아야 한다. dB 평활이면 시간 비율대로 조금만 올라간다.
        assertTrue("짧은 큰 소리를 제대로 잡아야 한다 (지금 ${"%.1f".format(energyDb)}dB)",
            energyDb > -15.0)
    }

    @Test
    fun `아무것도 안 넣으면 값이 없다`() {
        assertNull(ExponentialTimeWeighting(TimeWeight.Fast, fs).levelDbfs())
    }

    @Test
    fun `잘못된 샘플레이트를 거부한다`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ExponentialTimeWeighting(TimeWeight.Fast, 0)
        }
    }

    // ---- Leq ----

    @Test
    fun `일정한 신호의 Leq 는 그 신호의 레벨과 같다`() {
        val leq = RollingLeq(windowMs = 1000, sampleRate = fs)
        val n = SignalGenerator.wholeCycleLength(1000.0, fs, fs)
        leq.addBlock(SignalGenerator.sine(1000.0, fs, n, 1.0), n)
        assertEquals(-3.0103, leq.leqDbfs()!!.value, 0.05)
    }

    @Test
    fun `Leq 는 에너지 평균이다 — dB 평균이 아니다`() {
        // 절반은 -10dBFS, 절반은 -30dBFS. 에너지 평균은 -12.97dB 이고
        // dB 평균(-20dB)과 7dB 나 다르다.
        val leq = RollingLeq(windowMs = 2000, sampleRate = fs)
        val loud = 10.0.pow(-10.0 / 20.0) * kotlin.math.sqrt(2.0)   // RMS 가 -10dBFS 가 되게
        val quiet = 10.0.pow(-30.0 / 20.0) * kotlin.math.sqrt(2.0)
        val n = SignalGenerator.wholeCycleLength(1000.0, fs, fs)
        leq.addBlock(SignalGenerator.sine(1000.0, fs, n, loud), n)
        leq.addBlock(SignalGenerator.sine(1000.0, fs, n, quiet), n)
        assertEquals(-12.9671, leq.leqDbfs()!!.value, 0.05)
    }

    @Test
    fun `창이 지나가면 옛 소리는 빠진다`() {
        val leq = RollingLeq(windowMs = 1000, sampleRate = fs, bucketMs = 100)
        val n = SignalGenerator.wholeCycleLength(1000.0, fs, fs)
        // 1초 동안 큰 소리
        leq.addBlock(SignalGenerator.sine(1000.0, fs, n, 1.0), n)
        val loudLeq = leq.leqDbfs()!!.value
        // 그 뒤 1초 동안 조용한 소리 → 큰 소리는 창 밖으로 밀려난다
        leq.addBlock(SignalGenerator.sine(1000.0, fs, n, 0.01), n)
        val quietLeq = leq.leqDbfs()!!.value
        assertTrue("큰 소리가 창을 벗어나야 한다 ($loudLeq → $quietLeq)",
            loudLeq - quietLeq > 30.0)
    }

    @Test
    fun `창이 찼는지 알려준다`() {
        val leq = RollingLeq(windowMs = 1000, sampleRate = fs, bucketMs = 100)
        assertFalse(leq.isFull)
        leq.addBlock(SignalGenerator.sine(1000.0, fs, fs / 2, 0.5), fs / 2)
        assertFalse("0.5초로는 1초 창이 차지 않는다", leq.isFull)
        leq.addBlock(SignalGenerator.sine(1000.0, fs, fs, 0.5), fs)
        assertTrue(leq.isFull)
    }

    @Test
    fun `아직 안 채워진 칸을 0 으로 세지 않는다`() {
        // 0 으로 세면 시작 직후에 「아주 조용함」으로 보인다.
        val leq = RollingLeq(windowMs = 60_000, sampleRate = fs)
        val n = SignalGenerator.wholeCycleLength(1000.0, fs, fs / 10)
        leq.addBlock(SignalGenerator.sine(1000.0, fs, n, 1.0), n)
        // 1분 창에 0.1초만 넣었는데도 그 0.1초의 레벨이 나와야 한다.
        assertEquals(-3.0103, leq.leqDbfs()!!.value, 0.1)
        assertFalse(leq.isFull)
    }

    @Test
    fun `reset 하면 처음으로 돌아간다`() {
        val leq = RollingLeq(windowMs = 1000, sampleRate = fs)
        leq.addBlock(SignalGenerator.sine(1000.0, fs, fs, 1.0), fs)
        leq.reset()
        assertNull(leq.leqDbfs())
        assertFalse(leq.isFull)
    }

    @Test
    fun `잘못된 창 설정을 거부한다`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RollingLeq(windowMs = 0, sampleRate = fs)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RollingLeq(windowMs = 100, sampleRate = fs, bucketMs = 200)
        }
    }

    private fun Double.pow(e: Double) = Math.pow(this, e)
}
