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
        val r = judgeQuality(QualityReport(bands(snrDb = 25.0), dspVerifiedBySignal = true))
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
        val r = judgeQuality(QualityReport(b, dspVerifiedBySignal = true))

        assertTrue("대부분 쓸 수 있으므로 버리지는 않는다", r.maySave)
        assertEquals(QualityVerdict.Degraded, r.verdict)
        assertTrue(
            "좁은 범위를 알려야 한다: ${r.reasonsKo}",
            r.reasonsKo.any { it.contains("믿을 수 있는 범위") },
        )
        val range = r.report.usableRangeHz!!
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
            QualityReport(
                bands(25.0),
                repeatSpreadDb = 1.0,
                referenceDriftDb = 0.4,
                dspVerifiedBySignal = true,
            ),
        )
        assertEquals(QualityVerdict.Pass, r.verdict)
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
        val r = judgeQuality(QualityReport(bands(25.0), dspVerifiedBySignal = false))
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
        val r = judgeQuality(QualityReport(bands(20.0), policy = p, dspVerifiedBySignal = true))
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
            judgeQuality(QualityReport(b, dspVerifiedBySignal = true)).verdict,
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
