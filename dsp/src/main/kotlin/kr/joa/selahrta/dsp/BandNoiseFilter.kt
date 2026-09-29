package kr.joa.selahrta.dsp

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * **1/3 옥타브 한 대역만 통과시키는 필터**(담당자 지시 2026-09-29).
 *
 * ## 무엇에 쓰나
 *
 * 그래픽 EQ 를 만질 때 「지금 만지는 그 대역이 어디인지」를 귀로
 * 확인하는 데 쓴다. 31밴드 EQ 의 250Hz 슬라이더를 올리는 중이라면,
 * 250Hz 대역만 담긴 잡음을 틀어 놓고 그 변화를 듣는 것이다.
 *
 * 순음(사인)으로는 그 일을 못 한다 — 한 점만 울리므로 **대역 전체가
 * 어떻게 되는지**를 못 듣고, 방의 공진 한 자리에 걸리면 엉뚱하게 크게
 * 들린다. 대역 잡음은 그 폭을 고르게 채운다.
 *
 * ## 왜 4차(2차 두 개)인가
 *
 * IEC 61260 의 1/3 옥타브 필터는 6차(3차 두 쌍)를 쓴다. 여기서는
 * **재는 것이 아니라 들려주는 것**이라 그만큼 가파를 까닭이 없다 —
 * 4차면 대역 밖이 옥타브당 24dB 씩 떨어져, 옆 대역과 귀로 충분히
 * 갈린다.
 *
 * 재는 쪽(`BandAnalyzer`)은 FFT 로 따로 한다. 이 필터는 그쪽에 쓰지
 * 않는다 — 섞으면 「들려준 것」과 「잰 것」이 같은 계통 오차를 갖게 된다.
 *
 * ## 대역폭
 *
 * 1/3 옥타브는 위아래 경계가 중심의 2^(±1/6) 배다.
 * Q = f₀/(f₂−f₁) ≈ 4.318 이 그 폭에 해당한다.
 */
class BandNoiseFilter(centerHz: Double, sampleRate: Int) {

    private val chain: BiquadChain

    /** 실제로 걸린 중심주파수(Hz). 1/3 옥타브 중심으로 맞춘 값이다. */
    val centerHz: Double = snapToBandCenter(centerHz)

    init {
        require(sampleRate > 0) { "샘플레이트가 0 이하다: $sampleRate" }
        // **나이퀴스트 가까이는 피한다.** 쌍일차 변환이 주파수 축을 휘게
        // 만들어, 그 근처에서는 중심이 눈에 띄게 아래로 밀린다.
        val f0 = this.centerHz.coerceAtMost(sampleRate * 0.4)
        val w0 = 2 * PI * f0
        // 2차를 둘 이어 붙인다. 하나씩은 Q 를 낮춰 잡아야 둘을 합쳤을 때
        // 원하는 폭이 된다 — 같은 Q 로 둘을 겹치면 대역이 좁아진다.
        val q = BAND_Q * BUTTERWORTH_PAIR
        chain = BiquadChain(
            List(2) {
                // 아날로그 대역통과: (w0/Q)·s / (s² + (w0/Q)·s + w0²)
                Biquad.fromAnalog(
                    b2 = 0.0, b1 = w0 / q, b0 = 0.0,
                    a2 = 1.0, a1 = w0 / q, a0 = w0 * w0,
                    sampleRate = sampleRate,
                )
            },
        )
    }

    fun process(x: Double): Double = chain.process(x)

    fun reset() = chain.reset()

    companion object {
        /**
         * 1/3 옥타브의 Q.
         *
         * 경계가 중심의 2^(±1/6) 배이므로
         * Q = 1/(2^(1/6) − 2^(−1/6)) ≈ 4.318 이다.
         */
        val BAND_Q: Double = 1.0 / (2.0.pow(1.0 / 6) - 2.0.pow(-1.0 / 6))

        /**
         * 2차를 둘 이어 붙일 때 각 단의 Q 를 넓히는 값.
         *
         * 같은 Q 로 둘을 겹치면 −3dB 폭이 √(√2−1) 배로 좁아진다. 그만큼
         * 미리 넓혀 두어야 합친 결과가 1/3 옥타브가 된다.
         */
        val BUTTERWORTH_PAIR: Double = sqrt(sqrt(2.0) - 1.0)

        /**
         * 가장 가까운 1/3 옥타브 **호칭** 중심으로 맞춘다.
         *
         * 사람이 슬라이더로 고른 3147Hz 를 그대로 쓰면 「3150Hz 대역」이
         * 아니다. EQ 의 눈금과 같은 자리를 내주어야 쓸모가 있다.
         */
        fun snapToBandCenter(hz: Double): Double {
            val c = ThirdOctave.CENTERS_HZ
            var best = c[0]
            var bestGap = Double.MAX_VALUE
            for (v in c) {
                // **로그 거리로 잰다.** 선형으로 재면 고역 쪽이 늘 이긴다.
                val gap = kotlin.math.abs(kotlin.math.ln(hz / v))
                if (gap < bestGap) {
                    bestGap = gap
                    best = v
                }
            }
            return best
        }
    }
}
