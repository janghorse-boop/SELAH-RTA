package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * **CAL 을 칸에 거는 길이 실제로 맞는가.**
 *
 * 세션의 다른 시험들은 밴드 값을 그대로 쓰는 표본으로 순서와 셈을
 * 재지만, **이 파일만은 실제 칸 전력에서 밴드까지 가는 길을 잰다.**
 * 그 길이 틀리면 나머지 전부가 조용히 틀린다.
 *
 * 왜 이 길이어야 하는지는 [applyReferenceCalibration] 의 KDoc 에 있다 —
 * 밴드로 묶은 뒤 중심주파수 응답을 빼는 것은 밴드 안에서 CAL 이 일정할
 * 때만 맞고, 이 저장소는 그 오류를 두 번 겪었다(R05, CP04).
 */
class ReferenceCalibrationProofTest {

    private val fftSize = 4096
    private val sampleRate = 48_000
    private val analyzer = BandAnalyzer(fftSize, sampleRate)
    private val binCount = fftSize / 2 + 1
    private val binWidth = sampleRate.toDouble() / fftSize

    /** 한 칸에만 전력이 있는 스펙트럼. 그 칸의 주파수를 알고 만든다. */
    private fun singleBin(hz: Double, power: Double = 1.0): DoubleArray {
        val k = Math.round(hz / binWidth).toInt()
        return DoubleArray(binCount).also { it[k] = power }
    }

    private fun flatCurve(gainDb: Double) = CalibrationCurve.of(
        listOf(CurvePoint(20.0, gainDb), CurvePoint(20_000.0, gainDb)),
    ).getOrThrow()

    // ------------------------------------------------------------------
    // 거는 방향과 양
    // ------------------------------------------------------------------

    /**
     * **CAL 규약대로 뺀다.** 마이크가 +6dB 더 잡는다면 그만큼 낮춰야
     * 참값이 된다.
     */
    @Test
    fun `평탄한 CAL 은 그만큼 값을 낮춘다`() {
        val bins = singleBin(1000.0)
        val zero = applyReferenceCalibration(bins, analyzer, flatCurve(0.0), fftSize, sampleRate)
        val plus6 = applyReferenceCalibration(bins, analyzer, flatCurve(6.0), fftSize, sampleRate)

        val b = ThirdOctave.nearestBand(1000.0)
        assertEquals(
            "마이크가 6dB 더 잡았으니 6dB 를 뺀다",
            zero.bandsDb[b] - 6.0, plus6.bandsDb[b], 1e-9,
        )
    }

    @Test
    fun `CAL 이 0dB 면 값이 그대로다`() {
        val bins = singleBin(1000.0)
        val out = applyReferenceCalibration(bins, analyzer, flatCurve(0.0), fftSize, sampleRate)

        val plain = DoubleArray(ThirdOctave.BAND_COUNT)
        val db = DoubleArray(ThirdOctave.BAND_COUNT)
        analyzer.toBandPower(bins, plain)
        analyzer.toBandDbfs(plain, db)

        assertArrayClose("보정 없는 경로와 같아야 한다", db, out.bandsDb, 1e-9)
    }

    /**
     * ★ **이 파일의 요점** — 밴드 안에서 CAL 이 변하면 칸마다 걸어야
     * 한다.
     *
     * 한 밴드 안에 응답 +6dB 와 −6dB 인 성분을 하나씩 두고, 중심 응답은
     * 0dB 로 둔다. 밴드 중심에서 빼는 방식은 **0 을 빼므로 아무것도
     * 고치지 못한다**(독립 검증 CP04 에서 3.2554dB 가 남았다).
     * 칸마다 걸면 두 성분이 각각 제 값으로 보정되어 **전력이 같아진다.**
     */
    @Test
    fun `밴드 안에서 CAL 이 변해도 칸마다 제대로 걸린다`() {
        val center = ThirdOctave.exactCenter(17) // 1000Hz
        // **칸 중심에서 잡는다.** binCorrectionLinear 는 칸 중심주파수에서
        // CAL 을 읽으므로, 그 사이 어딘가를 고르면 보간 때문에 값이
        // 조금 달라진다 — 처음에 그렇게 써서 0.096dB 만큼 어긋났다.
        val kLo = Math.round(center / 1.06 / binWidth).toInt()
        val kHi = Math.round(center * 1.06 / binWidth).toInt()
        val fLo = kLo * binWidth
        val fHi = kHi * binWidth

        val curve = CalibrationCurve.of(
            listOf(
                CurvePoint(20.0, 6.0),
                CurvePoint(fLo, 6.0),
                CurvePoint(center, 0.0),
                CurvePoint(fHi, -6.0),
                CurvePoint(20_000.0, -6.0),
            ),
        ).getOrThrow()

        // 전제: 두 성분이 정말 같은 밴드에 들고, CAL 이 그 안에서 변한다.
        assertEquals(17, ThirdOctave.nearestBand(fLo))
        assertEquals(17, ThirdOctave.nearestBand(fHi))
        assertEquals("아래 칸에서 +6dB", 6.0, curve.gainDbAt(fLo), 1e-9)
        assertEquals("위 칸에서 −6dB", -6.0, curve.gainDbAt(fHi), 1e-9)

        // 실제 소리는 같은 크기인데 마이크가 한쪽은 6dB 더, 다른 쪽은
        // 6dB 덜 잡았다고 하자.
        val bins = DoubleArray(binCount)
        bins[kLo] = 10.0.pow(6.0 / 10.0)
        bins[kHi] = 10.0.pow(-6.0 / 10.0)

        val out = applyReferenceCalibration(bins, analyzer, curve, fftSize, sampleRate)

        // 보정 뒤에는 두 성분이 각각 전력 1 이 되어야 한다.
        val expected = DoubleArray(binCount)
        expected[kLo] = 1.0
        expected[kHi] = 1.0
        val want = DoubleArray(ThirdOctave.BAND_COUNT)
        val wantDb = DoubleArray(ThirdOctave.BAND_COUNT)
        analyzer.toBandPower(expected, want)
        analyzer.toBandDbfs(want, wantDb)

        assertEquals(
            "칸마다 걸면 두 성분이 각각 제 값으로 돌아온다",
            wantDb[17], out.bandsDb[17], 1e-9,
        )

        // 그리고 **밴드 중심에서 빼는 방식은 이것을 못 한다** — 중심
        // 응답이 0dB 라 아무것도 빼지 않는다.
        val uncorrected = DoubleArray(ThirdOctave.BAND_COUNT)
        val uncorrectedDb = DoubleArray(ThirdOctave.BAND_COUNT)
        analyzer.toBandPower(bins, uncorrected)
        analyzer.toBandDbfs(uncorrected, uncorrectedDb)
        val centerWouldSubtract = curve.gainDbAt(center)
        assertEquals("중심 응답은 0dB 다", 0.0, centerWouldSubtract, 1e-9)
        assertTrue(
            "중심에서 빼는 방식은 오차를 남긴다: ${uncorrectedDb[17] - wantDb[17]}",
            abs(uncorrectedDb[17] - centerWouldSubtract - wantDb[17]) > 1.0,
        )
    }

    // ------------------------------------------------------------------
    // 증거
    // ------------------------------------------------------------------

    @Test
    fun `증거에 CAL 신원과 분석 설정이 담긴다`() {
        val curve = CalibrationCurve.of(
            listOf(CurvePoint(200.0, 0.0), CurvePoint(10_000.0, 0.0)),
        ).getOrThrow()
        val out = applyReferenceCalibration(
            singleBin(1000.0), analyzer, curve, fftSize, sampleRate,
            calFileName = "17860.txt", calSha256 = "abc",
        )
        assertEquals("17860.txt", out.proof.calFileName)
        assertEquals("abc", out.proof.calSha256)
        assertEquals(fftSize, out.proof.fftSize)
        assertEquals(sampleRate, out.proof.sampleRate)
        assertEquals("CAL 이 실제로 잰 범위", curve.rangeHz, out.proof.rangeHz)
    }

    @Test
    fun `같은 설정이면 같은 증거로 본다`() {
        val curve = flatCurve(0.0)
        val a = applyReferenceCalibration(
            singleBin(1000.0), analyzer, curve, fftSize, sampleRate, "c.txt", "h",
        )
        val b = applyReferenceCalibration(
            singleBin(2000.0), analyzer, curve, fftSize, sampleRate, "c.txt", "h",
        )
        assertTrue("값은 달라도 설정은 같다", a.proof.sameSetupAs(b.proof))
    }

    @Test
    fun `해시가 다르면 다른 증거다`() {
        val curve = flatCurve(0.0)
        val a = applyReferenceCalibration(
            singleBin(1000.0), analyzer, curve, fftSize, sampleRate, "c.txt", "h1",
        )
        val b = applyReferenceCalibration(
            singleBin(1000.0), analyzer, curve, fftSize, sampleRate, "c.txt", "h2",
        )
        assertFalse("이름이 같아도 내용이 다르면 다른 기준이다", a.proof.sameSetupAs(b.proof))
    }

    @Test
    fun `분석 설정이 다르면 다른 증거다`() {
        val curve = flatCurve(0.0)
        val a = applyReferenceCalibration(
            DoubleArray(binCount).also { it[100] = 1.0 }, analyzer, curve, fftSize, sampleRate,
        )
        val other = BandAnalyzer(2048, sampleRate)
        val b = applyReferenceCalibration(
            DoubleArray(2048 / 2 + 1).also { it[50] = 1.0 }, other, curve, 2048, sampleRate,
        )
        assertFalse("FFT 길이가 다르면 칸 주파수가 다르다", a.proof.sameSetupAs(b.proof))
        assertNotEquals(a.proof.fftSize, b.proof.fftSize)
    }

    /** 증거 문구는 사람이 읽는다 — **별표가 새지 않아야** 한다. */
    @Test
    fun `증거 문구에 별표가 없다`() {
        val out = applyReferenceCalibration(
            singleBin(1000.0), analyzer, flatCurve(0.0), fftSize, sampleRate, "17860.txt", "h",
        )
        assertFalse(out.proof.toString(), out.proof.toString().contains("*"))
    }

    private fun assertArrayClose(msg: String, want: DoubleArray, got: DoubleArray, eps: Double) {
        assertEquals("$msg — 길이", want.size, got.size)
        for (i in want.indices) {
            assertEquals("$msg — $i 번", want[i], got[i], eps)
        }
    }
}
