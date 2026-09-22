package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 교정 세션의 **순서와 셈**을 잰다.
 *
 * 가장 중요한 시험은 [기준 마이크 보정을 빠뜨리면 없던 보정이 걸린다] 다.
 * 나머지는 그 계산이 제 순서로 도는지 보는 것이다.
 */
class CalibrationSessionTest {

    private val n = ThirdOctave.BAND_COUNT

    private fun flat(db: Double) = DoubleArray(n) { db }

    /** [fromHz] 위로 [riseDb] 만큼 올라가는 장. */
    private fun rising(baseDb: Double, fromHz: Double, toHz: Double, riseDb: Double) =
        DoubleArray(n) { i ->
            val hz = ThirdOctave.exactCenter(i)
            baseDb + when {
                hz <= fromHz -> 0.0
                hz >= toHz -> riseDb
                else -> riseDb * (kotlin.math.log10(hz / fromHz) / kotlin.math.log10(toHz / fromHz))
            }
        }

    private fun sessionOf(
        before: DoubleArray,
        target: DoubleArray,
        after: DoubleArray,
        repeats: Int = 4,
    ): SessionResult {
        val s = CalibrationSession()
        repeat(repeats) { s.record(MeasureStep.ReferenceBefore, before) }
        repeat(repeats) { s.record(MeasureStep.Target, target) }
        repeat(repeats) { s.record(MeasureStep.ReferenceAfter, after) }
        return s.result()!!
    }

    // ------------------------------------------------------------------
    // 순서
    // ------------------------------------------------------------------

    @Test
    fun `한 단계라도 비면 셈하지 않는다`() {
        val s = CalibrationSession()
        assertNull("아무것도 없으면 null", s.result())
        s.record(MeasureStep.ReferenceBefore, flat(70.0))
        assertFalse(s.complete)
        assertNull(s.result())
        s.record(MeasureStep.Target, flat(70.0))
        assertNull("기준 재측정이 없으면 여전히 null", s.result())
        s.record(MeasureStep.ReferenceAfter, flat(70.0))
        assertTrue(s.complete)
        assertNotNull("이제는 나와야 한다", s.result())
    }

    @Test
    fun `단계마다 장 수를 센다`() {
        val s = CalibrationSession()
        repeat(3) { s.record(MeasureStep.ReferenceBefore, flat(70.0)) }
        repeat(7) { s.record(MeasureStep.Target, flat(70.0)) }
        assertEquals(3, s.frameCount(MeasureStep.ReferenceBefore))
        assertEquals(7, s.frameCount(MeasureStep.Target))
        assertEquals(0, s.frameCount(MeasureStep.ReferenceAfter))
    }

    @Test
    fun `밴드 수가 다르면 막는다`() {
        val s = CalibrationSession()
        assertTrue(runCatching { s.record(MeasureStep.Target, DoubleArray(4)) }.isFailure)
    }

    @Test
    fun `초기화하면 처음으로 돌아간다`() {
        val s = CalibrationSession()
        s.record(MeasureStep.Target, flat(70.0))
        s.reset()
        assertEquals(0, s.frameCount(MeasureStep.Target))
        assertFalse(s.complete)
    }

    @Test
    fun `넣은 배열을 나중에 고쳐도 결과가 변하지 않는다`() {
        val s = CalibrationSession()
        val mutable = flat(70.0)
        s.record(MeasureStep.ReferenceBefore, mutable)
        s.record(MeasureStep.Target, mutable)
        s.record(MeasureStep.ReferenceAfter, mutable)
        mutable.fill(0.0) // 바깥에서 재사용하는 버퍼라고 치자
        assertEquals("복사해 두어야 한다", 70.0, s.result()!!.target.meanDb[0], 1e-9)
    }

    // ------------------------------------------------------------------
    // 기준 흐름(drift)
    // ------------------------------------------------------------------

    @Test
    fun `앞뒤 기준이 같으면 흐름이 없다`() {
        val r = sessionOf(flat(70.0), flat(68.0), flat(70.0))
        assertEquals(0.0, r.referenceDriftDb, 1e-9)
    }

    /** **부호를 살린다** — 커졌는지 작아졌는지 알아야 원인을 짚는다. */
    @Test
    fun `재는 동안 커지면 흐름이 양수다`() {
        val r = sessionOf(flat(70.0), flat(68.0), flat(72.0))
        assertEquals(2.0, r.referenceDriftDb, 1e-9)
    }

    @Test
    fun `재는 동안 작아지면 흐름이 음수다`() {
        val r = sessionOf(flat(70.0), flat(68.0), flat(67.5))
        assertEquals(-2.5, r.referenceDriftDb, 1e-9)
    }

    /** 기준은 앞뒤의 **에너지** 평균이다. 0dB 와 20dB 의 평균은 17dB. */
    @Test
    fun `기준은 앞뒤의 에너지 평균이다`() {
        val before = flat(0.0)
        val after = flat(20.0)
        val r = sessionOf(before, flat(10.0), after)
        assertEquals(17.03, r.referenceMeanDb[0], 0.01)
        assertTrue("dB 평균(10dB)이 아니어야 한다", r.referenceMeanDb[0] > 16.0)
    }

    // ------------------------------------------------------------------
    // 튄 장
    // ------------------------------------------------------------------

    @Test
    fun `튄 장은 버리고 몇 장 버렸는지 적는다`() {
        val s = CalibrationSession()
        repeat(3) { s.record(MeasureStep.ReferenceBefore, flat(70.0)) }
        repeat(9) { s.record(MeasureStep.Target, flat(60.0)) }
        s.record(MeasureStep.Target, flat(90.0)) // 문 닫는 소리
        repeat(3) { s.record(MeasureStep.ReferenceAfter, flat(70.0)) }

        val t = s.result()!!.target
        assertEquals(9, t.keptFrames)
        assertEquals(1, t.droppedFrames)
        assertEquals("튄 장이 빠지면 조용한 값", 60.0, t.meanDb[0], 1e-9)
    }

    /**
     * **다 버려지면 안 버린 셈 친다.**
     *
     * 장이 둘뿐이고 서로 멀면 중앙값이 그 가운데라 둘 다 벗어난다. 그때
     * 빈손으로 돌려주면 세션이 통째로 사라진다 — 대신 전부 쓰고, 벌어짐을
     * 그대로 남겨 품질 관문이 잡게 둔다.
     */
    @Test
    fun `다 버려지면 전부 쓰고 벌어짐을 남긴다`() {
        val s = CalibrationSession()
        s.record(MeasureStep.ReferenceBefore, flat(70.0))
        s.record(MeasureStep.Target, flat(40.0))
        s.record(MeasureStep.Target, flat(90.0))
        s.record(MeasureStep.ReferenceAfter, flat(70.0))

        val t = s.result()!!.target
        assertEquals("빈손으로 돌려주지 않는다", 2, t.keptFrames)
        assertEquals(0, t.droppedFrames)
        assertEquals("벌어짐이 그대로 남아야 한다", 50.0, t.levelSpreadDb, 1e-9)
    }

    @Test
    fun `대상의 벌어짐이 반복 편차가 된다`() {
        val s = CalibrationSession()
        s.record(MeasureStep.ReferenceBefore, flat(70.0))
        s.record(MeasureStep.Target, flat(69.0))
        s.record(MeasureStep.Target, flat(71.0))
        s.record(MeasureStep.ReferenceAfter, flat(70.0))
        assertEquals(2.0, s.result()!!.repeatSpreadDb, 1e-9)
    }

    @Test
    fun `장이 하나면 벌어짐은 0 이다`() {
        val r = sessionOf(flat(70.0), flat(68.0), flat(70.0), repeats = 1)
        assertEquals(0.0, r.repeatSpreadDb, 1e-9)
    }

    // ------------------------------------------------------------------
    // 광대역 레벨
    // ------------------------------------------------------------------

    /** 같은 레벨의 밴드 31개를 합치면 10·log10(31) 만큼 올라간다. */
    @Test
    fun `광대역은 전력으로 합친다`() {
        val db = broadbandDb(flat(0.0))
        assertEquals(10.0 * kotlin.math.log10(31.0), db, 1e-9)
        assertEquals(14.91, db, 0.01)
    }

    @Test
    fun `전부 조용해도 무한대가 되지 않는다`() {
        val db = broadbandDb(DoubleArray(n) { -400.0 })
        assertTrue("유한해야 한다: $db", db.isFinite())
    }

    // ------------------------------------------------------------------
    // ★ 기준 마이크 보정 — 이 파일의 요점
    // ------------------------------------------------------------------

    @Test
    fun `보정이 없으면 그대로 돌려준다`() {
        val src = flat(70.0)
        val out = applyMicCalibration(src, null)
        assertEquals(70.0, out[0], 1e-9)
        assertFalse("복사본이어야 한다", out === src)
    }

    /** [CalibrationCurve] 규약대로 **뺀다**. */
    @Test
    fun `기준 마이크의 응답을 뺀다`() {
        val curve = CalibrationCurve.of(
            listOf(CurvePoint(20.0, 3.0), CurvePoint(20_000.0, 3.0)),
        ).getOrThrow()
        val out = applyMicCalibration(flat(70.0), curve)
        assertEquals("더하는 게 아니라 빼야 한다", 67.0, out[0], 1e-9)
    }

    /**
     * ## 이 시험이 잡는 것
     *
     * EMM-6 는 고역을 **더 크게** 잡는다(실측 20kHz 에서 +4.1dB). 그
     * 곡선을 그대로 「참값」으로 쓰면, 내장 마이크가 멀쩡해도 「고역이
     * 4dB 모자라다」로 보인다 → **없던 보정을 4dB 걸게 된다.**
     *
     * 여기서는 방도 휴대폰도 완전히 평탄하게 두고, EMM-6 만 고역에서
     * +4dB 더 잡게 한다. **옳은 답은 보정 0dB 다.**
     */
    @Test
    fun `기준 마이크 보정을 빠뜨리면 없던 보정이 걸린다`() {
        val riseDb = 4.0
        // EMM-6 의 응답: 5kHz 까지 평탄, 10kHz 위로 +4dB.
        val emm6 = CalibrationCurve.of(
            listOf(
                CurvePoint(20.0, 0.0),
                CurvePoint(5_000.0, 0.0),
                CurvePoint(10_000.0, riseDb),
                CurvePoint(20_000.0, riseDb),
            ),
        ).getOrThrow()

        // 방도 휴대폰도 평탄. EMM-6 가 잰 것은 「방 + 자기 응답」.
        val room = 70.0
        val measuredByEmm6 = rising(room, 5_000.0, 10_000.0, riseDb)
        val measuredByPhone = flat(room)

        val session = sessionOf(measuredByEmm6, measuredByPhone, measuredByEmm6)

        val right = calibrateFromSession(session, emm6)
        val wrong = calibrateFromSession(session, null) // CAL 을 빠뜨린 경우

        fun at(o: CalibrationOutcome, hz: Double): Double {
            val i = o.correction.hz.indices.minByOrNull { abs(o.correction.hz[it] - hz) }!!
            return o.correction.db[i]
        }

        val rightAt14k = at(right, 14_000.0)
        val wrongAt14k = at(wrong, 14_000.0)
        println(
            "CAL_MISSING 14kHz 보정: CAL 적용=${"%.2f".format(rightAt14k)}dB  " +
                "CAL 빠뜨림=${"%.2f".format(wrongAt14k)}dB",
        )

        assertEquals("휴대폰이 멀쩡하므로 보정은 0 이어야 한다", 0.0, rightAt14k, 0.3)
        assertTrue(
            "CAL 을 빠뜨리면 없던 보정이 걸린다 ($wrongAt14k)",
            wrongAt14k > riseDb - 1.0,
        )
    }

    /** 중역(정규화 대역)은 어느 쪽이든 0 이다 — 차이가 고역에만 있다는 확인. */
    @Test
    fun `중역에서는 두 경우가 같다`() {
        val emm6 = CalibrationCurve.of(
            listOf(
                CurvePoint(20.0, 0.0),
                CurvePoint(5_000.0, 0.0),
                CurvePoint(10_000.0, 4.0),
                CurvePoint(20_000.0, 4.0),
            ),
        ).getOrThrow()
        val measured = rising(70.0, 5_000.0, 10_000.0, 4.0)
        val session = sessionOf(measured, flat(70.0), measured)

        fun at(o: CalibrationOutcome, hz: Double): Double {
            val i = o.correction.hz.indices.minByOrNull { abs(o.correction.hz[it] - hz) }!!
            return o.correction.db[i]
        }
        assertEquals(0.0, at(calibrateFromSession(session, emm6), 1_000.0), 0.1)
        assertEquals(0.0, at(calibrateFromSession(session, null), 1_000.0), 0.1)
    }

    // ------------------------------------------------------------------
    // 품질 보고
    // ------------------------------------------------------------------

    @Test
    fun `세션의 흐름과 편차가 품질 보고로 넘어간다`() {
        val s = CalibrationSession()
        repeat(3) { s.record(MeasureStep.ReferenceBefore, flat(70.0)) }
        s.record(MeasureStep.Target, flat(69.0))
        s.record(MeasureStep.Target, flat(70.5))
        repeat(3) { s.record(MeasureStep.ReferenceAfter, flat(71.0)) }
        val r = s.result()!!

        val q = qualityFromSession(r, noiseDb = flat(40.0))
        assertEquals(r.repeatSpreadDb, q.repeatSpreadDb!!, 1e-9)
        assertEquals(r.referenceDriftDb, q.referenceDriftDb!!, 1e-9)
        assertEquals(n, q.bands.size)
        assertEquals("1kHz 밴드의 중심주파수", 1000.0, q.bands[17].hz, 1e-9)
        assertTrue("SNR 이 넉넉하면 다 쓸 수 있다", q.usableRatio > 0.99)
    }

    /**
     * **배경을 안 쟀으면 「모른다」다** — 「괜찮다」가 아니다.
     *
     * SNR 을 0 으로 두어 모든 대역이 기준 미달이 되고, 관문이 막는다.
     */
    @Test
    fun `배경을 안 쟀으면 쓸 수 있는 대역이 없다`() {
        val r = sessionOf(flat(70.0), flat(70.0), flat(70.0))
        val q = qualityFromSession(r, noiseDb = null)
        assertEquals(0.0, q.usableRatio, 1e-9)
        assertEquals(QualityVerdict.Fail, judgeQuality(q).verdict)
    }

    @Test
    fun `기준이 많이 흐르면 막는다`() {
        val r = sessionOf(flat(70.0), flat(70.0), flat(75.0))
        val q = qualityFromSession(r, noiseDb = flat(40.0))
        val judged = judgeQuality(q)
        assertEquals(QualityVerdict.Fail, judged.verdict)
        assertTrue(
            "까닭에 기준 흐름이 있어야 한다: ${judged.reasonsKo}",
            judged.reasonsKo.any { it.contains("기준") },
        )
    }

    // ------------------------------------------------------------------
    // 안내 문구
    // ------------------------------------------------------------------

    @Test
    fun `흐름이 허용 안이면 그렇게 알린다`() {
        val note = referenceDriftNoteKo(0.4, 1.0)
        assertTrue(note, note.contains("허용"))
        assertTrue("부호를 보여야 한다: $note", note.contains("+0.4"))
    }

    @Test
    fun `커졌는지 작아졌는지 말로 알린다`() {
        assertTrue(referenceDriftNoteKo(3.0, 1.0).contains("커졌"))
        assertTrue(referenceDriftNoteKo(-3.0, 1.0).contains("작아졌"))
    }

    @Test
    fun `안내 문구에 별표가 새지 않는다`() {
        listOf(referenceDriftNoteKo(0.2, 1.0), referenceDriftNoteKo(5.0, 1.0))
            .forEach { assertFalse(it, it.contains("*")) }
    }
}
