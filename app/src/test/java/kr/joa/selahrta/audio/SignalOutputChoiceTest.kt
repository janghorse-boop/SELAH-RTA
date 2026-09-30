package kr.joa.selahrta.audio

import kr.joa.selahrta.domain.MicKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **소리를 어디로 내보낼지는 사람이 고른다**(독립 검토 R5-04).
 *
 * 처음에는 「USB 로 재면 무조건 폰 스피커」로 못박았다. 실기기에서 잰
 * 것이 근거였다:
 *
 * ```
 * 출력 안 정함 → 실제 출력=UMC404HD, 입력 RMS=-240.0dBFS  (죽음)
 * 폰 스피커로  → 실제 출력=SM-S918N,  입력 RMS= -67.8dBFS  (삶)
 * ```
 *
 * 그러나 그것은 **기기 하나로 본 것**이고, 무엇보다 **사람이 고른 출력을
 * 조용히 덮었다.** PA 로 신호를 넣고 USB 마이크로 재려던 사람에게는
 * 폰 스피커에서 소리가 나는 것이 고장이다.
 *
 * **기본값은 그대로 우회다.** 끄면 그 조합에서 아무것도 못 잰 채로
 * 마법사가 넘어간다 — 다만 이제 **덮지 않고 고르게 한다.**
 */
class SignalOutputChoiceTest {

    // ── 자동: 재는 쪽이 USB 일 때만 ──────────────────────

    @Test
    fun `자동은 USB 로 재면 폰 스피커로 돌린다`() {
        assertTrue(SignalOutputChoice.preferBuiltInSpeaker(SignalOutput.Auto, MicKind.Usb))
    }

    /**
     * **내장 마이크로 재면 건드리지 않는다.**
     *
     * PA 로 신호를 넣고 폰으로 방을 재는 것은 **흔하고 쓸모 있는
     * 조합**이다. 그때 소리를 폰 스피커로 되돌리면 **쓸모를 없앤다.**
     */
    @Test
    fun `자동은 내장 마이크로 재면 건드리지 않는다`() {
        assertFalse(SignalOutputChoice.preferBuiltInSpeaker(SignalOutput.Auto, MicKind.BuiltIn))
    }

    /** 안 열렸으면 건드리지 않는다 — 짐작해서 바꾸지 않는다. */
    @Test
    fun `자동은 안 열렸으면 건드리지 않는다`() {
        assertFalse(SignalOutputChoice.preferBuiltInSpeaker(SignalOutput.Auto, null))
    }

    /** **기본값이 자동이다.** 기본값이 바뀌면 그 조합에서 다시 무음이 된다. */
    @Test
    fun `기본값은 자동이다`() {
        assertTrue(kr.joa.selahrta.settings.MeterSettings().signalOutput == SignalOutput.Auto)
    }

    // ── 고른 것은 덮지 않는다 ───────────────────────────

    /**
     * **「시스템이 고른 곳」을 골랐으면 USB 여도 안 돌린다.**
     *
     * 이것이 이번 회차의 요점이다. 우회가 필요 없는 기기이거나, PA 로
     * 신호를 넣으려는 사람이 **일부러** 고른 것이다.
     */
    @Test
    fun `시스템을 골랐으면 USB 라도 안 돌린다`() {
        assertFalse(
            SignalOutputChoice.preferBuiltInSpeaker(SignalOutput.SystemDefault, MicKind.Usb),
        )
    }

    /** **「언제나 폰 스피커」는 내장 마이크로 재도 돌린다.** */
    @Test
    fun `폰 스피커를 골랐으면 내장 마이크로 재도 돌린다`() {
        assertTrue(
            SignalOutputChoice.preferBuiltInSpeaker(SignalOutput.BuiltInSpeaker, MicKind.BuiltIn),
        )
    }

    /** 고른 것은 **입력이 안 열려 있어도** 그대로 간다. */
    @Test
    fun `고른 것은 입력과 무관하게 간다`() {
        assertFalse(SignalOutputChoice.preferBuiltInSpeaker(SignalOutput.SystemDefault, null))
        assertTrue(SignalOutputChoice.preferBuiltInSpeaker(SignalOutput.BuiltInSpeaker, null))
    }

    /** 고르개마다 **사람이 읽을 설명**이 있어야 한다. 「자동」만으로는 못 고른다. */
    @Test
    fun `고르개마다 설명이 있다`() {
        for (o in SignalOutput.entries) {
            assertTrue("${o.name} 설명이 없다", o.helpKo.length > 10)
            assertTrue("${o.name} 짧은 이름이 없다", o.shortLabelKo.isNotBlank())
        }
    }
}
