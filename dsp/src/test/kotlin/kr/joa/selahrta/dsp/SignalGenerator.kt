package kr.joa.selahrta.dsp

import kotlin.math.PI
import kotlin.math.sin

/**
 * 시험용 합성 신호. 명세 16장의 SignalGenerator 를 검증 쪽에 먼저 둔다.
 *
 * **측정 코드는 실제 마이크가 없어도 검증할 수 있어야 한다.** 값을 아는
 * 신호를 넣고 나오는 숫자를 확인하는 것이 유일하게 믿을 만한 방법이다.
 */
object SignalGenerator {

    /**
     * 사인파. [amplitude] 는 최대 진폭(1.0 이 풀스케일).
     *
     * 주기가 딱 떨어지지 않으면 RMS 가 이론값에서 미세하게 벗어난다.
     * 그래서 시험에서는 되도록 정수 주기가 담기는 길이를 쓴다.
     */
    fun sine(
        frequencyHz: Double,
        sampleRate: Int,
        samples: Int,
        amplitude: Double = 1.0,
        phase: Double = 0.0,
    ): DoubleArray {
        val out = DoubleArray(samples)
        val w = 2.0 * PI * frequencyHz / sampleRate
        for (i in 0 until samples) out[i] = amplitude * sin(w * i + phase)
        return out
    }

    /** 정수 주기가 정확히 담기는 길이를 고른다. 창 함수 없이 재도 누설이 없다. */
    fun wholeCycleLength(frequencyHz: Double, sampleRate: Int, atLeast: Int): Int {
        val period = sampleRate / frequencyHz
        val cycles = kotlin.math.ceil(atLeast / period)
        return kotlin.math.round(cycles * period).toInt()
    }

    /** 직류. RMS 가 진폭과 같아지는 유일한 경우라 경계 확인에 쓴다. */
    fun dc(level: Double, samples: Int) = DoubleArray(samples) { level }

    /** 완전한 무음. dB 가 -무한으로 발산하는 경계. */
    fun silence(samples: Int) = DoubleArray(samples)
}
