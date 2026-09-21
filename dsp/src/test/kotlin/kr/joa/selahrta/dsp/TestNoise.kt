package kr.joa.selahrta.dsp

import kotlin.random.Random

/**
 * 핑크 잡음(−3dB/옥타브). Voss-McCartney 를 줄여 쓴 것이다.
 *
 * 백색 잡음보다 예배당의 배경음에 가깝고, **저역이 커서 저역 봉우리
 * 오탐을 걸러내는 데 더 엄한 시험**이 된다.
 *
 * **여기 한 벌만 둔다.** 시험 파일마다 따로 두면 한쪽만 고쳐졌을 때
 * 「같은 잡음으로 쟀다」는 말이 조용히 거짓이 된다 — 샘플레이트를 견주는
 * 시험은 두 쪽이 정말 같은 신호를 본다는 것에 기대고 있다.
 */
internal class PinkNoise(private val rng: Random) {
    private val rows = DoubleArray(16)
    private var counter = 0
    private var running = 0.0

    fun next(): Double {
        counter++
        var k = 0
        var c = counter
        while (c and 1 == 0 && k < rows.size - 1) {
            c = c shr 1
            k++
        }
        running -= rows[k]
        rows[k] = rng.nextDouble() * 2 - 1
        running += rows[k]
        return running / rows.size
    }
}
