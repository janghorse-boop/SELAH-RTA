package kr.joa.selahrta.audio

import android.media.AudioDeviceInfo
import kr.joa.selahrta.domain.MicKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **소리를 어디로 내보낼지는 사람이 고른다**(독립 검토 R5-04, 2026-10-01 확장).
 *
 * ## 실제로 쓰이는 길이 셋이다
 *
 * 담당자가 쓰는 장비로 정리하면 이렇다:
 *
 * - **폰 스피커** — 방을 울려 제 마이크로 되받는다.
 * - **3.5잭 · USB-C** — **iMM-6C 의 Y 케이블**. 마이크는 USB-C 로 들어오고
 *   같은 케이블의 3.5잭으로 폰의 출력이 나가 **믹서 입력**에 꽂힌다.
 * - **블루투스** — 폰이 믹서·스피커에 선 없이 붙는다.
 *
 * ## 고른 것을 덮지 않는다
 *
 * 처음에는 「USB 로 재면 무조건 폰 스피커」로 못박았다. 실기기에서 잰 것이
 * 근거였다 — 같은 USB 카드로 동시에 넣고 빼면 입력이 **완전한 디지털
 * 무음**이 된다(SM-S918N + UMC404HD). 그러나 그것은 **기기 하나로 본
 * 것**이고, 무엇보다 **PA 로 신호를 넣으며 재려던 사람에게는 고장**이다.
 *
 * 그래서 기본값(자동)만 그 우회를 하고, 나머지는 고른 대로 간다.
 */
class SignalOutputChoiceTest {

    // ── 고른 것이 곧 나갈 자리다 ────────────────────────

    /**
     * **「자동」과 「시스템」은 없앴다**(2026-10-01 담당자 지적:
     * 「테스트 신호에서 자동과 시스템은 선택할 필요가 있는지?」).
     *
     * 시스템은 「어디로 나가는지 모르겠다」는 뜻이고, 자동은 「USB 로 재면
     * 폰 스피커」라는 **숨은 규칙**이었다. 셋이 다 드러난 지금은 둘 다
     * 쓸모가 없다.
     */
    @Test
    fun `고르개는 셋뿐이다`() {
        assertEquals(
            listOf(SignalOutput.BuiltInSpeaker, SignalOutput.Wired, SignalOutput.Bluetooth),
            SignalOutput.entries.toList(),
        )
    }

    /** 고른 것이 **그대로** 나갈 자리다. 가운데서 바꾸는 규칙이 없다. */
    @Test
    fun `고른 것이 그대로 나갈 자리다`() {
        assertEquals(
            OutputKind.BuiltInSpeaker,
            SignalOutputChoice.wantedKind(SignalOutput.BuiltInSpeaker),
        )
        assertEquals(OutputKind.Wired, SignalOutputChoice.wantedKind(SignalOutput.Wired))
        assertEquals(OutputKind.Bluetooth, SignalOutputChoice.wantedKind(SignalOutput.Bluetooth))
    }

    /**
     * **기본값은 폰 스피커다.**
     *
     * 늘 있고(안 꽂혀서 못 나가는 일이 없다), 같은 USB 카드로 넣고 빼지
     * 않아 **입력이 죽지 않는다** — 「자동」이 막던 사고가 그대로 막힌다.
     */
    @Test
    fun `기본값은 폰 스피커다`() {
        assertEquals(
            SignalOutput.BuiltInSpeaker,
            kr.joa.selahrta.settings.MeterSettings().signalOutput,
        )
    }

    // ── 어떤 기기가 그 자리에 드는가 ────────────────────

    /**
     * **3.5잭과 USB-C 를 한 종류로 묶는다.**
     *
     * 쓰는 사람에게는 「선으로 내보낸다」 하나이고, iMM-6C 처럼 USB-C 에
     * 3.5잭이 달린 케이블은 둘을 가를 수도 없다.
     */
    @Test
    fun `유선은 3_5잭과 USB 를 다 받는다`() {
        for (t in listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )) {
            assertTrue("$t 를 유선으로 안 봤다", SignalOutputChoice.matches(OutputKind.Wired, t))
        }
    }

    /**
     * **블루투스는 한 가지가 아니다.** A2DP 만 보면 LE 로 붙은 기기를
     * 「없다」고 말한다 — 그러면 꽂혀 있는데 안 꽂혔다고 적는다.
     */
    @Test
    fun `블루투스는 A2DP 와 LE 를 다 받는다`() {
        for (t in listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )) {
            assertTrue(
                "$t 를 블루투스로 안 봤다",
                SignalOutputChoice.matches(OutputKind.Bluetooth, t),
            )
        }
    }

    /** **아무거나 받지는 않는다.** 섞이면 엉뚱한 자리로 못박는다. */
    @Test
    fun `종류가 서로 섞이지 않는다`() {
        assertTrue(
            SignalOutputChoice.matches(
                OutputKind.BuiltInSpeaker,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            ),
        )
        assertFalse(
            SignalOutputChoice.matches(
                OutputKind.BuiltInSpeaker,
                AudioDeviceInfo.TYPE_USB_HEADSET,
            ),
        )
        assertFalse(
            SignalOutputChoice.matches(OutputKind.Wired, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
        )
        assertFalse(
            SignalOutputChoice.matches(OutputKind.Bluetooth, AudioDeviceInfo.TYPE_USB_DEVICE),
        )
    }

    // ── 같은 USB 로 넣고 빼는 것을 미리 알린다 ──────────

    /**
     * **iMM-6C 의 Y 케이블이 정확히 그 모양이다** — 마이크도 USB-C, 출력도
     * 그 USB-C 다. 실기기에서 입력이 죽는 것을 봤으니 미리 알린다.
     */
    @Test
    fun `USB 로 재면서 유선으로 내보내면 알린다`() {
        val say = SignalOutputChoice.usbDuplexRiskKo(SignalOutput.Wired, MicKind.Usb)
        assertTrue("$say", say != null && say.contains("무음"))
    }

    /** **막지는 않는다.** 알리는 말일 뿐이고, 고름 자체는 그대로 간다. */
    @Test
    fun `알려도 고른 자리는 그대로다`() {
        assertEquals(OutputKind.Wired, SignalOutputChoice.wantedKind(SignalOutput.Wired))
    }

    /** **아무 때나 겁주지 않는다.** 내장 마이크로 재면 그 걱정이 없다. */
    @Test
    fun `내장으로 재면 알리지 않는다`() {
        assertNull(SignalOutputChoice.usbDuplexRiskKo(SignalOutput.Wired, MicKind.BuiltIn))
        assertNull(SignalOutputChoice.usbDuplexRiskKo(SignalOutput.BuiltInSpeaker, MicKind.Usb))
        assertNull(SignalOutputChoice.usbDuplexRiskKo(SignalOutput.Bluetooth, MicKind.Usb))
    }

    // ── 사람이 읽을 말 ──────────────────────────────────

    /** 고르개마다 **사람이 읽을 설명**이 있어야 한다. 「자동」만으로는 못 고른다. */
    @Test
    fun `고르개마다 설명이 있다`() {
        for (o in SignalOutput.entries) {
            assertTrue("${o.name} 설명이 없다", o.helpKo.length > 10)
            assertTrue("${o.name} 짧은 이름이 없다", o.shortLabelKo.isNotBlank())
        }
    }
}
