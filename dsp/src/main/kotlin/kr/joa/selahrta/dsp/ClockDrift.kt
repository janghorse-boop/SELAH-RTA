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

/**
 * [ClockDriftTracker] 의 결과.
 *
 * @param validPairs 양쪽 타임스탬프가 **같은 물음에서** 함께 나온 쌍의 수.
 * @param spanSeconds 기준 쌍에서 끝 쌍까지의 시간(출력 쪽 시각). 못 쟀으면 0.
 */
data class ClockDriftTrack(
    val result: DriftResult,
    val asked: Int,
    val validPairs: Int,
    val outMissed: Int,
    val inMissed: Int,
    val spanSeconds: Double,
)

/**
 * 타임스탬프를 여러 번 물어 **기준 쌍과 끝 쌍**을 고른다(6회차 R6-05).
 *
 * 예전 계측은 첫 물음을 기준으로 박아, 그때 한쪽이라도 못 받으면 뒤에 잘 받아도
 * 끝까지 `Unavailable` 이었다. 그리고 판정은 결과를 보지 않고 「한 번이라도
 * 받았나」만 물어, 못 쟀는데 통과할 수 있었다.
 *
 * - **기준**: 양쪽이 **같은 물음에서** 다 나온 첫 쌍. 다른 물음의 출력과 입력을
 *   섞지 않는다 — 시점이 달라진다.
 * - **끝**: 양쪽이 다 나오고 기준보다 **프레임과 시간이 둘 다 늘어난** 마지막 쌍.
 *   멈춘 스트림의 쌍은 끝이 될 수 없다.
 */
class ClockDriftTracker {
    private var first: Pair<ClockSample, ClockSample>? = null
    private var last: Pair<ClockSample, ClockSample>? = null
    private var asked = 0
    private var pairs = 0
    private var outMissed = 0
    private var inMissed = 0

    fun offer(out: ClockSample?, input: ClockSample?) {
        asked++
        if (out == null) outMissed++
        if (input == null) inMissed++
        if (out == null || input == null) return
        pairs++
        val f = first
        if (f == null) { first = out to input; return }
        // **직전에 받아들인 쌍**보다 나아갔어야 한다 — 기준과만 견주면, 한 번 나아간
        // 뒤 멈춘 스트림의 쌍(프레임은 그대로인데 시간만 흐른)을 끝으로 삼는다.
        val p = last ?: f
        val advanced = out.frames > p.first.frames && out.nanos > p.first.nanos &&
            input.frames > p.second.frames && input.nanos > p.second.nanos
        if (advanced) last = out to input
    }

    fun track(): ClockDriftTrack {
        val f = first
        val l = last
        val result = if (f != null && l != null) estimateDrift(f.first, l.first, f.second, l.second) else DriftResult.Unavailable
        val span = if (f != null && l != null && result is DriftResult.Ppm) (l.first.nanos - f.first.nanos) / 1e9 else 0.0
        return ClockDriftTrack(result, asked, pairs, outMissed, inMissed, span)
    }
}

/**
 * 계측이 **쟀는가**. 잰 값으로 가르지 않는다 — 문턱을 정할 근거가 없다. 대신 못 쟀으면
 * 그 까닭을 돌려준다. 통과면 null.
 *
 * - 결과가 `Ppm` 이 아니면 실패.
 * - 분석 구간이 요청 시간의 **절반**에 못 미치면 실패 — 1분짜리 값을 「30분 측정」으로
 *   적지 않는다. 절반은 실측으로 정한 값이 아니다.
 * - 입출력 오류나 경로 변경이 한 번이라도 있었으면 실패.
 */
fun clockDriftVerdict(track: ClockDriftTrack, requestedSeconds: Double, ioErrors: Int, routeChanges: Int): String? = when {
    ioErrors > 0 -> "도는 동안 입출력 오류가 $ioErrors 번 났다 — 이 측정은 못 쓴다"
    routeChanges > 0 -> "도는 동안 경로가 $routeChanges 회 바뀌었다 — 이 측정은 못 쓴다"
    track.result !is DriftResult.Ppm ->
        "드리프트를 못 쟀다 — 양쪽이 함께 나온 쌍 ${track.validPairs}/${track.asked}"
    track.spanSeconds < requestedSeconds / 2 ->
        "분석 구간이 %.0f초 — 요청 %.0f초의 절반에 못 미친다".format(track.spanSeconds, requestedSeconds)
    else -> null
}

private fun rateOrNull(first: ClockSample, last: ClockSample): Double? {
    val dFrames = last.frames - first.frames
    val dNanos = last.nanos - first.nanos
    // 시간이 거꾸로 가거나 멈춰 있거나, 프레임이 안 늘었으면 잴 수 없다.
    if (dNanos <= 0L || dFrames <= 0L) return null
    return dFrames * 1_000_000_000.0 / dNanos
}
