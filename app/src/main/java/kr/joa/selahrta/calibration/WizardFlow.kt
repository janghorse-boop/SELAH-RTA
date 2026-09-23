package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.MicSeparation
import kr.joa.selahrta.audio.MicSeparationResult
import kr.joa.selahrta.dsp.CalibrationOutcome
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
enum class WizardStep(val titleKo: String, val whatKo: String) {
    Equipment(
        "장비 · CAL · +48V",
        "기준 마이크를 꽂고 CAL 파일을 불러옵니다. 팬텀전원은 직접 켜셔야 합니다.",
    ),
    InputCheck(
        "입력 · DSP 점검",
        "소리를 틀어 두고 이득과 스펙트럼이 변하는지 봅니다.",
    ),
    MicJudgement(
        "물리 마이크 판정",
        "이 폰이 후면·하단을 따로 열어 주는지 기기에 물어봅니다.",
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

/** 불러온 CAL 파일의 신원. 프로파일에 그대로 남는다(지시서 6장). */
data class CalInfo(
    val fileName: String,
    val sha256: String,
    val lowestHz: Double,
    val highestHz: Double,
    val pointCount: Int,
)

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
     * 팬텀전원을 켰다고 **사람이 말했는가.**
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
    /** AGC/NS/AEC 를 **API 로** 껐는가. 설정값일 뿐이라 이것만으로는 모자라다. */
    val effectsAllClear: Boolean? = null,
    val clipped: Boolean = false,

    // 3단계
    val separation: MicSeparationResult? = null,
    /** 후면을 잴 때 케이스를 벗겼는가. **확인할 길이 없어 들은 대로 적는다.** */
    val caseRemoved: Boolean? = null,

    // 4단계
    val session: SessionResult? = null,

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
    if (state.cal == null) {
        block += "EMM-6 CAL 파일을 불러오지 않았습니다. 기준 없이 잰 값은 보정이 되지 않습니다."
    }
    if (!state.phantomAcknowledged) {
        block += "팬텀전원(+48V)을 켰는지 확인해 주십시오. 앱은 이 상태를 알 수 없습니다."
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
    "이 폰에서는 후면·하단을 따로 열 수 없어 마이크별 교정을 만들지 않습니다. " +
        "둘을 임의로 나누면 서로 다른 마이크의 보정이 섞이고, 그건 화면에 보이지 않습니다."

const val LOGICAL_ONLY_KO: String =
    "입력 경로는 갈리지만 실제 물리 마이크가 갈린다는 확인은 없습니다. " +
        "「후면」·「하단」 같은 위치 이름을 붙이지 않고 경로 프로파일로 저장합니다."

const val CASE_UNKNOWN_KO: String =
    "케이스를 벗기고 쟀는지 적어 두지 않았습니다. 후면 마이크는 케이스에 막혀 " +
        "응답이 크게 달라지므로, 나중에 걸 때 같은 상태인지 알 수 없게 됩니다."
