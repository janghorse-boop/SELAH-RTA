package kr.joa.selahrta.audio

import kr.joa.selahrta.domain.MicKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **USB 로 재는 동안에는 소리를 폰 스피커로 돌린다**(2026-09-30).
 *
 * 실기기에서 잰 것:
 *
 * ```
 * 출력 안 정함 → 실제 출력=UMC404HD, 입력 RMS=-240.0dBFS  (죽음)
 * 폰 스피커로  → 실제 출력=SM-S918N,  입력 RMS= -67.8dBFS  (삶)
 * ```
 */
class SignalOutputChoiceTest {

    @Test
    fun `USB 로 재면 폰 스피커로 돌린다`() {
        assertTrue(SignalOutputChoice.preferBuiltInSpeaker(MicKind.Usb))
    }

    /**
     * **내장 마이크로 재면 건드리지 않는다.**
     *
     * PA 로 신호를 넣고 폰으로 방을 재는 것은 **흔하고 쓸모 있는
     * 조합**이다. 그때 소리를 폰 스피커로 되돌리면 **쓸모를 없앤다.**
     */
    @Test
    fun `내장 마이크로 재면 건드리지 않는다`() {
        assertFalse(SignalOutputChoice.preferBuiltInSpeaker(MicKind.BuiltIn))
    }

    /** 안 열렸으면 건드리지 않는다 — 짐작해서 바꾸지 않는다. */
    @Test
    fun `안 열렸으면 건드리지 않는다`() {
        assertFalse(SignalOutputChoice.preferBuiltInSpeaker(null))
    }
}
