package kr.joa.selahrta.audio

/**
 * **녹음 중 실제로 소리를 받는 마이크 조합**(개발지시서 4·18장).
 *
 * 폰에 내장 마이크가 셋 보여도, 한 번의 녹음에 실제로 쓰이는 것은
 * 하나일 수도 둘일 수도 있다. 지시서가 못박은 자리다:
 *
 * > 물리적인 마이크가 3개 존재하더라도 실제 ToneVista 녹음 시 1개 또는
 * > 여러 개가 동시에 활성화될 수 있음을 전제로 한다.
 *
 * 여기 있는 것은 **셈하고 견주는 일**뿐이다 — 안드로이드에서 값을 읽는
 * 일은 [MicrophoneProbe] 와 [MicSource] 가 하고, 이 파일은 기기 없이
 * 돌려 볼 수 있다.
 *
 * ## 빈 목록은 「없다」가 아니다
 *
 * `getActiveMicrophones()` 는 안드로이드 28 부터고, 그 위에서도 제조사에
 * 따라 빈 목록을 준다. 그것은 **「모른다」이지 「마이크가 없다」가
 * 아니다.**
 *
 * 이 저장소가 같은 모양으로 한 번 데였다 — 빈 주소를 「같다」로 읽어
 * `bottom` 의 감도를 `back` 에 걸었다(CAR-03). 그래서 여기서도 빈 쪽이
 * 끼면 **견주지 않는다.** 모르는 것을 근거로 「바뀌었다」고 말하지
 * 않는다.
 */

/**
 * 조합을 한 이름으로 굳힌다. 모르면 빈 문자열.
 *
 * id 를 **정렬해서** 붙인다. 안드로이드가 주는 차례는 보장되지 않아,
 * 그대로 이으면 같은 조합이 회차마다 다른 이름이 된다.
 */
fun activeMicComboKey(mics: List<ActiveMicInfo>): String =
    mics.map { it.id }.distinct().sorted().joinToString("+")

/** 진단 화면과 로그에 적을 한 줄. 모르면 그렇다고 적는다. */
fun activeMicComboKo(mics: List<ActiveMicInfo>): String {
    if (mics.isEmpty()) return "확인 불가"
    return mics.sortedBy { it.id }.joinToString(" · ") { m ->
        "Mic ${m.id}(그룹 ${m.group}·${m.indexInTheGroup})"
    }
}

/**
 * 조합이 바뀌었으면 사람에게 할 말. 안 바뀌었거나 **모르면** null.
 *
 * 화면의 숫자는 그대로인데 소리를 받는 마이크가 달라지면, 보정값은 옛
 * 조합의 것이라 **그만큼 틀린 채로 그럴듯해 보인다.** 지시서 18장이
 * 「Calibration Profile mismatch 를 표시한다」고 한 자리다.
 */
fun activeMicChangeKo(before: List<ActiveMicInfo>, after: List<ActiveMicInfo>): String? {
    // 한쪽이라도 모르면 견주지 않는다. 위 「빈 목록은 없다가 아니다」 참고.
    if (before.isEmpty() || after.isEmpty()) return null
    val was = activeMicComboKey(before)
    val now = activeMicComboKey(after)
    if (was == now) return null
    return "재는 도중에 소리를 받는 마이크가 바뀌었습니다($was → $now). " +
        "지금 걸린 보정값은 앞의 조합으로 맞춘 것이라 그만큼 어긋날 수 있습니다 — " +
        "측정을 멈췄다 다시 시작하십시오."
}
