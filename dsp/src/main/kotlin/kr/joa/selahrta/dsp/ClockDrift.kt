package kr.joa.selahrta.dsp

/**
 * 한 시점의 「프레임 수와 그때의 시각」.
 *
 * **시각은 반드시 같은 시간축이라야 한다.** 안드로이드에서는
 * `AudioTrack` 쪽이 `MONOTONIC` 이고 `AudioRecord` 는 고를 수 있으므로,
 * 부르는 쪽이 `TIMEBASE_MONOTONIC` 을 명시해야 한다(명세 7장). 어긋나면
 * 드리프트가 아니라 **두 시계의 기준점 차이**를 재게 된다.
 */
data class ClockSample(val frames: Long, val nanos: Long)

/** 드리프트를 쟀는가. */
sealed interface DriftResult {
    /** 백만분율. 양수면 출력이 빠르다. */
    data class Ppm(val value: Double) : DriftResult

    /**
     * **못 쟀다.**
     *
     * 0 ppm 으로 돌려주지 않는다 — 그러면 「드리프트 없음」으로 읽힌다.
     * 안드로이드는 route 에 따라 타임스탬프를 아예 안 줄 수 있다.
     */
    data object Unavailable : DriftResult
}

/**
 * 출력과 입력의 **표본 속도 비**로 드리프트를 낸다.
 *
 * ```
 * outputRate = Δframes / Δtime
 * inputRate  = Δframes / Δtime
 * ppm = (outputRate / inputRate - 1) × 1,000,000
 * ```
 *
 * **음향이 전혀 필요 없다.** 스피커를 울리지 않고 조용히 한 시간을 잴 수
 * 있다. 음향으로 재면 클럭 드리프트와 방·온도 변화가 섞인다 — 음속은
 * 1℃ 에 약 0.17% 변하고, 8m 에서 1℃ 면 0.04ms 라 찾으려는 값과 같은
 * 자릿수다.
 */
fun estimateDrift(
    outFirst: ClockSample,
    outLast: ClockSample,
    inFirst: ClockSample,
    inLast: ClockSample,
): DriftResult {
    val outRate = rateOrNull(outFirst, outLast) ?: return DriftResult.Unavailable
    val inRate = rateOrNull(inFirst, inLast) ?: return DriftResult.Unavailable
    // 지금은 도달하지 않는다 — rateOrNull 이 null 이 아닌 값을 주면
    // dNanos > 0 && dFrames > 0 이 보장되어 inRate 는 항상 엄밀히 양수다.
    // rateOrNull 의 계약이 나중에 느슨해지면(예: null 대신 기본값을 돌려주는
    // 리팩터링) 그때를 위한 안전망으로 의도적으로 남긴다.
    if (inRate <= 0.0) return DriftResult.Unavailable
    return DriftResult.Ppm((outRate / inRate - 1.0) * 1_000_000.0)
}

private fun rateOrNull(first: ClockSample, last: ClockSample): Double? {
    val dFrames = last.frames - first.frames
    val dNanos = last.nanos - first.nanos
    // 시간이 거꾸로 가거나 멈춰 있거나, 프레임이 안 늘었으면 잴 수 없다.
    if (dNanos <= 0L || dFrames <= 0L) return null
    return dFrames * 1_000_000_000.0 / dNanos
}
