package kr.joa.selahrta.audio

import kr.joa.selahrta.domain.MicKind

/**
 * **두 번째 내장 마이크를 골랐을 때 알려야 하는 것**
 * (S23 개별 자동교정 지시서 6장: 「케이스 등 음향 조건 변경 시 재검증을
 * 요청한다」).
 *
 * 폰 케이스는 마이크 구멍을 막거나 좁힐 수 있다. 그걸 모르고 재면,
 * 케이스가 만든 감쇠를 「이 마이크의 응답」으로 적어 보정에 넣게 된다.
 * 그 보정은 케이스를 바꾸는 순간 틀린 값이 된다.
 *
 * ## 안드로이드의 주소는 **실제 위치가 아니다** (2026-09-23 실기기 확인)
 *
 * 갤럭시 S23 Ultra 에서 안드로이드는 두 번째 내장 마이크의 주소를
 * `back` 으로 알린다. 그런데 **담당자가 확인한 실제 위치는 상단이다.**
 *
 * 그래서 예전에 이 파일이 하던 말 — 「후면 마이크를 골랐습니다. 케이스가
 * 후면 구멍을 막으면…」 — 은 이 폰에서 **틀린 안내**였다. 위쪽 마이크는
 * 대개 케이스가 덮지 않는다.
 *
 * 지시서 1장이 「모델명만으로 후면·하단을 하드코딩하지 않는다」고 한
 * 까닭이 이것이고, 2.3 이 「사용자에게 물리 위치를 확인받는다」고 한
 * 까닭도 이것이다. 주소는 **안드로이드가 붙인 이름**으로만 쓰고, 어디에
 * 있는지는 **사람에게 묻는다.**
 *
 * 그러니 이 안내도 위치를 단정하지 않는다 — 「이 마이크가 케이스에
 * 가려지는 자리인지 직접 보라」까지만 말한다.
 */

/**
 * 하단이 아닌 **두 번째** 내장 마이크인가.
 *
 * 이름이 `isRearAddress` 였는데 **그 이름 자체가 「뒤에 있다」는 단정**
 * 이라 바꿨다. 보는 것은 주소가 하단이 아니라는 것뿐이다.
 */
fun isSecondaryBuiltInAddress(address: String): Boolean {
    val a = address.trim().lowercase()
    return a.isNotEmpty() && a != "bottom"
}

/**
 * 고른 기기에 대해 알릴 말. 없으면 null.
 *
 * @param device 사람이 고른(또는 지금 열린) 입력 기기.
 * @param separation 내장 마이크 탐색 결과. 아직 안 재 봤으면 null.
 */
fun builtInMicNoticeKo(device: InputDeviceInfo?, separation: MicSeparation? = null): String? {
    if (device == null || device.kind != MicKind.BuiltIn) return null
    if (!isSecondaryBuiltInAddress(device.address)) return null

    val addr = device.address.trim().lowercase()
    return buildString {
        append("하단이 아닌 내장 마이크를 골랐습니다(안드로이드 표기: $addr). ")
        append("이 표기는 **실제 위치와 다를 수 있습니다** — 갤럭시 S23 Ultra 는 ")
        append("back 으로 알리지만 실제로는 상단에 있습니다(실기기 확인).")

        append("\n\n이 마이크가 **케이스에 가려지는 자리인지 직접 보십시오.** ")
        append("가려진다면 벗기고 재야 합니다. 케이스가 만든 감쇠를 이 마이크의 ")
        append("응답으로 적으면, 케이스를 바꾸는 순간 그 보정은 틀린 값이 됩니다.")

        append("\n\n하단 마이크와 **따로** 재십시오. 두 마이크는 위치가 달라 ")
        append("같은 자리에 둘 수 없고, 한 번에 잰 값을 나눠 쓸 수 없습니다.")

        if (separation != null && separation != MicSeparation.Separable) {
            append("\n\n그리고 이 폰에서는 **이것을 골라도 하단이 함께 켜집니다**")
            append("(내장 마이크 탐색 결과). 섞여 들어오는 소리라 ")
            append("「이 마이크의 응답」이라고 부를 수 없습니다.")
        }
    }.replace("**", "")
}
