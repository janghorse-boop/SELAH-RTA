package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.domain.MicKind

/**
 * **개발자가 재서 앱에 실어 둔 기종별 기본 보정값.**
 *
 * 폰을 처음 켠 사람은 기준 소음계도 교정기도 없다. 그 사람에게 지금
 * 보이는 숫자는 [ASSUMED_FULL_SCALE_SPL] — **재서 얻은 값이 아니라
 * 짐작**이고 ±10dB 넘게 틀릴 수 있다. 같은 기종을 한 대라도 제대로
 * 재 두었다면, 그 값을 기본으로 걸어 주는 편이 짐작보다 낫다.
 *
 * ## 이것은 「보정」이 아니다
 *
 * 개발지시서 20장 ②는 **「Galaxy 모델 전체에 하나의 고정값 적용」을 하지
 * 말라**고 한다. 맞는 말이고, 이 표는 그 말을 어기지 않는다 — 어기는
 * 것은 값을 싣는 일이 아니라 **그 값을 그 기기의 보정이라고 말하는**
 * 일이기 때문이다. 그래서:
 *
 * - 화면에 「보정됨」이라 적지 않는다. [CalibrationState.FactoryDefault]
 *   는 따로 있고 글자도 「기종 기본값」이다.
 * - [ActiveCalibration.isReferenceOnly] 가 **참**이다. 녹음 파일에도
 *   참고용 구간으로 남고, 마법사는 이 값을 다른 마이크로 **옮기지
 *   않는다**([ActiveCalibration.appliedOffsetDb] 가 null 이다).
 * - 사용자가 제 값을 재면 **언제나 그쪽이 이긴다.**
 *
 * 즉 기본값은 **출발점**이지 도착점이 아니다.
 *
 * ## 지울 수 없다
 *
 * 이 표는 앱 안에 있고 [CalibrationStore] 에 없다. 「초기화」는 사용자가
 * 잰 값만 지우고, 지우고 나면 이 기본값으로 **되돌아온다.** 지우는 길을
 * 따로 두지 않았다 — 지워 봐야 짐작값으로 내려갈 뿐이라 얻을 것이 없다.
 *
 * ## 값을 실을 때 지키는 것
 *
 * **잰 적 없는 값을 적지 않는다.** 그럴듯한 숫자 하나가 그 기종을 쓰는
 * 모든 사람의 화면에 걸린다 — 사용자가 잘못 적은 값보다 나쁘다. 그래서
 * 항목마다 **언제·무엇으로·누가** 쟀는지를 함께 적게 했고, 빈칸이면
 * [FactoryCalibrationTable] 시험이 막는다.
 */
data class FactoryCalibration(
    /** `Build.MANUFACTURER`. 대소문자는 가리지 않는다. */
    val manufacturer: String,
    /** `Build.MODEL`. `SM-S918N` 처럼 **판매명이 아니라 모델 코드**다. */
    val model: String,
    /**
     * 어느 입력 경로로 잰 값인가(개발지시서 6장).
     *
     * 소스가 달라지면 제조사 DSP·AGC·잡음억제가 달리 걸리므로 **같은
     * 보정으로 보지 않는다.** UNPROCESSED 로 잰 값을 MIC 경로에 걸지
     * 않는다.
     */
    val source: CaptureSource,
    /**
     * 어느 자리에서 잰 값인가(`bottom`·`back`). **null 은 「자리를 가리지
     * 않는다」는 개발자의 선언**이다.
     *
     * 빈 문자열이 아니라 null 인 까닭이 있다. 이 저장소에서 「빈 주소」는
     * 늘 **「모른다」이지 「같다」가 아니다**(CAR-03). 앱이 모르는 것을
     * 같다고 우기는 길을 막아 두었는데, 여기서 빈 값을 「아무거나」로
     * 읽으면 그 보호가 통째로 풀린다.
     *
     * 그래서 「자리에 상관없다」는 **사람이 재 보고 그렇게 적을 때만**
     * 성립한다. 값이 적혀 있으면 지금 열린 자리가 **확인된 채로** 같아야
     * 걸린다.
     */
    val routeAddress: String?,
    /** dBFS 에 더하면 dB SPL 이 되는 값. */
    val offsetDb: Double,
    /** 언제 쟀는가(`2026-09-27`). */
    val measuredOn: String,
    /** 무엇을 기준으로 쟀는가. 한 줄로 적는다. */
    val referenceKo: String,
    /** 누가 쟀는가. 나중에 물어볼 사람이 있어야 한다. */
    val byKo: String,
    /** 덧붙일 말(측정 대수, 편차 따위). 없으면 빈 값. */
    val noteKo: String = "",
) {
    /** 사람에게 보일 한 줄. 「어디서 온 값인가」를 화면이 늘 말하게 한다. */
    fun originKo(): String = buildString {
        append(measuredOn).append(" · ").append(referenceKo).append(" · ").append(byKo)
        if (noteKo.isNotEmpty()) append(" · ").append(noteKo)
    }

    /** 자리까지 적힌 항목인가. 같은 기종에 둘 다 있으면 이쪽이 먼저다. */
    val routeSpecific: Boolean get() = routeAddress != null
}

/**
 * **앱에 실린 기종별 기본값.**
 *
 * 지금은 비어 있다. **비어 있는 것이 맞다** — 아직 기준 소음계와 맞춰
 * 잰 기종이 하나도 없기 때문이다. 재기 전에 그럴듯한 숫자를 채워 넣으면
 * 그 기종을 쓰는 모든 사람이 **틀린 값을 「기본값」이라는 이름으로**
 * 받는다. 화면은 그때도 멀쩡해 보인다.
 *
 * 값을 실으려면 그 기기에서 간편 보정을 끝낸 뒤, 보정 화면 아래
 * 「기종 기본값으로 실을 코드」를 그대로 여기에 붙인다.
 */
val FACTORY_CALIBRATIONS: List<FactoryCalibration> = listOf(
    // 예: FactoryCalibration(
    //     manufacturer = "samsung",
    //     model = "SM-S918N",
    //     source = CaptureSource.Unprocessed,
    //     routeAddress = "bottom",
    //     offsetDb = 122.6,
    //     measuredOn = "2026-09-27",
    //     referenceKo = "EMM-6 + UMC404HD (교정기 94dB 로 기준 맞춤)",
    //     byKo = "장훈",
    //     noteKo = "1대 측정",
    // ),
)

/**
 * 지금 열린 경로에 걸 **기종 기본값**을 찾는다. 없으면 null.
 *
 * ## 내장 마이크만이다
 *
 * USB 마이크는 폰의 일부가 아니다. 같은 기종을 쓴다고 같은 마이크를
 * 꽂았을 리 없고, 인터페이스의 게인 노브는 사람마다 다르다. 기종으로
 * 값을 정할 수 있는 것은 **폰에 붙어 있는 것**뿐이다.
 *
 * ## 자리가 적혀 있으면 확인된 채로 같아야 한다
 *
 * [FactoryCalibration.routeAddress] 참고. 자리를 모르는 상태
 * (`routeConfirmed == false` 또는 빈 주소)에서는 자리를 적은 항목이
 * 걸리지 않는다 — 그 상태로 걸면 `bottom` 의 감도를 `back` 에 거는
 * CAR-03 을 기본값이라는 이름으로 되살리는 꼴이다.
 *
 * ## 그럴듯하지 않은 값은 무시한다
 *
 * 표는 사람이 손으로 적는다. 오타 하나가 그 기종 전체에 걸리므로,
 * [PLAUSIBLE_OFFSET_RANGE] 밖이면 **걸지 않고 없는 것으로 친다.**
 * 시험이 먼저 막지만([FactoryCalibrationTable]) 그물은 두 겹이 낫다.
 */
fun findFactoryCalibration(
    build: DeviceBuildInfo,
    micKind: MicKind,
    source: CaptureSource,
    routedAddress: String,
    routeConfirmed: Boolean,
    table: List<FactoryCalibration> = FACTORY_CALIBRATIONS,
): FactoryCalibration? {
    if (micKind != MicKind.BuiltIn) return null
    val maker = build.manufacturer.trim()
    val model = build.model.trim()
    if (maker.isEmpty() || model.isEmpty()) return null

    val matches = table.filter { e ->
        e.manufacturer.trim().equals(maker, ignoreCase = true) &&
            e.model.trim().equals(model, ignoreCase = true) &&
            e.source == source &&
            e.offsetDb in PLAUSIBLE_OFFSET_RANGE &&
            when (val addr = e.routeAddress) {
                null -> true
                else -> routeConfirmed && routedAddress.isNotEmpty() && routedAddress == addr
            }
    }
    // 자리를 적은 항목이 먼저다. 더 좁게 말한 쪽이 더 많이 안다.
    return matches.firstOrNull { it.routeSpecific } ?: matches.firstOrNull()
}

/**
 * 이 기기의 보정값을 [FACTORY_CALIBRATIONS] 에 붙일 수 있는 코드로 적는다.
 *
 * **재는 것과 싣는 것 사이를 사람 손이 잇는다.** 그 사이에서 숫자 하나가
 * 틀리면 그 기종 전체가 틀리므로, 손으로 옮겨 적게 두지 않고 화면이
 * 그대로 복사할 줄을 준다.
 *
 * @param routeAddress 지금 자리. 비어 있으면 자리 줄을 `null` 로 적는다 —
 *   그러면 **사람이 그 자리에서 정말 자리를 안 가려도 되는지 판단해서**
 *   고쳐 적어야 한다. 앱이 대신 정하지 않는다.
 */
fun factoryEntrySnippet(
    build: DeviceBuildInfo,
    source: CaptureSource,
    routeAddress: String,
    cal: GlobalCalibration,
    measuredOn: String,
): String = buildString {
    appendLine("FactoryCalibration(")
    appendLine("    manufacturer = \"${build.manufacturer}\",")
    appendLine("    model = \"${build.model}\",")
    appendLine("    source = CaptureSource.${source.name},")
    if (routeAddress.isEmpty()) {
        appendLine("    routeAddress = null, // 자리를 확인하지 못했습니다 — 정말 안 가려도 되는지 보십시오")
    } else {
        appendLine("    routeAddress = \"$routeAddress\",")
    }
    appendLine("    offsetDb = ${"%.1f".format(cal.offsetDb)},")
    appendLine("    measuredOn = \"$measuredOn\",")
    appendLine("    referenceKo = \"${cal.source.labelKo} ${"%.1f".format(cal.referenceDb)}dB\",")
    appendLine("    byKo = \"\", // 누가 쟀는지 적으십시오")
    appendLine("    noteKo = \"\",")
    append("),")
}
