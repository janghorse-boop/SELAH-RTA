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
        calApplied: Boolean = true,
    ): SessionResult {
        val s = CalibrationSession(referenceCalApplied = calApplied)
        repeat(repeats) { s.record(MeasureStep.ReferenceBefore, before) }
        repeat(repeats) { s.record(MeasureStep.Target, target) }
        repeat(repeats) { s.record(MeasureStep.ReferenceAfter, after) }
        return s.result()!!
    }

    /** 앞뒤 기준이 같은 경우. 흐름 0 이라 다른 것만 보게 된다. */
    private fun sessionOf(reference: DoubleArray, target: DoubleArray): SessionResult =
        sessionOf(reference, target, reference)

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
     * **다 버려지면 전부 쓰되, 그랬다고 적는다**(독립 검증 CP01).
     *
     * 장이 둘뿐이고 서로 멀면 중앙값이 그 가운데라 둘 다 벗어난다. 빈손으로
     * 돌려주면 세션이 통째로 사라지므로 전부 쓴다 — 다만 `droppedFrames`
     * 만 보면 0 이라 **「다 안정적이었다」로 읽힌다.** 정반대다.
     */
    @Test
    fun `다 버려지면 전부 쓰되 그랬다고 적는다`() {
        val s = CalibrationSession()
        s.record(MeasureStep.ReferenceBefore, flat(70.0))
        s.record(MeasureStep.Target, flat(40.0))
        s.record(MeasureStep.Target, flat(90.0))
        s.record(MeasureStep.ReferenceAfter, flat(70.0))

        val t = s.result()!!.target
        assertEquals("빈손으로 돌려주지 않는다", 2, t.keptFrames)
        assertEquals("버린 것은 정말 없다", 0, t.droppedFrames)
        assertTrue("안정된 장이 없었다고 적어야 한다", t.noStableFrames)
        assertEquals("벌어짐이 그대로 남아야 한다", 50.0, t.levelSpreadDb!!, 1e-9)
    }

    /**
     * **CP01 반례 ①** — 기준 단계만 불안정하고 대상은 얌전한 경우.
     *
     * 예전에는 `repeatSpreadDb` 에 대상의 벌어짐만 담겨서, 기준이 50dB
     * 출렁여도 **Pass** 가 나왔다.
     */
    @Test
    fun `기준 단계가 불안정하면 막는다`() {
        val s = CalibrationSession(referenceCalApplied = true)
        for (step in listOf(MeasureStep.ReferenceBefore, MeasureStep.ReferenceAfter)) {
            repeat(4) { s.record(step, flat(40.0)); s.record(step, flat(90.0)) }
        }
        repeat(8) { s.record(MeasureStep.Target, flat(60.0)) }
        val r = s.result()!!

        assertTrue("기준이 안정되지 않았다", r.noStableFrames)
        val judged = judgeQuality(qualityFromSession(r, flat(10.0), dspVerifiedBySignal = true))
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

        val judged = judgeQuality(qualityFromSession(r, flat(10.0), dspVerifiedBySignal = true))
        assertEquals(QualityVerdict.Fail, judged.verdict)
        assertTrue(
            "모양이 변했다고 말해야 한다: ${judged.reasonsKo}",
            judged.reasonsKo.any { it.contains("모양") },
        )
    }

    /** 세 단계 중 **가장 나쁜** 벌어짐을 쓴다 — 대상만 보지 않는다. */
    @Test
    fun `세 단계 중 가장 나쁜 벌어짐을 쓴다`() {
        val s = CalibrationSession(referenceCalApplied = true)
        repeat(4) { s.record(MeasureStep.ReferenceBefore, flat(70.0)) }
        repeat(4) { s.record(MeasureStep.ReferenceBefore, flat(71.5)) } // 1.5dB
        repeat(8) { s.record(MeasureStep.Target, flat(60.0)) } // 0dB
        repeat(4) { s.record(MeasureStep.ReferenceAfter, flat(70.0)) }
        repeat(4) { s.record(MeasureStep.ReferenceAfter, flat(70.5)) } // 0.5dB

        assertEquals("기준 앞단의 1.5dB 가 이겨야 한다", 1.5, s.result()!!.repeatSpreadDb!!, 1e-9)
    }

    /** **장이 하나면 「0」이 아니라 「모른다」다.** */
    @Test
    fun `장이 하나면 벌어짐을 모른다`() {
        val r = sessionOf(flat(70.0), flat(68.0), flat(70.0), repeats = 1)
        assertNull("0 이 아니라 null 이어야 한다", r.target.levelSpreadDb)
        assertNull(r.repeatSpreadDb)

        val judged = judgeQuality(qualityFromSession(r, flat(40.0), dspVerifiedBySignal = true))
        assertTrue("통과시키면 안 된다", judged.verdict != QualityVerdict.Pass)
    }

    @Test
    fun `장이 모자라면 막는다`() {
        val r = sessionOf(flat(70.0), flat(68.0), flat(70.0), repeats = 3)
        val judged = judgeQuality(qualityFromSession(r, flat(40.0), dspVerifiedBySignal = true))
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

    /** 두 경로 모두 배경이 조용했던 경우. 기준 배경까지 준다(RCP02). */
    private fun goodQuality(r: SessionResult) =
        qualityFromSession(r, flat(40.0), referenceNoiseDb = flat(40.0), dspVerifiedBySignal = true)

    /**
     * **칸 단위로 걸지 않았으면 만들지 않는다.**
     *
     * 예전에는 밴드 레벨에서 중심주파수 응답만 빼는 `applyMicCalibration`
     * 이 있었는데, 그건 밴드 안에서 CAL 이 일정할 때만 맞다. 이 저장소가
     * 이미 적어 둔 교훈을(R05) 새 경로에서 되살렸던 자리다.
     */
    @Test
    fun `기준 CAL 을 칸에 걸지 않았으면 거절한다`() {
        val r = sessionOf(flat(70.0), flat(70.0), flat(70.0), calApplied = false)
        val out = calibrateFromSession(r, goodQuality(r), null)
        assertTrue("거절해야 한다", out.isFailure)
        assertTrue(
            "까닭을 말해야 한다: ${out.exceptionOrNull()?.message}",
            out.exceptionOrNull()?.message?.contains("칸") == true,
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

        val rightOut = calibrateFromSession(right, goodQuality(right), null).getOrThrow()
        val wrongOut = calibrateFromSession(wrong, goodQuality(wrong), null).getOrThrow()

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
            r, noise, referenceNoiseDb = flat(40.0), dspVerifiedBySignal = true,
        )

        assertFalse("그 대역은 못 쓴다", q.usable[20])
        assertTrue("나머지는 쓸 수 있어 전체는 통과한다", q.usableRatio > 0.9)

        val out = calibrateFromSession(r, q, null).getOrThrow()
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
        val r = sessionOf(flat(70.0), flat(70.0), flat(70.0))
        val q = goodQuality(r)
        val out = calibrateFromSession(r, q, 200.0..10_000.0).getOrThrow()

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
        val q = qualityFromSession(r, noiseDb = null, referenceNoiseDb = flat(40.0))
        val out = calibrateFromSession(r, q, null)
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
        val r = sessionOf(slope, slope)
        // **배경을 아주 조용하게 둔다.** 40dB 로 두면 1414Hz 아래가 SNR 로
        // 먼저 잘려 두 마스크가 **우연히 같아지고**, 그러면 이 시험은
        // 아무것도 재지 않는다(실제로 그랬다 — 되돌린 판이 통과했다).
        // 여기서 지지구간을 가르는 것은 오직 CAL 범위여야 한다.
        val q = qualityFromSession(
            r, flat(0.0), referenceNoiseDb = flat(0.0), dspVerifiedBySignal = true,
        )

        // CAL 은 기준에만 걸린다 — 여기서 지지구간이 갈린다.
        val out = calibrateFromSession(r, q, 1000.0..16_000.0).getOrThrow()

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
            r, noise, referenceNoiseDb = noise, dspVerifiedBySignal = true,
        )

        val out = calibrateFromSession(r, q, 20.0..20_000.0)
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
        val out = calibrateFromSession(r, goodQuality(r), null).getOrThrow()
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
        val q = qualityFromSession(r, flat(40.0), dspVerifiedBySignal = true)
        assertFalse(q.referenceSnrKnown)

        val out = calibrateFromSession(r, q, null)
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
            r, flat(40.0), referenceNoiseDb = refNoise, dspVerifiedBySignal = true,
        )

        assertTrue("대상은 그 대역을 쓸 수 있다", q.usable[20])
        assertFalse("기준은 못 쓴다", q.referenceUsable[20])
        assertFalse("그래서 함께 쓸 수 없다", q.bothUsable[20])

        val out = calibrateFromSession(r, q, null).getOrThrow()
        val i = nearest(out.correction, ThirdOctave.exactCenter(20))
        assertFalse("기준이 못 믿을 자리에 보정이 남으면 안 된다", out.correction.valid[i])
    }

    @Test
    fun `기준 경로가 통째로 시끄러우면 막는다`() {
        val r = sessionOf(flat(70.0), flat(70.0))
        val q = qualityFromSession(
            r, flat(40.0), referenceNoiseDb = flat(70.0), dspVerifiedBySignal = true,
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
        val s = CalibrationSession(referenceCalApplied = true)
        for (step in MeasureStep.entries) {
            repeat(3) { s.record(step, flat(0.0)) }
            repeat(2) { s.record(step, flat(50.0)) }
            repeat(3) { s.record(step, flat(100.0)) }
        }
        val r = s.result()!!

        assertEquals("넣은 것은 여덟", 8, r.minTotalFramesPerStep)
        assertEquals("남은 것은 둘", 2, r.minKeptFramesPerStep)
        assertEquals(2, r.target.keptFrames)

        val judged = judgeQuality(
            qualityFromSession(r, flat(0.0), referenceNoiseDb = flat(0.0), dspVerifiedBySignal = true),
        )
        assertEquals(QualityVerdict.Fail, judged.verdict)
        assertTrue(
            "몇 장을 썼는지 말해야 한다: ${judged.reasonsKo}",
            judged.reasonsKo.any { it.contains("2개") },
        )
    }

    /** 평활을 지나도 **무효 구간이 살아 있어야** 한다. */
    @Test
    fun `평활 뒤에도 무효 구간이 남는다`() {
        val r = sessionOf(flat(70.0), flat(70.0), flat(70.0))
        val q = goodQuality(r)
        val out = calibrateFromSession(r, q, 200.0..10_000.0).getOrThrow()
        assertTrue("무효 구간이 있어야 한다", out.correction.valid.any { !it })
        assertTrue("유효 구간도 있어야 한다", out.correction.valid.any { it })
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
        assertEquals(r.repeatSpreadDb!!, q.repeatSpreadDb!!, 1e-9)
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
