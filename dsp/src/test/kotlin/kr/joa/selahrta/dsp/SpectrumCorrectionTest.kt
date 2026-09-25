package kr.joa.selahrta.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 화면 스펙트럼이 **RTA 와 같은 숫자를 말하는가**.
 *
 * ## 왜 이 시험이 있는가
 *
 * [SpectrumSink] 는 **보정 전** 스펙트럼을 받는다. 일부러 그렇다 — 하울링
 * 탐지는 봉우리가 둘레보다 얼마나 솟았는지만 보므로 마이크 응답을 되돌릴
 * 까닭이 없다([RtaEngine.runFft] 주석).
 *
 * 그런데 화면은 다르다. Spectrum 을 그 싱크 위에 그대로 올리면, 나란히 둔
 * RTA 막대와 **다른 값**이 된다 — EMM-6 처럼 곡선이 걸린 마이크에서는
 * 고역이 눈에 띄게 어긋난다. 같은 소리를 같은 화면에서 두 숫자로 말하면
 * 어느 쪽을 믿어야 할지 알 수 없다.
 *
 * 그래서 화면 경로만 보정 뒤 값을 쓴다. 이 시험이 그 두 가지를 한꺼번에
 * 못박는다 — **화면은 RTA 를 따라 움직이고, 탐지기는 건드리지 않는다.**
 */
class SpectrumCorrectionTest {

    private val fs = 48_000
    private val toneHz = 4_000.0
    private val band4k = 23

    /** 1kHz 를 기준으로 4kHz 를 6dB 더 잡는 마이크. */
    private fun curve(): CalibrationCurve = CalibrationCurve.of(
        listOf(
            CurvePoint(20.0, 0.0),
            CurvePoint(1_000.0, 0.0),
            CurvePoint(4_000.0, 6.0),
            CurvePoint(20_000.0, 6.0),
        ),
    ).getOrThrow()

    private fun run(c: CalibrationCurve?, sink: SpectrumSink? = null): RtaEngine {
        val e = RtaEngine(fs)
        e.spectrumEnabled = true
        sink?.let { e.addSpectrumSink(it) }
        c?.let { e.setCurve(it) }
        val n = fs / 2
        val d = SignalGenerator.sine(toneHz, fs, n, 0.3)
        e.process(FloatArray(n) { d[it].toFloat() }, n)
        return e
    }

    /** 4kHz 가 떨어지는 화면 칸. */
    private fun columnAt(a: SpectrumAxis, hz: Double): Int =
        a.hz.indices.minByOrNull { abs(a.hz[it] - hz) }!!

    /**
     * **이 시험이 보이는 것의 한계**(독립 검토 2026-09-25의 정정).
     *
     * 쓰는 곡선이 4kHz 둘레에서 평평해서 두 값이 같은 만큼 움직인다.
     * 밴드 안에서 계수가 달라지면 전력 **합**과 **최대값**의 이동량은
     * 일반적으로 다르다 — 그때도 같기를 요구하면 안 된다. 여기서 못박는
     * 것은 **「화면이 RTA 와 같은 보정을 받는다」**이지 「어떤 곡선에서도
     * 같은 숫자만큼 움직인다」가 아니다.
     */
    @Test
    fun `보정이 걸리면 화면 스펙트럼이 RTA 밴드와 같은 만큼 움직인다`() {
        val plain = run(null)
        val corrected = run(curve())

        val col = columnAt(plain.spectrumAxis, toneHz)
        val deltaBand = corrected.frame()!!.bandsDbfs[band4k] - plain.frame()!!.bandsDbfs[band4k]
        val deltaColumn = corrected.spectrumFrame()!!.columnsDbfs[col] -
            plain.spectrumFrame()!!.columnsDbfs[col]

        assertTrue("곡선이 아무 일도 안 했다: ${deltaBand}dB", abs(deltaBand) > 3.0)
        assertEquals(
            "RTA 는 ${"%.2f".format(deltaBand)}dB 움직였는데 화면은 ${"%.2f".format(deltaColumn)}dB",
            deltaBand,
            deltaColumn,
            0.5,
        )
    }

    @Test
    fun `보정을 걸어도 탐지기가 받는 스펙트럼은 그대로다`() {
        var plainSeen: DoubleArray? = null
        var correctedSeen: DoubleArray? = null
        run(null) { p -> plainSeen = p.copyOf() }
        run(curve()) { p -> correctedSeen = p.copyOf() }

        assertNotNull(plainSeen)
        assertNotNull(correctedSeen)
        // 같은 신호를 같은 길이로 넣었으므로 **한 칸도 달라지면 안 된다.**
        assertArrayEquals(plainSeen!!, correctedSeen!!, 0.0)
    }

    @Test
    fun `켜지 않으면 화면 스펙트럼을 만들지 않는다`() {
        val e = RtaEngine(fs)
        val n = fs / 4
        val d = SignalGenerator.sine(toneHz, fs, n, 0.3)
        e.process(FloatArray(n) { d[it].toFloat() }, n)
        assertNotNull("RTA 는 돈다", e.frame())
        assertNull("스펙트럼은 꺼져 있다", e.spectrumFrame())
    }

    @Test
    fun `최고 봉우리도 보정 뒤 값으로 낸다`() {
        val top = run(curve()).spectrumFrame()!!.top
        assertNotNull(top)
        assertEquals("주파수", toneHz, top!!.hz, 2.0)
        val col = columnAt(run(curve()).spectrumAxis, toneHz)
        val colDb = run(curve()).spectrumFrame()!!.columnsDbfs[col]
        // 같은 봉우리를 보는 두 값이라 크게 벌어질 수 없다.
        assertEquals("칸 값과 봉우리 값", colDb, top.dbfs, 1.0)
    }

    @Test
    fun `곡선을 갈면 화면 스펙트럼도 함께 비운다`() {
        val e = run(null)
        assertNotNull(e.spectrumFrame())
        e.setCurve(curve())
        assertNull("옛 곡선으로 낸 장이 남아 있다", e.spectrumFrame())
    }
}
