package kr.joa.selahrta.calibration

/**
 * **저장된 전대역 보정을 지금 이 경로에 걸어도 되는가.**
 *
 * ## 왜 필요한가 (독립 재검토 CAR-03)
 *
 * 주파수 프로파일에는 「잰 자리」를 붙여 두고 자리가 다르면 자동 적용을
 * 멈춘다. 그런데 **전대역 오프셋**(dBFS → dB SPL)은 그 보호를 지나지
 * 않았다. 저장 열쇠에 자리가 없어서([CalibrationKey]), 내장 마이크의
 * `bottom` 에서 잰 +110dB 을 `back` 으로 다시 열어도 그대로 걸고
 * **「보정 완료」로 표시**했다.
 *
 * 주소가 다르다고 해서 물리적으로 다른 마이크라고 단정하는 것은 아니다.
 * 문제는 **같은 교정을 써도 된다는 근거 없이 승인한다**는 점이다.
 *
 * ## 모르면 「같다」가 아니다
 *
 * 옛 기록에는 자리가 없다. 그것을 「같다」로 읽으면 이 검사가 있으나 마나
 * 다. 그렇다고 **앱이 지금 주소를 과거의 것으로 채워 넣지도 않는다** —
 * 그러면 확인하지 않은 것을 확인했다고 적는 꼴이다. 사람이 「이 자리에서
 * 계속 쓰기」를 눌러야 채워진다.
 */
enum class RouteVerdict {
    /** 잰 자리와 같다. 그대로 건다. */
    Same,

    /** 어느 한쪽의 자리를 모른다. **걸지 않고 사람에게 묻는다.** */
    Unknown,

    /** 잰 자리와 다르다. **걸지 않는다.** */
    Different,
    ;

    /** 저절로 걸어도 되는가. */
    val mayAutoApply: Boolean get() = this == Same
}

/**
 * 저장된 보정의 자리와 지금 자리를 견준다.
 *
 * @param savedRoute 보정을 **잴 때**의 자리. 옛 기록이면 null.
 * @param nowRoute 지금 열린 경로의 자리. 확인 못 했으면 빈 문자열.
 * @param routeConfirmed 지금 경로를 실제로 확인했는가.
 */
fun judgeCalibrationRoute(
    savedRoute: String?,
    nowRoute: String,
    routeConfirmed: Boolean,
): RouteVerdict = when {
    savedRoute.isNullOrEmpty() -> RouteVerdict.Unknown
    !routeConfirmed || nowRoute.isEmpty() -> RouteVerdict.Unknown
    savedRoute == nowRoute -> RouteVerdict.Same
    else -> RouteVerdict.Different
}

/** 사람에게 보일 말. 걸어도 되면 null. */
fun routeNoticeKo(
    verdict: RouteVerdict,
    savedRoute: String?,
    nowRoute: String,
): String? = when (verdict) {
    RouteVerdict.Same -> null

    // **화면에 그대로 나가는 문장이다.** 마크다운 강조(`**`)를 쓰지
    // 않는다 — 이 자리는 서식 없는 Text 라 별표가 글자로 보인다
    // (2026-09-22 실기기에서 겪었다).
    RouteVerdict.Unknown ->
        "저장된 보정이 어느 자리에서 잰 것인지 기록돼 있지 않습니다. " +
            "같은 이름의 내장 마이크라도 자리가 다르면 감도가 다르므로, 확인하기 " +
            "전에는 걸지 않습니다. 이 자리에서 잰 것이 맞으면 「이 자리에서 계속 쓰기」를, " +
            "아니면 간편 보정을 다시 하십시오."

    RouteVerdict.Different ->
        "저장된 보정은 $savedRoute 자리에서 잰 것인데 지금은 $nowRoute 입니다. " +
            "자리가 다르면 감도도 다르므로 그대로 걸지 않습니다 — 잰 자리로 되돌리거나 " +
            "이 자리에서 간편 보정을 다시 하십시오."
}
