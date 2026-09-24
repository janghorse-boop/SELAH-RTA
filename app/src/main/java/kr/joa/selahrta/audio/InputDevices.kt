package kr.joa.selahrta.audio

import android.media.AudioDeviceInfo
import kr.joa.selahrta.domain.MicKind

/**
 * 입력 기기 하나. **안드로이드 자료형을 쓰지 않는다** —
 * 고르는 규칙을 기기 없이 시험할 수 있어야 하기 때문이다.
 */
data class InputDeviceInfo(
    /** AudioDeviceInfo.getId(). 기기를 꽂았다 빼면 바뀔 수 있다. */
    val id: Int,
    /** 사람이 읽는 이름. */
    val productName: String,
    val kind: MicKind,
    /** 좀 더 자세한 종류. 화면에 적는 데 쓴다. */
    val typeKo: String,
    /** 기기가 알려주는 샘플레이트 목록. 비어 있으면 「모름」이다. */
    val sampleRates: List<Int> = emptyList(),
    /**
     * 안드로이드가 주는 주소. 내장 마이크를 구별하는 유일한 단서다.
     *
     * 갤럭시 S23 은 내장 마이크를 둘 노출하는데(실측: addr='bottom', 'back')
     * 이름이 둘 다 기기 모델명이라 이것 없이는 구별되지 않는다.
     */
    val address: String = "",
    /**
     * 기기가 **열 수 있다고 알리는** 채널 수 목록. 비어 있으면 「모름」이다.
     *
     * **열리는 값이 아니다.** 오디오 인터페이스가 4 를 알려도 안드로이드가
     * 2 로 열어 주는 일이 흔하다. 화면은 이 값으로 고를 수 있는 것을 그리되,
     * 재는 것은 [OpenedFormat.channelCount] 를 따른다(USB 오디오 지시서 5장:
     * 「4채널을 하드코딩하지 않는다」).
     */
    val channelCounts: List<Int> = emptyList(),
) {
    /**
     * 기기를 다시 찾을 때 쓰는 열쇠.
     *
     * **id 로 기억하면 안 된다.** USB 를 뺐다 꽂으면 id 가 바뀌어서
     * 「고른 기기가 사라졌다」고 잘못 판단한다.
     *
     * **외부 기기는 주소도 넣는다.** 같은 이름의 인터페이스를 둘 꽂으면
     * 주소만이 둘을 가른다.
     *
     * ## 내장 마이크는 주소를 빼고 묶는다 (2026-09-23)
     *
     * 예전에는 내장도 주소를 넣어 하단·상단을 다른 기기로 다뤘다. 실측이
     * 그 전제를 무너뜨렸다 — 「상단」을 골라도 하단이 함께 켜지고, 주소가
     * 가리키는 위치조차 실제와 다르다([InputDeviceScanner] 의 접는 규칙).
     *
     * **이 한 줄이 실제로 하는 일은 보정값을 붙들어 두는 것이다.** 열쇠에
     * 주소가 남아 있으면 라우팅이 하단에서 상단으로 한 번 넘어가는 것만으로
     * 「모르는 기기」가 되어 **저장된 보정이 조용히 떨어져 나간다.**
     */
    val stableKey: String get() = when (kind) {
        MicKind.BuiltIn -> "${kind.name}|$productName"
        else -> "${kind.name}|$productName|$address"
    }

    /**
     * 화면에 적을 이름.
     *
     * 내장 마이크는 **기기명 그대로**다. 위치를 붙이지 않는다 — 고를 수도
     * 없고 이름도 못 믿는 구분이라(위 참고) 붙이면 잘못 고르게 할 뿐이다.
     * 외부 기기는 주소가 둘을 가르는 단서라 그대로 붙인다.
     */
    val displayName: String
        get() = when (kind) {
            MicKind.BuiltIn -> productName
            else -> micPositionKo(address)?.let { "$productName ($it)" } ?: productName
        }

    /**
     * **탐색 전용** 열쇠. 내장 마이크도 주소까지 넣어 후보를 가른다.
     *
     * [stableKey] 는 내장을 한 줄로 묶으므로 후보 둘이 같은 값을 갖는다.
     * 그 값으로 「고른 것과 열린 것이 같은가」를 물으면 **언제나 같다**가
     * 되어, 탐색이 아무것도 묻지 않은 것과 같아진다.
     *
     * 보정·기억에는 쓰지 않는다 — 그쪽은 묶여 있어야 한다([stableKey]).
     */
    val probeKey: String get() = "${kind.name}|$productName|$address"

    /** 탐색 보고서에 적을 이름. 어느 후보였는지 알아볼 수 있어야 한다. */
    val probeLabel: String
        get() = if (address.isBlank()) productName else "$productName [$address]"

    /** 48kHz 로 열 수 있다고 기기가 알리는가. 모르면 true 로 본다(해 보면 안다). */
    val supports48k: Boolean get() = sampleRates.isEmpty() || sampleRates.contains(48_000)
}

/**
 * 안드로이드의 입력 기기 종류를 우리 분류로 옮긴다.
 *
 * USB 만 외부로 치지 않는다. 유선 헤드셋과 블루투스도 **내장이 아닌 입력**이며
 * 감도가 전혀 다르므로 보정값을 따로 가져야 한다(명세 8장).
 */
fun classifyInput(type: Int): Pair<MicKind, String>? = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> MicKind.BuiltIn to "내장 마이크"
    AudioDeviceInfo.TYPE_USB_DEVICE -> MicKind.Usb to "USB 오디오 기기"
    AudioDeviceInfo.TYPE_USB_HEADSET -> MicKind.Usb to "USB 헤드셋"
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> MicKind.Usb to "USB 액세서리"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> MicKind.Usb to "유선 헤드셋"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> MicKind.Usb to "블루투스 헤드셋"
    // 나머지(FM 튜너, 텔레포니 등)는 음향 측정에 쓸 수 있는 입력이 아니다.
    else -> null
}

/**
 * 안드로이드 주소를 사람 말로 옮긴다.
 *
 * 「bottom」을 그대로 띄우면 담당자가 무슨 뜻인지 알 수 없다. 모르는
 * 주소는 그대로 보여 준다 — 추측해서 엉뚱한 이름을 붙이는 것보다 낫다.
 */
fun micPositionKo(address: String): String? = when (address.lowercase()) {
    "" -> null
    "bottom" -> "하단"
    "top" -> "상단"
    "back" -> "후면"
    "front" -> "전면"
    else -> address
}

/** USB 분리 시 어떻게 할 것인가(명세 2장). */
enum class DisconnectPolicy(val labelKo: String, val helpKo: String) {
    /**
     * 내장 마이크로 갈아타고 계속 잰다.
     *
     * 예배 도중에 케이블이 빠져도 측정이 끊기지 않는다. 대신 **그 시점부터
     * 다른 마이크·다른 보정값**이라 값이 갑자기 달라진다 — 반드시 기록에 남기고
     * 화면에도 알려야 한다.
     */
    FallBack("내장 마이크로 전환", "측정이 끊기지 않지만 그 뒤 값은 다른 마이크의 값입니다."),

    /**
     * 멈추고 기다린다.
     *
     * 측정값의 일관성을 지킨다. 다시 꽂으면 이어서 잴 수 있다.
     */
    Pause("멈추고 기다리기", "값이 섞이지 않지만 다시 꽂을 때까지 재지 않습니다."),
}

/**
 * 어느 기기로 열 것인가를 정한다. **순수 함수다.**
 *
 * @param available 지금 쓸 수 있는 입력들
 * @param preferredKey 사용자가 골라 둔 기기([InputDeviceInfo.stableKey]).
 *   null 이면 **내장 마이크**로 연다.
 *
 * ## 「자동으로 고르기」를 없앴다 (2026-09-24 담당자 지시)
 *
 * 예전에는 외부 기기가 꽂히면 알아서 그쪽으로 갈아타는 설정이 있었다.
 * 담당자 판단 — **불필요하다.** 고르는 것은 사람이 하고, 고른 것이
 * 없거나 빠졌으면 내장으로 연다. 그것이 당연한 동작이다.
 *
 * 자동 전환은 값을 조용히 바꾸는 쪽이라 위험하기도 했다. 재는 도중에
 * 기기가 꽂히면 그 자리에서 측정을 끊고 새로 시작해야 했는데, 그 복잡함이
 * 전부 이 설정 하나에서 나왔다.
 */
fun chooseInput(
    available: List<InputDeviceInfo>,
    preferredKey: String?,
    /**
     * 「내장 마이크로 전환」 정책으로 다시 시작하는 중인가.
     *
     * 켜져 있으면 고른 기기를 보지 않고 내장을 고른다.
     * 그러지 않으면 외부 마이크가 하나 더 꽂혀 있을 때 그쪽으로 열려,
     * 정책 이름과 다른 일이 벌어진다(독립 검증 R11).
     */
    disconnectFallBack: Boolean = false,
): InputChoice {
    if (available.isEmpty()) return InputChoice(null, ChoiceReason.NoDevice)

    if (disconnectFallBack) {
        val builtIn = available.firstOrNull { it.kind == MicKind.BuiltIn }
        // 내장이 아예 없는 기기도 있다. 그때는 남은 것 중 하나로 연다.
        return InputChoice(builtIn ?: available.first(), ChoiceReason.DisconnectFallBack)
    }

    // 고른 기기가 지금도 있으면 그것을 쓴다. 사용자의 선택이 가장 앞선다.
    if (preferredKey != null) {
        val exact = available.firstOrNull { it.stableKey == preferredKey }
        if (exact != null) return InputChoice(exact, ChoiceReason.UserPicked)
        // 골라 둔 기기가 사라졌다. 조용히 다른 것으로 바꾸지 않고 그 사실을 알린다.
        return InputChoice(builtInFirst(available), ChoiceReason.PreferredMissing)
    }

    return InputChoice(builtInFirst(available), ChoiceReason.Auto)
}

/** 내장 마이크. 없는 기기도 있으므로 그때는 남은 것 중 하나. */
private fun builtInFirst(available: List<InputDeviceInfo>): InputDeviceInfo =
    available.firstOrNull { it.kind == MicKind.BuiltIn } ?: available.first()

data class InputChoice(val device: InputDeviceInfo?, val reason: ChoiceReason)

enum class ChoiceReason {
    /** 사용자가 고른 기기를 그대로 쓴다. */
    UserPicked,

    /** 사용자가 고르지 않아 규칙대로 골랐다. */
    Auto,

    /** 골라 둔 기기가 지금 없어서 다른 것으로 열었다. 화면에 알려야 한다. */
    PreferredMissing,

    /** 쓸 수 있는 입력이 없다. */
    NoDevice,

    /**
     * 쓰던 기기가 빠져 「내장 마이크로 전환」 정책대로 내장을 골랐다.
     *
     * 이때는 사용자가 골라 둔 기기를 따르지 않는다 —
     * 정책 이름이 「내장 마이크로 전환」인데 다른 외부 마이크를 고르면
     * 적힌 것과 다른 일을 하는 것이다(독립 검증 R11).
     */
    DisconnectFallBack,
    ;

    fun noticeKo(chosen: InputDeviceInfo?): String? = when (this) {
        PreferredMissing ->
            "골라 두신 마이크가 지금 연결돼 있지 않아 " +
                "${chosen?.productName ?: "다른 마이크"}로 잽니다. " +
                "보정값도 그 마이크의 것으로 바뀝니다."
        DisconnectFallBack ->
            "${chosen?.productName ?: "내장 마이크"}로 다시 시작했습니다. " +
                "여기서부터는 다른 마이크·다른 보정값의 값입니다."
        NoDevice -> "쓸 수 있는 입력 기기가 없습니다."
        else -> null
    }
}
