package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **A·C 가중의 정확도를 실제 신호로 잰다**(독립 검토 R01).
 *
 * ## 1. 검토자의 수치를 재현했다
 *
 * 검토자가 고역 오차 표를 내놓았고, 그것을 **내 손으로 다시 쟀다** —
 * 재현되지 않으면 둘 중 하나가 다른 것을 재고 있는 것이고, 그걸 모른 채
 * 고치면 엉뚱한 데를 고친다. **0.05dB 안쪽으로 같았다.**
 *
 * 기존 시험([WeightingTest])은 계수에서 응답을 푸는데, 여기서는 **필터에
 * 순음을 흘려 RMS 를 잰다.** 두 길이 같은 답을 내는 것도 확인한 셈이다.
 *
 * ## 2. 고쳐 보고 되돌렸다 — 나쁜 거래였다
 *
 * 원인은 쌍일차 변환의 주파수 뒤틀림이다. 표준 처방은 **예비 뒤틀림**
 * (pre-warping)이라, 각 2차 구간을 제 극점에서 박도록 바꿔 재 보았다.
 *
 * | 표본율 | 주파수 | 그대로 | 예비 뒤틀림 |
 * |---|---|---|---|
 * | 48k | 5k | 규격 안 | **+0.48 (벗어남)** |
 * | 48k | 8k | -0.544 | +0.698 |
 * | 48k | 10k | -1.216 | +0.587 |
 * | 48k | 16k | -6.434 | **-3.037** |
 * | 48k | 20k | -15.842 | **-11.750** |
 *
 * 16kHz 오차는 절반으로 줄지만 **4~8kHz 가 밀려 올라가** 기존 시험의
 * 허용치(A 0.3dB · C 0.2dB)를 벗어났다. 갈라 보니 12194Hz 이중극점의
 * 뒤틀림이 원인이고, 그 극점은 고역만이 아니라 4kHz 부터의 기울기를
 * 함께 좌우한다.
 *
 * **예배 소리의 에너지는 5kHz 쪽에 있고 16kHz 에는 거의 없다.** 그래서
 * 되돌렸다. 허용치를 늘려 통과시키는 것은 코드 대신 관문을 고치는 일이라
 * 하지 않았다.
 *
 * 고역까지 맞추려면 검토자가 적은 대로 오버샘플링이나 보정 필터를
 * 안정성·위상·CPU·에일리어싱과 함께 견줘 골라야 한다. **지금은 하지
 * 않고, 목표 대역과 그 밖의 오차를 적어 둔다.**
 */
class WeightingAccuracyTest {

    private val f1 = 20.598997
    private val f2 = 107.65265
    private val f3 = 737.86223
    private val f4 = 12194.217

    /** IEC 61672-1 의 연속 주파수 A 가중. 1kHz 에서 0dB 로 맞춘다. */
    private fun analyticADb(hz: Double): Double {
        fun r(f: Double): Double {
            val ff = f * f
            return (f4 * f4 * ff * ff) /
                ((ff + f1 * f1) * sqrt((ff + f2 * f2) * (ff + f3 * f3)) * (ff + f4 * f4))
        }
        return 20.0 * log10(r(hz) / r(1000.0))
    }

    private fun analyticCDb(hz: Double): Double {
        fun r(f: Double): Double {
            val ff = f * f
            return (f4 * f4 * ff) / ((ff + f1 * f1) * (ff + f4 * f4))
        }
        return 20.0 * log10(r(hz) / r(1000.0))
    }

    /**
     * 필터의 크기 응답(dB). 순음을 넣고 정착시킨 뒤 RMS 를 잰다.
     *
     * 앞부분을 버리는 까닭은 필터가 자리를 잡기 전의 과도 구간이 RMS 를
     * 끌어내리기 때문이다.
     */
    private fun measuredDb(chain: BiquadChain, hz: Double, rate: Int): Double {
        val settle = rate / 2
        val measure = rate
        var sum = 0.0
        for (i in 0 until settle + measure) {
            val y = chain.process(sin(2.0 * PI * hz * i / rate))
            if (i >= settle) sum += y * y
        }
        // 입력 순음의 RMS 는 1/√2 다.
        return 20.0 * log10(sqrt(sum / measure) * sqrt(2.0))
    }

    private fun errorAt(rate: Int, hz: Double): Double =
        measuredDb(aWeighting(rate), hz, rate) - analyticADb(hz)

    // ------------------------------------------------------------------
    // 검토자의 표를 재현한다
    // ------------------------------------------------------------------

    @Test
    fun `검토자가 잰 고역 오차가 재현된다`() {
        val reviewer = listOf(
            Triple(44_100, 8_000.0, -0.662),
            Triple(44_100, 10_000.0, -1.501),
            Triple(44_100, 16_000.0, -8.530),
            Triple(44_100, 20_000.0, -24.542),
            Triple(48_000, 8_000.0, -0.544),
            Triple(48_000, 10_000.0, -1.216),
            Triple(48_000, 16_000.0, -6.434),
            Triple(48_000, 20_000.0, -15.842),
        )
        val lines = mutableListOf<String>()
        var worst = 0.0
        for ((rate, hz, want) in reviewer) {
            val got = errorAt(rate, hz)
            lines += "%d %.0fHz: 잰 값 %.3f · 검토자 %.3f".format(rate, hz, got, want)
            worst = maxOf(worst, abs(got - want))
        }
        assertTrue(
            "검토자의 표를 재현하지 못했다. 서로 다른 것을 재고 있다:\n" +
                lines.joinToString("\n"),
            worst < 0.05,
        )
    }

    // ------------------------------------------------------------------
    // 목표 대역과 허용 오차 — 검토자가 「먼저 정하라」고 한 것
    // ------------------------------------------------------------------

    /**
     * **목표: 20Hz ~ 5kHz 에서 ±0.3dB(A) · ±0.2dB(C).**
     *
     * 이것은 **내부 공학 목표**이고 IEC 등급 판정이 아니다. 등급은 주파수
     * 응답 말고도 요구하는 것이 있고 측정 불확도 평가도 필요하다.
     *
     * 5kHz 에서 끊는 까닭은 위 KDoc 에 적은 그대로다 — 그 위를 맞추려면
     * 4~8kHz 를 내주어야 하는데, 예배 소리는 그쪽에 에너지가 있다.
     *
     * 기존 [WeightingTest] 가 같은 목표를 계수 쪽에서 본다. 여기서는
     * **신호를 흘려서** 같은 목표를 확인한다 — 두 길이 어긋나면 그것도
     * 알아야 한다.
     */
    @Test
    fun `목표 대역에서 A 가중이 0점3dB 안에 든다`() {
        for (rate in listOf(44_100, 48_000)) {
            for ((hz, _) in WeightingReference.A) {
                if (hz > 5_000.0) continue
                val e = errorAt(rate, hz)
                assertTrue("%d %.0fHz 에서 %.3fdB (목표 ±0.3)".format(rate, hz, e), abs(e) <= 0.3)
            }
        }
    }

    @Test
    fun `목표 대역에서 C 가중이 0점2dB 안에 든다`() {
        for (rate in listOf(44_100, 48_000)) {
            val chain = cWeighting(rate)
            for ((hz, _) in WeightingReference.C) {
                if (hz > 5_000.0) continue
                val e = measuredDb(chain, hz, rate) - analyticCDb(hz)
                assertTrue("%d %.0fHz 에서 %.3fdB (목표 ±0.2)".format(rate, hz, e), abs(e) <= 0.2)
            }
        }
    }

    /**
     * **목표 밖(5kHz 위)의 오차를 적어 둔다.** 목표는 아니지만 나빠지면
     * 알아야 한다. 한도는 지금 값에 조금 여유를 둔 것이다.
     */
    @Test
    fun `목표 밖 고역 오차가 지금보다 나빠지지 않는다`() {
        val bound = listOf(
            Triple(44_100, 8_000.0, 0.8),
            Triple(44_100, 16_000.0, 9.0),
            Triple(44_100, 20_000.0, 25.5),
            Triple(48_000, 8_000.0, 0.7),
            Triple(48_000, 16_000.0, 7.0),
            Triple(48_000, 20_000.0, 16.5),
        )
        for ((rate, hz, limit) in bound) {
            val e = abs(errorAt(rate, hz))
            assertTrue("%d %.0fHz 에서 %.3fdB (한도 %.1f)".format(rate, hz, e, limit), e <= limit)
        }
    }

    /** 1kHz 는 규격이 정한 기준점이다. 여기가 어긋나면 전부 어긋난다. */
    @Test
    fun `1kHz 는 0dB 이다`() {
        for (rate in listOf(44_100, 48_000)) {
            assertEquals("${rate}Hz", 0.0, measuredDb(aWeighting(rate), 1_000.0, rate), 0.02)
        }
    }
}
