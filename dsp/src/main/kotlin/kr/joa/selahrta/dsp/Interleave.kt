package kr.joa.selahrta.dsp

/**
 * 여러 채널이 섞여 들어온 PCM 에서 **한 채널만 뽑는다**.
 *
 * 멀티 입력 인터페이스(예: 4채널 오디오 인터페이스)는 채널을 번갈아
 * 담아 준다:
 *
 * ```
 * [ch0 ch1 ch2 ch3][ch0 ch1 ch2 ch3][ch0 ch1 ch2 ch3] ...
 *   ↑ 프레임 0       ↑ 프레임 1       ↑ 프레임 2
 * ```
 *
 * 측정은 **고른 한 채널**로만 한다. 여기서 뽑지 않고 그대로 FFT 에 넣으면
 * 네 채널이 하나의 파형으로 이어 붙은 꼴이 돼, 주파수 축이 통째로
 * 어긋나고 없던 성분이 생긴다.
 *
 * **평균을 내지 않는다.** 다운믹스는 서로 다른 마이크의 소리를 섞는
 * 것이고, 보정값은 마이크마다 다르다. 「어느 마이크로 쟀는가」가 흐려지면
 * 절대 음압을 말할 수 없다.
 *
 * @param src 섞여 들어온 표본들. 앞에서부터 [samples] 개만 본다.
 * @param samples `src` 에 실제로 들어 있는 **표본 수**(프레임 수가 아니다).
 * @param channelCount 한 프레임에 든 채널 수. 1 이면 그냥 베낀다.
 * @param channelIndex 뽑을 채널(0부터).
 * @param dst 받을 곳. 적어도 `samples / channelCount` 개는 들어가야 한다.
 * @return 뽑아낸 **프레임 수**.
 */
fun deinterleave(
    src: FloatArray,
    samples: Int,
    channelCount: Int,
    channelIndex: Int,
    dst: FloatArray,
): Int {
    require(channelCount >= 1) { "채널 수가 1보다 작다: $channelCount" }
    require(channelIndex in 0 until channelCount) {
        "채널 번호가 범위를 벗어난다: $channelIndex / $channelCount"
    }
    require(samples >= 0 && samples <= src.size) {
        "표본 수가 범위를 벗어난다: $samples (크기 ${src.size})"
    }

    // **모자란 꼬리는 버린다.** 채널 수로 나누어떨어지지 않게 들어오면
    // 마지막 프레임이 반쯤 찬 것인데, 그 자리를 0 으로 채우면 없던
    // 「툭」 소리가 생긴다. 다음 번에 이어서 온다.
    val frames = samples / channelCount
    require(frames <= dst.size) {
        "받을 곳이 작다: 프레임 $frames, 크기 ${dst.size}"
    }

    if (channelCount == 1) {
        System.arraycopy(src, 0, dst, 0, frames)
        return frames
    }

    var s = channelIndex
    for (f in 0 until frames) {
        dst[f] = src[s]
        s += channelCount
    }
    return frames
}

/**
 * [samples] 개를 읽으려면 몇 개를 요청해야 하는가.
 *
 * 읽는 쪽은 **프레임** 단위로 생각하는데 `AudioRecord` 는 **표본** 단위로
 * 주고받는다. 이 둘을 헷갈리면 4채널에서 네 배 긴 버퍼를 요청하거나
 * 4분의 1만 읽는다.
 */
fun framesToSamples(frames: Int, channelCount: Int): Int {
    require(frames >= 0) { "프레임 수가 음수다: $frames" }
    require(channelCount >= 1) { "채널 수가 1보다 작다: $channelCount" }
    return frames * channelCount
}
