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

/**
 * **파일에 적힐 환경이 잰 경로와 같은가**(독립 재검토 CARF-03).
 *
 * 저장은 인자를 둘 받는다 — 검사에 쓰는 지금 신원(`now`)과 파일에 적히는
 * 환경(`environment`). 그런데 **검사는 앞엣것으로, 기록은 뒤엣것으로**
 * 하고 있어 둘이 같다는 보장이 없었다.
 *
 * 부르는 쪽도 같은 순간에서 오지 않는다: 환경은 Compose 가 그릴 때 모아
 * 둔 `capture.opened` 에서, 신원은 다리가 누를 때 다시 읽는 `vm.state`
 * 에서 온다. 입력을 바꾸고 다시 그리기 전이면 둘이 다르다.
 *
 * 검토자가 실제 저장까지 성공시켰다 — `measured=대상 · checked=대상 ·
 * saved=기준`. **올바른 신원을 검사했는데 산출물이 틀렸다.**
 *
 * 그래서 **저장 경계가 스스로 본다.** 화면의 사전 판정에 기대지 않는다.
 */
fun environmentMismatchKo(
    measured: CaptureIdentity?,
    environment: ProfileEnvironment,
): String? {
    if (measured == null) return "대상 마이크를 아직 재지 않았습니다. 4단계로 돌아가십시오."
    val what = when {
        environment.key() != measured.calKey -> "입력 경로"
        environment.deviceAddress != measured.routedAddress -> "마이크 자리"
        environment.sampleRate != measured.sampleRate -> "샘플레이트"
        else -> return null
    }
    return "파일에 적힐 환경이 잰 경로와 다릅니다($what). 저장하지 않았습니다 — " +
        "${measured.labelKo()} 로 되돌린 뒤 다시 하십시오. 그대로 저장하면 이 교정이 " +
        "잰 적 없는 경로의 것으로 기록됩니다."
}

// ----------------------------------------------------------------------
// 증거의 일생 (CAR-02)
// ----------------------------------------------------------------------

/**
 * **다시 재기 시작했다 — 이전에 내놓은 판정을 함께 버린다**
 * (독립 재검토 CARF-04).
 *
 * `CalibrationSession.discard()` 는 **집계기 안의 장**만 버린다. 그런데
 * 한 번 세 단계를 마치면 `session`·`quality`·`outcome`·`levelTransfer`
 * 가 이미 화면으로 나가 있고, 그것들이 저장 관문이 보는 값이다.
 *
 * 검토자가 잰 것 — 대상을 다시 재다가 마지막 장에서 채널을 바꿔 폐기한 뒤:
 *
 * ```
 * DISCARD_OLD_RESULT liveFrames=0 publishedFrames=120
 * sameOutcome=true transferAllowed=true
 * ```
 *
 * 집계기는 0장인데 화면과 저장 관문은 **옛 120장의 Pass** 를 본다. 옛
 * 측정이 틀렸다는 말이 아니다 — **폐기한 지금 시도와 남겨 둔 지난 결과를
 * 구분하지 않는 것**이 문제다. 그 상태에서 사람은 새 결과인 줄 알고
 * 옛 교정을 저장한다.
 *
 * 장과 판정을 **같은 상태 전이로** 다룬다.
 */
fun WizardState.discardingStep(step: MeasureStep): WizardState {
    // 세 단계가 다 있어야 나오는 값들이다. 한 단계를 버렸으니 더는 없다.
    val cleared = copy(
        session = null,
        quality = null,
        outcome = null,
        levelTransfer = null,
        levelTransferBlockKo = null,
    )
    // **버린 단계의 이름표만** 지운다. 장이 없는데 신원만 남으면 다음
    // 관문이 「이미 쟀다」로 읽는다. 남은 단계까지 지우면 멀쩡히 잰 것을
    // 다시 재게 한다.
    return when (step) {
        MeasureStep.Target -> cleared.copy(
            targetIdentity = null,
            targetDeviceKey = null,
            targetCalKey = null,
            targetEvidenceKey = null,
        )

        MeasureStep.ReferenceBefore -> cleared.copy(
            referenceIdentity = null,
            referenceDeviceKey = null,
            referenceCalKey = null,
            referenceEvidenceKey = null,
            referenceOffsetDb = null,
        )

        // 마지막 기준에는 제 이름표가 없다 — 첫 기준의 것을 쓴다.
        // 그 장만 사라지고 나머지는 그대로다.
        MeasureStep.ReferenceAfter -> cleared
    }
}

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
