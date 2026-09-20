package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 명세 15장 「RMS/dBFS synthetic sine」.
 *
 * 구현을 그대로 베낀 시험이 아니라, **손으로 계산할 수 있는 값**과 맞춘다.
 * 풀스케일 사인파의 RMS 는 1/√2 이고 20·log10(1/√2) = -3.0103 dBFS 다.
 * 이 숫자는 구현이 어떻게 생겼든 변하지 않는다.
 */
class LevelTest {

    private val sampleRate = 48_000

    @Test
    fun `풀스케일 사인파는 -3_01 dBFS 다`() {
        val n = SignalGenerator.wholeCycleLength(1000.0, sampleRate, 4096)
        val x = SignalGenerator.sine(1000.0, sampleRate, n, amplitude = 1.0)
        assertEquals(0.70710678, rms(x), 1e-6)
        assertEquals(-3.0103, blockDbfs(x).value, 1e-3)
    }

    @Test
    fun `진폭이 절반이면 정확히 6dB 낮다`() {
        // 전압비 1/2 은 20·log10(0.5) = -6.0206 dB. 에너지비로 착각해
        // 10·log10 을 쓰면 -3dB 이 나오므로, 이 시험이 그 실수를 잡는다.
        val n = SignalGenerator.wholeCycleLength(1000.0, sampleRate, 4096)
        val full = blockDbfs(SignalGenerator.sine(1000.0, sampleRate, n, 1.0)).value
        val half = blockDbfs(SignalGenerator.sine(1000.0, sampleRate, n, 0.5)).value
        assertEquals(-6.0206, half - full, 1e-3)
    }

    @Test
    fun `직류는 RMS 가 진폭과 같다`() {
        assertEquals(1.0, rms(SignalGenerator.dc(1.0, 1024)), 1e-12)
        assertEquals(0.0, blockDbfs(SignalGenerator.dc(1.0, 1024)).value, 1e-12)
    }

    @Test
    fun `무음은 발산하지 않고 바닥값이 된다`() {
        // -Infinity 가 새어나가면 평균·그래프·표시가 한꺼번에 무너진다.
        val db = blockDbfs(SignalGenerator.silence(1024)).value
        assertEquals(SILENCE_DBFS, db, 0.0)
        assertTrue("무음이 유한한 값이어야 한다", db.isFinite())
    }

    @Test
    fun `주파수가 달라도 레벨은 같다`() {
        // RMS 는 주파수와 무관하다. 여기서 차이가 나면 창·길이 처리가 잘못된 것이다.
        for (f in listOf(125.0, 315.0, 1000.0, 2500.0, 4000.0)) {
            val n = SignalGenerator.wholeCycleLength(f, sampleRate, 4096)
            val db = blockDbfs(SignalGenerator.sine(f, sampleRate, n, 0.5)).value
            assertEquals("${f}Hz", -9.0309, db, 1e-3)
        }
    }

    @Test
    fun `일부 구간만 재도 결과가 같다`() {
        val n = SignalGenerator.wholeCycleLength(1000.0, sampleRate, 4096)
        val x = SignalGenerator.sine(1000.0, sampleRate, n * 2, 1.0)
        assertEquals(rms(x, 0, n), rms(x, n, n * 2), 1e-9)
    }

    @Test
    fun `빈 구간은 0 이고 잘못된 범위는 거부한다`() {
        assertEquals(0.0, rms(DoubleArray(0)), 0.0)
        assertEquals(0.0, rms(DoubleArray(10), 5, 5), 0.0)
        assertThrows(IllegalArgumentException::class.java) { rms(DoubleArray(10), 0, 11) }
        assertThrows(IllegalArgumentException::class.java) { rms(DoubleArray(10), 7, 3) }
    }

    @Test
    fun `보정을 거쳐야만 음압이 된다`() {
        // 미보정이면 dBFS 와 숫자가 같다. 그 사실이 화면에서 「참고용」 표시로
        // 이어져야 한다 — 같은 숫자라고 해서 음압이 된 것은 아니다.
        val dbfs = Dbfs(-30.0)
        assertEquals(-30.0, dbfs.toSpl(CalibrationOffset.UNCALIBRATED).value, 1e-12)
        assertEquals(64.0, dbfs.toSpl(CalibrationOffset(94.0)).value, 1e-12)
    }
}
