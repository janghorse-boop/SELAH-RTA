package kr.joa.selahrta.calibration

/**
 * **저장된 절대 SPL 교정을 아직 믿어도 되는가**(독립 검토 R06).
 *
 * ## 문제
 *
 * 교정은 「이 dBFS 가 저 dB SPL 이다」를 적어 둔 것이다. 그 대응은
 * **그때의 아날로그 이득에 묶여 있다.** UMC404HD 의 gain 노브를 돌리면
 * 같은 마이크·같은 채널인데도 대응이 통째로 어긋난다.
 *
 * 그런데 **안드로이드는 그 노브를 읽을 수 없다.** 읽을 수 있다고 가정한
 * 설계를 두면 안 된다는 것이 검토자의 지적이고, 맞는 말이다.
 *
 * ## 그래도 볼 수 있는 것: 잡음 바닥
 *
 * 노브를 돌리면 **전기 잡음도 함께 오르내린다.** 조용한 방에서 잰
 * dBFS 잡음 바닥은 그래서 이득의 그림자다. 교정할 때의 값을 적어 두고
 * 나중 값과 견주면, 노브가 움직였는지 **짐작할 수 있다.**
 *
 * **증거가 아니라 단서다.** 방이 시끄러워져도 같은 방향으로 움직이고,
 * 조용해지면 반대로 움직인다. 그래서 이 판정은 「바뀌었다」가 아니라
 * 「바뀌었을 수 있으니 확인하라」다.
 *
 * ## 무엇을 하지 않는가
 *
 * - **자동으로 교정을 지우지 않는다.** 단서로 지우면, 조용한 새벽에 잰
 *   것 하나로 멀쩡한 교정이 날아간다.
 * - **자동으로 보정하지 않는다.** 잡음 바닥 차이를 offset 에 더하는 것은
 *   틀렸다 — 방 소리와 이득을 구별하지 못하기 때문이다.
 */
enum class GainTrust {
    /** 견줄 것이 없다. 교정할 때 잡음을 적어 두지 않았다. */
    Unknown,

    /** 잡음 바닥이 그때와 비슷하다. 이득이 그대로일 가능성이 높다. */
    Consistent,

    /** 많이 다르다. **이득이 바뀌었거나 방이 달라졌다.** */
    Suspect,
}

data class GainVerdict(
    val trust: GainTrust,
    /** 지금 − 그때 (dB). 부호를 살린다 — 올랐는지 내렸는지가 단서다. */
    val driftDb: Double?,
    val noticeKo: String?,
)

/**
 * 교정할 때의 잡음 바닥과 지금 것을 견준다.
 *
 * @param savedNoiseDbfs 교정할 때 적어 둔 값. 없으면 [GainTrust.Unknown].
 * @param nowNoiseDbfs 방금 잰 값.
 * @param maxDriftDb 이보다 크게 벌어지면 의심한다. **실측으로 정한 값이
 *   아니다** — 방 소리만으로도 이만큼은 흔히 움직인다는 짐작이고, 그래서
 *   판정도 「확인하라」에서 멈춘다.
 */
fun judgeGainDrift(
    savedNoiseDbfs: Double?,
    nowNoiseDbfs: Double?,
    maxDriftDb: Double = 6.0,
): GainVerdict {
    if (savedNoiseDbfs == null || nowNoiseDbfs == null ||
        !savedNoiseDbfs.isFinite() || !nowNoiseDbfs.isFinite()
    ) {
        return GainVerdict(GainTrust.Unknown, null, GAIN_UNKNOWN_KO)
    }
    val drift = nowNoiseDbfs - savedNoiseDbfs
    return if (kotlin.math.abs(drift) <= maxDriftDb) {
        GainVerdict(GainTrust.Consistent, drift, null)
    } else {
        GainVerdict(GainTrust.Suspect, drift, gainSuspectKo(drift))
    }
}

/** 교정할 때 잡음을 안 적어 둔 경우. **없는 것을 괜찮은 것으로 치지 않는다.** */
const val GAIN_UNKNOWN_KO: String =
    "이 교정을 할 때의 입력 잡음을 적어 두지 않아, 그 뒤로 입력 이득이 " +
        "바뀌었는지 알 수 없습니다. 앱은 오디오 인터페이스의 gain 노브를 " +
        "읽을 수 없습니다 — 노브를 건드린 적이 있다면 다시 교정하십시오."

fun gainSuspectKo(driftDb: Double): String {
    val dir = if (driftDb > 0) "올랐습니다" else "내렸습니다"
    val head = "교정할 때보다 입력 잡음이 %.1fdB %s.".format(kotlin.math.abs(driftDb), dir)
    return head + " 입력 이득(gain 노브)이 바뀌었으면 지금 음압 값이 그만큼 " +
        "틀립니다. 방이 더 시끄러워졌거나 조용해졌을 뿐일 수도 있으니, 노브를 " +
        "건드린 적이 있는지 보고 필요하면 다시 교정하십시오."
}

/**
 * 교정과 함께 **늘 적어 두는 말**.
 *
 * 노브를 읽을 수 없다는 사실은 단서가 있든 없든 참이다. 문제가 생겼을
 * 때만 알리면, 그때까지는 앱이 알아서 지켜 주는 줄 안다.
 */
const val GAIN_NOT_READABLE_KO: String =
    "이 교정은 그때의 입력 이득에 묶여 있습니다. 앱은 오디오 인터페이스의 " +
        "gain 노브 위치를 읽을 수 없으니, 노브를 돌렸다면 다시 교정해야 " +
        "합니다."
