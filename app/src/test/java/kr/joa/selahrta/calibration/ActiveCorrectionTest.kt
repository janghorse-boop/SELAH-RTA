package kr.joa.selahrta.calibration

import kr.joa.selahrta.dsp.BandAnalyzer
import kr.joa.selahrta.dsp.CurvePoint
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.dsp.calibrateResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * **잰 보정을 걸면 내장 마이크가 기준을 닮는가.**
 *
 * 이 파일에서 제일 중요한 것은 **부호**다. 보정은 `기준 − 내장` 이라
 * 측정에 **더해야** 하는데, [kr.joa.selahrta.dsp.CalibrationCurve] 는
 * 점을 「응답」으로 보고 **뺀다**. 그대로 넣으면 고쳐야 할 만큼을 정확히
 * 거꾸로 밀어 놓는다 — 그러고도 곡선은 매끈하고 막대도 그럴듯하다.
 *
 * 그래서 말로 확인하지 않고 **끝에서 끝까지 숫자로** 확인한다: 내장
 * 마이크가 낸 스펙트럼에 보정을 걸어, 기준이 냈을 값과 같아지는지 본다.
 */
class ActiveCorrectionTest {

    private val fftSize = 4096
    private val rate = 48_000
    private val analyzer = BandAnalyzer(fftSize, rate)

    /**
     * 내장 마이크의 (가짜) 응답. 저역이 모자라고 고역이 솟는다 —
     * 흔한 모양이다.
     */
    private fun internalResponseDb(hz: Double): Double = when {
        hz < 100.0 -> -6.0
        hz > 5_000.0 -> 4.0
        else -> 0.0
    }

    private fun outcome() = calibrateResponse(
        referencePoints = (0 until ThirdOctave.BAND_COUNT).map {
            CurvePoint(ThirdOctave.exactCenter(it), 70.0)
        },
        internalPoints = (0 until ThirdOctave.BAND_COUNT).map {
            val hz = ThirdOctave.exactCenter(it)
            CurvePoint(hz, 70.0 + internalResponseDb(hz))
        },
    )

    /**
     * **끝에서 끝까지.** 내장 마이크가 받았을 칸 전력을 만들고, 보정을
     * 걸어 밴드로 묶은 뒤, 기준이 냈을 밴드와 견준다.
     */
    @Test
    fun `보정을 걸면 내장이 기준을 닮는다`() {
        val o = outcome()
        val curve = correctionAsCurve(o.correction).getOrThrow()
        val correctionLinear = curve.binCorrectionLinear(fftSize, rate)

        // 기준이 평탄하게 받았을 칸 전력(임의 기준값).
        val flat = 1e-8
        // 내장 마이크는 자기 응답만큼 다르게 받는다.
        val binWidth = rate.toDouble() / fftSize
        val internalPower = DoubleArray(fftSize / 2 + 1) { k ->
            val hz = if (k == 0) 1.0 else k * binWidth
            flat * 10.0.pow(internalResponseDb(hz) / 10.0)
        }

        val corrected = DoubleArray(ThirdOctave.BAND_COUNT)
        analyzer.toBandPower(internalPower, corrected, correctionLinear)
        val correctedDb = DoubleArray(ThirdOctave.BAND_COUNT)
        analyzer.toBandDbfs(corrected, correctedDb)

        val referencePower = DoubleArray(ThirdOctave.BAND_COUNT)
        analyzer.toBandPower(DoubleArray(fftSize / 2 + 1) { flat }, referencePower, null)
        val referenceDb = DoubleArray(ThirdOctave.BAND_COUNT)
        analyzer.toBandDbfs(referencePower, referenceDb)

        // 보정은 **모양**을 맞추는 것이지 절대 레벨을 맞추는 것이 아니다
        // (레벨 정규화는 따로 있다). 그래서 평균 차이를 뺀 뒤 견준다.
        val used = (0 until ThirdOctave.BAND_COUNT).filter {
            val hz = ThirdOctave.exactCenter(it)
            hz in 50.0..15_000.0 && analyzer.bandResolved[it]
        }
        val offsets = used.map { correctedDb[it] - referenceDb[it] }
        val mean = offsets.average()

        var worst = 0.0
        var worstBand = -1
        for ((i, b) in used.withIndex()) {
            val e = abs(offsets[i] - mean)
            if (e > worst) {
                worst = e
                worstBand = b
            }
        }
        assertTrue(
            "보정 뒤에도 %s 대역이 %.2fdB 어긋난다. 부호가 뒤집혔을 수 있다"
                .format(ThirdOctave.label(worstBand), worst),
            worst < 1.0,
        )
    }

    /**
     * **부호가 뒤집히면 두 배로 벌어진다.**
     *
     * 위 시험이 무엇을 잡는지 여기서 못 박는다 — 그냥 넣었을 때(뒤집지
     * 않았을 때) 실제로 나빠지는지 재어 본다.
     */
    @Test
    fun `뒤집지 않고 넣으면 오히려 두 배로 틀어진다`() {
        val o = outcome()
        val points = o.correction.hz.indices
            .filter { o.correction.valid[it] }
            .map { CurvePoint(o.correction.hz[it], o.correction.db[it]) }

        // 뒤집은 것(옳은 것)과 그냥 넣은 것.
        val right = correctionAsCurve(o.correction).getOrThrow()
        val wrong = kr.joa.selahrta.dsp.CalibrationCurve.of(points).getOrThrow()

        // 저역에서 보정은 +6dB 여야 한다(내장이 6dB 모자라므로).
        val hz = 50.0
        val rightDb = -right.gainDbAt(hz) // 실제로 더해지는 양
        val wrongDb = -wrong.gainDbAt(hz)
        assertTrue("옳은 쪽은 올려야 한다: %.2f".format(rightDb), rightDb > 3.0)
        assertTrue("뒤집힌 쪽은 내린다: %.2f".format(wrongDb), wrongDb < -3.0)
        assertEquals("정확히 반대여야 한다", rightDb, -wrongDb, 1e-9)
    }

    @Test
    fun `믿을 수 없는 점은 빼고 옮긴다`() {
        val n = ThirdOctave.BAND_COUNT
        val usable = BooleanArray(n) { ThirdOctave.exactCenter(it) in 200.0..8_000.0 }
        val o = calibrateResponse(
            referencePoints = (0 until n).map { CurvePoint(ThirdOctave.exactCenter(it), 70.0) },
            internalPoints = (0 until n).map { CurvePoint(ThirdOctave.exactCenter(it), 64.0) },
            referenceValid = usable,
            internalValid = usable,
        )
        val curve = correctionAsCurve(o.correction).getOrThrow()
        assertTrue("범위가 줄어야 한다: ${curve.lowestHz}", curve.lowestHz >= 150.0)
        assertTrue("범위가 줄어야 한다: ${curve.highestHz}", curve.highestHz <= 9_000.0)
    }

    @Test
    fun `믿을 수 있는 점이 없으면 걸지 않는다`() {
        val n = ThirdOctave.BAND_COUNT
        val none = BooleanArray(n) { false }
        val o = calibrateResponse(
            referencePoints = (0 until n).map { CurvePoint(ThirdOctave.exactCenter(it), 70.0) },
            internalPoints = (0 until n).map { CurvePoint(ThirdOctave.exactCenter(it), 64.0) },
            referenceValid = none,
            internalValid = none,
        )
        assertTrue(correctionAsCurve(o.correction).isFailure)
    }

    // ------------------------------------------------------------------
    // 어느 것을 거는가 — 둘을 함께 걸지 않는다
    // ------------------------------------------------------------------

    private fun importedCurve(enabled: Boolean = true) = ActiveCurve(
        curve = kr.joa.selahrta.dsp.CalibrationCurve.of(
            listOf(CurvePoint(20.0, 1.0), CurvePoint(20_000.0, 1.0)),
        ).getOrThrow(),
        fileName = "mic.cal",
        pointCount = 2,
        importedAtEpochMs = 0L,
        enabled = enabled,
    )

    @Test
    fun `걸 만한 프로파일이 있으면 그것이 이긴다`() {
        val now = testEnvironment(address = "bottom")
        val c = chooseCorrection(
            imported = importedCurve(),
            profile = testProfile(address = "bottom"),
            profileCurve = outcome().correction,
            now = now,
        )
        assertTrue("$c", c is ActiveCorrection.FromProfile)
        assertTrue(c.labelKo, c.labelKo.contains("잰 프로파일"))
    }

    /** **둘을 함께 걸지 않는다.** 건 것은 언제나 하나다. */
    @Test
    fun `건 것은 언제나 하나다`() {
        val now = testEnvironment(address = "bottom")
        val c = chooseCorrection(importedCurve(), testProfile(address = "bottom"), outcome().correction, now)
        // 프로파일이 이겼으면 가져온 파일 이름은 어디에도 없어야 한다.
        assertFalse(c.labelKo, c.labelKo.contains("mic.cal"))
        assertTrue(c.curveOrNull != null)
    }

    @Test
    fun `경로가 다르면 가져온 파일 차례다`() {
        val c = chooseCorrection(
            imported = importedCurve(),
            profile = testProfile(address = "back"),
            profileCurve = outcome().correction,
            now = testEnvironment(address = "bottom"),
        )
        assertTrue("$c", c is ActiveCorrection.FromFile)
    }

    @Test
    fun `꺼 둔 프로파일은 걸지 않는다`() {
        val c = chooseCorrection(
            imported = importedCurve(),
            profile = testProfile(address = "bottom", enabled = false),
            profileCurve = outcome().correction,
            now = testEnvironment(address = "bottom"),
        )
        assertTrue("$c", c is ActiveCorrection.FromFile)
    }

    @Test
    fun `꺼 둔 파일도 걸지 않는다`() {
        val c = chooseCorrection(importedCurve(enabled = false), null, null, null)
        assertTrue("$c", c is ActiveCorrection.None)
        assertTrue(c.curveOrNull == null)
    }

    @Test
    fun `아무것도 없으면 걸지 않는다`() {
        assertTrue(chooseCorrection(null, null, null, null) is ActiveCorrection.None)
    }

    /** 열린 경로를 모르면 프로파일을 걸 수 없다 — 견줄 것이 없다. */
    @Test
    fun `열린 경로를 모르면 프로파일을 안 건다`() {
        val c = chooseCorrection(importedCurve(), testProfile(), outcome().correction, null)
        assertTrue("$c", c is ActiveCorrection.FromFile)
    }

    @Test
    fun `화면 문구에 마크다운이 없다`() {
        val all = listOf(
            ActiveCorrection.None,
            chooseCorrection(importedCurve(), null, null, null),
            chooseCorrection(
                null, testProfile(address = "bottom"), outcome().correction,
                testEnvironment(address = "bottom"),
            ),
        )
        all.forEach {
            assertFalse("별표가 있다: ${it.labelKo}", it.labelKo.contains("*"))
        }
    }

    /** 밴드 dB 로 옮길 때 쓰는 도우미. 시험이 읽기 쉬우라고 둔다. */
    private fun powerToDb(p: Double) = 10.0 * log10(p)
}

/** 여럿 중 어느 것을 거는가. */
class PickApplicableTest {

    private val now = testEnvironment(address = "bottom")

    @Test
    fun `걸 수 있는 것이 없으면 안 고른다`() {
        assertEquals(null, pickApplicable(emptyList(), now))
        assertEquals(
            null,
            pickApplicable(listOf(testProfile(address = "back")), now),
        )
    }

    /** 마이크도 방도 변한다. 나중에 잰 것이 지금에 가깝다. */
    @Test
    fun `여럿이면 가장 최근 것`() {
        val old = testProfile(id = "a", address = "bottom", createdAt = 1_000L)
        val recent = testProfile(id = "b", address = "bottom", createdAt = 9_000L)
        assertEquals("b", pickApplicable(listOf(old, recent), now)?.id)
        assertEquals("b", pickApplicable(listOf(recent, old), now)?.id)
    }

    /**
     * **걸 수 없는 것은 아예 고르지 않는다.**
     *
     * 「골라 두고 나중에 막는다」로 두면, 막는 곳을 한 군데라도 빠뜨렸을
     * 때 엉뚱한 프로파일이 조용히 걸린다.
     */
    @Test
    fun `꺼 둔 것은 더 최근이어도 안 고른다`() {
        val on = testProfile(id = "on", address = "bottom", createdAt = 1_000L)
        val offNewer = testProfile(
            id = "off", address = "bottom", createdAt = 9_000L, enabled = false,
        )
        assertEquals("on", pickApplicable(listOf(on, offNewer), now)?.id)
    }

    @Test
    fun `열린 경로를 모르면 안 고른다`() {
        assertEquals(null, pickApplicable(listOf(testProfile(address = "bottom")), null))
    }
}
