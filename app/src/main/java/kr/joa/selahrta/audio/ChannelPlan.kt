package kr.joa.selahrta.audio

/**
 * 몇 채널로 열고 그중 몇 번을 쓸 것인가.
 *
 * 기기가 알리는 채널 수는 **목록**이다(예: `[1, 2, 4]` — 모노로도,
 * 스테레오로도, 4채널로도 열 수 있다). 사람이 「Input 3」을 골랐으면
 * **3번이 들어 있는 가장 작은 수**로 열어야 한다.
 *
 * **가장 큰 것으로 열지 않는다.** 필요 없는 채널까지 읽으면 버퍼와
 * CPU 를 그만큼 더 쓰고, 안드로이드가 큰 쪽을 거절해 아예 안 열릴 수도
 * 있다. 내장 마이크(`[1]` 또는 빈 목록)에서는 지금까지와 똑같이
 * 모노로 연다.
 */
data class ChannelPlan(val count: Int, val index: Int) {
    init {
        require(count >= 1) { "채널 수가 1보다 작다: $count" }
        require(index in 0 until count) { "채널 번호가 범위를 벗어난다: $index / $count" }
    }
}

/**
 * @param reported 기기가 알린 채널 수 목록. 비어 있으면 「모름」이다.
 * @param wantedIndex 사람이 고른 채널(0부터). 없으면 0.
 */
fun planChannels(reported: List<Int>, wantedIndex: Int): ChannelPlan {
    val counts = reported.filter { it >= 1 }.distinct().sorted()
    // **모르면 모노다.** 알리지 않는 기기에 넉넉히 요청했다가 못 열면,
    // 지금까지 잘 되던 내장 마이크가 안 열린다.
    if (counts.isEmpty()) return ChannelPlan(1, 0)

    val want = wantedIndex.coerceAtLeast(0)
    val fit = counts.firstOrNull { it > want }
    if (fit != null) return ChannelPlan(fit, want)

    // 고른 번호가 어느 후보에도 안 들어간다 — 기기를 바꿨거나 목록이
    // 줄었다. **가장 큰 것으로 열고 번호를 당긴다.** 조용히 0번으로
    // 되돌리지 않는 까닭은, 4채널을 쓰던 사람이 2채널 기기를 꽂았을 때
    // 1번보다 2번이 그 사람이 쓰던 것에 가깝기 때문이다.
    val max = counts.last()
    return ChannelPlan(max, max - 1)
}
