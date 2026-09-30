package kr.joa.selahrta.recording

import kr.joa.selahrta.domain.MicKind

/**
 * **이 보정을 이 기록에 걸어도 되는가**(독립 검토 R3-03).
 *
 * ## 왜 물어야 하나
 *
 * 다시 분석은 **지금 걸린 보정**을 씁니다. 그런데 지금 걸린 보정은
 * **지금 열린 입력**의 것입니다 — 내장 마이크로 담은 기록에 **USB
 * 마이크의 감도와 곡선**을 걸면, 과거 기록이 **다른 마이크의 잣대로
 * 바뀌고** 그것이 「보정 완료된 수치」처럼 보입니다.
 *
 * 화면에는 아무 표시도 안 납니다. 오프셋은 숫자 하나라, 어느 마이크의
 * 것인지 값만 봐서는 알 길이 없습니다.
 *
 * ## 무엇으로 가리나
 *
 * **기록이 적어 둔 입력**과 **지금 열린 입력**이 같은지 봅니다. 같으면
 * 지금 보정이 그 기록의 것이기도 합니다.
 *
 * **모르면 막습니다.** 열린 것이 없거나 기록에 신원이 안 적혀 있으면
 * 「아마 같겠지」로 넘기지 않습니다 — 틀렸을 때 되돌릴 표시가 없습니다.
 *
 * ## 왜 화면 밖에 두나
 *
 * 이 판단이 틀리면 **조용히 틀립니다.** 기기를 꽂아야만 확인할 수 있는
 * 자리에 두면 다음에 누가 고칠 때 또 꽂아야 합니다.
 */
object ReanalysisIdentity {

    /**
     * 걸어도 되면 null, 안 되면 **까닭**.
     *
     * @param openedDeviceKey 지금 열린 입력의 열쇠. 안 열렸으면 null.
     * @param routeConfirmed 어느 기기로 열렸는지 **확인됐는가.**
     *   확인 전의 열쇠는 「요청한 기기」일 뿐 열린 기기가 아니다.
     */
    fun blockedReasonKo(
        meta: SessionMeta,
        openedDeviceKey: String?,
        openedMicKind: MicKind?,
        openedChannelIndex: Int?,
        routeConfirmed: Boolean,
    ): String? {
        if (openedDeviceKey.isNullOrBlank()) {
            return "지금 열린 입력이 없어 어느 마이크의 보정인지 알 수 없습니다. " +
                "측정을 시작해 이 기록을 담았던 마이크를 연 뒤에 다시 하십시오."
        }
        if (!routeConfirmed) {
            return "어느 마이크로 열렸는지 아직 확인되지 않았습니다. " +
                "확인된 뒤에 다시 하십시오 — 지금 걸면 다른 기기의 보정이 걸릴 수 있습니다."
        }
        if (meta.deviceKey.isBlank()) {
            return "이 기록에는 어느 마이크로 쟀는지 적혀 있지 않습니다(옛 기록). " +
                "지금 보정이 그때의 마이크 것인지 알 수 없어 다시 셈하지 않습니다."
        }
        if (meta.deviceKey != openedDeviceKey) {
            return "이 기록은 다른 마이크로 쟀습니다(${meta.deviceLabel}). " +
                "지금 열린 마이크의 보정을 걸면 **다른 마이크의 잣대**가 됩니다."
        }
        // **같은 기기라도 채널이 다르면 다른 마이크다.** 오디오 인터페이스는
        // Input 1 과 Input 3 에 서로 다른 마이크가 꽂혀 있을 수 있다.
        if (openedChannelIndex != null && meta.channelIndex != openedChannelIndex) {
            return "이 기록은 같은 기기의 다른 입력(${meta.channelIndex + 1}번)으로 쟀습니다. " +
                "지금은 ${openedChannelIndex + 1}번이 열려 있어 보정이 다릅니다."
        }
        // 열쇠가 같으면 대개 종류도 같지만, 어긋나면 **열쇠 쪽을 못 믿는다.**
        if (openedMicKind != null && meta.micKind != openedMicKind) {
            return "이 기록은 ${meta.micKind.name} 마이크로 쟀는데 지금은 " +
                "${openedMicKind.name} 이 열려 있습니다."
        }
        return null
    }
}
