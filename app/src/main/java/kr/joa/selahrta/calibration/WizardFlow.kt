package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.MicSeparation
import kr.joa.selahrta.audio.MicSeparationResult
import kr.joa.selahrta.dsp.LevelTransfer
import kr.joa.selahrta.dsp.CalibrationOutcome
import kr.joa.selahrta.dsp.CurveReading
import kr.joa.selahrta.dsp.ReadingDecision
import kr.joa.selahrta.dsp.ReadingStakes
import kr.joa.selahrta.dsp.SignEvidence
import kr.joa.selahrta.dsp.decideReading
import kr.joa.selahrta.dsp.DspProbeResult
import kr.joa.selahrta.dsp.DspVerdict
import kr.joa.selahrta.dsp.QualityReport
import kr.joa.selahrta.dsp.SessionResult
import kr.joa.selahrta.dsp.judgeCalibration

/**
 * 교정 마법사의 **순서와 관문**(지시서 7장 두 번째 항목).
 *
 * > 기준 장비 연결·CAL·+48V 안내 → DSP 및 신호 점검 → 대상 마이크 확인
 * > → 기준/대상/기준 측정 → 곡선 검토 → 저장
 *
 * ## 왜 화면과 따로 두는가
 *
 * 여기 담긴 것은 **어느 단계에서 무엇이 갖춰져야 다음으로 가는가**이고,
 * 그 규칙이 틀리면 「재지도 않고 저장된 프로파일」이 생긴다. 화면 안에
 * 두면 기기를 들고 눌러 봐야만 확인되고, 그렇게 확인한 것은 다음에
 * 누가 고칠 때 또 눌러 봐야 한다.
 *
 * 그래서 이 파일에는 안드로이드가 하나도 없다. 전부 시험으로 확인한다.
 *
 * ## 막는 것과 알리는 것을 가른다
 *
 * 지시서는 두 가지를 서로 다르게 다룬다:
 *
 * - **막는다**: 물리 마이크가 구분되지 않으면 개별 교정을 **비활성화**
 *   한다(2.5). 재지 않은 단계를 건너뛸 수도 없다.
 * - **알리고 내린다**: 잔여 DSP 우회가 검증되지 않으면 「제한적 정확도」로
 *   명시하고 **자동 적용만** 금지한다(3장). 측정 자체는 해도 된다 —
 *   보정 전후를 눈으로 견주는 것만으로도 쓸모가 있다.
 *
 * 둘을 섞으면 어느 쪽으로든 나쁘다. 다 막으면 아무것도 못 하고, 다
 * 통과시키면 못 믿을 보정이 조용히 걸린다.
 */
/**
 * 기준 마이크를 **어떻게 물렸는가.**
 *
 * ## 왜 묻는가
 *
 * 가격·구독 전략 문서(2026-09-24) 3장이 못박은 원칙이 있다 —
 * **iMM-6C 와 EMM-6 은 둘 다 개별 CAL 을 적용하는 정식 측정용
 * 마이크다.** 「간편형/정밀형」처럼 품질 등급으로 나누지 않는다.
 * 다른 것은 **연결 방식**뿐이다.
 *
 * 그런데 마법사 1단계는 EMM-6 만 가정하고 있었다. 팬텀전원(+48V)
 * 확인을 **모두에게** 요구해서, USB 직결 마이크를 쓰는 사람은 자기
 * 장비에 **없는 스위치**를 켰다고 체크해야만 다음으로 갈 수 있었다.
 * 거짓을 체크하게 만드는 관문은 관문이 아니다.
 *
 * ## 무엇이 달라지는가
 *
 * | 연결 | 팬텀전원 | 게인 노브 |
 * |---|---|---|
 * | [XlrInterface] | 인터페이스에서 켠다 | 있다 |
 * | [UsbDirect] | 없다 | 대개 없다 |
 *
 * 측정 품질의 우열이 아니다. 확인할 것이 다를 뿐이다.
 */
enum class ReferenceHookup(val labelKo: String, val helpKo: String) {
    XlrInterface(
        "XLR 마이크 + 오디오 인터페이스",
        "EMM-6 처럼 XLR 로 나가는 측정 마이크입니다. 인터페이스에서 " +
            "팬텀전원(+48V)을 켜고 입력 게인을 맞춥니다.",
    ),
    UsbDirect(
        "USB 직결 측정 마이크",
        "iMM-6C 처럼 폰에 바로 꽂는 측정 마이크입니다. 팬텀전원도 게인 " +
            "노브도 없으므로 확인할 것이 없습니다.",
    ),
    ;

    /** 이 연결에 팬텀전원 확인이 필요한가. */
    val needsPhantom: Boolean get() = this == XlrInterface
}

enum class WizardStep(val titleKo: String, val whatKo: String) {
    Equipment(
        "장비 · CAL · 연결 확인",
        "기준 마이크를 꽂고 CAL 파일을 불러옵니다. 연결 방식에 따라 확인할 것이 다릅니다.",
    ),
    InputCheck(
        "입력 · DSP 점검",
        "소리를 틀어 두고 이득과 스펙트럼이 변하는지 봅니다.",
    ),
    MicJudgement(
        "물리 마이크 판정",
        "이 폰이 내장 마이크를 하나하나 따로 열어 주는지 기기에 물어봅니다.",
    ),
    Measure(
        "기준 → 대상 → 기준",
        "핑크 노이즈를 세 번에 나눠 잽니다. 기준을 앞뒤로 재서 그사이 변화를 봅니다.",
    ),
    Review(
        "레벨 · 응답 · 보정치 비교",
        "보정 전후를 견주고, 믿을 수 없는 구간을 확인합니다.",
    ),
    Save(
        "저장",
        "판정을 통과해야 저장됩니다. 자동 적용은 그중에서도 통과한 것만 됩니다.",
    ),
    ;

    val number: Int get() = ordinal + 1
}

/**
 * 불러온 CAL 파일의 신원. 프로파일에 그대로 남는다(지시서 6장).
 *
 * **둘째 열을 무엇으로 읽었는지도 함께 남긴다**(독립 검토 R04). 그 값이
 * 없으면 나중에 「이 프로파일이 어느 규약으로 만들어졌는가」를 알 길이
 * 없고, 부호가 뒤집힌 채 굳은 것을 되짚지 못한다.
 */
data class CalInfo(
    val fileName: String,
    val sha256: String,
    val lowestHz: Double,
    val highestHz: Double,
    val pointCount: Int,
    /** 머리글에서 찾은 단서. 정해진 값이 아니라 단서다. */
    val evidence: SignEvidence = SignEvidence.Unknown,
    /**
     * 머리글이 **열 이름을 선언했는가**(독립 재검토 CA-R05).
     *
     * 설명문에서 낱말을 찾은 것만으로는 둘째 열이 무엇인지 알 수 없다.
     * 기준 CAL 은 선언이 있을 때만 저절로 정해진다.
     */
    val columnDeclared: Boolean = false,
    val reading: CurveReading = CurveReading.Response,
    /**
     * **사람이 화면에서 골랐는가.**
     *
     * 관례로 정해진 것과 다르다. 교정의 기준은 관례로 때우지 않는다 —
     * 틀리면 이 기준으로 만든 프로파일이 전부 같은 방향으로 틀어진다.
     */
    val readingChosenByPerson: Boolean = false,
) {
    private val decision: ReadingDecision
        get() = decideReading(evidence, ReadingStakes.ReferenceForCalibration, columnDeclared)

    /** 읽는 법이 정해졌는가. 사람이 골랐거나, 머리글이 분명하거나. */
    val readingSettled: Boolean get() = readingChosenByPerson || decision.settled

    /** 안 정해졌을 때 화면에 적을 까닭. */
    val readingQuestionKo: String get() = decision.whyKo
}

/**
 * 다음으로 가도 되는가.
 *
 * **[AllowedWithWarning] 를 따로 둔다.** 「되지만 이러이러하다」를
 * [Allowed] 로 뭉개면 사람이 그 사실을 볼 자리가 없어지고, [Blocked] 로
 * 뭉개면 할 수 있는 일을 못 하게 된다.
 */
sealed interface StepGate {
    val reasonsKo: List<String>

    data object Allowed : StepGate {
        override val reasonsKo: List<String> get() = emptyList()
    }

    data class AllowedWithWarning(override val reasonsKo: List<String>) : StepGate

    data class Blocked(override val reasonsKo: List<String>) : StepGate

    val passable: Boolean get() = this !is Blocked
}

/**
 * 마법사가 지금까지 모은 것.
 *
 * 배열 대신 [List] 를 쓰는 까닭은 **같은지 견줄 수 있어야** 하기
 * 때문이다. `DoubleArray` 를 담으면 내용이 같아도 다른 것으로 보여,
 * 화면이 쓸데없이 다시 그려지고 시험도 어긋난다.
 */
data class WizardState(
    val step: WizardStep = WizardStep.Equipment,

    // 1단계
    val cal: CalInfo? = null,
    /**
     * 기준 마이크를 **어떻게 물렸는가.** 안 고르면 null.
     *
     * 묻는 까닭은 [ReferenceHookup] 참고 — 한마디로 팬텀전원은 XLR
     * 경로에만 있는 물건이라 모두에게 물으면 안 된다.
     */
    val referenceHookup: ReferenceHookup? = null,
    /**
     * 팬텀전원을 켰다고 **사람이 말했는가.**
     *
     * [ReferenceHookup.XlrInterface] 일 때만 본다.
     *
     * 앱은 팬텀전원 상태를 알 수 없다(지시서 3장: 「제어하거나 확정
     * 표시하지 않는다」). 켰는지 확인한 것이 아니라 **켰다고 들은 것**이고,
     * 화면 문구도 그렇게 적는다.
     */
    val phantomAcknowledged: Boolean = false,

    // 2단계
    /** 신호를 끄고 잰 대역별 잡음. 잰 적 없으면 null. */
    val noiseFloorDb: List<Double>? = null,
    val dsp: DspProbeResult? = null,
    /**
     * **기기마다 따로 둔 증거**(독립 검토 CA-03).
     *
     * 위 둘은 「방금 점검한 것」이라 화면이 보여 주는 값이다. 그런데 판정에
     * 쓸 때는 **기준의 것과 대상의 것이 달라야 한다** — 기준과 대상은
     * 일부러 다른 입력이고, ADC 마다 dBFS 잡음 바닥이 다르다.
     *
     * 예전에는 한 벌을 양쪽에 그대로 넘겼다. 검토자가 기준 SNR 40dB ·
     * 대상 SNR 2dB 인 자료로 재 보니 **31/31 밴드가 「쓸 수 있음」에 Pass**
     * 였다. 대상 배경을 제대로 넘기면 0/31 이고 곡선 생성이 거절된다 —
     * DSP 함수는 가려낼 줄 아는데 연결이 그것을 없앤 것이다.
     *
     * 없으면 **없는 채로 둔다.** 다른 입력의 값으로 채우지 않는다.
     *
     * ## 열쇠는 **경로 전체 + 분석 격자**다 (독립 재검토 CA-R02)
     *
     * 처음에는 기기 열쇠로만 갈랐다. 그랬더니 같은 USB 인터페이스의
     * **채널을 바꿔도** ch0 의 배경과 `verifiedBySignal=true` 가 그대로
     * 쓰였다 — 검토자가 ch0 의 배경(SNR 40dB)으로 31/31 Pass, 실제
     * ch1 의 배경(SNR 2dB)으로 0/31 이 나오는 것을 쟀다.
     *
     * [evidenceKey] 가 기기·source·채널·샘플레이트·FFT 길이를 모두 넣는다.
     * 하나라도 다르면 **다른 증거**다.
     */
    val noiseFloorByKey: Map<String, List<Double>> = emptyMap(),
    val dspByKey: Map<String, DspProbeResult> = emptyMap(),
    /** AGC/NS/AEC 를 **API 로** 껐는가. 설정값일 뿐이라 이것만으로는 모자라다. */
    val effectsAllClear: Boolean? = null,
    val clipped: Boolean = false,

    // 3단계
    val separation: MicSeparationResult? = null,
    /** 후면을 잴 때 케이스를 벗겼는가. **확인할 길이 없어 들은 대로 적는다.** */
    val caseRemoved: Boolean? = null,

    // 4단계
    val session: SessionResult? = null,
    /** 기준을 잰 입력의 열쇠. 마지막 기준 측정이 이것과 같아야 한다. */
    val referenceDeviceKey: String? = null,
    /** 대상을 잰 입력의 열쇠. 기준과 **달라야** 한다. */
    val targetDeviceKey: String? = null,
    /**
     * 대상을 잰 **경로 전체**의 열쇠(독립 재검토 CA-R01).
     *
     * 기기 열쇠만 들고 있었더니, 같은 USB 인터페이스의 **다른 채널**로
     * 옮겨도 관문이 통과했다 — Input 1 의 감도로 Input 2 의 교정을
     * 덮어쓸 수 있었다. 저장소 열쇠는 기기·source·채널을 모두 가른다
     * ([CalibrationKey])면서 관문만 기기를 봤다.
     *
     * 기준 쪽([referenceCalKey])은 이미 전체 열쇠였다. 대상만 빠져 있었다.
     */
    val targetCalKey: CalibrationKey? = null,
    /**
     * 대상을 **잴 때** 쓰던 증거의 이름(독립 재검토 CA-R02).
     *
     * 판정이 이 이름으로 배경·DSP 를 꺼낸다. 기기 열쇠로 꺼내면 그사이
     * 채널이 바뀌어도 옛 증거가 딸려 온다.
     */
    val targetEvidenceKey: String? = null,
    /** 기준을 잴 때 쓰던 증거의 이름. */
    val referenceEvidenceKey: String? = null,
    /**
     * 기준을 잰 **경로 전체**의 열쇠. 절대 레벨을 옮길 때 그 경로의
     * 보정값을 찾는 데 쓴다.
     *
     * 기기 열쇠만으로는 모자라다 — 같은 인터페이스라도 채널이 다르면
     * 다른 마이크이고 보정값도 다르다(USB 지시서 9.2).
     */
    val referenceCalKey: CalibrationKey? = null,
    /**
     * 기준을 잴 **그때** 그 경로에 저장돼 있던 보정값(dBFS → dB SPL).
     *
     * **그때의 값을 붙들어 둔다.** 나중에 저장소를 다시 보면, 그사이
     * 사람이 기준을 다시 보정했을 수도 있고 그러면 잰 것과 다른 값을
     * 옮기게 된다. null 이면 기준이 미보정이었다는 뜻이다.
     */
    val referenceOffsetDb: Double? = null,

    // 5~6단계
    /**
     * 기준에서 대상으로 옮길 **절대 레벨**. 못 옮기면 null.
     *
     * 조용히 걸지 않는다 — 음압을 바꾸는 일이라 사람이 보고 정한다
     * (간편 보정이 계산을 먼저 보여 주는 것과 같은 까닭).
     */
    val levelTransfer: LevelTransfer? = null,
    /** 못 옮기는 까닭. 옮길 수 있으면 null. */
    val levelTransferBlockKo: String? = null,

    // 5단계
    val outcome: CalibrationOutcome? = null,
    val quality: QualityReport? = null,
) {
    /** 잔여 DSP 를 **신호로** 확인했는가. 프로파일에 이 값이 남는다. */
    val dspVerifiedBySignal: Boolean get() = dsp?.verifiedBySignal == true
}

/**
 * 이 단계를 마치고 다음으로 가도 되는가.
 *
 * **[WizardState.step] 이 아니라 물어본 단계를 본다** — 뒤로 갔다가
 * 다시 올 때도 같은 답이 나와야 한다.
 */
fun gateFor(state: WizardState, step: WizardStep): StepGate = when (step) {
    WizardStep.Equipment -> equipmentGate(state)
    WizardStep.InputCheck -> inputCheckGate(state)
    WizardStep.MicJudgement -> micGate(state)
    WizardStep.Measure -> measureGate(state)
    WizardStep.Review -> reviewGate(state)
    WizardStep.Save -> saveGate(state)
}

private fun equipmentGate(state: WizardState): StepGate {
    val block = mutableListOf<String>()
    val cal = state.cal
    if (cal == null) {
        block += "기준 마이크의 CAL 파일을 불러오지 않았습니다. 기준 없이 잰 값은 보정이 되지 않습니다."
    } else if (!cal.readingSettled) {
        // **관례로 때우지 않는다**(독립 검토 R04). 이 파일이 교정의
        // 기준이라, 부호가 뒤집히면 이 기준으로 만든 프로파일이 전부
        // 같은 방향으로 틀어지고 나중에 봐도 알 수 없다.
        block += cal.readingQuestionKo
    }
    // **팬텀전원은 XLR 경로에만 있는 물건이다.** 연결 방식을 먼저 묻고,
    // XLR 일 때만 +48V 를 확인한다. 예전에는 모두에게 물어서, USB 직결
    // 측정 마이크(iMM-6C 등)를 쓰는 사람은 **자기 장비에 없는 스위치**를
    // 켰다고 체크해야만 다음으로 갈 수 있었다.
    when (state.referenceHookup) {
        null -> block += "기준 마이크를 어떻게 물렸는지 골라 주십시오. " +
            "연결 방식에 따라 확인할 것이 다릅니다."

        ReferenceHookup.XlrInterface ->
            if (!state.phantomAcknowledged) {
                block += "팬텀전원(+48V)을 켰는지 확인해 주십시오. 앱은 이 상태를 알 수 없습니다."
            }

        // USB 직결에는 팬텀전원이 없다. 확인할 것이 없으므로 막지 않는다.
        ReferenceHookup.UsbDirect -> Unit
    }
    return if (block.isEmpty()) StepGate.Allowed else StepGate.Blocked(block)
}

private fun inputCheckGate(state: WizardState): StepGate {
    val dsp = state.dsp
        ?: return StepGate.Blocked(listOf("아직 점검하지 않았습니다. 소리를 틀고 점검을 누르십시오."))

    if (state.clipped) {
        return StepGate.Blocked(
            listOf("신호가 찌그러졌습니다(클리핑). 입력 이득을 낮추고 다시 점검하십시오."),
        )
    }

    return when (dsp.verdict) {
        // **재지 못한 것은 막는다.** 통과가 아니다.
        DspVerdict.NotEnoughData -> StepGate.Blocked(dsp.reasonsKo)

        // **막지 않는다**(지시서 3장). 「제한적 정확도」로 적고 자동 적용만
        // 막는다 — 그 처리는 판정([judgeCalibration])이 한다.
        DspVerdict.Suspect -> StepGate.AllowedWithWarning(dsp.reasonsKo + LIMITED_ACCURACY_KO)

        DspVerdict.NoTimeVaryingFound -> {
            // API 로 못 끈 것이 있으면 알린다. 신호 검사가 통과했다고
            // 해서 **없는 것은 아니다** — 고정 처리는 안 보인다.
            if (state.effectsAllClear == false) {
                StepGate.AllowedWithWarning(listOf(EFFECTS_STILL_ON_KO))
            } else {
                StepGate.Allowed
            }
        }
    }
}

private fun micGate(state: WizardState): StepGate {
    val sep = state.separation
        ?: return StepGate.Blocked(listOf("아직 판정하지 않았습니다. 마이크 탐색을 돌리십시오."))

    return when (sep.state) {
        // 지시서 2.5: 구분 불가면 **개별 교정을 끈다.**
        MicSeparation.Indistinguishable -> StepGate.Blocked(
            listOf(sep.reasonKo, INDISTINGUISHABLE_KO),
        )

        // 지시서 2.5: 논리 입력만 구분되면 저장은 하되 **물리 위치 이름을
        // 붙이지 않는다.**
        MicSeparation.LogicalOnly -> StepGate.AllowedWithWarning(
            listOf(sep.reasonKo, LOGICAL_ONLY_KO),
        )

        MicSeparation.Separable -> StepGate.Allowed
    }
}

private fun measureGate(state: WizardState): StepGate {
    val s = state.session
        ?: return StepGate.Blocked(listOf("아직 세 번을 다 재지 않았습니다."))

    val warn = mutableListOf<String>()
    if (s.noStableFrames) {
        return StepGate.Blocked(
            listOf("쓸 만한 장이 한 장도 남지 않았습니다. 주변이 조용한지 보고 다시 재십시오."),
        )
    }
    if (s.referenceProof == null) {
        // 여기 오면 통로가 잘못 쓰인 것이다. 조용히 넘기지 않는다.
        return StepGate.Blocked(listOf("기준 측정에 CAL 이 걸리지 않았습니다. 다시 재야 합니다."))
    }
    if (state.caseRemoved == null && state.separation?.mayNamePhysicalPosition == true) {
        warn += CASE_UNKNOWN_KO
    }
    return if (warn.isEmpty()) StepGate.Allowed else StepGate.AllowedWithWarning(warn)
}

private fun reviewGate(state: WizardState): StepGate {
    if (state.outcome == null || state.quality == null) {
        return StepGate.Blocked(listOf("아직 보정 곡선을 만들지 않았습니다."))
    }
    return StepGate.Allowed
}

private fun saveGate(state: WizardState): StepGate {
    val outcome = state.outcome
    val quality = state.quality
    if (outcome == null || quality == null) {
        return StepGate.Blocked(listOf("잰 것이 없습니다."))
    }
    // **판정을 여기서 다시 부르지 않는다는 뜻이 아니다.** 저장 자체는
    // buildProfileForSave 가 판정을 품고 있고, 여기는 **가기 전에 미리**
    // 같은 답을 보여 주는 자리다. 둘이 어긋날 수 없도록 같은 함수를 쓴다.
    val judged = judgeCalibration(quality, outcome)
    return when {
        !judged.maySave -> StepGate.Blocked(judged.reasonsKo)
        !judged.mayAutoApply -> StepGate.AllowedWithWarning(judged.reasonsKo)
        else -> StepGate.Allowed
    }
}

/**
 * 다음 단계. 지금 단계를 못 지나면 null.
 *
 * **경고는 넘어간다.** 경고는 「보고도 가겠다」는 것이고, 막는 것과 다르다.
 */
fun nextStep(state: WizardState): WizardStep? {
    if (!gateFor(state, state.step).passable) return null
    return WizardStep.entries.getOrNull(state.step.ordinal + 1)
}

/** 뒤로. 첫 단계면 null. 뒤로 가는 데는 관문이 없다. */
fun previousStep(state: WizardState): WizardStep? =
    WizardStep.entries.getOrNull(state.step.ordinal - 1)

/**
 * 여기까지 온 길에 **막힌 단계가 있는가.**
 *
 * 뒤로 갔다가 값을 지우고 앞으로 건너뛰는 길을 막는다 — 예를 들어 CAL 을
 * 비운 채 저장까지 가면 기준 없는 프로파일이 생긴다.
 */
fun blockedBefore(state: WizardState): WizardStep? =
    WizardStep.entries
        .take(state.step.ordinal)
        .firstOrNull { !gateFor(state, it).passable }

// ----------------------------------------------------------------------
// 화면에 그대로 나가는 말들
// ----------------------------------------------------------------------

const val LIMITED_ACCURACY_KO: String =
    "이대로 진행하면 「제한적 정확도」로 저장되고 자동 적용은 되지 않습니다. " +
        "보정 전후를 눈으로 견주는 데는 쓸 수 있습니다."

const val EFFECTS_STILL_ON_KO: String =
    "신호 쪽에서는 변하는 처리를 찾지 못했지만, 안드로이드가 끄지 못했다고 " +
        "알린 처리가 남아 있습니다. 고정된 처리는 신호로 가려낼 수 없습니다."

const val INDISTINGUISHABLE_KO: String =
    "이 폰에서는 내장 마이크를 하나하나 따로 열 수 없어 마이크별 교정을 만들지 않습니다. " +
        "둘을 임의로 나누면 서로 다른 마이크의 보정이 섞이고, 그건 화면에 보이지 않습니다."

const val LOGICAL_ONLY_KO: String =
    "입력 경로는 갈리지만 실제 물리 마이크가 갈린다는 확인은 없습니다. " +
        "「후면」·「하단」 같은 위치 이름을 붙이지 않고 경로 프로파일로 저장합니다."

const val CASE_UNKNOWN_KO: String =
    "케이스를 벗기고 쟀는지 적어 두지 않았습니다. 후면 마이크는 케이스에 막혀 " +
        "응답이 크게 달라지므로, 나중에 걸 때 같은 상태인지 알 수 없게 됩니다."
