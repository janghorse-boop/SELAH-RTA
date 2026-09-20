package kr.joa.selahrta.dsp

import kotlin.math.abs

/**
 * PCM 한 덩어리에서 뽑는 사실들. 판정이나 보정이 섞이지 않은 날것이다.
 *
 * @param peakAbs 절대값 최대(0..1 이상). 클리핑 판정과 Peak 지표의 재료다.
 * @param rms 선형 RMS.
 * @param clippedSamples 풀스케일에 닿은 샘플 수.
 */
data class BlockStats(
    val peakAbs: Double,
    val rms: Double,
    val clippedSamples: Int,
) {
    /**
     * 이 덩어리가 잘렸는가.
     *
     * 한 샘플만 닿아도 잘린 것으로 본다. 클리핑은 **측정값을 못 믿게 만드는
     * 사건**이지 정도의 문제가 아니다 — 잘린 파형의 RMS 는 실제보다 낮게
     * 나오고, FFT 에는 없던 고조파가 생긴다. 화면에 반드시 알려야 한다.
     */
    val clipped: Boolean get() = clippedSamples > 0
}

/**
 * 풀스케일로 보는 문턱.
 *
 * 정확히 1.0 만 세면 놓친다. 16비트 정수를 float 로 옮길 때 최대값이
 * 32767/32768 = 0.99997 이 되고, float 경로에서도 하드웨어단에서 이미
 * 잘린 신호는 1.0 에 아주 가까운 값으로 들어온다.
 */
const val CLIP_THRESHOLD: Double = 0.999

/**
 * 한 덩어리를 **한 번만 훑어** 통계를 낸다.
 *
 * 초당 수십 번 도는 자리다. RMS·피크·클리핑을 따로 훑으면 같은 메모리를
 * 세 번 읽는다. 덩어리마다 배열을 새로 만들지도 않는다.
 */
fun blockStats(samples: FloatArray, frames: Int): BlockStats {
    require(frames >= 0 && frames <= samples.size) {
        "frames=$frames 이 범위를 벗어난다 (크기 ${samples.size})"
    }
    if (frames == 0) return BlockStats(0.0, 0.0, 0)

    var peak = 0.0
    var sumSq = 0.0
    var clipped = 0
    for (i in 0 until frames) {
        val v = samples[i].toDouble()
        val a = abs(v)
        if (a > peak) peak = a
        sumSq += v * v
        if (a >= CLIP_THRESHOLD) clipped++
    }
    return BlockStats(
        peakAbs = peak,
        rms = kotlin.math.sqrt(sumSq / frames),
        clippedSamples = clipped,
    )
}
