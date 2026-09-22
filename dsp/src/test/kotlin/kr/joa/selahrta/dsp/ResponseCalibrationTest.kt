package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * **알려진 합성 응답으로 보정 계산을 검증한다**
 * (S23 개별 교정 지시서 DoD 7.3).
 *
 * > 알려진 합성 응답으로 EMM-6 CAL 적용, 보정 부호, 로그 주파수 보간,
 * > 레벨 정규화, smoothing, 보정 상한, 한 번만 적용되는 처리 순서를
 * > 검증한다.
 *
 * 답을 아는 자료로 재는 것이 요점이다. 실기기 그래프는 **틀려도
 * 그럴듯해 보인다** — 무엇이 맞는지 모르면 틀린 것을 못 본다.
 */
class ResponseCalibrationTest {

    /** 평탄한 기준 마이크. 20Hz~20kHz 에서 0dB. */
    private val flatReference = listOf(
        CurvePoint(20.0, 0.0),
        CurvePoint(1000.0, 0.0),
        CurvePoint(20000.0, 0.0),
    )

    private fun at(curve: ResponseCurve, hz: Double): Double {
        val i = curve.hz.indices.minByOrNull { abs(curve.hz[it] - hz) }!!
        return curve.db[i]
    }

    private fun validAt(curve: ResponseCurve, hz: Double): Boolean {
        val i = curve.hz.indices.minByOrNull { abs(curve.hz[it] - hz) }!!
        return curve.valid[i]
    }

    // ------------------------------------------------------------------
    // 축과 보간
    // ------------------------------------------------------------------

    @Test
    fun `로그 축은 옥타브마다 같은 수의 점을 둔다`() {
        val axis = logAxis(100.0, 1600.0, pointsPerOctave = 12)
        // 100 → 1600 은 4옥타브. 12×4 + 1 = 49점.
        assertEquals(49, axis.size)
        assertEquals(100.0, axis.first(), 1e-9)
        assertEquals(1600.0, axis.last(), 1e-6)
        // 이웃 간 비율이 일정해야 한다.
        val r0 = axis[1] / axis[0]
        val r1 = axis[30] / axis[29]
        assertEquals("로그 축이 아니다", r0, r1, 1e-12)
    }

    /** **주파수는 로그로 보간한다.** 선형으로 하면 저역이 크게 어긋난다. */
    @Test
    fun `로그 축에서 보간한다`() {
        val axis = logAxis(100.0, 1000.0, 12)
        val c = interpolateToAxis(
            listOf(CurvePoint(100.0, 0.0), CurvePoint(1000.0, 10.0)),
            axis,
        )
        // 100 과 1000 의 로그 가운데는 316.2Hz. 거기서 5dB 여야 한다.
        assertEquals(5.0, at(c, 316.2), 0.1)
        // 선형 가운데(550Hz)에서 5dB 이면 선형 보간을 한 것이다.
        assertTrue("550Hz 가 5dB 이면 선형 보간이다", at(c, 550.0) > 6.5)
    }

    /** **잰 범위 밖은 지어내지 않는다.** */
    @Test
    fun `범위 밖은 믿지 않는다`() {
        val axis = logAxis(20.0, 20000.0, 12)
        val c = interpolateToAxis(
            listOf(CurvePoint(100.0, -3.0), CurvePoint(10000.0, 3.0)),
            axis,
        )
        assertFalse("100Hz 아래는 잰 적이 없다", validAt(c, 50.0))
        assertFalse("10kHz 위는 잰 적이 없다", validAt(c, 16000.0))
        assertTrue(validAt(c, 1000.0))
    }

    // ------------------------------------------------------------------
    // 레벨 정규화 — 지시서 3.4·5장
    // ------------------------------------------------------------------

    /**
     * **이득 차이가 보정값이 되면 안 된다.**
     *
     * 모양이 똑같고 높이만 12dB 다른 두 곡선은 **보정이 0** 이어야 한다.
     * 정규화를 빠뜨리면 전 대역에 12dB 가 걸린다.
     */
    @Test
    fun `높이만 다르면 보정은 0 이다`() {
        val shape = listOf(
            CurvePoint(100.0, -4.0),
            CurvePoint(1000.0, 0.0),
            CurvePoint(10000.0, 5.0),
        )
        val louder = shape.map { CurvePoint(it.hz, it.gainDb + 12.0) }

        val out = calibrateResponse(shape, louder)

        for (hz in listOf(125.0, 500.0, 1000.0, 4000.0, 8000.0)) {
            assertEquals("${hz}Hz 에서 보정이 0 이어야 한다", 0.0, at(out.correction, hz), 0.05)
        }
        println("NORMALIZE offset=${"%.2f".format(out.internalNormalized.offsetDb)}dB")
    }

    /** **오프셋을 숨기지 않는다.** 화면이 수치로 보여야 한다(지시서 4장). */
    @Test
    fun `쓴 오프셋과 대역을 남긴다`() {
        val axis = logAxis(20.0, 20000.0, 12)
        val c = interpolateToAxis(listOf(CurvePoint(20.0, 7.0), CurvePoint(20000.0, 7.0)), axis)
        val n = normalizeToBand(c, 300.0, 3000.0)
        assertEquals(-7.0, n.offsetDb, 1e-9)
        assertEquals(300.0, n.bandLowHz, 0.0)
        assertEquals(3000.0, n.bandHighHz, 0.0)
        assertTrue("몇 점을 썼는지 남겨야 한다", n.pointsUsed > 0)
    }

    /** 대역에 믿을 점이 없으면 **건드리지 않는다.** 0 으로 밀면 통째로 어긋난다. */
    @Test
    fun `정규화 대역이 비면 건드리지 않는다`() {
        val axis = logAxis(20.0, 20000.0, 12)
        // 5kHz 위에서만 잰 곡선. 300~3000Hz 에는 믿을 점이 없다.
        val c = interpolateToAxis(
            listOf(CurvePoint(5000.0, 4.0), CurvePoint(15000.0, 4.0)),
            axis,
        )
        val n = normalizeToBand(c, 300.0, 3000.0)
        assertEquals(0.0, n.offsetDb, 0.0)
        assertEquals(0, n.pointsUsed)
    }

    // ------------------------------------------------------------------
    // 보정 부호 — 가장 조용히 틀리는 자리
    // ------------------------------------------------------------------

    /**
     * **부호가 맞는가.**
     *
     * 대상 마이크가 8kHz 에서 기준보다 **6dB 덜 잡으면**, 보정은
     * **+6dB** 여야 한다. 반대로 걸면 12dB 가 벌어진다.
     */
    @Test
    fun `덜 잡는 대역은 올려 준다`() {
        // **기울기가 아니라 평탄한 자리에서 재다.**
        //
        // 처음에는 1kHz→8kHz 로 비스듬하게 내려가는 곡선을 넣고
        // 8kHz 에서 +6dB 를 기대했는데 5.23 이 나왔다. **내 기대값이
        // 틀렸다** — 정규화 대역(300~3000Hz)에서 그 곡선이 이미
        // 내려가고 있어 오프셋이 붙고, 1/6옥타브 평활이 8kHz 의
        // 무릎을 가로질렀다. 파이프라인이 맞고 내가 둘을 빼먹었다.
        //
        // 그래서 **정규화 대역은 평탄하게, 재는 자리도 평탄한 한가운데**로
        // 고쳐 기대값이 의심할 여지 없게 만든다.
        val internal = listOf(
            CurvePoint(20.0, 0.0),
            CurvePoint(3000.0, 0.0),
            CurvePoint(5000.0, -6.0),
            CurvePoint(20000.0, -6.0),
        )
        val out = calibrateResponse(flatReference, internal)
        assertEquals("8kHz 에서 +6dB 여야 한다", 6.0, at(out.correction, 8000.0), 0.5)
        assertEquals("1kHz 는 그대로", 0.0, at(out.correction, 1000.0), 0.3)
    }

    @Test
    fun `더 잡는 대역은 내려 준다`() {
        // 위와 같은 이유로 평탄한 구간을 고른다 — 정규화 대역은
        // 0dB, 재는 자리(100Hz)는 +5dB 평지 한가운데다.
        val internal = listOf(
            CurvePoint(20.0, 5.0),
            CurvePoint(150.0, 5.0),
            CurvePoint(300.0, 0.0),
            CurvePoint(20000.0, 0.0),
        )
        val out = calibrateResponse(flatReference, internal)
        assertEquals("100Hz 에서 −5dB 여야 한다", -5.0, at(out.correction, 100.0), 0.5)
    }

    /**
     * **보정을 걸면 기준에 가까워진다.** 이것이 전체가 맞물렸는지 보는
     * 한 줄짜리 확인이다.
     */
    @Test
    fun `보정을 걸면 기준에 붙는다`() {
        val internal = listOf(
            CurvePoint(20.0, 6.0),
            CurvePoint(200.0, 3.0),
            CurvePoint(1000.0, 0.0),
            CurvePoint(5000.0, -4.0),
            CurvePoint(20000.0, -8.0),
        )
        val out = calibrateResponse(flatReference, internal)

        var beforeMax = 0.0
        var afterMax = 0.0
        for (i in out.corrected.hz.indices) {
            if (!out.corrected.valid[i]) continue
            beforeMax = maxOf(beforeMax, abs(out.internalNormalized.curve.db[i] - out.reference.db[i]))
            afterMax = maxOf(afterMax, abs(out.corrected.db[i] - out.reference.db[i]))
        }
        println("FIT before=${"%.2f".format(beforeMax)}dB after=${"%.2f".format(afterMax)}dB")
        assertTrue("보정 전에는 벌어져 있어야 시험이 뜻을 갖는다", beforeMax > 4.0)
        assertTrue("보정 뒤에는 붙어야 한다 (before=$beforeMax after=$afterMax)", afterMax < 1.0)
    }

    // ------------------------------------------------------------------
    // 평활과 상한
    // ------------------------------------------------------------------

    /** **좁은 딥은 평활에 녹는다.** 마이크를 1cm 옮기면 사라질 것들이다. */
    @Test
    fun `좁은 딥은 평활이 깎는다`() {
        val axis = logAxis(20.0, 20000.0, 24)
        val db = DoubleArray(axis.size)
        // 1kHz 근처 한 점만 −20dB.
        val spike = axis.indices.minByOrNull { abs(axis[it] - 1000.0) }!!
        db[spike] = -20.0
        val c = ResponseCurve(axis, db, BooleanArray(axis.size) { true })

        val smoothed = smoothFractionalOctave(c, fraction = 3.0)
        println("SMOOTH 딥 ${db[spike]} → ${"%.2f".format(smoothed.db[spike])}")
        assertTrue("깎여야 한다", abs(smoothed.db[spike]) < 10.0)
        assertTrue("아주 없어지지는 않는다", abs(smoothed.db[spike]) > 0.5)
    }

    /**
     * **상한을 넘으면 자르되 믿을 수 없다고 표시한다.**
     *
     * 조용히 자르면 화면이 「여기는 12dB 만 고치면 된다」고 말하게 된다.
     */
    @Test
    fun `상한을 넘으면 자르고 표시한다`() {
        val axis = logAxis(20.0, 20000.0, 12)
        val db = DoubleArray(axis.size) { 20.0 }
        val c = ResponseCurve(axis, db, BooleanArray(axis.size) { true })

        val limited = limitCorrection(c, maxAbsDb = 12.0)
        assertEquals(12.0, limited.db[0], 1e-9)
        assertFalse("자른 자리는 믿을 수 없다고 적어야 한다", limited.valid[0])
        assertEquals("믿을 수 있는 점이 하나도 없어야 한다", 0, limited.validCount)
    }

    @Test
    fun `상한 안이면 그대로 둔다`() {
        val axis = logAxis(20.0, 20000.0, 12)
        val c = ResponseCurve(axis, DoubleArray(axis.size) { 3.0 }, BooleanArray(axis.size) { true })
        val limited = limitCorrection(c, maxAbsDb = 12.0)
        assertEquals(3.0, limited.db[0], 1e-9)
        assertTrue(limited.valid[0])
    }

    // ------------------------------------------------------------------
    // 한쪽이라도 모르면 보정하지 않는다
    // ------------------------------------------------------------------

    @Test
    fun `한쪽이라도 범위 밖이면 보정하지 않는다`() {
        // 기준은 100Hz~10kHz 만, 대상은 20Hz~20kHz 전부.
        val out = calibrateResponse(
            listOf(CurvePoint(100.0, 0.0), CurvePoint(10000.0, 0.0)),
            listOf(CurvePoint(20.0, 0.0), CurvePoint(20000.0, 0.0)),
        )
        assertFalse("기준을 모르는 대역은 보정할 수 없다", validAt(out.correction, 50.0))
        assertFalse(validAt(out.correction, 16000.0))
        assertTrue(validAt(out.correction, 1000.0))
    }

    // ------------------------------------------------------------------
    // 중간 곡선을 버리지 않는다 — 지시서 4장
    // ------------------------------------------------------------------

    /**
     * 비교 화면이 요구하는 **네 곡선**이 모두 나와야 한다 — 기준, 원시,
     * 정규화, 보정 후. 중간 것을 버리면 무엇이 일어났는지 볼 수 없다.
     */
    @Test
    fun `네 곡선을 모두 남긴다`() {
        val out = calibrateResponse(
            flatReference,
            listOf(CurvePoint(20.0, 9.0), CurvePoint(1000.0, 9.0), CurvePoint(20000.0, 3.0)),
        )
        assertNotNull(out.reference)
        assertNotNull(out.internalRaw)
        assertNotNull(out.internalNormalized.curve)
        assertNotNull(out.corrected)
        assertNotNull(out.correction)

        // **원시는 정규화 전이다.** 둘이 같으면 하나를 잃은 것이다.
        assertTrue(
            "원시와 정규화가 같다 — 오프셋 ${out.internalNormalized.offsetDb}",
            abs(out.internalRaw.db[0] - out.internalNormalized.curve.db[0]) > 0.5,
        )
        // 설정도 함께 남는다(지시서 3.4 마지막 줄).
        assertEquals(12.0, out.settings.maxCorrectionDb, 0.0)
        assertEquals(6.0, out.settings.smoothingFraction, 0.0)
    }

    @Test
    fun `말이 안 되는 요청은 막는다`() {
        assertNotNull(runCatching { logAxis(0.0, 1000.0) }.exceptionOrNull())
        assertNotNull(runCatching { logAxis(1000.0, 100.0) }.exceptionOrNull())
        assertNotNull(runCatching { logAxis(20.0, 20000.0, 0) }.exceptionOrNull())
        assertNotNull(
            runCatching { interpolateToAxis(listOf(CurvePoint(1.0, 0.0)), logAxis(20.0, 100.0)) }
                .exceptionOrNull(),
        )
        assertNotNull(
            runCatching {
                val a = logAxis(20.0, 100.0)
                val b = logAxis(20.0, 200.0)
                correctionCurve(
                    ResponseCurve(a, DoubleArray(a.size), BooleanArray(a.size)),
                    ResponseCurve(b, DoubleArray(b.size), BooleanArray(b.size)),
                )
            }.exceptionOrNull(),
        )
    }
}
