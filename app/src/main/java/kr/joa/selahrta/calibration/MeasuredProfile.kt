package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.audio.MicSeparation
import kr.joa.selahrta.audio.isSecondaryBuiltInAddress
import kr.joa.selahrta.audio.micPositionKo
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.QualityVerdict

/**
 * **잰 프로파일** — 기준 마이크와 견주어 만든 주파수 보정 하나
 * (S23 개별 교정 지시서 6장).
 *
 * ## 가져온 CAL 파일과 다르다
 *
 * [CurveStore] 가 다루는 것은 **가져온** 곡선이다 — EMM-6 처럼 만든 이가
 * 재어 준 그 마이크의 응답. 여기 있는 것은 **우리가 잰** 것이다: 이 폰의
 * 이 마이크를, 그 기준 마이크에 대고 재서 나온 차이.
 *
 * 둘을 한 자리에 섞으면 나중에 어느 쪽인지 알 수 없다. 그래서 따로 둔다.
 */

/**
 * 프로파일 저장 형식의 판(版).
 *
 * **필드가 바뀌면 올린다.** 더 높은 판으로 적힌 것을 읽으면 모르는 필드를
 * 조용히 버리게 되므로 읽지 않고 막는다.
 */
const val PROFILE_SCHEMA_VERSION: Int = 1

/**
 * 보정 **계산**의 판.
 *
 * 정규화·평활·상한의 뜻이 바뀌면 올린다. 판이 낮은 프로파일은 걸 수는
 * 있지만(저장된 것은 여전히 「주파수별 보정 dB」다) 어떻게 나온 값인지가
 * 다르므로 재검증을 권한다.
 */
const val CALIBRATION_ALGORITHM_VERSION: Int = 1

/**
 * 프로파일을 만들 때의 **경로**. 앱을 다시 켤 때마다 이것을 다시 맞춰 본다.
 *
 * 지시서 6장: 「앱 재시작 및 녹음 시작 시 실제 활성 마이크·채널·경로·
 * sample rate 를 다시 대조한다. 불일치하면 자동 적용하지 않고 이유를
 * 표시한다.」
 */
data class ProfileEnvironment(
    /** [kr.joa.selahrta.audio.InputDeviceInfo.stableKey] — 종류·이름·주소. */
    val deviceKey: String,
    /** `bottom`·`back` 같은 주소. 내장 마이크를 가르는 **유일한** 단서다. */
    val deviceAddress: String,
    val micKind: MicKind,
    val audioSource: CaptureSource,
    val sampleRate: Int,
    val channelCount: Int,
    val channelIndex: Int,
    val manufacturer: String = "",
    val model: String = "",
    /** `Build.DISPLAY` 같은 것. OS 가 바뀌면 응답도 바뀔 수 있다. */
    val osBuild: String = "",
) {
    /**
     * 기기·경로·채널의 신원. **[CalibrationKey] 의 규칙을 그대로 쓴다** —
     * 여기서 다시 만들면 두 규칙이 갈라져, 한쪽만 고쳤을 때 조용히 어긋난다.
     */
    fun key() = CalibrationKey(
        deviceKey = deviceKey,
        source = audioSource,
        channelIndex = channelIndex.takeIf { channelCount > 1 },
    )

    /**
     * 하단이 아닌 두 번째 내장 마이크인가.
     *
     * **「후면」이라고 부르지 않는다.** 안드로이드가 back 이라 알려도
     * 실제 위치는 다를 수 있다 — S23 Ultra 는 상단이다(2026-09-23 확인).
     */
    val isSecondaryBuiltIn: Boolean get() = isSecondaryBuiltInAddress(deviceAddress)
}

/** 프로파일에 남기는 품질 요약(지시서 6장 「품질 지표」). */
data class ProfileQuality(
    val verdict: QualityVerdict,
    /**
     * 재는 동안 레벨이 얼마나 흔들렸는가 — **표준편차**(dB).
     *
     * 2026-09-24 이전 프로파일은 이 자리에 min−max 를 담고 있었다.
     * 다른 양이라 그대로 읽으면 안 된다 — 코덱이 **새 열쇠**로 읽고,
     * 옛 파일에서는 NaN(모름)이 된다.
     */
    val repeatStdevDb: Double,
    /** 앞뒤 기준 측정의 차이(dB). 부호를 살린다. */
    val referenceDriftDb: Double,
    /** 보정에 쓸 수 있었던 대역의 비율. */
    val usableBandRatio: Double,
    val worstSnrDb: Double?,
    /** 잔여 DSP 를 **신호로** 확인했는가(지시서 2장). 설정값만 본 것은 false. */
    val dspVerifiedBySignal: Boolean,
)

/**
 * 기준으로 쓴 마이크와 그 CAL 파일(지시서 6장 「EMM-6 CAL 파일 식별자/해시」).
 *
 * **해시를 남기는 까닭**: 파일 이름은 같은데 내용이 바뀌어 있을 수 있다.
 * 그러면 그 프로파일이 무엇을 기준으로 만들어진 것인지 말할 수 없게 된다.
 */
data class ReferenceRecord(
    /** CAL 파일 이름. 사람이 알아보는 값이다. */
    val calFileName: String?,
    /** 그 파일 내용의 SHA-256. 없으면 「기록하지 않았다」다. */
    val calSha256: String?,
    /** UMC404HD 의 몇 번 입력에 꽂혀 있었는가(0부터). 모르면 null. */
    val inputChannelIndex: Int?,
    /** 기준 마이크 이름. 사람이 적는다 — 짓어내지 않는다. */
    val micName: String = "",
)

/**
 * 잰 프로파일 하나.
 *
 * 곡선 넷([kr.joa.selahrta.dsp.CalibrationOutcome])은 부피가 커서 여기
 * 담지 않고 파일로 둔다. 이 기록은 **그 파일이 무엇인지 말해 주는 쪽**이다.
 */
data class MeasuredProfile(
    val id: String,
    val schemaVersion: Int = PROFILE_SCHEMA_VERSION,
    val algorithmVersion: Int = CALIBRATION_ALGORITHM_VERSION,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val environment: ProfileEnvironment,
    /** 만들 때의 물리 마이크 판정. 이름을 어떻게 붙일지가 여기서 갈린다. */
    val separation: MicSeparation,
    val reference: ReferenceRecord,
    val quality: ProfileQuality,
    /** 정규화로 더한 값(dB). **음압 차이가 아니다**(지시서 5장). */
    val levelOffsetDb: Double,
    val normalizeBandLowHz: Double,
    val normalizeBandHighHz: Double,
    val smoothingFraction: Double,
    val maxCorrectionDb: Double,
    /** 보정을 믿을 수 있는 주파수 범위. 없으면 쓸 수 있는 대역이 없었다는 뜻. */
    val validFromHz: Double?,
    val validToHz: Double?,
    /** 곡선 넷이 들어 있는 파일 이름(앱 전용 폴더 기준). */
    val curvesFileName: String,
    /**
     * 잴 때 케이스를 벗겼는가. **사람이 말한 것이고 확인할 길이 없다.**
     *
     * 후면 마이크는 케이스에 막혀 응답이 크게 달라진다. 안드로이드에는
     * 케이스 상태를 묻는 길이 없으므로, 적어 두고 **걸 때마다 확인을
     * 권하는 것**이 우리가 할 수 있는 전부다.
     */
    val caseRemoved: Boolean? = null,
    /** 지금 걸려 있는가. 꺼도 지우지 않는다. */
    val enabled: Boolean = true,
) {
    /**
     * 화면에 적을 이름.
     *
     * **물리 위치를 붙이는 것은 [MicSeparation.Separable] 일 때뿐이다**
     * (지시서 1장: 「나머지는 확인된 경로로만 명명」). 갈라진다는 확인 없이
     * 「후면」이라 적으면, 그 이름을 믿고 케이스를 벗기고 다시 재는 사람이
     * 생긴다.
     */
    val labelKo: String
        get() {
            val position = micPositionKo(environment.deviceAddress)
            return when {
                separation == MicSeparation.Separable && position != null ->
                    "${environment.micKind.labelKo} ($position)"
                environment.channelCount > 1 ->
                    "${environment.micKind.labelKo} · 입력 ${environment.channelIndex + 1}"
                else -> "${environment.micKind.labelKo} · ${environment.audioSource.labelKo}"
            }
        }
}

// ----------------------------------------------------------------------
// 다시 대조하기 (지시서 6장)
// ----------------------------------------------------------------------

/** 지금 이 프로파일을 걸어도 되는가. */
enum class ProfileApply {
    /** 그대로 건다. */
    Apply,

    /** 걸되 **재검증을 권한다.** 확인할 수 없는 것이 남아 있다. */
    ApplyWithWarning,

    /** 걸지 않는다. 다른 마이크·다른 경로의 보정이 될 수 있다. */
    Block,
}

/**
 * 판정과 **그렇게 판정한 까닭 전부**.
 *
 * 막는 까닭이 여럿이면 다 적는다 — 하나만 보여 주면 그것만 고치고 다시
 * 눌렀다가 또 막힌다.
 */
data class ProfileMatch(
    val apply: ProfileApply,
    val reasonsKo: List<String>,
) {
    val mayAutoApply: Boolean get() = apply == ProfileApply.Apply
    val blocked: Boolean get() = apply == ProfileApply.Block
}

/**
 * **저장할 때의 경로와 지금의 경로를 맞춰 본다**(지시서 6장).
 *
 * ## 왜 이것이 필요한가
 *
 * 보정 곡선은 「이 마이크가 이 경로로 열렸을 때」의 차이다. 다른 마이크나
 * 다른 경로에 걸면 **고치는 게 아니라 틀어 놓는다.** 그런데 경로는 사람이
 * 모르는 새 바뀐다 — USB 를 뺐다 꽂고, OS 가 올라가고, 샘플레이트가
 * 달라진다. 그래서 걸기 전에 매번 맞춰 본다.
 *
 * ## 꺼 둔 것도 여기서 본다
 *
 * 「경로가 같은가」와 「사람이 켜 두었는가」를 따로 두면, 부르는 쪽이 한쪽을
 * 잊었을 때 꺼 둔 보정이 조용히 걸린다. 한 함수가 **「지금 걸어도 되는가」**
 * 하나만 답하게 한다.
 *
 * @param now 지금 실제로 열려 있는 경로. 요청값이 아니라 **열린** 값이다.
 */
fun judgeProfileApply(
    profile: MeasuredProfile,
    now: ProfileEnvironment,
): ProfileMatch {
    val block = mutableListOf<String>()
    val warn = mutableListOf<String>()

    if (profile.schemaVersion > PROFILE_SCHEMA_VERSION) {
        block += "더 새 판(v${profile.schemaVersion})에서 만든 프로파일입니다. " +
            "앱을 올린 뒤 다시 시도하십시오."
    }

    val was = profile.environment
    // **[CalibrationKey] 로 견준다.** 기기·경로·채널의 신원 규칙이 거기
    // 하나뿐이어야 두 곳이 갈라지지 않는다.
    if (was.key() != now.key()) {
        when {
            was.deviceKey != now.deviceKey ->
                block += "다른 마이크입니다. 저장할 때는 「${was.labelOfDevice()}」, " +
                    "지금은 「${now.labelOfDevice()}」입니다."
            was.audioSource != now.audioSource ->
                block += "입력 경로가 다릅니다. 저장할 때는 ${was.audioSource.labelKo}, " +
                    "지금은 ${now.audioSource.labelKo}입니다."
            else ->
                block += "다른 입력 채널입니다. 저장할 때는 ${was.channelIndex + 1}번, " +
                    "지금은 ${now.channelIndex + 1}번입니다."
        }
    }

    // 곡선 자체는 샘플레이트에 매이지 않지만, **마이크가 그 레이트에서
    // 같은 응답을 낸다는 보장이 없다** — 안드로이드가 레이트마다 다른
    // 내부 경로를 쓰기도 하고, 나이퀴스트가 달라지면 잰 적 없는 대역이
    // 생긴다. 지시서가 sample rate 를 대조 항목에 넣은 자리다.
    if (was.sampleRate != now.sampleRate) {
        block += "샘플레이트가 다릅니다. 저장할 때는 ${was.sampleRate}Hz, " +
            "지금은 ${now.sampleRate}Hz입니다."
    }

    // **실제로 열린 마이크가 바뀌었는가**(독립 검토 CA-04).
    //
    // 내장 마이크의 열쇠에는 주소가 없다(2026-09-23 결정). 하단에서 후면으로
    // 라우팅이 넘어가도 **같은 기기**로 보이므로, 저장된 보정이 다른 마이크에
    // 계속 걸렸다. 검토자가 `sameStableKey=true · mayAutoApply=true` 를
    // 재현했다.
    //
    // **열쇠를 도로 가르지는 않는다.** 그러면 이미 저장된 보정이 통째로
    // 떨어져 나가고, 2026-09-23 결정이 실측을 근거로 피한 것이 바로 그
    // 일이다. 대신 **자동 적용만 멈추고 사람에게 알린다** — 열쇠를 지우는
    // 것으로 불확실성을 해결하지 않되, 모르고 지나가지도 않게.
    // **빈 주소는 「모른다」이지 「같다」가 아니다**(독립 재검토 CA-R03).
    //
    // 처음에는 양쪽이 다 채워졌을 때만 봤는데, 실제로는 목록이 주소를
    // 지워 **언제나 비어 있었다** — 검사가 통째로 돌지 않았다. 이제
    // 열린 경로가 진짜 주소를 싣지만, 옛 프로파일에는 없다.
    //
    // 모르면 자동으로 걸지 않는다. 사람이 보고 정하면 된다.
    val wasAddr = was.deviceAddress
    val nowAddr = now.deviceAddress
    if (was.micKind == MicKind.BuiltIn && (wasAddr.isEmpty() || nowAddr.isEmpty())) {
        warn += "어느 마이크 자리에서 잰 것인지 확인할 수 없습니다. " +
            "내장 마이크는 하단·후면이 따로 있어 응답이 다릅니다 — " +
            "저절로 걸지 않습니다. 다시 교정하면 이 표시가 사라집니다."
    } else if (wasAddr.isNotEmpty() && nowAddr.isNotEmpty() && wasAddr != nowAddr) {
        warn += "잴 때와 다른 마이크 자리로 열렸습니다 — 저장할 때는 " +
            "「${micPositionKo(wasAddr) ?: wasAddr}」, 지금은 " +
            "「${micPositionKo(nowAddr) ?: nowAddr}」입니다. " +
            "응답이 다를 수 있어 저절로 걸지 않습니다."
    }

    if (!profile.enabled) {
        block += "이 프로파일을 꺼 두었습니다."
    }

    // 저장은 됐지만 자동으로 걸 만한 측정이 아니었다(지시서 4장).
    if (profile.quality.verdict != QualityVerdict.Pass) {
        block += "측정 품질이 기준에 못 미쳤습니다(${profile.quality.verdict.labelKo}). " +
            "다시 재고 저장하십시오."
    }

    if (profile.algorithmVersion < CALIBRATION_ALGORITHM_VERSION) {
        warn += "옛 계산(v${profile.algorithmVersion})으로 만든 보정입니다. " +
            "다시 재기를 권합니다."
    }

    if (was.osBuild.isNotBlank() && now.osBuild.isNotBlank() && was.osBuild != now.osBuild) {
        warn += "OS 가 바뀌었습니다(${was.osBuild} → ${now.osBuild}). " +
            "마이크 경로가 달라졌을 수 있으니 재검증을 권합니다."
    }

    // **케이스는 물어볼 길이 없다.** 케이스에 가려지는 자리면 응답이
    // 크게 달라지므로, 확인할 수 없다는 사실을 걸 때마다 알린다.
    // 어느 자리인지는 안드로이드 주소로 알 수 없다(S23 은 back 이라
    // 알리지만 실제로는 상단이다) — 그래서 「가려지는 자리면」이라고만
    // 말하고 판단은 사람에게 맡긴다.
    if (now.isSecondaryBuiltIn) {
        warn += when (profile.caseRemoved) {
            true -> "케이스를 벗기고 잰 보정입니다. 지금도 벗겨져 있는지 확인하십시오."
            false -> "케이스를 씌운 채 잰 보정입니다. 지금도 같은 상태인지 확인하십시오."
            null -> "잴 때 케이스 상태를 적어 두지 않았습니다. 다시 재기를 권합니다."
        }
    }

    return when {
        block.isNotEmpty() -> ProfileMatch(ProfileApply.Block, block + warn)
        warn.isNotEmpty() -> ProfileMatch(ProfileApply.ApplyWithWarning, warn)
        else -> ProfileMatch(ProfileApply.Apply, emptyList())
    }
}

/** 어느 마이크인지 사람 말로. 까닭 문구에 쓴다. */
private fun ProfileEnvironment.labelOfDevice(): String =
    micPositionKo(deviceAddress)?.let { "${micKind.labelKo} ($it)" } ?: micKind.labelKo
