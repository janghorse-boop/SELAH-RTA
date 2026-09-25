package kr.joa.selahrta.dsp

import kotlin.math.log10
import kotlin.math.pow

/**
 * 1/3 옥타브 31밴드(명세 7장).
 *
 * 중심주파수는 IEC 61260 의 **호칭값**이다 — 31.5Hz 는 정확히는 31.623Hz
 * 이지만, 콘솔 EQ 와 소음계가 모두 31.5 로 적는다. 화면에는 호칭값을 쓰고
 * 경계 계산에는 정확값을 쓴다. 둘을 섞으면 경계가 미세하게 어긋나
 * 밴드 하나가 이웃의 에너지를 조금씩 먹는다.
 */
object ThirdOctave {

    /** 화면과 리포트에 적는 호칭 중심주파수(Hz). 명세 7장의 목록 그대로. */
    val CENTERS_HZ: DoubleArray = doubleArrayOf(
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0,
        200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0,
        2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0,
        20000.0,
    )

    val BAND_COUNT: Int get() = CENTERS_HZ.size

    /**
     * 경계 계산에 쓰는 정확한 중심주파수(Hz).
     *
     * 1kHz 를 기준으로 10^(n/10) 으로 정의한다(IEC 61260 의 base-10 방식).
     * 20Hz 가 n = -17, 20kHz 가 n = +13 이다.
     */
    fun exactCenter(index: Int): Double {
        require(index in 0 until BAND_COUNT) { "밴드 번호가 범위를 벗어난다: $index" }
        val n = index - 17
        return 1000.0 * 10.0.pow(n / 10.0)
    }

    /**
     * [hz] 가 속하는 밴드 번호. 범위 밖이면 가장 가까운 끝 밴드.
     *
     * **한 밴드에서 여러 축 점이 나온다.** 보정 축은 1/12옥타브라 밴드
     * 하나당 서너 점이 생기므로, 「점을 몇 개 썼는가」와 「몇 개 대역에서
     * 썼는가」는 다르다 — 뒤쪽이 「얼마나 넓은 자리에서 맞췄는가」에
     * 가깝다(독립 검증 RCP01).
     */
    fun nearestBand(hz: Double): Int {
        require(hz > 0.0) { "주파수는 양수여야 한다: $hz" }
        // exactCenter 의 역이다: n = 10·log10(hz/1000), 밴드 번호 = n + 17.
        val n = 10.0 * log10(hz / 1000.0)
        return (Math.round(n).toInt() + 17).coerceIn(0, BAND_COUNT - 1)
    }

    /**
     * [hz] 가 31칸 중 **어디쯤**인가 — 칸 사이 소수까지 돌려준다.
     *
     * [nearestBand] 는 「어느 칸이냐」를 묻고, 이쪽은 「그 칸의 어디냐」를
     * 묻는다. 차트에 봉우리를 찍을 때 필요하다 — 한 밴드는 23% 나 넓어서
     * 칸 가운데에 찍으면 실제 주파수와 눈에 띄게 어긋난다.
     *
     * 범위 밖이면 0 미만이거나 [BAND_COUNT]−1 을 넘는 값이 그대로 나온다.
     * **자르지 않는다** — 자르면 20Hz 아래 소리가 20Hz 자리에 찍혀, 없는
     * 대역에 봉우리가 있는 것처럼 보인다. 부르는 쪽이 범위를 보고 버린다.
     */
    fun bandPosition(hz: Double): Double {
        require(hz > 0.0) { "주파수는 양수여야 한다: $hz" }
        return 17.0 + 10.0 * log10(hz / 1000.0)
    }

    /** 아래쪽 경계(Hz). 중심에서 1/6 옥타브 아래. */
    fun lowerEdge(index: Int): Double = exactCenter(index) / RATIO

    /** 위쪽 경계(Hz). 중심에서 1/6 옥타브 위. */
    fun upperEdge(index: Int): Double = exactCenter(index) * RATIO

    /** 1/6 옥타브 비율. 10^(1/20) — base-10 정의와 짝이 맞아야 한다. */
    private val RATIO: Double = 10.0.pow(1.0 / 20.0)

    /** 화면 표시용 문자열. 1000 이상은 k 로 줄인다. */
    fun label(index: Int): String {
        val hz = CENTERS_HZ[index]
        return when {
            hz >= 1000.0 -> {
                val k = hz / 1000.0
                if (k == k.toInt().toDouble()) "${k.toInt()}k" else "${k}k"
            }
            hz == hz.toInt().toDouble() -> hz.toInt().toString()
            else -> hz.toString()
        }
    }
}
