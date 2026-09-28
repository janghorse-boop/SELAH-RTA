package kr.joa.selahrta.calibration

/**
 * **지금 보정값을 저장해도 되는가.**
 *
 * 화면도 안드로이드도 없이 판단한다 — 이 결정이 틀리면 그 뒤의 모든
 * 숫자가 조용히 틀리므로, 시험할 수 있는 자리에 두어야 한다.
 *
 * ## 왜 생겼나 (독립 검토 UIS-02, 2026-09-28)
 *
 * 교정기 단추가 「값을 읽고 있는가」만 보고 열려 있었다. 순음 판정은
 * 화면에 한 줄로 적기만 하고 저장 경계에는 닿지 않았다. 그래서
 * **교정기 없이 주변 소리에 맞춰도 그대로 저장됐다** — 검토자가 넣어
 * 본 값이 `offset=144.0 dB` 였다.
 *
 * 그때 「틀리게 맞추면 화면이 이상해진다」고 적어 두었는데 **그 말이
 * 틀렸다.** 오프셋은 `기준값 − 지금 읽는 값`이라, 무엇에 대고 맞추든
 * 맞춘 직후 화면은 반드시 기준값을 가리킨다. 틀릴수록 멀쩡해 보인다.
 *
 * ## 막는 것과 묻는 것을 가른다
 *
 * - **[Reject]** 는 사람이 확인해도 넘어가지 못한다. 잘린 파형이나 아직
 *   올라오는 중인 바늘로 셈한 오프셋은 **누가 확신하든 틀린 값**이다.
 * - **[AskConfirmation]** 은 사람이 답할 수 있다. 순음 판정은 교정기가
 *   헐겁거나 방이 시끄러우면 오락가락하는데, 그때 잠가 버리면 아예
 *   보정을 못 한다(2026-09-28 담당자 지시: 「간편교정은 쉬워야 합니다」).
 */
sealed interface CalibrationGate {
    /** 저장하면 안 된다. 사람이 확인해도 마찬가지다. */
    data class Reject(val reasonKo: String) : CalibrationGate

    /** 저장해도 되지만 **한 번 물어야** 한다. */
    data object AskConfirmation : CalibrationGate

    /** 그대로 저장한다. */
    data object Save : CalibrationGate
}

/**
 * @param opened 입력이 열려 있는가(측정 중인가).
 * @param routeConfirmed 어느 마이크로 열렸는지 확인됐는가. 아니면 다른
 *   기기의 보정값으로 남을 수 있다.
 * @param measuredDbfs 지금 읽는 날 값. 없으면 셈할 수 없다.
 * @param clipped 파형이 풀스케일에 닿고 있는가.
 * @param settled 시간가중이 자리를 잡았는가.
 * @param toneOk 1kHz 순음이 보이는가. **모르면 null** — 첫 FFT 전이다.
 *   모르는 것을 「보인다」로 읽지 않는다.
 */
fun calibrationGate(
    opened: Boolean,
    routeConfirmed: Boolean,
    measuredDbfs: Double?,
    clipped: Boolean,
    settled: Boolean,
    source: CalibrationSource,
    toneOk: Boolean?,
): CalibrationGate = when {
    !opened -> CalibrationGate.Reject("먼저 측정을 시작해야 보정할 수 있습니다.")

    !routeConfirmed -> CalibrationGate.Reject(
        "어느 마이크로 열렸는지 아직 확인되지 않았습니다. 확인된 뒤에 " +
            "보정하십시오 — 지금 저장하면 다른 기기의 보정값으로 남을 수 있습니다.",
    )

    measuredDbfs == null ->
        CalibrationGate.Reject("아직 읽은 값이 없습니다. 잠시 뒤에 다시 누르십시오.")

    // **잘린 소리로 맞추지 않는다.** 풀스케일에 닿은 순간의 실제 음압은
    // 읽은 값보다 높고, 얼마나 높은지는 알 길이 없다.
    clipped -> CalibrationGate.Reject(
        "소리가 너무 커서 파형이 잘리고 있습니다. 입력 볼륨을 낮춘 뒤에 " +
            "보정하십시오 — 지금 맞추면 잘린 만큼 틀립니다.",
    )

    // **바늘이 자리를 잡기 전에 맞추지 않는다.** 시작 직후 값은 0 에서
    // 올라오는 중이라 실제보다 낮다.
    !settled ->
        CalibrationGate.Reject("값이 아직 자리를 잡는 중입니다. 잠시 뒤에 다시 누르십시오.")

    // **교정기 단추만 순음을 본다.** 기준 소음계에 맞추는 쪽은 사람이
    // 다른 계기의 숫자를 보고 적는 것이라 순음이 있을 까닭이 없다.
    source != CalibrationSource.Calibrator -> CalibrationGate.Save

    toneOk == true -> CalibrationGate.Save

    else -> CalibrationGate.AskConfirmation
}
