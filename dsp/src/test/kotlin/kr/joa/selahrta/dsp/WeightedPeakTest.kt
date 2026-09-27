package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * **PEAK 은 두 가지다**(지시서 §5, §16).
 *
 * 하나는 클리핑을 잡는 가중 **전** 파형의 최고값이고, 다른 하나는
 * 화면에 적는 가중 **뒤** 값이다. 하나로 뭉뚱그리면 둘 중 하나가 틀린다 —
 * 가중 뒤 값으로 클리핑을 재면 저역이 깎여 ADC 가 포화한 것을 놓친다.
 */
class WeightedPeakTest {

    private val sampleRate = 48_000

    private fun tone(hz: Double, amp: Double, seconds: Double): FloatArray {
        val n = (sampleRate * seconds).toInt()
        return FloatArray(n) { i -> (amp * sin(2.0 * PI * hz * i / sampleRate)).toFloat() }
    }

    private fun frameOf(w: Weighting, hz: Double, amp: Double): SplFrame {
        val e = SplEngine(sampleRate, w, TimeWeight.Fast)
        val s = tone(hz, amp, 1.0)
        return e.process(s, s.size)
    }

    /** Z 는 통과 필터다. 두 값이 같아야 지금 동작이 안 바뀐다. */
    @Test
    fun `Z 에서는 가중 전후 PEAK 이 같다`() {
        val f = frameOf(Weighting.Z, 1000.0, 0.5)
        assertEquals(f.peakDbfs.value, f.weightedPeakDbfs.value, 0.1)
    }

    /** **이것이 이 파일의 한가운데다.** A 는 저역을 크게 깎는다. */
    @Test
    fun `A 에서는 저역 PEAK 이 가중 전보다 낮다`() {
        val f = frameOf(Weighting.A, 63.0, 0.5)
        assertTrue(
            "가중 뒤 PEAK(${f.weightedPeakDbfs.value})이 " +
                "가중 전(${f.peakDbfs.value})보다 낮지 않다",
            f.weightedPeakDbfs.value < f.peakDbfs.value - 15.0,
        )
    }

    @Test
    fun `C 는 A 보다 저역 PEAK 을 덜 깎는다`() {
        val a = frameOf(Weighting.A, 63.0, 0.5)
        val c = frameOf(Weighting.C, 63.0, 0.5)
        assertTrue(
            "C(${c.weightedPeakDbfs.value})가 A(${a.weightedPeakDbfs.value})보다 크지 않다",
            c.weightedPeakDbfs.value > a.weightedPeakDbfs.value + 10.0,
        )
    }

    /**
     * **클리핑은 ADC 에서 일어나는 일이다.**
     *
     * 가중 뒤 값으로 재면 저역이 깎여 포화한 것을 놓친다. 63Hz 를 풀스케일로
     * 넣으면 A 가중 뒤에는 한참 낮아지지만, 클리핑은 **찍혀야** 한다.
     */
    @Test
    fun `A 가중에서도 클리핑을 놓치지 않는다`() {
        val f = frameOf(Weighting.A, 63.0, 1.0)
        assertTrue("풀스케일 63Hz 에서 클리핑이 안 잡혔다", f.peakClipped)
    }

    /** 1kHz 는 규격의 기준점이다. 어느 가중에서도 PEAK 이 움직이면 안 된다. */
    @Test
    fun `1kHz 에서는 가중을 바꿔도 PEAK 이 그대로다`() {
        val z = frameOf(Weighting.Z, 1000.0, 0.5).weightedPeakDbfs.value
        assertEquals(z, frameOf(Weighting.A, 1000.0, 0.5).weightedPeakDbfs.value, 0.5)
        assertEquals(z, frameOf(Weighting.C, 1000.0, 0.5).weightedPeakDbfs.value, 0.5)
    }

    /** 다시 시작하면 쌓아 둔 최고값도 비워져야 한다. */
    @Test
    fun `reset 이 가중 뒤 PEAK 도 비운다`() {
        val e = SplEngine(sampleRate, Weighting.A, TimeWeight.Fast)
        val loud = tone(1000.0, 0.9, 0.5)
        e.process(loud, loud.size)
        val before = e.process(loud, loud.size).weightedPeakDbfs.value

        e.reset()
        val quiet = tone(1000.0, 0.01, 0.5)
        val after = e.process(quiet, quiet.size).weightedPeakDbfs.value

        assertTrue(
            "reset 뒤에도 옛 최고값($before)이 남아 있다: $after",
            after < before - 20.0,
        )
    }
}
