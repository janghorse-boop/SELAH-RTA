package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **앞뒤 기준의 모양 변화는 「쓸 대역」에서만 본다.**
 *
 * ## 무엇이 문제였나 (2026-09-24 실측)
 *
 * 교정이 「기준 재측정이 **20Hz** 에서 앞과 2.0dB 다릅니다」로 거부됐다.
 * 그런데 같은 화면이 바로 아래에 「보정이 걸리는 범위는 **50Hz~6kHz**」
 * 라고 적고 있었다 — **쓰지도 않을 대역을 근거로 쓸 것을 버린 셈**이다.
 *
 * 20Hz 는 스피커가 소리를 내지 못하고 방 잡음만 남는 자리라, 거기 숫자가
 * 2dB 흔들리는 것은 측정이 나쁘다는 뜻이 아니라 **잰 적이 없다는 뜻**이다.
 *
 * ## 무엇을 지키는가
 *
 * SNR 이 서지 않는 대역은 판정에서 뺀다. 다만 **기준 쪽 배경을 안 쟀으면
 * 가릴 수가 없으므로 전부 본다** — 모르는 것을 괜찮다고 바꾸지 않는다.
 */
class DriftBandUsableTest {

    private val n = ThirdOctave.BAND_COUNT
    private val lowBand = 0 // 실제 중심 19.95Hz
    private val midBand = ThirdOctave.nearestBand(1_000.0)

    private fun flat(db: Double) = DoubleArray(n) { db }

    private fun refSpectrum(bandsDb: DoubleArray) =
        testReferenceSpectrum(bandsDb, 10.0..25_000.0)

    /** 앞뒤 기준이 [band] 에서만 [driftDb] 만큼 다른 세션. */
    private fun sessionWithDrift(band: Int, driftDb: Double): SessionResult {
        val s = CalibrationSession(maxFrameDeviationDb = 50.0)
        val after = flat(70.0).also { it[band] += driftDb }
        repeat(8) { s.recordReference(MeasureStep.ReferenceBefore, refSpectrum(flat(70.0))) }
        repeat(8) { s.record(MeasureStep.Target, flat(60.0)) }
        repeat(8) { s.recordReference(MeasureStep.ReferenceAfter, refSpectrum(after)) }
        return s.result()!!
    }

    /** 저역만 잡음에 묻힌 배경. 20Hz 는 SNR 이 안 서고 나머지는 선다. */
    private fun noiseHidingLowBand() = DoubleArray(n) { if (it == lowBand) 69.0 else 30.0 }

    @Test
    fun `SNR 이 안 서는 대역의 흔들림은 판정하지 않는다`() {
        val r = sessionWithDrift(lowBand, driftDb = 8.0)
        val q = qualityFromSession(
            session = r,
            noiseDb = flat(30.0),
            referenceNoiseDb = noiseHidingLowBand(),
            referenceCalRangeHz = 10.0..25_000.0,
            dspVerifiedBySignal = true,
        )
        assertTrue(
            "묻힌 대역이 판정에 올라왔다: ${q.referenceDriftBand} / ${q.referenceBandDriftDb}",
            (q.referenceBandDriftDb ?: 0.0) < 1.0,
        )
        assertTrue("묻힌 대역을 가리키면 안 된다", q.referenceDriftBand != lowBand)
    }

    @Test
    fun `SNR 이 서는 대역의 흔들림은 그대로 잡는다`() {
        // 접어서 없앤 것이 아니다. 쓸 대역이 흔들리면 여전히 막는다.
        val r = sessionWithDrift(midBand, driftDb = 8.0)
        val q = qualityFromSession(
            session = r,
            noiseDb = flat(30.0),
            referenceNoiseDb = noiseHidingLowBand(),
            referenceCalRangeHz = 10.0..25_000.0,
            dspVerifiedBySignal = true,
        )
        assertEquals("1kHz 가 잡혀야 한다", midBand, q.referenceDriftBand)
        assertEquals(8.0, q.referenceBandDriftDb!!, 1e-9)

        val judged = judgeQuality(q)
        assertTrue("통과시키면 안 된다", judged.verdict != QualityVerdict.Pass)
    }

    @Test
    fun `기준 배경을 안 쟀으면 모든 대역을 본다`() {
        // 가릴 근거가 없으면 가리지 않는다 — 「모른다」를 「괜찮다」로
        // 바꾸는 것이 이 저장소가 거듭 데인 자리다.
        val r = sessionWithDrift(lowBand, driftDb = 8.0)
        val q = qualityFromSession(
            session = r,
            noiseDb = flat(30.0),
            referenceNoiseDb = null,
            referenceCalRangeHz = 10.0..25_000.0,
            dspVerifiedBySignal = true,
        )
        assertNotNull(q.referenceBandDriftDb)
        assertEquals("가릴 수 없으면 저역도 본다", lowBand, q.referenceDriftBand)
        assertEquals(8.0, q.referenceBandDriftDb!!, 1e-9)
    }
}
