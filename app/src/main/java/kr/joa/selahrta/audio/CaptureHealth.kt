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
 * ## 「밀렸다」가 아니라 「끊겼다」를 본다 (실기기 확인 2026-09-29)
 *
 * 처음에는 [CaptureDiagnostics.audioLagMs] 를 그대로 보고 500ms 를
 * 넘으면 알렸다. 그런데 UMC404HD 를 붙여 재 보니 **측정을 시작하자마자
 * 경고가 떴고, 90초를 재도 그대로 「1초」였다.**
 *
 * 그 1초는 잃은 소리가 아니라 **기기를 여는 데 걸린 시간**이다. 그 몫은
 * 처음부터 늦게 출발한 것이라 영영 따라잡히지 않는다. 그것으로 경고하면
 * **측정할 때마다 늘 떠 있는 경고**가 되는데, 그건 없느니만 못하다 —
 * 사람이 무시하는 법을 배우고 정작 진짜로 잃을 때도 안 읽는다.
 *
 * 그래서 출발선을 걷어낸 [CaptureDiagnostics.lagGrowthMs] 를 본다.
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
            // **늘어난 만큼만 말한다.** 기기를 여는 데 걸린 시간은 잃은
            // 것이 아니다(`CaptureDiagnostics.lagGrowthMs`).
            append("소리가 %.0f초쯤 끊겼습니다 — ".format(diag.lagGrowthMs / 1000.0))
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
