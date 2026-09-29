package kr.joa.selahrta.data.rta

import kr.joa.selahrta.audio.SignalChannels

/**
 * **L → R → L+R 을 이어서 재는 차례**(지시서 §7 후속).
 *
 * ## 왜 이어서 재나
 *
 * 좌우를 견주려면 세 번을 **같은 자리에서, 같은 조건으로** 재야 한다.
 * 손으로 하면 그 사이에 채널을 잘못 고르거나, 이름을 다르게 적거나,
 * 마이크를 옮기게 된다 — 그러면 잰 차이가 **좌우의 차이가 아니게 된다.**
 *
 * 한 번 눌러 세 번을 잇는 것이 이 장치의 전부다.
 *
 * ## 세 번을 다 재야만 끝나는 것은 아니다
 *
 * 담당자 지시대로 **한 채널만 있어도 쓸모가 있다.** 도중에 그만두면
 * 그때까지 저장한 것이 그대로 남는다 — 지우지 않는다.
 */
class RtaSequence(
    /** 사람이 붙인 이름. 각 걸음의 이름은 여기에 채널을 붙여 만든다. */
    val baseNameKo: String,
    /** 세 걸음이 **한 묶음**으로 들어갈 자리. */
    val setId: String,
    /** 잴 차례. 바꾸는 일은 없지만 시험이 줄여 쓴다. */
    val steps: List<SignalChannels> = DEFAULT_STEPS,
) {
    /** 지금 몇 번째인가(0부터). */
    var index: Int = 0
        private set

    val done: Boolean get() = index >= steps.size

    /** 지금 잴 채널. 끝났으면 null. */
    fun currentChannel(): SignalChannels? = steps.getOrNull(index)

    /**
     * 지금 걸음의 이름.
     *
     * **채널을 이름에 넣는다.** 세 개가 같은 이름이면 목록에서도 차이
     * 요약에서도 어느 쪽이 어느 쪽인지 갈리지 않는다.
     */
    fun currentNameKo(): String {
        val ch = currentChannel() ?: return baseNameKo
        return "$baseNameKo · ${ch.labelKo}"
    }

    /** 화면에 적을 진행. 예: 「2/3 · 왼쪽만」 */
    fun progressKo(): String {
        val ch = currentChannel() ?: return "끝"
        return "${index + 1}/${steps.size} · ${ch.labelKo}"
    }

    fun advance() {
        if (!done) index++
    }

    companion object {
        /**
         * 왼쪽 → 오른쪽 → 양쪽.
         *
         * **양쪽을 마지막에 둔다.** 좌우를 각각 잰 다음에 합친 것을 재야,
         * 합친 것이 둘의 어디쯤인지 바로 견줄 수 있다. 양쪽을 먼저 재면
         * 그 기준이 없는 채로 좌우를 듣게 된다.
         */
        val DEFAULT_STEPS = listOf(
            SignalChannels.Left,
            SignalChannels.Right,
            SignalChannels.Both,
        )
    }
}
