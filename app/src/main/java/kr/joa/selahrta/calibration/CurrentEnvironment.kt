package kr.joa.selahrta.calibration

import android.os.Build
import kr.joa.selahrta.audio.InputDeviceInfo
import kr.joa.selahrta.audio.OpenedFormat

/**
 * 기기·OS 신원(지시서 6장 「제조사·모델, Android/One UI 버전」).
 *
 * [android.os.Build] 를 바로 읽지 않고 값으로 들고 다니는 까닭은
 * **시험에서 정할 수 있어야** 하기 때문이다. `Build` 는 JVM 시험에서
 * 전부 `null` 이라, 바로 읽는 코드는 시험이 닿지 못한다.
 */
data class DeviceBuildInfo(
    val manufacturer: String,
    val model: String,
    /** `Build.DISPLAY`. OS 가 바뀌면 마이크 경로도 바뀔 수 있다. */
    val osBuild: String,
) {
    companion object {
        fun current(): DeviceBuildInfo = DeviceBuildInfo(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            osBuild = Build.DISPLAY.orEmpty(),
        )
    }
}

/**
 * **지금 실제로 열려 있는 경로**를 프로파일이 아는 꼴로 옮긴다.
 *
 * [judgeProfileApply] 가 견주는 「지금」이 이것이다. 요청한 값이 아니라
 * **열린** 값을 쓴다 — 48kHz 로 열어 달라고 해서 44.1kHz 로 열리는 일이
 * 흔하고, 그때 프로파일을 그대로 걸면 잰 적 없는 경로에 거는 셈이 된다.
 *
 * ## 주소는 목록에서 가져온다
 *
 * [OpenedFormat] 에는 주소가 없고 [OpenedFormat.deviceKey] 만 있다.
 * 주소는 **내장 마이크 둘을 가르는 유일한 단서**라(갤럭시 S23 은 둘 다
 * 이름이 모델명이다) 반드시 채워야 한다. 열쇠의 마지막 토막이 주소이긴
 * 하지만, 이름에 `|` 가 든 기기가 있으면 그 규칙이 깨진다. 그래서
 * **목록에서 같은 열쇠를 찾아** 그 기기의 주소를 쓰고, 목록에 없을
 * 때만 열쇠를 갈라 쓴다.
 */
fun currentProfileEnvironment(
    opened: OpenedFormat,
    inputs: List<InputDeviceInfo>,
    build: DeviceBuildInfo,
): ProfileEnvironment {
    // **열린 경로가 들고 있는 주소를 먼저 쓴다**(독립 재검토 CA-R03).
    //
    // 예전에는 목록에서 찾았는데, 목록은 내장 마이크를 한 줄로 접으며
    // 주소를 지운다. 그래서 언제나 빈 문자열이 들어갔고, 「자리가
    // 바뀌었는가」를 보는 검사가 통째로 작동하지 않았다.
    //
    // 열린 경로에도 없으면 목록을, 그것도 없으면 빈 문자열을 쓴다 —
    // **열쇠를 갈라 주소로 읽지 않는다.** 내장 열쇠의 마지막 토막은
    // 주소가 아니라 제품명이라, 그러면 제품명이 주소가 된다.
    val matched = inputs.firstOrNull { it.stableKey == opened.deviceKey }
    return ProfileEnvironment(
        deviceKey = opened.deviceKey,
        deviceAddress = opened.routedAddress.ifEmpty { matched?.address.orEmpty() },
        micKind = opened.micKind,
        audioSource = opened.audioSource,
        sampleRate = opened.sampleRate,
        channelCount = opened.channelCount,
        channelIndex = opened.channelIndex,
        manufacturer = build.manufacturer,
        model = build.model,
        osBuild = build.osBuild,
    )
}
