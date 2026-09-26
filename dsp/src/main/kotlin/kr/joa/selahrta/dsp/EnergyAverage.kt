package kr.joa.selahrta.dsp

import kotlin.math.sqrt

/**
 * 여러 덩어리의 레벨을 **에너지 영역에서** 모은다.
 *
 * dB 를 그대로 평균 내면 안 된다. dB 는 로그라서, 90dB 과 70dB 을 dB 로
 * 평균하면 80dB 이 나오지만 실제 에너지 평균은 87dB 이다 — **17dB 이나
 * 낮게 보고하는 셈**이고, 큰 소리가 섞인 구간일수록 더 틀린다.
 * 예배처럼 조용함과 큰 소리가 오가는 신호에서 이 실수는 치명적이다.
 *
 * Leq 도 같은 원리다(명세 6장: "Leq는 linear energy 평균 후 dB 변환").
 * 이 클래스가 그 바탕이 된다.
 */
class EnergyAverage {

    private var sumSquares = 0.0
    private var frames = 0L

    /** 덩어리 하나를 더한다. [rms] 는 그 덩어리의 선형 RMS. */
    fun add(rms: Double, frameCount: Int) {
        require(rms >= 0.0) { "RMS 는 음수일 수 없다: $rms" }
        require(frameCount >= 0) { "frameCount 는 음수일 수 없다: $frameCount" }
        if (frameCount == 0) return
        // 프레임 수로 가중한다. 마지막 덩어리가 짧을 수 있는데 그걸 무시하면
        // 짧은 덩어리가 긴 덩어리와 같은 무게를 갖는다.
        sumSquares += rms * rms * frameCount
        frames += frameCount
    }

    /** 모은 구간의 선형 RMS. 아직 아무것도 없으면 null. */
    fun rms(): Double? {
        if (frames == 0L) return null
        return sqrt(sumSquares / frames)
    }

    /** 모은 구간의 dBFS. 아직 아무것도 없으면 null. */
    fun dbfs(): Dbfs? = rms()?.let { amplitudeToDbfs(it) }

    /** 지금까지 모은 프레임 수. */
    fun frameCount(): Long = frames

    /**
     * 지금까지 모은 것을 그대로 떠 둔다.
     *
     * **구간 하나의 Leq 를 빼내려는 쪽이 쓴다.** 측정 도중에 기록을
     * 시작하면 기록의 Leq 는 「측정 전체의 Leq」가 아니라 **그 구간만의
     * Leq** 여야 한다. 에너지 합은 빼기가 되므로, 두 번 떠서 차를 내면
     * 정확히 나온다 — 엔진을 하나 더 돌리거나 시간가중 값을 평균하는
     * 것과 달리 **근사가 아니다.**
     */
    fun snapshot(): EnergySpan = EnergySpan(sumSquares, frames)

    fun reset() {
        sumSquares = 0.0
        frames = 0L
    }
}

/**
 * [EnergyAverage] 의 한 순간. 두 개의 차가 **그 사이 구간의 평균**이다.
 *
 * 값을 직접 읽지 못하게 둔다 — 밖에서 필요한 것은 차뿐이고, 합을 꺼내
 * 쓰기 시작하면 dB 로 평균 내는 실수로 돌아간다.
 */
data class EnergySpan internal constructor(
    internal val sumSquares: Double,
    val frames: Long,
) {
    /**
     * [start] 이후에 모인 것만의 dBFS. 그 사이에 아무것도 없으면 null.
     *
     * 뺄셈이라 마지막 비트가 음수로 갈 수 있다(부동소수). 그때는 0 으로
     * 본다 — 잰 에너지가 음수일 수는 없다.
     */
    fun since(start: EnergySpan): Dbfs? {
        val n = frames - start.frames
        if (n <= 0L) return null
        val s = (sumSquares - start.sumSquares).coerceAtLeast(0.0)
        return amplitudeToDbfs(kotlin.math.sqrt(s / n))
    }
}
