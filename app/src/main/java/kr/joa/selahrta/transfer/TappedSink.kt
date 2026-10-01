package kr.joa.selahrta.transfer

import kr.joa.selahrta.audio.SignalSink

/**
 * 실제로 출력에 넘어간 표본을 가로채는 덧씌우기.
 *
 * **「나중에 다시 만든 값」을 기준으로 쓰지 않는다**(명세 4장). 같은 식으로
 * 다시 그린 파형은 볼륨·부분 쓰기를 지나지 않아 실제로 나간 것과 다르다.
 *
 * ## 두 가지가 조용히 틀리기 쉽다
 *
 * 1. **`write()` 는 일부만 쓸 수 있다.** 넘긴 버퍼 전체를 적으면 기준이
 *    실제보다 앞서 가고 지연이 **작게** 나온다. 화면에는 아무 표시도
 *    안 난다. 그래서 **돌려받은 수만큼만** 적는다.
 * 2. **세는 단위가 프레임이 아니라 「칸」이다**(`SignalSink.write` 문서).
 *    스테레오면 한 프레임이 두 칸이라, 홀수 칸에서 끊기면 프레임 경계가
 *    어긋난다. 반 프레임을 들고 있다가 짝이 오면 그때 내보낸다.
 *
 * **스테레오 입력을 가정한다.** 짝짓기(2칸 = 1프레임, L+R) 로직은
 * `channels` 인자를 보지 않는다 — `inner.open()` 에 그대로 넘길 뿐이다.
 * 지금은 호출자(`SignalPlayer`)가 늘 두 채널로 열어서 안전하지만,
 * `channels = 1` 로 여는 호출자가 생기면 무관한 모노 표본 둘을 하나로
 * 합쳐 버린다.
 *
 * @param onMono 모노로 합친 표본. **재생 스레드에서 불린다** — 여기서
 *   무거운 일을 하면 소리가 끊긴다.
 */
class TappedSink(
    private val inner: SignalSink,
    private val onMono: (FloatArray, Int, Int) -> Unit,
) : SignalSink {

    /** 짝을 기다리는 왼쪽 칸. */
    private var pendingLeft = 0f
    private var hasPending = false

    private var mono = FloatArray(1024)

    override fun open(sampleRate: Int, frames: Int, channels: Int): Boolean {
        // 지난 판의 반 프레임을 끌고 오지 않는다.
        hasPending = false
        return inner.open(sampleRate, frames, channels)
    }

    override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
        val wrote = inner.write(buf, offset, frames)
        if (wrote <= 0) return wrote

        if (mono.size < (wrote + 1) / 2) mono = FloatArray((wrote + 1) / 2)
        var out = 0
        for (i in 0 until wrote) {
            val v = buf[offset + i]
            if (hasPending) {
                mono[out++] = pendingLeft + v
                hasPending = false
            } else {
                pendingLeft = v
                hasPending = true
            }
        }
        if (out > 0) onMono(mono, 0, out)
        return wrote
    }

    override fun stop() {
        // stop() 은 open() 을 다시 거치지 않고도 write() 가 이어올 수
        // 있다(SignalSink.stop() 의 계약이 그것을 막지 않는다). stop()
        // 전후의 표본은 시간상 이어져 있지 않으므로, 짝을 못 찾은 반
        // 프레임은 버리는 것이 맞다 — 그 값은 애초에 onMono 로 나간 적이
        // 없어 잃는 것도 없다.
        hasPending = false
        inner.stop()
    }

    override fun release(): Boolean {
        hasPending = false
        return inner.release()
    }
}
