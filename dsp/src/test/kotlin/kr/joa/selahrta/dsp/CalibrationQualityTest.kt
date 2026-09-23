package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **못 믿을 측정은 저장되지 않는다**(S23 개별 교정 지시서 4장·7.5).
 *
 * 이 시험들의 기준점은 **2026-09-23 사무실 실측**이다. 가장 센 대역에서도
 * SNR 이 9dB 였고, 그 곡선은 저역이 통째로 에어컨 소음의 모양이었을
 * 것이다. **그런데 그래프는 멀쩡해 보인다** — 그래서 막는 쪽을 먼저
 * 만들었고, 여기서 그날 조건이 실제로 걸리는지 본다.
 */
class CalibrationQualityTest {

    /** 1/3옥타브 31밴드 비슷하게. */
    private fun bands(snrDb: Double, n: Int = 31) = (0 until n).map {
        val hz = 20.0 * Math.pow(2.0, it / 3.0)
        BandNoise(hz, signalDb = 80.0, noiseDb = 80.0 - snrDb)
    }

    /**
     * **반복성 쪽은 멀쩡한** 보고. SNR 만 보는 시험들이 쓴다.
     *
     * 따로 둔 까닭: 반복성을 말할 근거가 없는 보고는 이제 Pass 가 되지
     * 않는다(독립 검증 CP01). 그건 의도된 것이라, 관문을 느슨하게 하는
     * 대신 **표본을 온전하게** 만든다 — 장을 충분히 모았고 세 단계가
     * 모두 안정적이었고 기준이 흐르지 않은 경우다.
     */
    private fun report(
        bands: List<BandNoise>,
        policy: QualityPolicy = QualityPolicy(),
        dspVerifiedBySignal: Boolean = true,
        repeatSpreadDb: Double? = 0.5,
        referenceDriftDb: Double? = 0.2,
        referenceBandDriftDb: Double? = 0.4,
        noStableFrames: Boolean = false,
        minFramesPerStep: Int? = 16,
        clipped: Boolean = false,
        // 기준 경로도 조용했던 경우. 안 주면 「모른다」가 되어 Pass 가
        // 나오지 않는다(독립 검증 RCP02).
        referenceBands: List<BandNoise>? = bands(25.0, bands.size),
        referenceCalRangeHz: ClosedFloatingPointRange<Double>? = null,
    ) = QualityReport(
        bands = bands,
        repeatSpreadDb = repeatSpreadDb,
        referenceDriftDb = referenceDriftDb,
        referenceBandDriftDb = referenceBandDriftDb,
        noStableFrames = noStableFrames,
        minFramesPerStep = minFramesPerStep,
        referenceBands = referenceBands,
        referenceCalRangeHz = referenceCalRangeHz,
        clipped = clipped,
        dspVerifiedBySignal = dspVerifiedBySignal,
        policy = policy,
    )

    @Test
    fun `SNR 은 신호에서 배경을 뺀 값이다`() {
        assertEquals(20.0, BandNoise(1000.0, 80.0, 60.0).snrDb, 1e-9)
        assertEquals(-3.0, BandNoise(1000.0, 57.0, 60.0).snrDb, 1e-9)
    }

    // ------------------------------------------------------------------
    // 그날의 조건
    // ------------------------------------------------------------------

    /**
     * **2026-09-23 사무실 조건은 막힌다.**
     *
     * 가장 센 대역이 9dB 였으니 나머지는 더 나빴다. 문턱 12dB 를 넘는
     * 대역이 하나도 없다.
     */
    @Test
    fun `사무실 9dB 조건은 저장되지 않는다`() {
        val r = judgeQuality(QualityReport(bands(snrDb = 9.0), dspVerifiedBySignal = true))
        assertEquals(QualityVerdict.Fail, r.verdict)
        assertFalse("저장하면 안 된다", r.maySave)
        assertFalse(r.mayAutoApply)
        assertTrue(
            "왜 막혔는지 적어야 한다: ${r.reasonsKo}",
            r.reasonsKo.any { it.contains("쓸 수 있는 대역") },
        )
        assertTrue(
            "무엇을 하라고 알려야 한다",
            r.reasonsKo.any { it.contains("배경 소음") || it.contains("신호를 키우") },
        )
    }

    @Test
    fun `조용한 조건은 통과한다`() {
        val r = judgeQuality(report(bands(snrDb = 25.0)))
        assertEquals(QualityVerdict.Pass, r.verdict)
        assertTrue(r.maySave)
        assertTrue(r.mayAutoApply)
    }

    // ------------------------------------------------------------------
    // 대역별로 가른다
    // ------------------------------------------------------------------

    /** **저역만 나쁜 경우** — 흔하다. 쓸 수 있는 범위를 좁혀 적는다. */
    @Test
    fun `저역만 나쁘면 범위를 좁혀 적는다`() {
        val b = (0 until 31).map {
            val hz = 20.0 * Math.pow(2.0, it / 3.0)
            // 200Hz 아래는 배경이 세다.
            val snr = if (hz < 200.0) 5.0 else 25.0
            BandNoise(hz, 80.0, 80.0 - snr)
        }
        val r = judgeQuality(report(b))

        assertTrue("대부분 쓸 수 있으므로 버리지는 않는다", r.maySave)
        assertEquals(QualityVerdict.Degraded, r.verdict)
        assertTrue(
            "좁은 범위를 알려야 한다: ${r.reasonsKo}",
            r.reasonsKo.any { it.contains("보정이 걸리는 범위") },
        )
        // **승인 범위**로 본다 — 대상만 보면 기준·CAL 제한이 숨는다(RCP-F01).
        val range = r.report.approvedRangeHz!!
        assertTrue("200Hz 아래는 빠져야 한다", range.start >= 200.0)
    }

    @Test
    fun `쓸 수 있는 대역과 범위를 센다`() {
        val b = listOf(
            BandNoise(100.0, 80.0, 79.0), // SNR 1 — 못 쓴다
            BandNoise(1000.0, 80.0, 50.0), // 30
            BandNoise(8000.0, 80.0, 60.0), // 20
        )
        val rep = QualityReport(b)
        assertEquals(listOf(false, true, true), rep.usable)
        assertEquals(2, rep.usableCount)
        assertEquals(1000.0, rep.usableRangeHz!!.start, 1e-9)
        assertEquals(8000.0, rep.usableRangeHz!!.endInclusive, 1e-9)
        assertEquals(1.0, rep.worstSnrDb!!, 1e-9)
        assertEquals(30.0, rep.bestSnrDb!!, 1e-9)
    }

    // ------------------------------------------------------------------
    // 막는 조건들
    // ------------------------------------------------------------------

    /** **잘린 측정은 쓸 수 없다.** 잘린 구간은 실제보다 낮게 나온다. */
    @Test
    fun `잘렸으면 버린다`() {
        val r = judgeQuality(
            QualityReport(bands(25.0), clipped = true, dspVerifiedBySignal = true),
        )
        assertEquals(QualityVerdict.Fail, r.verdict)
        assertTrue(r.reasonsKo.any { it.contains("GAIN") })
    }

    /** 되풀이해 잰 값이 벌어지면 무언가 움직인 것이다. */
    @Test
    fun `반복이 벌어지면 버린다`() {
        val r = judgeQuality(
            QualityReport(bands(25.0), repeatSpreadDb = 3.5, dspVerifiedBySignal = true),
        )
        assertEquals(QualityVerdict.Fail, r.verdict)
        assertTrue(r.reasonsKo.any { it.contains("반복") })
    }

    /**
     * **기준 전후 편차** — 지시서 3.2 의 「기준 → 대상 → 기준」 순서가
     * 있는 까닭이다. 앞뒤가 다르면 재는 동안 환경이 변한 것이다.
     */
    @Test
    fun `기준이 앞뒤로 달라지면 버린다`() {
        val r = judgeQuality(
            QualityReport(bands(25.0), referenceDriftDb = -1.8, dspVerifiedBySignal = true),
        )
        assertEquals(QualityVerdict.Fail, r.verdict)
        assertTrue(r.reasonsKo.any { it.contains("기준 재측정") })
    }

    @Test
    fun `허용 안이면 통과한다`() {
        val r = judgeQuality(
            report(bands(25.0), repeatSpreadDb = 1.0, referenceDriftDb = 0.4),
        )
        assertEquals(QualityVerdict.Pass, r.verdict)
    }

    // ------------------------------------------------------------------
    // ★ 승인은 교집합으로 한다 (독립 검증 RCP-F01)
    // ------------------------------------------------------------------

    /**
     * **RCP-F01 반례 ①** — 각 경로는 60% 를 넘는데 교집합은 25.8% 다.
     *
     * 잡음이 두 경로의 **서로 다른 구간**을 갉아먹으면 이렇게 된다.
     * 예전에는 대상 비율과 기준 비율을 따로 봐서 Pass 가 났다.
     */
    @Test
    fun `각각은 충분해도 교집합이 모자라면 막는다`() {
        val hz = { i: Int -> 20.0 * Math.pow(2.0, i / 3.0) }
        // 대상은 0~18 과 30 이 좋고, 기준은 12~30 이 좋다 → 겹치는 것은 12~18.
        val target = (0 until 31).map {
            BandNoise(hz(it), 70.0, if (it <= 18 || it == 30) 40.0 else 70.0)
        }
        val reference = (0 until 31).map {
            BandNoise(hz(it), 70.0, if (it >= 12) 40.0 else 70.0)
        }
        val rep = report(target, referenceBands = reference)

        // **전제부터 확인한다** — 각자는 정말 충분한가.
        assertTrue("대상은 60% 를 넘는다", rep.usableRatio > 0.6)
        assertTrue("기준도 60% 를 넘는다", rep.referenceUsable.count { it } / 31.0 > 0.6)
        // 12~18 의 일곱에 30 이 더해져 여덟이다 — Codex probe 가 보고한 수와 같다.
        assertEquals("겹치는 것은 여덟뿐이다", 8, rep.approvedCount)

        val r = judgeQuality(rep)
        assertEquals(QualityVerdict.Fail, r.verdict)
        assertFalse(r.mayAutoApply)
        assertTrue(
            "어느 쪽이 문제인지 짚어 줘야 한다: ${r.reasonsKo}",
            r.reasonsKo.any { it.contains("대상") && it.contains("기준") },
        )
    }

    /**
     * **RCP-F01 반례 ②** — CAL 이 좁으면 보정도 거기까지다.
     *
     * 예전에는 CAL 범위가 곡선 생성에만 쓰여, 대상 SNR 이 전 대역에서
     * 좋으면 좁은 범위 경고조차 없었다.
     */
    @Test
    fun `CAL 범위가 좁으면 승인에 반영된다`() {
        val r = judgeQuality(report(bands(25.0), referenceCalRangeHz = 1000.0..1300.0))
        assertEquals(QualityVerdict.Fail, r.verdict)
        assertTrue(
            "CAL 범위를 적어야 한다: ${r.reasonsKo}",
            r.reasonsKo.any { it.contains("CAL 1000~1300Hz") },
        )
    }

    /** 조금 좁은 정도면 막지는 않되 **범위를 알린다.** */
    @Test
    fun `CAL 이 조금 좁으면 범위를 알린다`() {
        // 20~1300Hz 는 19/31 = 61.3% — 비율 관문(60%)은 넘고 위끝은 8kHz 미만이다.
        val r = judgeQuality(report(bands(25.0), referenceCalRangeHz = 20.0..1_300.0))
        assertEquals(QualityVerdict.Degraded, r.verdict)
        assertTrue(
            "${r.reasonsKo}",
            r.reasonsKo.any { it.contains("보정이 걸리는 범위") },
        )
    }

    @Test
    fun `두 경로가 다 넓으면 통과한다`() {
        val r = judgeQuality(report(bands(25.0), referenceCalRangeHz = 20.0..20_000.0))
        assertEquals(QualityVerdict.Pass, r.verdict)
        assertTrue(r.mayAutoApply)
    }

    @Test
    fun `교집합이 0 이면 막는다`() {
        val hz = { i: Int -> 20.0 * Math.pow(2.0, i / 3.0) }
        val target = (0 until 31).map { BandNoise(hz(it), 70.0, if (it < 15) 40.0 else 70.0) }
        val reference = (0 until 31).map { BandNoise(hz(it), 70.0, if (it >= 15) 40.0 else 70.0) }
        val rep = report(target, referenceBands = reference)
        assertEquals(0, rep.approvedCount)
        assertEquals(QualityVerdict.Fail, judgeQuality(rep).verdict)
    }

    // ------------------------------------------------------------------
    // 반복성 — 「모른다」를 「괜찮다」로 바꾸지 않는다 (독립 검증 CP01)
    // ------------------------------------------------------------------

    /**
     * **광대역이 같아도 모양은 변했을 수 있다.**
     *
     * 이걸 안 보면 환경 변화가 마이크 특성으로 기록된다 — 반례에서
     * 광대역 흐름 0dB 에 6.73dB 의 보정이 생겼다.
     */
    @Test
    fun `기준이 대역별로 크게 달라지면 버린다`() {
        val r = judgeQuality(
            report(bands(25.0), referenceDriftDb = 0.0, referenceBandDriftDb = 6.0),
        )
        assertEquals(QualityVerdict.Fail, r.verdict)
        assertTrue("${r.reasonsKo}", r.reasonsKo.any { it.contains("모양") })
    }

    @Test
    fun `안정된 장이 하나도 없으면 버린다`() {
        val r = judgeQuality(report(bands(25.0), noStableFrames = true))
        assertEquals(QualityVerdict.Fail, r.verdict)
        assertTrue("${r.reasonsKo}", r.reasonsKo.any { it.contains("안정된 구간") })
    }

    @Test
    fun `장이 모자라면 버린다`() {
        val r = judgeQuality(report(bands(25.0), minFramesPerStep = 2))
        assertEquals(QualityVerdict.Fail, r.verdict)
        assertTrue("${r.reasonsKo}", r.reasonsKo.any { it.contains("2개") })
    }

    /** **모르는 것은 통과시키지 않는다** — 0 도 아니고 Pass 도 아니다. */
    @Test
    fun `벌어짐을 모르면 통과시키지 않는다`() {
        val r = judgeQuality(report(bands(25.0), repeatSpreadDb = null))
        assertEquals(QualityVerdict.Degraded, r.verdict)
        assertFalse("자동 적용은 막는다", r.mayAutoApply)
        assertTrue("${r.reasonsKo}", r.reasonsKo.any { it.contains("재지 못했") })
    }

    @Test
    fun `장 수를 모르면 통과시키지 않는다`() {
        val r = judgeQuality(report(bands(25.0), minFramesPerStep = null))
        assertEquals(QualityVerdict.Degraded, r.verdict)
        assertTrue("${r.reasonsKo}", r.reasonsKo.any { it.contains("모은 장 수") })
    }

    // ------------------------------------------------------------------
    // DSP 확인 — 설정값만으로는 모자란다
    // ------------------------------------------------------------------

    /**
     * **신호로 확인하지 않았으면 자동 적용까지는 못 간다**(지시서 2장).
     *
     * 「AGC 가 세션에 없다」는 설정값이다. 실제로 이득이 시간에 따라
     * 움직이지 않는지는 신호를 재 봐야 안다.
     */
    @Test
    fun `DSP 를 신호로 확인 안 했으면 자동 적용을 막는다`() {
        val r = judgeQuality(report(bands(25.0), dspVerifiedBySignal = false))
        assertEquals(QualityVerdict.Degraded, r.verdict)
        assertTrue("저장은 된다", r.maySave)
        assertFalse("자동 적용은 안 된다", r.mayAutoApply)
        assertTrue(r.reasonsKo.any { it.contains("잔여 DSP") })
    }

    // ------------------------------------------------------------------

    /** **막는 이유가 여럿이면 전부 적는다.** 하나 고치고 또 막히면 지친다. */
    @Test
    fun `막는 이유를 전부 적는다`() {
        val r = judgeQuality(
            QualityReport(
                bands(snrDb = 3.0),
                repeatSpreadDb = 5.0,
                clipped = true,
                dspVerifiedBySignal = false,
            ),
        )
        assertEquals(QualityVerdict.Fail, r.verdict)
        assertTrue("셋 이상 나와야 한다: ${r.reasonsKo}", r.reasonsKo.size >= 3)
        assertTrue(r.reasonsKo.any { it.contains("잘렸") })
        assertTrue(r.reasonsKo.any { it.contains("반복") })
        assertTrue(r.reasonsKo.any { it.contains("쓸 수 있는 대역") })
    }

    @Test
    fun `잰 것이 없으면 버린다`() {
        val r = judgeQuality(QualityReport(emptyList()))
        assertEquals(QualityVerdict.Fail, r.verdict)
        assertFalse(r.maySave)
    }

    /** **문턱은 결과와 함께 저장된다**(지시서 7.5). */
    @Test
    fun `쓴 기준을 함께 남긴다`() {
        val p = QualityPolicy(minBandSnrDb = 15.0, minUsableBandRatio = 0.8)
        val r = judgeQuality(report(bands(20.0), policy = p))
        assertEquals(15.0, r.report.policy.minBandSnrDb, 0.0)
        assertEquals(0.8, r.report.policy.minUsableBandRatio, 0.0)
        assertEquals("20dB 는 15dB 문턱을 넘는다", QualityVerdict.Pass, r.verdict)
    }

    /** 문턱을 올리면 같은 측정이 막힌다 — 정책이 실제로 쓰이는지 본다. */
    @Test
    fun `문턱을 올리면 같은 측정이 막힌다`() {
        val b = bands(20.0)
        assertEquals(
            QualityVerdict.Pass,
            judgeQuality(report(b)).verdict,
        )
        assertEquals(
            QualityVerdict.Fail,
            judgeQuality(
                QualityReport(
                    b,
                    policy = QualityPolicy(minBandSnrDb = 25.0),
                    dspVerifiedBySignal = true,
                ),
            ).verdict,
        )
    }
}
