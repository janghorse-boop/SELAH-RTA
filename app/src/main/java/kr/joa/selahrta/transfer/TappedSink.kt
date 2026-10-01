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
 * **스테레오 입력을 전제한다.** 짝짓기(2칸 = 1프레임)는 `channels` 인자를
 * 보지 않으므로 `open()` 에서 `channels == 2` 를 직접 강제한다 — 그러지
 * 않으면 `channels = 1` 로 여는 호출자가 생겼을 때 무관한 모노 표본 둘을
 * 하나로 합쳐 버리고도 아무도 모른다.
 *
 * **`(L + R) / 2` 로 평균해 합친다 — 크기가 걸린다.** 상관(지연 추정)은
 * 신호의 크기에 무관하지만, **Magnitude(H1)는 무관하지 않다.**
 *
 * **전달함수의 기준은 「양쪽(Both)」을 전제로 한다.** 양쪽에 같은 신호가
 * 실리면 평균이 정확히 그 신호가 된다. **한쪽만 울리는 모드에서는 기준이
 * 6 dB 낮아져 Magnitude 가 그만큼 밀린다** — 전달함수를 잴 때는 Both 로
 * 쓴다. (더 나은 길은 **어느 채널을 기준으로 쓸지 명시적으로 넘겨받는
 * 것**인데, 지금은 `SignalPlayer` 가 출력 장치를 **인자 없는 공장**으로
 * 만들어 모드를 전해 줄 자리가 없다. 화면·배선을 붙일 때 함께 고친다.)
 *
 * @param onMono 모노로 평균한 표본. **재생 스레드에서 불린다** — 여기서
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
        // 이 덧씌우기는 두 칸을 한 프레임으로 읽는다 — channels 를 보지
        // 않고 무조건 L/R 로 짝짓기 때문에, 모노로 열리면 무관한 표본
        // 둘을 하나로 합쳐 버린다. 그래서 여기서 미리 막는다.
        require(channels == 2) { "TappedSink 는 스테레오 전용: channels=$channels" }
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
                mono[out++] = (pendingLeft + v) * 0.5f
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
