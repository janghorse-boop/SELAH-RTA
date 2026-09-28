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
 * @param evidence 오디오 스레드가 덩어리마다 새로 만든 근거. **화면
 *   상태가 아니다** — 그 까닭은 [CalibrationEvidence] 머리말에 있다.
 * @param nowNs 나이를 재는 지금 시각. [evidence] 를 만든 것과 **같은
 *   단조 시계**여야 한다.
 * @param toneOk 1kHz 순음이 보이는가. **모르면 null** — 첫 FFT 전이다.
 *   모르는 것을 「보인다」로 읽지 않는다.
 */
fun calibrationGate(
    opened: Boolean,
    routeConfirmed: Boolean,
    evidence: CalibrationEvidence?,
    nowNs: Long,
    source: CalibrationSource,
    toneOk: Boolean?,
): CalibrationGate = when {
    !opened -> CalibrationGate.Reject("먼저 측정을 시작해야 보정할 수 있습니다.")

    !routeConfirmed -> CalibrationGate.Reject(
        "어느 마이크로 열렸는지 아직 확인되지 않았습니다. 확인된 뒤에 " +
            "보정하십시오 — 지금 저장하면 다른 기기의 보정값으로 남을 수 있습니다.",
    )

    evidence == null ->
        CalibrationGate.Reject("아직 읽은 값이 없습니다. 잠시 뒤에 다시 누르십시오.")

    // **묵은 값으로 맞추지 않는다**(독립 재검증 UISR-01). 세션이 살아
    // 있어도 콜백이 멈추면 마지막 값이 그대로 남는다. 교정기를 끼우고
    // 레벨을 바꾼 직후라면 **바꾸기 전 값으로 덮어쓰게 된다.**
    !evidence.fresh(nowNs) -> CalibrationGate.Reject(
        "지금 들어오는 소리가 없습니다(마지막 값이 %.1f초 전). ".format(
            evidence.ageMs(nowNs) / 1000.0,
        ) + "연결을 확인하고 소리가 다시 들어온 뒤에 보정하십시오.",
    )

    // **잘린 소리로 맞추지 않는다.** 풀스케일에 닿은 순간의 실제 음압은
    // 읽은 값보다 높고, 얼마나 높은지는 알 길이 없다.
    //
    // **「그 뒤로 조용했는가」를 묻는다**(독립 재검증 UISR-02). 예전에는
    // 세션 누적 플래그를 봤는데, 그러면 시작할 때 충격음 한 번으로
    // **입력을 낮춰도 영영 풀리지 않았다** — 안내는 낮추라고 하는데.
    !evidence.cleanWindow(nowNs) -> CalibrationGate.Reject(
        "소리가 너무 커서 파형이 잘렸습니다. 입력 볼륨을 낮추고 " +
            "%.0f초쯤 기다린 뒤에 보정하십시오 — 잘린 값으로 맞추면 그만큼 틀립니다."
                .format(CalibrationEvidence.CLEAN_WINDOW_MS / 1000.0),
    )

    // **바늘이 자리를 잡기 전에 맞추지 않는다.** 시작 직후 값은 0 에서
    // 올라오는 중이라 실제보다 낮다.
    !evidence.settled ->
        CalibrationGate.Reject("값이 아직 자리를 잡는 중입니다. 잠시 뒤에 다시 누르십시오.")

    // **교정기 단추만 순음을 본다.** 기준 소음계에 맞추는 쪽은 사람이
    // 다른 계기의 숫자를 보고 적는 것이라 순음이 있을 까닭이 없다.
    source != CalibrationSource.Calibrator -> CalibrationGate.Save

    toneOk == true -> CalibrationGate.Save

    else -> CalibrationGate.AskConfirmation
}
