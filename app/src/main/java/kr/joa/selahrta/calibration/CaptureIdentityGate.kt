package kr.joa.selahrta.calibration

import kr.joa.selahrta.dsp.MeasureStep

/**
 * **수집 신원을 지키는 관문들**(독립 재검토 CAR-01·CAR-02).
 *
 * ## 왜 VM 밖에 있는가
 *
 * 이 판단들이 `CalibrationWizardViewModel` 안에 있었다. 그 클래스는
 * `AndroidViewModel` 이라 기기 없이는 한 줄도 돌려 볼 수 없고, 그래서
 * **관문이 실제로 막는지 확인한 적이 없었다.** `WizardRunner` 를 따로
 * 둔 것과 같은 까닭으로 여기로 옮긴다.
 *
 * 검토자가 짚은 셋이 전부 이 파일의 함수 하나씩이다:
 *
 * - 전후 기준이 **같은 경로**인가 → [measureGateKo]
 * - 마지막 장과 이름표가 **같은 입력**인가 → [stampGateKo]
 * - 저장할 때 **잰 자리로 돌아왔는가** → [routeMismatchKo]
 */

/**
 * 이 단계를 지금 입력으로 재도 되는가. 되면 null, 안 되면 까닭.
 *
 * **마지막 기준은 앞의 기준과 경로 전체가 같아야 한다**(재현 A). 예전에는
 * 기기 열쇠만 봤는데, 그러면 같은 인터페이스의 **다른 채널**로 되돌려도
 * 통과했다 — 반복성 검사는 같은 소리를 들었으므로 Pass 가 나지만, 그것이
 * 같은 마이크·같은 감도라는 증거는 아니다.
 *
 * 세대는 묻지 않는다([CaptureIdentity.sameRouteAs]) — 기준과 대상을
 * 오가려면 기기를 다시 여는 일이 정상이다.
 */
fun measureGateKo(
    step: MeasureStep,
    referenceIdentity: CaptureIdentity?,
    now: CaptureIdentity?,
): String? {
    if (now == null) return ROUTE_UNCONFIRMED_KO
    return when (step) {
        MeasureStep.ReferenceBefore -> null
        MeasureStep.Target -> when {
            referenceIdentity == null -> "기준을 먼저 재십시오."
            // 같은 기기면 같은 마이크를 두 번 잰 셈이다. 그러면 보정
            // 곡선이 평탄해져 「잘 맞았다」로 보인다.
            referenceIdentity.calKey.deviceKey == now.calKey.deviceKey -> SAME_DEVICE_KO
            else -> null
        }

        MeasureStep.ReferenceAfter -> when {
            referenceIdentity == null -> "기준을 먼저 재십시오."
            referenceIdentity.sameRouteAs(now) -> null
            else -> "앞의 기준과 다른 입력입니다(${referenceIdentity.labelKo()} → " +
                "${now.labelKo()}). 기준 마이크로 되돌린 뒤 하십시오."
        }
    }
}

/**
 * 다 재고 난 뒤 **이름표를 붙여도 되는가**. 되면 null, 안 되면 까닭.
 *
 * 마지막 장이 들어간 뒤 이름표를 붙이기 전에 입력이 바뀔 수 있다(재현 B).
 * 그때 「지금 열린 것」을 읽으면 **ch0 의 장에 ch1 의 이름표**가 붙는다 —
 * 새 관문도 잘못 붙인 이름표를 믿으므로 그대로 통과한다.
 *
 * 이름표를 고치는 길은 없다. **장을 버리는 것**이 유일한 답이다.
 */
fun stampGateKo(started: CaptureIdentity, ended: CaptureIdentity?): String? {
    if (ended == null) {
        return "재는 도중에 입력이 닫혔습니다. 이 측정은 버립니다 — 다시 재십시오."
    }
    if (ended.sameAs(started)) return null
    return "재는 도중에 입력이 바뀌었습니다(${started.labelKo()} → ${ended.labelKo()}). " +
        "이 측정은 버립니다 — 다시 재십시오."
}

/**
 * 잰 자리와 지금 자리가 다른가. 같으면 null, 다르면 막는 까닭.
 *
 * **모르면 「같다」가 아니다**(재현 C). 어느 한쪽의 자리를 모르면 막는다 —
 * 자리를 확인하지 못한 채로 저장하면 뒤에 붙는 검사가 **이미 잘못 적힌
 * 이름표**를 보게 되어 차이를 영영 찾지 못한다.
 */
fun routeMismatchKo(
    measured: CaptureIdentity?,
    now: CaptureIdentity?,
    whatKo: String,
): String? {
    if (measured == null) return "대상 마이크를 아직 재지 않았습니다. 4단계로 돌아가십시오."
    if (now == null) return ROUTE_UNCONFIRMED_KO
    if (measured.calKey != now.calKey) {
        return "지금 열린 입력이 대상 경로가 아닙니다(${now.calKey.storageKey()}). " +
            "$whatKo 은 ${measured.calKey.storageKey()} 의 것이므로 그 경로로 되돌린 뒤 " +
            "저장해야 합니다 — 지금 저장하면 다른 경로의 보정이 덮어써집니다."
    }
    if (!measured.routeKnown || !now.routeKnown) {
        return "마이크가 실제로 어느 자리에 붙었는지 확인하지 못했습니다. " +
            "측정을 멈췄다 다시 시작해 자리를 확인한 뒤 저장하십시오 — " +
            "확인 없이 저장하면 잰 적 없는 자리에 이 값이 귀속됩니다."
    }
    if (measured.routedAddress != now.routedAddress) {
        return "잰 자리와 지금 자리가 다릅니다(${measured.labelKo()} 에서 쟀고 " +
            "지금은 ${now.labelKo()} 입니다). 같은 이름의 내장 마이크라도 " +
            "자리가 다르면 다른 마이크입니다 — 잰 자리로 되돌린 뒤 저장하십시오."
    }
    return null
}

// ----------------------------------------------------------------------
// 증거의 일생 (CAR-02)
// ----------------------------------------------------------------------

/**
 * **입력 점검을 시작한다 — 이 경로의 옛 증거를 먼저 버린다.**
 *
 * 예전에는 실패했을 때만 지웠다. 그런데 **취소**는 그 분기로 들어가지
 * 않아서, 「성공 → 배경이 나빠짐 → 재검사 → 취소」 뒤에 취소 전의 성공이
 * 그대로 승인 근거가 되었다. 검토자가 그 순서로 SNR 2dB 자료를 Pass
 * 시켰다.
 *
 * 다시 재겠다고 누른 순간 **옛 증거는 이 경로를 설명하지 않는다.**
 * 끝까지 마친 검사만 되살린다.
 */
fun WizardState.startingCheck(evidence: String): WizardState = forgetEvidence(evidence)

/**
 * 이 경로의 증거를 **통째로 버린다.**
 *
 * 배경과 DSP 는 **같은 시도에서 나온 짝**이어야 한다. 하나만 남기면
 * 다음 판정이 짝이 아닌 둘을 짝으로 읽는다.
 */
fun WizardState.forgetEvidence(evidence: String): WizardState = copy(
    noiseFloorDb = null,
    dsp = null,
    noiseFloorByKey = noiseFloorByKey - evidence,
    dspByKey = dspByKey - evidence,
)

/** 배경을 잰 결과를 그 경로의 이름으로 적는다. */
fun WizardState.withNoise(evidence: String, noise: List<Double>): WizardState = copy(
    noiseFloorDb = noise,
    noiseFloorByKey = noiseFloorByKey + (evidence to noise),
)

/** DSP 점검 결과를 그 경로의 이름으로 적는다. */
fun WizardState.withDsp(
    evidence: String,
    dsp: kr.joa.selahrta.dsp.DspProbeResult,
    clipped: Boolean,
): WizardState = copy(
    dsp = dsp,
    clipped = clipped,
    dspByKey = dspByKey + (evidence to dsp),
)

/**
 * 기준과 대상이 같은 입력일 때의 말.
 *
 * **막아야 하는 까닭**: 같은 마이크를 두 번 재면 두 곡선이 거의 같아
 * 보정이 평탄하게 나온다. 그 결과는 「아주 잘 맞았다」로 보이고, 품질
 * 판정도 통과한다 — 아무것도 재지 않은 것과 같은데 그렇게 보이지 않는다.
 */
const val SAME_DEVICE_KO: String =
    "기준과 같은 입력입니다. 대상 마이크(내장)로 바꾼 뒤 하십시오. " +
        "같은 마이크를 두 번 재면 보정이 평탄하게 나와 잘 맞은 것처럼 보입니다."

/**
 * 경로를 아직 확인하지 못했을 때의 말.
 *
 * **확인 전에는 수집을 시작하지 않는다.** `getRoutedDevice()` 는 녹음을
 * 시작하기 전에 null 을 준다는 규약이고(독립 검증 R01), 확인하지 못한
 * 채로 모은 자료는 **어느 마이크의 것인지 말할 수 없다.**
 */
const val ROUTE_UNCONFIRMED_KO: String =
    "어느 마이크로 붙었는지 아직 확인하지 못했습니다. " +
        "「측정」 화면에서 측정을 시작해 잠시 기다린 뒤 다시 하십시오."
