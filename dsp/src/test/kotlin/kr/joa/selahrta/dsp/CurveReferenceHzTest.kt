package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10

/**
 * **음압 교정기로 잡은 절대 레벨이 보정 곡선에 밀리지 않는가.**
 *
 * ## 무엇이 문제였나
 *
 * 교정기는 1kHz 순음으로 절대 레벨을 준다. 그 오프셋은 시간영역
 * 음압계에서 재는데(가중 필터만 지난다), RTA 밴드는 그 위에 보정 곡선을
 * 더 받는다. 곡선의 1kHz 값이 0dB 이 아니면 **큰 숫자와 1kHz 막대가
 * 그만큼 갈라진다.** 곡선을 갈아 끼우면 갈라짐도 따라 바뀐다.
 *
 * 지금 보정 곡선은 300~3000Hz **평균**을 0dB 로 맞춘다. 평균이 0 인 것과
 * 1kHz 가 0 인 것은 다르다 — 기울어진 곡선에서는 늘 다르다.
 *
 * ## 무엇을 검사하는가
 *
 * 걸려 있는 보정이 1kHz 에서 정확히 0dB 인가. 그러면 곡선이 무엇이든,
 * 곡선이 없든, 1kHz 는 교정기가 말한 값 그대로다.
 */
class CurveReferenceHzTest {

    private val fs = 48_000
    private val fft = 4096

    /** 저역이 처지고 고역이 솟은, 1kHz 가 0dB 이 **아닌** 응답 곡선. */
    private fun tilted() = CalibrationCurve.of(
        listOf(
            CurvePoint(20.0, -8.0),
            CurvePoint(1_000.0, 3.0),
            CurvePoint(20_000.0, 9.0),
        ),
    ).getOrThrow()

    private fun binDb(c: DoubleArray, hz: Double): Double {
        val k = (hz / (fs.toDouble() / fft)).toInt()
        return 10.0 * log10(c[k])
    }

    @Test
    fun `기준 주파수를 주면 그 자리의 보정이 정확히 0dB 이다`() {
        val c = tilted().binCorrectionLinear(fft, fs, CURVE_REFERENCE_HZ)
        assertEquals(0.0, binDb(c, 1_000.0), 0.02)
    }

    @Test
    fun `기준 주파수를 안 주면 0dB 이 아니다`() {
        // 이 시험이 없으면 위 시험이 「원래 그랬다」로도 통과한다.
        val c = tilted().binCorrectionLinear(fft, fs)
        assertEquals("응답 +3dB → 보정 -3dB", -3.0, binDb(c, 1_000.0), 0.05)
        assertNotEquals(0.0, binDb(c, 1_000.0), 0.5)
    }

    @Test
    fun `모양은 바뀌지 않는다`() {
        // 상수만큼 미는 것이므로 **어느 두 칸의 차이도 그대로**여야 한다.
        // 모양까지 건드리면 보정이 아니라 왜곡이다.
        val plain = tilted().binCorrectionLinear(fft, fs)
        val anchored = tilted().binCorrectionLinear(fft, fs, CURVE_REFERENCE_HZ)
        val shift = 10.0 * log10(anchored[1] / plain[1])
        for (k in 1 until plain.size) {
            assertEquals(
                "칸 $k 의 밀린 양이 다르다",
                shift,
                10.0 * log10(anchored[k] / plain[k]),
                1e-9,
            )
        }
        assertTrue("실제로 밀렸어야 한다", abs(shift) > 1.0)
    }

    @Test
    fun `엔진에 걸면 1kHz 순음의 값이 곡선과 무관하다`() {
        // 이것이 교정기가 서 있는 자리다. 같은 순음을 곡선 없이, 그리고
        // 기울어진 곡선으로 재서 1kHz 밴드가 같은지 본다.
        fun read(curve: CalibrationCurve?): Double {
            val e = RtaEngine(fs)
            e.setCurve(curve)
            val n = fs / 2
            val d = SignalGenerator.sine(1_000.0, fs, n, 0.3)
            e.process(FloatArray(n) { d[it].toFloat() }, n)
            return e.frame()!!.bandsDbfs[ThirdOctave.nearestBand(1_000.0)]
        }
        assertEquals("곡선이 1kHz 를 움직였다", read(null), read(tilted()), 0.05)
    }

    @Test
    fun `다른 대역은 곡선을 그대로 받는다`() {
        // 못을 박은 것이 「곡선을 무력화한 것」이면 곤란하다. 1kHz 만
        // 고정이고 나머지는 여전히 보정된다.
        fun read(hz: Double, curve: CalibrationCurve?): Double {
            val e = RtaEngine(fs)
            e.setCurve(curve)
            val n = fs / 2
            val d = SignalGenerator.sine(hz, fs, n, 0.3)
            e.process(FloatArray(n) { d[it].toFloat() }, n)
            return e.frame()!!.bandsDbfs[ThirdOctave.nearestBand(hz)]
        }
        // 20Hz 에서 -8dB, 1kHz 에서 +3dB 인 응답 → 125Hz 의 응답은 그 사이
        // (로그 보간). 보정은 그 반대 부호로 걸리고, 1kHz 기준으로 밀린다.
        val plain = read(125.0, null)
        val corrected = read(125.0, tilted())
        assertTrue(
            "125Hz 가 보정을 안 받았다: $plain -> $corrected",
            abs(corrected - plain) > 1.0,
        )
    }

    @Test
    fun `기준 주파수가 0 이하면 거부한다`() {
        val e = runCatching { tilted().binCorrectionLinear(fft, fs, 0.0) }.exceptionOrNull()
        assertTrue("조용히 넘어가면 안 된다", e is IllegalArgumentException)
    }
}
