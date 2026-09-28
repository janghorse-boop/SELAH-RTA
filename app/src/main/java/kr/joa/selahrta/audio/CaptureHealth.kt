package kr.joa.selahrta.audio

/**
 * **재는 동안 소리를 잃고 있는가.** 문제가 있을 때만 한 줄을 돌려준다.
 *
 * ## 왜 생겼나 (독립 검토 UIS-03, 2026-09-28)
 *
 * 측정 화면의 「캡처 진단」 상자를 걷어내면서, 그 안에만 있던
 * `audioLagMs` 와 `readErrors` 의 **표시 경로가 함께 사라졌다.**
 * 상자를 지운 것은 담당자 지시였지만(「아래 캡쳐 진단 문구는 삭제해
 * 주세요」), 지워야 했던 것은 **늘 떠 있는 숫자판**이지 「소리를 잃고
 * 있다」는 알림이 아니다.
 *
 * 그대로 두면 사람은 **지나간 소리를 지금 값으로 읽는다.** 화면은
 * 멀쩡히 움직이는데 그 숫자가 몇 초 전 것이고, 아무것도 그 사실을
 * 말하지 않는다.
 *
 * ## 처리 시간을 따로 보지 않는 까닭
 *
 * `lastProcessMs` 는 한 덩어리를 처리한 시간이라 **순간적으로 튄다.**
 * 그 값으로 경고하면 잘 돌아가는 기기에서도 상자가 깜빡인다.
 *
 * 처리가 **계속** 덩어리보다 느리면 그 결과는 반드시 [CaptureDiagnostics.audioLagMs]
 * 로 나타난다 — 밀린 만큼 쌓이기 때문이다. 그러니 지연을 보는 것이
 * 곧 「지속되는 처리 지연」을 보는 것이고, 따로 히스테리시스를 둘
 * 까닭이 없다. 순간의 튐은 여기 오지 않는다.
 */
fun captureWarningKo(diag: CaptureDiagnostics): String? {
    val losing = !diag.keepingUp
    val hadErrors = diag.readErrors > 0
    if (!losing && !hadErrors) return null

    return buildString {
        if (losing) {
            append("소리가 %.0f초쯤 밀리고 있습니다 — ".format(diag.audioLagMs / 1000.0))
            append("지금 보이는 값은 그만큼 지난 소리입니다.")
        }
        if (hadErrors) {
            if (losing) append(" ")
            append("읽기 오류 ${diag.readErrors}번. ")
            // **지나간 일과 지금 상태를 가른다**(독립 검토 UIS-03).
            // 누적 횟수만 적으면 이미 회복했는데도 「지금 고장 났다」로
            // 읽는다. 반대로 지금만 적으면 잃은 구간이 있었다는 사실이
            // 사라진다. 둘 다 적는다.
            append(
                if (losing) "그 사이의 소리는 남지 않았습니다."
                else "지금은 이어지고 있지만, 그 자리의 소리는 남지 않았습니다.",
            )
        }
    }
}
