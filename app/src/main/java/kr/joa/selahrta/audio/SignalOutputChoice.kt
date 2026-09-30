package kr.joa.selahrta.audio

import kr.joa.selahrta.domain.MicKind

/**
 * **소리를 어디로 내보낼 것인가**(2026-09-30, 독립 검토 R5-04).
 *
 * ## 왜 고르게 하는가
 *
 * USB 오디오 인터페이스를 꽂으면 안드로이드가 **출력도 그쪽으로** 보낸다.
 * 그런데 **같은 USB 카드로 동시에 넣고 빼면 입력이 완전한 디지털 무음이
 * 되는** 기기가 있다. 실기기에서 쟀다(SM-S918N + UMC404HD):
 *
 * ```
 * 출력 안 정함 → 실제 출력=UMC404HD, 입력 RMS=-240.0dBFS  (죽음)
 * 폰 스피커로  → 실제 출력=SM-S918N,  입력 RMS= -67.8dBFS  (삶)
 * ```
 *
 * 그래서 우회를 넣었는데, **처음에는 「USB 로 재면 무조건 폰 스피커」로
 * 못박았다.** 검토가 그것을 되돌렸다 — **기기 하나로 본 것을 모든 USB 의
 * 정책으로 넓힌 것**이고, 무엇보다 **사람이 고른 출력을 조용히 덮었다.**
 *
 * 실제로 뜻이 달라지는 쓰임이 있다: **PA 로 신호를 넣고 USB 마이크로
 * 재려던** 경우다. 그 사람에게는 폰 스피커에서 소리가 나는 것이 고장이다.
 *
 * ## 그래서 셋 중에 고른다
 *
 * 기본값은 [SignalOutput.Auto] 로 둔다. **기본값으로도 우회가 걸린다** —
 * 우회를 끄면 그 조합에서 **아무것도 못 잰 채로 마법사가 넘어가기**
 * 때문이다. 다만 이제 **화면에 무엇으로 나가는지 적고**, 언제든 바꾼다.
 *
 * ## 무엇을 모르는가
 *
 * - **왜 그런지 모른다.** 안드로이드 쪽인지 기기 쪽인지 가리지 않았다.
 * - **기기 하나로 본 것이다.** 다른 인터페이스도 그런지 모른다. 그래서
 *   [SignalOutput.Auto] 의 조건을 「USB 면」으로 넓게 둘 수밖에 없었다.
 * - **고른 것이 그대로 되는지는 따로 본다.** `setPreferredDevice` 는
 *   **요청**이다 — 실제 경로는 [AudioTrackSink] 가 재서 화면에 적는다.
 */
enum class SignalOutput(
    val labelKo: String,
    /** 칩에 들어갈 짧은 말. 긴 이름은 좁은 화면에서 한 자씩 쪼개진다. */
    val shortLabelKo: String,
    val helpKo: String,
) {

    /** USB 로 **재는 동안에만** 폰 스피커로 돌린다. */
    Auto(
        "자동 — USB 로 잴 때만 폰 스피커",
        "자동",
        "USB 인터페이스로 재는 동안에는 폰 스피커로 내보냅니다. " +
            "같은 카드로 동시에 넣고 빼면 입력이 무음이 되는 기기가 있어서입니다.",
    ),

    /** 안드로이드가 고른 곳으로 그냥 내보낸다. */
    SystemDefault(
        "시스템이 고른 곳",
        "시스템",
        "안드로이드가 고른 출력으로 그대로 내보냅니다. PA 로 신호를 넣고 " +
            "USB 마이크로 재려면 이쪽입니다 — 다만 기기에 따라 입력이 " +
            "무음이 될 수 있습니다.",
    ),

    /** 무엇으로 재든 폰 스피커로 내보낸다. */
    BuiltInSpeaker(
        "언제나 폰 스피커",
        "폰 스피커",
        "무엇으로 재든 폰 스피커로 내보냅니다.",
    ),
}

object SignalOutputChoice {

    /**
     * 출력을 **폰 스피커로 돌려야 하는가.**
     *
     * @param choice 사람이 고른 것. **조용히 덮지 않는다**(독립 검토 R5-04).
     * @param capturingFrom 지금 재고 있는 입력의 종류. 안 열렸으면 null.
     */
    fun preferBuiltInSpeaker(choice: SignalOutput, capturingFrom: MicKind?): Boolean =
        when (choice) {
            SignalOutput.SystemDefault -> false
            SignalOutput.BuiltInSpeaker -> true
            // **재는 쪽이 USB 일 때만** 돌린다. 내장 마이크로 재면서
            // 인터페이스로 소리를 내보내는 것은 **흔하고 쓸모 있는 조합**이다
            // — PA 로 신호를 넣고 폰으로 방을 재는 경우다.
            SignalOutput.Auto -> capturingFrom == MicKind.Usb
        }
}
