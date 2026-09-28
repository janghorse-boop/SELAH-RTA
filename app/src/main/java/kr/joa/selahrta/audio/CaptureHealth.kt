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
/**
 * @param lastInputAgeMs 마지막으로 소리가 들어온 뒤 흐른 시간(ms).
 *   모르면 null(아직 한 덩어리도 안 왔거나 측정 중이 아니다).
 *   **덩어리가 와야 갱신되는 [diag] 와 달리 이 값은 시계만으로 자란다** —
 *   콜백이 아예 멈춘 것을 알아채려면 그래야 한다.
 */
fun captureWarningKo(diag: CaptureDiagnostics, lastInputAgeMs: Double? = null): String? {
    val stalled = lastInputAgeMs != null && lastInputAgeMs >= INPUT_STALL_MS
    val shortfall = !diag.keepingUp
    val hadErrors = diag.readErrors > 0
    if (!stalled && !shortfall && !hadErrors) return null

    return buildString {
        // **지금 안 들어오는 것이 가장 급하다**(독립 재검증 UISR-03).
        //
        // 콜백이 멈추면 [diag] 도 멈춘다 — 그래서 누적값만 보던 예전
        // 경고는 입력이 끊겨도 **아무 말도 하지 않았다.** 화면은 마지막
        // 숫자를 그대로 들고 멀쩡히 서 있었다.
        if (stalled) {
            append("소리가 들어오지 않습니다(마지막 %.0f초 전). ".format(lastInputAgeMs!! / 1000.0))
            append("화면의 값은 그때 잰 것입니다 — 연결을 확인하십시오.")
        }

        if (shortfall) {
            if (isNotEmpty()) append(" ")
            // **관측한 것만 말한다**(독립 재검증 UISR-03).
            //
            // 예전에는 「지금 보이는 값은 그만큼 지난 소리입니다」라고
            // 적었다. 그런데 이 차이는 **늦게 오는 소리와 아주 사라진
            // 소리를 구별하지 못한다** — 잃었다가 다시 이어져도 차이는
            // 남는다. 그때 「지난 소리」라고 하면 멀쩡한 지금 값을 과거로
            // 안내하게 된다. 그래서 잰 것만 적고 뜻은 단정하지 않는다.
            append("받은 소리가 흐른 시간보다 %.0f초쯤 적습니다 — ".format(diag.lagGrowthMs / 1000.0))
            append("늦게 오거나 빠진 구간이 있습니다.")
        }

        if (hadErrors) {
            if (isNotEmpty()) append(" ")
            append("읽기 오류 ${diag.readErrors}번 — 그 자리의 소리는 남지 않았습니다.")
        }
    }
}

/**
 * 이보다 오래 소리가 안 들어오면 「멈췄다」고 말한다(ms).
 *
 * 화면 갱신 간격(66ms)의 열 배가 넘는다. USB 기기를 다시 무는 정도의
 * 짧은 끊김으로 상자가 깜빡이지 않을 만큼은 길고, 사람이 「멈췄네」라고
 * 느끼기 전에는 뜬다.
 */
const val INPUT_STALL_MS = 1_000.0
