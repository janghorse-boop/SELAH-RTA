package kr.joa.selahrta.dsp

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
