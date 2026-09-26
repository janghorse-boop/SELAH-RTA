package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log2

/**
 * 교정 세션의 **순서와 셈**을 잰다.
 *
 * 가장 중요한 시험은 [기준 마이크 보정을 빠뜨리면 없던 보정이 걸린다] 다.
 * 나머지는 그 계산이 제 순서로 도는지 보는 것이다.
 */
class CalibrationSessionTest {

    private val n = ThirdOctave.BAND_COUNT

    /**
     * 분석 축을 **넉넉히 덮는** CAL 범위.
     *
     * 20~20000 으로 잡으면 밴드 0(실제 중심 19.95Hz)이 빠져 지원이
     * 29/31 이 된다 — 「CAL 이 좁아서」를 재려는 게 아닌 시험에서 그게
     * 섞이면 안 된다.
     */
    private val FULL_CAL = 10.0..25_000.0

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

    /**
     * 기본 8장은 [QualityPolicy.minFramesPerStep] 의 바닥이다 — 그보다
     * 적으면 반복성을 말할 수 없어 관문이 막는다(독립 검증 CP01).
     */
    private fun sessionOf(
        before: DoubleArray,
        target: DoubleArray,
        after: DoubleArray,
        repeats: Int = 8,
        calRange: ClosedFloatingPointRange<Double> = FULL_CAL,
    ): SessionResult {
        val s = CalibrationSession()
        repeat(repeats) { s.recordReference(MeasureStep.ReferenceBefore, refSpectrum(before, calRange)) }
        repeat(repeats) { s.record(MeasureStep.Target, target) }
        repeat(repeats) { s.recordReference(MeasureStep.ReferenceAfter, refSpectrum(after, calRange)) }
        return s.result()!!
    }

    /**
     * 밴드 dB 를 **CAL 이 걸린 기준 장**으로 감싼다.
     *
     * 세션이 증거 없이는 기준을 받지 않으므로 시험도 그 길을 지나야
     * 한다. 여기서는 **밴드 값을 그대로 쓰려고** 증거만 붙인다 —
     * 이 시험들이 재는 것은 CAL 의 값이 아니라 순서와 셈이다.
     *
     * `internal` 이라 같은 모듈의 시험에서만 이렇게 만들 수 있다.
     * 바깥(app)에서는 [applyReferenceCalibration] 을 지나야 하고,
     * 그 길이 실제로 맞는지는 [ReferenceCalibrationProofTest] 가 잰다.
     */
    private fun refSpectrum(
        bandsDb: DoubleArray,
        calRange: ClosedFloatingPointRange<Double> = FULL_CAL,
    ) = testReferenceSpectrum(bandsDb, calRange)

    /** 앞뒤 기준이 같은 경우. 흐름 0 이라 다른 것만 보게 된다. */
    private fun sessionOf(
        reference: DoubleArray,
        target: DoubleArray,
        calRange: ClosedFloatingPointRange<Double> = FULL_CAL,
    ): SessionResult = sessionOf(reference, target, reference, calRange = calRange)

    /** 보정 곡선에서 [hz] 에 가장 가까운 자리. */
    private fun nearest(c: ResponseCurve, hz: Double): Int =
        c.hz.indices.minByOrNull { abs(c.hz[it] - hz) }!!

    // ------------------------------------------------------------------
    // 순서
    // ------------------------------------------------------------------

    @Test
    fun `한 단계라도 비면 셈하지 않는다`() {
        val s = CalibrationSession()
        assertNull("아무것도 없으면 null", s.result())
        s.recordReference(MeasureStep.ReferenceBefore, refSpectrum(flat(70.0)))
        assertFalse(s.complete)
        assertNull(s.result())
        s.record(MeasureStep.Target, flat(70.0))
        assertNull("기준 재측정이 없으면 여전히 null", s.result())
        s.recordReference(MeasureStep.ReferenceAfter, refSpectrum(flat(70.0)))
        assertTrue(s.complete)
        assertNotNull("이제는 나와야 한다", s.result())
    }

    /**
     * **한 단계를 버린다**(독립 재검토 CAR-01).
     *
     * 재는 도중에 입력이 바뀌면 그 단계의 장은 「어느 마이크의 것」이라고
     * 말할 수 없다. 이름표만 고쳐 붙이면 잰 적 없는 경로에 남의 자료가
     * 귀속되므로, 고쳐 붙이는 대신 버린다.
     */
    @Test
    fun `한 단계를 버리면 그 장만 사라진다`() {
        val s = CalibrationSession()
        repeat(3) { s.recordReference(MeasureStep.ReferenceBefore, refSpectrum(flat(70.0))) }
        repeat(3) { s.record(MeasureStep.Target, flat(60.0)) }

        s.discard(MeasureStep.Target)

        assertEquals("버린 단계만 비어야 한다", 0, s.frameCount(MeasureStep.Target))
        assertEquals("남의 장까지 지웠다", 3, s.frameCount(MeasureStep.ReferenceBefore))
        assertNotNull("기준 증거는 남아야 한다", s.referenceProof)
        assertFalse(s.complete)
    }

    /**
     * 기준 장이 하나도 안 남으면 **증거도 함께 버린다.**
     *
     * 남겨 두면 다음 시도가 **버린 시도의 CAL** 에 묶여, 다른 CAL 로
     * 다시 재려 할 때 「장들의 설정이 다르다」로 막힌다.
     */
    @Test
    fun `기준을 다 버리면 증거도 버린다`() {
        val s = CalibrationSession()
        s.recordReference(MeasureStep.ReferenceBefore, refSpectrum(flat(70.0)))
        assertNotNull(s.referenceProof)

        s.discard(MeasureStep.ReferenceBefore)

        assertNull("버린 시도의 CAL 이 남았다", s.referenceProof)
        // 그래서 다른 CAL 로 다시 잴 수 있다.
        s.recordReference(MeasureStep.ReferenceBefore, refSpectrum(flat(65.0)))
        assertEquals(1, s.frameCount(MeasureStep.ReferenceBefore))
    }

    @Test
    fun `단계마다 장 수를 센다`() {
        val s = CalibrationSession()
        repeat(3) { s.recordReference(MeasureStep.ReferenceBefore, refSpectrum(flat(70.0))) }
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
        s.recordReference(MeasureStep.ReferenceBefore, refSpectrum(mutable))
        s.record(MeasureStep.Target, mutable)
        s.recordReference(MeasureStep.ReferenceAfter, refSpectrum(mutable))
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
        repeat(3) { s.recordReference(MeasureStep.ReferenceBefore, refSpectrum(flat(70.0))) }
        repeat(9) { s.record(MeasureStep.Target, flat(60.0)) }
        s.record(MeasureStep.Target, flat(90.0)) // 문 닫는 소리
        repeat(3) { s.recordReference(MeasureStep.ReferenceAfter, refSpectrum(flat(70.0))) }

        val t = s.result()!!.target
        assertEquals(9, t.keptFrames)
        assertEquals(1, t.droppedFrames)
        assertEquals("튄 장이 빠지면 조용한 값", 60.0, t.meanDb[0], 1e-9)
    }

    /**
     * **다 버려지면 전부 쓰되, 그랬다고 적는다**(독립 검증 CP01).
     *
     * 장이 둘뿐이고 서로 멀면 중앙값이 그 가운데라 둘 다 벗어난다. 빈손으로
     * 돌려주면 세션이 통째로 사라지므로 전부 쓴다 — 다만 `droppedFrames`
     * 만 보면 0 이라 **「다 안정적이었다」로 읽힌다.** 정반대다.
     */
    @Test
    fun `다 버려지면 전부 쓰되 그랬다고 적는다`() {
        val s = CalibrationSession()
        s.recordReference(MeasureStep.ReferenceBefore, refSpectrum(flat(70.0)))
        s.record(MeasureStep.Target, flat(40.0))
        s.record(MeasureStep.Target, flat(90.0))
        s.recordReference(MeasureStep.ReferenceAfter, refSpectrum(flat(70.0)))

        val t = s.result()!!.target
        assertEquals("빈손으로 돌려주지 않는다", 2, t.keptFrames)
        assertEquals("버린 것은 정말 없다", 0, t.droppedFrames)
        assertTrue("안정된 장이 없었다고 적어야 한다", t.noStableFrames)
        // 값 둘(40·90)의 표준편차는 폭의 절반이다. 흔들림이 사라지지
        // 않고 그대로 남는지를 본다 — 예전 min−max 로는 50.0 이었다.
        assertEquals("흔들림이 그대로 남아야 한다", 25.0, t.levelStdevDb!!, 1e-9)
    }

    /**
     * **CP01 반례 ①** — 기준 단계만 불안정하고 대상은 얌전한 경우.
     *
     * 예전에는 `repeatStdevDb` 에 대상의 벌어짐만 담겨서, 기준이 50dB
     * 출렁여도 **Pass** 가 나왔다.
     */
    @Test
    fun `기준 단계가 불안정하면 막는다`() {
        val s = CalibrationSession()
        for (step in listOf(MeasureStep.ReferenceBefore, MeasureStep.ReferenceAfter)) {
            repeat(4) { s.putFrame(step, flat(40.0)); s.putFrame(step, flat(90.0)) }
        }
        repeat(8) { s.record(MeasureStep.Target, flat(60.0)) }
        val r = s.result()!!

        assertTrue("기준이 안정되지 않았다", r.noStableFrames)
        val judged = judgeQuality(qualityFromSession(r, flat(10.0), referenceCalRangeHz = FULL_CAL, dspVerifiedBySignal = true))
        assertEquals(QualityVerdict.Fail, judged.verdict)
        assertTrue(
            "안정된 구간이 없었다고 말해야 한다: ${judged.reasonsKo}",
            judged.reasonsKo.any { it.contains("안정된 구간") },
        )
    }

    /**
     * **CP01 반례 ②** — 광대역은 같은데 **모양**이 변한 경우.
     *
     * 한 대역이 오르고 다른 대역이 내리면 광대역 차이는 0 이다. 예전에는
     * 그것만 보아 Pass 를 주었고, 그 모양 변화가 **6.73dB 의 보정**으로
     * 기록되었다 — 환경 변화가 마이크 특성이 된다.
     */
    @Test
    fun `광대역이 같아도 모양이 변하면 막는다`() {
        val a = flat(50.0).also { it[20] = 60.0 }
        val b = flat(50.0).also { it[24] = 60.0 }
        val r = sessionOf(a, a, b)

        assertEquals("광대역은 변하지 않았다", 0.0, r.referenceDriftDb, 1e-9)
        assertTrue(
            "대역별로는 10dB 움직였다: ${r.referenceBandDriftDb}",
            r.referenceBandDriftDb > 9.9,
        )

        val judged = judgeQuality(qualityFromSession(r, flat(10.0), referenceCalRangeHz = FULL_CAL, dspVerifiedBySignal = true))
        assertEquals(QualityVerdict.Fail, judged.verdict)
        assertTrue(
            "모양이 변했다고 말해야 한다: ${judged.reasonsKo}",
            judged.reasonsKo.any { it.contains("모양") },
        )
    }

    /** 세 단계 중 **가장 나쁜** 벌어짐을 쓴다 — 대상만 보지 않는다. */
    @Test
    fun `세 단계 중 가장 많이 흔들린 것을 쓴다`() {
        val s = CalibrationSession()
        repeat(4) { s.recordReference(MeasureStep.ReferenceBefore, refSpectrum(flat(70.0))) }
        repeat(4) { s.recordReference(MeasureStep.ReferenceBefore, refSpectrum(flat(71.5))) } // 1.5dB
        repeat(8) { s.record(MeasureStep.Target, flat(60.0)) } // 0dB
        repeat(4) { s.recordReference(MeasureStep.ReferenceAfter, refSpectrum(flat(70.0))) }
        repeat(4) { s.recordReference(MeasureStep.ReferenceAfter, refSpectrum(flat(70.5))) } // 0.5dB

        // 앞단은 70·71.5 가 넷씩 → 표준편차 0.75. 뒷단은 70·70.5 → 0.25.
        // 대상은 흔들림 없음 → 0. 가장 큰 것이 올라와야 한다.
        assertEquals("기준 앞단이 이겨야 한다", 0.75, s.result()!!.repeatStdevDb!!, 1e-9)
    }

    /** **장이 하나면 「0」이 아니라 「모른다」다.** */
    @Test
    fun `장이 하나면 흔들림을 모른다`() {
        val r = sessionOf(flat(70.0), flat(68.0), flat(70.0), repeats = 1)
        assertNull("0 이 아니라 null 이어야 한다", r.target.levelStdevDb)
        assertNull(r.repeatStdevDb)

        val judged = judgeQuality(qualityFromSession(r, flat(40.0), referenceCalRangeHz = FULL_CAL, dspVerifiedBySignal = true))
        assertTrue("통과시키면 안 된다", judged.verdict != QualityVerdict.Pass)
    }

    @Test
    fun `장이 모자라면 막는다`() {
        val r = sessionOf(flat(70.0), flat(68.0), flat(70.0), repeats = 3)
        val judged = judgeQuality(qualityFromSession(r, flat(40.0), referenceCalRangeHz = FULL_CAL, dspVerifiedBySignal = true))
        assertEquals(QualityVerdict.Fail, judged.verdict)
        assertTrue(
            "몇 개인지 말해야 한다: ${judged.reasonsKo}",
            judged.reasonsKo.any { it.contains("3개") },
        )
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
    // ★ 기준 CAL 은 밴드가 아니라 FFT 칸에 건다 (독립 검증 CP04)
    // ------------------------------------------------------------------

    /**
     * 두 경로 모두 배경이 조용했던 경우. 기준 배경까지 준다(RCP02).
     *
     * @param cal 기준 CAL 이 덮는 범위. **승인과 곡선이 같은 값을 보도록**
     *   품질 보고에 넣는다(독립 검증 RCP-F01).
     */
    private fun goodQuality(
        r: SessionResult,
        cal: ClosedFloatingPointRange<Double>? = r.referenceProof?.rangeHz,
    ) = qualityFromSession(
        r, flat(40.0), referenceNoiseDb = flat(40.0),
        referenceCalRangeHz = cal, dspVerifiedBySignal = true,
    )

    /**
     * **CAL 을 걸지 않은 기준은 세션에 들어갈 수조차 없다.**
     *
     * 예전에는 `referenceCalApplied = false` 로 세션을 만들 수 있었고,
     * 거절은 나중에 `calibrateFromSession` 에서 했다. 이제는 기준 장이
     * [CalibratedReferenceSpectrum] 이어야 하고 그것은
     * [applyReferenceCalibration] 만 만든다 — **선언할 자리가 없다.**
     */
    @Test
    fun `기준 장을 대상 경로로 넣을 수 없다`() {
        val s = CalibrationSession()
        for (step in listOf(MeasureStep.ReferenceBefore, MeasureStep.ReferenceAfter)) {
            val e = runCatching { s.record(step, flat(70.0)) }.exceptionOrNull()
            assertTrue("$step 은 막아야 한다", e is IllegalArgumentException)
            assertTrue(
                "까닭을 말해야 한다: ${e?.message}",
                e?.message?.contains("recordReference") == true,
            )
        }
        assertTrue(
            "대상 장을 기준 경로로 넣는 것도 막는다",
            runCatching {
                s.recordReference(MeasureStep.Target, refSpectrum(flat(70.0)))
            }.isFailure,
        )
    }

    /**
     * 그래도 **관문은 남겨 둔다.**
     *
     * 증거 없는 결과는 지금 제품 경로로는 만들 수 없지만, 나중에 누가
     * 다른 길을 내면 이 검사가 마지막 그물이 된다.
     */
    @Test
    fun `증거가 없으면 보정을 만들지 않는다`() {
        val r = sessionOf(flat(70.0), flat(70.0), flat(70.0))
        val withoutProof = r.copy(referenceProof = null)
        val out = calibrateFromSession(withoutProof, goodQuality(r))
        assertTrue("거절해야 한다", out.isFailure)
        assertTrue(
            "까닭을 말해야 한다: ${out.exceptionOrNull()?.message}",
            out.exceptionOrNull()?.message?.contains("칸") == true,
        )
    }

    /** 다른 CAL·다른 설정으로 잰 기준 장이 **섞이면 막는다.** */
    @Test
    fun `증거가 다른 기준 장은 섞이지 않는다`() {
        val s = CalibrationSession()
        s.recordReference(MeasureStep.ReferenceBefore, refSpectrum(flat(70.0), 20.0..20_000.0))
        val e = runCatching {
            s.recordReference(MeasureStep.ReferenceBefore, refSpectrum(flat(70.0), 200.0..10_000.0))
        }.exceptionOrNull()
        assertTrue("$e", e?.message?.contains("CAL·분석 설정이 다르다") == true)
    }

    /** 승인이 본 CAL 범위와 **실제로 건 범위**가 다르면 만들지 않는다. */
    @Test
    fun `선언한 CAL 범위와 실제가 다르면 거절한다`() {
        val r = sessionOf(flat(70.0), flat(70.0), flat(70.0), calRange = 200.0..10_000.0)
        // 품질 보고에는 전 대역이라고 적었다 — 둘 중 하나는 거짓이다.
        val q = goodQuality(r, cal = 20.0..20_000.0)
        val out = calibrateFromSession(r, q)
        assertTrue("거절해야 한다", out.isFailure)
        assertTrue(
            "${out.exceptionOrNull()?.message}",
            out.exceptionOrNull()?.message?.contains("실제로 건 범위가 다릅니다") == true,
        )
    }

    /**
     * ## 기준에 CAL 이 걸려 있으면 보정은 0 이다
     *
     * EMM-6 는 고역을 더 크게 잡는다(실측 20kHz 에서 +4.1dB). 칸에서
     * 그 응답을 걷어낸 기준을 넣으면 **휴대폰이 멀쩡할 때 보정 0** 이
     * 나와야 하고, 걷어내지 않은 기준을 넣으면 그 응답만큼 **없던 보정**
     * 이 걸린다.
     */
    @Test
    fun `기준에서 마이크 응답을 걷어내지 않으면 없던 보정이 걸린다`() {
        val riseDb = 4.0
        val room = 70.0
        // 칸에서 CAL 을 건 뒤의 기준 = 방 그대로.
        val corrected = flat(room)
        // 걷어내지 않은 기준 = 방 + EMM-6 의 고역 상승.
        val uncorrected = rising(room, 5_000.0, 10_000.0, riseDb)
        val phone = flat(room)

        val right = sessionOf(corrected, phone, corrected)
        val wrong = sessionOf(uncorrected, phone, uncorrected)

        val rightOut = calibrateFromSession(right, goodQuality(right)).getOrThrow()
        val wrongOut = calibrateFromSession(wrong, goodQuality(wrong)).getOrThrow()

        val rightAt14k = rightOut.correction.db[nearest(rightOut.correction, 14_000.0)]
        val wrongAt14k = wrongOut.correction.db[nearest(wrongOut.correction, 14_000.0)]
        println(
            "CAL_MISSING 14kHz 보정: 걷어냄=${"%.2f".format(rightAt14k)}dB  " +
                "안 걷어냄=${"%.2f".format(wrongAt14k)}dB",
        )

        assertEquals("휴대폰이 멀쩡하므로 보정은 0 이어야 한다", 0.0, rightAt14k, 0.3)
        assertTrue("안 걷어내면 없던 보정이 걸린다 ($wrongAt14k)", wrongAt14k > riseDb - 1.0)
    }

    // ------------------------------------------------------------------
    // ★ 못 믿는 대역은 보정으로 넘어가지 않는다 (독립 검증 CP02)
    // ------------------------------------------------------------------

    /**
     * **CP02 반례 ①** — 전체 비율은 통과하는데 한 대역만 SNR 미달.
     *
     * 예전에는 품질 화면이 「이 대역은 못 믿는다」고 하는데 보정 곡선은
     * `valid=true` 라 그 자리에도 보정이 걸렸다.
     */
    @Test
    fun `SNR 미달 대역은 보정에서 빠진다`() {
        val signal = flat(70.0)
        val r = sessionOf(signal, signal, signal)
        // 20번 대역만 배경이 신호와 같다 → SNR 0.
        val noise = flat(40.0).also { it[20] = signal[20] }
        val q = qualityFromSession(
            r, noise, referenceNoiseDb = flat(40.0),
            referenceCalRangeHz = FULL_CAL, dspVerifiedBySignal = true,
        )

        assertFalse("그 대역은 못 쓴다", q.usable[20])
        assertTrue("나머지는 쓸 수 있어 전체는 통과한다", q.usableRatio > 0.9)

        val out = calibrateFromSession(r, q).getOrThrow()
        val i = nearest(out.correction, ThirdOctave.exactCenter(20))
        assertFalse(
            "못 믿는 대역에 보정이 남으면 안 된다",
            out.correction.valid[i],
        )
        // 이웃한 믿을 만한 자리는 살아 있어야 한다 — 전부 꺼 버리면 뜻이 없다.
        val far = nearest(out.correction, ThirdOctave.exactCenter(10))
        assertTrue("멀쩡한 대역은 살아 있어야 한다", out.correction.valid[far])
    }

    /**
     * **CP02 반례 ②** — 기준 CAL 이 덮지 않는 주파수.
     *
     * `gainDbAt` 은 범위 밖에서 끝점 값을 돌려주므로 **숫자가 나온다는
     * 것이 잰 적이 있다는 뜻이 아니다.** CAL 을 200Hz~10kHz 로 두었는데
     * 30Hz 의 보정이 valid=true 였다.
     */
    @Test
    fun `기준 CAL 이 덮지 않는 주파수는 보정에서 빠진다`() {
        val r = sessionOf(flat(70.0), flat(70.0), flat(70.0), calRange = 200.0..10_000.0)
        val q = goodQuality(r)
        val out = calibrateFromSession(r, q).getOrThrow()

        val low = nearest(out.correction, 30.0)
        val high = nearest(out.correction, 18_000.0)
        val mid = nearest(out.correction, 1_000.0)
        assertFalse("CAL 아래쪽 밖", out.correction.valid[low])
        assertFalse("CAL 위쪽 밖", out.correction.valid[high])
        assertTrue("CAL 안쪽은 살아 있어야 한다", out.correction.valid[mid])
    }

    @Test
    fun `믿을 수 있는 대역이 없으면 거절한다`() {
        val r = sessionOf(flat(70.0), flat(70.0), flat(70.0))
        // 대상 배경 미측정 → SNR 0. 기준 쪽은 쟀다고 둬야 이 시험이
        // 「기준을 안 쟀다」가 아니라 「대상이 못 쓴다」를 잰다.
        val q = qualityFromSession(
            r, noiseDb = null, referenceNoiseDb = flat(40.0), referenceCalRangeHz = FULL_CAL,
        )
        val out = calibrateFromSession(r, q)
        assertTrue("거절해야 한다", out.isFailure)
        assertTrue(
            "${out.exceptionOrNull()?.message}",
            out.exceptionOrNull()?.message?.contains("믿을 수 있는 대역") == true,
        )
    }

    // ------------------------------------------------------------------
    // ★ 같은 자리에서 레벨을 맞춘다 (독립 검증 RCP01)
    // ------------------------------------------------------------------

    /**
     * **RCP01 반례 ①** — 기준과 대상이 **완전히 같은 곡선**인데 보정이
     * 생겼다.
     *
     * CAL 범위가 기준에만 걸려 두 곡선의 유효 지지구간이 달라졌고, 각자의
     * valid 로 평균하니 실제로 평균한 주파수들이 달라졌다. 기울어진
     * 곡선에서 그 차이가 그대로 오프셋 차이가 되어 **−3.5dB** 의 없던
     * 보정이 나왔다.
     *
     * 같은 입력이면 상대 응답 보정은 **정확히 0** 이어야 한다.
     */
    @Test
    fun `같은 곡선이면 지지구간이 달라도 보정이 0 이다`() {
        val slope = DoubleArray(n) { 50 + 4 * log2(ThirdOctave.exactCenter(it) / 1000.0) }
        val r = sessionOf(slope, slope, calRange = 1000.0..16_000.0)
        // **배경을 아주 조용하게 둔다.** 40dB 로 두면 1414Hz 아래가 SNR 로
        // 먼저 잘려 두 마스크가 **우연히 같아지고**, 그러면 이 시험은
        // 아무것도 재지 않는다(실제로 그랬다 — 되돌린 판이 통과했다).
        // 여기서 지지구간을 가르는 것은 오직 CAL 범위여야 한다.
        val q = qualityFromSession(
            r, flat(0.0), referenceNoiseDb = flat(0.0),
            referenceCalRangeHz = 1000.0..16_000.0, dspVerifiedBySignal = true,
        )

        // CAL 은 기준에만 걸린다 — 여기서 지지구간이 갈린다.
        val out = calibrateFromSession(r, q).getOrThrow()

        // 전제 확인: 정규화 대역 안에서 두 마스크가 **정말 다른가.**
        val band = out.reference.hz.indices.filter {
            out.reference.hz[it] in 300.0..3000.0
        }
        assertTrue(
            "정규화 대역 안에서 기준·대상 마스크가 달라야 이 시험에 뜻이 있다",
            band.any { out.reference.valid[it] != out.internalRaw.valid[it] },
        )

        val validDb = out.correction.db.filterIndexed { i, _ -> out.correction.valid[i] }
        assertTrue("믿을 수 있는 자리가 있어야 한다", validDb.isNotEmpty())
        validDb.forEach {
            assertEquals("같은 입력이면 보정은 0 이다", 0.0, it, 1e-9)
        }
        assertEquals(
            "두 정규화 오프셋이 같아야 한다",
            out.referenceNormalized.offsetDb,
            out.internalNormalized.offsetDb,
            1e-9,
        )
    }

    /**
     * **RCP01 반례 ②** — 정규화할 자리가 하나도 없는데 조용히 넘어갔다.
     *
     * 오프셋 0 으로 진행하면 두 경로의 **녹음 게인 차이가 통째로 보정**이
     * 된다. 반례에서 10dB 차이가 그대로 +10dB 보정이 되었다.
     */
    @Test
    fun `레벨 맞출 자리가 없으면 거절한다`() {
        // 기준 60dB, 대상 50dB — 순전한 게인 차이다.
        val r = sessionOf(flat(60.0), flat(50.0))
        // 정규화 대역(300~3000Hz)을 덮는 자리의 배경이 신호와 같다.
        val noise = DoubleArray(n) {
            if (ThirdOctave.exactCenter(it) in 250.0..3500.0) 50.0 else 0.0
        }
        val q = qualityFromSession(
            r, noise, referenceNoiseDb = noise,
            referenceCalRangeHz = FULL_CAL, dspVerifiedBySignal = true,
        )

        val out = calibrateFromSession(r, q)
        assertTrue("거절해야 한다", out.isFailure)
        assertTrue(
            "레벨을 맞출 수 없다고 말해야 한다: ${out.exceptionOrNull()?.message}",
            out.exceptionOrNull()?.message?.contains("레벨을 맞출") == true,
        )
    }

    /** 정규화에 쓴 자리를 **점 수와 대역 수로 함께** 남긴다. */
    @Test
    fun `정규화에 쓴 자리를 남긴다`() {
        val r = sessionOf(flat(70.0), flat(70.0))
        val out = calibrateFromSession(r, goodQuality(r)).getOrThrow()
        assertTrue("점 수", out.normalizeSupportPoints > 0)
        assertTrue("대역 수", out.normalizeBandsUsed > 0)
        assertTrue(
            "축이 1/12옥타브라 점이 대역보다 많다",
            out.normalizeSupportPoints > out.normalizeBandsUsed,
        )
    }

    // ------------------------------------------------------------------
    // ★ 기준 경로의 SNR (독립 검증 RCP02)
    // ------------------------------------------------------------------

    /** 기준 경로를 안 쟀으면 **만들지 않는다.** 대상이 조용한 건 증명이 아니다. */
    @Test
    fun `기준 경로 배경을 안 쟀으면 거절한다`() {
        val r = sessionOf(flat(70.0), flat(70.0))
        val q = qualityFromSession(r, flat(40.0), referenceCalRangeHz = FULL_CAL, dspVerifiedBySignal = true)
        assertFalse(q.referenceSnrKnown)

        val out = calibrateFromSession(r, q)
        assertTrue("거절해야 한다", out.isFailure)
        assertTrue(
            "${out.exceptionOrNull()?.message}",
            out.exceptionOrNull()?.message?.contains("기준 경로") == true,
        )
        assertTrue(
            "판정도 통과를 주면 안 된다",
            judgeQuality(q).verdict != QualityVerdict.Pass,
        )
    }

    /**
     * **대상은 조용한데 기준만 시끄러운 경우.**
     *
     * 예전에는 대상의 마스크를 기준에 그대로 베껴 써서, 기준 마이크가
     * 배경과 구별되지 않아도 걸러지지 않았다.
     */
    @Test
    fun `기준 경로만 SNR 미달이면 그 대역이 빠진다`() {
        val r = sessionOf(flat(70.0), flat(70.0))
        // 20번 대역에서 **기준 경로만** 배경이 신호와 같다.
        val refNoise = flat(40.0).also { it[20] = 70.0 }
        val q = qualityFromSession(
            r, flat(40.0), referenceNoiseDb = refNoise,
            referenceCalRangeHz = FULL_CAL, dspVerifiedBySignal = true,
        )

        assertTrue("대상은 그 대역을 쓸 수 있다", q.usable[20])
        assertFalse("기준은 못 쓴다", q.referenceUsable[20])
        assertFalse("그래서 함께 쓸 수 없다", q.bothUsable[20])

        val out = calibrateFromSession(r, q).getOrThrow()
        val i = nearest(out.correction, ThirdOctave.exactCenter(20))
        assertFalse("기준이 못 믿을 자리에 보정이 남으면 안 된다", out.correction.valid[i])
    }

    @Test
    fun `기준 경로가 통째로 시끄러우면 막는다`() {
        val r = sessionOf(flat(70.0), flat(70.0))
        val q = qualityFromSession(
            r, flat(40.0), referenceNoiseDb = flat(70.0),
            referenceCalRangeHz = FULL_CAL, dspVerifiedBySignal = true,
        )
        val judged = judgeQuality(q)
        assertEquals(QualityVerdict.Fail, judged.verdict)
        assertTrue(
            "${judged.reasonsKo}",
            judged.reasonsKo.any { it.contains("기준 경로") },
        )
    }

    // ------------------------------------------------------------------
    // ★ 최소 표본은 쓴 장으로 센다 (독립 검증 RCP03)
    // ------------------------------------------------------------------

    /**
     * **RCP03 반례** — 여덟 장을 넣었지만 걸러내기가 둘만 남겼다.
     *
     * 예전에는 `minFramesPerStep` 을 **넣은** 장으로 세어 「여덟 장
     * 모았다」고 말했다. 평균과 반복성은 실제로 둘로 판단한 것이다.
     */
    @Test
    fun `최소 표본은 넣은 장이 아니라 쓴 장으로 센다`() {
        val s = CalibrationSession()
        for (step in MeasureStep.entries) {
            repeat(3) { s.putFrame(step, flat(0.0)) }
            repeat(2) { s.putFrame(step, flat(50.0)) }
            repeat(3) { s.putFrame(step, flat(100.0)) }
        }
        val r = s.result()!!

        assertEquals("넣은 것은 여덟", 8, r.minTotalFramesPerStep)
        assertEquals("남은 것은 둘", 2, r.minKeptFramesPerStep)
        assertEquals(2, r.target.keptFrames)

        val judged = judgeQuality(
            qualityFromSession(r, flat(0.0), referenceNoiseDb = flat(0.0), referenceCalRangeHz = FULL_CAL, dspVerifiedBySignal = true),
        )
        assertEquals(QualityVerdict.Fail, judged.verdict)
        assertTrue(
            "몇 장을 썼는지 말해야 한다: ${judged.reasonsKo}",
            judged.reasonsKo.any { it.contains("2개") },
        )
    }

    // ------------------------------------------------------------------
    // ★ 계산을 마친 뒤의 지원 범위 (독립 검증 F01-R)
    // ------------------------------------------------------------------

    /**
     * **F01-R 반례** — 상한 처리로 가운데가 무너졌는데 양끝만 남았다.
     *
     * 기준이 밴드마다 100dB/40dB 로 톱니처럼 요동하면 보정량이 상한
     * (12dB)을 넘어 대부분 무효가 된다. 그런데 양끝에 유효점이 남아
     * 있어 예전에는 **최종 Pass** 가 났다.
     */
    @Test
    fun `상한으로 가운데가 무너지면 최종 승인을 막는다`() {
        val ref = DoubleArray(n) {
            if (it < 2 || it > 28) 70.0 else if (it % 2 == 0) 100.0 else 40.0
        }
        val r = sessionOf(ref, flat(70.0))
        val q = qualityFromSession(
            r, flat(0.0), referenceNoiseDb = flat(0.0),
            referenceCalRangeHz = FULL_CAL, dspVerifiedBySignal = true,
        )
        val out = calibrateFromSession(r, q).getOrThrow()

        // **전제부터 확인한다** — 입력 단계는 정말 통과하는가.
        assertTrue("입력 교집합은 넉넉하다", q.approvedRatio >= 0.6)
        assertEquals("입력 판정은 통과다", QualityVerdict.Pass, judgeQuality(q).verdict)
        assertTrue("정규화 자리도 있다", out.normalizeSupportPoints > 0)
        // 양끝은 넓게 벌어져 있다 — 그래서 범위만 보면 멀쩡해 보인다.
        val lo = out.correction.hz[out.correction.valid.indexOfFirst { it }]
        val hi = out.correction.hz[out.correction.valid.indexOfLast { it }]
        assertTrue("아래끝이 100Hz 보다 낮다", lo < 100.0)
        assertTrue("위끝이 8kHz 보다 높다", hi > 8_000.0)

        // 그런데 실제로 보정이 걸리는 대역은 거의 없다.
        assertTrue(
            "지원 대역이 절반도 안 남아야 이 시험에 뜻이 있다: ${out.supportedBands.size}",
            out.supportedBandRatio < 0.5,
        )

        val final = judgeCalibration(q, out)
        assertEquals(QualityVerdict.Fail, final.verdict)
        assertFalse(final.mayAutoApply)
        assertTrue(
            "몇 대역이 남았는지 말해야 한다: ${final.reasonsKo}",
            final.reasonsKo.any { it.contains("보정이 걸리는 대역") },
        )
    }

    /**
     * **L01 반례** — 보정값이 전부 0dB 인데 상한을 탓했다.
     *
     * 지원을 줄이는 것은 상한만이 아니다. CAL 을 20~1300Hz 로 좁히면
     * 보정은 한 점도 상한을 넘지 않는데(전부 0dB), 예전 문구는 「보정량이
     * 상한(12dB)을 넘는 자리가 많다」고 말했다 — CAL 이 원인인데 레벨을
     * 고치러 가게 만든다.
     */
    @Test
    fun `상한을 넘지 않았으면 상한을 탓하지 않는다`() {
        val r = sessionOf(flat(70.0), flat(70.0), calRange = 20.0..1_300.0)
        val q = qualityFromSession(
            r, flat(0.0), referenceNoiseDb = flat(0.0),
            referenceCalRangeHz = 20.0..1_300.0, dspVerifiedBySignal = true,
        )
        val out = calibrateFromSession(r, q).getOrThrow()

        // **전제부터 확인한다** — 정말 상한을 넘은 점이 없는가.
        assertEquals(
            "보정값이 전부 0dB 여야 이 시험에 뜻이 있다",
            0.0, out.correction.db.maxOf { abs(it) }, 1e-9,
        )
        assertFalse("상한이 자른 점이 없어야 한다", out.limitedByMaxCorrection!!.any { it })
        assertTrue("그런데 지원은 모자라다", out.supportedBandRatio < 0.6)

        val final = judgeCalibration(q, out)
        assertEquals(QualityVerdict.Fail, final.verdict)
        val band = final.reasonsKo.first { it.contains("계산을 마친 뒤 보정이 걸리는 대역") }
        assertFalse("상한을 탓하면 안 된다: $band", band.contains("상한"))
        assertTrue("실제 까닭을 말해야 한다: $band", band.contains("SNR·CAL"))
    }

    /** 반대로 **정말 상한에 걸렸으면** 그렇게 말해야 한다. */
    @Test
    fun `상한에 걸렸으면 상한을 짚는다`() {
        val ref = DoubleArray(n) {
            if (it < 2 || it > 28) 70.0 else if (it % 2 == 0) 100.0 else 40.0
        }
        val r = sessionOf(ref, flat(70.0))
        val q = qualityFromSession(
            r, flat(0.0), referenceNoiseDb = flat(0.0),
            referenceCalRangeHz = FULL_CAL, dspVerifiedBySignal = true,
        )
        val out = calibrateFromSession(r, q).getOrThrow()

        assertTrue("상한이 실제로 잘랐어야 한다", out.limitedByMaxCorrection!!.any { it })

        val band = judgeCalibration(q, out).reasonsKo
            .first { it.contains("계산을 마친 뒤 보정이 걸리는 대역") }
        assertTrue("상한을 짚어야 한다: $band", band.contains("상한"))
    }

    /** 까닭은 **일어난 것만** 적는다 — 없는 까닭을 나열하지 않는다. */
    @Test
    fun `일어나지 않은 까닭은 적지 않는다`() {
        val r = sessionOf(flat(70.0), flat(70.0), calRange = 20.0..1_300.0)
        val q = qualityFromSession(
            r, flat(0.0), referenceNoiseDb = flat(0.0),
            referenceCalRangeHz = 20.0..1_300.0, dspVerifiedBySignal = true,
        )
        val why = calibrateFromSession(r, q).getOrThrow().unsupportedReasonsKo()
        assertEquals("한 가지 까닭만 있어야 한다: $why", 1, why.size)
    }

    /** 다 지원되면 까닭이 없다. */
    @Test
    fun `전부 지원되면 까닭이 비어 있다`() {
        val r = sessionOf(flat(70.0), flat(70.0))
        val out = calibrateFromSession(r, goodQuality(r)).getOrThrow()
        assertEquals(n, out.supportedBands.size)
        assertTrue(out.unsupportedReasonsKo().isEmpty())
    }

    /**
     * **최종 유효점이 0개인 결과는 실제 경로로 만들어진다.**
     *
     * 직전 회신서에서 「`calibrateFromSession` 이 먼저 거절하므로 그런
     * outcome 을 만들 수 없다」고 적었는데 **틀렸다.** 그 함수가 보는
     * 정규화 지원 점은 **상한 적용 전**의 것이라, 상한이 최종 보정을
     * 전부 무효로 만들어도 정규화 점은 남는다.
     *
     * 아주 작은 상한은 현장 값이 아니라 **그 분기에 닿기 위한 설정**이다.
     */
    @Test
    fun `최종 유효점이 없으면 막는다`() {
        val ref = DoubleArray(n) {
            if (it < 2 || it > 28) 70.0 else if (it % 2 == 0) 100.0 else 40.0
        }
        val r = sessionOf(ref, flat(70.0))
        val q = qualityFromSession(
            r, flat(0.0), referenceNoiseDb = flat(0.0),
            referenceCalRangeHz = FULL_CAL, dspVerifiedBySignal = true,
        )
        val out = calibrateFromSession(r, q, CalibrationSettings(maxCorrectionDb = 0.000001))
            .getOrThrow()

        // **전제부터 확인한다** — 정말 그 분기에 닿았는가.
        assertTrue("정규화 점은 남아 있다", out.normalizeSupportPoints > 0)
        assertEquals("최종 유효점은 하나도 없다", 0, out.correction.validCount)

        val final = judgeCalibration(q, out)
        assertEquals(QualityVerdict.Fail, final.verdict)
        assertTrue(
            "자리가 남지 않았다고 말해야 한다: ${final.reasonsKo}",
            final.reasonsKo.any { it.contains("보정이 걸리는 자리가 하나도") },
        )
    }

    /** 멀쩡한 평탄 입력은 **그대로 통과해야** 한다 — 관문이 과하지 않게. */
    @Test
    fun `평탄한 입력은 최종 승인을 받는다`() {
        val r = sessionOf(flat(70.0), flat(70.0))
        val q = goodQuality(r)
        val out = calibrateFromSession(r, q).getOrThrow()

        assertEquals("전 대역이 지원된다", n, out.supportedBands.size)
        val final = judgeCalibration(q, out)
        assertEquals(QualityVerdict.Pass, final.verdict)
        assertTrue(final.mayAutoApply)
    }

    /**
     * **지원 대역은 축 해상도에 흔들리지 않아야 한다.**
     *
     * 점으로 세면 옥타브당 점 수를 바꿀 때 같은 지원 구간이 다른 수로
     * 나온다. 밴드로 세는 까닭이다.
     */
    @Test
    fun `축 해상도를 바꿔도 지원 대역 수가 같다`() {
        val r = sessionOf(flat(70.0), flat(70.0))
        val q = goodQuality(r)
        val coarse = calibrateFromSession(r, q, CalibrationSettings(pointsPerOctave = 6)).getOrThrow()
        val fine = calibrateFromSession(r, q, CalibrationSettings(pointsPerOctave = 24)).getOrThrow()

        assertTrue("점 수는 달라야 한다", coarse.correction.size != fine.correction.size)
        assertEquals(
            "그래도 지원 대역 수는 같아야 한다",
            coarse.supportedBands.size,
            fine.supportedBands.size,
        )
    }

    /** 절반만 살아 있는 밴드는 **지원된다고 부르지 않는다.** */
    @Test
    fun `한 점이라도 못 믿으면 그 대역은 지원되지 않는다`() {
        val r = sessionOf(flat(70.0), flat(70.0), flat(70.0), calRange = 200.0..10_000.0)
        val q = goodQuality(r)
        val out = calibrateFromSession(r, q).getOrThrow()

        // CAL 밖 대역은 빠지고, 경계에 걸친 대역도 빠진다.
        assertTrue("전 대역은 아니다", out.supportedBands.size < n)
        out.supportedBands.forEach { b ->
            val lo = ThirdOctave.lowerEdge(b)
            val hi = ThirdOctave.upperEdge(b)
            out.correction.hz.indices
                .filter { out.correction.hz[it] in lo..hi }
                .forEach {
                    assertTrue("지원 대역 안은 모두 유효해야 한다", out.correction.valid[it])
                }
        }
    }

    /** 평활을 지나도 **무효 구간이 살아 있어야** 한다. */
    @Test
    fun `평활 뒤에도 무효 구간이 남는다`() {
        val r = sessionOf(flat(70.0), flat(70.0), flat(70.0), calRange = 200.0..10_000.0)
        val q = goodQuality(r)
        val out = calibrateFromSession(r, q).getOrThrow()
        assertTrue("무효 구간이 있어야 한다", out.correction.valid.any { !it })
        assertTrue("유효 구간도 있어야 한다", out.correction.valid.any { it })
    }

    // ------------------------------------------------------------------
    // 품질 보고
    // ------------------------------------------------------------------

    @Test
    fun `세션의 흐름과 편차가 품질 보고로 넘어간다`() {
        val s = CalibrationSession()
        repeat(3) { s.recordReference(MeasureStep.ReferenceBefore, refSpectrum(flat(70.0))) }
        s.record(MeasureStep.Target, flat(69.0))
        s.record(MeasureStep.Target, flat(70.5))
        repeat(3) { s.recordReference(MeasureStep.ReferenceAfter, refSpectrum(flat(71.0))) }
        val r = s.result()!!

        val q = qualityFromSession(r, noiseDb = flat(40.0))
        assertEquals(r.repeatStdevDb!!, q.repeatStdevDb!!, 1e-9)
        assertEquals(r.referenceDriftDb, q.referenceDriftDb!!, 1e-9)
        assertEquals(r.referenceBandDriftDb, q.referenceBandDriftDb!!, 1e-9)
        assertEquals(r.minKeptFramesPerStep, q.minFramesPerStep)
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
