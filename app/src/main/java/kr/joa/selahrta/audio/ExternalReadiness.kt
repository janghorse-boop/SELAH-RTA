package kr.joa.selahrta.audio

import kr.joa.selahrta.domain.MicKind

/**
 * 외부 마이크로 재기 전에 **무엇을 확인했고 무엇을 못 했는가**
 * (USB 오디오 지시서 13장).
 *
 * 지시서가 스스로 못박은 것이 이 파일의 전부다:
 *
 * > 팬텀전원은 앱이 **실제 확인한 것처럼 ✓ 표시하지 말고** 사용자 확인
 * > 항목으로 둔다.
 *
 * 앱은 USB 신호만으로 +48V 가 켜졌는지 알 수 없다. 신호가 들어온다는
 * 것은 팬텀전원이 켜졌다는 **증거가 아니다** — 다이내믹 마이크는
 * 팬텀 없이도 소리를 낸다. 그러니 확인한 것과 못 한 것을 갈라 적는다.
 */
enum class ReadinessKind {
    /** **앱이 실제로 확인했다.** 열린 값·들어온 소리로 안다. */
    Verified,

    /** 앱이 확인했고, 아직 아니다. */
    NotYet,

    /** **앱이 확인할 수 없다.** 사람이 봐야 한다. */
    AskUser,
}

data class ReadinessItem(
    val labelKo: String,
    val kind: ReadinessKind,
    /** 왜 이 상태인지. 「아직」과 「모름」은 까닭이 다르다. */
    val detailKo: String? = null,
)

/**
 * 준비 상태를 만든다. **외부 입력일 때만 뜻이 있다.**
 *
 * 내장 마이크에는 팬텀전원도 GAIN 도 없으므로 빈 목록을 준다 — 화면은
 * 아무것도 그리지 않는다.
 *
 * @param signalSeen 소리가 실제로 들어오고 있는가([SilenceWatch] 의 반대).
 * @param clipping 지금 잘리고 있는가.
 * @param curveApplied 주파수 보정이 **걸려 있는가**(파일만 있는 것과 다르다).
 */
fun externalReadiness(
    opened: OpenedFormat?,
    signalSeen: Boolean,
    clipping: Boolean,
    curveApplied: Boolean,
): List<ReadinessItem> {
    if (opened == null || opened.micKind == MicKind.BuiltIn) return emptyList()

    return buildList {
        add(
            ReadinessItem(
                "${opened.deviceLabel} 연결됨",
                // 열려서 소리를 읽고 있다는 것은 **실제로 확인한** 사실이다.
                ReadinessKind.Verified,
                "${opened.sampleRate} Hz · " +
                    if (opened.channelCount > 1) {
                        "${opened.channelCount}채널 중 ${opened.channelIndex + 1}번"
                    } else {
                        "모노"
                    },
            ),
        )

        add(
            if (signalSeen) {
                ReadinessItem("오디오 신호 감지됨", ReadinessKind.Verified)
            } else {
                ReadinessItem(
                    "오디오 신호 없음",
                    ReadinessKind.NotYet,
                    "마이크 연결과 +48 V 팬텀전원, GAIN 을 확인하십시오.",
                )
            },
        )

        add(
            if (clipping) {
                ReadinessItem(
                    "입력이 잘리고 있음",
                    ReadinessKind.NotYet,
                    "${opened.deviceLabel} 의 GAIN 을 낮추십시오.",
                )
            } else {
                ReadinessItem("입력이 잘리지 않음", ReadinessKind.Verified)
            },
        )

        add(
            if (curveApplied) {
                ReadinessItem("마이크 보정 적용됨", ReadinessKind.Verified)
            } else {
                ReadinessItem(
                    "마이크 보정 없음",
                    ReadinessKind.NotYet,
                    "측정 마이크의 보정 파일을 넣으면 주파수 응답 차이를 되돌립니다.",
                )
            },
        )

        // **여기부터는 앱이 모른다.** ✓ 를 붙이지 않는다.
        add(
            ReadinessItem(
                "+48 V 팬텀전원 켜짐",
                ReadinessKind.AskUser,
                "앱은 이것을 확인할 수 없습니다. 소리가 들어온다고 해서 " +
                    "팬텀전원이 켜진 것은 아닙니다 — 팬텀이 필요 없는 마이크도 " +
                    "소리를 냅니다. ${opened.deviceLabel} 에서 직접 보십시오.",
            ),
        )
        add(
            ReadinessItem(
                "GAIN 이 알맞게 맞춰짐",
                ReadinessKind.AskUser,
                "잘리지 않는 것까지만 앱이 압니다. 너무 낮아 잡음에 묻히는지는 " +
                    "입력 레벨 막대를 보고 사람이 정합니다.",
            ),
        )
    }
}
