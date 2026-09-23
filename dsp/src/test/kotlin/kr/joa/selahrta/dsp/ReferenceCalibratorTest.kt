package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 여러 장을 보정할 때 **한 번 만든 것을 돌려쓴다** — 그러면서도 장마다의
 * 값은 섞이지 않아야 한다.
 */
class ReferenceCalibratorTest {

    private val fftSize = 1024
    private val sampleRate = 48_000
    private val analyzer = BandAnalyzer(fftSize, sampleRate)

    private fun curve(gainDb: Double) = CalibrationCurve.of(
        listOf(CurvePoint(20.0, gainDb), CurvePoint(20_000.0, gainDb)),
    ).getOrThrow()

    private fun binPower(level: Double) = DoubleArray(fftSize / 2 + 1) { level }

    @Test
    fun `한 장짜리와 같은 값을 낸다`() {
        val c = curve(6.0)
        val p = binPower(1e-4)
        val once = applyReferenceCalibration(p, analyzer, c, fftSize, sampleRate, "17860.txt", "abc")
        val many = ReferenceCalibrator(c, analyzer, fftSize, sampleRate, "17860.txt", "abc")

        val got = many.spectrum(p)
        for (b in 0 until ThirdOctave.BAND_COUNT) {
            assertEquals("밴드 $b", once.bandsDb[b], got.bandsDb[b], 1e-12)
        }
        assertTrue(once.proof.sameSetupAs(got.proof))
    }

    /**
     * **장마다 새 배열이어야 한다.**
     *
     * 돌려쓰면 모아 둔 장들이 전부 같은 배열을 가리켜, 마지막 장 하나가
     * 여러 번 들어 있는 꼴이 된다 — 길이와 모양은 멀쩡해 보인다.
     */
    @Test
    fun `모아 둔 장들이 서로 다른 값을 지킨다`() {
        val cal = ReferenceCalibrator(curve(0.0), analyzer, fftSize, sampleRate)
        val quiet = cal.spectrum(binPower(1e-8))
        val loud = cal.spectrum(binPower(1e-4))

        assertNotSame(quiet.bandsDb, loud.bandsDb)
        val gap = loud.bandsDb[15] - quiet.bandsDb[15]
        assertEquals("40dB 차이가 남아 있어야 한다", 40.0, gap, 0.01)
    }

    /** 증거는 하나로 충분하다 — 설정이 같으니 장마다 다를 까닭이 없다. */
    @Test
    fun `모든 장이 같은 증거를 단다`() {
        val cal = ReferenceCalibrator(curve(0.0), analyzer, fftSize, sampleRate)
        assertSame(cal.proof, cal.spectrum(binPower(1e-6)).proof)
        assertSame(cal.proof, cal.spectrum(binPower(1e-5)).proof)
    }

    /**
     * **묶기 전에 곱한다**(R05 · CP04). 밴드 중심에서 빼는 것과 다르다.
     *
     * 여기서는 그 차이가 드러나도록 한 밴드 안에서 CAL 이 크게 변하는
     * 곡선을 쓴다 — 중심 하나로 빼면 안쪽의 기울기를 놓친다.
     */
    @Test
    fun `밴드 안에서 변하는 CAL 도 칸마다 걸린다`() {
        // 1kHz 부근에서만 가파르게 솟는 곡선.
        val steep = CalibrationCurve.of(
            listOf(
                CurvePoint(20.0, 0.0),
                CurvePoint(900.0, 0.0),
                CurvePoint(1_000.0, 12.0),
                CurvePoint(1_120.0, 0.0),
                CurvePoint(20_000.0, 0.0),
            ),
        ).getOrThrow()
        val cal = ReferenceCalibrator(steep, analyzer, fftSize, sampleRate)
        val flat = ReferenceCalibrator(curve(0.0), analyzer, fftSize, sampleRate)

        val p = binPower(1e-6)
        val band = ThirdOctave.nearestBand(1_000.0)
        val withSteep = cal.spectrum(p).bandsDb[band]
        val withFlat = flat.spectrum(p).bandsDb[band]

        // 중심에서 12dB 를 통째로 빼면 정확히 -12dB 가 된다. 칸마다 걸면
        // 밴드 안의 일부 칸만 깎이므로 **그보다 적게** 내려간다.
        val drop = withFlat - withSteep
        assertTrue("아무 차이가 없다면 CAL 이 안 걸린 것이다: $drop", drop > 0.5)
        assertTrue("중심에서 통째로 뺀 것과 같아졌다: $drop", drop < 11.0)
    }
}
