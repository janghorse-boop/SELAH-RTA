package kr.joa.selahrta.audio

import kr.joa.selahrta.domain.MicKind

/**
 * **뒤쪽 내장 마이크를 골랐을 때 알려야 하는 것**
 * (S23 개별 자동교정 지시서 6장: 「케이스 등 음향 조건 변경 시 재검증을
 * 요청한다」).
 *
 * 폰 케이스는 **후면 마이크 구멍을 막거나 좁힌다.** 하단 마이크는 대개
 * 케이스가 트여 있지만 후면은 그렇지 않다. 같은 폰인데도 두 마이크의
 * 응답이 크게 갈리는 가장 큰 까닭이 이것일 수 있다 — 마이크 자체의
 * 차이가 아니라 **케이스의 차이**다.
 *
 * 그걸 모르고 재면, 케이스가 만든 감쇠를 「이 마이크의 응답」으로 적어
 * 보정에 넣게 된다. 그 보정은 케이스를 바꾸는 순간 틀린 값이 된다.
 *
 * ## 위치는 **기기가 알린 주소**로 판단한다
 *
 * 모델명으로 「S23 은 후면이 어디」를 박아 넣지 않는다(지시서 1장).
 * 안드로이드가 주는 주소(`back`·`rear`)를 그대로 읽는다. 모르는 주소면
 * 아무 말도 하지 않는다 — 짐작해서 케이스를 벗기라고 하지 않는다.
 */

/** 이 주소가 **폰 뒤쪽**을 가리키는가. */
fun isRearAddress(address: String): Boolean =
    address.trim().lowercase() in setOf("back", "rear")

/**
 * 고른 기기에 대해 알릴 말. 없으면 null.
 *
 * @param device 사람이 고른(또는 지금 열린) 입력 기기.
 * @param separation 내장 마이크 탐색 결과. 아직 안 재 봤으면 null.
 */
fun builtInMicNoticeKo(device: InputDeviceInfo?, separation: MicSeparation? = null): String? {
    if (device == null || device.kind != MicKind.BuiltIn) return null
    if (!isRearAddress(device.address)) return null

    return buildString {
        append("후면 마이크를 골랐습니다. **케이스를 벗기고 재십시오** — ")
        append("케이스가 후면 구멍을 막으면 그 감쇠가 이 마이크의 응답으로 ")
        append("기록되고, 케이스를 바꾸는 순간 그 보정은 틀린 값이 됩니다.")

        append("\n\n하단 마이크와 **따로** 재십시오. 두 마이크는 위치가 달라 ")
        append("같은 자리에 둘 수 없고, 한 번에 잰 값을 나눠 쓸 수 없습니다.")

        if (separation != null && separation != MicSeparation.Separable) {
            append("\n\n그리고 이 폰에서는 **후면을 골라도 하단이 함께 켜집니다**")
            append("(내장 마이크 탐색 결과). 섞여 들어오는 소리라 ")
            append("「후면 마이크의 응답」이라고 부를 수 없습니다.")
        }
    }.replace("**", "")
}
