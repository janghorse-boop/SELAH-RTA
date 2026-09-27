package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * **표기만 바뀌고 값이 그대로인 일이 없어야 한다**(지시서 §18).
 *
 * 가중치를 고르는 기능을 만들 때 가장 쉬운 실패는, 화면의 단위 글자만
 * 바꾸고 DSP 는 그대로 두는 것이다. 그러면 「A 로 봐도 C 로 봐도 같은
 * 숫자」가 되어 기능 자체가 거짓말이 된다.
 */
class RtaWeightingTest {

    private val sampleRate = 48_000

    /** 그 주파수의 순음을 [seconds] 초만큼 만든다. */
    private fun tone(hz: Double, seconds: Double): FloatArray {
        val n = (sampleRate * seconds).toInt()
        return FloatArray(n) { i ->
            (0.2 * sin(2.0 * PI * hz * i / sampleRate)).toFloat()
        }
    }

    private fun bandOf(hz: Double): Int {
        val b = ThirdOctave.CENTERS_HZ.indexOfFirst { abs(it - hz) < hz * 0.06 }
        assertTrue("${hz}Hz 대역을 못 찾았다", b >= 0)
        return b
    }

    /** 순음을 흘려 그 대역의 값을 읽는다. */
    private fun bandDbOf(w: Weighting, hz: Double): Double {
        val e = RtaEngine(sampleRate, smoothingFactor = 0.0)
        e.setAnalysisWeighting(w)
        val s = tone(hz, 1.0)
        e.process(s, s.size)
        val f = e.frame() ?: error("프레임이 없다")
        return f.bandsDbfs[bandOf(hz)]
    }

    /** **이것이 이 파일의 한가운데다.** 지시서 §18 그 자체다. */
    @Test
    fun `A 를 고르면 저역 대역이 실제로 내려간다`() {
        val z = bandDbOf(Weighting.Z, 63.0)
        val a = bandDbOf(Weighting.A, 63.0)
        // IEC 61672-1: A-weighting 63Hz = -26.2 dB
        assertEquals("63Hz 에서 A 가중이 걸리지 않았다", -26.2, a - z, 1.5)
    }

    /**
     * **31.5Hz 로 본다.** 63Hz 에서 C 가중은 -0.8dB 뿐이라, 재는 여유
     * 안에 들어 「가중이 아예 안 걸린 상태」와 구분되지 않는다 — 변이를
     * 심어 보고 알았다. 31.5Hz 는 -3.0dB 이라 갈린다.
     */
    @Test
    fun `C 를 고르면 저역이 실제로 깎인다`() {
        val z = bandDbOf(Weighting.Z, 31.5)
        val c = bandDbOf(Weighting.C, 31.5)
        // IEC 61672-1: C-weighting 31.5Hz = -3.0 dB
        assertEquals("31.5Hz 에서 C 가중이 걸리지 않았다", -3.0, c - z, 1.0)
    }

    /** A 는 C 보다 저역을 훨씬 많이 깎는다. 둘이 뒤바뀌면 잡는다. */
    @Test
    fun `A 가 C 보다 저역을 많이 깎는다`() {
        val a = bandDbOf(Weighting.A, 31.5)
        val c = bandDbOf(Weighting.C, 31.5)
        // 규격상 31.5Hz 에서 A 는 -39.4, C 는 -3.0 — 36dB 넘게 벌어진다.
        assertTrue("A(${a}) 가 C(${c}) 보다 충분히 낮지 않다", c - a > 30.0)
    }

    /** 1kHz 는 규격의 기준점이다. 어느 가중에서도 움직이면 안 된다. */
    @Test
    fun `1kHz 는 가중을 바꿔도 그대로다`() {
        val z = bandDbOf(Weighting.Z, 1000.0)
        assertEquals(z, bandDbOf(Weighting.A, 1000.0), 0.3)
        assertEquals(z, bandDbOf(Weighting.C, 1000.0), 0.3)
    }

    /** **회귀 방지.** Z 는 지금까지의 동작과 한 치도 달라지면 안 된다. */
    @Test
    fun `Z 는 가중을 걸기 전과 똑같다`() {
        val before = RtaEngine(sampleRate, smoothingFactor = 0.0)
        val s1 = tone(250.0, 1.0)
        before.process(s1, s1.size)
        val expected = before.frame()!!.bandsDbfs

        val after = RtaEngine(sampleRate, smoothingFactor = 0.0)
        after.setAnalysisWeighting(Weighting.Z)
        val s2 = tone(250.0, 1.0)
        after.process(s2, s2.size)
        val actual = after.frame()!!.bandsDbfs

        expected.indices.forEach { i ->
            assertEquals("밴드 $i 가 달라졌다", expected[i], actual[i], 1e-9)
        }
    }

    /** 기본값은 Z 다 — 분석 화면은 가중을 걸지 않고 보는 것이 기본이다. */
    @Test
    fun `기본 분석 가중은 Z 다`() {
        assertEquals(Weighting.Z, RtaEngine(sampleRate).analysisWeighting)
    }

    /**
     * **하울링 탐지는 가중 전 스펙트럼을 본다.**
     *
     * 봉우리가 둘레보다 얼마나 솟았는지를 보는 것이라, 기울기를 씌우면
     * 솟은 정도가 달라져 판정이 흔들린다.
     */
    @Test
    fun `싱크가 받는 스펙트럼은 가중을 타지 않는다`() {
        fun sinkPowerAt(w: Weighting): DoubleArray {
            val e = RtaEngine(sampleRate, smoothingFactor = 0.0)
            e.setAnalysisWeighting(w)
            var got: DoubleArray? = null
            e.addSpectrumSink { p -> if (got == null) got = p.copyOf() }
            val s = tone(63.0, 1.0)
            e.process(s, s.size)
            return got ?: error("싱크가 받지 못했다")
        }
        val z = sinkPowerAt(Weighting.Z)
        val a = sinkPowerAt(Weighting.A)
        z.indices.forEach { i ->
            assertEquals("칸 $i 가 가중을 탔다", z[i], a[i], 1e-12)
        }
    }

    /**
     * 가중을 바꿨다가 되돌리면 처음과 같아야 한다.
     *
     * 곡선과 가중을 합쳐 두는 구조라, 되돌릴 때 합친 배열을 다시 만들지
     * 않으면 가중이 **걸린 채로 남는다.**
     */
    @Test
    fun `가중을 되돌리면 처음 값으로 돌아온다`() {
        val e = RtaEngine(sampleRate, smoothingFactor = 0.0)
        val s = tone(63.0, 1.0)
        e.process(s, s.size)
        val first = e.frame()!!.bandsDbfs[bandOf(63.0)]

        e.setAnalysisWeighting(Weighting.A)
        e.process(tone(63.0, 1.0), s.size)
        e.setAnalysisWeighting(Weighting.Z)
        e.process(tone(63.0, 1.0), s.size)
        val back = e.frame()!!.bandsDbfs[bandOf(63.0)]

        assertEquals("가중이 걸린 채로 남았다", first, back, 0.5)
    }
}
