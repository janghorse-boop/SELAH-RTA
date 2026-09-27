package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * **확인해 준 규약으로 파일을 다시 읽는다**(독립 재검토 CFRF-01).
 *
 * 사람이 「이 파일은 보정값이다」라고 정해 주면 원본에서 다시 만든다.
 * 그때 **이미 뒤집은 점을 또 뒤집지 않는** 것이 이 시험의 전부다 —
 * 두 번 뒤집으면 원래대로 돌아가, 확인 단추가 아무 일도 안 한 것처럼
 * 보이면서 보정은 반대로 걸린다.
 */
class CalibrationFileReadingTest {

    /** 1kHz 에서 +3dB, 즉 「그 마이크가 1kHz 를 3dB 크게 잡는다」. */
    private val text = """
        # Correction factors
        Frequency (Hz),Response (dB)
        100,0.0
        1000,3.0
        10000,0.0
    """.trimIndent()

    private fun gainAt1k(reading: CurveReading): Double =
        CalibrationFile.load(text, reading).getOrThrow().curve.gainDbAt(1000.0)

    @Test
    fun `기본은 관례대로 응답으로 읽는다`() {
        assertEquals(gainAt1k(CurveReading.Response), CalibrationFile.load(text).getOrThrow().curve.gainDbAt(1000.0), 1e-9)
    }

    /**
     * **한 번만 뒤집는다.** 뒤집는 일은 `CalibrationCurve.of` 안에서만
     * 일어나야 하고, 여기서 점을 미리 뒤집어 넘기면 두 번이 된다.
     */
    @Test
    fun `보정값으로 읽으면 부호가 한 번만 뒤집힌다`() {
        val asResponse = gainAt1k(CurveReading.Response)
        val asCorrection = gainAt1k(CurveReading.Correction)
        assertNotEquals("두 규약이 같은 값을 낸다 — 뒤집지 않았거나 두 번 뒤집었다", asResponse, asCorrection)
        assertEquals("정확히 부호만 반대여야 한다", -asResponse, asCorrection, 1e-9)
    }

    @Test
    fun `점 수와 머리글은 규약과 무관하다`() {
        val a = CalibrationFile.load(text, CurveReading.Response).getOrThrow()
        val b = CalibrationFile.load(text, CurveReading.Correction).getOrThrow()
        assertEquals(a.pointCount, b.pointCount)
        assertEquals(a.headerLines, b.headerLines)
        assertEquals(a.signEvidence, b.signEvidence)
    }
}
