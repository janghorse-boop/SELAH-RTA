package kr.joa.selahrta.dsp

import kotlin.math.cos
import kotlin.math.sin

/**
 * 2차 IIR 구간 하나. 계수는 a0 으로 정규화돼 있다고 본다.
 *
 * 상태(이전 입출력)를 들고 있으므로 **한 구간을 두 신호에 함께 쓰면 안 된다.**
 * 섞이면 한쪽 소리가 다른 쪽 필터를 흔들어 둘 다 틀린 값이 나온다.
 */
class Biquad(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }

    /**
     * 이 구간의 주파수 응답 크기. 시험과 이득 정규화에 쓴다.
     *
     * z = e^{jω} 를 넣어 |H(z)| 를 구한다. 실제로 신호를 흘려 재는 것보다
     * 정확하고 빠르며, 필터를 고치면 곧바로 드러난다.
     */
    fun magnitudeAt(frequencyHz: Double, sampleRate: Int): Double {
        val w = 2.0 * Math.PI * frequencyHz / sampleRate
        val cw = cos(w); val sw = sin(w)
        val c2w = cos(2 * w); val s2w = sin(2 * w)
        // 분자: b0 + b1·e^{-jw} + b2·e^{-j2w}
        val nRe = b0 + b1 * cw + b2 * c2w
        val nIm = -(b1 * sw + b2 * s2w)
        // 분모: 1 + a1·e^{-jw} + a2·e^{-j2w}
        val dRe = 1.0 + a1 * cw + a2 * c2w
        val dIm = -(a1 * sw + a2 * s2w)
        val nMag = kotlin.math.hypot(nRe, nIm)
        val dMag = kotlin.math.hypot(dRe, dIm)
        return if (dMag == 0.0) Double.POSITIVE_INFINITY else nMag / dMag
    }

    /** 이득만 바꾼 같은 필터. 정규화에 쓴다. */
    fun scaled(gain: Double) = Biquad(b0 * gain, b1 * gain, b2 * gain, a1, a2)

    companion object {
        /**
         * 아날로그 2차 구간을 **쌍일차 변환**으로 디지털로 옮긴다.
         *
         * 아날로그: (b2·s² + b1·s + b0) / (a2·s² + a1·s + a0)
         * 치환:     s → 2·fs · (1 - z⁻¹)/(1 + z⁻¹)
         *
         * IEC 61672 는 가중치를 **아날로그 전달함수**로 정의한다. 그래서
         * 표를 보고 dB 를 더하는 것(단순 offset)이 아니라, 그 전달함수를
         * 지금 샘플레이트에 맞는 디지털 필터로 옮겨야 한다(명세 6장).
         * 샘플레이트가 달라지면 계수도 달라진다 — 그래서 [sampleRate] 를 받는다.
         *
         * 쌍일차 변환은 주파수 축을 휘게 만든다(warping). 낮은 주파수에서는
         * 무시할 만하지만 나이퀴스트 가까이에서는 실제로 어긋난다 —
         * 그 어긋남을 숨기지 않고 시험에서 크기를 재어 둔다.
         */
        fun fromAnalog(
            b2: Double, b1: Double, b0: Double,
            a2: Double, a1: Double, a0: Double,
            sampleRate: Int,
        ): Biquad {
            val c = 2.0 * sampleRate
            val cc = c * c

            val nb0 = b2 * cc + b1 * c + b0
            val nb1 = 2.0 * (b0 - b2 * cc)
            val nb2 = b2 * cc - b1 * c + b0

            val na0 = a2 * cc + a1 * c + a0
            val na1 = 2.0 * (a0 - a2 * cc)
            val na2 = a2 * cc - a1 * c + a0

            require(na0 != 0.0) { "분모의 상수항이 0 이라 정규화할 수 없다" }
            return Biquad(nb0 / na0, nb1 / na0, nb2 / na0, na1 / na0, na2 / na0)
        }
    }
}

/**
 * 2차 구간을 이어 붙인 필터.
 *
 * 6차 필터를 계수 일곱 개짜리 하나로 쓰면 부동소수점 오차가 쌓여 저역에서
 * 눈에 띄게 틀어진다. 2차씩 나눠 거는 것이 수치적으로 안정하다.
 */
class BiquadChain(private val sections: List<Biquad>) {

    fun process(x: Double): Double {
        var v = x
        for (s in sections) v = s.process(v)
        return v
    }

    /** 덩어리를 제자리에서 거른다. 초당 수십 번 도는 자리라 배열을 새로 만들지 않는다. */
    fun processInPlace(buf: DoubleArray, frames: Int) {
        require(frames in 0..buf.size) { "frames=$frames 이 범위를 벗어난다" }
        for (i in 0 until frames) buf[i] = process(buf[i])
    }

    fun reset() = sections.forEach { it.reset() }

    fun magnitudeAt(frequencyHz: Double, sampleRate: Int): Double =
        sections.fold(1.0) { acc, s -> acc * s.magnitudeAt(frequencyHz, sampleRate) }

    /** 1kHz 에서 0dB 이 되도록 맞춘 사본. */
    fun normalisedAt(frequencyHz: Double, sampleRate: Int): BiquadChain {
        val m = magnitudeAt(frequencyHz, sampleRate)
        require(m > 0.0 && m.isFinite()) { "${frequencyHz}Hz 에서 이득이 $m 이라 정규화할 수 없다" }
        // 첫 구간에만 이득을 곱한다. 어디에 곱하든 전체 이득은 같다.
        return BiquadChain(listOf(sections.first().scaled(1.0 / m)) + sections.drop(1))
    }
}
