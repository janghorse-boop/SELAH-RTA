package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 명세 15장 「Calibration interpolation」. */
class CalibrationCurveTest {

    private fun curve(vararg p: Pair<Double, Double>) =
        CalibrationCurve.of(p.map { CurvePoint(it.first, it.second) }).getOrThrow()

    @Test
    fun `점 위에서는 그 값이 그대로 나온다`() {
        val c = curve(100.0 to -2.0, 1000.0 to 0.0, 10000.0 to 3.0)
        assertEquals(-2.0, c.gainDbAt(100.0), 1e-12)
        assertEquals(0.0, c.gainDbAt(1000.0), 1e-12)
        assertEquals(3.0, c.gainDbAt(10000.0), 1e-12)
    }

    @Test
    fun `주파수를 로그로 보간한다 — 선형으로 하면 저역에서 크게 어긋난다`() {
        val c = curve(100.0 to 0.0, 10000.0 to 20.0)
        // 100Hz 와 10kHz 의 로그 가운데는 1000Hz 다. 거기서 딱 절반이어야 한다.
        assertEquals(10.0, c.gainDbAt(1000.0), 1e-9)
        // 선형 보간이었다면 5050Hz 가 절반이 되고, 1000Hz 는 1.8dB 였을 것이다.
        val linearWouldGive = (1000.0 - 100.0) / (10000.0 - 100.0) * 20.0
        assertEquals(1.818, linearWouldGive, 0.01)
        assertTrue("로그 보간이 선형과 확실히 달라야 한다", c.gainDbAt(1000.0) - linearWouldGive > 5.0)
    }

    @Test
    fun `곡선 바깥은 끝점 값을 유지한다 — 밖으로 추정하지 않는다`() {
        val c = curve(50.0 to -3.0, 5000.0 to 2.0)
        assertEquals("아래로 내려가도 끝점", -3.0, c.gainDbAt(10.0), 1e-12)
        assertEquals("위로 올라가도 끝점", 2.0, c.gainDbAt(20000.0), 1e-12)
        // 이어서 추정했다면 20kHz 에서 훨씬 큰 값이 나왔을 것이다.
        assertTrue(c.gainDbAt(20000.0) < 3.0)
    }

    @Test
    fun `정렬되지 않은 파일도 받아들인다`() {
        val c = CalibrationCurve.of(
            listOf(CurvePoint(1000.0, 0.0), CurvePoint(100.0, -5.0), CurvePoint(10000.0, 5.0)),
        ).getOrThrow()
        assertEquals(100.0, c.lowestHz, 0.0)
        assertEquals(10000.0, c.highestHz, 0.0)
        assertEquals(-5.0, c.gainDbAt(100.0), 1e-12)
    }

    @Test
    fun `쓸 수 없는 점을 걸러낸다`() {
        val r = CalibrationCurve.of(
            listOf(
                CurvePoint(0.0, 1.0),                    // 주파수 0
                CurvePoint(-100.0, 1.0),                 // 음수
                CurvePoint(Double.NaN, 1.0),             // NaN
                CurvePoint(100.0, Double.POSITIVE_INFINITY), // 무한
                CurvePoint(100.0, -1.0),
                CurvePoint(1000.0, 0.0),
            ),
        )
        val c = r.getOrThrow()
        assertEquals(2, c.points.size)
    }

    @Test
    fun `점이 하나뿐이면 곡선을 만들 수 없다`() {
        val r = CalibrationCurve.of(listOf(CurvePoint(1000.0, 0.0)))
        assertTrue(r.isFailure)
        assertNotNull(r.exceptionOrNull()?.message)
    }

    @Test
    fun `밴드 보정값은 평탄한 곡선에서 그 값이다`() {
        val flat = curve(20.0 to -2.5, 20000.0 to -2.5)
        for (g in flat.bandCenterResponseDb()) assertEquals(-2.5, g, 1e-9)
    }

    /**
     * 예전에는 여기서 「밴드 평균이 중심 한 점과 달라야 한다」를 확인했다.
     * 그 평균을 빼는 것이 보정 경로였는데 그것이 R05 의 결함이었다 —
     * 실제 보정은 칸마다 건다([CurveCorrectionTest]).
     */
    @Test
    fun `밴드 보정값은 중심 주파수의 응답을 그대로 준다`() {
        val steep = curve(891.0 to 0.0, 1000.0 to 0.0, 1123.0 to 12.0)
        val gains = steep.bandCenterResponseDb()
        for (b in 0 until ThirdOctave.BAND_COUNT) {
            assertEquals(steep.gainDbAt(ThirdOctave.exactCenter(b)), gains[b], 1e-12)
        }
    }

    @Test
    fun `곡선이 덮지 않는 밴드를 알려준다`() {
        val c = curve(200.0 to 0.0, 5000.0 to 0.0)
        val covered = c.bandCovered()
        assertFalse("20Hz 는 곡선 밖이다", covered[0])
        assertTrue("1kHz 는 안에 있다", covered[17])
        assertFalse("20kHz 는 곡선 밖이다", covered[30])
    }

    @Test
    fun `가장 큰 보정량을 알려준다`() {
        val c = curve(100.0 to -8.0, 1000.0 to 0.0, 10000.0 to 3.0)
        assertEquals(8.0, c.maxAbsGainDb, 1e-12)
    }

    @Test
    fun `0 이하 주파수를 물으면 거부한다`() {
        val c = curve(100.0 to 0.0, 1000.0 to 0.0)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { c.gainDbAt(0.0) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { c.gainDbAt(-1.0) }
    }

    // ---- 파일 파싱 ----

    @Test
    fun `공백으로 나뉜 세 열짜리 파일을 읽는다`() {
        val text = """
            * Dayton Audio iMM-6C calibration
            * Serial 123456
            20.000   -1.23   0.0
            1000.000  0.00   0.0
            20000.000 2.50   0.0
        """.trimIndent()
        val r = CalibrationFile.load(text).getOrThrow()
        assertEquals(3, r.pointCount)
        assertEquals(-1.23, r.curve.gainDbAt(20.0), 1e-9)
        assertEquals(2.50, r.curve.gainDbAt(20000.0), 1e-9)
    }

    @Test
    fun `쉼표로 나뉜 두 열짜리 파일도 읽는다`() {
        val text = "20,-1.5\n1000,0\n20000,3.2"
        val r = CalibrationFile.load(text).getOrThrow()
        assertEquals(3, r.pointCount)
        assertEquals(0.0, r.curve.gainDbAt(1000.0), 1e-9)
    }

    @Test
    fun `따옴표 머리글과 여러 주석 기호를 건너뛴다`() {
        val text = """
            # hash comment
            ; semicolon comment
            // slash comment
            "Frequency","SPL","Phase"
            100 -2 0
            1000 0 0
        """.trimIndent()
        val r = CalibrationFile.load(text).getOrThrow()
        assertEquals(2, r.pointCount)
        // 머리글 줄은 숫자가 아니라 건너뛴 것으로 센다.
        assertTrue("건너뛴 줄을 세야 한다", r.skippedLines >= 1)
    }

    @Test
    fun `숫자가 없는 파일은 왜 안 되는지 말한다`() {
        val r = CalibrationFile.load("hello\nworld\n")
        assertTrue(r.isFailure)
        val msg = r.exceptionOrNull()!!.message!!
        assertTrue("형식을 알려줘야 한다", msg.contains("주파수"))
        assertTrue("실제 줄을 보여줘야 한다", msg.contains("hello"))
    }

    @Test
    fun `보정량이 지나치게 크면 수상하다고 알린다`() {
        val text = "20 -45\n1000 0\n20000 40"
        val r = CalibrationFile.load(text).getOrThrow()
        assertNotNull(r.warningKo)
        assertTrue(r.warningKo!!.contains("부호"))
    }

    @Test
    fun `대역이 좁으면 늘여 쓴다고 알린다`() {
        val low = CalibrationFile.load("200 0\n5000 0").getOrThrow()
        assertNotNull(low.warningKo)
        assertTrue(low.warningKo!!.contains("200Hz"))

        val high = CalibrationFile.load("20 0\n5000 0").getOrThrow()
        assertNotNull(high.warningKo)
        assertTrue(high.warningKo!!.contains("kHz"))
    }

    @Test
    fun `정상 범위 파일에는 경고가 없다`() {
        val text = (0..40).joinToString("\n") { i ->
            val hz = 20.0 * Math.pow(1000.0, i / 40.0)
            "%.1f %.2f".format(hz, -2.0 + i * 0.1)
        }
        val r = CalibrationFile.load(text).getOrThrow()
        assertNull(r.warningKo)
        assertEquals(41, r.pointCount)
    }

    @Test
    fun `BOM 과 빈 줄을 견딘다`() {
        val text = "﻿\n\n  100  -1  \n\n 1000 0 \n\n"
        val r = CalibrationFile.load(text).getOrThrow()
        assertEquals(2, r.pointCount)
    }

    @Test
    fun `위상 열이 있어도 크기만 쓴다`() {
        // 셋째 열을 dB 로 잘못 읽으면 곡선이 통째로 엉뚱해진다.
        val r = CalibrationFile.load("100 -2 45.5\n1000 0 -30.2").getOrThrow()
        assertEquals(-2.0, r.curve.gainDbAt(100.0), 1e-9)
        assertEquals(0.0, r.curve.gainDbAt(1000.0), 1e-9)
    }
}
