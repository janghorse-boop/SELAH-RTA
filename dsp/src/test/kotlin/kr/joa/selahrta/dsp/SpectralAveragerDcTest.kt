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

    /**
     * 기준이 DC 뿐이면 쓸 칸이 없다 — 평균을 빼면 남는 기준이 없다.
     *
     * 평균은 **첫 표본을 뺀 차이들의 평균**으로 낸다(15회차 R15-01). 그냥 더해서
     * 나누면 0.2 같은 값의 반올림 오차로 1e-17 짜리 찌꺼기가 남아, 상대 문턱 안에서
     * 「유효한 칸」을 지어냈다. 한때 절대 바닥(1e-20)으로 막았는데 그것이 실제 작은
     * 신호까지 지웠다 — 아래 시험.
     */
    @Test
    fun `기준이 DC 뿐이면 모든 칸이 무효다`() {
        for (dc in listOf(0.1, 0.2, 1.0, 0.3e-6)) {
            val a = SpectralAverager(n)
            repeat(4) { a.addBlock(DoubleArray(n) { dc }, 0, DoubleArray(n) { dc * 0.5 }, 0) }
            val t = transferFunction(a)
            assertEquals("DC $dc", 0, t.valid.count { it })
        }
    }

    /**
     * **15회차 R15-01 의 반례.** 공통 배율만 다른 같은 상관 신호는 같은 칸이 유효해야
     * 한다. 절대 바닥을 두었을 때는 1e-11 배에서 유효 칸 4,049 → 0 이 됐다. float 를
     * 거쳐 표현 가능한 값이다. 이 시험은 폰이 그런 작은 음압을 잴 수 있다는 뜻이 아니다.
     */
    @Test
    fun `공통 배율만 다른 신호는 같은 칸이 유효하다`() {
        val base = DoubleArray(n * 16) { Random(17 + it).nextDouble(-1.0, 1.0) }
        val counts = listOf(1e-6, 1e-11, 1e-15).map { scale ->
            val a = SpectralAverager(n)
            val x = DoubleArray(base.size) { (base[it] * scale).toFloat().toDouble() }
            val y = DoubleArray(base.size) { x[it] * 0.5 }
            repeat(16) { b -> a.addBlock(x, b * n, y, b * n) }
            transferFunction(a).valid.count { it }
        }
        assertTrue("유효 칸이 ${counts[0]} 뿐이다", counts[0] > 4000)
        assertEquals("배율 1e-11 에서 칸이 사라졌다", counts[0], counts[1])
        assertEquals("배율 1e-15 에서 칸이 사라졌다", counts[0], counts[2])
    }

    /**
     * **16회차 R16-02 의 반례.** 차이들의 평균에 첫 표본을 **다시 더해** 절대 평균을
     * 만들면, 큰 DC 위의 작은 평균이 그 합의 정밀도에 묻힌다. `x` 가 1 과 1+ulp(1)
     * 사이를 오가고 `y = (x−1)·0.5` 이면 1번 칸이 −51 dB · 상관 0.06 으로 나왔다
     * (DC 를 미리 뺀 대조군은 −6.02 dB). double 을 직접 넣는 극단의 경계다 — float
     * 입력 9조합은 그전에도 통과했다.
     */
    @Test
    fun `큰 DC 위의 작은 변화도 첫 표본 기준으로 끝까지 중심화한다`() {
        val r = Random(3)
        val ulp = Math.ulp(1.0)
        val a = SpectralAverager(1024)
        repeat(16) {
            val x = DoubleArray(1024) { if (r.nextBoolean()) 1.0 else 1.0 + ulp }
            val y = DoubleArray(1024) { (x[it] - 1.0) * 0.5 }
            a.addBlock(x, 0, y, 0)
        }
        val t = transferFunction(a)
        for (k in listOf(1, 50, 300)) {
            assertTrue("칸 $k 무효", t.valid[k])
            assertEquals("칸 $k 크기", -6.0206, t.magnitudeDb[k], 0.01)
            assertEquals("칸 $k 상관", 1.0, t.coherence[k], 1e-6)
        }
    }

    /**
     * **같은 평균제곱의 순음은 FFT 크기와 상관없이 유효해야 한다**(15회차). 절대 바닥은
     * `peak/(count·N)` 을 표본당 전력이라 불렀지만 그렇지 않아, FFT 256·1024 에서는
     * 무효, 8192 에서는 유효였다.
     */
    @Test
    fun `작은 순음은 FFT 크기와 상관없이 유효하다`() {
        for (size in listOf(256, 1024, 8192)) {
            val a = SpectralAverager(size)
            val x = DoubleArray(size) { 1e-11 * sin(2 * PI * 7 * it / size) }
            repeat(16) { a.addBlock(x, 0, x, 0) }
            assertTrue("FFT $size 에서 7번 칸 무효", transferFunction(a).valid[7])
        }
    }
}
