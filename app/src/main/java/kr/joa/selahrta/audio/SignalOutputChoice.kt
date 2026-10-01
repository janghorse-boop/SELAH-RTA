package kr.joa.selahrta.audio

import android.media.AudioDeviceInfo
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
/**
 * **소리가 나갈 자리 — 사람이 고른다.**
 *
 * 셋이 실제로 쓰인다(2026-10-01 담당자 지시).
 *
 * | 고름 | 어디로 | 쓰임 |
 * |---|---|---|
 * | 폰 스피커 | 폰 자체 | 방을 울려 마이크로 되받는다 |
 * | 3.5잭 · USB-C | 꽂힌 USB-C 기기의 출력 | **iMM-6C 의 Y 케이블**로 믹서에 넣는다 |
 * | 블루투스 | 폰이 붙은 블루투스 기기 | 폰 → 믹서·스피커를 선 없이 |
 *
 * iMM-6C 는 USB-C 로 마이크가 들어오고 **같은 케이블의 3.5잭으로 폰의
 * 출력이 나간다.** 그래서 재면서 동시에 PA 에 신호를 넣을 수 있다 —
 * 다만 **같은 USB 카드로 넣고 빼는 것**이라 아래의 맞바꿈이 걸린다.
 */
enum class SignalOutput(
    val labelKo: String,
    /** 칩에 들어갈 짧은 말. 긴 이름은 좁은 화면에서 한 자씩 쪼개진다. */
    val shortLabelKo: String,
    val helpKo: String,
    /** 이 고름이 찾는 출력 종류. **셋 다 제 자리가 있다.** */
    val kind: OutputKind,
) {

    /**
     * 폰 스피커. **기본값이다.**
     *
     * 방을 울려 제 마이크로 되받는 시험이 가장 흔하고, 무엇보다 **늘
     * 있다** — 안 꽂혀서 못 나가는 일이 없다. 그리고 USB 로 재는 동안에도
     * 같은 USB 카드로 넣고 빼지 않으므로 입력이 죽지 않는다.
     */
    BuiltInSpeaker(
        "폰 스피커",
        "폰 스피커",
        "폰 스피커로 내보냅니다. 방을 울려 마이크로 되받는 시험에 씁니다.",
        OutputKind.BuiltInSpeaker,
    ),

    /**
     * 3.5잭 · USB-C 로 꽂힌 기기의 출력.
     *
     * **iMM-6C 의 Y 케이블이 여기다** — 마이크는 USB-C 로 들어오고 3.5잭으로
     * 폰의 출력이 나가, 믹서 입력에 그대로 꽂힌다.
     */
    Wired(
        "3.5잭 · USB-C",
        "3.5잭",
        "꽂힌 3.5잭이나 USB-C 기기로 내보냅니다. iMM-6C 의 Y 케이블로 " +
            "믹서에 신호를 넣을 때 쓰십시오.",
        OutputKind.Wired,
    ),

    /** 폰이 붙은 블루투스 기기. */
    Bluetooth(
        "블루투스",
        "블루투스",
        "폰이 붙은 블루투스 기기로 내보냅니다. 믹서나 스피커에 선 없이 " +
            "넣을 때 쓰십시오. 블루투스는 지연이 있어 **시간에 민감한 " +
            "시험에는 맞지 않습니다.**",
        OutputKind.Bluetooth,
    ),
}

/**
 * 찾는 출력의 **종류**.
 *
 * 안드로이드의 기기 종류 번호를 화면까지 들고 다니지 않으려고 한 겹 둔다 —
 * 번호는 판마다 늘어나고, 화면이 그것을 알 까닭이 없다.
 */
enum class OutputKind(val labelKo: String) {
    BuiltInSpeaker("폰 스피커"),
    Wired("3.5잭 · USB-C"),
    Bluetooth("블루투스"),
}

object SignalOutputChoice {

    /**
     * 이 고름이 찾는 출력 종류.
     *
     * ## 「자동」과 「시스템」은 없앴다 (2026-10-01 담당자 지적)
     *
     * > 테스트 신호에서 자동과 시스템은 선택할 필요가 있는지?
     *
     * 없었다.
     *
     * - **시스템**은 「어디로 나가는지 모르겠다」는 뜻이다. 이 앱이 가장
     *   피해야 할 상태를 고르개로 둔 셈이었다.
     * - **자동**은 「USB 로 재면 폰 스피커」라는 **숨은 규칙**이었다. 셋이
     *   다 드러난 지금은 숨길 까닭이 없고, 그 규칙이 막던 사고(같은 USB 로
     *   넣고 빼면 입력이 무음)는 [usbDuplexRiskKo] 가 **말로** 알린다.
     *
     * 기본값을 **폰 스피커**로 두면 그 사고도 그대로 막힌다 — 같은 USB
     * 카드로 넣고 빼지 않기 때문이다.
     *
     * @param choice 사람이 고른 것. **조용히 덮지 않는다**(독립 검토 R5-04).
     */
    fun wantedKind(choice: SignalOutput): OutputKind = choice.kind

    /**
     * 안드로이드 기기 종류 번호가 이 종류에 드는가.
     *
     * **번호를 늘어놓는 까닭**: `AudioDeviceInfo` 의 종류는 한 가지가 아니다.
     * USB 만 해도 헤드셋·기기·액세서리 셋이고, 블루투스는 A2DP 와 LE 가
     * 따로다. 하나만 보면 **꽂혀 있는데 없다고 말한다.**
     *
     * 3.5잭과 USB-C 를 **한 종류로 묶는다.** 쓰는 사람에게는 「선으로
     * 내보낸다」 하나이고, iMM-6C 처럼 USB-C 에 3.5잭이 달린 케이블은
     * 둘을 가를 수도 없다.
     */
    fun matches(kind: OutputKind, androidType: Int): Boolean = when (kind) {
        OutputKind.BuiltInSpeaker ->
            androidType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

        OutputKind.Wired -> androidType in setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )

        OutputKind.Bluetooth -> androidType in setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
    }

    /**
     * **같은 USB 카드로 넣고 빼려는가** — 그러면 입력이 죽을 수 있다.
     *
     * 실기기에서 쟀다(SM-S918N + UMC404HD): 같은 카드로 동시에 넣고 빼면
     * 입력이 **완전한 디지털 무음**이 됐다.
     *
     * **iMM-6C 의 Y 케이블이 정확히 그 모양이다** — 마이크도 USB-C, 출력도
     * 그 USB-C 다. 그래서 「3.5잭」을 고르고 USB 로 재면 미리 알린다.
     *
     * **막지는 않는다.** 믹서에 신호를 넣으며 재는 것은 쓸모 있는 쓰임이고,
     * 기기마다 되기도 한다 — 되는지 아닌지는 **실제 입력 레벨**이 말한다.
     */
    fun usbDuplexRiskKo(choice: SignalOutput, capturingFrom: MicKind?): String? =
        if (choice == SignalOutput.Wired && capturingFrom == MicKind.Usb) {
            "같은 USB 기기로 넣고 빼는 셈입니다. 그러면 입력이 무음이 되는 " +
                "기기가 있습니다(실기기에서 겪었습니다) — 재는 값이 0 에 " +
                "붙으면 「폰 스피커」로 바꿔 보십시오."
        } else {
            null
        }
}
