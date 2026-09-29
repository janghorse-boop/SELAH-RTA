package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **적힌 주파수가 실제로 나오는가**(독립 검토 SRL-02, 2026-09-29).
 *
 * ## 무엇을 놓쳤나
 *
 * 먼저 쓴 `BandNoiseFilterTest` 는 **1kHz 언저리에서만** 파형을 재고,
 * 대역 맞추기는 `centerHz` **숫자만** 견줬다. 그래서 고역이 통째로
 * 어긋난 것을 못 잡았다:
 *
 * | 화면이 적는 대역 | 실제 최대 통과점 | 적힌 곳에서의 이득 |
 * |---:|---:|---:|
 * | 8,000 Hz | 7,382 Hz | −2.25 dB |
 * | 16,000 Hz | **12,342 Hz** | **−19.6 dB** |
 * | 20,000 Hz | **13,691 Hz** | **−34.7 dB** |
 *
 * 16kHz 슬라이더를 점검한다고 믿으면서 **12.3kHz 소리를 듣게 된다.**
 * 그러고는 엉뚱한 대역의 반응을 그 슬라이더 탓으로 읽는다.
 *
 * 까닭은 쌍일차 변환이 주파수 축을 휘게 만드는 것인데, 아날로그 원형을
 * **사전 보정 없이** 그대로 넣었기 때문이다. 나이퀴스트에 가까울수록
 * 심해진다.
 *
 * ## 이 시험은 31개 대역을 모두 잰다
 *
 * 숫자 속성이 아니라 **순음을 넣어 나오는 크기**를 본다. 한 자리만
 * 재면 같은 함정에 다시 빠진다.
 */
class BandCenterAccuracyTest {

    private val fs = 48_000

    /** [hz] 순음을 넣었을 때 나오는 크기(dB). 0dB 가 그대로 통과다. */
    private fun gainDb(centerHz: Double, hz: Double): Double {
        val f = BandNoiseFilter(centerHz, fs)
        val warm = fs / 4
        repeat(warm) { f.process(sin(2 * PI * hz * it / fs)) }
        var sum = 0.0
        val n = fs / 4
        for (i in 0 until n) {
            val v = f.process(sin(2 * PI * hz * (warm + i) / fs))
            sum += v * v
        }
        return 20 * log10(sqrt(sum / n) / (1.0 / sqrt(2.0)))
    }

    /**
     * **31개 대역 모두, 적힌 그 주파수에서 통과해야 한다.**
     *
     * 이것이 화면과 소리의 약속이다. 어긋나면 바가 거짓말을 한다.
     */
    @Test
    fun `호칭 중심에서 모두 통과한다`() {
        val bad = ThirdOctave.CENTERS_HZ.toList().mapNotNull { c ->
            val g = gainDb(c, c)
            if (g < -1.0) "%.0f Hz: %.2f dB".format(c, g) else null
        }
        assertTrue("적힌 주파수가 깎여 나온다 — $bad", bad.isEmpty())
    }

    /**
     * **폭도 1/3 옥타브여야 한다.**
     *
     * 중심만 맞추고 폭이 좁아지면, 그 대역 안에서도 가장자리가 안 들려
     * 「슬라이더가 덮는 폭」을 귀로 못 가린다. 4차 버터워스 쌍이면 경계가
     * −3.01dB 다.
     */
    @Test
    fun `대역 경계가 3dB 언저리다`() {
        val step = 2.0.pow(1.0 / 6)
        // **맨 위 20kHz 는 뺀다.** 위 경계가 22.4kHz 로 48kHz 의
        // 나이퀴스트(24kHz)에 너무 가까워, 거기서는 필터가 아니라 표본율이
        // 폭을 정한다. 그 한 칸은 아래 시험이 따로 본다.
        for (c in ThirdOctave.CENTERS_HZ.toList().filter { it < 20_000.0 }) {
            val lo = gainDb(c, c / step)
            val hi = gainDb(c, c * step)
            assertEquals("%.0f Hz 아래 경계".format(c), -3.01, lo, 0.35)
            assertEquals("%.0f Hz 위 경계".format(c), -3.01, hi, 0.35)
        }
    }

    /**
     * **맨 위 대역도 적힌 자리에서는 들려야 한다.**
     *
     * 20kHz 의 위 경계(22.4kHz)는 48kHz 표본율의 나이퀴스트 바로
     * 아래다. 폭은 좁아질 수 있어도 **20kHz 그 자리**는 통과해야 한다 —
     * 안 그러면 화면이 적은 것과 들리는 것이 또 갈린다.
     */
    @Test
    fun `맨 위 대역도 적힌 자리에서 통과한다`() {
        val g = gainDb(20_000.0, 20_000.0)
        assertTrue("20kHz 대역이 %.2f dB 로 깎였다".format(g), g > -1.5)
    }

    /**
     * **옆 대역은 여전히 죽어야 한다.** 중심을 맞추다 폭이 넓어지면
     * 무엇을 만지는지 귀로 가를 수 없게 된다.
     */
    @Test
    fun `한 옥타브 밖은 여전히 크게 죽는다`() {
        for (c in listOf(125.0, 1_000.0, 4_000.0)) {
            assertTrue("%.0f Hz 아래".format(c), gainDb(c, c / 2) < -20.0)
            assertTrue("%.0f Hz 위".format(c), gainDb(c, c * 2) < -20.0)
        }
    }

    /**
     * **실제 최대 통과점이 적힌 자리 가까이에 있어야 한다.**
     *
     * 위 시험들은 「적힌 자리에서 통과한다」를 본다. 이것은 **봉우리가
     * 딴 데 있지 않은가**를 본다 — 둘은 다른 물음이고, 처음 놓친 것이
     * 이쪽이었다.
     */
    @Test
    fun `봉우리가 적힌 자리 가까이 있다`() {
        for (c in listOf(1_000.0, 8_000.0, 16_000.0)) {
            var bestHz = 0.0
            var best = -999.0
            var hz = c * 0.6
            while (hz < c * 1.4) {
                val g = gainDb(c, hz)
                if (g > best) { best = g; bestHz = hz }
                hz *= 1.02
            }
            // 훑는 간격이 2% 라 그만큼은 봐준다.
            assertEquals(
                "%.0f Hz 대역의 봉우리가 %.0f Hz 에 있다".format(c, bestHz),
                1.0, bestHz / c, 0.05,
            )
        }
    }
}
