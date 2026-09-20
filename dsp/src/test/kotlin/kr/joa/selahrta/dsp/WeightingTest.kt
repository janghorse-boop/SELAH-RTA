package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * 명세 15장 「A/C weighting 주파수별 기대 감쇠」.
 *
 * 기준값은 [WeightingReference] — IEC 61672-1 이 표로 정한 값이다.
 * **구현에서 뽑은 값을 기준으로 삼지 않는다.** 그러면 틀린 구현이 자기
 * 자신과 맞는지 확인하는 시험이 된다.
 */
class WeightingTest {

    private val fs = 48_000

    private fun responseDb(chain: BiquadChain, f: Double) =
        20.0 * log10(chain.magnitudeAt(f, fs))

    @Test
    fun `A 가중은 1kHz 에서 정확히 0dB 이다`() {
        // 규격이 정한 기준점. 여기가 틀어지면 모든 dBA 값이 통째로 옮겨간다.
        assertEquals(0.0, responseDb(aWeighting(fs), 1000.0), 1e-9)
    }

    @Test
    fun `C 가중도 1kHz 에서 정확히 0dB 이다`() {
        assertEquals(0.0, responseDb(cWeighting(fs), 1000.0), 1e-9)
    }

    @Test
    fun `A 가중이 10Hz~5kHz 에서 규격과 0_3dB 안에 든다`() {
        val a = aWeighting(fs)
        for ((f, want) in WeightingReference.A) {
            if (f > 5000.0) continue
            val got = responseDb(a, f)
            assertEquals("${f}Hz", want, got, 0.3)
        }
    }

    @Test
    fun `C 가중이 10Hz~5kHz 에서 규격과 0_2dB 안에 든다`() {
        val c = cWeighting(fs)
        for ((f, want) in WeightingReference.C) {
            if (f > 5000.0) continue
            assertEquals("${f}Hz", want, responseDb(c, f), 0.2)
        }
    }

    @Test
    fun `A 가중의 모양이 규격과 같다 — 저역을 깎고 3kHz 부근을 올린다`() {
        val a = aWeighting(fs)
        // 단순 offset 으로는 절대 만들 수 없는 모양이다. 이 시험이
        // 「표를 보고 dB 를 더하는」 구현을 걸러낸다(명세 6장 금지 사항).
        assertTrue("100Hz 는 크게 깎여야 한다", responseDb(a, 100.0) < -15.0)
        assertTrue("2.5kHz 는 오히려 올라가야 한다", responseDb(a, 2500.0) > 0.5)
        assertTrue("20Hz 와 1kHz 의 차이가 45dB 을 넘어야 한다",
            responseDb(a, 1000.0) - responseDb(a, 20.0) > 45.0)
    }

    @Test
    fun `C 가중은 중역에서 평탄하다`() {
        val c = cWeighting(fs)
        for (f in listOf(200.0, 315.0, 500.0, 800.0, 1000.0, 1250.0)) {
            assertEquals("${f}Hz 는 평탄해야 한다", 0.0, responseDb(c, f), 0.1)
        }
    }

    @Test
    fun `샘플레이트가 다르면 계수도 달라진다`() {
        // 단순 offset 이면 샘플레이트와 무관할 것이다. 필터라면 달라야 한다.
        // 다만 **응답은** 두 샘플레이트에서 (저역에서는) 같아야 한다.
        val a48 = aWeighting(48_000)
        val a44 = aWeighting(44_100)
        for (f in listOf(31.5, 125.0, 500.0, 1000.0, 4000.0)) {
            assertEquals(
                "${f}Hz 응답은 샘플레이트와 무관해야 한다",
                20.0 * log10(a48.magnitudeAt(f, 48_000)),
                20.0 * log10(a44.magnitudeAt(f, 44_100)),
                0.05,
            )
        }
    }

    /**
     * 고역의 어긋남을 **숨기지 않고 못박아 둔다.**
     *
     * 쌍일차 변환은 나이퀴스트 가까이에서 주파수 축을 휘게 만든다.
     * 48kHz 에서 실측한 값이다. 이 시험은 「여기까지는 알고 있다」는 기록이자,
     * 나중에 필터를 고쳤을 때 더 나빠지면 잡아 주는 그물이다.
     */
    @Test
    fun `고역에서는 규격보다 낮게 나온다 — 알고 있는 만큼 못박는다`() {
        val a = aWeighting(fs)
        val measured = mapOf(
            8000.0 to -0.59,
            10000.0 to -1.21,
            12500.0 to -2.62,
            16000.0 to -6.54,
            20000.0 to -15.89,
        )
        for ((f, expectedError) in measured) {
            val err = responseDb(a, f) - WeightingReference.A[f]!!
            assertEquals("${f}Hz 의 어긋남", expectedError, err, 0.05)
        }
    }

    /**
     * **이 시험이 실제로 중요한 시험이다.**
     *
     * 고역이 몇 dB 틀린 것 자체가 아니라, 그것이 이 앱이 재는 소리의
     * 총 dBA 를 얼마나 흔드는지가 문제다. 설교와 찬양 스펙트럼으로 재 보면
     * 0.1dB 아래다 — 폰 마이크 보정 오차(수 dB)에 비하면 무시할 수 있다.
     */
    @Test
    fun `실제 예배 소리에서는 고역 오차가 총 레벨을 0_15dB 도 못 흔든다`() {
        val a = aWeighting(fs)
        val bands = WeightingReference.A.keys.sorted().filter { it < fs / 2.0 }

        // 설교: 500Hz 부근이 정점이고 고역으로 급감한다.
        val speech = bands.associateWith { f ->
            -0.006 * (f - 500.0).pow(2) / 500.0 - 12.0 * log10(maxOf(f, 100.0) / 500.0)
        }
        // 찬양: 저역이 크고 고역도 어느 정도 있다.
        val worship = bands.associateWith { f ->
            if (f < 200) 3.0 else -6.0 * log10(f / 200.0)
        }

        for ((name, spectrum) in listOf("설교" to speech, "찬양" to worship)) {
            var exact = 0.0
            var mine = 0.0
            for (f in bands) {
                val e = 10.0.pow(spectrum[f]!! / 10.0)
                exact += e * 10.0.pow(WeightingReference.A[f]!! / 10.0)
                mine += e * 10.0.pow(responseDb(a, f) / 10.0)
            }
            val diff = 10.0 * log10(mine / exact)
            assertTrue("$name 총 레벨 오차 ${"%.3f".format(diff)}dB", abs(diff) < 0.15)
        }
    }

    @Test
    fun `Z 가중은 아무것도 바꾸지 않는다`() {
        val z = zWeighting()
        for (f in listOf(20.0, 1000.0, 10000.0)) {
            assertEquals("${f}Hz", 1.0, z.magnitudeAt(f, fs), 1e-12)
        }
        // 신호를 흘려도 그대로여야 한다.
        val x = SignalGenerator.sine(1000.0, fs, 512, 0.5)
        val y = x.copyOf()
        z.processInPlace(y, y.size)
        for (i in x.indices) assertEquals(x[i], y[i], 0.0)
    }

    @Test
    fun `실제로 신호를 흘려도 응답과 같은 값이 나온다`() {
        // magnitudeAt 은 계산식이고, processInPlace 는 실제 필터다.
        // 둘이 어긋나면 화면의 숫자와 시험이 서로 다른 것을 보고 있는 셈이다.
        val a = aWeighting(fs)
        for (f in listOf(125.0, 1000.0, 4000.0)) {
            a.reset()
            val n = SignalGenerator.wholeCycleLength(f, fs, 48_000)
            val x = SignalGenerator.sine(f, fs, n, 0.5)
            a.processInPlace(x, x.size)
            // 앞쪽은 필터가 자리를 잡는 구간이라 버린다.
            val settled = x.copyOfRange(n / 2, n)
            val gotDb = 20.0 * log10(rms(settled) / (0.5 / kotlin.math.sqrt(2.0)))
            assertEquals("${f}Hz", 20.0 * log10(a.magnitudeAt(f, fs)), gotDb, 0.1)
        }
    }
}
