package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * **창을 씌우기 전에 블록의 DC 를 뺀다** (6회차 R6-03).
 *
 * DC 는 Hann 창을 지나며 0번 칸에만 머물지 않고 **1번 칸으로 샌다.**
 * `transferFunction()` 은 피크 후보에서 0번만 빼므로, 샌 1번 칸이 피크가 되어
 * 기준 에너지가 멀쩡한 나머지 칸을 「기준이 약함」으로 지웠다. 검토자 반례:
 * DC 0.1 + 작은 AC 에서 유효 칸 4096 → 1.
 *
 * 1번 칸을 가리거나 문턱(`refFloorDb`)을 낮춰 통과시키지 않는다 — 그건 관문을
 * 고치는 일이다. 빼는 것은 **블록 평균**이고, 기준·측정 양쪽에 같이 한다.
 */
class SpectralAveragerDcTest {

    private val n = 8192

    private fun estimate(dc: Double): TransferResult {
        val r = Random(71)
        val a = SpectralAverager(n)
        repeat(16) {
            val ac = DoubleArray(n) { r.nextDouble(-1e-4, 1e-4) }
            val x = DoubleArray(n) { ac[it] + dc }
            a.addBlock(x, 0, ac, 0)
        }
        return transferFunction(a)
    }

    /** 검토자의 반례 그대로 — 약 1kHz 칸(171)과 유효 칸 수. */
    @Test
    fun `기준에 DC 가 실려도 AC 칸을 지우지 않는다`() {
        val clean = estimate(0.0)
        val dc = estimate(0.1)
        assertTrue(clean.valid[171])
        assertTrue("DC 가 1번 칸으로 새어 문턱을 쥐었다", dc.valid[171])
        val cleanCount = clean.valid.count { it }
        val dcCount = dc.valid.count { it }
        assertTrue("유효 칸 $dcCount — DC 없을 때 $cleanCount", dcCount >= cleanCount * 0.99)
    }

    /**
     * **저역을 깎지 않는가.** 평균 빼기는 블록마다 상수를 빼는 일이라, 블록에
     * 딱 맞지 않는 저역 성분의 평균도 조금 걷어 낸다. 기준·측정에 같이 하므로
     * H1 에서 상쇄되어야 한다 — 0.5 배 경로의 저역 순음이 −6.02 dB 로 나오는지 본다.
     */
    @Test
    fun `저역 순음의 크기를 바꾸지 않는다`() {
        val fs = 48_000.0
        for (bin in listOf(2.3, 3.7, 7.5)) {
            val hz = bin * fs / n
            val a = SpectralAverager(n)
            val r = Random(5)
            repeat(16) { b ->
                val start = b * n / 2
                val x = DoubleArray(n) { 0.3 * sin(2 * PI * hz * (start + it) / fs) + r.nextDouble(-1e-3, 1e-3) }
                val y = DoubleArray(n) { 0.5 * x[it] }
                a.addBlock(x, 0, y, 0)
            }
            val t = transferFunction(a)
            val k = Math.round(bin).toInt()
            assertTrue("칸 $k 무효", t.valid[k])
            assertEquals("칸 $k ($hz Hz) 크기", -6.0206, t.magnitudeDb[k], 0.05)
            assertEquals("칸 $k 상관", 1.0, t.coherence[k], 1e-3)
        }
    }

    /** 기준이 DC 뿐이면 쓸 칸이 없다 — 평균을 빼면 남는 기준이 없다. */
    @Test
    fun `기준이 DC 뿐이면 모든 칸이 무효다`() {
        val a = SpectralAverager(n)
        repeat(4) { a.addBlock(DoubleArray(n) { 0.2 }, 0, DoubleArray(n) { 0.1 }, 0) }
        val t = transferFunction(a)
        assertEquals(0, t.valid.count { it })
    }
}
