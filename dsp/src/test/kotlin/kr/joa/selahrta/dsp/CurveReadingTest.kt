package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **둘째 열이 응답인가 보정값인가**(독립 검토 R04).
 *
 * 검토자의 말 그대로다:
 *
 * > 실제로 +2dB를 더하라는 correction 값을 응답으로 해석해 -2dB를
 * > 적용하면 의도한 결과와 4dB 차이가 난다.
 *
 * 4dB 는 작지 않다. 그런데 **곡선은 멀쩡해 보인다** — 기존 경고(±30dB
 * 초과)로는 잡히지 않는다. 측정 마이크 파일은 대개 ±5dB 안쪽이라
 * 부호가 반대여도 조용히 통과한다.
 *
 * 검토자가 제안한 표를 그대로 시험한다: response +2, correction +2,
 * 무헤더, 모순 헤더, 불완전한 범위.
 */
class CurveReadingTest {

    private fun points(db: Double) = listOf(
        CurvePoint(100.0, db),
        CurvePoint(1_000.0, db),
        CurvePoint(10_000.0, db),
    )

    // ------------------------------------------------------------------
    // 읽는 법이 값에 실제로 반영되는가
    // ------------------------------------------------------------------

    @Test
    fun `응답으로 읽으면 적힌 값 그대로다`() {
        val c = CalibrationCurve.of(points(2.0), CurveReading.Response).getOrThrow()
        assertEquals(2.0, c.gainDbAt(1_000.0), 1e-12)
    }

    /** 보정값은 **이미 뒤집힌 값**이라, 응답 규약으로 옮기면 부호가 바뀐다. */
    @Test
    fun `보정값으로 읽으면 뒤집힌다`() {
        val c = CalibrationCurve.of(points(2.0), CurveReading.Correction).getOrThrow()
        assertEquals(-2.0, c.gainDbAt(1_000.0), 1e-12)
    }

    /**
     * **검토자가 말한 4dB 가 실제로 4dB 인지 잰다.**
     *
     * 말로만 적어 두면 다음 사람이 「얼마나 큰 일인지」를 모른다.
     */
    @Test
    fun `잘못 읽으면 두 배로 어긋난다`() {
        val asResponse = CalibrationCurve.of(points(2.0), CurveReading.Response).getOrThrow()
        val asCorrection = CalibrationCurve.of(points(2.0), CurveReading.Correction).getOrThrow()
        val gap = asResponse.gainDbAt(1_000.0) - asCorrection.gainDbAt(1_000.0)
        assertEquals("적힌 값의 두 배만큼 벌어진다", 4.0, gap, 1e-12)
    }

    /** 칸 보정 계수까지 따라가는가 — 화면만 바뀌고 계산이 그대로면 소용없다. */
    @Test
    fun `칸 보정 계수도 따라 뒤집힌다`() {
        val resp = CalibrationCurve.of(points(6.0), CurveReading.Response).getOrThrow()
        val corr = CalibrationCurve.of(points(6.0), CurveReading.Correction).getOrThrow()
        val k = 1_000 * 1024 / 48_000 // 1kHz 근처 칸
        val a = resp.binCorrectionLinear(1024, 48_000)[k]
        val b = corr.binCorrectionLinear(1024, 48_000)[k]
        assertTrue("응답 6dB 은 깎는다", a < 1.0)
        assertTrue("보정값 6dB 은 올린다", b > 1.0)
        assertEquals("서로 역수여야 한다", 1.0, a * b, 1e-9)
    }

    @Test
    fun `적힌 값 그대로도 남는다`() {
        val c = CalibrationCurve.of(points(2.0), CurveReading.Correction).getOrThrow()
        assertEquals("파일에 적힌 값", 2.0, c.rawPoints[1].gainDb, 1e-12)
        assertEquals("응답으로 옮긴 값", -2.0, c.points[1].gainDb, 1e-12)
        assertEquals(CurveReading.Correction, c.reading)
    }

    @Test
    fun `읽는 법을 바꿔도 점이 달라지지 않는다`() {
        val c = CalibrationCurve.of(points(2.0), CurveReading.Response).getOrThrow()
        val flipped = c.withReading(CurveReading.Correction)
        assertEquals(c.rawPoints, flipped.rawPoints)
        assertNotEquals(c.points, flipped.points)
        assertEquals(-2.0, flipped.gainDbAt(1_000.0), 1e-12)
        assertEquals("되돌리면 제자리", 2.0, flipped.withReading(CurveReading.Response).gainDbAt(1_000.0), 1e-12)
    }

    @Test
    fun `같은 읽는 법이면 그대로 돌려준다`() {
        val c = CalibrationCurve.of(points(2.0), CurveReading.Response).getOrThrow()
        assertTrue(c === c.withReading(CurveReading.Response))
    }

    /** 기본값은 지금까지의 동작과 같아야 한다 — 말없이 바뀌면 안 된다. */
    @Test
    fun `기본은 응답이다`() {
        assertEquals(CurveReading.Response, CalibrationCurve.of(points(1.0)).getOrThrow().reading)
    }

    // ------------------------------------------------------------------
    // 언제 사람에게 묻는가
    // ------------------------------------------------------------------

    @Test
    fun `머리글이 응답이면 묻지 않는다`() {
        for (s in ReadingStakes.entries) {
            val d = decideReading(SignEvidence.LooksLikeResponse, s)
            assertTrue("$s: $d", d.settled)
            assertEquals(CurveReading.Response, d.reading)
        }
    }

    /**
     * **어긋나는 것은 모르는 것과 다르다.**
     *
     * 머리글이 우리 가정과 반대를 가리키면 용도와 상관없이 묻는다.
     */
    @Test
    fun `머리글이 보정값이면 용도와 상관없이 묻는다`() {
        for (s in ReadingStakes.entries) {
            val d = decideReading(SignEvidence.LooksLikeCorrection, s)
            assertFalse("$s: $d", d.settled)
            assertEquals("제안은 보정값 쪽", CurveReading.Correction, d.reading)
        }
    }

    /**
     * 단서가 없는 파일이 **대부분이다.** 전부 막으면 아무 파일도 못 쓰고,
     * 전부 통과시키면 기준이 뒤집힌 채로 굳는다. 걸린 것의 크기로 가른다.
     */
    @Test
    fun `단서가 없으면 표시용은 관례로 간다`() {
        val d = decideReading(SignEvidence.Unknown, ReadingStakes.DisplayCurve)
        assertTrue("$d", d.settled)
        assertEquals(CurveReading.Response, d.reading)
        assertTrue(d.whyKo, d.whyKo.contains("관례"))
    }

    @Test
    fun `단서가 없으면 교정 기준은 묻는다`() {
        val d = decideReading(SignEvidence.Unknown, ReadingStakes.ReferenceForCalibration)
        assertFalse("$d", d.settled)
        assertTrue(d.whyKo, d.whyKo.contains("기준"))
    }

    /** 모순된 머리글은 「모름」이다 — 거기서 억지로 고르지 않는다. */
    @Test
    fun `모순된 머리글은 모름으로 본다`() {
        val e = signEvidenceOf(listOf("Correction derived from measured response"))
        assertEquals(SignEvidence.Unknown, e)
        assertFalse(decideReading(e, ReadingStakes.ReferenceForCalibration).settled)
    }

    // ------------------------------------------------------------------
    // 문구
    // ------------------------------------------------------------------

    @Test
    fun `사람에게 보이는 말에 마크다운이 없다`() {
        val all = CurveReading.entries.flatMap { listOf(it.labelKo, it.explainKo) } +
            SignEvidence.entries.flatMap { e ->
                ReadingStakes.entries.map { decideReading(e, it).whyKo }
            }
        all.forEach {
            assertFalse("별표가 있다: $it", it.contains("*"))
            assertFalse("백틱이 있다: $it", it.contains("`"))
        }
    }

    @Test
    fun `묻는 문구가 왜 위험한지 말한다`() {
        val d = decideReading(SignEvidence.LooksLikeCorrection, ReadingStakes.DisplayCurve)
        assertTrue(d.whyKo, d.whyKo.contains("두 배"))
    }
}

/**
 * 곡선의 **모양**에서 오는 단서(독립 검토 R04 보강).
 *
 * 값은 실제 EMM-6 CAL 파일(17860.txt)의 모양을 그대로 옮긴 것이다:
 * 20Hz −2.0 · 1kHz 0.0 · 20kHz +4.1. **파일 자체는 저장소에 올리지
 * 않는다**(inbox 규칙) — 모양만 여기 적는다.
 */
class CurveShapeTest {

    private fun emm6Like() = CalibrationCurve.of(
        listOf(
            CurvePoint(20.0, -2.0),
            CurvePoint(1_000.0, 0.0),
            CurvePoint(10_000.0, 3.0),
            CurvePoint(20_000.0, 4.1),
        ),
    ).getOrThrow()

    @Test
    fun `저중고 세 점을 뽑는다`() {
        val s = shapeOf(emm6Like())
        assertEquals(-2.0, s.lowDb, 1e-9)
        assertEquals(0.0, s.midDb, 1e-9)
        assertEquals(4.1, s.highDb, 1e-9)
    }

    @Test
    fun `측정 캡슐 응답 모양을 알아본다`() {
        val s = shapeOf(emm6Like())
        assertTrue(s.risesToHigh)
        assertTrue(s.normalizedAtMid)
        assertTrue(describeShapeKo(s), describeShapeKo(s).contains("캡슐의 응답이 대체로"))
    }

    /** 거울상이면 **보정값일 수 있다**고 말한다 — 단정하지는 않는다. */
    @Test
    fun `거울상이면 보정값일 수 있다고 말한다`() {
        val mirrored = CalibrationCurve.of(
            listOf(
                CurvePoint(20.0, 2.0),
                CurvePoint(1_000.0, 0.0),
                CurvePoint(20_000.0, -4.1),
            ),
        ).getOrThrow()
        val s = shapeOf(mirrored)
        assertFalse(s.risesToHigh)
        val text = describeShapeKo(s)
        assertTrue(text, text.contains("보정값일 수 있습니다"))
    }

    /** **앱이 정해 준 것처럼 읽히면 안 된다.** 숫자를 먼저, 뜻은 뒤에. */
    @Test
    fun `숫자를 먼저 적고 확인을 권한다`() {
        val text = describeShapeKo(shapeOf(emm6Like()))
        assertTrue(text, text.startsWith("이 파일은 저역"))
        assertTrue(text, text.contains("제조사 설명으로 확인"))
        assertFalse("별표가 있다: $text", text.contains("*"))
    }

    /** 곡선이 좁아도 뽑히는 자리가 곡선 안이어야 한다. */
    @Test
    fun `좁은 곡선도 자기 범위 안에서 뽑는다`() {
        val narrow = CalibrationCurve.of(
            listOf(CurvePoint(200.0, -1.0), CurvePoint(5_000.0, 2.0)),
        ).getOrThrow()
        val s = shapeOf(narrow)
        assertEquals("끝점을 늘여 쓰지 않는다", -1.0, s.lowDb, 1e-9)
        assertEquals(2.0, s.highDb, 1e-9)
    }
}
