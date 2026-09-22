package kr.joa.selahrta.ui.components

/**
 * 계기 바가 **색으로** 말하는 것을 **말로** 옮긴다(TalkBack).
 *
 * ## 왜 따로 있는가
 *
 * 화면의 「낮음/적정/높음」 배지는 사용자 요청으로 지웠다 — 바가 이미
 * 같은 말을 하고 있어 화면에 같은 말이 두 번 나왔다. 그런데 **배지를
 * 지우는 것과 뜻을 지우는 것은 다르다**: 색을 못 보는 사람에게는 바가
 * 아무 말도 하지 않게 된다. 검증자가 그 둘을 구분하라고 짚었다.
 *
 * 그래서 화면에는 색만 두고, **읽어 주는 쪽에는 말을 남긴다.**
 *
 * ## 경계
 *
 * 색은 [FADE_DB] 에 걸쳐 건너가지만 **말은 건너갈 수 없다.** 「조금
 * 높음」 같은 중간 단계를 두면 같은 값이 색과 다른 것을 말하게 된다.
 * 그래서 말은 **범위 안인가 아닌가**로만 가른다 — 색의 중간 단계는
 * 「보기에 부드럽게」를 위한 것이지 판정이 아니다.
 *
 * `null` 은 「말할 것이 없다」이고, 부르는 쪽이 상태 설명을 붙이지 않는다.
 * A 가중이 아니거나 범위가 없거나 값이 없을 때다.
 */
fun levelStateKo(db: Double?, low: Double?, high: Double?): String? {
    if (db == null || low == null || high == null) return null
    return when {
        db < low -> "권장 범위보다 낮음"
        db > high -> "권장 범위보다 높음"
        else -> "권장 범위 안"
    }
}

/**
 * 읽어 주는 쪽에 넘길 한 줄. 값과 판정을 함께 말한다.
 *
 * 숫자만 읽어 주면 「73.4」가 높은 것인지 낮은 것인지 알 수 없고, 판정만
 * 읽어 주면 얼마나 벗어났는지 알 수 없다.
 */
fun levelSpeechKo(db: Double?, low: Double?, high: Double?, unit: String): String {
    val value = if (db == null) "측정값 없음" else "%.1f %s".format(db, unit)
    val state = levelStateKo(db, low, high) ?: return value
    return "$value, $state"
}
