package kr.joa.selahrta.audio

/**
 * **두 내장 마이크가 공개 API 로 정말 갈라지는가**
 * (S23 Ultra 개별 자동교정 지시서 2장).
 *
 * 이 판정이 관문이다. 지시서가 못박았다:
 *
 * > 선호 장치 지정 성공, 방향 지정 성공, **목록에 마이크가 보인다는
 * > 사실만으로 물리 마이크 선택 성공으로 간주하지 않는다.**
 *
 * 그리고:
 *
 * > 물리 마이크 분리가 Android 공개 API 및 실기기에서 확인되지 않으면
 * > **두 개의 프로파일을 임의로 만들지 않는다.**
 *
 * 지금 이 앱은 갤럭시 S23 의 내장 마이크를 둘로 보고 있다 — 주소가
 * `bottom` 과 `back` 으로 다르고, `setPreferredDevice` 도 받아들여진다.
 * **그것은 증거가 아니다.** 실제로 어느 물리 마이크가 소리를 받고
 * 있는지는 녹음 중 `AudioRecord.getActiveMicrophones()` 가 말한다.
 */

/**
 * 녹음 중 활성 마이크 하나에 대해 안드로이드가 알려 주는 것.
 *
 * `MicrophoneInfo` 를 그대로 베낀 값 객체다. **안드로이드 형을 들고 오지
 * 않는다** — 그래야 판정을 기기 없이 돌려 볼 수 있다.
 */
data class ActiveMicInfo(
    val id: Int,
    val address: String,
    val description: String,
    /** `MicrophoneInfo.LOCATION_*`. 후보를 가릴 때만 쓴다(지시서 2.1). */
    val location: Int,
    /** `MicrophoneInfo.DIRECTIONALITY_*`. */
    val directionality: Int,
    val group: Int,
    val indexInTheGroup: Int,
    /** (채널 번호, 매핑 종류) 쌍들. 비어 있을 수 있다. */
    val channelMapping: List<Pair<Int, Int>> = emptyList(),
)

/** 후보 하나를 골라 실제로 녹음해 본 결과. */
data class MicProbeRound(
    /** 우리가 고른 입력 기기(그 기기의 [InputDeviceInfo.stableKey]). */
    val requestedKey: String,
    val requestedLabel: String,
    /** 실제로 열린 기기. 요청과 다를 수 있다. */
    val routedKey: String?,
    /** **녹음 중** 활성 마이크. 빈 목록이면 안드로이드가 말해 주지 않은 것이다. */
    val activeMics: List<ActiveMicInfo>,
    /** 열거나 읽는 데 실패했으면 까닭. */
    val failureKo: String? = null,
)

/**
 * 세 상태. 지시서 2.5 가 정한 그대로다.
 */
enum class MicSeparation {
    /** **독립 선택·식별 가능.** 이때만 후면·하단 개별 교정을 제공한다. */
    Separable,

    /**
     * **논리 입력만 구분 가능.**
     *
     * 기기 목록은 둘로 보이는데 실제 물리 마이크가 갈린다는 확인이 없다.
     * 이때는 입력 경로 프로파일로만 저장하고 **물리 위치 이름을 붙이지
     * 않는다.**
     */
    LogicalOnly,

    /** **구분 불가.** 개별 교정을 끄고 까닭을 설명한다. */
    Indistinguishable,
}

data class MicSeparationResult(
    val state: MicSeparation,
    /** 왜 그렇게 판정했는지. 화면과 보고서에 그대로 쓴다. */
    val reasonKo: String,
) {
    /** 후면·하단 같은 **물리 위치 이름**을 붙여도 되는가. */
    val mayNamePhysicalPosition: Boolean get() = state == MicSeparation.Separable
}

/**
 * 탐색 결과로 세 상태 중 하나를 고른다.
 *
 * **올라가는 쪽으로 기울이지 않는다.** 확신이 없으면 낮은 상태로 간다 —
 * 잘못 올리면 서로 다른 마이크의 보정이 섞이고, 그건 화면에 안 보인다.
 *
 * @param rounds 후보마다 한 번씩. 같은 후보를 여러 번 재려면
 *   [judgeSeparationAcrossRepeats] 를 쓴다.
 */
fun judgeSeparation(rounds: List<MicProbeRound>): MicSeparationResult {
    val usable = rounds.filter { it.failureKo == null }
    if (usable.size < 2) {
        return MicSeparationResult(
            MicSeparation.Indistinguishable,
            "열어서 재 볼 수 있는 내장 마이크 후보가 ${usable.size}개뿐입니다. " +
                "가릴 것이 없습니다.",
        )
    }

    // **요청한 기기로 열리지 않았으면 그 회차는 아무것도 증명하지 않는다.**
    val misrouted = usable.filter { it.routedKey != null && it.routedKey != it.requestedKey }
    if (misrouted.isNotEmpty()) {
        return MicSeparationResult(
            MicSeparation.LogicalOnly,
            "고른 기기와 다른 곳으로 열린 경우가 있습니다" +
                "(${misrouted.joinToString { it.requestedLabel }}). " +
                "고르는 대로 열린다고 말할 수 없습니다.",
        )
    }

    val noInfo = usable.filter { it.activeMics.isEmpty() }
    if (noInfo.isNotEmpty()) {
        return MicSeparationResult(
            MicSeparation.LogicalOnly,
            "녹음 중 활성 마이크를 알려 주지 않는 경우가 있습니다" +
                "(${noInfo.joinToString { it.requestedLabel }}). " +
                "기기 목록은 갈라지지만 어느 물리 마이크가 소리를 받았는지는 " +
                "확인할 수 없습니다.",
        )
    }

    // **둘 이상이 함께 활성이면 단일 물리 마이크가 아니다**(지시서 2.3:
    // 「한 녹음 스트림에서 두 마이크가 섞이거나 빔포밍·자동 전환되면
    // 단일 물리 마이크 프로파일로 표시하지 않는다」).
    val mixed = usable.filter { it.activeMics.size > 1 }
    if (mixed.isNotEmpty()) {
        return MicSeparationResult(
            MicSeparation.LogicalOnly,
            "한 번에 여러 마이크가 함께 활성인 경우가 있습니다" +
                "(${mixed.joinToString { "${it.requestedLabel}: ${it.activeMics.size}개" }}). " +
                "섞이거나 빔포밍이 걸린 것이라 단일 마이크의 응답이라고 할 수 없습니다.",
        )
    }

    val ids = usable.map { it.activeMics.single().id }
    if (ids.distinct().size < ids.size) {
        return MicSeparationResult(
            MicSeparation.LogicalOnly,
            "서로 다른 기기를 골랐는데 같은 물리 마이크가 활성입니다(id=$ids). " +
                "고를 수는 있지만 실제로 갈리지는 않습니다.",
        )
    }

    return MicSeparationResult(
        MicSeparation.Separable,
        "후보 ${usable.size}개가 각각 다른 물리 마이크로 열렸습니다" +
            "(${usable.joinToString { "${it.requestedLabel}→id=${it.activeMics.single().id}" }}).",
    )
}

/**
 * 여러 번 재서 **재현되는지**까지 본다(지시서 2.4).
 *
 * > 마이크 ID 또는 채널 매핑의 재현성이 부족하면 해당 프로파일 자동
 * > 적용을 차단한다.
 *
 * 한 번 갈렸다고 갈리는 것이 아니다. 같은 후보가 회차마다 다른 마이크로
 * 열리면 그건 자동 전환이지 선택이 아니다.
 *
 * @param repeats 회차마다 [judgeSeparation] 에 넣을 목록.
 */
fun judgeSeparationAcrossRepeats(repeats: List<List<MicProbeRound>>): MicSeparationResult {
    if (repeats.isEmpty()) {
        return MicSeparationResult(MicSeparation.Indistinguishable, "잰 것이 없습니다.")
    }

    val each = repeats.map { judgeSeparation(it) }
    // **가장 낮은 판정을 따른다.**
    val worst = each.minByOrNull { it.state.ordinal.let { o -> -o } }
        ?: return MicSeparationResult(MicSeparation.Indistinguishable, "잰 것이 없습니다.")
    if (worst.state != MicSeparation.Separable) return worst

    // 모든 회차가 Separable 이면, 같은 후보가 늘 같은 마이크였는지 본다.
    val byKey = mutableMapOf<String, MutableSet<Int>>()
    for (round in repeats.flatten()) {
        val mic = round.activeMics.singleOrNull() ?: continue
        byKey.getOrPut(round.requestedKey) { mutableSetOf() }.add(mic.id)
    }
    val unstable = byKey.filterValues { it.size > 1 }
    if (unstable.isNotEmpty()) {
        return MicSeparationResult(
            MicSeparation.LogicalOnly,
            "같은 기기를 골랐는데 회차마다 다른 마이크가 활성입니다" +
                "(${unstable.entries.joinToString { "${it.key}: ${it.value}" }}). " +
                "자동 전환이라 고정된 선택이라고 볼 수 없습니다.",
        )
    }

    return MicSeparationResult(
        MicSeparation.Separable,
        "${repeats.size}회 모두 같은 결과였습니다. " + each.first().reasonKo,
    )
}
