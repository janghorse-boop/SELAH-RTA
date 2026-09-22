package kr.joa.selahrta.audio

import kr.joa.selahrta.dsp.deinterleave
import kr.joa.selahrta.dsp.framesToSamples

/**
 * 한 덩어리를 읽어 **고른 채널만** 돌려준다.
 *
 * `AudioRecord` 에 붙는 자리와 **프레임↔표본을 옮기는 자리**를 갈라
 * 놓으려고 따로 뒀다. 옮기는 쪽이 안드로이드에 매여 있으면 기기 없이
 * 돌려 볼 수 없는데, 여기가 한 칸이라도 밀리면 **다른 마이크의 소리를
 * 그 마이크의 보정값으로 재게 된다**(USB 오디오 지시서 17장 DoD).
 *
 * @param into 받을 곳. 모노로 편 값이 들어간다.
 * @param frames 몇 **프레임**을 바라는가.
 * @param channelCount 실제로 열린 채널 수.
 * @param channelIndex 그중 쓸 채널.
 * @param mixed 여러 채널이 섞인 것을 받을 자리. 모노면 null 이어도 된다.
 * @param readSamples 실제 읽기. **표본** 수를 받고 **표본** 수를 돌려준다.
 * @return 읽은 **프레임** 수. 0 이하는 그대로 흘려보낸다(멈추는 중이거나 오류).
 */
internal fun readOneChannel(
    into: FloatArray,
    frames: Int,
    channelCount: Int,
    channelIndex: Int,
    mixed: FloatArray?,
    readSamples: (dst: FloatArray, wantSamples: Int) -> Int,
): Int {
    val want = framesToSamples(frames, channelCount)

    // 모노면 섞인 것이 없다. 그대로 받는다 — 베끼기를 한 번 아낀다.
    if (channelCount == 1) return readSamples(into, want)

    requireNotNull(mixed) { "여러 채널($channelCount)인데 섞인 것을 받을 자리가 없다" }
    require(mixed.size >= want) {
        "섞인 것을 받을 자리가 작다: $want 개가 필요한데 ${mixed.size} 개다"
    }

    val n = readSamples(mixed, want)
    // **0 이하는 손대지 않고 그대로 넘긴다.** 음수는 오류이고 0 은 멈추는
    // 중인데, 여기서 프레임으로 나누면 −3 같은 오류 부호가 0 이 돼
    // 「그냥 조용한 덩어리」로 둔갑한다.
    if (n <= 0) return n

    return deinterleave(mixed, n, channelCount, channelIndex, into)
}
