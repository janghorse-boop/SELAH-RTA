package kr.joa.selahrta.audio

import kr.joa.selahrta.domain.MicKind

/**
 * 이만큼 내내 조용했으면 「신호 없음」이라고 말한다(ms).
 *
 * 설교 중 숨 쉬는 사이나 기도 중의 고요함이 여기 걸리면 안 된다. 3초는
 * 그보다 길고, 케이블이 빠졌을 때 알아차리기에는 충분히 짧다.
 *
 * **재서 고른 값이 아니다.**
 */
const val NO_SIGNAL_HOLD_MS: Long = 3_000L

/**
 * 입력이 지금 어떤 상태인가. **측정값이 아니라 입력 그 자체**를 말한다.
 *
 * USB 오디오 지시서 6·7·14장이 요구한 안내의 바탕이다.
 */
enum class InputSignalState {
    /** 잘리고 있다. 값을 믿을 수 없다. */
    Clipping,

    /** 아무것도 안 들어온다. 케이블·팬텀전원·GAIN 을 볼 자리다. */
    NoSignal,

    /** 평범하다. 할 말이 없다. */
    Ok,
}

/**
 * **자르는 쪽이 먼저다.** 둘 다 참일 수는 없지만(잘리면서 무음일 수
 * 없다) 순서를 정해 두면 읽는 쪽이 헷갈리지 않는다.
 */
fun inputSignalState(clipping: Boolean, noSignal: Boolean): InputSignalState = when {
    clipping -> InputSignalState.Clipping
    noSignal -> InputSignalState.NoSignal
    else -> InputSignalState.Ok
}

/**
 * 사람에게 할 말. 없으면 null.
 *
 * ## 왜 기기 종류를 보는가
 *
 * 지시서의 문구는 **오디오 인터페이스를 쓰는 사람**에게 한 말이다 —
 * 「UMC404HD 의 GAIN 을 낮춰주세요」, 「+48V 팬텀전원을 확인해주세요」.
 * 내장 마이크에는 **GAIN 노브도 팬텀전원도 없다.** 그대로 띄우면 찾을
 * 수 없는 것을 찾게 만든다.
 *
 * 그래서 같은 상태라도 말이 다르다. **기기 이름을 하드코딩하지 않는다** —
 * 「UMC404HD」가 아니라 실제로 열린 기기 이름을 쓴다(지시서 19장 3).
 */
fun inputSignalNoticeKo(
    state: InputSignalState,
    micKind: MicKind,
    deviceLabel: String,
): String? = when (state) {
    InputSignalState.Ok -> null

    InputSignalState.Clipping -> if (micKind == MicKind.BuiltIn) {
        "입력 신호가 잘리고 있습니다. 소리가 너무 커서 이 마이크가 담을 수 있는 " +
            "범위를 넘었습니다 — 이 구간의 값은 실제보다 낮게 나옵니다. " +
            "마이크를 소리에서 떨어뜨리십시오."
    } else {
        "입력 신호가 클리핑되고 있습니다. $deviceLabel 의 GAIN 을 낮춰주세요. " +
            "잘린 구간은 값이 실제보다 낮게 나오고 없던 고조파가 생깁니다."
    }

    InputSignalState.NoSignal -> if (micKind == MicKind.BuiltIn) {
        "입력 신호가 감지되지 않습니다. 다른 앱이 마이크를 쓰고 있거나 " +
            "마이크가 막혀 있는지 확인하십시오."
    } else {
        "입력 신호가 감지되지 않습니다. 마이크 연결, $deviceLabel 의 +48 V " +
            "팬텀전원 및 GAIN 을 확인해주세요."
    }
}
