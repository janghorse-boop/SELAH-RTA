package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * **dB 를 그냥 평균하면 틀린다** — 이 시험들의 요점이다.
 *
 * dB 는 로그라 산술 평균이 에너지 평균이 아니다. 핑크 노이즈는 순간마다
 * 출렁이므로 그 차이가 그대로 쌓인다: dB 평균은 실제보다 **낮게** 나오고
 * 출렁임이 큰 대역일수록 더 낮아진다. 그러면 「저역이 모자란 마이크」로
 * 보여 **없던 보정이 걸린다.**
 *
 * 그리고 그 결과는 그래프만 봐서는 멀쩡하다.
 */
class SpectrumAverageTest {

    private fun frame(vararg db: Double) = db

    @Test
    fun `한 장도 없으면 답이 없다`() {
        val a = BandAccumulator(3)
        assertNull(a.meanDb())
        assertNull(a.spreadDb())
        assertNull(a.stdDevDb())
        assertEquals(0, a.count)
    }

    @Test
    fun `한 장이면 그 값 그대로다`() {
        val a = BandAccumulator(3)
        a.add(frame(10.0, 20.0, 30.0))
        val m = a.meanDb()!!
        assertEquals(10.0, m[0], 1e-9)
        assertEquals(20.0, m[1], 1e-9)
        assertEquals(30.0, m[2], 1e-9)
        assertNull("한 장으로는 흔들림을 못 잰다", a.stdDevDb())
    }

    /**
     * **0dB 와 20dB 의 에너지 평균은 17dB 다.** dB 평균이면 10dB 가
     * 나오는데, 그건 7dB 나 낮다.
     */
    @Test
    fun `에너지로 평균한다 — dB 평균이 아니다`() {
        val a = BandAccumulator(1)
        a.add(frame(0.0))
        a.add(frame(20.0))
        val mean = a.meanDb()!![0]

        // (10^0 + 10^2)/2 = 50.5 → 10log10(50.5) = 17.03
        assertEquals(17.03, mean, 0.01)
        assertTrue("dB 평균(10dB)보다 훨씬 커야 한다", mean > 16.0)
    }

    /**
     * **출렁임이 클수록 두 평균의 차이가 벌어진다.**
     *
     * 이것이 저역에서 특히 문제가 되는 까닭이다 — 저역은 FFT 창 하나에
     * 주기가 몇 개 안 들어가 잴 때마다 크게 흔들린다.
     */
    @Test
    fun `출렁임이 클수록 dB 평균이 더 낮아진다`() {
        val rnd = Random(11)
        for (spread in listOf(1.0, 6.0, 12.0)) {
            val acc = BandAccumulator(1)
            var dbSum = 0.0
            val n = 500
            repeat(n) {
                val db = rnd.nextDouble(-spread, spread)
                acc.add(frame(db))
                dbSum += db
            }
            val energyMean = acc.meanDb()!![0]
            val dbMean = dbSum / n
            val gap = energyMean - dbMean
            println("AVG_GAP spread=±${spread}dB  energy=${"%.2f".format(energyMean)}  " +
                "dB=${"%.2f".format(dbMean)}  차이=${"%.2f".format(gap)}dB")
            assertTrue("에너지 평균이 더 커야 한다", gap > 0)
            if (spread >= 6.0) {
                assertTrue("출렁임이 크면 차이도 커야 한다 ($gap)", gap > 0.5)
            }
        }
    }

    @Test
    fun `대역마다 흔들림을 센다`() {
        val a = BandAccumulator(2)
        // 0번은 늘 같고, 1번은 ±5dB 로 흔들린다.
        a.add(frame(50.0, 45.0))
        a.add(frame(50.0, 55.0))
        a.add(frame(50.0, 45.0))
        a.add(frame(50.0, 55.0))

        val sd = a.stdDevDb()!!
        assertEquals("흔들리지 않는 대역은 0", 0.0, sd[0], 1e-9)
        assertEquals("±5dB 면 표준편차 5", 5.0, sd[1], 1e-9)

        val sp = a.spreadDb()!!
        assertEquals(0.0, sp[0], 1e-9)
        assertEquals(10.0, sp[1], 1e-9)
    }

    @Test
    fun `밴드 수가 다르면 막는다`() {
        val a = BandAccumulator(3)
        assertTrue(runCatching { a.add(frame(1.0, 2.0)) }.isFailure)
    }

    @Test
    fun `초기화하면 처음으로 돌아간다`() {
        val a = BandAccumulator(2)
        a.add(frame(10.0, 10.0))
        a.reset()
        assertEquals(0, a.count)
        assertNull(a.meanDb())
    }

    // ------------------------------------------------------------------
    // 튄 장 걸러 내기
    // ------------------------------------------------------------------

    /** **문 닫히는 소리 한 번**이 평균을 끌고 가면 안 된다. */
    @Test
    fun `튄 장을 걸러 낸다`() {
        val quiet = DoubleArray(31) { 50.0 }
        val bang = DoubleArray(31) { 75.0 }
        val frames = List(9) { quiet } + listOf(bang)

        val keep = keepStableFrames(frames, maxDeviationDb = 3.0)
        assertEquals("아홉 장만 남아야 한다", 9, keep.size)
        assertFalse("튄 장은 빠져야 한다", keep.contains(9))
    }

    /**
     * **중앙값을 쓰는 까닭.**
     *
     * 튄 장이 절반 가까이면 평균은 그쪽으로 끌려가 기준 자체가 오염된다.
     * 중앙값은 버틴다.
     */
    @Test
    fun `튄 장이 많아도 중앙값은 버틴다`() {
        val quiet = DoubleArray(31) { 50.0 }
        val bang = DoubleArray(31) { 80.0 }
        // 조용한 것 6, 튄 것 4 — 평균은 크게 올라가지만 중앙값은 조용한 쪽
        val frames = List(6) { quiet } + List(4) { bang }

        val keep = keepStableFrames(frames, maxDeviationDb = 3.0)
        assertEquals(6, keep.size)
        assertTrue("조용한 쪽이 남아야 한다", keep.all { it < 6 })
    }

    @Test
    fun `모두 고르면 모두 남긴다`() {
        val frames = List(5) { DoubleArray(31) { 50.0 } }
        assertEquals(listOf(0, 1, 2, 3, 4), keepStableFrames(frames))
    }

    @Test
    fun `빈 목록은 빈 채로 돌려준다`() {
        assertEquals(emptyList<Int>(), keepStableFrames(emptyList()))
    }

    /** 걸러 낸 뒤 평균하면 튄 장의 영향이 사라진다. */
    @Test
    fun `걸러 내고 평균하면 튐이 빠진다`() {
        val quiet = DoubleArray(31) { 50.0 }
        val bang = DoubleArray(31) { 80.0 }
        val frames = List(9) { quiet } + listOf(bang)

        val all = BandAccumulator(31).apply { frames.forEach { add(it) } }.meanDb()!![0]
        val kept = BandAccumulator(31).apply {
            keepStableFrames(frames).forEach { add(frames[it]) }
        }.meanDb()!![0]

        println("OUTLIER 전부=${"%.2f".format(all)}dB  걸러냄=${"%.2f".format(kept)}dB")
        assertEquals("걸러 내면 조용한 값이 나와야 한다", 50.0, kept, 1e-9)
        assertTrue("걸러 내지 않으면 끌려간다", all > 60.0)
    }

    // ------------------------------------------------------------------
    // 밴드 → 곡선
    // ------------------------------------------------------------------

    /**
     * **호칭 중심이 아니라 정확한 중심주파수를 쓴다.**
     *
     * 호칭 125Hz 의 실제 중심은 125.89Hz 다. 0.7% 차이지만, 두 곡선을
     * 뺄 때는 그 어긋남이 그대로 보정값이 된다.
     */
    @Test
    fun `정확한 중심주파수로 옮긴다`() {
        val pts = bandsToCurvePoints(DoubleArray(ThirdOctave.BAND_COUNT) { it.toDouble() })
        assertEquals(ThirdOctave.BAND_COUNT, pts.size)
        assertEquals(1000.0, pts[17].hz, 1e-9)
        assertEquals(17.0, pts[17].gainDb, 1e-9)

        val at125 = pts[8].hz
        assertTrue("호칭 125 가 아니라 정확한 중심이어야 한다 ($at125)", abs(at125 - 125.0) > 0.5)
        assertEquals(125.89, at125, 0.01)
    }

    @Test
    fun `밴드 수가 맞지 않으면 막는다`() {
        assertTrue(runCatching { bandsToCurvePoints(DoubleArray(10)) }.isFailure)
    }

    /** 옮긴 점들이 보정 계산에 그대로 들어간다. */
    @Test
    fun `옮긴 점으로 보정을 낼 수 있다`() {
        val flat = bandsToCurvePoints(DoubleArray(ThirdOctave.BAND_COUNT) { 70.0 })
        val tilted = bandsToCurvePoints(
            DoubleArray(ThirdOctave.BAND_COUNT) { 70.0 + if (it >= 24) -6.0 else 0.0 },
        )
        val out = calibrateResponse(flat, tilted)
        assertNotNull(out.correction)
        // 5kHz 위는 6dB 덜 잡았으므로 올려 준다.
        val i = out.correction.hz.indices.minByOrNull { abs(out.correction.hz[it] - 10000.0) }!!
        assertTrue("고역을 올려야 한다: ${out.correction.db[i]}", out.correction.db[i] > 4.0)
    }
}
